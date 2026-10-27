package ru.tarkovskiy.ui.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class UserDbHelper {

    private UserDbHelper() {
    }

    public static boolean userExists(String email) throws SQLException {

        String sql = "SELECT COUNT(*) FROM users WHERE email = ?";

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, email);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    public static int deleteUser(String email) throws SQLException {

        if (email == null || !email.matches(
                "^qa_[0-9a-f]{32}@example\\.test$")) {

            throw new IllegalArgumentException(
                    "Удаление разрешено только для тестовых пользователей"
            );
        }

        String sql = "DELETE FROM users WHERE email = ?";

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, email);

            return statement.executeUpdate();
        }
    }

    public static int countUsers() throws SQLException {

        String sql = "SELECT COUNT(*) FROM users";

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            resultSet.next();

            return resultSet.getInt(1);
        }
    }
}