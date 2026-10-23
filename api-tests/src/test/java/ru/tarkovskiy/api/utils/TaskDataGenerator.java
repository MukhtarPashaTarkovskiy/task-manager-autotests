package ru.tarkovskiy.api.utils;

import ru.tarkovskiy.api.models.TaskRequest;
import java.time.LocalDate;
import java.util.UUID;

public final class TaskDataGenerator {

    private TaskDataGenerator() {
    }

    public static TaskRequest newTask() {

        String title = TestData.TASK_TITLE_PREFIX
                + UUID.randomUUID();

        return new TaskRequest(
                title,
                "Задача создана автоматизированным тестом",
                "TODO",
                "MEDIUM",
                LocalDate.of(2026, 12, 31)
        );
    }
}