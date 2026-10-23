
package ru.tarkovskiy.api.clients;

import io.restassured.http.ContentType;
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

    public Response register(RegistrationRequest request) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .contentType(ContentType.JSON)
                .header(headerName, token)
                .body(request)
                .when()
                .post("/api/auth/register");
    }

    public Response getCsrfToken() {
        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .when()
                .get("/api/csrf");
    }

    public Response login(String username, String password) {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .contentType(ContentType.URLENC)
                .header(headerName, token)
                .formParam("username", username)
                .formParam("password", password)
                .when()
                .post("/login");
    }

    public Response getTasks() {
        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .when()
                .get("/api/tasks");
    }

    public Response logout() {

        Response csrfResponse = getCsrfToken();

        String token = csrfResponse.jsonPath().getString("token");
        String headerName = csrfResponse.jsonPath().getString("headerName");

        return given()
                .filter(sessionFilter)
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .header(headerName, token)
                .when()
                .post("/logout");
    }
}
