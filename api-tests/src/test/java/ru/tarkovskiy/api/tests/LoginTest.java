package ru.tarkovskiy.api.tests;

import io.qameta.allure.Allure;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.qameta.allure.Step;

import io.restassured.response.Response;
import org.testng.annotations.Test;
import ru.tarkovskiy.api.base.BaseApiTest;
import ru.tarkovskiy.api.clients.AuthClient;
import ru.tarkovskiy.api.utils.TestData;

import static org.hamcrest.Matchers.containsString;

public class LoginTest extends BaseApiTest {

    @Test(description = "API: Вход с корректными учётными данными")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Вход с корректными учётными данными")
    @Severity(SeverityLevel.NORMAL)
    @Description("Вход с корректными учётными данными. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Вход с корректными учётными данными")
    public void shouldLoginWithValidCredentials() {

        String username = TestData.EXISTING_USERNAME;
        String password = System.getenv("TEST_USER_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Не задана переменная TEST_USER_PASSWORD"
            );
        }

        AuthClient authClient = new AuthClient();

        Response response = Allure.step("POST /login: войти тестовым пользователем",
                () -> authClient.login(username, password));

        Allure.step("Проверить HTTP 302 и перенаправление на /tasks", () -> {
            response.then()
                    .statusCode(302)
                    .header("Location", containsString("/tasks"));
        });

        Allure.step("GET /api/tasks: проверить HTTP 200 после входа", () -> {
            authClient.getTasks()
                    .then()
                    .statusCode(200);
        });
    }

    @Test(description = "API: Отклонение неверного пароля")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Отклонение неверного пароля")
    @Severity(SeverityLevel.NORMAL)
    @Description("Отклонение неверного пароля. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Отклонение неверного пароля")
    public void shouldNotLoginWithInvalidPassword() {

        AuthClient authClient = new AuthClient();

        Response response = authClient.login(
                TestData.EXISTING_USERNAME,
                TestData.INVALID_PASSWORD
        );

        response.then()
                .statusCode(302)
                .header(
                        "Location",
                        containsString("/login?error")
                );

        Response tasksResponse = authClient.getTasks();

        tasksResponse.then()
                .statusCode(302)
                .header(
                        "Location",
                        containsString("/login")
                );
    }

    @Test(description = "API: Отклонение несуществующего пользователя")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Отклонение несуществующего пользователя")
    @Severity(SeverityLevel.NORMAL)
    @Description("Отклонение несуществующего пользователя. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Отклонение несуществующего пользователя")
    public void shouldNotLoginWithNonExistingUsername() {

        AuthClient authClient = new AuthClient();

        Response response = authClient.login(
                TestData.NON_EXISTING_USERNAME,
                TestData.INVALID_PASSWORD
        );

        response.then()
                .statusCode(302)
                .header("Location", containsString("/login?error"));

        authClient.getTasks()
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
    }

    @Test(description = "API: Защита задач от неавторизованного доступа")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Защита задач от неавторизованного доступа")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Защита задач от неавторизованного доступа. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Защита задач от неавторизованного доступа")
    public void shouldNotAccessTasksWithoutAuthentication() {

        AuthClient authClient = new AuthClient();

        Response response = authClient.getTasks();

        response.then()
                .statusCode(302)
                .header(
                        "Location",
                        containsString("/login")
                );
    }

    @Test(description = "API: Выход и прекращение доступа к задачам")
    @Epic("Task Manager")
    @Feature("Авторизация")
    @Story("Выход и прекращение доступа к задачам")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Выход и прекращение доступа к задачам. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Выход и прекращение доступа к задачам")
    public void shouldLogoutSuccessfully() {

        String password = System.getenv("TEST_USER_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Не задана переменная TEST_USER_PASSWORD"
            );
        }

        AuthClient authClient = new AuthClient();

        // 1. Авторизуемся
        authClient.login(
                        TestData.EXISTING_USERNAME,
                        password
                ).then()
                .statusCode(302)
                .header("Location", containsString("/tasks"));

        // 2. Проверяем доступ до выхода
        authClient.getTasks()
                .then()
                .statusCode(200);

        // 3. Выходим из аккаунта
        authClient.logout()
                .then()
                .statusCode(302)
                .header("Location", containsString("/login?logout"));

        // 4. Проверяем доступ после выхода
        authClient.getTasks()
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
    }
}
