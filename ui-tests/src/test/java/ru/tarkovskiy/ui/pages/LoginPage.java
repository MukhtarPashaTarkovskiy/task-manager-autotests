package ru.tarkovskiy.ui.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import ru.tarkovskiy.ui.base.BasePage;
import ru.tarkovskiy.ui.enums.LoginElement;

import static ru.tarkovskiy.ui.enums.LoginElement.*;

public class LoginPage extends BasePage {

    private static final String LOGIN_URL = "/login";
    private final By usernameInput = By.id("username");

    private final By passwordInput = By.id("password");

    private final By loginButton =
            By.cssSelector("form.auth-form button[type='submit']");

    private final By registrationLink =
            By.cssSelector("a[href='/register']");

    private final By pageTitle =
            By.tagName("h1");

    public LoginPage(WebDriver driver) {
        super(driver);
    }

    public LoginPage open(String baseUrl) {
        driver.get(baseUrl + LOGIN_URL);
        return this;
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public boolean isElementVisible(LoginElement element) {
        By locator = switch (element) {
            case USERNAME -> usernameInput;
            case PASSWORD -> passwordInput;
            case LOGIN_BUTTON -> loginButton;
            case REGISTRATION_LINK -> registrationLink;
            case PAGE_TITLE -> pageTitle;
        };

        return findVisible(locator).isDisplayed();
    }

    public LoginPage waitForSuccessfulLogin(String baseUrl) {
        wait.until(
                ExpectedConditions.urlToBe(baseUrl + "/tasks")
        );
        return this;
    }

    public LoginPage enterUsername(String username) {
        type(usernameInput, username);
        return this;
    }

    public LoginPage enterPassword(String password) {
        type(passwordInput, password);
        return this;
    }

    public LoginPage submit() {
        click(loginButton);
        return this;
    }

    public LoginPage login(String username, String password) {
        enterUsername(username);
        enterPassword(password);
        submit();
        return this;
    }
}