package ru.tarkovskiy.reporting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.io.*;

public final class ReportSafety {
    private static final Set<String> secrets = ConcurrentHashMap.newKeySet();
    static {
        for (String key : List.of("TEST_USER_PASSWORD","TEST_DB_PASSWORD","TEST_DB_USERNAME","TEST_DB_URL","TEST_ALLOWED_DB_HOST")) {
            remember(System.getenv(key));
        }
    }
    private ReportSafety() { }
    public static void remember(String value) {
        if (value != null && !value.isEmpty()) {
            secrets.add(value);
            secrets.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
            secrets.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
    }
    public static String redact(String value) {
        if (value == null) return null;
        String text = value;
        for (String secret : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            text = text.replace(secret, "[REDACTED]");
        }
        return text.replaceAll("(?i)jdbc:postgresql:[^\\s]+", "[JDBC OMITTED]")
            .replaceAll("[\\w.+%-]+@[\\w.-]+", "[EMAIL OMITTED]")
            .replaceAll("qa_user_[a-f0-9]+", "[USER OMITTED]")
            .replaceAll("(?im)(authorization|cookie|set-cookie|x-csrf-token|password|token)\\s*[:=][^\\r\\n]*", "$1: [REDACTED]");
    }
    /** Free-form exception messages may contain arbitrary response bodies and capabilities. */
    public static String safeTrace(String trace) {
        if (trace == null) return null;
        StringBuilder safe = new StringBuilder();
        for (String line : trace.split("\\R")) {
            if (line.matches("\\s*at [\\w.$/]+\\([^\\r\\n]*\\)\\s*") || line.matches("\\s*\\.\\.\\. \\d+ more")) safe.append(line).append('\n');
            else {
                var m = java.util.regex.Pattern.compile("(?:Caused by: |Suppressed: )?([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)+(?:Exception|Error))").matcher(line.trim());
                if (m.lookingAt()) safe.append(m.group()).append(": сообщение исключено; см. безопасную диагностику\n");
            }
        }
        return redact(safe.toString());
    }
    public static String trace(Throwable failure) {
        StringWriter text = new StringWriter(); failure.printStackTrace(new PrintWriter(text)); return safeTrace(text.toString());
    }
    public static String value(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return value.toString();
        if (value instanceof String text) return "строка длиной " + text.length() + " (значение скрыто)";
        return "объект " + value.getClass().getSimpleName() + " (значение скрыто)";
    }
}
