package ru.tarkovskiy.api.clients;

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

    public Response getCsrfToken() {
        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .when()
                .get("/api/csrf");
    }

    public Response createTask(TaskRequest task) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .contentType(ContentType.JSON)
                .header(headerName, token)
                .body(task)
                .when()
                .post("/api/tasks");
    }

    public Response getTaskById(long taskId) {
        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .when()
                .get("/api/tasks/{id}", taskId);
    }

    public Response getAllTasks() {
        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .when()
                .get("/api/tasks");
    }

    public Response updateTask(long taskId, TaskRequest task) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .contentType(ContentType.JSON)
                .header(headerName, token)
                .body(task)
                .when()
                .put("/api/tasks/{id}", taskId);
    }

    public Response deleteTask(long taskId) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .header(headerName, token)
                .when()
                .delete("/api/tasks/{id}", taskId);
    }
}
