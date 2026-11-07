package ru.tarkovskiy.reporting.bootstrap;

import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.io.*;
import static java.nio.file.StandardOpenOption.*;

/** JDK-only premain: runs before TestNG and the Allure singleton are loaded. */
public final class IsolatedRunAgent {
    public static void premain(String arguments, Instrumentation ignored) throws Exception {
        if (arguments == null) throw new IllegalArgumentException("Reporting agent requires root,module arguments");
        String[] args = arguments.split(",", -1);
        if (args.length < 2 || args.length > 3 || args[0].isBlank())
            throw new IllegalArgumentException("Expected root,module[,maven=session-key]");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String module = args.length >= 2 ? args[1] : "unknown";
        if (!Set.of("api-tests", "ui-tests").contains(module)) throw new IllegalArgumentException("Reporting module required");
        if (Boolean.getBoolean("tm.reporting.initialized")) {
            Path existing = Path.of(System.getProperty("tm.run.part"));
            if (!existing.startsWith(root.resolve("test-runs")) || !existing.getParent().getFileName().toString().equals(module))
                throw new IllegalStateException("Conflicting duplicate reporting agents");
            return;
        }
        // IDEA can import Surefire argLine into a direct TestNG run. Its Maven timestamp
        // has no corresponding validate execution and must not select a Maven session.
        boolean ideaTestNg = System.getProperty("sun.java.command", "").contains("com.intellij.rt.testng.RemoteTestNGStarter");
        Path runs = root.resolve("test-runs");
        Files.createDirectories(runs);
        String id = System.getenv("TM_RUN_ID");
        String suite = Optional.ofNullable(System.getenv("TM_SUITE")).orElse("All");
        validate(suite);
        if (id == null || id.isBlank()) {
            if (args.length == 3 && !ideaTestNg) {
                if (!args[2].startsWith("maven=")) throw new IllegalArgumentException("Maven mode requires explicit maven=session-key");
                String session = args[2].substring("maven=".length());
                validate(session);
                Path sessionFile = runs.resolve(".sessions").resolve("maven-" + session + ".id");
                if (!Files.isRegularFile(sessionFile)) throw new IllegalStateException(
                    "Reporting Maven session is missing. Run Maven from the root with -am; direct TestNG uses only root,module arguments.");
                id = Files.readString(sessionFile).trim();
            } else id = newId();
        }
        validate(id);
        Path run = runs.resolve(id);
        Files.createDirectories(run);
        Path manifest = run.resolve("run.properties");
        // CREATE_NEW prevents overwriting another invocation's metadata.
        try { writeProperties(manifest, Map.of("runId", id, "created", Instant.now().toString(), "createdEpoch", Long.toString(System.currentTimeMillis()), "environment", "TEST")); }
        catch (FileAlreadyExistsException e) {
            Properties old = readProperties(manifest);
            if (!id.equals(old.getProperty("runId")) || !"TEST".equals(old.getProperty("environment"))) throw e;
        }
        Path part = run.resolve(module).resolve(suite);
        Files.createDirectories(part.getParent());
        Files.createDirectory(part); // Never silently append/retry into an existing result set.
        Path results = Files.createDirectory(part.resolve("allure-results"));
        writeProperties(part.resolve("run.properties"), Map.of("runId", id, "module", module, "suite", suite));
        Files.writeString(results.resolve("environment.properties"), "Environment=TEST\nModule=" + module + "\nRun=" + id + "\n", CREATE_NEW);
        System.setProperty("allure.results.directory", results.toString());
        System.setProperty("tm.run.id", id);
        System.setProperty("tm.run.part", part.toString());
        System.setProperty("tm.reporting.initialized", "true");
        // Premain must not write stdout: Surefire has not installed its transport yet.
    }
    /** Executed once by the root Maven validate phase, before either module forks. */
    public static void main(String[] args) throws Exception {
        Path sessions = Path.of(args[0]).resolve("test-runs/.sessions");
        Files.createDirectories(sessions);
        Files.writeString(sessions.resolve("maven-" + args[1] + ".id"), newId(), CREATE_NEW);
    }
    private static String newId() { return Instant.now().toString().replaceAll("[^0-9TZ]", "") + "-" + UUID.randomUUID(); }
    private static void validate(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")) throw new IllegalArgumentException("Invalid reporting identifier");
    }
    private static Properties readProperties(Path path) throws IOException {
        Properties p = new Properties(); try (var in = Files.newInputStream(path)) { p.load(in); } return p;
    }
    private static void writeProperties(Path path, Map<String,String> values) throws IOException {
        Properties p = new Properties(); p.putAll(values);
        try (var out = Files.newOutputStream(path, CREATE_NEW)) { p.store(out, "Reporting identity; no credentials"); }
    }
}
