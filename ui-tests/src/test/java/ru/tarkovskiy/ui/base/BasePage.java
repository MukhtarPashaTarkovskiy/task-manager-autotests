package ru.tarkovskiy.ui.base;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriverException;
import ru.tarkovskiy.ui.reporting.UiFailureDiagnostics;
import java.util.function.Function;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public abstract class BasePage {

    protected final WebDriver driver;
    protected final WebDriverWait wait;

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
        try { driver.get(url); }
        catch (WebDriverException failure) {
            UiFailureDiagnostics.attach(driver, null, failure);
            throw failure;
        }
    }

    protected <T> T await(Function<? super WebDriver, T> condition) {
        return await(condition, null);
    }

    private <T> T await(Function<? super WebDriver, T> condition, By locator) {
        try { return wait.until(condition); }
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
