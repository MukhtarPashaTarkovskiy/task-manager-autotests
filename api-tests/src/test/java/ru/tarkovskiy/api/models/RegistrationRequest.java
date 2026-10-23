
package ru.tarkovskiy.api.models;

public record RegistrationRequest(
        String username,
        String email,
        String password
) {
}
