package ru.tarkovskiy.api.base;

import ru.tarkovskiy.api.config.ApiConfig;
import ru.tarkovskiy.api.db.DbConnection;
import ru.tarkovskiy.reporting.UbuntuSuiteRunner;
import java.util.Set;

/** Ubuntu standalone entry point. Existing XML, fixtures, listeners and cleanup remain in use. */
public final class UbuntuApiLauncher {
    private UbuntuApiLauncher() { }
    public static void main(String[] args) {
        int code = 1;
        try {
            if (args.length != 1 || !Set.of("ApiSmoke", "ApiRegression").contains(args[0])
                    || !"Linux".equals(System.getProperty("os.name")) || Runtime.version().feature() != 17
                    || !Boolean.getBoolean("tm.reporting.initialized")) throw new IllegalStateException("Invalid API environment");
            if (!"https://task-manager-test-588r.onrender.com".equals(ApiConfig.BASE_URL)) throw new IllegalStateException("Invalid TEST target");
            DbConnection.validateTarget(required("TEST_DB_URL"), required("TEST_ALLOWED_DB_HOST"), required("TEST_ALLOWED_DB_PORT"));
            required("TEST_USER_PASSWORD"); required("TEST_DB_USERNAME"); required("TEST_DB_PASSWORD");
            code = UbuntuSuiteRunner.run("api-tests", args[0].equals("ApiSmoke") ? "Smoke" : "Regression", null);
        } catch (Throwable failure) {
            System.out.println("API_UBUNTU failed; inspect audited suite results; private details suppressed");
        }
        System.exit(code);
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing TEST setting");
        return value;
    }
}
