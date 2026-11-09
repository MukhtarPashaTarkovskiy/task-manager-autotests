package ru.tarkovskiy.ui.models;

import java.time.LocalDate;

public record TaskDbRecord(
        long id,
        String title,
        String description,
        String status,
        String priority,
        LocalDate deadline
) {
}