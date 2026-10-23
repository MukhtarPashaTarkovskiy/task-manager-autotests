package ru.tarkovskiy.api.utils;

import ru.tarkovskiy.api.models.RegistrationRequest;
import java.util.UUID;

public class TestDataGenerator {

    public static RegistrationRequest newUser() {

        String password = System.getenv("TEST_USER_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Не задана переменная окружения TEST_USER_PASSWORD"
            );
        }

        String uuid = UUID.randomUUID()
                .toString()
                .replace("-", "");

        return new RegistrationRequest(
                "qa_user_" + uuid.substring(0, 20),
                "qa_" + uuid + "@example.test",
                password
        );
    }
}