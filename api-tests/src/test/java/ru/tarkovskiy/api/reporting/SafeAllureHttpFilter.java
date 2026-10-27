package ru.tarkovskiy.api.reporting;

import io.qameta.allure.Allure;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.net.URI;

/** Explicit allowlist: never serialize request/response bodies, headers or cookies. */
public final class SafeAllureHttpFilter implements Filter {
    @Override
    public Response filter(FilterableRequestSpecification request,
                           FilterableResponseSpecification response, FilterContext context) {
        URI uri = URI.create(request.getURI());
        if (!"https".equals(uri.getScheme()) || !"task-manager-test-588r.onrender.com".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null) {
            throw new IllegalStateException("HTTP request refused: unapproved TEST origin");
        }
        String path = uri.getPath();
        if (!path.matches("/(?:api/(?:csrf|auth/register|tasks(?:/-?[0-9]+)?)|login|logout)")) {
            throw new IllegalStateException("HTTP request refused: unapproved TEST route");
        }
        long started = System.nanoTime();
        Response result = context.next(request, response);
        String summary = request.getMethod() + " " + path + "\nHTTP status: " + result.statusCode()
                + "\nDuration ms: " + ((System.nanoTime() - started) / 1_000_000);
        Allure.addAttachment("HTTP operation and result", "text/plain", summary, ".txt");
        return result;
    }
}
