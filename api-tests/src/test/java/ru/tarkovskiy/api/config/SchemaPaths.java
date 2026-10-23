package ru.tarkovskiy.api.config;

public class SchemaPaths {

    private SchemaPaths() {
    }

    public static final String TASK_RESPONSE =
            "schemas/task-response.schema.json";

    public static final String REGISTRATION_CONFLICT_ERROR =
            "schemas/registration-conflict-error.schema.json";

    public static final String REGISTRATION_VALIDATION_ERROR =
            "schemas/registration-validation-error.schema.json";

    public static final String TASK_LIST_RESPONSE =
            "schemas/task-list-response.schema.json";
}
