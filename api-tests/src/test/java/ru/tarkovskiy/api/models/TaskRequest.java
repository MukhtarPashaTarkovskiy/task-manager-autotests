package ru.tarkovskiy.api.models;

import java.time.LocalDate;

public record TaskRequest(
        String title,
        String description,
        String status,
        String priority,
        LocalDate deadline
) {
}