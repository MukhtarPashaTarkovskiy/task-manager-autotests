package ru.tarkovskiy.ui.reporting;

import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.AllureResultsWriter;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;
import org.openqa.selenium.*;
import ru.tarkovskiy.ui.base.BasePage;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Standalone fake WebDriver checks. No TestNG execution, Chrome, HTTP client or JDBC. */
public final class UiNavigationProbe {
    private static final String LOGIN = "https://task-manager-test-588r.onrender.com/login";
    private static final String CANARY = "DO_NOT_EMIT_secret-<&>?";
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Offline navigation check " + checks); }
    private static final class Page extends BasePage {
        Page(WebDriver d) { super(d); }
        void open(String url) { navigate(url); }
        void firstElement() { findVisible(By.id("username")); }
    }
    private static final class Fake {
        String url = LOGIN;
        int gets, reads;
        boolean unavailable, missingElement;
        long readyAt;
        WebDriverException getFailure;
        Map<String, Object> data = new HashMap<>();
        Fake() {
            data.putAll(Map.of("state", "complete", "dom", 20, "chromeError", false, "error", "UNKNOWN", "markers", true,
                    "http", 200, "redirects", 0, "responseMs", 10, "durationMs", 30, "navigationType", "navigate"));
        }
        WebDriver driver() {
            WebElement element = (WebElement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WebElement.class},
                    (p,m,a) -> { if (m.getName().equals("isDisplayed")) return true; throw new AssertionError("Unexpected element command"); });
            return (WebDriver) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WebDriver.class, JavascriptExecutor.class, HasCapabilities.class},
                    (p,m,a) -> {
                        switch (m.getName()) {
                            case "get": gets++; if (getFailure != null) throw getFailure; return null;
                            case "getCurrentUrl": return url;
                            case "getTitle": return CANARY;
                            case "executeScript":
                                reads++;
                                if (unavailable) throw new WebDriverException(CANARY);
                                String script = (String) a[0];
                                if (script.equals("return document.readyState")) return data.get("state");
                                if (script.equals("return document.getElementsByTagName('*').length")) return data.get("dom");
                                if (System.nanoTime() < readyAt) {
                                    Map<String, Object> pending = new HashMap<>(data);
                                    pending.put("markers", false);
                                    return pending;
                                }
                                return data;
                            case "findElement": if (missingElement) throw new org.openqa.selenium.NoSuchElementException(CANARY); return element;
                            case "findElements": return missingElement ? List.of() : List.of(element);
                            case "getCapabilities": return new ImmutableCapabilities(Map.of(
                                    "browserName", "chrome", "browserVersion", "157.0.8103.38",
                                    "chrome", Map.of("chromedriverVersion", "156.0.8078.80 (" + CANARY + ")", "userDataDir", CANARY),
                                    "pageLoadStrategy", "normal", "acceptInsecureCerts", false,
                                    "timeouts", Map.of("implicit", 0, "pageLoad", 300000, "script", 30000),
                                    "proxy", Map.of("proxyType", "manual", "httpProxy", CANARY)));
                            case "toString": return CANARY;
                            default: throw new AssertionError("Unexpected driver command: " + m.getName());
                        }
                    });
        }
        NavigationDiagnostics.Snapshot snapshot() { return NavigationDiagnostics.snapshot(driver(), LOGIN); }
    }
    public static void main(String[] args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        List<String> attachments = new ArrayList<>();
        Allure.setLifecycle(new AllureLifecycle(new AllureResultsWriter() {
            public void write(TestResult r) { }
            public void write(TestResultContainer r) { }
            public void write(String source, InputStream content) {
                try { attachments.add(new String(content.readAllBytes(), StandardCharsets.UTF_8)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }));
        Allure.getLifecycle().scheduleTestCase(new TestResult().setUuid("offline").setName("offline"));
        Allure.getLifecycle().startTestCase("offline");
        try (PrintStream capture = new PrintStream(console, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            check(NavigationDiagnostics.safeUrl("data:,").equals("[DATA_EMPTY]"));
            check(NavigationDiagnostics.safeUrl("data:text/html," + CANARY).equals("[DATA_OTHER]"));
            check(NavigationDiagnostics.safeUrl("about:blank").equals("[ABOUT_BLANK]"));
            check(NavigationDiagnostics.safeUrl("about:" + CANARY).equals("[ABOUT_OTHER]"));
            for (String bad : List.of("https://prod.example/login", "http://task-manager-test-588r.onrender.com/login",
                    LOGIN + "?token=" + CANARY, LOGIN + "#" + CANARY, "https://user:password@task-manager-test-588r.onrender.com/login",
                    "https://task-manager-test-588r.onrender.com:444/login", "data:,", "about:blank")) {
                Fake f = new Fake();
                try { new Page(f.driver()).open(bad); throw new AssertionError("Target accepted"); }
                catch (IllegalArgumentException expected) { check(f.gets == 0); }
            }
            check(NavigationDiagnostics.safeUrl(LOGIN + "?token=DO_NOT_EMIT_secret").equals(LOGIN));
            Fake f = new Fake();
            check(f.snapshot().ready(LOGIN));
            check(f.snapshot().classification(LOGIN, null).equals("TEST_READY"));
            for (int status : new int[]{429, 502, 503}) {
                f.data.put("http", status);
                check(!f.snapshot().ready(LOGIN));
                check(f.snapshot().classification(LOGIN, null).equals("HTTP_ERROR"));
            }
            for (int status : new int[]{0, -1}) {
                f.data.put("http", status);
                check(f.snapshot().ready(LOGIN));
                check(f.snapshot().classification(LOGIN, null).equals("TEST_READY_HTTP_UNKNOWN"));
            }
            f.url = LOGIN + "?error";
            check(!f.snapshot().ready(LOGIN));
            f.url = "data:,";
            check(f.snapshot().classification(LOGIN, null).equals("DATA_DOCUMENT"));
            f.url = "about:blank";
            check(f.snapshot().classification(LOGIN, null).equals("ABOUT_DOCUMENT"));
            f.url = LOGIN;
            f.data.put("chromeError", true);
            check(!f.snapshot().ready(LOGIN));
            check(f.snapshot().classification(LOGIN, null).equals("CHROME_ERROR"));
            f.data.put("error", "ERR_NAME_NOT_RESOLVED");
            check(f.snapshot().classification(LOGIN, null).equals("NETWORK_ERROR"));
            f.data.put("error", "ERR_HTTP_RESPONSE_CODE_FAILURE");
            check(f.snapshot().classification(LOGIN, null).equals("HTTP_ERROR"));
            f.data.put("error", CANARY);
            check(f.snapshot().networkError().equals("UNKNOWN"));
            check(NavigationDiagnostics.networkError(new WebDriverException("net::ERR_CONNECTION_RESET " + CANARY)).equals("ERR_CONNECTION_RESET"));
            check(NavigationDiagnostics.networkError(new WebDriverException(CANARY)).equals("UNKNOWN"));
            f.unavailable = true;
            check(!f.snapshot().ready(LOGIN));
            check(f.snapshot().http() == -1);
            String environment = NavigationDiagnostics.environment(f.driver(), true);
            check(environment.contains("chromeVersion=157.0.8103.38"));
            check(environment.contains("chromeDriverVersion=156.0.8078.80"));
            check(environment.contains("proxyType=manual"));
            check(!environment.contains(CANARY));
            NavigationDiagnostics.attachSession(f.driver(), true);
            f = new Fake();
            Page page = new Page(f.driver());
            page.open(LOGIN);
            page.firstElement();
            check(f.gets == 1); // No retry of navigation or tests.
            Fake broken = new Fake();
            broken.getFailure = new WebDriverException("net::ERR_CONNECTION_RESET " + CANARY);
            try { new Page(broken.driver()).open(LOGIN); throw new AssertionError("Failure swallowed"); }
            catch (WebDriverException expected) { check(expected == broken.getFailure); }
            check(broken.gets == 1);
            Fake blank = new Fake();
            blank.url = "data:,"; blank.data.put("markers", false); blank.data.put("dom", 3); blank.data.put("http", 0);
            long start = System.nanoTime();
            try { new Page(blank.driver()).open(LOGIN); throw new AssertionError("Blank page accepted"); }
            catch (TimeoutException expected) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                check(ms >= 14500 && ms < 17500); // One existing wait budget, never 30 seconds.
            }
            check(blank.gets == 1);
            Fake delayed = new Fake();
            delayed.readyAt = System.nanoTime() + 1_000_000_000L;
            delayed.missingElement = true;
            start = System.nanoTime();
            Page delayedPage = new Page(delayed.driver());
            delayedPage.open(LOGIN);
            try { delayedPage.firstElement(); throw new AssertionError("Missing element accepted"); }
            catch (TimeoutException expected) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                check(ms >= 14500 && ms < 17500);
            }
            check(delayed.gets == 1);
            NavigationDiagnostics.attachNavigation(LOGIN, Instant.EPOCH, 1, 2, blank.snapshot(), blank.snapshot(), null);
            String secret = System.getenv("TEST_USER_PASSWORD");
            check(secret != null);
            String encoded = java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
            String base64 = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
            String redacted = UiFailureDiagnostics.redactConfiguredSecrets(secret + encoded + base64);
            check(!redacted.contains(secret) && !redacted.contains(encoded) && !redacted.contains(base64));
        } finally { System.setOut(original); }
        String output = console.toString(StandardCharsets.UTF_8);
        check(!output.contains(CANARY));
        check(attachments.stream().noneMatch(s -> s.contains(CANARY)));
        check(attachments.stream().anyMatch(s -> s.contains("classification=NETWORK_ERROR")));
        check(attachments.stream().anyMatch(s -> s.contains("classification=DATA_DOCUMENT")));
        check(attachments.stream().anyMatch(s -> s.contains("classification=TEST_READY")));
        System.out.println("PASS: " + checks + " offline navigation checks; fake driver only; no Chrome, HTTP or DB");
    }
}
