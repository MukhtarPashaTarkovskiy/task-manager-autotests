package ru.tarkovskiy.reporting;

import io.qameta.allure.Allure;
import io.qameta.allure.Step;
import org.testng.TestNG;
import org.testng.annotations.*;
import java.nio.file.*;

/** Explicit main only; never selected by Surefire's integration-test discovery. No network or DB. */
public final class ReportingProbe {
    public static class Scenario {
        @DataProvider public Object[][] cases() { return new Object[][]{{"probe-secret-123"},{"probe-secret-456"}}; }
        @Test(dataProvider="cases", description="Автономная проверка шагов и параметров")
        public void scenario(String secret) {
            annotatedStep();
            try { Class.forName("ru.tarkovskiy.ui.reporting.UiReportingProbe").getMethod("verify").invoke(null); }
            catch (ClassNotFoundException ignored) { /* API module has no browser classes. */ }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            Checks.assertTrue(true, "Проверить результат автономного действия");
            Allure.step("Проверить запись явного шага", () -> { });
        }
        @Step("Шаг через AspectJ") public void annotatedStep() { }
    }
    public static class FailureScenario {
        @Test(description="Автономная проверка ошибки") public void failure() {
            throw new IllegalStateException("password=probe-secret-123; arbitrary-private-response");
        }
        @AfterMethod(alwaysRun=true) public void cleanup() { throw new IllegalStateException("private-cleanup-value"); }
    }
    public static void main(String[] args) throws Exception {
        TestNG testng = new TestNG();
        testng.setUseDefaultListeners(false); testng.setVerbose(0);
        testng.setTestClasses(new Class<?>[]{Scenario.class, FailureScenario.class});
        testng.run();
        Path results = Path.of(System.getProperty("allure.results.directory"));
        StringBuilder all = new StringBuilder();
        int tests = 0;
        try (var files = Files.list(results)) {
            for (Path path : files.toList()) {
                if (path.toString().endsWith(".json") || path.toString().endsWith(".txt")) all.append(Files.readString(path));
                if (path.toString().endsWith("-result.json")) tests++;
            }
        }
        String text = all.toString();
        require(tests >= 3, "TestNG results not written");
        require(text.contains("Шаг через AspectJ"), "AspectJ steps missing");
        require(text.contains("Проверить запись явного шага"), "Explicit steps missing");
        require(text.contains("Вариант набора"), "Safe case identities missing");
        require(text.contains("java.lang.IllegalStateException"), "Exception type missing");
        require(text.contains("afters"), "Cleanup fixture missing");
        for (String secret : new String[]{"probe-secret-123","probe-secret-456","arbitrary-private-response","private-cleanup-value"})
            require(!text.contains(secret), "Synthetic sensitive value leaked");
        require(Files.exists(results.getParent().resolve("completed.marker")), "Completion marker missing");
        if (text.contains("Проверка PNG")) {
            require(!text.contains("Повторный PNG должен быть исключён"), "Duplicate screenshot");
            require(text.contains("WebDriver не создан"), "Missing screenshot reason");
            require(!text.contains("must-not-appear"), "URL query leaked");
        }
        System.out.println("PASS: TestNG, @Step/Allure.step, fixtures, safe parameters and traces. " + tests + " result records.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
