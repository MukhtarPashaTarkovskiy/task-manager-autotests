package ru.tarkovskiy.api.setup;

import io.restassured.response.Response;
import ru.tarkovskiy.api.clients.AuthClient;
import ru.tarkovskiy.api.models.RegistrationRequest;

public class PermanentTestUserSetup {

    public static void main(String[] args) {

        String password = System.getenv("TEST_USER_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Не задана переменная TEST_USER_PASSWORD"
            );
        }

        RegistrationRequest user = new RegistrationRequest(
                "autotest_user",
                "autotest_user@example.test",
                password
        );

        Response response = new AuthClient().register(user);

        response.then()
                .statusCode(201);

        System.out.println("Постоянный тестовый пользователь создан");
    }
}