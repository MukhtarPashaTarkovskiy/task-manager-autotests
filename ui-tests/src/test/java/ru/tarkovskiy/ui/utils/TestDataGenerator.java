package ru.tarkovskiy.ui.utils;

import ru.tarkovskiy.ui.models.RegistrationRequest;
import java.util.UUID;

public final class TestDataGenerator {

    private TestDataGenerator() {
    }

    public static RegistrationRequest newUser() {

        String password = TestData.getTestPassword();

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