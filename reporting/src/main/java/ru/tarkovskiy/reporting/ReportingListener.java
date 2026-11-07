package ru.tarkovskiy.reporting;

import io.qameta.allure.Allure;
import io.qameta.allure.listener.*;
import io.qameta.allure.model.*;
import org.testng.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public class ReportingListener implements TestLifecycleListener, ContainerLifecycleListener, IInvokedMethodListener, IExecutionListener {
    private final Map<String,AtomicInteger> variants = new ConcurrentHashMap<>();
    public ReportingListener() {
        // Applied during SPI discovery, before TestNG installs its default file reporters.
        if (TestNG.getDefault() != null) TestNG.getDefault().setUseDefaultListeners(false);
        if (!Boolean.getBoolean("tm.reporting.initialized")) {
            throw new IllegalStateException("Подключите reporting-agent.jar в VM options TestNG; см. docs/allure-reporting.md. Тесты не запущены.");
        }
        Path expected = Path.of(System.getProperty("tm.run.part")).resolve("allure-results");
        if (!expected.equals(Path.of(System.getProperty("allure.results.directory")))) {
            throw new IllegalStateException("Не переопределяйте allure.results.directory: каталог выбирается по Run ID");
        }
    }
    @Override public void onExecutionStart() { System.out.println("Allure Run ID: " + System.getProperty("tm.run.id")); }
    @Override public void onExecutionFinish() {
        try { Files.writeString(Path.of(System.getProperty("tm.run.part")).resolve("completed.marker"), "finished", StandardOpenOption.CREATE_NEW); }
        catch (java.io.IOException e) { throw new IllegalStateException("Не удалось зафиксировать завершение отчётности", e); }
        try {
            Path part = Path.of(System.getProperty("tm.run.part"));
            LatestAllurePublisher.publish(part.getParent().getParent().getParent().getParent(), System.getProperty("tm.run.id"), part.getFileName().toString());
        } catch (Exception failure) {
            // Reporting infrastructure must not change the outcome of the business scenario.
            System.err.println("Allure: подготовка кнопок Maven не завершена; предыдущий отчёт сохранён. Тип: " + failure.getClass().getSimpleName());
        }
    }
    @Override public void beforeInvocation(IInvokedMethod method, ITestResult result) {
        if (!method.isTestMethod()) return;
        String name = result.getMethod().getQualifiedName();
        int variant = result.getParameters().length == 0 ? 0 : variants.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        for (Object arg : result.getParameters()) if (arg instanceof String text) ReportSafety.remember(text);
        Allure.getLifecycle().updateTestCase(test -> {
            test.getParameters().clear();
            if (variant > 0) test.getParameters().add(new Parameter().setName("Вариант набора").setValue(Integer.toString(variant)));
            // Stable case identity contains neither credentials nor a run ID.
            test.setHistoryId(hash(name + ":" + variant));
            test.getLabels().add(new Label().setName("runId").setValue(System.getProperty("tm.run.id")));
        });
    }
    @Override public void afterInvocation(IInvokedMethod method, ITestResult result) {
        if (result.getThrowable() == null) return;
        String phase = method.isTestMethod() ? "Основной сценарий" :
            result.getMethod().isAfterMethodConfiguration() || result.getMethod().isAfterClassConfiguration() || result.getMethod().isAfterSuiteConfiguration() ? "Очистка" : "Подготовка";
        Throwable failure = result.getThrowable();
        Allure.addAttachment("Ошибка: " + phase, "text/plain",
            "Метод: " + result.getMethod().getQualifiedName() + "\nЭтап: " + phase +
            "\nТип: " + failure.getClass().getName() + "\n" + ReportSafety.trace(failure), ".txt");
    }
    @Override public void beforeTestWrite(TestResult result) { sanitize(result); }
    @Override public void beforeContainerWrite(TestResultContainer container) {
        container.getBefores().forEach(this::sanitize); container.getAfters().forEach(this::sanitize);
    }
    protected void sanitize(ExecutableItem item) {
        item.getParameters().removeIf(p -> !"Вариант набора".equals(p.getName()));
        if (item.getStatusDetails() != null) {
            var details = item.getStatusDetails();
            details.setTrace(ReportSafety.safeTrace(details.getTrace()));
            details.setMessage("Ошибка выполнения. Тип и стек — в диагностике; произвольный текст исключён для защиты данных.");
        }
        item.getSteps().forEach(this::sanitize);
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
