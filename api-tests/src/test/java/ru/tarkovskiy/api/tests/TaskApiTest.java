package ru.tarkovskiy.api.tests;

import java.sql.SQLException;

import io.restassured.filter.session.SessionFilter;
import io.restassured.response.Response;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import ru.tarkovskiy.api.base.BaseApiTest;
import ru.tarkovskiy.api.clients.AuthClient;
import ru.tarkovskiy.api.clients.TaskClient;
import ru.tarkovskiy.api.db.TaskDbHelper;
import ru.tarkovskiy.api.models.RegistrationRequest;
import ru.tarkovskiy.api.models.TaskRequest;
import ru.tarkovskiy.api.utils.TaskDataGenerator;
import ru.tarkovskiy.api.utils.TestData;
import ru.tarkovskiy.api.utils.TestDataGenerator;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static ru.tarkovskiy.api.config.ApiConfig.BASE_URL;
import static ru.tarkovskiy.api.config.SchemaPaths.TASK_LIST_RESPONSE;
import static ru.tarkovskiy.api.config.SchemaPaths.TASK_RESPONSE;

public class TaskApiTest extends BaseApiTest {

    private AuthClient authClient;
    private TaskClient taskClient;

    @BeforeMethod
    public void setUpTaskClients() {
        SessionFilter session = new SessionFilter();
        authClient = new AuthClient(session);
        taskClient = new TaskClient(session);
    }

    // Авторизация постоянного тестового пользователя.
    private void loginAsTestUser() {
        authClient.login(TestData.EXISTING_USERNAME, testPassword())
                .then()
                .statusCode(302);
    }

    private String testPassword() {
        String password = System.getenv("TEST_USER_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Не задана переменная TEST_USER_PASSWORD");
        }
        return password;
    }

    // Обычные задачи имеют уникальный заголовок AUTOTEST_UUID.
    // Записываем их на очистку ДО HTTP-запроса, чтобы не оставить данные при ошибке.
    private void trackGeneratedTask(TaskRequest task) {
        cleanup.trackTaskByTitle(TestData.EXISTING_USERNAME, task.title());
    }

    // Создание обычной задачи с защитой очистки; для граничных заголовков не применять.
    private long createTestTask(TaskClient client, TaskRequest task) {
        trackGeneratedTask(task);
        Response response = client.createTask(task);
        assertTaskFields(response, task);
        Long id = response.jsonPath().getLong("id");
        assertNotNull(id, "Сервер не вернул ID созданной задачи");
        assertTrue(id > 0, "ID задачи должен быть положительным");
        return id;
    }

    private void assertTaskFields(Response response, TaskRequest task) {
        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath(TASK_RESPONSE))
                .body("title", equalTo(task.title()))
                .body("description", equalTo(task.description()))
                .body("status", equalTo(task.status()))
                .body("priority", equalTo(task.priority()));
    }

    private void assertTaskExistsInDb(long taskId) throws SQLException {
        assertTrue(
                TaskDbHelper.taskExists(taskId, TestData.EXISTING_USERNAME),
                "Задача не найдена в тестовой БД: " + taskId
        );
    }

    @Test
    public void shouldCreateTaskSuccessfully() throws SQLException {
        loginAsTestUser();
        TaskRequest task = TaskDataGenerator.newTask();
        long taskId = createTestTask(taskClient, task);

        // Проверяем, что возвращённая по ID задача соответствует запросу.
        assertTaskFields(taskClient.getTaskById(taskId), task);
        assertTaskExistsInDb(taskId);
    }

    @Test
    public void shouldGetTaskByIdSuccessfully() {
        loginAsTestUser();
        TaskRequest task = TaskDataGenerator.newTask();
        long taskId = createTestTask(taskClient, task);

        Response response = taskClient.getTaskById(taskId);
        assertTaskFields(response, task);
        response.then().body("id", equalTo((int) taskId));
    }

    @Test
    public void shouldGetAllTasksSuccessfully() {
        loginAsTestUser();
        TaskRequest task = TaskDataGenerator.newTask();
        long taskId = createTestTask(taskClient, task);

        taskClient.getAllTasks().then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath(TASK_LIST_RESPONSE))
                .body("find { it.id == " + taskId + " }.title", equalTo(task.title()));
    }

    @Test
    public void shouldNotGetNonExistingTask() {
        loginAsTestUser();
        taskClient.getTaskById(-1L).then().statusCode(404);
    }

    @DataProvider(name = "invalidTaskTitles")
    public Object[][] invalidTaskTitles() {
        return new Object[][]{
                {"", "Пустое название"},
                {"ab", "Название короче 3 символов"},
                {"a".repeat(101), "Название длиннее 100 символов"}
        };
    }

    @Test(dataProvider = "invalidTaskTitles")
    public void shouldNotCreateTaskWithInvalidTitle(String invalidTitle, String description) {
        loginAsTestUser();
        TaskRequest task = new TaskRequest(
                invalidTitle, "Проверка валидации названия", "TODO", "MEDIUM", null
        );
        taskClient.createTask(task).then().statusCode(400);
    }

    @DataProvider(name = "validTaskTitleBoundaries")
    public Object[][] validTaskTitleBoundaries() {
        return new Object[][]{{"a".repeat(3)}, {"a".repeat(100)}};
    }

    @Test(dataProvider = "validTaskTitleBoundaries")
    public void shouldCreateTaskWithValidTitleBoundaries(String title) throws SQLException {
        loginAsTestUser();
        assertFalse(
                TaskDbHelper.taskExistsByTitle(title, TestData.EXISTING_USERNAME),
                "Задача с таким названием уже существует"
        );

        TaskRequest task = new TaskRequest(
                title, "Проверка граничных значений названия", "TODO", "MEDIUM", null
        );
        Response response = taskClient.createTask(task);

        // Для граничных названий используется отдельный защищённый способ очистки.
        Long taskId = response.jsonPath().getLong("id");
        assertNotNull(taskId, "Сервер не вернул ID задачи");
        cleanup.trackBoundaryTask(taskId, TestData.EXISTING_USERNAME, title);

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath(TASK_RESPONSE))
                .body("title", equalTo(title));
        assertTaskExistsInDb(taskId);
    }

    @DataProvider(name = "validTaskStatuses")
    public Object[][] validTaskStatuses() {
        return new Object[][]{{"TODO"}, {"IN_PROGRESS"}, {"DONE"}};
    }

    @Test(dataProvider = "validTaskStatuses")
    public void shouldCreateTaskWithValidStatus(String status) throws SQLException {
        loginAsTestUser();
        TaskRequest generated = TaskDataGenerator.newTask();
        TaskRequest task = new TaskRequest(
                generated.title(), generated.description(), status,
                generated.priority(), generated.deadline()
        );
        long taskId = createTestTask(taskClient, task);

        assertTaskFields(taskClient.getTaskById(taskId), task);
        assertTaskExistsInDb(taskId);
    }

    @DataProvider(name = "validTaskPriorities")
    public Object[][] validTaskPriorities() {
        return new Object[][]{{"LOW"}, {"MEDIUM"}, {"HIGH"}};
    }

    @Test(dataProvider = "validTaskPriorities")
    public void shouldCreateTaskWithValidPriority(String priority) throws SQLException {
        loginAsTestUser();
        TaskRequest generated = TaskDataGenerator.newTask();
        TaskRequest task = new TaskRequest(
                generated.title(), generated.description(), generated.status(),
                priority, generated.deadline()
        );
        long taskId = createTestTask(taskClient, task);

        assertTaskFields(taskClient.getTaskById(taskId), task);
        assertTaskExistsInDb(taskId);
    }

    @DataProvider(name = "invalidTaskEnums")
    public Object[][] invalidTaskEnums() {
        return new Object[][]{
                {"INVALID", "MEDIUM", "Некорректный статус"},
                {"TODO", "INVALID", "Некорректный приоритет"},
                {"", "MEDIUM", "Пустой статус"},
                {"TODO", "", "Пустой приоритет"}
        };
    }

    @Test(dataProvider = "invalidTaskEnums")
    public void shouldNotCreateTaskWithInvalidEnums(
            String status, String priority, String description) {
        loginAsTestUser();
        TaskRequest generated = TaskDataGenerator.newTask();
        TaskRequest task = new TaskRequest(
                generated.title(), generated.description(), status,
                priority, generated.deadline()
        );

        // Если сервер ошибочно примет запрос, cleanup сможет найти созданную задачу.
        trackGeneratedTask(task);
        taskClient.createTask(task).then().statusCode(400);
    }

    @Test
    public void shouldUpdateTaskSuccessfully() throws SQLException {
        loginAsTestUser();
        TaskRequest original = TaskDataGenerator.newTask();
        long taskId = createTestTask(taskClient, original);

        // Сохраняем уникальный заголовок, чтобы очистка по title продолжала работать.
        TaskRequest updated = new TaskRequest(
                original.title(), "Описание изменено через PUT", "IN_PROGRESS",
                "HIGH", original.deadline()
        );

        Response updateResponse = taskClient.updateTask(taskId, updated);
        assertTaskFields(updateResponse, updated);
        updateResponse.then().body("id", equalTo((int) taskId));
        assertTaskFields(taskClient.getTaskById(taskId), updated);
        assertTaskExistsInDb(taskId);
    }

    @DataProvider(name = "invalidTaskUpdateData")
    public Object[][] invalidTaskUpdateData() {
        return new Object[][]{
                {"", "TODO", "MEDIUM", "Пустое название"},
                {"ab", "TODO", "MEDIUM", "Название короче 3 символов"},
                {"a".repeat(101), "TODO", "MEDIUM", "Название длиннее 100 символов"},
                {"Valid title", "INVALID", "MEDIUM", "Некорректный статус"},
                {"Valid title", "TODO", "INVALID", "Некорректный приоритет"}
        };
    }

    @Test(dataProvider = "invalidTaskUpdateData")
    public void shouldNotUpdateTaskWithInvalidData(
            String title, String status, String priority, String description) {
        loginAsTestUser();
        TaskRequest original = TaskDataGenerator.newTask();
        long taskId = createTestTask(taskClient, original);

        TaskRequest invalid = new TaskRequest(
                title, original.description(), status, priority, original.deadline()
        );
        taskClient.updateTask(taskId, invalid).then().statusCode(400);
        assertTaskFields(taskClient.getTaskById(taskId), original);
    }

    @Test
    public void shouldDeleteTaskSuccessfully() throws SQLException {
        loginAsTestUser();
        TaskRequest task = TaskDataGenerator.newTask();
        long taskId = createTestTask(taskClient, task);
        assertTaskExistsInDb(taskId);

        taskClient.deleteTask(taskId).then().statusCode(200);
        taskClient.getTaskById(taskId).then().statusCode(404);
        assertFalse(
                TaskDbHelper.taskExists(taskId, TestData.EXISTING_USERNAME),
                "Задача осталась в тестовой БД после DELETE"
        );
        taskClient.deleteTask(taskId).then().statusCode(404);
    }

    @Test
    public void shouldNotGetTasksWithoutAuthentication() {
        // Новый SessionFilter в @BeforeMethod ещё не авторизован.
        taskClient.getAllTasks().then()
                .statusCode(302)
                .header("Location", equalTo(BASE_URL + "/login"));
    }

    @Test
    public void shouldNotGetAnotherUsersTask() {
        // Владелец использует основную сессию.
        loginAsTestUser();

        // Второй пользователь использует независимую сессию.
        SessionFilter secondSession = new SessionFilter();
        AuthClient secondAuthClient = new AuthClient(secondSession);
        TaskClient secondTaskClient = new TaskClient(secondSession);
        RegistrationRequest secondUser = TestDataGenerator.newUser();

        cleanup.track(secondUser.email()); // ДО регистрации
        secondAuthClient.register(secondUser).then().statusCode(201);
        secondAuthClient.login(secondUser.username(), testPassword())
                .then().statusCode(302);

        TaskRequest task = TaskDataGenerator.newTask();
        long taskId = createTestTask(taskClient, task);

        // Чужую задачу нельзя прочитать, изменить или удалить.
        secondTaskClient.getTaskById(taskId).then().statusCode(404);
        secondTaskClient.updateTask(taskId, TaskDataGenerator.newTask())
                .then().statusCode(404);
        secondTaskClient.deleteTask(taskId).then().statusCode(404);

        // Данные владельца сохранились без изменений.
        assertTaskFields(taskClient.getTaskById(taskId), task);
    }
}
