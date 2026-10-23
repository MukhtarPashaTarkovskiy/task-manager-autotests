
package ru.tarkovskiy.ui.config;

import org.testng.annotations.BeforeSuite;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class RenderReadiness {

//    private static final String LOGIN_URL =
//            "https://task-manager-test-588r.onrender.com/login";

    public void waitForRender() throws Exception {

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        long deadline = System.nanoTime()
                + Duration.ofMinutes(4).toNanos();

        while (System.nanoTime() < deadline) {

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(UiConfig.LOGIN_URL))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() == 200
                        && response.body().contains("id=\"username\"")
                        && response.body().contains("id=\"password\"")) {

                    System.out.println("TEST Render готов к UI-тестированию");
                    return;
                }

            } catch (Exception e) {
                System.out.println(
                        "TEST Render ещё недоступен: " + e.getMessage()
                );
            }

            Thread.sleep(5000);
        }

        throw new IllegalStateException(
                "TEST Render не запустился за 4 минуты"
        );
    }
}
