import java.nio.file.*;
import java.util.*;
public class AgentStartupProbe {
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("tm.reporting.initialized")) throw new AssertionError("Agent not initialized");
        Path part = Path.of(System.getProperty("tm.run.part"));
        if (!Files.isDirectory(part.resolve("allure-results"))) throw new AssertionError("Missing results");
        if (!part.getParent().getFileName().toString().equals(args[0])) throw new AssertionError("Wrong module");
        Class<?> agent = Class.forName("ru.tarkovskiy.reporting.bootstrap.IsolatedRunAgent");
        if (!agent.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("reporting-agent.jar")) throw new AssertionError("Bootstrap shadowed by test classpath");
        Class.forName("org.aspectj.weaver.loadtime.Agent");
        System.out.println("PASS " + args[0] + " " + System.getProperty("tm.run.id"));
    }
}
