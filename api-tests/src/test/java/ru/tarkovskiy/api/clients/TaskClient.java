package ru.tarkovskiy.api.clients;

import io.qameta.allure.Step;
import ru.tarkovskiy.api.reporting.SafeAllureHttpFilter;
import io.restassured.filter.session.SessionFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import ru.tarkovskiy.api.models.TaskRequest;
import static ru.tarkovskiy.api.config.ApiConfig.BASE_URL;

import static io.restassured.RestAssured.given;

public class TaskClient {

    private final SessionFilter sessionFilter;

    public TaskClient(SessionFilter sessionFilter) {
        this.sessionFilter = sessionFilter;
    }

    @Step("Получить CSRF без записи токена")
    public Response getCsrfToken() {
        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .when()
                .get("/api/csrf");
    }

    @Step("Создать задачу через API")
    public Response createTask(TaskRequest task) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .contentType(ContentType.JSON)
                .header(headerName, token)
                .body(task)
                .when()
                .post("/api/tasks");
    }

    @Step("Получить задачу по ID")
    public Response getTaskById(long taskId) {
        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .when()
                .get("/api/tasks/{id}", taskId);
    }

    @Step("Получить список задач через API")
    public Response getAllTasks() {
        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .when()
                .get("/api/tasks");
    }

    @Step("Изменить задачу через API")
    public Response updateTask(long taskId, TaskRequest task) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .contentType(ContentType.JSON)
                .header(headerName, token)
                .body(task)
                .when()
                .put("/api/tasks/{id}", taskId);
    }

    @Step("Подтвердить удаление задачи")
    public Response deleteTask(long taskId) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .header(headerName, token)
                .when()
                .delete("/api/tasks/{id}", taskId);
    }
}
