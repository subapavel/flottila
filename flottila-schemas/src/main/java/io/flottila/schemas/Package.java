package io.flottila.schemas;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A package directory as the schemas see it: {@code package.yaml}, {@code endpoints/*.yaml},
 * {@code flows/*.yaml} and {@code flows/*.test.yaml}. {@link #crossCheck()} is what no single-file schema
 * can say: a flow's endpoints exist, its stylesheets exist, a test's flow exists, the fixtures a test names exist.
 *
 * @since 0.1
 */
public final class Package {

    private final Path dir;
    private final JsonObject manifest;
    private final Map<String, JsonObject> endpoints;
    private final Map<String, JsonObject> flows;
    private final Map<String, Path> flowFiles;
    private final Map<Path, JsonObject> tests;

    private Package(Path dir, JsonObject manifest, Map<String, JsonObject> endpoints, Map<String, JsonObject> flows, Map<String, Path> flowFiles, Map<Path, JsonObject> tests) {
        this.dir = dir;
        this.manifest = manifest;
        this.endpoints = endpoints;
        this.flows = flows;
        this.flowFiles = flowFiles;
        this.tests = tests;
    }

    public static Package load(Path dir) throws IOException {
        JsonObject manifest = Documents.read(dir.resolve("package.yaml"));
        Map<String, JsonObject> endpoints = new LinkedHashMap<>();
        for (Path p : documents(dir.resolve("endpoints"), false)) { JsonObject d = Documents.read(p); endpoints.put(d.getString("id", p.getFileName().toString()), d); }
        Map<String, JsonObject> flows = new LinkedHashMap<>();
        Map<String, Path> flowFiles = new LinkedHashMap<>();
        for (Path p : documents(dir.resolve("flows"), false)) {
            JsonObject d = Documents.read(p);
            String id = d.getString("id", p.getFileName().toString());
            flows.put(id, d);
            flowFiles.put(id, p);
        }
        Map<Path, JsonObject> tests = new LinkedHashMap<>();
        for (Path p : documents(dir.resolve("flows"), true)) tests.put(p, Documents.read(p));
        return new Package(dir, manifest, endpoints, flows, flowFiles, tests);
    }

    private static List<Path> documents(Path folder, boolean testFiles) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> s = Files.list(folder)) {
            return s.filter(p -> { String n = p.getFileName().toString(); return (n.endsWith(".yaml") || n.endsWith(".yml")) && n.contains(".test.") == testFiles; })
                    .sorted().toList();
        }
    }

    public String name() { return manifest.getString("name"); }

    public Set<String> endpointIds() { return endpoints.keySet(); }

    public List<String> flowIds() { return List.copyOf(flows.keySet()); }

    /** Problems the schemas cannot see: dangling endpoint, missing stylesheet or XSD, test of an unknown flow, missing fixture. Empty = consistent. */
    public List<String> crossCheck() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonObject> f : flows.entrySet()) {
            String flow = f.getKey();
            JsonObject from = f.getValue().getJsonObject("from", new JsonObject());
            requireEndpoint(problems, "flow '" + flow + "' from", from.getString("endpoint"));
            JsonArray steps = f.getValue().getJsonArray("steps", new JsonArray());
            for (int i = 0; i < steps.size(); i++) {
                JsonObject step = steps.getJsonObject(i);
                String where = "flow '" + flow + "' step '" + step.getString("name") + "'";
                if (step.containsKey("endpoint")) requireEndpoint(problems, where, step.getString("endpoint"));
                if (step.containsKey("stylesheet")) requireFile(problems, flowFiles.get(flow), where + " stylesheet", step.getString("stylesheet"));
                if (step.containsKey("schema")) requireFile(problems, flowFiles.get(flow), where + " schema", step.getString("schema"));
                JsonObject byProp = step.getJsonObject("endpoints", new JsonObject());
                for (String k : byProp.fieldNames()) requireEndpoint(problems, where + " endpoints." + k, byProp.getString(k));
            }
        }
        for (Map.Entry<Path, JsonObject> t : tests.entrySet()) {
            Path file = t.getKey();
            JsonObject test = t.getValue();
            String flow = test.getString("flow");
            if (!flows.containsKey(flow)) problems.add(file.getFileName() + ": flow '" + flow + "' is not in this package");
            requireMocksExist(problems, file, test.getJsonObject("mocks", new JsonObject()));
            JsonArray cases = test.getJsonArray("cases", new JsonArray());
            for (int i = 0; i < cases.size(); i++) {
                JsonObject c = cases.getJsonObject(i);
                requireFile(problems, file, "case '" + c.getString("name") + "' send", c.getString("send"));
                requireMocksExist(problems, file, c.getJsonObject("mocks", new JsonObject()));
            }
        }
        return problems;
    }

    private void requireEndpoint(List<String> problems, String where, String id) {
        if (id != null && !endpoints.containsKey(id)) problems.add(where + ": endpoint '" + id + "' is not in this package");
    }

    private void requireMocksExist(List<String> problems, Path testFile, JsonObject mocks) {
        for (String endpoint : mocks.fieldNames()) {
            if (!endpoints.containsKey(endpoint)) problems.add(testFile.getFileName() + ": mock for endpoint '" + endpoint + "' which is not in this package");
            JsonObject behaviour = mocks.getJsonObject(endpoint);
            if (behaviour.containsKey("reply")) requireFile(problems, testFile, "mock '" + endpoint + "' reply", behaviour.getString("reply"));
        }
    }

    /** Fixture and stylesheet paths are relative to the file that names them (D-040). */
    private static void requireFile(List<String> problems, Path namingFile, String what, String relative) {
        if (relative == null) return;
        Path target = namingFile.getParent().resolve(relative).normalize();
        if (!Files.isRegularFile(target)) problems.add(namingFile.getFileName() + ": " + what + " '" + relative + "' does not exist (" + target + ")");
    }

    @Override
    public String toString() { return "package " + name() + " at " + dir; }
}
