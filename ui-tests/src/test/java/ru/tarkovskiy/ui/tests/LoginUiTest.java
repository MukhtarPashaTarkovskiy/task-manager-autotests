package ru.tarkovskiy.ui.tests;

import io.qameta.allure.Allure;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.qameta.allure.Step;

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

    @Test(description = "UI: Открытие страницы входа")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Открытие страницы входа")
    @Severity(SeverityLevel.NORMAL)
    @Description("Открытие страницы входа. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Открытие страницы входа")
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

    @Test(dataProvider = "loginElements", description = "UI: Отображение элементов формы входа")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Отображение элементов формы входа")
    @Severity(SeverityLevel.NORMAL)
    @Description("Отображение элементов формы входа. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Отображение элементов формы входа")
    public void shouldDisplayLoginElement(LoginElement element) {

        LoginPage loginPage = new LoginPage(driver);
        loginPage.open(BASE_URL);

        assertTrue(
                loginPage.isElementVisible(element),
                "Не отображается элемент: " + element
        );
    }

    @Test(description = "UI: Вход и переход к списку задач")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Вход и переход к списку задач")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Вход и переход к списку задач. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Вход и переход к списку задач")
    public void shouldLoginSuccessfully() {

        LoginPage loginPage = new LoginPage(driver);

        Allure.step("Открыть форму входа и авторизоваться тестовым пользователем", () -> {
            loginPage.open(BASE_URL)
                    .login(
                            TestData.EXISTING_USERNAME,
                            TestData.getTestPassword()
                    )
                    .waitForSuccessfulLogin(BASE_URL);
        });

        Allure.step("Проверить переход на страницу задач", () -> {
            assertEquals(
                    loginPage.getCurrentUrl(),
                    BASE_URL + "/tasks",
                    "После авторизации не открылась страница задач"
            );
        });
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

    @Test(dataProvider = "invalidCredentials", description = "UI: Отклонение неверных учётных данных")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Отклонение неверных учётных данных")
    @Severity(SeverityLevel.NORMAL)
    @Description("Отклонение неверных учётных данных. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Отклонение неверных учётных данных")
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

    @Test(dataProvider = "emptyCredentials", description = "UI: Проверка обязательных полей входа")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Проверка обязательных полей входа")
    @Severity(SeverityLevel.NORMAL)
    @Description("Проверка обязательных полей входа. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Проверка обязательных полей входа")
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
