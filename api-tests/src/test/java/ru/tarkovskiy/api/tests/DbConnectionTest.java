
package ru.tarkovskiy.api.tests;

import io.qameta.allure.Allure;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.qameta.allure.Step;

import org.testng.annotations.Test;
import ru.tarkovskiy.api.db.DbConnection;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.testng.Assert.assertEquals;

public class DbConnectionTest {

    @Test(description = "API: Подключение к тестовой базе данных")
    @Epic("Task Manager")
    @Feature("Инфраструктура")
    @Story("Подключение к тестовой базе данных")
    @Severity(SeverityLevel.NORMAL)
    @Description("Подключение к тестовой базе данных. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Подключение к тестовой базе данных")
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
