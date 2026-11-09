package ru.tarkovskiy.ui.base;

import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.AllureResultsWriter;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;
import io.qameta.allure.model.Status;
import org.openqa.selenium.*;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fake browser only; rejects every auth/input action and every URL except TEST /login. */
public final class UbuntuDiagnosticLauncherProbe {
    private static final String LOGIN = "https://task-manager-test-588r.onrender.com/login";
    private static final String PRIVATE = "DO_NOT_EMIT_private_token";
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Launcher check " + checks); }
    private static final class Fake {
        int gets, quits;
        boolean fail;
        WebDriver driver() {
            return (WebDriver) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WebDriver.class, JavascriptExecutor.class, HasCapabilities.class}, (p,m,a) -> {
                switch (m.getName()) {
                    case "get":
                        gets++;
                        check(LOGIN.equals(a[0]));
                        if (fail) throw new WebDriverException("net::ERR_CONNECTION_RESET " + PRIVATE);
                        return null;
                    case "quit": quits++; return null;
                    case "getCurrentUrl": return LOGIN;
                    case "getTitle": return PRIVATE;
                    case "getCapabilities": return new ImmutableCapabilities("browserVersion", "157.0.8103.38",
                            "chrome", Map.of("chromedriverVersion", "157.0.8103.38 (" + PRIVATE + ")"));
                    case "executeScript":
                        if (a[0].equals("return document.readyState")) return "complete";
                        if (a[0].equals("return document.getElementsByTagName('*').length")) return 20;
                        return Map.of("state", "complete", "dom", 20, "chromeError", false, "error", "UNKNOWN", "markers", true,
                                "http", 200, "redirects", 0, "responseMs", 10, "durationMs", 30, "navigationType", "navigate");
                    default: throw new AssertionError("Forbidden fake browser action: " + m.getName());
                }
            });
        }
    }
    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        List<Status> statuses = new ArrayList<>();
        List<String> attachments = new ArrayList<>();
        Allure.setLifecycle(new AllureLifecycle(new AllureResultsWriter() {
            public void write(TestResult result) { statuses.add(result.getStatus()); }
            public void write(TestResultContainer result) { }
            public void write(String name, InputStream content) {
                try { attachments.add(new String(content.readAllBytes(), StandardCharsets.UTF_8)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }));
        PrintStream original = System.out;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(console, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            for (String scenario : List.of("success", "chrome-failure", "render-not-ready")) {
                boolean ready = !scenario.equals("render-not-ready");
                boolean failure = !scenario.equals("success");
                Path part = Files.createDirectory(scratch.resolve(scenario));
                System.setProperty("tm.run.part", part.toString());
                Fake fake = new Fake(); fake.fail = scenario.equals("chrome-failure");
                int[] readinessCalls = {0}, browserCalls = {0};
                int code = UbuntuDiagnosticLauncher.navigation(() -> {
                    check(readinessCalls[0] == 1);
                    check(ready);
                    browserCalls[0]++;
                    return fake.driver();
                }, () -> {
                    readinessCalls[0]++;
                    check(browserCalls[0] == 0 && fake.gets == 0);
                    if (!ready) throw new IllegalStateException(PRIVATE);
                });
                check(code == (failure ? 1 : 0));
                check(readinessCalls[0] == 1);
                check(browserCalls[0] == (ready ? 1 : 0));
                check(fake.gets == (ready ? 1 : 0));
                check(fake.quits == (ready ? 1 : 0));
                check(Files.readString(part.resolve("completed.marker")).equals("finished"));
            }
        } finally { System.setOut(original); }
        check(statuses.equals(List.of(Status.PASSED, Status.BROKEN, Status.BROKEN)));
        check(!console.toString(StandardCharsets.UTF_8).contains(PRIVATE));
        check(attachments.stream().noneMatch(s -> s.contains(PRIVATE)));
        check(attachments.stream().anyMatch(s -> s.contains("classification=NETWORK_ERROR")));
        check(attachments.stream().anyMatch(s -> s.contains("UI_RENDER_READINESS\nstatus=READY\n")));
        check(attachments.stream().anyMatch(s -> s.contains("UI_RENDER_READINESS\nstatus=NOT_READY\n")));
        check(attachments.stream().anyMatch(s -> s.contains("phase=RENDER_READINESS_FAILED")));
        check(attachments.stream().anyMatch(s -> s.contains("phase=CHROME_NAVIGATION_FAILED")));
        System.out.println("PASS: " + checks + " standalone launcher checks; fake WebDriver; no TestNG suite, Chrome, HTTP or JDBC");
    }
}
