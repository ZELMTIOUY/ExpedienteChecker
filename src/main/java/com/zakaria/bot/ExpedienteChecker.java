package com.zakaria.bot;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ExpedienteChecker — Automatización para consultar el estado de expediente en Infoext2.
 *
 * Variables de entorno (configurar como GitHub Secrets):
 *   MY_NIE            → Tu NIE (ej. Z1017349H)
 *   FECHA_SOLICITUD   → Fecha DD/MM/YYYY (ej. 30/07/2026)
 *   ANIO_NACIMIENTO   → Año YYYY (ej. 1996)
 *   TELEGRAM_TOKEN    → Token del bot de Telegram
 *   CHAT_ID           → Tu Chat ID de Telegram
 */
public class ExpedienteChecker {

    // ── CONFIG ────────────────────────────────────────────────────────────────
    private static final String TELEGRAM_TOKEN = env("TELEGRAM_TOKEN", "8791426005:AAG-zE-bvklgZ--eKcv-ON2pUuG_owlQnfg");
    private static final String CHAT_ID        = env("CHAT_ID",        "6082672502");
    private static final String INFOEXT_URL    = "https://infoext2.delegaciondelgobierno.gob.es/infoext2/";

    private static final String MY_NIE          = env("MY_NIE",          "Z1017349H");
    private static final String FECHA_SOLICITUD = env("FECHA_SOLICITUD", "30/07/2026");
    private static final String ANIO_NACIMIENTO = env("ANIO_NACIMIENTO", "1996");

    private static final int NAV_TIMEOUT_MS = 35_000;

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
            // Confirmed field IDs from DEBUG INPUTS on previous run:
            //   #nie, #fechaPresentacion, #anio, #captcha (name=txtCaptcha)
            log("FORM", "Rellenando NIE, fecha y año de nacimiento...");

            fillById(page, "nie",                MY_NIE);
            fillById(page, "fechaPresentacion",  FECHA_SOLICITUD);
            fillById(page, "anio",               ANIO_NACIMIENTO);

            screenshot(page, "step3_before_captcha");

            // ── STEP 4: Solve CAPTCHA ─────────────────────────────────────────
            // The form has #captcha (name=txtCaptcha).
            // Spanish gov portals typically show a simple arithmetic CAPTCHA
            // e.g. "¿Cuánto es 3 + 5?" → we parse and compute the answer.
            boolean captchaSolved = solveCaptcha(page);
            if (!captchaSolved) {
                log("CAPTCHA", "⚠️  No se pudo resolver el CAPTCHA automáticamente.");
                screenshot(page, "step4_captcha_fail");
                // Still attempt submit — might work without CAPTCHA on some requests
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

            // ── STEP 6: Parse visible text (not raw HTML) ─────────────────────
            // innerText gives us clean readable text without HTML tags
            String bodyText = "";
            try {
                bodyText = page.locator("body").innerText().toUpperCase();
            } catch (Exception e) {
                bodyText = page.content().toUpperCase();
            }

            log("RESULT TEXT", bodyText.substring(0, Math.min(1000, bodyText.length())));
            parseAndNotify(bodyText, page.url());

            browser.close();

        } catch (Exception e) {
            log("ERROR", "Fallo durante la ejecución: " + e.getMessage());
            sendTelegram("❌ Error en el bot de expediente: " + e.getMessage());
            System.exit(1);
        }
    }

    // ── Solve arithmetic CAPTCHA ──────────────────────────────────────────────
    // Searches the page for patterns like "3 + 5", "7 - 2", "4 * 3"
    // and fills the answer into the captcha input.
    private static boolean solveCaptcha(Page page) {
        try {
            // Check if captcha input exists
            if (page.locator("#captcha, input[name='txtCaptcha']").count() == 0) {
                log("CAPTCHA", "No se detectó campo CAPTCHA. Continuando...");
                return true;
            }

            // Get the page text to find the arithmetic question
            String pageText = page.locator("body").innerText();
            log("CAPTCHA", "Buscando expresión aritmética en: " + pageText.substring(0, Math.min(500, pageText.length())));

            // Pattern: "3 + 5", "12 - 4", "3 × 5", "3 * 5", "cuánto es N op N"
            Pattern mathPattern = Pattern.compile("(\\d+)\\s*([+\\-×\\*xX])\\s*(\\d+)");
            Matcher matcher = mathPattern.matcher(pageText);

            if (matcher.find()) {
                int a = Integer.parseInt(matcher.group(1));
                String op = matcher.group(2).trim();
                int b = Integer.parseInt(matcher.group(3));
                int result;

                switch (op) {
                    case "+": result = a + b; break;
                    case "-": result = a - b; break;
                    case "*":
                    case "×":
                    case "x":
                    case "X": result = a * b; break;
                    default:  result = a + b; break;
                }

                log("CAPTCHA", "Expresión detectada: " + a + " " + op + " " + b + " = " + result);
                page.locator("#captcha, input[name='txtCaptcha']").first().fill(String.valueOf(result));
                log("CAPTCHA", "✅ CAPTCHA resuelto: " + result);
                return true;
            }

            // Try to find captcha as an image — log src so we can inspect it
            try {
                String captchaImgSrc = page.evaluate(
                    "() => { const img = document.querySelector('img[src*=\"captcha\"], img[id*=\"captcha\"]'); return img ? img.src : 'no-img'; }"
                ).toString();
                log("CAPTCHA IMG", captchaImgSrc);
            } catch (Exception ignored) {}

            log("CAPTCHA", "No se encontró expresión aritmética reconocible.");
            return false;

        } catch (Exception e) {
            log("CAPTCHA ERR", e.getMessage());
            return false;
        }
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

    // ── Parse result and ALWAYS send Telegram ────────────────────────────────
    // User wants a notification for ANY state — useful to verify Telegram works.
    private static void parseAndNotify(String bodyText, String currentUrl) {
        String status;
        String emoji;
        boolean isImportant;

        if (bodyText.contains("FAVORABLE")) {
            status      = "✅ FAVORABLE";
            emoji       = "🎉";
            isImportant = true;
            log("STATUS", "¡FAVORABLE detectado!");

        } else if (bodyText.contains("EN TRÁMITE") || bodyText.contains("EN TRAMITE")) {
            status      = "⏳ EN TRÁMITE";
            emoji       = "⏳";
            isImportant = false;
            log("STATUS", "Expediente en trámite.");

        } else if (bodyText.contains("DENEGADO") || bodyText.contains("ARCHIVADO") || bodyText.contains("INADMITIDO")) {
            status      = "⚠️ RESOLUCIÓN NEGATIVA";
            emoji       = "⚠️";
            isImportant = true;
            log("STATUS", "Resolución negativa detectada.");

        } else if (bodyText.contains("NO SE HA ENCONTRADO") || bodyText.contains("NO EXISTE")) {
            status      = "❓ DATOS NO ENCONTRADOS";
            emoji       = "❓";
            isImportant = false;
            log("STATUS", "Datos no localizados en el sistema.");

        } else if (bodyText.contains("CAPTCHA") || bodyText.contains("CÓDIGO DE VERIFICACIÓN")) {
            status      = "🤖 CAPTCHA NO RESUELTO";
            emoji       = "🤖";
            isImportant = false;
            log("STATUS", "El servidor rechazó por CAPTCHA no resuelto.");

        } else {
            // Extract first 300 meaningful chars from body as preview
            String preview = bodyText.replaceAll("\\s+", " ").trim();
            preview = preview.length() > 300 ? preview.substring(0, 300) + "..." : preview;
            status      = "❓ ESTADO DESCONOCIDO";
            emoji       = "❓";
            isImportant = false;
            log("STATUS", "Estado no reconocido. Preview: " + preview);
        }

        // ── Always send Telegram — so user can confirm the bot is alive ───────
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

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static void log(String tag, String msg) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s][%s] %s%n", ts, tag, msg);
    }

    private static void screenshot(Page page, String name) {
        try {
            java.nio.file.Files.createDirectories(Paths.get("screenshots"));
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("screenshots", name + ".png"))
                    .setFullPage(true));
            log("SCREENSHOT", "screenshots/" + name + ".png");
        } catch (Exception e) {
            log("SCREENSHOT ERR", e.getMessage());
        }
    }

    private static void sendTelegram(String message) {
        try {
            String encoded = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String url = String.format(
                    "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&parse_mode=Markdown&text=%s",
                    TELEGRAM_TOKEN, CHAT_ID, encoded);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            log("TELEGRAM", "Enviado — HTTP " + resp.statusCode() + " | Body: " + resp.body().substring(0, Math.min(100, resp.body().length())));
        } catch (Exception e) {
            log("TELEGRAM ERR", e.getMessage());
        }
    }
}
