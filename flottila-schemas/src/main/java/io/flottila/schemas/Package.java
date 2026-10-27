package io.flottila.schemas;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * {@link #problems()} is everything a deploy refuses the package for before any component is asked (D-059).
 * {@link #manifest()}, {@link #endpoints()} and {@link #flows()} hand out copies of the documents as loaded, by id.
 *
 * @since 0.1
 */
public final class Package {

    private final Path dir;
    private final JsonObject manifest;
    private final Map<String, JsonObject> endpoints;
    private final Map<String, JsonObject> flows;
    private final Map<String, Path> flowFiles;
    private final List<Read> documents;
    private final List<String> duplicates;

    private Package(Path dir, JsonObject manifest, Map<String, JsonObject> endpoints, Map<String, JsonObject> flows, Map<String, Path> flowFiles,
                    List<Read> documents, List<String> duplicates) {
        this.dir = dir;
        this.manifest = manifest;
        this.endpoints = endpoints;
        this.flows = flows;
        this.flowFiles = flowFiles;
        this.documents = documents;
        this.duplicates = duplicates;
    }

    /** One document as read, with the schema its place in the package names and that schema's verdict on it. */
    private record Read(Path file, Schemas schema, JsonObject document, Schemas.Report report) {
        Read(Path file, Schemas schema, JsonObject document) { this(file, schema, document, schema.validate(document)); }
    }

    /**
     * Reads the package in {@code dir}. A file that does not parse, or cannot be read, stops the load: an
     * {@link IllegalArgumentException} whose message starts with that file's path relative to {@code dir} (never an absolute one).
     *
     * @throws IOException a folder of the package cannot be listed
     */
    public static Package load(Path dir) throws IOException {
        List<Read> documents = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        Path manifestFile = dir.resolve("package.yaml");
        JsonObject manifest = read(dir, manifestFile);
        documents.add(new Read(manifestFile, Schemas.PACKAGE, manifest));
        Map<String, JsonObject> endpoints = new LinkedHashMap<>();
        Map<String, Path> endpointFiles = new LinkedHashMap<>();
        for (Path p : documents(dir.resolve("endpoints"), false)) readById(dir, p, Schemas.ENDPOINT, documents, endpoints, endpointFiles, duplicates);
        Map<String, JsonObject> flows = new LinkedHashMap<>();
        Map<String, Path> flowFiles = new LinkedHashMap<>();
        for (Path p : documents(dir.resolve("flows"), false)) readById(dir, p, Schemas.FLOW, documents, flows, flowFiles, duplicates);
        for (Path p : documents(dir.resolve("flows"), true)) documents.add(new Read(p, Schemas.TEST, read(dir, p)));
        return new Package(dir, manifest, endpoints, flows, flowFiles, List.copyOf(documents), List.copyOf(duplicates));
    }

    /** {@code file} as a document; one that does not parse or cannot be read is refused naming its path relative to {@code dir}. */
    private static JsonObject read(Path dir, Path file) {
        try {
            return Documents.read(file);
        } catch (IllegalArgumentException | UncheckedIOException unreadable) {
            String why = unreadable instanceof UncheckedIOException io ? "cannot be read (" + io.getCause().getClass().getSimpleName() + ")" : unreadable.getMessage();
            throw new IllegalArgumentException(dir.relativize(file) + ": " + why, unreadable);
        }
    }

    /** Reads {@code file} under its id (its file name when it has none); a later file of the same id replaces the earlier one, and is a duplicate. */
    private static void readById(Path dir, Path file, Schemas schema, List<Read> documents, Map<String, JsonObject> byId, Map<String, Path> files, List<String> duplicates) {
        JsonObject d = read(dir, file);
        documents.add(new Read(file, schema, d));
        String id = id(d, file);
        byId.put(id, d);
        Path earlier = files.put(id, file);
        if (earlier != null) {
            duplicates.add(schema.kind() + " id '" + id + "' is declared twice, in " + dir.relativize(earlier) + " and in " + dir.relativize(file));
        }
    }

    private static String id(JsonObject document, Path file) {
        return document.getString("id", file.getFileName().toString());
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

    /** {@code package.yaml} as loaded: a copy, the caller's to change. */
    public JsonObject manifest() { return manifest.copy(); }

    /** Every endpoint document by its id, in file order: copies, the caller's to change. */
    public Map<String, JsonObject> endpoints() { return copies(endpoints); }

    /** Every flow document by its id, in file order: copies, the caller's to change. */
    public Map<String, JsonObject> flows() { return copies(flows); }

    /**
     * The file the flow {@code id} was read from, whatever it is called: the steps' file references resolve against its folder.
     *
     * @throws IllegalArgumentException no flow of this package has the id
     */
    public Path flowFile(String id) {
        Path file = flowFiles.get(id);
        if (file == null) throw new IllegalArgumentException("no flow '" + id + "' in this package " + flowFiles.keySet());
        return file;
    }

    private static Map<String, JsonObject> copies(Map<String, JsonObject> documents) {
        Map<String, JsonObject> copies = new LinkedHashMap<>();
        documents.forEach((id, d) -> copies.put(id, d.copy()));
        return copies;
    }

    /**
     * Everything wrong with the package, empty when nothing is: every document against its kind's schema, each problem as
     * {@code <file> at '<JSON pointer>': <message>} with the file relative to the package directory; then each endpoint or flow
     * id that a second file declares again (only the later file is kept); then {@link #crossCheck()}.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        for (Read r : documents) {
            for (Schemas.Problem p : r.report().problems()) {
                problems.add(dir.relativize(r.file()) + " at '" + p.path() + "': " + p.message());
            }
        }
        problems.addAll(duplicates);
        problems.addAll(crossCheck());
        return List.copyOf(problems);
    }

    /**
     * Problems the schemas cannot see: dangling endpoint, missing stylesheet or XSD, test of an unknown flow, missing fixture, and
     * a step field the flow schema leaves untyped holding a value of the wrong type. Empty = consistent. A line that names a file
     * names it by its path relative to the package directory. Only the flow and test files that passed their schema are read: the
     * schema's problems already say what is wrong with the others, whose shape nothing here may rely on. Their ids still count as present.
     */
    public List<String> crossCheck() {
        List<String> problems = new ArrayList<>();
        for (Read r : documents) {
            if (!r.report().valid()) continue;
            if (r.schema() == Schemas.FLOW) crossCheckFlow(problems, r.file(), r.document());
            else if (r.schema() == Schemas.TEST) crossCheckTest(problems, r.file(), r.document());
        }
        return problems;
    }

    private void crossCheckFlow(List<String> problems, Path file, JsonObject document) {
        String flow = id(document, file);
        JsonObject from = document.getJsonObject("from", new JsonObject());
        requireEndpoint(problems, "flow '" + flow + "' from", from.getString("endpoint"));
        JsonArray steps = document.getJsonArray("steps", new JsonArray());
        for (int i = 0; i < steps.size(); i++) {
            JsonObject step = steps.getJsonObject(i);
            String where = "flow '" + flow + "' step '" + step.getString("name") + "'";
            // a step's fields beyond its type's requirements are untyped in the flow schema: each is read through a type guard
            requireEndpoint(problems, where, string(problems, file, where + " endpoint", step.getValue("endpoint")));
            requireFile(problems, file, where + " stylesheet", string(problems, file, where + " stylesheet", step.getValue("stylesheet")));
            requireFile(problems, file, where + " schema", string(problems, file, where + " schema", step.getValue("schema")));
            Object endpoints = step.getValue("endpoints");
            if (endpoints instanceof JsonObject byValue) {
                for (String k : byValue.fieldNames()) {
                    String what = where + " endpoints." + k;
                    requireEndpoint(problems, what, string(problems, file, what, byValue.getValue(k)));
                }
            } else if (endpoints != null) {
                problems.add(relative(file) + ": " + where + " endpoints must map property values to endpoint ids, was " + endpoints);
            }
        }
    }

    /** An untyped field's value when it is a string or absent (null); any other value is a problem naming the file and the field. */
    private String string(List<String> problems, Path file, String what, Object value) {
        if (value == null || value instanceof String) return (String) value;
        problems.add(relative(file) + ": " + what + " must be a string, was " + value);
        return null;
    }

    private void crossCheckTest(List<String> problems, Path file, JsonObject test) {
        String flow = test.getString("flow");
        if (!flows.containsKey(flow)) problems.add(relative(file) + ": flow '" + flow + "' is not in this package");
        requireMocksExist(problems, file, test.getJsonObject("mocks", new JsonObject()));
        JsonArray cases = test.getJsonArray("cases", new JsonArray());
        for (int i = 0; i < cases.size(); i++) {
            JsonObject c = cases.getJsonObject(i);
            requireFile(problems, file, "case '" + c.getString("name") + "' send", c.getString("send"));
            requireMocksExist(problems, file, c.getJsonObject("mocks", new JsonObject()));
        }
    }

    private void requireEndpoint(List<String> problems, String where, String id) {
        if (id != null && !endpoints.containsKey(id)) problems.add(where + ": endpoint '" + id + "' is not in this package");
    }

    private void requireMocksExist(List<String> problems, Path testFile, JsonObject mocks) {
        for (String endpoint : mocks.fieldNames()) {
            if (!endpoints.containsKey(endpoint)) problems.add(relative(testFile) + ": mock for endpoint '" + endpoint + "' which is not in this package");
            JsonObject behaviour = mocks.getJsonObject(endpoint);
            if (behaviour.containsKey("reply")) requireFile(problems, testFile, "mock '" + endpoint + "' reply", behaviour.getString("reply"));
        }
    }

    /** Fixture and stylesheet paths are relative to the file that names them (D-040). */
    private void requireFile(List<String> problems, Path namingFile, String what, String reference) {
        if (reference == null) return;
        Path target = namingFile.getParent().resolve(reference).normalize();
        if (!Files.isRegularFile(target)) problems.add(relative(namingFile) + ": " + what + " '" + reference + "' does not exist");
    }

    /** A file of this package by its path relative to the package directory: no problem line carries an absolute path. */
    private Path relative(Path file) {
        return dir.relativize(file);
    }

    @Override
    public String toString() { return "package " + name() + " at " + dir; }
}
