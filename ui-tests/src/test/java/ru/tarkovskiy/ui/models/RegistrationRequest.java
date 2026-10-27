package ru.tarkovskiy.ui.models;

public record RegistrationRequest(
        String username,
        String email,
        String password
) {
}
