package ru.tarkovskiy.ui.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class DbConnection {

    private DbConnection() {
    }

    public static Connection getConnection() throws SQLException {

        String url = System.getenv("TEST_DB_URL");
        String username = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv("TEST_DB_PASSWORD");

        if (url == null || username == null || password == null
                || url.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                    "Не заданы переменные окружения тестовой БД"
            );
        }

        if (!url.startsWith("jdbc:postgresql://")
                || !url.matches(
                "(?s)^jdbc:postgresql://[^/]+/task_manager_test(?:\\?.*)?$"
        )) {
            throw new IllegalStateException(
                    "Подключение разрешено только к базе task_manager_test"
            );
        }

        Connection connection =
                DriverManager.getConnection(url, username, password);

        // Дополнительно проверяем фактическое имя БД на сервере.
        try {
            String databaseName = connection.getCatalog();

            if (!"task_manager_test".equals(databaseName)) {
                throw new IllegalStateException(
                        "Подключение установлено к недопустимой БД: "
                                + databaseName
                );
            }

            return connection;
        } catch (RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }
}