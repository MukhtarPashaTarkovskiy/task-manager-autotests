
package ru.tarkovskiy.api.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DbConnection {

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
                || !url.matches("(?s)^jdbc:postgresql://[^/]+/task_manager_test(?:\\?.*)?$")) {
            throw new IllegalStateException(
                    "Подключение разрешено только к базе task_manager_test"
            );
        }

        return DriverManager.getConnection(url, username, password);
    }
}
