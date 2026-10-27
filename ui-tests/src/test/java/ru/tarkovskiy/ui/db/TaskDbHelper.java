package ru.tarkovskiy.ui.db;

import ru.tarkovskiy.ui.models.TaskDbRecord;
import ru.tarkovskiy.ui.utils.TestData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

public final class TaskDbHelper {

    private static final Pattern TEST_TASK_TITLE = Pattern.compile(
            "^AUTOTEST_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );

    private TaskDbHelper() {
    }

    public static Long findTaskIdByTitle(String title, String username)
            throws SQLException {

        if (title == null
                || !TEST_TASK_TITLE.matcher(title).matches()
                || !TestData.EXISTING_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные тестовой задачи"
            );
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

    public static int deleteTestTask(long taskId, String username, String expectedTitle)
            throws SQLException {

        if (taskId <= 0
                || expectedTitle == null
                || !TEST_TASK_TITLE.matcher(expectedTitle).matches()
                || !TestData.EXISTING_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные для удаления тестовой задачи"
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

    public static boolean taskExistsByTitle(String title, String username)
            throws SQLException {

        if (title == null
                || !TEST_TASK_TITLE.matcher(title).matches()
                || !TestData.EXISTING_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные тестовой задачи"
            );
        }

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

    public static TaskDbRecord getTaskByTitle(String title, String username)
            throws SQLException {

        if (title == null
                || !TEST_TASK_TITLE.matcher(title).matches()
                || !TestData.EXISTING_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные тестовой задачи"
            );
        }

        String sql = """
            SELECT t.id, t.title, t.description,
                   t.status, t.priority, t.deadline
            FROM task t
            JOIN users u ON t.user_id = u.id
            WHERE t.title = ? AND u.username = ?
            """;

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, title);
            statement.setString(2, username);

            try (ResultSet resultSet = statement.executeQuery()) {

                if (!resultSet.next()) {
                    return null;
                }

                java.sql.Date deadline = resultSet.getDate("deadline");

                return new TaskDbRecord(
                        resultSet.getLong("id"),
                        resultSet.getString("title"),
                        resultSet.getString("description"),
                        resultSet.getString("status"),
                        resultSet.getString("priority"),
                        deadline == null ? null : deadline.toLocalDate()
                );
            }
        }
    }

    public static Long findBoundaryTaskId(
            String title,
            String description,
            String username
    ) throws SQLException {

        if (title == null
                || title.length() < 3
                || title.length() > 100
                || description == null
                || !TEST_TASK_TITLE.matcher(description).matches()
                || !TestData.EXISTING_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные граничной тестовой задачи"
            );
        }

        String sql = """
            SELECT t.id
            FROM task t
            JOIN users u ON t.user_id = u.id
            WHERE t.title = ?
              AND t.description = ?
              AND u.username = ?
            """;

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, title);
            statement.setString(2, description);
            statement.setString(3, username);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("id");
                }
                return null;
            }
        }
    }

    public static int deleteBoundaryTask(
            long taskId,
            String title,
            String description,
            String username
    ) throws SQLException {

        if (taskId <= 0
                || title == null
                || title.length() < 3
                || title.length() > 100
                || description == null
                || !TEST_TASK_TITLE.matcher(description).matches()
                || !TestData.EXISTING_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные для удаления граничной задачи"
            );
        }

        String sql = """
            DELETE FROM task
            WHERE id = ?
              AND title = ?
              AND description = ?
              AND user_id = (
                  SELECT id FROM users WHERE username = ?
              )
            """;

        try (Connection connection = DbConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, taskId);
            statement.setString(2, title);
            statement.setString(3, description);
            statement.setString(4, username);

            return statement.executeUpdate();
        }
    }
}