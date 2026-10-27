package ru.tarkovskiy.ui.tests;

import org.testng.annotations.Test;
import ru.tarkovskiy.ui.base.BaseUiTest;
import ru.tarkovskiy.ui.enums.LoginElement;
import org.testng.annotations.DataProvider;
import ru.tarkovskiy.ui.pages.LoginPage;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import ru.tarkovskiy.ui.utils.TestData;

public class LoginUiTest extends BaseUiTest {

    @Test
    public void shouldOpenLoginPage() {
        loginPage.open(BASE_URL);
        assertEquals(
                loginPage.getCurrentUrl(),
                BASE_URL + "/login",
                "Открылась неверная страница"
        );
    }

    @DataProvider(name = "loginElements")
    public Object[][] loginElements() {
        return new Object[][]{
                {LoginElement.USERNAME},
                {LoginElement.PASSWORD},
                {LoginElement.LOGIN_BUTTON},
                {LoginElement.REGISTRATION_LINK},
                {LoginElement.PAGE_TITLE}
        };
    }

    @Test(dataProvider = "loginElements")
    public void shouldDisplayLoginElement(LoginElement element) {

        LoginPage loginPage = new LoginPage(driver);
        loginPage.open(BASE_URL);

        assertTrue(
                loginPage.isElementVisible(element),
                "Не отображается элемент: " + element
        );
    }

    @Test
    public void shouldLoginSuccessfully() {

        LoginPage loginPage = new LoginPage(driver);

        loginPage.open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        assertEquals(
                loginPage.getCurrentUrl(),
                BASE_URL + "/tasks",
                "После авторизации не открылась страница задач"
        );
    }

    @DataProvider(name = "invalidCredentials")
    public Object[][] invalidCredentials() {
        return new Object[][]{
                {
                        TestData.EXISTING_USERNAME,
                        TestData.INVALID_PASSWORD
                },
                {
                        TestData.NON_EXISTING_USERNAME,
                        TestData.INVALID_PASSWORD
                },
                {
                        TestData.NON_EXISTING_USERNAME,
                        TestData.getTestPassword()
                }
        };
    }

    @Test(dataProvider = "invalidCredentials")
    public void shouldRejectInvalidCredentials(
            String username,
            String password
    ) {
        LoginPage loginPage = new LoginPage(driver);

        loginPage.open(BASE_URL)
                .login(username, password)
                .waitForLoginError(BASE_URL);

        assertEquals(
                loginPage.getCurrentUrl(),
                BASE_URL + "/login?error",
                "При неверных учётных данных пользователь не должен войти"
        );

        assertEquals(
                loginPage.getLoginErrorMessage(),
                "Неверный логин или пароль",
                "Отображается неверное сообщение об ошибке авторизации"
        );
    }

    @DataProvider(name = "emptyCredentials")
    public Object[][] emptyCredentials() {
        return new Object[][]{
                {"", TestData.getTestPassword()},
                {TestData.EXISTING_USERNAME, ""},
                {"", ""}
        };
    }

    @Test(dataProvider = "emptyCredentials")
    public void shouldRejectEmptyCredentials(
            String username,
            String password
    ) {
        LoginPage loginPage = new LoginPage(driver);

        loginPage.open(BASE_URL)
                .enterUsername(username)
                .enterPassword(password)
                .submit();

        // Определяем, какое поле должно не пройти валидацию
        LoginElement invalidElement =
                username.isEmpty()
                        ? LoginElement.USERNAME
                        : LoginElement.PASSWORD;

        assertTrue(
                loginPage.isValueMissing(invalidElement),
                "Браузер должен блокировать отправку формы из-за пустого поля: "
                        + invalidElement
        );

        assertEquals(
                loginPage.getCurrentUrl(),
                BASE_URL + "/login",
                "При пустых обязательных полях форма не должна отправляться"
        );
    }
}
