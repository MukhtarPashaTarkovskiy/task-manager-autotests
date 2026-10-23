
package ru.tarkovskiy.api.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class UserDbHelper {

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

    public static boolean userExistsByUsername(String username)
            throws SQLException {

        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    public static int deleteUserByUsername(String username)
            throws SQLException {

        if (username == null || !username.matches(
                "^qa_user_[0-9a-f]{20}$")) {
            throw new IllegalArgumentException(
                    "Удаление разрешено только для тестовых пользователей"
            );
        }

        String sql = "DELETE FROM users WHERE username = ?";

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username);

            return statement.executeUpdate();
        }
    }
}
