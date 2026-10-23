
package ru.tarkovskiy.api.tests;

import org.testng.annotations.Test;
import ru.tarkovskiy.api.db.DbConnection;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.testng.Assert.assertEquals;

public class DbConnectionTest {

    @Test
    public void shouldConnectToTestDatabase() throws Exception {

        try (Connection connection = DbConnection.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT current_database()")) {

            resultSet.next();

            String databaseName = resultSet.getString(1);

            assertEquals(databaseName, "task_manager_test");

            System.out.println("Подключение к тестовой БД успешно");
        }
    }
}
