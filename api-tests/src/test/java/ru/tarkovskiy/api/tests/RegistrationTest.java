
package ru.tarkovskiy.api.tests;

import io.restassured.response.Response;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import ru.tarkovskiy.api.base.BaseApiTest;
import ru.tarkovskiy.api.clients.AuthClient;
import ru.tarkovskiy.api.db.UserDbHelper;
import ru.tarkovskiy.api.models.RegistrationRequest;
import ru.tarkovskiy.api.utils.TestDataGenerator;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.hasItem;

import java.sql.SQLException;

import static org.hamcrest.Matchers.equalTo;
import static org.testng.Assert.*;
import static ru.tarkovskiy.api.config.SchemaPaths.REGISTRATION_CONFLICT_ERROR;
import static ru.tarkovskiy.api.config.SchemaPaths.REGISTRATION_VALIDATION_ERROR;

public class RegistrationTest extends BaseApiTest {

    @Test
    public void shouldRegisterUserSuccessfully() throws Exception {

        RegistrationRequest user = TestDataGenerator.newUser();

        cleanup.track(user.email());

        Response response = new AuthClient().register(user);

        response.then()
                .statusCode(201)
                .body(equalTo("Пользователь зарегистрирован"));

        assertTrue(
                UserDbHelper.userExists(user.email()),
                "Пользователь не найден в тестовой БД"
        );
    }

    @Test
    public void shouldNotRegisterUserWithDuplicateUsername() throws SQLException {

        RegistrationRequest firstUser =
                TestDataGenerator.newUser();

        RegistrationRequest secondUser =
                new RegistrationRequest(
                        firstUser.username(),
                        TestDataGenerator.newUser().email(),
                        firstUser.password()
                );

        cleanup.track(firstUser.email());
        cleanup.track(secondUser.email());
        AuthClient authClient = new AuthClient();

        Response firstResponse = authClient.register(firstUser);

        assertEquals(
                firstResponse.statusCode(),
                201,
                "Не удалось зарегистрировать первого пользователя"
        );

        Response secondResponse = authClient.register(secondUser);

        secondResponse.then()
                .statusCode(409)
                .body(matchesJsonSchemaInClasspath(
                        REGISTRATION_CONFLICT_ERROR))
                .body("error", equalTo("Логин уже занят"));

        assertTrue(
                UserDbHelper.userExists(firstUser.email()),
                "Первый пользователь не найден в БД"
        );

        assertFalse(
                UserDbHelper.userExists(secondUser.email()),
                "Второй пользователь не должен быть зарегистрирован"
        );
    }

    @Test
    public void shouldNotRegisterUserWithDuplicateEmail() throws SQLException {

        RegistrationRequest firstUser =
                TestDataGenerator.newUser();

        RegistrationRequest secondUser =
                new RegistrationRequest(
                        TestDataGenerator.newUser().username(),
                        firstUser.email(),
                        firstUser.password()
                );

        cleanup.track(firstUser.email());

        AuthClient authClient = new AuthClient();

        Response firstResponse = authClient.register(firstUser);

        firstResponse.then()
                .statusCode(201)
                .body(equalTo("Пользователь зарегистрирован"));

        Response secondResponse = authClient.register(secondUser);

        secondResponse.then()
                .statusCode(409)
                .body(matchesJsonSchemaInClasspath(
                        REGISTRATION_CONFLICT_ERROR))
                .body("error", equalTo("Email уже зарегистрирован"));

        assertTrue(
                UserDbHelper.userExistsByUsername(firstUser.username()),
                "Первый пользователь не найден в БД"
        );

        assertFalse(
                UserDbHelper.userExistsByUsername(secondUser.username()),
                "Второй пользователь не должен быть зарегистрирован"
        );
    }

    @DataProvider(name = "invalidEmails")
    public Object[][] invalidEmails() {
        return new Object[][]{
                {"invalid-email"},
                {"user@"},
                {"@example.com"}
        };
    }

    @Test(dataProvider = "invalidEmails")
    public void shouldNotRegisterUserWithInvalidEmail(String invalidEmail)
            throws SQLException {

        RegistrationRequest validUser = TestDataGenerator.newUser();

        RegistrationRequest user = new RegistrationRequest(
                validUser.username(),
                invalidEmail,
                validUser.password()
        );

        cleanup.trackUsername(user.username());
        Response response = new AuthClient().register(user);

        response.then()
                .statusCode(400)
                .body(
                        matchesJsonSchemaInClasspath(
                                REGISTRATION_VALIDATION_ERROR
                        )
                )
                .body("errors.email",
                        hasItem("Некорректный формат email"));
    }

    @DataProvider(name = "emptyRequiredFields")
    public Object[][] emptyRequiredFields() {
        return new Object[][]{
                {"", "username", "Логин не может быть пустым"},
                {"", "email", "Email не может быть пустым"},
                {"", "password", "Пароль не может быть пустым"}
        };
    }

    @Test(dataProvider = "emptyRequiredFields")
    public void shouldNotRegisterUserWithEmptyFields(
            String emptyValue,
            String fieldName,
            String expectedError) throws SQLException {

        RegistrationRequest validUser =
                TestDataGenerator.newUser();

        RegistrationRequest user = new RegistrationRequest(
                fieldName.equals("username") ? emptyValue : validUser.username(),
                fieldName.equals("email") ? emptyValue : validUser.email(),
                fieldName.equals("password") ? emptyValue : validUser.password()
        );

        if (fieldName.equals("username")) {
            cleanup.track(validUser.email());
        } else {
            cleanup.trackUsername(validUser.username());
        }

        Response response = new AuthClient().register(user);

        response.then()
                .statusCode(400)
                .body(
                        matchesJsonSchemaInClasspath(
                                REGISTRATION_VALIDATION_ERROR
                        )
                )
                .body(
                        "errors." + fieldName,
                        hasItem(expectedError)
                );
    }

    @DataProvider(name = "usernameBoundaries")
    public Object[][] usernameBoundaries() {
        return new Object[][]{
                {"ab", 400},
                {"abc", 201},
                {"a".repeat(30), 201},
                {"a".repeat(31), 400}
        };
    }

    @Test(dataProvider = "usernameBoundaries")
    public void shouldValidateUsernameBoundaries(
            String username,
            int expectedStatus) throws SQLException {

        RegistrationRequest validUser = TestDataGenerator.newUser();

        cleanup.track(validUser.email());

        RegistrationRequest user = new RegistrationRequest(
                username,
                validUser.email(),
                validUser.password()
        );

        Response response = new AuthClient().register(user);

        response.then()
                .statusCode(expectedStatus);
        if (expectedStatus == 400) {
            response.then()
                    .body(
                            matchesJsonSchemaInClasspath(
                                    REGISTRATION_VALIDATION_ERROR
                            )
                    )
                    .body(
                            "errors.username",
                            hasItem("Логин должен содержать от 3 до 30 символов")
                    );
        } else {
            response.then()
                    .body(equalTo("Пользователь зарегистрирован"));
        }

        boolean userExists = UserDbHelper.userExists(validUser.email());

        assertEquals(
                userExists,
                expectedStatus == 201,
                "Некорректное состояние пользователя в БД"
        );
    }

    @DataProvider(name = "passwordBoundaries")
    public Object[][] passwordBoundaries() {
        return new Object[][]{
                {"a".repeat(7), 400},
                {"a".repeat(8), 201},
                {"a".repeat(72), 201},
                {"a".repeat(73), 400}
        };
    }

    @Test(dataProvider = "passwordBoundaries")
    public void shouldValidatePasswordBoundaries(
            String password,
            int expectedStatus) throws SQLException {

        RegistrationRequest validUser = TestDataGenerator.newUser();

        RegistrationRequest user = new RegistrationRequest(
                validUser.username(),
                validUser.email(),
                password
        );

        cleanup.track(validUser.email());

        Response response = new AuthClient().register(user);

        response.then()
                .statusCode(expectedStatus);

        if (expectedStatus == 400) {
            response.then()
                    .body(
                            matchesJsonSchemaInClasspath(
                                    REGISTRATION_VALIDATION_ERROR
                            )
                    )
                    .body(
                            "errors.password",
                            hasItem("Пароль должен содержать от 8 до 72 символов")
                    );
        } else {
            response.then()
                    .body(equalTo("Пользователь зарегистрирован"));
        }

        boolean userExists = UserDbHelper.userExists(validUser.email());

        assertEquals(
                userExists,
                expectedStatus == 201,
                "Некорректное состояние пользователя в БД"
        );
    }
}
