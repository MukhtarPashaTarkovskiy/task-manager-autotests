package ru.tarkovskiy.ui.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DbConnection {
    private DbConnection() { }

    public static Connection getConnection() throws SQLException {
        String url = System.getenv("TEST_DB_URL");
        String username = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv("TEST_DB_PASSWORD");
        String host = System.getenv("TEST_ALLOWED_DB_HOST");
        String port = System.getenv("TEST_ALLOWED_DB_PORT");
        if (url == null || username == null || password == null || host == null || port == null
                || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Required TEST database settings are missing");
        }
        validateTarget(url, host, port);
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        // Preserve the selected TLS mode. Only verify-full uses Java certificate validation.
        // require uses pgJDBC's normal encrypted connection without forced CA validation.
        if (url.endsWith("sslmode=verify-full")) {
            properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        }
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(url, properties);
            if (!"task_manager_test".equals(connection.getCatalog())) {
                throw new SQLException("Unexpected database");
            }
            return connection;
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) { /* Do not expose diagnostics. */ }
            }
            // JDBC exceptions may include usernames, hostnames or URLs. Keep SQLState only.
            String state = failure instanceof SQLException ? ((SQLException) failure).getSQLState() : null;
            throw new SQLException("TEST database connection failed; check endpoint, credentials and TLS", state);
        } finally {
            properties.clear();
        }
    }

    public static void validateTarget(String url, String host, String port) {
        String dns = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+";
        if (url == null || host == null || port == null || host.length() > 253 || !host.matches(dns)
                || !port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) > 65535) {
            throw new IllegalStateException("Invalid approved TEST database endpoint");
        }
        Matcher match = Pattern.compile("jdbc:postgresql://([a-z0-9.-]+)(?::([1-9][0-9]{0,4}))?/task_manager_test\\?sslmode=(require|verify-full)").matcher(url);
        if (!match.matches()) {
            throw new IllegalStateException("JDBC target refused: TEST database and sslmode=require or verify-full are required");
        }
        int actualPort = match.group(2) == null ? 5432 : Integer.parseInt(match.group(2));
        if (!host.equals(match.group(1)) || actualPort != Integer.parseInt(port)) {
            throw new IllegalStateException("JDBC target refused: approved TEST host and port do not match");
        }
    }
}