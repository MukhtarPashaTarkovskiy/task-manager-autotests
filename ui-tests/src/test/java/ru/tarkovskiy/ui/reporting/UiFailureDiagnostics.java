package ru.tarkovskiy.ui.reporting;

import io.qameta.allure.Allure;
import ru.tarkovskiy.ui.config.UiConfig;
import org.openqa.selenium.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A whitelist-only snapshot: never reads HTML, cookies, input values or HTTP headers. */
public final class UiFailureDiagnostics {
    public static final String ATTACHED = UiFailureDiagnostics.class.getName() + ".attached";
    private UiFailureDiagnostics() { }

    public static void attach(WebDriver driver, By locator, WebDriverException failure) {
        try {
            String report = "UI_FAILURE_DIAGNOSTICS\n"
                    + "url=" + read(() -> safeUrl(driver.getCurrentUrl())) + "\n"
                    + "readyState=" + read(() -> {
                        Object state = ((JavascriptExecutor) driver).executeScript("return document.readyState");
                        return Set.of("loading", "interactive", "complete").contains(state) ? state.toString() : "[UNAVAILABLE]";
                    }) + "\n"
                    + "title=" + read(() -> safeTitle(driver.getTitle())) + "\n"
                    + "domElements=" + read(() -> count(((JavascriptExecutor) driver)
                        .executeScript("return document.getElementsByTagName('*').length"))) + "\n"
                    + "locatorKnown=" + (locator != null) + "\n"
                    + "matchingElements=" + (locator == null ? "[UNKNOWN_LOCATOR]"
                        : read(() -> Integer.toString(driver.findElements(locator).size()))) + "\n"
                    + "exceptionType=" + failure.getClass().getName() + "\n"
                    + "exceptionMessage=" + safeMessage(failure) + "\n";
            report = redactConfiguredSecrets(report);
            // Identical sanitized content goes to Allure and the buffered/redacted CI console.
            System.out.println(report);
            Allure.addAttachment("UI failure diagnostics", "text/plain", report, ".txt");
            org.testng.ITestResult result = org.testng.Reporter.getCurrentTestResult();
            if (result != null) result.setAttribute(ATTACHED, Boolean.TRUE);
        } catch (RuntimeException ignored) {
            // Diagnostics must never replace the original navigation/wait exception.
            System.out.println("UI_FAILURE_DIAGNOSTICS unavailable; original exception retained.");
        }
    }

    private static String read(Supplier<String> action) {
        try { return action.get(); }
        catch (RuntimeException ignored) { return "[UNAVAILABLE]"; }
    }

    private static String safeUrl(String raw) {
        if ("about:blank".equals(raw)) return "[ABOUT_BLANK]";
        URI uri = URI.create(raw);
        if (!"https".equals(uri.getScheme())
                || !URI.create(UiConfig.BASE_URL).getHost().equals(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawPath() == null
                || !uri.getRawPath().matches("/(?:login|register|tasks(?:/new|/[0-9]+/edit)?)?")) {
            return "[UNAPPROVED_URL_OMITTED]";
        }
        return UiConfig.BASE_URL + uri.getRawPath();
    }

    private static String safeTitle(String title) {
        // Titles are free text and can reflect private input; unknown titles fail closed.
        return Set.of("", "Task Manager", "Вход", "Регистрация", "Мои задачи",
                "Вход | Task Manager", "Регистрация | Task Manager", "Задачи | Task Manager",
                "Task Manager - Login", "Task Manager - Register").contains(title)
                ? title : "[NON_ALLOWLISTED_TITLE_OMITTED]";
    }

    private static String count(Object value) {
        return value instanceof Number number && number.longValue() >= 0
                ? Long.toString(number.longValue()) : "[UNAVAILABLE]";
    }

    private static String safeMessage(WebDriverException failure) {
        // Selenium messages may contain capabilities, URLs, tokens and arbitrary page text.
        // Keep recognized literal timeout text; never emit the raw message as a fallback.
        String message = failure.getMessage();
        if (message == null) return "[EMPTY_MESSAGE]";
        Matcher timeout = Pattern.compile("Expected condition failed: waiting for (?:visibility of element located by By\\.(?:id: (?:username|password|email|title|description|status|priority|deadline)|tagName: h1)|element to be clickable: By\\.cssSelector: a\\[href='/(?:login|register)'\\]) \\(tried for [0-9]+ second\\(s\\) with [0-9]+ milliseconds interval\\)").matcher(message);
        if (timeout.find()) return timeout.group();
        Matcher browserError = Pattern.compile("timeout: Timed out receiving message from renderer(?:: [0-9.]+)?|unknown error: net::(?:ERR_CONNECTION_(?:RESET|REFUSED|CLOSED|TIMED_OUT)|ERR_NAME_NOT_RESOLVED|ERR_TIMED_OUT|ERR_INTERNET_DISCONNECTED|ERR_EMPTY_RESPONSE)|tab crashed|invalid session id|disconnected: not connected to DevTools|chrome not reachable|no such window: target window already closed").matcher(message);
        if (browserError.find()) return browserError.group();
        if (failure instanceof TimeoutException) return "TimeoutException: navigation/wait timed out; non-allowlisted message omitted";
        if (failure instanceof NoSuchElementException) return "NoSuchElementException: element not found; non-allowlisted message omitted";
        return "WebDriver command failed; non-allowlisted message omitted";
    }

    private static String redactConfiguredSecrets(String text) {
        for (String key : new String[]{"TEST_USER_PASSWORD", "TEST_DB_PASSWORD", "TEST_DB_USERNAME", "TEST_DB_URL", "TEST_ALLOWED_DB_HOST"}) {
            String value = System.getenv(key);
            if (value != null && !value.isEmpty()) {
                for (String form : new String[]{value, URLEncoder.encode(value, StandardCharsets.UTF_8),
                        Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))}) {
                    text = text.replace(form, "[REDACTED]");
                }
            }
        }
        return text;
    }
}
