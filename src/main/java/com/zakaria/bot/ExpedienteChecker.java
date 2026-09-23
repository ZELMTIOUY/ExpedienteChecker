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

/**
 * ExpedienteChecker — Automatización para consultar el estado de expediente en Infoext2.
 *
 * Uso con variables de entorno (GitHub Secrets recomendado):
 *   MY_NIE            → Tu NIE (ej. Z1017349H)
 *   FECHA_SOLICITUD   → Fecha DD/MM/YYYY (ej. 30/07/2026)
 *   ANIO_NACIMIENTO   → Año YYYY (ej. 1996)
 *   TELEGRAM_TOKEN    → Token del bot de Telegram
 *   CHAT_ID           → Tu Chat ID de Telegram
 */
public class ExpedienteChecker {

    // ── TELEGRAM CONFIGURATION ────────────────────────────────────────────────
    private static final String TELEGRAM_TOKEN  = env("TELEGRAM_TOKEN",  "8791426005:AAG-zE-bvklgZ--eKcv-ON2pUuG_owlQnfg");
    private static final String CHAT_ID         = env("CHAT_ID",         "6082672502");
    private static final String INFOEXT_URL     = "https://infoext2.delegaciondelgobierno.gob.es/infoext2/";

    // ── EXPEDIENTE DATA ───────────────────────────────────────────────────────
    private static final String MY_NIE           = env("MY_NIE",           "Z1017349H");
    private static final String FECHA_SOLICITUD  = env("FECHA_SOLICITUD",  "30/07/2026"); // DD/MM/YYYY
    private static final String ANIO_NACIMIENTO  = env("ANIO_NACIMIENTO",  "1996");       // YYYY

    // ── TIMEOUTS ──────────────────────────────────────────────────────────────
    private static final int NAV_TIMEOUT_MS  = 35_000;
    private static final int PAGE_WAIT_MS    =  5_000;

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
                                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                    + "Chrome/122.0.0.0 Safari/537.36")
                            .setViewportSize(1920, 1080)
            );

            Page page = context.newPage();
            page.setDefaultTimeout(NAV_TIMEOUT_MS);

            // ── 1. Navigate ───────────────────────────────────────────────────
            log("NAV", "Accediendo a Infoext2...");
            navigateWithRetry(page, INFOEXT_URL, 3);
            page.waitForTimeout(3000);

            screenshot(page, "step1_landing");

            // ── 2. WAF / block detection ──────────────────────────────────────
            String html = page.content().toLowerCase();
            if (html.contains("fortigate") || html.contains("access denied") || html.contains("url rejected")) {
                log("WAF", "⚠️  IP bloqueada por el firewall. Abortando.");
                screenshot(page, "waf_block");
                browser.close();
                return;
            }

            // ── 3. Click NIE tab if present ───────────────────────────────────
            Locator nieTab = page.locator("a:has-text('NIE'), button:has-text('NIE'), input[value*='NIE']");
            if (nieTab.count() > 0) {
                log("TAB", "Seleccionando pestaña NIE...");
                nieTab.first().click();
                page.waitForTimeout(1500);
            }

            // ── 4. Fill form ──────────────────────────────────────────────────
            log("FORM", "Introduciendo datos: NIE, fecha de solicitud, año de nacimiento...");
            fillField(page, "input[name*='nie' i], input[id*='nie' i]",                    MY_NIE);
            fillField(page, "input[name*='fecha' i], input[id*='fecha' i]",                FECHA_SOLICITUD);
            fillField(page, "input[name*='anio' i], input[id*='anio' i], input[name*='ano' i]", ANIO_NACIMIENTO);

            screenshot(page, "step2_form_filled");

            // ── 5. Submit ─────────────────────────────────────────────────────
            log("SUBMIT", "Enviando formulario de consulta...");
            page.locator("input[type='submit'], button[type='submit'], "
                    + "input[value*='Consultar' i], button:has-text('Consultar')")
                    .first().click();

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(PAGE_WAIT_MS);

            screenshot(page, "step3_resultado");

            // ── 6. Parse result ───────────────────────────────────────────────
            String pageText = page.content().toUpperCase();
            parseAndNotify(pageText);

            browser.close();

        } catch (Exception e) {
            log("ERROR", "Fallo durante la ejecución: " + e.getMessage());
            System.exit(1);
        }
    }

    // ── Result parser & Telegram dispatcher ──────────────────────────────────

    private static void parseAndNotify(String pageText) {
        if (pageText.contains("FAVORABLE")) {
            log("STATUS ✅", "¡Estado detectado: FAVORABLE!");
            sendTelegram("🎉 ¡Buenas noticias! Tu expediente de extranjería ("
                    + MY_NIE + ") ha cambiado a: *FAVORABLE*. 🥳");

        } else if (pageText.contains("EN TRÁMITE") || pageText.contains("EN TRAMITE")) {
            log("STATUS ⏳", "Expediente en trámite. Sin cambios.");
            // No Telegram — avoid spam when nothing changed

        } else if (pageText.contains("DENEGADO")
                || pageText.contains("ARCHIVADO")
                || pageText.contains("INADMITIDO")) {
            log("STATUS ⚠️", "Resolución final negativa detectada.");
            sendTelegram("⚠️ Aviso: Hay una actualización en tu expediente ("
                    + MY_NIE + "). Estado detectado: posible resolución negativa.");

        } else if (pageText.contains("NO SE HA ENCONTRADO") || pageText.contains("NO EXISTE")) {
            log("WARNING", "Datos no localizados. Verifica NIE/fecha o si aún no está en la BBDD.");

        } else {
            log("INFO", "Estado no reconocido. Revisa la captura step3_resultado.png para más detalles.");
        }
    }

    // ── Navigation with retry ─────────────────────────────────────────────────

    private static void navigateWithRetry(Page page, String url, int maxRetries) {
        for (int i = 1; i <= maxRetries; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(NAV_TIMEOUT_MS));
                return;
            } catch (Exception e) {
                log("NAV RETRY", "Intento " + i + "/" + maxRetries + " fallido: " + e.getMessage());
                if (i == maxRetries) throw new RuntimeException("No se pudo navegar a " + url, e);
                page.waitForTimeout(3000);
            }
        }
    }

    // ── Safe field fill ───────────────────────────────────────────────────────

    private static void fillField(Page page, String selector, String value) {
        try {
            Locator loc = page.locator(selector);
            if (loc.count() > 0) {
                loc.first().fill(value);
            } else {
                log("WARN", "Campo no encontrado con selector: " + selector);
            }
        } catch (Exception e) {
            log("WARN", "No se pudo rellenar campo [" + selector + "]: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String env(String key, String fallback) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : fallback;
    }

    private static void log(String tag, String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s][%s] %s%n", timestamp, tag, msg);
    }

    private static void screenshot(Page page, String name) {
        try {
            java.nio.file.Files.createDirectories(Paths.get("screenshots"));
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("screenshots", name + ".png"))
                    .setFullPage(true));
            log("SCREENSHOT", "Captura guardada: screenshots/" + name + ".png");
        } catch (Exception e) {
            log("SCREENSHOT ERR", e.getMessage());
        }
    }

    private static void sendTelegram(String message) {
        try {
            // StandardCharsets.UTF_8 avoids the deprecated String-charset overload
            String encoded = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String url = String.format(
                    "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&parse_mode=Markdown&text=%s",
                    TELEGRAM_TOKEN, CHAT_ID, encoded);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            log("TELEGRAM", "Enviado — HTTP " + resp.statusCode());
        } catch (Exception e) {
            log("TELEGRAM ERR", e.getMessage());
        }
    }
}
