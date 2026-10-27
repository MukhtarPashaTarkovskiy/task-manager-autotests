package ru.tarkovskiy.ui.config;

import java.net.URI;

public final class UiConfig {
    private UiConfig() { }

    public static final String BASE_URL = checkedBaseUrl();
    public static final String LOGIN_URL = BASE_URL + "/login";

    private static String checkedBaseUrl() {
        String configured = System.getenv("TEST_BASE_URL");
        if (configured == null) {
            configured = "https://task-manager-test-588r.onrender.com";
        }
        // Exact origin: no userinfo, alternate port, path, query, fragment or suffix.
        if (!"https://task-manager-test-588r.onrender.com".equals(configured)) {
            throw new IllegalStateException("HTTP target refused: only the approved TEST Render origin is allowed");
        }
        URI uri = URI.create(configured);
        if (!"https".equals(uri.getScheme())
                || !"task-manager-test-588r.onrender.com".equals(uri.getHost())) {
            throw new IllegalStateException("HTTP target refused");
        }
        return configured;
    }
}