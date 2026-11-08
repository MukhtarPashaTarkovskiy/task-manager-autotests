package ru.tarkovskiy.ui.base;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StatusDetails;
import io.qameta.allure.model.TestResult;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.testng.*;
import ru.tarkovskiy.ui.config.UiConfig;
import ru.tarkovskiy.ui.config.RenderReadiness;
import ru.tarkovskiy.ui.db.DbConnection;
import ru.tarkovskiy.ui.pages.LoginPage;
import ru.tarkovskiy.ui.reporting.NavigationDiagnostics;
import ru.tarkovskiy.ui.reporting.UiFailureDiagnostics;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicit standalone entry point; never selected by All Tests or the regression package. */
public final class UbuntuDiagnosticLauncher {
    private UbuntuDiagnosticLauncher() { }

    public static void main(String[] args) {
        int code = 1;
        try {
            if (args.length != 1 || !List.of("NavigationOnly", "UiSmoke").contains(args[0]))
                throw new IllegalArgumentException("Invalid diagnostic mode");
            if (!"Linux".equals(System.getProperty("os.name")) || Runtime.version().feature() != 17
                    || !"true".equals(System.getenv("HEADLESS")) || !Boolean.getBoolean("tm.reporting.initialized"))
                throw new IllegalStateException("Linux Java 17 headless reporting environment required");
            NavigationDiagnostics.requireTarget(UiConfig.LOGIN_URL);
            Path driver = Path.of(required("UI_DIAGNOSTIC_DRIVER_PATH"));
            if (!Files.isExecutable(driver)) throw new IllegalStateException("Selected driver unavailable");
            System.setProperty("webdriver.chrome.driver", driver.toString());
            code = "NavigationOnly".equals(args[0]) ? navigation(UbuntuDiagnosticLauncher::newBrowser) : smoke();
        } catch (Throwable failure) {
            // Never print arbitrary WebDriver, TestNG or JDBC exception text.
            System.out.println("UI_UBUNTU_DIAGNOSTIC failed; inspect audited diagnostics");
        }
        System.exit(code);
    }

    private static WebDriver newBrowser() {
        ChromeOptions options = new ChromeOptions();
        options.setBinary(required("UI_DIAGNOSTIC_CHROME_PATH"));
        options.addArguments("--headless=new", "--window-size=1440,1000", "--disable-dev-shm-usage");
        ChromeDriverService service = new ChromeDriverService.Builder()
                .usingDriverExecutable(Path.of(required("UI_DIAGNOSTIC_DRIVER_PATH")).toFile())
                .withLogOutput(OutputStream.nullOutputStream()).build();
        return new ChromeDriver(service, options);
    }

    // No TestNG instance, BaseUiTest fixture, auth method, user/task operation or DB call in this path.
    static int navigation(Supplier<WebDriver> browser) throws Exception {
        return navigation(browser, () -> new RenderReadiness().waitForRender());
    }

    @FunctionalInterface
    interface Readiness { void await() throws Exception; }

    // Production uses RenderReadiness; offline probes inject a check without HTTP.
    static int navigation(Supplier<WebDriver> browser, Readiness readiness) throws Exception {
        String id = UUID.randomUUID().toString();
        var lifecycle = Allure.getLifecycle();
        lifecycle.scheduleTestCase(new TestResult().setUuid(id).setName("TEST login navigation only")
                .setFullName("UbuntuDiagnosticLauncher.NavigationOnly").setHistoryId("ubuntu-navigation-only"));
        lifecycle.startTestCase(id);
        WebDriver driver = null;
        boolean passed = false;
        boolean renderReady = false;
        String phase = "RENDER_READINESS";
        long readinessStart = System.nanoTime();
        try {
            readiness.await();
            renderReady = true;
            attachReadiness("READY", readinessStart);
            phase = "CHROME_START";
            driver = browser.get();
            NavigationDiagnostics.attachSession(driver, true);
            verifySession(driver);
            phase = "CHROME_NAVIGATION";
            new LoginPage(driver).open(UiConfig.BASE_URL);
            passed = true;
            phase = "COMPLETE";
        } catch (Throwable failure) {
            phase += "_FAILED";
            if (!renderReady) attachReadiness("NOT_READY", readinessStart);
            else UiFailureDiagnostics.attach(driver, null, failure);
        } finally {
            if (driver != null) {
                try { driver.quit(); }
                catch (RuntimeException failure) {
                    if (passed) phase = "CHROME_CLEANUP_FAILED";
                    passed = false;
                    UiFailureDiagnostics.attach(driver, null, failure);
                }
            }
            String stageReport = "UI_NAVIGATION_ONLY_STAGE\nphase=" + phase + "\n";
            System.out.println(stageReport);
            Allure.addAttachment("NavigationOnly stage", "text/plain", stageReport, ".txt");
            final boolean result = passed;
            final String failureMessage = renderReady ? "Chrome navigation/environment failed; see safe diagnostics"
                    : "TEST Render not ready; Chrome was not started";
            lifecycle.updateTestCase(id, test -> test.setStatus(result ? Status.PASSED : Status.BROKEN)
                    .setStatusDetails(result ? null : new StatusDetails().setMessage(failureMessage)));
            lifecycle.stopTestCase(id);
            lifecycle.writeTestCase(id);
            Files.writeString(Path.of(System.getProperty("tm.run.part")).resolve("completed.marker"), "finished", StandardOpenOption.CREATE_NEW);
        }
        System.out.println("UI_DIAGNOSTIC_RESULT mode=NavigationOnly passed=" + (passed ? 1 : 0) + " failed=" + (passed ? 0 : 1) + " skipped=0");
        return passed ? 0 : 1;
    }

    private static void attachReadiness(String status, long started) {
        // No exception text, response body, headers, credentials or arbitrary URL.
        String report = "UI_RENDER_READINESS\nstatus=" + status
                + "\ndurationMs=" + (System.nanoTime() - started) / 1_000_000 + "\n";
        System.out.println(report);
        Allure.addAttachment("TEST Render readiness", "text/plain", report, ".txt");
    }

    private static int smoke() {
        // Reuse the existing Java target validator; validation makes no connection.
        DbConnection.validateTarget(required("TEST_DB_URL"), required("TEST_ALLOWED_DB_HOST"), required("TEST_ALLOWED_DB_PORT"));
        required("TEST_USER_PASSWORD"); required("TEST_DB_USERNAME"); required("TEST_DB_PASSWORD");
        TestNG testng = new TestNG();
        testng.setUseDefaultListeners(false);
        testng.setOutputDirectory(Path.of(System.getProperty("tm.run.part")).resolve("testng-output").toString());
        testng.setTestSuites(List.of("ui-tests/smoke-tests.xml"));
        TestListenerAdapter counts = new TestListenerAdapter();
        testng.addListener((ITestNGListener) counts);
        testng.addListener(new IInvokedMethodListener() {
            @Override public void beforeInvocation(IInvokedMethod method, ITestResult result) {
                if (method.isTestMethod() && result.getInstance() instanceof BaseUiTest test) {
                    // Refuse business execution if the default system Chrome differs from the selected pair.
                    verifySession(test.driver);
                }
            }
        });
        testng.run();
        int passed = counts.getPassedTests().size(), failed = counts.getFailedTests().size(), skipped = counts.getSkippedTests().size();
        System.out.println("UI_DIAGNOSTIC_RESULT mode=UiSmoke passed=" + passed + " failed=" + failed + " skipped=" + skipped);
        // A skipped test or configuration failure must never make this diagnostic job green.
        return passed == 4 && failed == 0 && skipped == 0 && !testng.hasFailure() && !testng.hasSkip() ? 0 : 1;
    }

    static void verifySession(WebDriver driver) {
        var caps = ((HasCapabilities) driver).getCapabilities();
        Object chrome = caps.getCapability("chrome");
        String actualDriver = chrome instanceof Map<?, ?> m && m.get("chromedriverVersion") instanceof String s ? s.split(" ")[0] : "";
        String expectedChrome = required("UI_DIAGNOSTIC_CHROME_VERSION");
        String expectedDriver = required("UI_DIAGNOSTIC_DRIVER_VERSION");
        if (!expectedChrome.matches("[0-9]+(?:\\.[0-9]+){3}") || !expectedDriver.matches("[0-9]+(?:\\.[0-9]+){3}")
                || !expectedChrome.equals(caps.getBrowserVersion()) || !expectedDriver.equals(actualDriver)
                || !build(expectedChrome).equals(build(actualDriver)))
            throw new IllegalStateException("Actual browser pair differs from validated executables");
    }
    private static String build(String version) { return version.substring(0, version.lastIndexOf('.')); }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required diagnostic setting missing");
        return value;
    }
}
