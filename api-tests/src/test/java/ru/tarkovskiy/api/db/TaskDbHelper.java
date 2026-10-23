package ru.tarkovskiy.api.db;

import ru.tarkovskiy.api.utils.TestData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

public class TaskDbHelper {

    private static final Pattern TEST_TASK_TITLE = Pattern.compile(
            "^AUTOTEST_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );

    public static boolean isTestTaskTitle(String title) {
        return title != null && TEST_TASK_TITLE.matcher(title).matches();
    }

    public static boolean taskExists(long taskId, String username) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM task t
                JOIN users u ON t.user_id = u.id
                WHERE t.id = ? AND u.username = ?
                """;

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setString(2, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    public static int deleteTestTask(long taskId, String username, String expectedTitle)
            throws SQLException {
        validateOrdinaryTask(taskId, username, expectedTitle);

        String sql = """
                DELETE FROM task
                WHERE id = ?
                  AND title = ?
                  AND title LIKE ?
                  AND user_id = (SELECT id FROM users WHERE username = ?)
                """;

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setString(2, expectedTitle);
            statement.setString(3, TestData.TASK_TITLE_PREFIX + "%");
            statement.setString(4, username);
            return statement.executeUpdate();
        }
    }

    public static Long findTaskIdByTitle(String title, String username) throws SQLException {
        if (!isTestTaskTitle(title) || !isPermanentTestUser(username)) {
            throw new IllegalArgumentException("Недопустимые данные тестовой задачи");
        }

        String sql = """
                SELECT t.id
                FROM task t
                JOIN users u ON t.user_id = u.id
                WHERE t.title = ? AND u.username = ?
                """;

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("id");
                }
                return null;
            }
        }
    }

    public static int deleteBoundaryTestTask(long taskId, String username, String expectedTitle)
            throws SQLException {
        if (taskId <= 0 || !isPermanentTestUser(username) || !isBoundaryTitle(expectedTitle)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные для удаления граничной тестовой задачи"
            );
        }

        String sql = """
                DELETE FROM task
                WHERE id = ?
                  AND title = ?
                  AND user_id = (SELECT id FROM users WHERE username = ?)
                """;

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setString(2, expectedTitle);
            statement.setString(3, username);
            return statement.executeUpdate();
        }
    }

    public static boolean taskExistsByTitle(String title, String username) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM task t
                JOIN users u ON t.user_id = u.id
                WHERE t.title = ? AND u.username = ?
                """;

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    public static boolean isBoundaryTitle(String title) {
        return "aaa".equals(title) || "a".repeat(100).equals(title);
    }

    private static void validateOrdinaryTask(long taskId, String username, String expectedTitle) {
        if (taskId <= 0 || !isPermanentTestUser(username) || !isTestTaskTitle(expectedTitle)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные для удаления тестовой задачи"
            );
        }
    }

    private static boolean isPermanentTestUser(String username) {
        return TestData.EXISTING_USERNAME.equals(username);
    }
}
