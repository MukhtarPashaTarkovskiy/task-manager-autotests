package ru.tarkovskiy.reporting;

import org.testng.*;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Standalone Ubuntu suites only. No fixtures or test methods are called by inventory(). */
public final class UbuntuSuiteRunner {
    private UbuntuSuiteRunner() { }

    public static Map<String, Integer> inventory(String module, String kind) throws Exception {
        if (!Set.of("api-tests", "ui-tests").contains(module) || !Set.of("Smoke", "Regression").contains(kind))
            throw new IllegalArgumentException("Unknown suite");
        String pkg = "ru.tarkovskiy." + (module.equals("api-tests") ? "api" : "ui") + ".tests";
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(Path.of(module, kind.toLowerCase(Locale.ROOT) + "-tests.xml").toFile());
        Map<String, Set<String>> selected = new TreeMap<>();
        var classes = doc.getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            Element node = (Element) classes.item(i);
            Set<String> methods = new TreeSet<>();
            var includes = node.getElementsByTagName("include");
            for (int j = 0; j < includes.getLength(); j++) methods.add(((Element) includes.item(j)).getAttribute("name"));
            selected.put(node.getAttribute("name"), methods);
        }
        var packages = doc.getElementsByTagName("package");
        for (int i = 0; i < packages.getLength(); i++) {
            if (!pkg.equals(((Element) packages.item(i)).getAttribute("name"))) throw new IllegalStateException("Unexpected package");
            try (var paths = Files.list(Path.of(module, "src/test/java", pkg.replace('.', '/')))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList())
                    selected.put(pkg + "." + path.getFileName().toString().replace(".java", ""), Set.of());
            }
        }
        Map<String, Integer> expected = new TreeMap<>();
        for (var entry : selected.entrySet()) {
            if (!entry.getKey().startsWith(pkg + ".")) throw new IllegalStateException("Unexpected class");
            Class<?> type = Class.forName(entry.getKey());
            Object instance = type.getConstructor().newInstance();
            Map<String, Method> providers = new HashMap<>();
            for (Method m : type.getDeclaredMethods()) {
                DataProvider dp = m.getAnnotation(DataProvider.class);
                if (dp != null) {
                    if (dp.parallel() || m.getParameterCount() != 0 || m.getReturnType() != Object[][].class)
                        throw new IllegalStateException("Unsupported data provider");
                    providers.put(dp.name().isEmpty() ? m.getName() : dp.name(), m);
                }
            }
            Set<String> found = new TreeSet<>();
            for (Method m : type.getDeclaredMethods()) {
                Test t = m.getAnnotation(Test.class);
                if (t == null || !entry.getValue().isEmpty() && !entry.getValue().contains(m.getName())) continue;
                if (!t.enabled() || t.invocationCount() != 1 || t.dataProviderClass() != Object.class)
                    throw new IllegalStateException("Unexpected disabled/repeated test or external provider");
                // Current providers are pure Object[][] factories; no test/fixture/network call.
                int count = t.dataProvider().isEmpty() ? 1 : ((Object[][]) providers.get(t.dataProvider()).invoke(instance)).length;
                if (count < 1) throw new IllegalStateException("Empty provider");
                found.add(m.getName());
                expected.put(type.getName() + "." + m.getName(), count);
            }
            if (!entry.getValue().isEmpty() && !found.equals(entry.getValue())) throw new IllegalStateException("Missing selected method");
        }
        int count = expected.values().stream().mapToInt(Integer::intValue).sum();
        int required = module.equals("api-tests") ? (kind.equals("Smoke") ? 6 : 51) : (kind.equals("Smoke") ? 4 : 66);
        if (count != required) throw new IllegalStateException("Suite inventory changed; review expected counts");
        return expected;
    }

    public static int run(String module, String kind, IInvokedMethodListener guard) throws Exception {
        Map<String, Integer> expected = inventory(module, kind);
        return execute(module, kind, Path.of(module, kind.toLowerCase(Locale.ROOT) + "-tests.xml"), expected, guard);
    }

    static int execute(String module, String kind, Path xml, Map<String, Integer> expected, IInvokedMethodListener guard) throws Exception {
        TestNG ng = new TestNG();
        ng.setUseDefaultListeners(false);
        ng.setDataProviderThreadCount(1);
        Path part = Path.of(System.getProperty("tm.run.part"));
        ng.setOutputDirectory(part.resolve("testng-output").toString());
        ng.setTestSuites(List.of(xml.toString()));
        TestListenerAdapter counts = new TestListenerAdapter();
        ng.addListener((ITestNGListener) counts);
        if (guard != null) ng.addListener(guard);
        ng.run();
        List<ITestResult> all = new ArrayList<>(counts.getPassedTests());
        all.addAll(counts.getFailedTests()); all.addAll(counts.getSkippedTests());
        Map<String, Integer> actual = new TreeMap<>();
        for (ITestResult result : all) actual.merge(result.getMethod().getQualifiedName(), 1, Integer::sum);
        int passed = counts.getPassedTests().size(), failed = counts.getFailedTests().size(), skipped = counts.getSkippedTests().size();
        int configurations = counts.getConfigurationFailures().size() + counts.getConfigurationSkips().size();
        boolean complete = expected.equals(actual);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("module", module); summary.put("suite", kind); summary.put("expected", expected);
        summary.put("actual", actual); summary.put("passed", passed); summary.put("failed", failed);
        summary.put("skipped", skipped); summary.put("configurationErrors", configurations); summary.put("complete", complete);
        summary.put("javaVersion", Runtime.version().toString());
        summary.put("osVersion", System.getProperty("os.version"));
        summary.put("architecture", System.getProperty("os.arch"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(part.resolve("suite-summary.json").toFile(), summary);
        System.out.println("UBUNTU_SUITE_RESULT module=" + module + " suite=" + kind + " passed=" + passed
                + " failed=" + failed + " skipped=" + skipped + " complete=" + complete);
        if (module.equals("ui-tests") && kind.equals("Smoke"))
            System.out.println("UI_DIAGNOSTIC_RESULT mode=UiSmoke passed=" + passed + " failed=" + failed + " skipped=" + skipped);
        return complete && failed == 0 && skipped == 0 && configurations == 0 && !ng.hasFailure() && !ng.hasSkip() ? 0 : 1;
    }
}
