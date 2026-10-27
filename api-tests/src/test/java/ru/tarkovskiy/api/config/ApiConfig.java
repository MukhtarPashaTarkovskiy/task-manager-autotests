package ru.tarkovskiy.api.config;

import java.net.URI;
import io.restassured.RestAssured;
import io.restassured.config.RedirectConfig;

public final class ApiConfig {
    private ApiConfig() { }

    public static final String BASE_URL = checkedBaseUrl();
    static {
        // Do not follow a server redirect to an unapproved origin.
        RestAssured.config = RestAssured.config().redirect(RedirectConfig.redirectConfig().followRedirects(false));
    }


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