package ru.tarkovskiy.ui.reporting;

import io.qameta.allure.Allure;
import org.openqa.selenium.*;
import ru.tarkovskiy.ui.config.UiConfig;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Passive, allowlist-only evidence. No HTTP clients, page source, headers or input values. */
public final class NavigationDiagnostics {
    private NavigationDiagnostics() { }
    private static final Set<String> NETWORK_ERRORS = Set.of("ERR_CONNECTION_RESET", "ERR_CONNECTION_REFUSED",
            "ERR_CONNECTION_CLOSED", "ERR_CONNECTION_TIMED_OUT", "ERR_NAME_NOT_RESOLVED", "ERR_TIMED_OUT",
            "ERR_INTERNET_DISCONNECTED", "ERR_EMPTY_RESPONSE", "ERR_NETWORK_CHANGED", "ERR_PROXY_CONNECTION_FAILED",
            "ERR_TUNNEL_CONNECTION_FAILED", "ERR_CERT_AUTHORITY_INVALID", "ERR_CERT_DATE_INVALID",
            "ERR_CERT_COMMON_NAME_INVALID", "ERR_SSL_PROTOCOL_ERROR", "ERR_HTTP_RESPONSE_CODE_FAILURE");

    // Reads only structure and numeric Navigation Timing fields. documentURI is never returned verbatim.
    private static final String SNAPSHOT = """
        const n = performance.getEntriesByType('navigation')[0];
        const chromeError = document.documentURI.startsWith('chrome-error:');
        const code = chromeError ? document.querySelector('#error-code') : null;
        const known = arguments[1];
        const error = code && known.includes(code.textContent.trim()) ? code.textContent.trim() : 'UNKNOWN';
        const visible = s => [...document.querySelectorAll(s)].some(e => {
            const r=e.getBoundingClientRect(), c=getComputedStyle(e);
            return r.width>0 && r.height>0 && c.visibility!=='hidden' && c.display!=='none';
        });
        return {state:document.readyState, dom:document.getElementsByTagName('*').length,
            chromeError:chromeError, error:error, markers:arguments[0].every(visible),
            http:n && typeof n.responseStatus==='number' ? n.responseStatus : -1,
            redirects:n ? n.redirectCount : -1, responseMs:n ? n.responseStart-n.requestStart : -1,
            durationMs:n ? n.duration : -1, navigationType:n ? n.type : 'UNKNOWN'};
        """;

    public static String safeUrl(String raw) {
        if ("data:,".equals(raw)) return "[DATA_EMPTY]";
        if ("about:blank".equals(raw)) return "[ABOUT_BLANK]";
        if (raw != null && raw.startsWith("data:")) return "[DATA_OTHER]";
        if (raw != null && raw.startsWith("about:")) return "[ABOUT_OTHER]";
        try {
            URI uri = URI.create(raw);
            if ("about".equals(uri.getScheme())) return "[ABOUT_OTHER]";
            if ("data".equals(uri.getScheme())) return "[DATA_OTHER]";
            if ("chrome-error".equals(uri.getScheme())) return "[CHROME_ERROR]";
            if ("chrome".equals(uri.getScheme())) return "[CHROME_INTERNAL]";
            if ("https".equals(uri.getScheme()) && URI.create(UiConfig.BASE_URL).getHost().equals(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawPath() != null
                    && uri.getRawPath().matches("/(?:login|register|tasks(?:/new|/[0-9]+/edit)?)?")) {
                return UiConfig.BASE_URL + uri.getRawPath();
            }
        } catch (RuntimeException ignored) { /* Never include the malformed input. */ }
        return "[UNAPPROVED_URL_OMITTED]";
    }

    public static void requireTarget(String url) {
        if (url == null || !url.startsWith(UiConfig.BASE_URL + "/") || !url.equals(safeUrl(url))) {
            throw new IllegalArgumentException("Navigation target refused: only approved TEST routes without query or fragment");
        }
    }

    private static String[] markers(String expected) {
        if (expected.endsWith("/login")) return new String[]{"#username", "#password", "form.auth-form button[type='submit']"};
        if (expected.endsWith("/register")) return new String[]{"#username", "#email", "#password", "form.auth-form button[type='submit']"};
        if (expected.endsWith("/tasks")) return new String[]{"h1", ".tasks-table"};
        if (expected.endsWith("/tasks/new")) return new String[]{"#title", "#description", "#create-task-button"};
        if (expected.matches(".*/tasks/[0-9]+/edit")) return new String[]{"#title", "#description", "#save-task-button"};
        return new String[]{"h1"};
    }

    public record Snapshot(String url, boolean exactExpectedUrl, String state, long dom, boolean chromeError, String networkError,
                           boolean markers, int http, long redirects, long responseMs, long durationMs, String type) {
        public boolean ready(String expected) {
            return exactExpectedUrl && expected.equals(url) && "complete".equals(state) && markers && !chromeError && (http == 0 || http == -1 || http >= 200 && http < 300);
        }
        public String classification(String expected, Throwable failure) {
            String exceptionError = NavigationDiagnostics.networkError(failure);
            if (http >= 400 || "ERR_HTTP_RESPONSE_CODE_FAILURE".equals(networkError)
                    || "ERR_HTTP_RESPONSE_CODE_FAILURE".equals(exceptionError)) return "HTTP_ERROR";
            if (!"UNKNOWN".equals(networkError) || !"UNKNOWN".equals(exceptionError)) return "NETWORK_ERROR";
            if (chromeError || "[CHROME_ERROR]".equals(url)) return "CHROME_ERROR";
            if ("[CHROME_INTERNAL]".equals(url)) return "CHROME_INTERNAL";
            if (url.startsWith("[DATA_")) return "DATA_DOCUMENT";
            if (url.startsWith("[ABOUT_")) return "ABOUT_DOCUMENT";
            if (ready(expected)) return http >= 200 ? "TEST_READY" : "TEST_READY_HTTP_UNKNOWN";
            return "UNEXPECTED_OR_NOT_READY";
        }
    }

    public static Snapshot snapshot(WebDriver driver, String expected) {
        String url = "[UNAVAILABLE]";
        boolean exactExpected = false;
        try {
            String raw = driver.getCurrentUrl();
            exactExpected = expected.equals(raw);
            url = safeUrl(raw);
        } catch (RuntimeException ignored) { }
        try {
            Object raw = ((JavascriptExecutor) driver).executeScript(SNAPSHOT, markers(expected), NETWORK_ERRORS.toArray(String[]::new));
            Map<?, ?> m = raw instanceof Map<?, ?> map ? map : Map.of();
            String state = allowed(m.get("state"), Set.of("loading", "interactive", "complete"));
            long http = number(m.get("http"));
            return new Snapshot(url, exactExpected, state, number(m.get("dom")), Boolean.TRUE.equals(m.get("chromeError")),
                    allowed(m.get("error"), NETWORK_ERRORS), Boolean.TRUE.equals(m.get("markers")),
                    http == 0 || http >= 100 && http <= 599 ? (int) http : -1,
                    number(m.get("redirects")), number(m.get("responseMs")), number(m.get("durationMs")),
                    allowed(m.get("navigationType"), Set.of("navigate", "reload", "back_forward", "prerender")));
        } catch (RuntimeException ignored) {
            return new Snapshot(url, exactExpected, "UNKNOWN", -1, false, "UNKNOWN", false, -1, -1, -1, -1, "UNKNOWN");
        }
    }

    private static long number(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0) return -1;
        return n.longValue();
    }
    private static String allowed(Object value, Set<String> allowed) {
        return value instanceof String s && allowed.contains(s) ? s : "UNKNOWN";
    }
    private static String version(Object value) {
        if (!(value instanceof String s)) return "UNKNOWN";
        var match = java.util.regex.Pattern.compile("^([0-9]+(?:\\.[0-9]+){1,3})(?:$|[ (])").matcher(s);
        return match.find() ? match.group(1) : "UNKNOWN";
    }
    public static String networkError(Throwable failure) {
        // Only literal known codes, never arbitrary exception text or capabilities.
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            String message = failure.getMessage();
            if (message == null) continue;
            var match = java.util.regex.Pattern.compile("(?:net::)?\\b(ERR_[A-Z_]+)\\b").matcher(message);
            while (match.find()) if (NETWORK_ERRORS.contains(match.group(1))) return match.group(1);
        }
        return "UNKNOWN";
    }

    public static String environment(WebDriver driver, boolean headless) {
        Capabilities caps = driver instanceof HasCapabilities c ? c.getCapabilities() : new ImmutableCapabilities();
        Object chrome = caps.getCapability("chrome");
        Object driverVersion = chrome instanceof Map<?, ?> m ? m.get("chromedriverVersion") : null;
        Object timeouts = caps.getCapability("timeouts");
        Map<?, ?> timeoutMap = timeouts instanceof Map<?, ?> m ? m : Map.of();
        Object proxy = caps.getCapability("proxy");
        Object proxyType = proxy instanceof Map<?, ?> m ? m.get("proxyType")
                : proxy instanceof org.openqa.selenium.Proxy p ? p.getProxyType().toString().toLowerCase(java.util.Locale.ROOT) : null;
        return "UI_BROWSER_SESSION\nchromeVersion=" + version(caps.getBrowserVersion())
                + "\nchromeDriverVersion=" + version(driverVersion)
                + "\nseleniumVersion=" + version(new BuildInfo().getReleaseLabel())
                + "\njavaVersion=" + version(System.getProperty("java.version"))
                + "\nos=" + allowed(System.getProperty("os.name"), Set.of("Windows 11", "Windows 10", "Windows Server 2025", "Windows Server 2022", "Linux", "Mac OS X"))
                + "\nosVersion=" + version(System.getProperty("os.version"))
                + "\narchitecture=" + allowed(System.getProperty("os.arch"), Set.of("amd64", "x86", "aarch64", "x86_64"))
                + "\navailableProcessors=" + Runtime.getRuntime().availableProcessors()
                + "\njvmMaxHeapMiB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024)
                + "\ngithubActions=" + "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                + "\nrequestedHeadlessNew=" + headless + "\nrequestedWindow=" + (headless ? "1440x1000" : "MAXIMIZED")
                + "\nrequestedDisableDevShmUsage=" + headless
                + "\npageLoadStrategy=" + allowed(caps.getCapability("pageLoadStrategy"), Set.of("normal", "eager", "none"))
                + "\nimplicitTimeoutMs=" + number(timeoutMap.get("implicit"))
                + "\npageLoadTimeoutMs=" + number(timeoutMap.get("pageLoad"))
                + "\nscriptTimeoutMs=" + number(timeoutMap.get("script"))
                + "\nproxyType=" + allowed(proxyType, Set.of("direct", "system", "manual", "pac", "autodetect", "unspecified"))
                + "\nacceptInsecureCerts=" + (caps.getCapability("acceptInsecureCerts") instanceof Boolean b ? b.toString() : "UNKNOWN")
                + "\nproxyEnvironmentPresent=" + proxyEnvironmentPresent()
                + "\nexplicitDriverPropertyPresent=" + (System.getProperty("webdriver.chrome.driver") != null)
                + "\n";
    }
    private static boolean proxyEnvironmentPresent() {
        return java.util.stream.Stream.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")
                .anyMatch(k -> System.getenv(k) != null && !System.getenv(k).isBlank());
    }
    public static void attachSession(WebDriver driver, boolean headless) {
        try { emit("Browser session", environment(driver, headless)); }
        catch (RuntimeException ignored) { System.out.println("UI_BROWSER_SESSION unavailable"); }
    }
    public static void attachNavigation(String expected, Instant started, long getMillis, long totalMillis,
                                        Snapshot initial, Snapshot last, Throwable failure) {
        String report = "UI_NAVIGATION\nstartedUtc=" + started + "\nexpected=" + safeUrl(expected)
                + "\ndriverGetMs=" + getMillis + "\ntotalMs=" + totalMillis
                + "\ncommandOrReadinessFailed=" + (failure != null)
                + "\nurlAfterGet=" + initial.url() + "\nurlAfterWait=" + last.url()
                + "\nclassification=" + last.classification(expected, failure)
                + "\nreadyState=" + last.state() + "\ndomElements=" + last.dom()
                + "\nexactExpectedUrl=" + last.exactExpectedUrl()
                + "\nexpectedMarkersVisible=" + last.markers()
                + "\nhttpStatus=" + (last.http() > 0 ? last.http() : "UNKNOWN")
                + "\nnetworkError=" + (!"UNKNOWN".equals(last.networkError()) ? last.networkError() : networkError(failure))
                + "\nredirectCount=" + last.redirects() + "\nresponseStartMs=" + last.responseMs()
                + "\ndocumentDurationMs=" + last.durationMs() + "\nnavigationType=" + last.type()
                + "\nnetworkEvidence=PASSIVE_NAVIGATION_TIMING_ONLY; zero/unsupported status is UNKNOWN; redirect URLs unavailable\n";
        emit("Browser navigation", report);
    }
    private static void emit(String name, String report) {
        report = UiFailureDiagnostics.redactConfiguredSecrets(report);
        System.out.println(report);
        Allure.addAttachment(name, "text/plain", report, ".txt");
    }
}
