package io.flottila.schemas;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * A package directory as the schemas see it (D-078): {@code package.yaml} at the root, a {@code folder.yaml} in any folder below
 * it, and every other {@code *.yaml} / {@code *.yml} — in any folder, whatever its name — an endpoint, a flow or a test by its
 * {@code kind}; a YAML without a known kind is a plain file when a flow or a test names it as one, else a problem. Folder names
 * are slugs of at most 64 characters, at most four folders deep. {@link #crossCheck()} is what no single-file schema can say: a
 * flow's endpoints exist, its stylesheets exist, a test's flow exists, the fixtures a test names exist — every file reference a
 * path from the package root that stays inside it. {@link #problems()} is everything a deploy refuses the package for before any
 * component is asked (D-059). {@link #manifest()}, {@link #endpoints()} and {@link #flows()} hand out copies of the documents as
 * loaded, by id; {@link #folders()} and {@link #artifacts()} are the package's tree.
 *
 * @since 0.1
 */
public final class Package {

    /** The deepest a folder may lie below the package root, the root's own children being 1 deep (D-078). */
    private static final int MAX_DEPTH = 4;
    /** A folder name: a slug of at most 64 characters (D-078); matched whole. */
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final String MANIFEST = "package.yaml";
    private static final String FOLDER = "folder.yaml";
    private static final Set<Schemas> ARTIFACTS = Set.of(Schemas.ENDPOINT, Schemas.FLOW, Schemas.TEST);

    private final Path dir;
    private final Path root;
    private final JsonObject manifest;
    private final Map<String, JsonObject> endpoints;
    private final Map<String, JsonObject> flows;
    private final Map<String, Path> flowFiles;
    private final List<Read> documents;
    private final List<String> layout;
    private final List<String> misplaced;
    private final List<String> duplicates;
    private final List<Folder> folders;
    private final List<Artifact> artifacts;

    private Package(Load load) {
        this.dir = load.dir;
        this.root = load.root;
        this.manifest = load.manifest;
        this.endpoints = load.endpoints;
        this.flows = load.flows;
        this.flowFiles = load.flowFiles;
        this.documents = List.copyOf(load.documents);
        this.layout = List.copyOf(load.layout.values());
        this.misplaced = List.copyOf(load.misplaced);
        this.duplicates = List.copyOf(load.duplicates);
        List<Folder> folders = new ArrayList<>();
        for (String path : load.folders.stream().sorted().toList()) {
            JsonObject d = load.folderDocs.getOrDefault(path, new JsonObject());
            folders.add(new Folder(path, text(d, "namespace"), text(d, "description")));
        }
        this.folders = List.copyOf(folders);
        this.artifacts = documents.stream().filter(r -> ARTIFACTS.contains(r.schema()))   // documents are in path order already
                .map(r -> new Artifact(r.path(), r.schema().kind(), r.schema() == Schemas.TEST ? Optional.empty() : Optional.of(id(r.document(), r.file()))))
                .toList();
    }

    /**
     * One folder below the package root.
     *
     * @param path        from the package root, {@code /}-separated
     * @param namespace   its {@code folder.yaml}'s namespace, verbatim; empty without one
     * @param description its {@code folder.yaml}'s description; empty without one
     * @since 0.1
     */
    public record Folder(String path, Optional<String> namespace, Optional<String> description) {}

    /**
     * One endpoint, flow or test document of the package ({@code package.yaml} and {@code folder.yaml} are none).
     *
     * @param path from the package root, {@code /}-separated
     * @param kind {@code endpoint}, {@code flow} or {@code test}
     * @param id   the document's id; empty for a test, which has none
     * @since 0.1
     */
    public record Artifact(String path, String kind, Optional<String> id) {}

    /** One document as read, with the schema its kind (or its name) names and that schema's verdict on it. */
    private record Read(String path, Path file, Schemas schema, JsonObject document, Schemas.Report report) {
        Read(String path, Path file, Schemas schema, JsonObject document) { this(path, file, schema, document, schema.validate(document)); }
    }

    /**
     * Reads the package in {@code dir}: {@code package.yaml} first, then every folder below it and every YAML in them, in
     * {@code /}-path order. A file that does not parse, or cannot be read, stops the load — unless a flow or a test names it as a
     * file and it is no {@code package.yaml} or {@code folder.yaml}: an {@link IllegalArgumentException} whose message starts with
     * that file's path relative to {@code dir} (never an absolute one).
     *
     * @throws IOException a folder of the package cannot be listed
     */
    public static Package load(Path dir) throws IOException {
        Load load = new Load(dir);
        load.walk(dir, 0);
        load.classify();
        return new Package(load);
    }

    /** What {@link #load} gathers on its way through the package. */
    private static final class Load {
        final Path dir;
        final Path root;
        final JsonObject manifest;
        final List<Read> documents = new ArrayList<>();
        final Map<String, String> layout = new TreeMap<>();   // a folder's path → its one line: in path order
        final List<String> folders = new ArrayList<>();
        final Map<String, JsonObject> folderDocs = new HashMap<>();   // a folder's path → its folder.yaml
        final Map<String, String> namespaces = new HashMap<>();       // a namespace → the folder.yaml that declares it
        final List<Path> yamls = new ArrayList<>();
        final List<String> misplaced = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();
        final Map<String, JsonObject> endpoints = new LinkedHashMap<>();
        final Map<String, Path> endpointFiles = new HashMap<>();
        final Map<String, JsonObject> flows = new LinkedHashMap<>();
        final Map<String, Path> flowFiles = new LinkedHashMap<>();

        /** A missing or unreadable {@code package.yaml} stops the load before anything else is read. */
        Load(Path dir) {
            this.dir = dir;
            this.root = dir.toAbsolutePath().normalize();
            Path manifestFile = dir.resolve(MANIFEST);
            this.manifest = read(dir, manifestFile);
            documents.add(new Read(MANIFEST, manifestFile, Schemas.PACKAGE, manifest));
        }

        /** Every folder below {@code folder} ({@code depth} deep) with its name checked, and every YAML in them; never below a folder too deep. */
        void walk(Path folder, int depth) throws IOException {
            List<Path> entries;
            try (Stream<Path> s = Files.list(folder)) {
                entries = s.toList();
            }
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    String path = path(dir, p);
                    int deep = depth + 1;
                    if (deep > MAX_DEPTH) {
                        layout.put(path, path + ": " + deep + " folders deep, at most " + MAX_DEPTH);   // its own folders are not read
                        continue;
                    }
                    if (!SLUG.matcher(name).matches()) layout.put(path, path + ": folder name '" + name + "' is not a slug");
                    folders.add(path);
                    walk(p, deep);
                } else if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                    yamls.add(p);
                }
            }
        }

        /** Each YAML by its name, then by its kind; a kind-less one, or one that does not read, is a problem unless a flow or test names it. */
        void classify() {
            yamls.sort(Comparator.comparing(f -> path(dir, f)));
            Map<Path, Object> kindless = new LinkedHashMap<>();             // a file → its kind value, null without one
            Map<Path, RuntimeException> unread = new LinkedHashMap<>();    // a file → why it does not read
            for (Path file : yamls) {
                String path = path(dir, file);
                String name = file.getFileName().toString();
                boolean atRoot = path.equals(name);
                if (name.equals(MANIFEST)) {
                    if (!atRoot) misplaced.add(path + ": a package.yaml belongs at the package root only");
                } else if (name.equals(FOLDER)) {
                    if (atRoot) misplaced.add(path + ": a folder.yaml describes the folder it lies in, never the package root");
                    else folder(file, path);
                } else {
                    JsonObject d;
                    try {
                        d = Documents.read(file);
                    } catch (IllegalArgumentException | UncheckedIOException notADocument) {
                        unread.put(file, notADocument);
                        continue;
                    }
                    Object kind = d.getValue("kind");
                    if ("endpoint".equals(kind)) byId(file, path, d, Schemas.ENDPOINT, endpoints, endpointFiles);
                    else if ("flow".equals(kind)) byId(file, path, d, Schemas.FLOW, flows, flowFiles);
                    else if ("test".equals(kind)) documents.add(new Read(path, file, Schemas.TEST, d));
                    else if ("package".equals(kind)) misplaced.add(path + ": kind 'package' belongs in package.yaml at the package root only");
                    else if ("folder".equals(kind)) misplaced.add(path + ": kind 'folder' belongs in a file named folder.yaml");
                    else kindless.put(file, kind);
                }
            }
            Named named = named();
            kindless.forEach((file, kind) -> {
                if (!named.names(file)) {
                    misplaced.add(path(dir, file) + " at '/kind': " + (kind == null ? "no kind" : "unknown kind '" + kind + "'")
                            + ": a YAML is an endpoint, a flow or a test by its kind, or a file a flow or a test names");
                }
            });
            unread.forEach((file, why) -> {
                if (!named.names(file)) throw unreadable(dir, file, why);
            });
        }

        /** A folder.yaml: read by the folder schema, its folder's namespace and description; a namespace a second folder declares is a duplicate. */
        private void folder(Path file, String path) {
            JsonObject d = read(dir, file);
            documents.add(new Read(path, file, Schemas.FOLDER, d));
            folderDocs.put(path.substring(0, path.lastIndexOf('/')), d);
            if (d.getValue("namespace") instanceof String namespace) {
                String earlier = namespaces.put(namespace, path);
                if (earlier != null) duplicates.add("namespace '" + namespace + "' is declared twice, in " + earlier + " and in " + path);
            }
        }

        /** {@code d} under its id (its file name when it has none); a later file of the same id replaces the earlier one, and is a duplicate. */
        private void byId(Path file, String path, JsonObject d, Schemas schema, Map<String, JsonObject> byId, Map<String, Path> files) {
            documents.add(new Read(path, file, schema, d));
            String id = id(d, file);
            byId.put(id, d);
            Path earlier = files.put(id, file);
            if (earlier != null) duplicates.add(schema.kind() + " id '" + id + "' is declared twice, in " + path(dir, earlier) + " and in " + path);
        }

        /**
         * The files the flows and tests that passed their schema name: a step's {@code stylesheet} or {@code schema}, a case's
         * {@code send}, a mock's {@code reply} — each a path from the root — and a mock's {@code files} or {@code messages}, a path from
         * the root whose last segment is a glob over the file names of its folder (as the mock feeds them).
         */
        private Named named() {
            Named named = new Named(root, new HashSet<>(), new ArrayList<>());
            for (Read r : documents) {
                if (!r.report().valid()) continue;
                if (r.schema() == Schemas.FLOW) {
                    JsonArray steps = r.document().getJsonArray("steps", new JsonArray());
                    for (int i = 0; i < steps.size(); i++) {
                        named.file(steps.getJsonObject(i).getValue("stylesheet"));
                        named.file(steps.getJsonObject(i).getValue("schema"));
                    }
                } else if (r.schema() == Schemas.TEST) {
                    named.mocks(r.document().getJsonObject("mocks", new JsonObject()));
                    JsonArray cases = r.document().getJsonArray("cases", new JsonArray());
                    for (int i = 0; i < cases.size(); i++) {
                        named.file(cases.getJsonObject(i).getValue("send"));
                        named.mocks(cases.getJsonObject(i).getJsonObject("mocks", new JsonObject()));
                    }
                }
            }
            return named;
        }
    }

    /** The files a package's flows and tests name: by path and by glob, each absolute and normalised. */
    private record Named(Path root, Set<Path> files, List<Path> globs) {

        void file(Object reference) {
            if (reference instanceof String path) files.add(root.resolve(path).normalize());
        }

        void mocks(JsonObject mocks) {
            for (String endpoint : mocks.fieldNames()) {
                JsonObject behaviour = mocks.getJsonObject(endpoint);
                file(behaviour.getValue("reply"));
                glob(behaviour.getValue("files"));
                glob(behaviour.getValue("messages"));
            }
        }

        void glob(Object reference) {
            if (reference instanceof String glob) globs.add(root.resolve(glob).normalize());
        }

        boolean names(Path file) {
            Path absolute = file.toAbsolutePath().normalize();
            return files.contains(absolute) || globs.stream().anyMatch(glob -> matches(glob, absolute));
        }

        /** Whether {@code file} lies in {@code glob}'s folder and its name matches {@code glob}'s last segment. */
        private static boolean matches(Path glob, Path file) {
            try {
                return file.getParent().equals(glob.getParent()) && file.getFileSystem().getPathMatcher("glob:" + glob.getFileName()).matches(file.getFileName());
            } catch (PatternSyntaxException notAGlob) {
                return false;   // names no file; the mock refuses it where it feeds
            }
        }
    }

    /** {@code file} as a document; one that does not parse or cannot be read is refused naming its path relative to {@code dir}. */
    private static JsonObject read(Path dir, Path file) {
        try {
            return Documents.read(file);
        } catch (IllegalArgumentException | UncheckedIOException unreadable) {
            throw unreadable(dir, file, unreadable);
        }
    }

    private static IllegalArgumentException unreadable(Path dir, Path file, RuntimeException unreadable) {
        String why = unreadable instanceof UncheckedIOException io ? "cannot be read (" + io.getCause().getClass().getSimpleName() + ")" : unreadable.getMessage();
        return new IllegalArgumentException(path(dir, file) + ": " + why, unreadable);
    }

    private static String id(JsonObject document, Path file) {
        return document.getString("id", file.getFileName().toString());
    }

    /** {@code key}'s value when it is a string, else empty. */
    private static Optional<String> text(JsonObject document, String key) {
        return document.getValue(key) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /** {@code file} from {@code dir}, {@code /}-separated: no path this class shows is absolute. */
    private static String path(Path dir, Path file) {
        return dir.relativize(file).toString().replace(File.separatorChar, '/');
    }

    public String name() { return manifest.getString("name"); }

    public Set<String> endpointIds() { return endpoints.keySet(); }

    public List<String> flowIds() { return List.copyOf(flows.keySet()); }

    /** {@code package.yaml} as loaded: a copy, the caller's to change. */
    public JsonObject manifest() { return manifest.copy(); }

    /** Every endpoint document by its id, in path order: copies, the caller's to change. */
    public Map<String, JsonObject> endpoints() { return copies(endpoints); }

    /** Every flow document by its id, in path order: copies, the caller's to change. */
    public Map<String, JsonObject> flows() { return copies(flows); }

    /** Every folder below the package root, sorted by path; one more than four deep is not listed (a problem says so). */
    public List<Folder> folders() { return folders; }

    /** Every endpoint, flow and test document, sorted by path. */
    public List<Artifact> artifacts() { return artifacts; }

    /**
     * The file the flow {@code id} was read from, wherever it lies and whatever it is called; the steps' file references resolve
     * against the package root, not its folder (D-078).
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
     * Everything wrong with the package, empty when nothing is: first the layout — a folder name that is no slug, a folder more than
     * four deep (one line on the shallowest) — in path order; then every document against its kind's schema, each problem as
     * {@code <file> at '<JSON pointer>': <message>} with the file relative to the package directory; then a {@code package.yaml} or
     * {@code folder.yaml} out of its place and a YAML of no known kind that no flow or test names ({@code <file> at '/kind': …});
     * then each endpoint or flow id, or folder namespace, that a second file declares again (only the later file is kept); then
     * {@link #crossCheck()}.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>(layout);
        for (Read r : documents) {
            for (Schemas.Problem p : r.report().problems()) {
                problems.add(r.path() + " at '" + p.path() + "': " + p.message());
            }
        }
        problems.addAll(misplaced);
        problems.addAll(duplicates);
        problems.addAll(crossCheck());
        return List.copyOf(problems);
    }

    /**
     * Problems the schemas cannot see: dangling endpoint (a test's mock or {@code expect.sent} included), missing stylesheet or
     * XSD, test of an unknown flow, missing fixture, a file reference that leaves the package, and a step field the flow schema
     * leaves untyped holding a value of the wrong type.
     * Empty = consistent. A line that names a file names it by its path relative to the package directory. Only the flow and test
     * files that passed their schema are read: the schema's problems already say what is wrong with the others, whose shape
     * nothing here may rely on. Their ids still count as present.
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
            for (String endpoint : c.getJsonObject("expect").getJsonObject("sent", new JsonObject()).fieldNames()) {
                if (!endpoints.containsKey(endpoint)) {
                    problems.add(relative(file) + ": case '" + c.getString("name") + "' expects sends to endpoint '" + endpoint + "' which is not in this package");
                }
            }
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

    /**
     * A stylesheet, schema or fixture reference is a path from the package root (D-078, amending D-040), whichever file names it;
     * one that normalises outside the package leaves it, whether or not a file is there.
     */
    private void requireFile(List<String> problems, Path namingFile, String what, String reference) {
        if (reference == null) return;
        Path target = root.resolve(reference).normalize();
        if (!target.startsWith(root)) problems.add(relative(namingFile) + ": " + what + " '" + reference + "' leaves the package");
        else if (!Files.isRegularFile(target)) problems.add(relative(namingFile) + ": " + what + " '" + reference + "' does not exist");
    }

    /** A file of this package by its path relative to the package directory: no problem line carries an absolute path. */
    private String relative(Path file) {
        return path(dir, file);
    }

    @Override
    public String toString() { return "package " + name() + " at " + dir; }
}
