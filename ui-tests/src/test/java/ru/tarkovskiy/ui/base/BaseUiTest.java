package ru.tarkovskiy.ui.base;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import io.qameta.allure.Allure;
import java.io.ByteArrayInputStream;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import ru.tarkovskiy.ui.pages.LoginPage;
import org.testng.annotations.BeforeSuite;
import ru.tarkovskiy.ui.config.RenderReadiness;
import ru.tarkovskiy.ui.config.UiConfig;

import java.time.Duration;

public class BaseUiTest {

    protected WebDriver driver;
    protected LoginPage loginPage;
    protected static final String BASE_URL = UiConfig.BASE_URL;

    public void attachFailureScreenshot() {
        attachScreenshot("Состояние браузера при падении");
    }

    public void attachFailureDiagnostics(org.openqa.selenium.WebDriverException failure) {
        ru.tarkovskiy.ui.reporting.UiFailureDiagnostics.attach(driver, null, failure);
    }

    public void attachScreenshot(String name) {
        if (!(driver instanceof TakesScreenshot screenshot)) {
            return;
        }
        try {
            Allure.addAttachment(name, "image/png",
                    new ByteArrayInputStream(screenshot.getScreenshotAs(OutputType.BYTES)), ".png");
        } catch (RuntimeException ignored) {
            // Reporting must never replace the original failure or interrupt cleanup.
            // Do not log exception text: driver diagnostics can contain sensitive data.
            System.err.println("Allure: скриншот недоступен; исходная ошибка сохранена.");
        }
    }

    @BeforeSuite(alwaysRun = true)
    public void waitForTestServer() throws Exception {
        new RenderReadiness().waitForRender();
    }

    @BeforeMethod
    public void setUp() {

        ChromeOptions options = new ChromeOptions();
        boolean headless = "true".equalsIgnoreCase(System.getenv("HEADLESS"));
        if (headless) {
            options.addArguments("--headless=new", "--window-size=1440,1000", "--disable-dev-shm-usage");
        } else {
            options.addArguments("--start-maximized");
        }

        driver = new ChromeDriver(options);
        loginPage = new LoginPage(driver);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {

        if (driver != null) {
            driver.quit();
        }
    }
}
