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

        // 1. Получаем пароль из переменной окружения
        String password = System.getenv("TEST_USER_PASSWORD");

        assertNotNull(
                password,
                "Не задана переменная TEST_USER_PASSWORD"
        );

        assertFalse(
                password.isBlank(),
                "Пароль тестового пользователя пуст"
        );

        // 2. Открываем страницу входа
        LoginPage loginPage = new LoginPage(driver);

        loginPage.open(BASE_URL);

        // 3. Авторизуемся постоянным тестовым пользователем
        loginPage.login("autotest_user", password);

        // 4. Ожидаем переход на страницу задач
        loginPage.waitForSuccessfulLogin(BASE_URL);

        // 5. Проверяем итоговый адрес
        assertEquals(
                loginPage.getCurrentUrl(),
                BASE_URL + "/tasks",
                "После авторизации не открылась страница задач"
        );
    }
}
