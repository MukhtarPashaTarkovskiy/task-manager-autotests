package ru.tarkovskiy.ui.pages;

import io.qameta.allure.Step;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import ru.tarkovskiy.ui.base.BasePage;
import ru.tarkovskiy.ui.config.UiConfig;
import ru.tarkovskiy.ui.enums.RegistrationElement;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import ru.tarkovskiy.ui.enums.RegistrationElement;

public class RegistrationPage extends BasePage {

    private static final String REGISTER_URL = "/register";
    private final By usernameInput = By.id("username");
    private final By emailInput = By.id("email");
    private final By passwordInput = By.id("password");
    private final By registerButton = By.cssSelector("form.auth-form button[type='submit']");
    private final By loginLink = By.cssSelector("a[href='/login']");
    private final By pageTitle = By.cssSelector(".auth-header h1");
    private final By errorMessage = By.cssSelector("[role='alert'].alert-error");

    public RegistrationPage(WebDriver driver) {
        super(driver);
    }

    @Step("Открыть страницу")
    public RegistrationPage open(String baseUrl) {
        driver.get(baseUrl + REGISTER_URL);
        return this;
    }

    @Step("Заполнить логин")
    public RegistrationPage enterUsername(String username) {
        type(usernameInput, username);
        return this;
    }

    @Step("Заполнить email")
    public RegistrationPage enterEmail(String email) {
        type(emailInput, email);
        return this;
    }

    @Step("Заполнить пароль без записи значения")
    public RegistrationPage enterPassword(String password) {
        type(passwordInput, password);
        return this;
    }

    @Step("Отправить форму")
    public RegistrationPage submit() {
        click(registerButton);
        return this;
    }

    @Step("Зарегистрировать пользователя")
    public RegistrationPage register(
            String username,
            String email,
            String password
    ) {
        return enterUsername(username)
                .enterEmail(email)
                .enterPassword(password)
                .submit();
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public boolean isElementVisible(RegistrationElement element) {

        By locator = switch (element) {
            case USERNAME -> usernameInput;
            case EMAIL -> emailInput;
            case PASSWORD -> passwordInput;
            case REGISTER_BUTTON -> registerButton;
            case LOGIN_LINK -> loginLink;
            case PAGE_TITLE -> pageTitle;
        };

        return findVisible(locator).isDisplayed();
    }

    @Step("Перейти ко входу")
    public LoginPage clickLoginLink() {
        click(loginLink);

        wait.until(
                ExpectedConditions.urlToBe(
                        UiConfig.BASE_URL + "/login"
                )
        );

        return new LoginPage(driver);
    }

    public String getInputAttribute(
            RegistrationElement element,
            String attributeName
    ) {
        By locator = switch (element) {
            case USERNAME -> usernameInput;
            case EMAIL -> emailInput;
            case PASSWORD -> passwordInput;
            default -> throw new IllegalArgumentException(
                    "Элемент не является полем ввода: " + element
            );
        };

        return findVisible(locator).getDomAttribute(attributeName);
    }

    public boolean isValueMissing(RegistrationElement element) {

        By locator = switch (element) {
            case USERNAME -> usernameInput;
            case EMAIL -> emailInput;
            case PASSWORD -> passwordInput;
            default -> throw new IllegalArgumentException(
                    "Элемент не является полем ввода: " + element
            );
        };

        WebElement input = findVisible(locator);

        JavascriptExecutor js = (JavascriptExecutor) driver;

        return (Boolean) js.executeScript(
                "return arguments[0].validity.valueMissing;",
                input
        );
    }

    public int getUsernameLength() {
        String value = findVisible(usernameInput)
                .getDomProperty("value");

        return value.length();
    }

    public boolean isUsernameTooShort() {
        WebElement input = findVisible(usernameInput);

        JavascriptExecutor js = (JavascriptExecutor) driver;

        return (Boolean) js.executeScript(
                "return arguments[0].validity.tooShort;",
                input
        );
    }

    public int getPasswordLength() {
        String value = findVisible(passwordInput)
                .getDomProperty("value");

        return value.length();
    }

    public boolean isPasswordTooShort() {
        WebElement input = findVisible(passwordInput);

        JavascriptExecutor js = (JavascriptExecutor) driver;

        return (Boolean) js.executeScript(
                "return arguments[0].validity.tooShort;",
                input
        );
    }

    public boolean isEmailTypeMismatch() {
        WebElement input = findVisible(emailInput);

        JavascriptExecutor js = (JavascriptExecutor) driver;

        return (Boolean) js.executeScript(
                "return arguments[0].validity.typeMismatch;",
                input
        );
    }

    public String getRegistrationErrorMessage() {
        return findVisible(errorMessage).getText();
    }
}