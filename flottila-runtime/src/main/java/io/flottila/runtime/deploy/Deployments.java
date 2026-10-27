package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Runtime;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.flow.Delivery;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.flottila.core.record.MessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.schemas.Package;
import io.flottila.schemas.Schemas;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The packages deployed on this node and their running flows (D-059, D-060 a/b). The deployed state lives in memory until
 * P1-09c (F-5); a deploy of a name already deployed is refused until redeploy exists (P1-09d2).
 *
 * <p>{@link #deploy} blocks: call it on a virtual thread. Its order: a bad name or revision ({@link Kind#BAD_REQUEST}); a
 * deployed environment ({@link Kind#FORBIDDEN}: manual deploy is non-production only, ARCHITECTURE §9); a closing node
 * ({@link Kind#STOPPING}); another deploy holding the lock or the name deployed already ({@link Kind#CONFLICT}) — all of them
 * before the archive is read. Then the archive is unpacked into {@code <dataDir>/.incoming} and moved atomically to
 * {@code <dataDir>/packages/<name>/<sha256>/} (a directory an earlier node left there is replaced); a refused archive is
 * {@link Kind#BAD_REQUEST}. Then the package is checked whole — {@code Package.problems()}, the manifest's name, no
 * {@code dependencies}, a component for every scheme, every endpoint's config (its environment's keys laid over it) against
 * its component's {@code configSchema()}, no in endpoint setting {@code flow} or {@code maxBodyBytes}, one flow per in endpoint,
 * no flow from an out endpoint, every flow definition, no at-least-once flow above {@code FLOTTILA_BLOB_DURABLE_MAX} (D-058) — and every flow built, its
 * delivery, its worker (at-least-once) and its consumer; every problem is one line of one {@link Kind#INVALID}, and nothing
 * starts before every check passed. Then the consumers start one after the other (an at-least-once flow's worker right after
 * its consumer, as worker {@code nodeId}); one that does not start is {@link Kind#FAILED}. Whatever refuses the deploy after
 * the unpack began stops every flow it built (zero deadline) and deletes its directories, {@code packages/<name>/} too when
 * nothing else is in it: nothing of it runs, is listed or stays on disk.
 *
 * <p>A flow runs as {@code <package>/<flow>} (D-060 a): its snapshots, DLQ rows and queue items carry that id, and so does
 * the consumer option {@code flow}; the deploy also sets {@code maxBodyBytes}, the flow's limit. Every message carries the
 * versions {@code package=<name>@<version>} and, when the deploy names one, {@code revision}.
 *
 * <p>{@link #close} refuses deploys from then on, waits for a running one, then stops every flow at once: its worker first,
 * then its delivery (within the node's stop deadline) and its consumer together; it returns when all have stopped and
 * rethrows the first failure, in flow order. Worker items still running are not awaited (P1-09e): their lease runs them again.
 *
 * <p>The {@link Runtime} only goes into each deploy's {@code new FlowBuilder}: nothing here reads it, and no flow or lambda
 * captures it (CLAUDE.md); the components and the clock come in on their own.
 *
 * @since 0.1
 */
public final class Deployments {

    private static final System.Logger LOG = System.getLogger(Deployments.class.getName());
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern REVISION = Pattern.compile("[0-9A-Za-z._-]{1,64}");
    private static final String AT_LEAST_ONCE = "at-least-once";
    /** The consumer options the deploy sets on every in endpoint (D-059): a package that sets one itself is refused. */
    private static final List<String> RESERVED = List.of("flow", "maxBodyBytes");

    private final NodeConfig config;
    private final Runtime runtime;
    private final Map<String, Component> components;
    private final Clock clock;
    private final List<StepFactory> steps;
    private final List<Expressions> languages;
    private final MessageStore messages;
    private final Dlq dlq;
    private final WorkQueue queue;
    private final Meter meter;
    /** One deploy at a time; {@link #close} takes it to wait for the one running. */
    private final ReentrantLock deploying = new ReentrantLock();
    /** By name, so {@link #packages()} is sorted; written under {@link #deploying}, read by anyone. */
    private final Map<String, Deployed> deployed = new ConcurrentSkipListMap<>();
    private volatile boolean closing;

    /**
     * @param runtime handed to each deploy's {@code new FlowBuilder} and read nowhere else
     * @param components the node's components by scheme: the consumers, and the config schemas the endpoints are checked against
     * @param clock {@code deployedAt}
     */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.components = Map.copyOf(components);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.steps = List.copyOf(steps);
        this.languages = List.copyOf(languages);
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.meter = Objects.requireNonNull(meter, "meter");
    }

    /** One flow as the deploy built it; {@code worker} is null unless the flow is at-least-once. */
    private record Flow(DeployedFlow flow, Delivery delivery, FlowWorker worker, Consumer consumer) {}

    private record Deployed(DeployedPackage pkg, List<Flow> flows) {}

    /** What the checks hand the build: each valid endpoint's scheme and config (environment laid over), each valid flow's definition. */
    private record Checked(Map<String, String> schemes, Map<String, JsonObject> configs, Map<String, FlowDefinition> flows) {}

    /** {@code FLOTTILA_MAX_PACKAGE_BYTES}: the largest archive the admin API takes; it may inflate to four times that. */
    public long maxPackageBytes() {
        return config.maxPackageBytes();
    }

    /** {@code <dataDir>/.incoming}: uploads and unpacks land here, on the file system of the packages, so the move is atomic. */
    public Path incomingDir() {
        return config.dataDir().resolve(".incoming");
    }

    /** Every flow running, sorted by its qualified id. */
    public List<DeployedFlow> flows() {
        return deployed.values().stream().flatMap(d -> d.flows().stream()).map(Flow::flow).sorted(Comparator.comparing(DeployedFlow::id)).toList();
    }

    /** Every package deployed, sorted by name. */
    public List<DeployedPackage> packages() {
        return deployed.values().stream().map(Deployed::pkg).toList();
    }

    /**
     * Deploys the package archive {@code archive} as {@code name}; see the class comment for the order. Blocking: on a virtual thread.
     *
     * @throws DeployRefusedException what the admin API answers with its own status
     * @throws IOException the data directory cannot be written or the archive read: the node's failure, not the package's
     */
    public DeployedPackage deploy(String name, Optional<String> revision, Path archive) throws DeployRefusedException, IOException {
        Objects.requireNonNull(archive, "archive");
        if (!NAME.matcher(name).matches()) {
            throw refused(Kind.BAD_REQUEST, "the package name '" + name + "' must be a lower-case letter, then at most 63 lower-case letters, digits and dashes");
        }
        if (revision.isPresent() && !REVISION.matcher(revision.get()).matches()) {
            throw refused(Kind.BAD_REQUEST, "the revision '" + revision.get() + "' must be 1 to 64 letters, digits, dots, underscores and dashes");
        }
        if (config.deployed()) throw refused(Kind.FORBIDDEN, "manual deploy is non-production only (ARCHITECTURE §9)");
        // closing is read where the lock decides: a close that holds the lock (stopping) or took it before this deploy (closed)
        // has set it first, so no deploy starts a flow after close stopped them all
        if (!deploying.tryLock()) throw closing ? stopping() : refused(Kind.CONFLICT, "another deploy is running: try again once it is done");
        try {
            if (closing) throw stopping();
            Deployed running = deployed.get(name);
            if (running != null) {
                throw refused(Kind.CONFLICT, "package '" + name + "' is deployed already (version " + running.pkg().version() + "); redeploy comes with P1-09d2");
            }
            return install(name, revision, archive);
        } finally {
            deploying.unlock();
        }
    }

    /**
     * Refuses deploys from now on, waits for a running one, then stops every flow at once (see the class comment) and returns
     * once all have stopped; the first failure, in flow order, is rethrown then. Blocking: on a virtual thread.
     */
    public void close() {
        closing = true;
        deploying.lock();
        try {
            List<Flow> running = deployed.values().stream().flatMap(d -> d.flows().stream()).toList();
            deployed.clear();
            stop(running, config.stopDeadline()).await();
        } finally {
            deploying.unlock();
        }
    }

    // --- one deploy, under the lock

    /** Unpacks, checks, builds and starts; whatever refuses it stops what it built and deletes what it wrote, unchecked failures included. */
    private DeployedPackage install(String name, Optional<String> revision, Path archive) throws DeployRefusedException, IOException {
        String sha256 = sha256(archive);
        Path incoming = incomingDir();
        Path dir = config.dataDir().resolve("packages").resolve(name).resolve(sha256);
        Files.createDirectories(incoming);
        Files.createDirectories(dir.getParent());
        boolean installed = false;
        try {
            Path unpacked = Files.createTempDirectory(incoming, "unpack-");
            List<Flow> flows = List.of();
            try {
                unpack(archive, unpacked);
                deleteTree(dir);   // what an earlier node left at <name>/<sha256> is replaced, never merged
                Files.move(unpacked, dir, StandardCopyOption.ATOMIC_MOVE);
                Package pkg = load(name, dir);
                Checked checked = check(name, pkg);
                String version = pkg.manifest().getString("version");
                flows = build(name, revision, version, pkg, dir, checked);
                DeployedPackage deployedPackage = new DeployedPackage(name, version, revision, sha256, flows.stream().map(f -> f.flow().id()).sorted().toList(),
                        clock.instant(), files(dir));
                start(flows);
                deployed.put(name, new Deployed(deployedPackage, flows));
                installed = true;
                return deployedPackage;
            } finally {
                if (!installed) {
                    stopQuietly(flows);
                    deleteTree(unpacked);
                    deleteTree(dir);
                }
            }
        } finally {
            if (!installed) deleteIfEmpty(dir.getParent());   // packages/<name>/ goes with a refused first deploy; an earlier node's leftovers stay
        }
    }

    private void unpack(Path archive, Path into) throws DeployRefusedException, IOException {
        try {
            PackageArchive.unpack(archive, into, config.maxPackageBytes());
        } catch (IllegalArgumentException refused) {
            throw refused(Kind.BAD_REQUEST, "the archive is refused: " + refused.getMessage());
        }
    }

    /** The package as unpacked; a file that does not parse stops the load, and the 422 names it (package-relative). */
    private static Package load(String name, Path dir) throws DeployRefusedException, IOException {
        try {
            return Package.load(dir);
        } catch (IllegalArgumentException unreadable) {
            throw new DeployRefusedException(Kind.INVALID, "package '" + name + "' cannot be read", List.of(unreadable.getMessage()));
        }
    }

    /** Every check that needs no component built; all problems at once, as one {@link Kind#INVALID}. */
    private Checked check(String name, Package pkg) throws DeployRefusedException {
        List<String> problems = new ArrayList<>(pkg.problems());
        JsonObject manifest = pkg.manifest();
        if (!name.equals(manifest.getValue("name"))) problems.add("package.yaml names the package '" + manifest.getValue("name") + "', the deploy '" + name + "'");
        if (manifest.getValue("dependencies") instanceof JsonArray dependencies && !dependencies.isEmpty()) {
            problems.add("package.yaml: dependencies " + dependencies.encode() + " are refused until package.lock exists (P2-02b)");
        }
        Map<String, String> schemes = new LinkedHashMap<>();
        Map<String, JsonObject> configs = new LinkedHashMap<>();
        Set<String> outs = new HashSet<>();
        pkg.endpoints().forEach((id, endpoint) -> {
            if (!Schemas.ENDPOINT.validate(endpoint).valid()) return;   // what its schema says is listed already; nothing here relies on its shape
            String scheme = endpoint.getString("scheme");
            JsonObject cfg = overlaid(endpoint);
            schemes.put(id, scheme);
            configs.put(id, cfg);
            Component component = components.get(scheme);
            if (component == null) {
                problems.add("endpoint '" + id + "': no component on this node serves the scheme '" + scheme + "'");
            } else {
                for (Schemas.Problem p : Schemas.check(component.configSchema(), cfg).problems()) {
                    problems.add("endpoint '" + id + "' config (environment " + config.env() + ") at '" + p.path() + "': " + p.message());
                }
            }
            if ("in".equals(endpoint.getString("direction"))) {
                for (String key : RESERVED) {
                    if (cfg.containsKey(key)) problems.add("endpoint '" + id + "' sets '" + key + "': the deploy sets it on every in endpoint (the flow's qualified id, its maxBodyBytes)");
                }
            } else {
                outs.add(id);   // the schema allows in and out only
            }
        });
        Map<String, FlowDefinition> flows = new LinkedHashMap<>();
        Map<String, List<String>> feeds = new LinkedHashMap<>();
        pkg.flows().forEach((id, flow) -> {
            if (!Schemas.FLOW.validate(flow).valid()) return;
            FlowDefinition def;
            try {
                def = FlowDefinition.from(map(flow.put("id", name + "/" + id)));
            } catch (RuntimeException refused) {
                problems.add("flow '" + id + "': " + reason(refused));
                return;
            }
            flows.put(id, def);
            feeds.computeIfAbsent(def.fromEndpoint(), endpoint -> new ArrayList<>()).add(id);
            if (outs.contains(def.fromEndpoint())) {
                problems.add("flow '" + id + "' consumes from endpoint '" + def.fromEndpoint() + "', whose direction is out: a flow's from is an in endpoint");
            }
            long durableMax = config.blobs().durableMax();
            if (AT_LEAST_ONCE.equals(def.deliveryMode()) && def.maxBodyBytes() > durableMax) {
                problems.add("flow '" + id + "' is at-least-once with limits.maxBodyBytes " + def.maxBodyBytes() + " above FLOTTILA_BLOB_DURABLE_MAX " + durableMax + " (D-058)");
            }
        });
        feeds.forEach((endpoint, fed) -> {
            if (fed.size() > 1) problems.add("endpoint '" + endpoint + "' is the from endpoint of the flows " + fed + ": an in endpoint feeds one flow");
        });
        if (!problems.isEmpty()) throw invalid(name, problems);
        return new Checked(schemes, configs, flows);
    }

    /** The endpoint's {@code config} with {@code environments.<env>.config} laid over it: a key the environment names is replaced whole, never merged. */
    private JsonObject overlaid(JsonObject endpoint) {
        JsonObject cfg = endpoint.getJsonObject("config");
        JsonObject environment = endpoint.getJsonObject("environments", new JsonObject()).getJsonObject(config.env(), new JsonObject());
        environment.getJsonObject("config", new JsonObject()).forEach(key -> cfg.put(key.getKey(), key.getValue()));
        return cfg;
    }

    /** Every flow built — delivery, worker, consumer — over one builder that carries the package's versions; every failure as one problem. */
    private List<Flow> build(String name, Optional<String> revision, String version, Package pkg, Path dir, Checked checked) throws DeployRefusedException {
        Map<String, String> versions = new HashMap<>();
        versions.put("package", name + "@" + version);
        revision.ifPresent(r -> versions.put("revision", r));
        Map<String, EndpointConfig> endpoints = new LinkedHashMap<>();
        checked.configs().forEach((id, cfg) -> endpoints.put(id, endpoint(id, checked.schemes().get(id), cfg)));
        FlowBuilder builder = new FlowBuilder(runtime, steps, languages, endpoints).recording(messages, meter).dlq(dlq).queue(queue).versions(versions);
        List<Flow> flows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        checked.flows().forEach((id, def) -> {
            try {
                flows.add(flow(builder, name, version, revision, def, pkg.flowFile(id).getParent(), endpoints.get(def.fromEndpoint())));
            } catch (RuntimeException refused) {
                // a step's failure may name a package file by its absolute path: a problem line names it from the package root
                problems.add("flow '" + id + "' does not build: " + reason(refused).replace(dir + File.separator, ""));
            }
        });
        if (!problems.isEmpty()) throw invalid(name, problems);
        return List.copyOf(flows);
    }

    private Flow flow(FlowBuilder builder, String name, String version, Optional<String> revision, FlowDefinition def, Path baseDir, EndpointConfig from) {
        Delivery delivery = builder.delivery(def, baseDir);
        FlowWorker worker = AT_LEAST_ONCE.equals(def.deliveryMode()) ? builder.worker(def, baseDir, config.nodeId()) : null;
        Function<Envelope, Future<Ack>> handler = worker == null ? delivery : worker.pollingAfterAccept(delivery);
        Map<String, Object> options = new HashMap<>(from.options());
        options.put("flow", def.id());
        options.put("maxBodyBytes", def.maxBodyBytes());
        Consumer consumer = components.get(from.scheme()).consumer(new EndpointConfig(from.name(), from.uri(), options), handler);
        return new Flow(new DeployedFlow(def.id(), name, version, revision, def.version(), def.deliveryMode()), delivery, worker, consumer);
    }

    /** One consumer after the other, an at-least-once flow's worker right after its consumer; the first that does not start refuses the deploy. */
    private static void start(List<Flow> flows) throws DeployRefusedException {
        for (Flow f : flows) {
            try {
                f.consumer().start().await();
            } catch (Exception failed) {   // a failed start, one that throws, or a checked failure the await throws sneakily
                throw refused(Kind.FAILED, "flow '" + f.flow().id() + "' did not start: " + reason(failed));
            }
            if (f.worker() != null) f.worker().start();
        }
    }

    /**
     * Stops {@code flows} at once: each one's worker first (no round starts while its consumer stops), then its delivery with
     * {@code deadline} and its consumer together. Completes when every one has stopped, failed with the first failure in flow order.
     */
    private static Future<Void> stop(List<Flow> flows, Duration deadline) {
        List<Future<?>> stopped = new ArrayList<>(flows.size());
        for (Flow f : flows) {
            if (f.worker() != null) f.worker().stop();
            stopped.add(Future.join(f.delivery().stop(deadline), Future.<Void>future(done -> f.consumer().stop().onComplete(done))));
        }
        return Future.join(stopped).mapEmpty();
    }

    /** Stops what a refused deploy built, cancelling what is in flight at once; a failure to stop is logged: the refusal stays what is answered. */
    private static void stopQuietly(List<Flow> flows) {
        stop(flows, Duration.ZERO).onFailure(failed -> LOG.log(System.Logger.Level.WARNING, "a flow of a refused deploy did not stop: " + failed, failed))
                .otherwiseEmpty().await();
    }

    // --- helpers

    private static DeployRefusedException refused(Kind kind, String error) {
        return new DeployRefusedException(kind, error, List.of());
    }

    private static DeployRefusedException stopping() {
        return refused(Kind.STOPPING, "the node is closing: it takes no deploy");
    }

    private static DeployRefusedException invalid(String name, List<String> problems) {
        return new DeployRefusedException(Kind.INVALID, "package '" + name + "' is invalid: " + problems.size() + " problem(s)", problems);
    }

    private static String reason(Throwable failure) {
        return Objects.toString(failure.getMessage(), failure.toString());
    }

    /** An endpoint as a component gets it: {@code <scheme>://<id>}, its options as plain maps and lists; a null option is an absent one. */
    private static EndpointConfig endpoint(String id, String scheme, JsonObject cfg) {
        Map<String, Object> options = map(cfg);
        options.values().removeIf(Objects::isNull);
        return new EndpointConfig(id, URI.create(scheme + "://" + id), options);
    }

    /** A document as core and the components read it: nested objects as maps, arrays as lists (FlowDefinition reads no JsonObject). */
    private static Map<String, Object> map(JsonObject document) {
        Map<String, Object> map = new LinkedHashMap<>();
        document.forEach(e -> map.put(e.getKey(), plain(e.getValue())));
        return map;
    }

    private static Object plain(Object value) {
        if (value instanceof JsonObject o) return map(o);
        if (value instanceof JsonArray a) return a.stream().map(Deployments::plain).toList();
        return value;
    }

    /** Every regular file below {@code dir}, by its {@code /}-separated path from it, with its SHA-256. */
    private static List<DeployedPackage.PackageFile> files(Path dir) throws IOException {
        List<DeployedPackage.PackageFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.add(new DeployedPackage.PackageFile(dir.relativize(p).toString().replace(File.separatorChar, '/'), sha256(p)));
            }
        }
        return files;
    }

    /** The SHA-256 of the file's bytes, lower-case hex. */
    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException mandatory) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", mandatory);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Deletes the directory {@code dir} when nothing is in it. */
    private static void deleteIfEmpty(Path dir) throws IOException {
        boolean empty;
        try (Stream<Path> left = Files.list(dir)) {
            empty = left.findAny().isEmpty();
        }
        if (empty) Files.delete(dir);
    }

    /** Deletes {@code root} with everything below it; nothing when it does not exist. */
    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
