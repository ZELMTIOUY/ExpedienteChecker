package com.zakaria.bot;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;

/**
 * ExpedienteChecker — Automatización para consultar el estado de expediente en Infoext2.
 *
 * El CAPTCHA de este formulario es una imagen de texto distorsionado (no una operación
 * aritmética), así que el bot NO intenta resolverlo automáticamente. En su lugar:
 *   1. Rellena el formulario.
 *   2. Envía la imagen del CAPTCHA a tu Telegram.
 *   3. Espera tu respuesta (el texto que ves en la imagen) durante un tiempo limitado.
 *   4. Introduce esa respuesta y envía el formulario.
 *   5. Te notifica el resultado.
 *
 * Variables de entorno REQUERIDAS (configúralas como GitHub Secrets — no hay valores
 * por defecto embebidos en el código, por seguridad):
 *   MY_NIE            → Tu NIE (ej. Z1017349H)
 *   FECHA_SOLICITUD   → Fecha DD/MM/YYYY (ej. 30/07/2026)
 *   ANIO_NACIMIENTO   → Año YYYY (ej. 1996)
 *   TELEGRAM_TOKEN    → Token del bot de Telegram
 *   CHAT_ID           → Tu Chat ID de Telegram
 *
 * Variables opcionales:
 *   CAPTCHA_WAIT_MINUTES → minutos a esperar tu respuesta (por defecto 5)
 */
public class ExpedienteChecker {

    // ── CONFIG ────────────────────────────────────────────────────────────────
    private static final String TELEGRAM_TOKEN = requireEnv("TELEGRAM_TOKEN");
    private static final String CHAT_ID        = requireEnv("CHAT_ID");
    private static final String INFOEXT_URL    = "https://infoext2.delegaciondelgobierno.gob.es/infoext2/";

    private static final String MY_NIE          = requireEnv("MY_NIE");
    private static final String FECHA_SOLICITUD = requireEnv("FECHA_SOLICITUD");
    private static final String ANIO_NACIMIENTO = requireEnv("ANIO_NACIMIENTO");

    private static final int NAV_TIMEOUT_MS = 35_000;
    private static final int CAPTCHA_WAIT_MINUTES = Integer.parseInt(env("CAPTCHA_WAIT_MINUTES", "5"));

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        log("INIT", "Iniciando comprobación del estado de expediente...");
        log("DATA", "NIE=" + MY_NIE + " | Fecha=" + FECHA_SOLICITUD + " | Año=" + ANIO_NACIMIENTO);

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(Arrays.asList(
                                    "--disable-blink-features=AutomationControlled",
                                    "--no-sandbox",
                                    "--disable-setuid-sandbox",
                                    "--disable-dev-shm-usage",
                                    "--disable-gpu"
                            ))
            );

            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                            .setViewportSize(1920, 1080)
            );

            Page page = context.newPage();
            page.setDefaultTimeout(NAV_TIMEOUT_MS);

            // ── STEP 1: Landing page ──────────────────────────────────────────
            log("NAV", "Accediendo a Infoext2...");
            navigateWithRetry(page, INFOEXT_URL, 3);
            page.waitForTimeout(3000);
            screenshot(page, "step1_landing");

            if (page.content().toLowerCase().contains("fortigate")
                    || page.content().toLowerCase().contains("access denied")) {
                log("WAF", "IP bloqueada. Abortando.");
                screenshot(page, "waf_block");
                sendTelegram("⚠️ *ExpedienteChecker* — la IP del runner fue bloqueada por el WAF. Reintenta más tarde.");
                browser.close();
                System.exit(1);
            }

            // ── STEP 2: Click "ENTRAR FORMULARIO" ────────────────────────────
            log("CLICK", "Pulsando 'ENTRAR FORMULARIO'...");
            page.locator(
                    "a[onclick*='entradaFormu'], " +
                    "a:has-text('ENTRAR FORMULARIO'), " +
                    "span:has-text('ENTRAR FORMULARIO')"
            ).first().click();

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(3000);
            screenshot(page, "step2_formulario_page");
            log("URL", "Página del formulario: " + page.url());

            // ── STEP 3: Fill known fields ─────────────────────────────────────
            log("FORM", "Rellenando NIE, fecha y año de nacimiento...");
            fillById(page, "nie",               MY_NIE);
            fillById(page, "fechaPresentacion", FECHA_SOLICITUD);
            fillById(page, "anio",              ANIO_NACIMIENTO);
            screenshot(page, "step3_before_captcha");

            // ── STEP 4: Human-in-the-loop CAPTCHA ────────────────────────────
            boolean solved = solveCaptchaViaTelegram(page);
            if (!solved) {
                log("CAPTCHA", "No se recibió respuesta a tiempo. Abortando sin enviar el formulario.");
                sendTelegram("⌛ *ExpedienteChecker* — no respondiste al CAPTCHA a tiempo ("
                        + CAPTCHA_WAIT_MINUTES + " min). No se envió el formulario. Se reintentará en la próxima ejecución.");
                browser.close();
                return; // exit cleanly — do NOT submit an invalid captcha
            }

            screenshot(page, "step4_form_complete");

            // ── STEP 5: Submit ────────────────────────────────────────────────
            log("SUBMIT", "Enviando formulario...");
            page.locator(
                    "input[type='submit'], button[type='submit'], " +
                    "input[value*='Consultar' i], button:has-text('Consultar'), " +
                    "input[value*='Aceptar' i], button:has-text('Aceptar'), " +
                    "a[onclick*='submit']"
            ).first().click();

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(5000);
            screenshot(page, "step5_resultado");

            // ── STEP 6: Parse visible text ─────────────────────────────────────
            String bodyText;
            try {
                bodyText = page.locator("body").innerText().toUpperCase();
            } catch (Exception e) {
                bodyText = page.content().toUpperCase();
            }

            log("RESULT TEXT", bodyText.substring(0, Math.min(1000, bodyText.length())));

            if (bodyText.contains("CAPTCHA") && bodyText.contains("CONTINUAR")) {
                log("CAPTCHA", "El texto introducido fue rechazado por el servidor.");
                sendTelegram("🤖 *ExpedienteChecker* — el texto del CAPTCHA que enviaste fue rechazado por el portal. Se reintentará en la próxima ejecución.");
                browser.close();
                return;
            }

            parseAndNotify(bodyText);
            browser.close();

        } catch (Exception e) {
            log("ERROR", "Fallo durante la ejecución: " + e.getMessage());
            sendTelegram("❌ Error en el bot de expediente: " + e.getMessage());
            System.exit(1);
        }
    }

    // ── Human-in-the-loop CAPTCHA solving ───────────────────────────────────────
    // Sends a cropped screenshot of the captcha image to Telegram, then long-polls
    // getUpdates() for your reply. Returns true if a reply was received and typed in.
    private static boolean solveCaptchaViaTelegram(Page page) {
        try {
            Locator captchaInput = page.locator("#captcha, input[name='txtCaptcha']");
            if (captchaInput.count() == 0) {
                log("CAPTCHA", "No se detectó campo CAPTCHA. Continuando sin él.");
                return true;
            }

            // The real distorted-text image — exclude the small "listen"/"reload" icons,
            // whose filenames contain "ico_".
            Locator captchaImg = page.locator("img[src*='captcha' i]:not([src*='ico_' i])").first();
            if (captchaImg.count() == 0) {
                log("CAPTCHA", "No se encontró la imagen del CAPTCHA.");
                return false;
            }

            byte[] imgBytes = captchaImg.screenshot();
            Files.createDirectories(Paths.get("screenshots"));
            Files.write(Paths.get("screenshots", "captcha_crop.png"), imgBytes);

            // Mark any old, unrelated Telegram messages as already read before asking.
            long baselineUpdateId = getLatestUpdateId();

            sendTelegramPhoto(imgBytes,
                    "🔐 *ExpedienteChecker* necesita el texto del CAPTCHA.\n"
                    + "Responde a este mensaje con el texto exacto que ves en la imagen.\n"
                    + "Tienes " + CAPTCHA_WAIT_MINUTES + " minutos.");

            log("CAPTCHA", "Imagen enviada a Telegram. Esperando tu respuesta (máx " + CAPTCHA_WAIT_MINUTES + " min)...");

            String reply = waitForTelegramReply(baselineUpdateId, Duration.ofMinutes(CAPTCHA_WAIT_MINUTES));
            if (reply == null) {
                return false;
            }

            reply = reply.trim();
            log("CAPTCHA", "Respuesta recibida: " + reply);
            captchaInput.first().fill(reply);
            return true;

        } catch (Exception e) {
            log("CAPTCHA ERR", String.valueOf(e.getMessage()));
            return false;
        }
    }

    // Returns the highest existing Telegram update_id (0 if none), so we know to
    // ignore anything older than this when waiting for your captcha reply.
    private static long getLatestUpdateId() {
        try {
            JSONObject resp = telegramGet("getUpdates", "");
            JSONArray results = resp.optJSONArray("result");
            long max = 0;
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    long id = results.getJSONObject(i).getLong("update_id");
                    if (id > max) max = id;
                }
            }
            return max;
        } catch (Exception e) {
            log("TELEGRAM ERR", "getLatestUpdateId: " + e.getMessage());
            return 0;
        }
    }

    // Long-polls Telegram getUpdates for a new text message from CHAT_ID, ignoring
    // update_ids <= sinceUpdateId. Returns the message text, or null on timeout.
    private static String waitForTelegramReply(long sinceUpdateId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        long offset = sinceUpdateId + 1;

        while (Instant.now().isBefore(deadline)) {
            try {
                // Long-poll server-side for up to 25s per call so we don't spam requests.
                JSONObject resp = telegramGet("getUpdates", "offset=" + offset + "&timeout=25");
                JSONArray results = resp.optJSONArray("result");
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject update = results.getJSONObject(i);
                        long updateId = update.getLong("update_id");
                        offset = Math.max(offset, updateId + 1);

                        if (!update.has("message")) continue;
                        JSONObject message = update.getJSONObject("message");
                        String fromChatId = String.valueOf(message.getJSONObject("chat").getLong("id"));
                        if (!fromChatId.equals(CHAT_ID)) continue;
                        if (!message.has("text")) continue;

                        String text = message.getString("text").trim();
                        if (text.isEmpty() || text.startsWith("/")) continue;

                        return text;
                    }
                }
            } catch (Exception e) {
                log("TELEGRAM ERR", "waitForTelegramReply: " + e.getMessage());
                sleep(3000);
            }
        }
        return null;
    }

    // ── Fill by exact ID ──────────────────────────────────────────────────────
    private static void fillById(Page page, String id, String value) {
        try {
            Locator loc = page.locator("#" + id);
            if (loc.count() > 0) {
                loc.first().clear();
                loc.first().fill(value);
                log("FORM OK", "#" + id + " = " + value);
            } else {
                log("FORM WARN", "Campo #" + id + " no encontrado.");
            }
        } catch (Exception e) {
            log("FORM ERR", "#" + id + ": " + e.getMessage());
        }
    }

    // ── Parse result and notify ──────────────────────────────────────────────
    private static void parseAndNotify(String bodyText) {
        String status;
        String emoji;

        if (bodyText.contains("FAVORABLE")) {
            status = "✅ FAVORABLE";
            emoji = "🎉";
            log("STATUS", "¡FAVORABLE detectado!");

        } else if (bodyText.contains("EN TRÁMITE") || bodyText.contains("EN TRAMITE")) {
            status = "⏳ EN TRÁMITE";
            emoji = "⏳";
            log("STATUS", "Expediente en trámite.");

        } else if (bodyText.contains("DENEGADO") || bodyText.contains("ARCHIVADO") || bodyText.contains("INADMITIDO")) {
            status = "⚠️ RESOLUCIÓN NEGATIVA";
            emoji = "⚠️";
            log("STATUS", "Resolución negativa detectada.");

        } else if (bodyText.contains("NO SE HA ENCONTRADO") || bodyText.contains("NO EXISTE")) {
            status = "❓ DATOS NO ENCONTRADOS";
            emoji = "❓";
            log("STATUS", "Datos no localizados en el sistema.");

        } else {
            String preview = bodyText.replaceAll("\\s+", " ").trim();
            preview = preview.length() > 300 ? preview.substring(0, 300) + "..." : preview;
            status = "❓ ESTADO DESCONOCIDO";
            emoji = "❓";
            log("STATUS", "Estado no reconocido. Preview: " + preview);
        }

        String message = emoji + " *ExpedienteChecker* — " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + "\n\n"
                + "👤 NIE: `" + MY_NIE + "`\n"
                + "📋 Estado: *" + status + "*\n"
                + "🔗 [Ver portal](https://infoext2.delegaciondelgobierno.gob.es/infoext2/)";

        sendTelegram(message);
    }

    // ── Navigation with retry ──────────────────────────────────────────────────
    private static void navigateWithRetry(Page page, String url, int maxRetries) {
        for (int i = 1; i <= maxRetries; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(NAV_TIMEOUT_MS));
                return;
            } catch (Exception e) {
                log("NAV RETRY", i + "/" + maxRetries + ": " + e.getMessage());
                if (i == maxRetries) throw new RuntimeException("Navegación fallida: " + url, e);
                page.waitForTimeout(3000);
            }
        }
    }

    // ── Env helpers ───────────────────────────────────────────────────────────
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    // No insecure hardcoded fallbacks for secrets — fail loudly instead.
    private static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno requerida: " + key
                    + ". Configúrala como GitHub Secret.");
        }
        return v;
    }

    private static void log(String tag, String msg) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s][%s] %s%n", ts, tag, msg);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static void screenshot(Page page, String name) {
        try {
            Files.createDirectories(Paths.get("screenshots"));
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("screenshots", name + ".png"))
                    .setFullPage(true));
            log("SCREENSHOT", "screenshots/" + name + ".png");
        } catch (Exception e) {
            log("SCREENSHOT ERR", e.getMessage());
        }
    }

    // ── Telegram helpers ──────────────────────────────────────────────────────
    private static JSONObject telegramGet(String method, String query) throws Exception {
        String url = "https://api.telegram.org/bot" + TELEGRAM_TOKEN + "/" + method
                + (query.isEmpty() ? "" : "?" + query);
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(35)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new JSONObject(resp.body());
    }

    private static void sendTelegram(String message) {
        try {
            String encoded = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String url = String.format(
                    "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&parse_mode=Markdown&text=%s",
                    TELEGRAM_TOKEN, CHAT_ID, encoded);
            HttpResponse<String> resp = HTTP.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            log("TELEGRAM", "Enviado — HTTP " + resp.statusCode() + " | Body: " + resp.body().substring(0, Math.min(100, resp.body().length())));
        } catch (Exception e) {
            log("TELEGRAM ERR", e.getMessage());
        }
    }

    // Sends a photo via multipart/form-data (Telegram sendPhoto requires this for raw bytes).
    private static void sendTelegramPhoto(byte[] imageBytes, String caption) throws Exception {
        String boundary = "----ExpedienteCheckerBoundary" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        writeField(body, boundary, "chat_id", CHAT_ID);
        writeField(body, boundary, "caption", caption);
        writeField(body, boundary, "parse_mode", "Markdown");

        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"photo\"; filename=\"captcha.png\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(imageBytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + TELEGRAM_TOKEN + "/sendPhoto"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        log("TELEGRAM", "Foto enviada — HTTP " + resp.statusCode());
    }

    private static void writeField(ByteArrayOutputStream body, String boundary, String name, String value) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
