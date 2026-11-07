package ru.tarkovskiy.reporting;
import io.qameta.allure.Allure;
import org.testng.Assert;

/** Delegates to the original TestNG checks; only adds safe expected/actual evidence. */
public final class Checks {
    private Checks() { }
    private static void check(String message, Object actual, Object expected, Runnable assertion) {
        Allure.step(message, () -> {
            Allure.addAttachment("Ожидаемый и фактический результат", "text/plain",
                "Ожидалось: " + ReportSafety.value(expected) + "\nПолучено: " + ReportSafety.value(actual) +
                "\nСовпадение: " + java.util.Objects.deepEquals(actual, expected), ".txt");
            assertion.run();
        });
    }
    public static void assertEquals(Object actual, Object expected) { assertEquals(actual, expected, "Проверить совпадение ожидаемого и фактического результата"); }
    public static void assertEquals(Object actual, Object expected, String message) { check(ReportSafety.redact(message), actual, expected, () -> Assert.assertEquals(actual, expected, message)); }
    public static void assertTrue(boolean actual) { assertTrue(actual, "Проверить выполнение условия"); }
    public static void assertTrue(boolean actual, String message) { check(ReportSafety.redact(message), actual, true, () -> Assert.assertTrue(actual, message)); }
    public static void assertFalse(boolean actual) { assertFalse(actual, "Проверить отсутствие запрещённого результата"); }
    public static void assertFalse(boolean actual, String message) { check(ReportSafety.redact(message), actual, false, () -> Assert.assertFalse(actual, message)); }
    public static void assertNull(Object actual) { assertNull(actual, "Проверить отсутствие значения"); }
    public static void assertNull(Object actual, String message) { check(ReportSafety.redact(message), actual, null, () -> Assert.assertNull(actual, message)); }
    public static void assertNotNull(Object actual) { assertNotNull(actual, "Проверить наличие результата"); }
    public static void assertNotNull(Object actual, String message) { check(ReportSafety.redact(message), actual != null, true, () -> Assert.assertNotNull(actual, message)); }
}
