package ru.tarkovskiy.ui.utils;

public class TestData {
    private TestData() {
    }

    public static final String EXISTING_USERNAME = "autotest_user";
    public static final String INVALID_PASSWORD = "WrongPassword123!";
    public static final String NON_EXISTING_USERNAME = "unknown_user_123456789";
    public static final String VALID_EMAIL = "test.user@example.com";
    public static final String EXISTING_EMAIL = "autotest_user@example.test";
    public static final String TASK_TITLE_PREFIX = "AUTOTEST_";

    public static String getTestPassword() {

        String password = System.getenv("TEST_USER_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Не задана переменная TEST_USER_PASSWORD"
            );
        }

        return password;
    }
}
