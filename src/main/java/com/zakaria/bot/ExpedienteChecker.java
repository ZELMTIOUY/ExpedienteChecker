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

            // WAF detection
            if (page.content().toLowerCase().contains("fortigate")
                    || page.content().toLowerCase().contains("access denied")) {
                log("WAF", "IP bloqueada. Abortando.");
                screenshot(page, "waf_block");
                browser.close();
                System.exit(1);
            }

            // ── STEP 2: Click "ENTRAR FORMULARIO" ────────────────────────────
            // The landing page (HTML confirmed) shows two choices: Cl@ve and Formulario.
            // "ENTRAR FORMULARIO" uses onclick="entradaFormu()" which submits #frmFormu.
            // The actual form fields are on the NEXT page: /infoext2/entradaFormulario.html
            log("CLICK", "Pulsando 'ENTRAR FORMULARIO'...");
            page.locator(
                    "a[onclick*='entradaFormu'], " +
                    "a:has-text('ENTRAR FORMULARIO'), " +
                    "span:has-text('ENTRAR FORMULARIO')"
            ).first().click();

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(3000);
            screenshot(page, "step2_formulario_page");
            log("URL", "Página tras click: " + page.url());

            // Dump all form fields found — helps tune selectors on next run
            dumpInputs(page);

            // ── STEP 3: Fill the form ─────────────────────────────────────────
            log("FORM", "Rellenando NIE, fecha de solicitud y año de nacimiento...");

            // NIE / NIF / document identifier
            fillFirstMatch(page, MY_NIE,
                    "input[id*='nie' i]",
                    "input[name*='nie' i]",
                    "input[id*='nif' i]",
                    "input[name*='nif' i]",
                    "input[id*='identificacion' i]",
                    "input[id*='idSolicitante' i]",
                    "input[id*='documento' i]",
                    "input[placeholder*='NIE' i]",
                    "input[placeholder*='NIF' i]"
            );

            // Fecha de solicitud
            fillFirstMatch(page, FECHA_SOLICITUD,
                    "input[id*='fecha' i]",
                    "input[name*='fecha' i]",
                    "input[placeholder*='fecha' i]",
                    "input[type='date']"
            );

            // Año de nacimiento
            fillFirstMatch(page, ANIO_NACIMIENTO,
                    "input[id*='anio' i]",
                    "input[name*='anio' i]",
                    "input[id*='ano' i]",
                    "input[name*='ano' i]",
                    "input[id*='nacimiento' i]",
                    "input[name*='nacimiento' i]",
                    "input[placeholder*='año' i]",
                    "input[placeholder*='nacimiento' i]"
            );

            screenshot(page, "step3_form_filled");

            // ── STEP 4: Submit ────────────────────────────────────────────────
            log("SUBMIT", "Enviando formulario...");
            page.locator(
                    "input[type='submit'], button[type='submit'], " +
                    "input[value*='Consultar' i], button:has-text('Consultar'), " +
                    "input[value*='Aceptar' i], button:has-text('Aceptar'), " +
                    "input[value*='Verificar' i], button:has-text('Verificar'), " +
                    "a[onclick*='submit'], a:has-text('Consultar')"
            ).first().click();

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(5000);
            screenshot(page, "step4_resultado");

            // ── STEP 5: Parse ─────────────────────────────────────────────────
            String pageText = page.content().toUpperCase();
            // Log first 600 chars so we can read result state in Actions log
            log("CONTENT_SAMPLE", pageText.substring(0, Math.min(600, pageText.length())));
            parseAndNotify(pageText);

            browser.close();

        } catch (Exception e) {
            log("ERROR", "Fallo durante la ejecución: " + e.getMessage());
            System.exit(1);
        }
    }

    // ── Fill first matching selector among candidates ─────────────────────────
    private static void fillFirstMatch(Page page, String value, String... selectors) {
        for (String selector : selectors) {
            try {
                Locator loc = page.locator(selector);
                if (loc.count() > 0) {
                    loc.first().clear();
                    loc.first().fill(value);
                    log("FORM OK", "Rellenado [" + selector + "] = " + value);
                    return;
                }
            } catch (Exception ignored) {}
        }
        log("FORM WARN", "Campo no encontrado para valor: " + value);
    }

    // ── Dump all visible inputs to log (debug helper) ─────────────────────────
    private static void dumpInputs(Page page) {
        try {
            Object result = page.evaluate(
                    "() => Array.from(document.querySelectorAll('input,select,textarea'))" +
                    ".map(e => e.tagName + '#' + (e.id||'') + '[name=' + (e.name||'') + '][type=' + (e.type||'') + ']')" +
                    ".join(' | ')");
            log("DEBUG INPUTS", result != null ? result.toString() : "(ninguno)");
        } catch (Exception ignored) {}
    }

    // ── Parse result page and send Telegram if needed ─────────────────────────
    private static void parseAndNotify(String pageText) {
        if (pageText.contains("FAVORABLE")) {
            log("STATUS ✅", "¡FAVORABLE detectado!");
            sendTelegram("🎉 ¡Buenas noticias! Tu expediente (" + MY_NIE + ") ha cambiado a: *FAVORABLE*. 🥳");

        } else if (pageText.contains("EN TRÁMITE") || pageText.contains("EN TRAMITE")) {
            log("STATUS ⏳", "Expediente en trámite. Sin cambios.");

        } else if (pageText.contains("DENEGADO") || pageText.contains("ARCHIVADO") || pageText.contains("INADMITIDO")) {
            log("STATUS ⚠️", "Resolución final negativa.");
            sendTelegram("⚠️ Tu expediente (" + MY_NIE + ") tiene una actualización: posible resolución negativa.");

        } else if (pageText.contains("NO SE HA ENCONTRADO") || pageText.contains("NO EXISTE")) {
            log("WARNING", "Datos no localizados. Verifica NIE/fecha.");

        } else {
            log("INFO", "Estado no reconocido. Ver captura step4_resultado.png.");
        }
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
            log("TELEGRAM", "Enviado — HTTP " + resp.statusCode());
        } catch (Exception e) {
            log("TELEGRAM ERR", e.getMessage());
        }
    }
}
