package ru.tarkovskiy.ui.utils;

import java.util.UUID;

public final class TaskDataGenerator {

    private TaskDataGenerator() {
    }

    public static String newTaskTitle() {
        return TestData.TASK_TITLE_PREFIX + UUID.randomUUID();
    }

    public static String newBoundaryTaskDescription() {
        return TestData.TASK_TITLE_PREFIX + UUID.randomUUID();
    }
}