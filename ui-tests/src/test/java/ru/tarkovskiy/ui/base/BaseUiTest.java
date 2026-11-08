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

    public void attachFailureDiagnostics(Throwable failure) {
        ru.tarkovskiy.ui.reporting.UiFailureDiagnostics.attach(driver, null, failure);
    }

    public void attachScreenshot(String name) {
        ru.tarkovskiy.ui.reporting.SafeScreenshot.attach(driver, name);
    }

    @BeforeSuite(alwaysRun = true, description = "Подготовка: ожидание готовности TEST Render")
    public void waitForTestServer() throws Exception {
        new RenderReadiness().waitForRender();
    }

    @BeforeMethod(description = "Подготовка окружения и изолированного состояния сценария")
    public void setUp() {

        driver = null;
        loginPage = null;
        ChromeOptions options = new ChromeOptions();
        boolean headless = "true".equalsIgnoreCase(System.getenv("HEADLESS"));
        if (headless) {
            options.addArguments("--headless=new", "--window-size=1440,1000", "--disable-dev-shm-usage");
        } else {
            options.addArguments("--start-maximized");
        }

        driver = new ChromeDriver(options);
        ru.tarkovskiy.ui.reporting.NavigationDiagnostics.attachSession(driver, headless);
        loginPage = new LoginPage(driver);
    }

    @AfterMethod(alwaysRun = true, description = "Завершение: очистка ресурсов и тестовых данных")
    public void tearDown() {

        if (driver != null) {
            try { driver.quit(); } finally { driver = null; loginPage = null; }
        }
    }
}
