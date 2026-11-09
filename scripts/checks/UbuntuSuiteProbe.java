package ru.tarkovskiy.reporting;

import org.testng.annotations.*;
import org.testng.Assert;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Executes ONLY synthetic in-memory-style fixtures, never business tests or their lifecycle. */
public final class UbuntuSuiteProbe {
    public static class Passing { @Test public void ok() { } }
    public static class Failing { @Test public void fail() { Assert.fail("synthetic"); } }
    public static class Skipping { @Test public void skip() { throw new org.testng.SkipException("synthetic"); } }
    public static class SetupFailure {
        @BeforeMethod public void before() { throw new IllegalStateException("synthetic"); }
        @Test public void never() { throw new AssertionError("must not run"); }
    }
    public static class CleanupFailure {
        @Test public void ok() { }
        @AfterMethod public void after() { throw new IllegalStateException("synthetic"); }
    }
    public static class Variants {
        @DataProvider public Object[][] cases() { return new Object[][] {{1}, {2}}; }
        @Test(dataProvider="cases") public void variant(int n) { Assert.assertTrue(n > 0); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        if (args.length == 2) {
            // A separate JVM with the REAL reporting-agent/SPI, synthetic fixture only.
            Class<?> type = args[1].equals("setup") ? SetupFailure.class : args[1].equals("cleanup") ? CleanupFailure.class : Failing.class;
            Path xml = root.resolve("synthetic-" + args[1] + ".xml");
            Files.writeString(xml, "<suite name=\"offline\"><test name=\"synthetic\"><classes><class name=\"" + type.getName() + "\"/></classes></test></suite>");
            String method = type == SetupFailure.class ? "never" : type == CleanupFailure.class ? "ok" : "fail";
            if (UbuntuSuiteRunner.execute("synthetic", "Offline", xml, Map.of(type.getName() + "." + method, 1), null) == 0)
                throw new AssertionError("Synthetic failure was green");
            if (!Files.isRegularFile(Path.of(System.getProperty("tm.run.part"), "completed.marker")))
                throw new AssertionError("Real reporting listener did not complete");
            return;
        }
        int checks = 0;
        for (String module : List.of("api-tests", "ui-tests")) {
            for (String kind : List.of("Smoke", "Regression")) {
                Map<String, Integer> inventory = UbuntuSuiteRunner.inventory(module, kind);
                int total = inventory.values().stream().mapToInt(Integer::intValue).sum();
                System.out.println("OFFLINE_INVENTORY " + module + " " + kind + " " + total);
                if (inventory.isEmpty()) throw new AssertionError("Empty inventory");
                checks++;
            }
        }
        for (Class<?> type : List.of(Passing.class, Failing.class, Skipping.class, SetupFailure.class, CleanupFailure.class, Variants.class)) {
            Path part = Files.createTempDirectory(root, "synthetic-suite-");
            System.setProperty("tm.run.part", part.toString());
            Path xml = part.resolve("synthetic.xml");
            Files.writeString(xml, "<suite name=\"offline\"><test name=\"synthetic\"><classes><class name=\"" + type.getName() + "\"/></classes></test></suite>");
            String method = type == Passing.class || type == CleanupFailure.class ? "ok" : type == Variants.class ? "variant" : type == Failing.class ? "fail" : type == Skipping.class ? "skip" : "never";
            int code = UbuntuSuiteRunner.execute("synthetic", "Offline", xml, Map.of(type.getName() + "." + method, type == Variants.class ? 2 : 1), null);
            boolean pass = type == Passing.class || type == Variants.class;
            if ((code == 0) != pass) throw new AssertionError("Incorrect synthetic outcome: " + type.getSimpleName());
            if (!Files.isRegularFile(part.resolve("suite-summary.json"))) throw new AssertionError("Missing summary");
            checks++;
        }
        Path missing = Files.createTempDirectory(root, "incomplete-suite-");
        System.setProperty("tm.run.part", missing.toString());
        Path xml = missing.resolve("synthetic.xml");
        Files.writeString(xml, "<suite name=\"offline\"><test name=\"synthetic\"><classes><class name=\"" + Passing.class.getName() + "\"/></classes></test></suite>");
        if (UbuntuSuiteRunner.execute("synthetic", "Offline", xml, Map.of(Passing.class.getName() + ".ok", 2), null) == 0)
            throw new AssertionError("Incomplete suite was green");
        checks++;
        System.out.println("PASS: " + checks + " offline inventory/outcome checks; no business tests/fixtures executed");
    }
}
