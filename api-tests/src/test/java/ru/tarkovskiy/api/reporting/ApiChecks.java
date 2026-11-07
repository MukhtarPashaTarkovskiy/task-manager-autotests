package ru.tarkovskiy.api.reporting;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import org.hamcrest.Matcher;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;

/** Adds evidence while REST Assured continues to perform the original assertions. */
public final class ApiChecks {
    private static final ThreadLocal<Integer> lastStatus = new ThreadLocal<>();
    private ApiChecks() { }
    public static void actual(int status) { lastStatus.set(status); }
    public static int expected(int expected) {
        Integer actual = lastStatus.get();
        Allure.step("HTTP-статус: ожидалось " + expected + ", получено " + (actual == null ? "неизвестно" : actual),
            actual == null ? Status.BROKEN : actual == expected ? Status.PASSED : Status.FAILED);
        return expected;
    }
    public static Matcher<?> matchesJsonSchemaInClasspath(String schema) {
        Matcher<?> original = io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath(schema);
        return new BaseMatcher<Object>() {
            @Override public boolean matches(Object response) {
                String uuid = java.util.UUID.randomUUID().toString();
                Allure.getLifecycle().startStep(uuid, new io.qameta.allure.model.StepResult().setName("Проверить JSON Schema: " + schema));
                try {
                    boolean matches = original.matches(response);
                    Allure.addAttachment("Результат JSON Schema", "text/plain", "Схема: " + schema + "\nСоответствие: " + matches + "\nТело ответа не сохраняется.", ".txt");
                    Allure.getLifecycle().updateStep(uuid, step -> step.setStatus(matches ? Status.PASSED : Status.FAILED));
                    return matches;
                } catch (RuntimeException failure) {
                    Allure.getLifecycle().updateStep(uuid, step -> step.setStatus(Status.BROKEN));
                    throw failure;
                } finally { Allure.getLifecycle().stopStep(uuid); }
            }
            @Override public void describeTo(Description description) { original.describeTo(description); }
            @Override public void describeMismatch(Object value, Description description) { original.describeMismatch(value, description); }
        };
    }
}
