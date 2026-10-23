package ru.tarkovskiy.api.tests;

import io.restassured.response.Response;
import org.testng.annotations.Test;
import ru.tarkovskiy.api.base.BaseApiTest;
import ru.tarkovskiy.api.clients.AuthClient;
import ru.tarkovskiy.api.utils.TestData;

import static org.hamcrest.Matchers.containsString;

public class LoginTest extends BaseApiTest {

    @Test
    public void shouldLoginWithValidCredentials() {

        String username = TestData.EXISTING_USERNAME;
        String password = System.getenv("TEST_USER_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Не задана переменная TEST_USER_PASSWORD"
            );
        }

        AuthClient authClient = new AuthClient();

        Response response = authClient.login(username, password);

        response.then()
                .statusCode(302)
                .header("Location", containsString("/tasks"));

        authClient.getTasks()
                .then()
                .statusCode(200);
    }

    @Test
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

    @Test
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

    @Test
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

    @Test
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