package ru.tarkovskiy.ui.base;

import org.openqa.selenium.WebDriver;
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
    protected WebDriverWait wait;
    protected LoginPage loginPage;
    protected static final String BASE_URL = UiConfig.BASE_URL;

    @BeforeSuite(alwaysRun = true)
    public void waitForTestServer() throws Exception {
        new RenderReadiness().waitForRender();
    }

    @BeforeMethod
    public void setUp() {

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");

        driver = new ChromeDriver(options);
        loginPage = new LoginPage(driver);
        wait = new WebDriverWait(
                driver,
                Duration.ofSeconds(15)
        );
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {

        if (driver != null) {
            driver.quit();
        }
    }
}