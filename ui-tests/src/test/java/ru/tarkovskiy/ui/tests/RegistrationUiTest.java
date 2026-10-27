package ru.tarkovskiy.ui.tests;

import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.annotations.Test;
import ru.tarkovskiy.ui.base.BaseUiTest;
import ru.tarkovskiy.ui.pages.LoginPage;
import org.testng.annotations.DataProvider;
import ru.tarkovskiy.ui.enums.RegistrationElement;
import ru.tarkovskiy.ui.pages.RegistrationPage;
import ru.tarkovskiy.ui.pages.TasksPage;
import ru.tarkovskiy.ui.utils.TestData;
import org.testng.annotations.AfterMethod;
import ru.tarkovskiy.ui.utils.TestDataCleanup;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;

import ru.tarkovskiy.ui.models.RegistrationRequest;
import ru.tarkovskiy.ui.utils.TestDataGenerator;
import ru.tarkovskiy.ui.db.UserDbHelper;

import static org.testng.Assert.*;

public class RegistrationUiTest extends BaseUiTest {

    private final TestDataCleanup cleanup = new TestDataCleanup();

    @AfterMethod(alwaysRun = true)
    public void cleanupTestData() throws Exception {
        cleanup.cleanup();
    }

    @Test
    public void shouldNavigateToRegistrationPage() {

        LoginPage loginPage = new LoginPage(driver);

        loginPage.open(BASE_URL)
                .clickRegistrationLink();

        assertEquals(
                driver.getCurrentUrl(),
                BASE_URL + "/register",
                "После нажатия ссылки не открылась страница регистрации"
        );
    }

    @DataProvider(name = "registrationElements")
    public Object[][] registrationElements() {
        return new Object[][]{
                {RegistrationElement.USERNAME},
                {RegistrationElement.EMAIL},
                {RegistrationElement.PASSWORD},
                {RegistrationElement.REGISTER_BUTTON},
                {RegistrationElement.LOGIN_LINK},
                {RegistrationElement.PAGE_TITLE}
        };
    }

    @Test(dataProvider = "registrationElements")
    public void shouldDisplayRegistrationElement(
            RegistrationElement element
    ) {
        RegistrationPage registrationPage = new RegistrationPage(driver);

        registrationPage.open(BASE_URL);

        assertTrue(
                registrationPage.isElementVisible(element),
                "Не отображается элемент регистрации: " + element
        );
    }

    @Test
    public void shouldNavigateToLoginPage() {

        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        LoginPage loginPage = registrationPage
                .open(BASE_URL)
                .clickLoginLink();

        assertEquals(
                loginPage.getCurrentUrl(),
                BASE_URL + "/login",
                "После нажатия ссылки «Войти» не открылась страница авторизации"
        );
    }

    @DataProvider(name = "emptyRegistrationFields")
    public Object[][] emptyRegistrationFields() {
        return new Object[][]{
                {"", "test@example.test", "ValidPass123!"},
                {"testuser", "", "ValidPass123!"},
                {"testuser", "test@example.test", ""},
                {"", "", ""}
        };
    }

    @Test(dataProvider = "emptyRegistrationFields")
    public void shouldRejectEmptyRegistrationFields(
            String username,
            String email,
            String password
    ) {
        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        registrationPage.open(BASE_URL)
                .enterUsername(username)
                .enterEmail(email)
                .enterPassword(password)
                .submit();

        RegistrationElement invalidElement;

        if (username.isEmpty()) {
            invalidElement = RegistrationElement.USERNAME;
        } else if (email.isEmpty()) {
            invalidElement = RegistrationElement.EMAIL;
        } else {
            invalidElement = RegistrationElement.PASSWORD;
        }

        assertTrue(
                registrationPage.isValueMissing(invalidElement),
                "Не обнаружена ошибка пустого обязательного поля: "
                        + invalidElement
        );

        assertEquals(
                registrationPage.getCurrentUrl(),
                BASE_URL + "/register",
                "При пустых обязательных полях регистрация не должна выполняться"
        );
    }

    @DataProvider(name = "usernameBoundaries")
    public Object[][] usernameBoundaries() {
        return new Object[][]{
                {"ab", 2, false},
                {"abc", 3, true},
                {"a".repeat(30), 30, true},
                {"a".repeat(31), 30, true}
        };
    }

    @Test(dataProvider = "usernameBoundaries")
    public void shouldValidateUsernameBoundaries(
            String username,
            int expectedLength,
            boolean expectedValid
    ) {
        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        registrationPage.open(BASE_URL)
                .enterUsername(username);

        assertEquals(
                registrationPage.getUsernameLength(),
                expectedLength,
                "Неверная длина логина после ввода"
        );

        assertEquals(
                registrationPage.isUsernameTooShort(),
                !expectedValid,
                "Некорректная проверка минимальной длины логина"
        );
    }

    @DataProvider(name = "passwordBoundaries")
    public Object[][] passwordBoundaries() {
        return new Object[][]{
                {"a".repeat(7), 7, false},
                {"a".repeat(8), 8, true},
                {"a".repeat(72), 72, true},
                {"a".repeat(73), 72, true}
        };
    }

    @Test(dataProvider = "passwordBoundaries")
    public void shouldValidatePasswordBoundaries(
            String password,
            int expectedLength,
            boolean expectedValid
    ) {
        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        registrationPage.open(BASE_URL)
                .enterPassword(password);

        assertEquals(
                registrationPage.getPasswordLength(),
                expectedLength,
                "Неверная длина пароля после ввода"
        );

        assertEquals(
                registrationPage.isPasswordTooShort(),
                !expectedValid,
                "Некорректная проверка минимальной длины пароля"
        );
    }

    @DataProvider(name = "invalidEmails")
    public Object[][] invalidEmails() {
        return new Object[][]{
                {"plainaddress"},
                {"user@"},
                {"@example.com"},
                {"user name@example.com"}
        };
    }

    @Test(dataProvider = "invalidEmails")
    public void shouldRejectInvalidEmail(String email) {

        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        registrationPage.open(BASE_URL)
                .enterEmail(email);

        assertTrue(
                registrationPage.isEmailTypeMismatch(),
                "Браузер не обнаружил некорректный формат email: " + email
        );
    }

    @Test
    public void shouldAcceptValidEmail() {

        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        registrationPage.open(BASE_URL)
                .enterEmail(TestData.VALID_EMAIL);

        assertFalse(
                registrationPage.isEmailTypeMismatch(),
                "Браузер ошибочно отклонил корректный email"
        );
    }

    @Test
    public void shouldRegisterNewUserSuccessfully() throws Exception {

        RegistrationRequest user = TestDataGenerator.newUser();

        // Запоминаем пользователя до отправки формы
        cleanup.track(user.email());

        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        registrationPage.open(BASE_URL)
                .register(
                        user.username(),
                        user.email(),
                        user.password()
                );

        // Ожидаем завершения регистрации и перехода на страницу входа
        new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(ExpectedConditions.urlContains("/login"));

        // Проверяем результат в интерфейсе
        assertEquals(
                registrationPage.getCurrentUrl(),
                BASE_URL + "/login",
                "После регистрации не выполнен ожидаемый переход"
        );

        // Проверяем создание пользователя в тестовой БД
        assertTrue(
                UserDbHelper.userExists(user.email()),
                "Зарегистрированный пользователь не найден в БД"
        );
    }

    @DataProvider(name = "duplicateRegistrationData")
    public Object[][] duplicateRegistrationData() {
        return new Object[][]{
                {
                        true,
                        false,
                        "Логин уже занят"
                },
                {
                        false,
                        true,
                        "Email уже зарегистрирован"
                },
                {
                        true,
                        true,
                        "Логин уже занят"
                }
        };
    }

    @Test(dataProvider = "duplicateRegistrationData")
    public void shouldRejectDuplicateRegistration(
            boolean duplicateUsername,
            boolean duplicateEmail,
            String expectedError
    ) throws Exception {

        RegistrationRequest user = TestDataGenerator.newUser();

        String username = duplicateUsername
                ? TestData.EXISTING_USERNAME
                : user.username();

        String email = duplicateEmail
                ? TestData.EXISTING_EMAIL
                : user.email();

        // Отслеживаем только новый email, не постоянного пользователя
        if (!duplicateEmail) {
            cleanup.track(user.email());
        }

        int usersCountBefore = UserDbHelper.countUsers();

        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        registrationPage.open(BASE_URL)
                .register(username, email, user.password());

        // Проверяем, что регистрация отклонена
        assertEquals(
                registrationPage.getRegistrationErrorMessage(),
                expectedError,
                "Неверное сообщение об ошибке регистрации"
        );

        // Проверяем, что остались на странице регистрации
        assertEquals(
                registrationPage.getCurrentUrl(),
                BASE_URL + "/register",
                "Приложение не должно перенаправлять пользователя"
        );

        int usersCountAfter = UserDbHelper.countUsers();

        assertEquals(
                usersCountAfter,
                usersCountBefore,
                "После отклонённой регистрации количество пользователей изменилось"
        );

        // Новый пользователь не должен появиться в БД
        assertFalse(
                UserDbHelper.userExists(user.email()),
                "После отклонённой регистрации создан новый пользователь"
        );
    }

    @Test
    public void shouldLoginAfterRegistration() throws Exception {

        RegistrationRequest user = TestDataGenerator.newUser();

        // Запоминаем пользователя для последующей очистки
        cleanup.track(user.email());

        // Регистрируем пользователя
        RegistrationPage registrationPage =
                new RegistrationPage(driver);

        registrationPage.open(BASE_URL)
                .register(
                        user.username(),
                        user.email(),
                        user.password()
                );

        // Проверяем переход на страницу авторизации
        new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(ExpectedConditions.urlToBe(BASE_URL + "/login"));

        // Проверяем, что пользователь создан в БД
        assertTrue(
                UserDbHelper.userExists(user.email()),
                "Пользователь не найден в БД после регистрации"
        );

        // Авторизуемся под только что созданным пользователем
        LoginPage loginPage = new LoginPage(driver);

        loginPage.login(user.username(), user.password())
                .waitForSuccessfulLogin(BASE_URL);

        // Проверяем успешную авторизацию
        assertEquals(
                loginPage.getCurrentUrl(),
                BASE_URL + "/tasks",
                "После авторизации пользователь не попал на страницу задач"
        );
    }
}
