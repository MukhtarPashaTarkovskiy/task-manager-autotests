package ru.tarkovskiy.reporting;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.io.*;
import static java.nio.file.StandardOpenOption.*;

/** Publishes immutable, validated snapshots; atomically selects them for ordinary Maven goals. */
public final class LatestAllurePublisher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> MODULES = List.of("api-tests", "ui-tests");
    private record Run(Path path, String id, String kind, long created, List<Path> inputs, List<JsonNode> tests, String fingerprint) { }
    private LatestAllurePublisher() { }
    public static void main(String[] args) throws Exception {
        publish(Path.of(args[0]), args[1], args.length > 2 ? args[2] : "All");
    }
    public static synchronized void publish(Path root, String runId, String kind) throws Exception {
        root = root.toAbsolutePath().normalize();
        identifier(runId); identifier(kind);
        Path runs = root.resolve("test-runs");
        Files.createDirectories(runs);
        try (var channel = FileChannel.open(runs.resolve(".latest.lock"), CREATE, WRITE); var lock = channel.lock()) {
            Path runtime = runtime(root);
            Map<String,String> selection = readSelection(root);
            for (String scope : List.of("api", "ui", "combined")) {
                Optional<Run> candidate = readRun(runs.resolve(runId), kind, scope);
                if (candidate.isEmpty()) continue;
                if (scope.equals("combined") && (!kind.equals("All") || !fullInventory(root,candidate.get()))) continue;
                Run run = candidate.get();
                String prefix = "allure.latest." + scope + ".";
                String oldOrder = selection.get(prefix + "order");
                if (oldOrder != null && Long.parseLong(oldOrder) > run.created) continue;
                Path snapshot = prepare(root, runtime, run, scope);
                selection.put(prefix + "input", slash(snapshot.resolve("input")));
                selection.put(prefix + "output", slash(snapshot.resolve("view")));
                selection.put(prefix + "runId", run.id + " / " + scope + " / " + kind);
                selection.put(prefix + "order", Long.toString(run.created));
            }
            select(root, selection);
        }
    }
    private static Optional<Run> readRun(Path path, String kind, String scope) throws Exception {
        List<String> required = scope.equals("combined") ? MODULES : List.of(scope.equals("api") ? MODULES.get(0) : MODULES.get(1));
        for (String module : required) if (!Files.isRegularFile(path.resolve(module).resolve(kind).resolve("completed.marker"))) return Optional.empty();
        if (!Files.isRegularFile(path.resolve("run.properties"))) return Optional.empty();
        Properties identity = properties(path.resolve("run.properties"));
        String id = identity.getProperty("runId");
        if (!path.getFileName().toString().equals(id) || !"TEST".equals(identity.getProperty("environment"))) throw new IOException("Invalid run identity");
        List<Path> inputs = new ArrayList<>(); List<JsonNode> tests = new ArrayList<>();
        for (String module : scope.equals("combined") ? MODULES : List.of(scope.equals("api") ? MODULES.get(0) : MODULES.get(1))) {
            Path part = path.resolve(module).resolve(kind);
            if (!Files.isRegularFile(part.resolve("completed.marker"))) return Optional.empty();
            Properties p = properties(part.resolve("run.properties"));
            if (!id.equals(p.getProperty("runId")) || !module.equals(p.getProperty("module")) || !kind.equals(p.getProperty("suite"))) throw new IOException("Partition identity mismatch");
            Path input = part.resolve("allure-results"); int count = 0;
            try (var files = Files.list(input)) {
                for (Path f : files.filter(x -> x.toString().endsWith("-result.json")).toList()) {
                    JsonNode test = JSON.readTree(f.toFile()); count++;
                    if (!Set.of("passed", "failed", "broken", "skipped", "unknown").contains(test.path("status").asText())) throw new IOException("Unknown result status");
                    for (JsonNode label : test.path("labels")) if (label.path("name").asText().equals("runId") && !id.equals(label.path("value").asText())) throw new IOException("Result Run ID mismatch");
                    attachments(test, input); tests.add(test);
                }
            }
            if (count == 0) return Optional.empty();
            inputs.add(input);
        }
        // A history stream follows the same scope, suite and test/variant inventory.
        List<String> inventory = tests.stream().map(t -> t.path("fullName").asText() + ":" + t.path("historyId").asText()).sorted().toList();
        return Optional.of(new Run(path,id,kind,Long.parseLong(identity.getProperty("createdEpoch","0")),inputs,tests,digest(String.join("\n",inventory).getBytes(StandardCharsets.UTF_8))));
    }
    private static void attachments(JsonNode node, Path input) throws Exception {
        for (JsonNode a : node.path("attachments")) {
            String name = a.path("source").asText();
            if (name.isEmpty() || !Path.of(name).getFileName().toString().equals(name) || !Files.isRegularFile(input.resolve(name))) throw new IOException("Attachment missing or unsafe");
        }
        for (JsonNode step : node.path("steps")) attachments(step,input);
    }
    private static boolean fullInventory(Path root, Run run) throws Exception {
        Set<String> observed = new HashSet<>();
        for (JsonNode test : run.tests) observed.add(test.path("fullName").asText());
        java.util.regex.Pattern method = java.util.regex.Pattern.compile("@Test\\b[\\s\\S]*?public\\s+void\\s+(\\w+)\\s*\\(");
        for (String module : MODULES) {
            String layer = module.equals("api-tests") ? "api" : "ui";
            Path source = root.resolve(module + "/src/test/java/ru/tarkovskiy/" + layer + "/tests");
            if (!Files.isDirectory(source)) return false;
            try (var files = Files.list(source)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = method.matcher(Files.readString(file));
                    while (matcher.find()) if (!observed.contains("ru.tarkovskiy." + layer + ".tests." + file.getFileName().toString().replace(".java","") + "." + matcher.group(1))) return false;
                }
            }
        }
        return true;
    }
    private static Path prepare(Path root, Path runtime, Run run, String scope) throws Exception {
        Path prepared = run.path.resolve("prepared"); Files.createDirectories(prepared);
        Path destination = prepared.resolve(scope + "-" + run.kind);
        String sourceHash = sourceHash(run.inputs);
        if (Files.isRegularFile(destination.resolve("publication.json"))) {
            JsonNode publication = JSON.readTree(destination.resolve("publication.json").toFile());
            if (!sourceHash.equals(publication.path("sourceHash").asText()) || !run.id.equals(publication.path("runId").asText())) throw new IOException("Published source changed; snapshot retained");
            return destination;
        }
        // Complete older runs in the same stream once, even if nobody opened their report.
        List<Run> previous = new ArrayList<>();
        try (var paths = Files.list(root.resolve("test-runs"))) {
            for (Path p : paths.filter(Files::isDirectory).toList()) {
                if (p.equals(run.path) || p.getFileName().toString().startsWith(".")) continue;
                Optional<Run> old = readRun(p,run.kind,scope);
                if (old.isPresent() && old.get().created < run.created && old.get().fingerprint.equals(run.fingerprint)) previous.add(old.get());
            }
        }
        previous.sort(Comparator.comparingLong(Run::created));
        Path last = previous.isEmpty() ? null : prepare(root,runtime,previous.get(previous.size()-1),scope);
        Path staging = Files.createDirectory(prepared.resolve(".pending-" + scope + "-" + UUID.randomUUID()));
        Path input = Files.createDirectory(staging.resolve("input"));
        for (Path source : run.inputs) {
            try (var files = Files.list(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (Set.of("environment.properties", "executor.json", "allure.properties").contains(file.getFileName().toString())) continue;
                    if (file.toString().endsWith(".json") || file.toString().endsWith(".txt")) audit(Files.readString(file));
                    Files.copy(file,input.resolve(file.getFileName())); // No replacement; duplicate UUIDs fail closed.
                }
            }
        }
        if (last != null) copyTree(last.resolve("html/history"),input.resolve("history"));
        Files.writeString(input.resolve("environment.properties"), "Environment=TEST\nRun=" + run.id + "\nScope=" + scope + "\nSuite=" + run.kind + "\n", CREATE_NEW);
        JSON.writeValue(input.resolve("executor.json").toFile(), Map.of("name","Task Manager TEST", "type","local", "buildName",run.id,"reportName",run.id + " / " + scope,"buildOrder",run.created));
        generate(runtime,input,staging.resolve("html"),staging.resolve("generation.log"));
        JsonNode summary = JSON.readTree(staging.resolve("html/widgets/summary.json").toFile()).path("statistic");
        Map<String,Long> expected = new TreeMap<>();
        for (String status : List.of("passed","failed","broken","skipped","unknown")) expected.put(status,run.tests.stream().filter(t -> status.equals(t.path("status").asText())).count());
        if (summary.path("total").asInt() != run.tests.size()) throw new IOException("Allure count differs from source; publication refused");
        for (var e : expected.entrySet()) if (summary.path(e.getKey()).asLong() != e.getValue()) throw new IOException("Allure status counts differ");
        JSON.writeValue(staging.resolve("publication.json").toFile(),Map.of("runId",run.id,"scope",scope,"kind",run.kind,"sourceHash",sourceHash,"createdEpoch",run.created,"statistic",expected,"fingerprint",run.fingerprint));
        // The canonical history is now immutable; the Maven plugin uses only its working input copy.
        Files.move(staging,destination,StandardCopyOption.ATOMIC_MOVE);
        return destination;
    }
    private static Path runtime(Path root) throws Exception {
        Path target = root.resolve(".allure/allure-2.46.1");
        if (Files.isRegularFile(target.resolve("lib/allure-commandline-2.46.1.jar"))) return target;
        List<Path> options = new ArrayList<>();
        if (System.getenv("ALLURE_HOME") != null) options.add(Path.of(System.getenv("ALLURE_HOME")));
        options.add(Path.of(System.getProperty("user.home"),"scoop/apps/allure/current"));
        for (Path source : options) if (Files.isRegularFile(source.resolve("lib/allure-commandline-2.46.1.jar"))) {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(".runtime-" + UUID.randomUUID());
            copyTree(source,temporary); Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); return target;
        }
        throw new IOException("Allure 2.46.1 runtime not found: install locally or set ALLURE_HOME; previous selection retained");
    }
    private static void generate(Path runtime, Path input, Path output, Path log) throws Exception {
        String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String cp = (runtime.resolve("lib").toString() + "/*") + File.pathSeparator + runtime.resolve("lib/config");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",exe).toString(),"-cp",cp,"io.qameta.allure.CommandLine","generate",input.toString(),"-o",output.toString());
        builder.environment().keySet().removeIf(k -> k.startsWith("TEST_") || Set.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS").contains(k));
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(Duration.ofMinutes(3).toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS)) { process.destroyForcibly(); throw new IOException("Allure generation timed out; selection retained"); }
        if (process.exitValue() != 0) throw new IOException("Allure generation failed; selection retained (see generation.log)");
    }
    private static Map<String,String> readSelection(Path root) throws Exception {
        Map<String,String> map = new TreeMap<>(); Path file = root.resolve(".mvn/maven.config");
        if (Files.exists(file)) for (String line : Files.readAllLines(file)) {
            String value = line.trim(); if (value.startsWith("\"") && value.endsWith("\"")) value=value.substring(1,value.length()-1);
            if (value.startsWith("-Dallure.latest.")) { int split=value.indexOf('='); if(split>0) map.put(value.substring(2,split),value.substring(split+1)); }
        }
        return map;
    }
    private static void select(Path root, Map<String,String> selection) throws Exception {
        Path file=root.resolve(".mvn/maven.config"); Files.createDirectories(file.getParent());
        List<String> lines=new ArrayList<>();
        if (Files.exists(file)) for(String line:Files.readAllLines(file)) if(!line.contains("-Dallure.latest.")) lines.add(line);
        selection.forEach((key,value)->lines.add("\"-D"+key+"="+value+"\""));
        String content=String.join("\n",lines)+"\n";
        if(Files.exists(file) && Files.readString(file).equals(content)) return;
        Path temporary=file.resolveSibling(".allure-selection-"+UUID.randomUUID());
        Files.writeString(temporary,content,CREATE_NEW);
        Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static String sourceHash(List<Path> inputs) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        for(Path input:inputs) try(var files=Files.list(input)) {
            for(Path p:files.filter(Files::isRegularFile).sorted().toList()) {md.update(p.getFileName().toString().getBytes(StandardCharsets.UTF_8));md.update(Files.readAllBytes(p));}
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static String digest(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Properties properties(Path path) throws Exception {Properties p=new Properties();try(var in=Files.newInputStream(path)){p.load(in);}return p;}
    private static void copyTree(Path from,Path to) throws Exception {try(var paths=Files.walk(from)){for(Path p:paths.toList()){Path target=to.resolve(from.relativize(p));if(Files.isDirectory(p))Files.createDirectories(target);else Files.copy(p,target);}}}
    private static String slash(Path p){return p.toString().replace('\\','/');}
    private static void identifier(String s){if(!s.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}"))throw new IllegalArgumentException("Invalid run/suite identifier");}
    private static void audit(String text) throws Exception {
        for(String key:List.of("TEST_USER_PASSWORD","TEST_DB_PASSWORD","TEST_DB_USERNAME","TEST_DB_URL","TEST_ALLOWED_DB_HOST")) {
            String value=System.getenv(key);if(value==null || value.isEmpty())continue;
            for(String form:List.of(value,java.net.URLEncoder.encode(value,StandardCharsets.UTF_8),Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))))if(text.contains(form))throw new IOException("Sensitive value detected; publication refused");
        }
    }
}
