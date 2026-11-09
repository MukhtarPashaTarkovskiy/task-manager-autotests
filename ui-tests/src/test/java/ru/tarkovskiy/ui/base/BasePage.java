package ru.tarkovskiy.ui.base;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriverException;
import ru.tarkovskiy.ui.reporting.UiFailureDiagnostics;
import ru.tarkovskiy.ui.reporting.NavigationDiagnostics;
import java.util.function.Function;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.Instant;

public abstract class BasePage {

    protected final WebDriver driver;
    protected final WebDriverWait wait;
    // Readiness and the first element wait share the existing 15-second post-get budget.
    private long navigationDeadline;

    public BasePage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(
                driver,
                Duration.ofSeconds(15)
        );
    }

    protected WebElement findVisible(By locator) {
        return await(ExpectedConditions.visibilityOfElementLocated(locator), locator);
    }

    protected WebElement findClickable(By locator) {
        return await(ExpectedConditions.elementToBeClickable(locator), locator);
    }

    protected void navigate(String url) {
        NavigationDiagnostics.requireTarget(url);
        Instant started = Instant.now();
        long start = System.nanoTime();
        long getMillis = -1;
        NavigationDiagnostics.Snapshot initial = null;
        NavigationDiagnostics.Snapshot[] last = {null};
        Throwable error = null;
        navigationDeadline = 0;
        try {
            driver.get(url);
            getMillis = (System.nanoTime() - start) / 1_000_000;
            navigationDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            initial = NavigationDiagnostics.snapshot(driver, url);
            last[0] = initial;
            new WebDriverWait(driver, Duration.ofNanos(Math.max(0, navigationDeadline - System.nanoTime()))).until(d -> {
                last[0] = NavigationDiagnostics.snapshot(d, url);
                return last[0].ready(url);
            });
        }
        catch (WebDriverException failure) {
            error = failure;
            UiFailureDiagnostics.attach(driver, null, failure);
            throw failure;
        } finally {
            // Diagnostic failures must never change the result of navigation.
            try {
                if (last[0] == null) last[0] = NavigationDiagnostics.snapshot(driver, url);
                if (initial == null) initial = last[0];
                NavigationDiagnostics.attachNavigation(url, started, getMillis,
                        (System.nanoTime() - start) / 1_000_000, initial, last[0], error);
            } catch (RuntimeException ignored) {
                System.out.println("UI_NAVIGATION unavailable; original result retained");
            }
        }
    }

    protected <T> T await(Function<? super WebDriver, T> condition) {
        return await(condition, null);
    }

    private <T> T await(Function<? super WebDriver, T> condition, By locator) {
        WebDriverWait currentWait = wait;
        if (navigationDeadline != 0) {
            long remaining = Math.max(0, navigationDeadline - System.nanoTime());
            navigationDeadline = 0;
            currentWait = new WebDriverWait(driver, Duration.ofNanos(remaining));
        }
        try { return currentWait.until(condition); }
        catch (WebDriverException failure) {
            UiFailureDiagnostics.attach(driver, locator, failure);
            throw failure;
        }
    }

    protected void type(By locator, String text) {
        WebElement element = findVisible(locator);
        element.clear();
        element.sendKeys(text);
    }

    protected void click(By locator) {
        findClickable(locator).click();
    }
}
