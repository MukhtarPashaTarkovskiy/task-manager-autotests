package ru.tarkovskiy.ui.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.os.ExternalProcess;
import org.openqa.selenium.remote.service.DriverFinder;
import org.openqa.selenium.remote.service.DriverService;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Real Selenium selection/process builder, fake executable and descendant; no HTTP or WebDriver session. */
public final class UbuntuDriverEnvironmentProbe {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Driver environment check " + checks); }
    private static JsonNode launch(ChromeDriverService service, Path directory, String mode) throws Exception {
        Files.createDirectory(directory);
        String executable = new DriverFinder(service, new ChromeOptions()).getDriverPath();
        var builder = ExternalProcess.builder().command(executable, List.of("--probe", mode, directory.toString()));
        // This is the 4.27 DriverService.start process transport, without its HTTP status polling.
        Method environment = DriverService.class.getDeclaredMethod("getEnvironment");
        environment.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, String> values = (Map<String, String>) environment.invoke(service);
        values.forEach(builder::environment);
        ExternalProcess process = builder.start();
        try {
            check(process.waitFor(Duration.ofSeconds(10)));
            check(process.exitValue() == 0);
        } finally { if (process.isAlive()) process.shutdown(); }
        return new ObjectMapper().readTree(directory.resolve("driver.json").toFile());
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0]; Path root = Path.of(args[1]);
        Map<String, String> parent = new HashMap<>(System.getenv());
        for (String key : List.of("TEST_USER_PASSWORD", "TEST_DB_URL", "TEST_DB_USERNAME", "TEST_DB_PASSWORD", "TEST_ALLOWED_DB_HOST", "TEST_ALLOWED_DB_PORT"))
            check(mode.equals("NavigationOnly") ? !parent.containsKey(key) : parent.containsKey(key) && !parent.get(key).isBlank());
        check(parent.containsKey("TEST_FUTURE_SECRET") && parent.containsKey("TM_PRIVATE_SETTING"));
        UbuntuDiagnosticLauncher.configureDriver();
        ChromeDriverService service = mode.equals("NavigationOnly") ? UbuntuDiagnosticLauncher.selectedService()
                : ChromeDriverService.createDefaultService(); // BaseUiTest's unchanged default service path.
        String selected = new DriverFinder(service, new ChromeOptions()).getDriverPath();
        check(selected.equals(parent.get("UI_DIAGNOSTIC_DRIVER_PATH")));
        Path safe = root.resolve(mode);
        JsonNode driver = launch(service, safe, mode);
        JsonNode browser = new ObjectMapper().readTree(safe.resolve("browser.json").toFile());
        for (JsonNode child : List.of(driver, browser)) {
            check(child.path("blockedKeys").isEmpty());
            check(child.path("args").get(0).asText().equals("--probe"));
            check(child.path("args").get(1).asText().equals(mode));
            for (String key : List.of("PATH", "HOME", "TMPDIR", "LANG", "HEADLESS", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_RUNTIME_DIR")) {
                if (parent.containsKey(key)) {
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(parent.get(key).getBytes(StandardCharsets.UTF_8)));
                    check(child.path("systemDigests").path(key).asText().equals(digest));
                }
            }
        }
        check(parent.equals(System.getenv())); // JDBC/auth secrets remain in the test JVM.
        // Negative control: the identical fake driver leaks environment if the adapter is bypassed.
        ChromeDriverService unfiltered = new ChromeDriverService.Builder().usingDriverExecutable(Path.of(args[2]).toFile()).build();
        JsonNode control = launch(unfiltered, root.resolve(mode + "-control"), mode);
        check(!control.path("blockedKeys").isEmpty());
        System.out.println("PASS: " + checks + " actual Selenium process/environment checks for " + mode + "; fake driver/browser only");
    }
}
