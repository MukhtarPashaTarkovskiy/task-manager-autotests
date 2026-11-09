package ru.tarkovskiy.ui.reporting;
import org.openqa.selenium.*;
import java.lang.reflect.Proxy;
import java.util.Base64;

/** Synthetic driver only: does not start a browser, Selenium Manager or a network connection. */
public final class UiReportingProbe {
    public static void verify() {
        WebElement mask = (WebElement) Proxy.newProxyInstance(UiReportingProbe.class.getClassLoader(), new Class<?>[]{WebElement.class}, (p,m,a) -> {
            if (m.getName().equals("getScreenshotAs")) return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ZkAAAAASUVORK5CYII=");
            return null;
        });
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(UiReportingProbe.class.getClassLoader(), new Class<?>[]{WebDriver.class,JavascriptExecutor.class}, (p,m,a) -> {
            if (m.getName().equals("getCurrentUrl")) return "https://task-manager-test-588r.onrender.com/login?token=must-not-appear";
            if (m.getName().equals("executeScript")) return mask;
            return null;
        });
        SafeScreenshot.attach(driver, "Проверка PNG");
        SafeScreenshot.attach(driver, "Повторный PNG должен быть исключён");
        SafeScreenshot.attach(null, "Недоступный браузер");
    }
}
