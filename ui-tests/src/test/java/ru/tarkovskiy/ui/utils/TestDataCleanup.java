package ru.tarkovskiy.ui.utils;

import ru.tarkovskiy.ui.db.UserDbHelper;

import java.util.ArrayList;
import java.util.List;

public final class TestDataCleanup {

    private final List<String> emails = new ArrayList<>();

    public void track(String email) {

        if (email == null || !email.matches(
                "^qa_[0-9a-f]{32}@example\\.test$")) {

            throw new IllegalArgumentException(
                    "Недопустимый email тестового пользователя"
            );
        }

        emails.add(email);
    }

    public void cleanup() throws Exception {

        try {
            for (String email : emails) {

                int deleted = UserDbHelper.deleteUser(email);

                if (deleted > 1) {
                    throw new IllegalStateException(
                            "Удалено более одного пользователя: " + email
                    );
                }

                if (UserDbHelper.userExists(email)) {
                    throw new IllegalStateException(
                            "Пользователь остался в БД: " + email
                    );
                }

                System.out.println(
                        "CLEANUP PASS: пользователь отсутствует в БД: " + email
                );
            }
        } finally {
            emails.clear();
        }
    }
}