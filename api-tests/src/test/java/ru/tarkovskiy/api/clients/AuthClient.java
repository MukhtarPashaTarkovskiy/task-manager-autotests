
package ru.tarkovskiy.api.clients;

import io.restassured.http.ContentType;
import io.qameta.allure.Step;
import ru.tarkovskiy.api.reporting.SafeAllureHttpFilter;
import io.restassured.response.Response;
import ru.tarkovskiy.api.models.RegistrationRequest;
import io.restassured.filter.session.SessionFilter;
import static io.restassured.RestAssured.given;
import static ru.tarkovskiy.api.config.ApiConfig.BASE_URL;


public class AuthClient {

    private final SessionFilter sessionFilter;

    public AuthClient() {
        this(new SessionFilter());
    }

    public AuthClient(SessionFilter sessionFilter) {
        this.sessionFilter = sessionFilter;
    }

    @Step("Зарегистрировать пользователя")
    public Response register(RegistrationRequest request) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .contentType(ContentType.JSON)
                .header(headerName, token)
                .body(request)
                .when()
                .post("/api/auth/register");
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

    @Step("Войти пользователем")
    public Response login(String username, String password) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .contentType(ContentType.URLENC)
                .header(headerName, token)
                .formParam("username", username)
                .formParam("password", password)
                .when()
                .post("/login");
    }

    @Step("Получить список задач")
    public Response getTasks() {
        return given()
                .filter(sessionFilter)
                .filter(new SafeAllureHttpFilter())
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .when()
                .get("/api/tasks");
    }

    @Step("Завершить сессию")
    public Response logout() {

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
                .post("/logout");
    }
}
