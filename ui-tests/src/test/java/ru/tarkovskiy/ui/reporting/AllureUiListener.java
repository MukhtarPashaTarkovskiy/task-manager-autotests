package ru.tarkovskiy.ui.reporting;
import org.testng.IInvokedMethod;
import org.testng.ITestResult;
import ru.tarkovskiy.ui.base.BaseUiTest;
public final class AllureUiListener extends ru.tarkovskiy.reporting.ReportingListener {
    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult result) {
        super.afterInvocation(method, result);
        // This callback runs before the subclass data-cleanup @AfterMethod.
        if (result.getThrowable() != null
                && result.getInstance() instanceof BaseUiTest test) {
            // Also cover direct WebDriverWait calls in tests; avoid a second snapshot.
            if (!Boolean.TRUE.equals(result.getAttribute(UiFailureDiagnostics.ATTACHED))) {
                test.attachFailureDiagnostics(result.getThrowable());
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
}
