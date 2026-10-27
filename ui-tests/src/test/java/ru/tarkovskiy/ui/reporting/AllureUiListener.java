package ru.tarkovskiy.ui.reporting;

import io.qameta.allure.listener.ContainerLifecycleListener;
import io.qameta.allure.listener.TestLifecycleListener;
import io.qameta.allure.model.ExecutableItem;
import io.qameta.allure.model.StatusDetails;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;
import ru.tarkovskiy.ui.base.BaseUiTest;

/** Removes parameter values from the serialized Allure model, not just its HTML view. */
public final class AllureUiListener implements TestLifecycleListener, ContainerLifecycleListener, IInvokedMethodListener {
    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult result) {
        if (method.isTestMethod() && result.getParameters().length > 0) {
            io.qameta.allure.Allure.parameter("Вариант набора", result.getMethod().getCurrentInvocationCount() + 1);
        }
    }

    @Override
    public void beforeTestWrite(TestResult result) {
        removeParameters(result);
    }

    @Override
    public void beforeContainerWrite(TestResultContainer container) {
        container.getBefores().forEach(this::removeParameters);
        container.getAfters().forEach(this::removeParameters);
    }

    private void removeParameters(ExecutableItem item) {
        // Deliberately fail closed for the prototype: also omit non-sensitive parameters.
        // Do not change TestNG arguments or historyId; separate provider cases stay distinct.
        item.getParameters().removeIf(parameter -> !"Вариант набора".equals(parameter.getName()));
        StatusDetails details = item.getStatusDetails();
        if (details != null) {
            details.setMessage(redact(details.getMessage()));
            details.setTrace(redact(details.getTrace()));
        }
        item.getSteps().forEach(this::removeParameters);
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult result) {
        // This callback runs before the subclass data-cleanup @AfterMethod.
        if (method.isTestMethod() && result.getThrowable() != null
                && result.getInstance() instanceof BaseUiTest test) {
            // Also cover direct WebDriverWait calls in tests; avoid a second snapshot.
            if (result.getThrowable() instanceof org.openqa.selenium.WebDriverException failure
                    && !Boolean.TRUE.equals(result.getAttribute(UiFailureDiagnostics.ATTACHED))) {
                test.attachFailureDiagnostics(failure);
            }
            test.attachFailureScreenshot();
        } else if (method.isTestMethod() && result.getStatus() == ITestResult.SUCCESS
                && result.getInstance() instanceof BaseUiTest test
                && java.util.Set.of("shouldLoginSuccessfully", "shouldRegisterNewUserSuccessfully",
                        "shouldLoginAfterRegistration", "shouldCreateTaskSuccessfully", "shouldCreateTaskWithAllFields",
                        "shouldEditTaskSuccessfully", "shouldDeleteTaskSuccessfully").contains(result.getMethod().getMethodName())) {
            test.attachScreenshot("Успешный сценарий: итоговое состояние");
        }
    }
    private String redact(String text) {
        if (text == null) return null;
        for (String key : new String[]{"TEST_USER_PASSWORD", "TEST_DB_PASSWORD", "TEST_DB_USERNAME", "TEST_DB_URL", "TEST_ALLOWED_DB_HOST"}) {
            String value = System.getenv(key);
            if (value != null && !value.isEmpty()) {
                text = text.replace(value, "[REDACTED]")
                        .replace(URLEncoder.encode(value, StandardCharsets.UTF_8), "[REDACTED]");
            }
        }
        return text.replaceAll("(?im)(authorization|cookie|set-cookie|x-csrf-token)\\s*[:=][^\\r\\n]*", "$1: [REDACTED]");
    }

}
