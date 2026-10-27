package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.schemas.Schemas;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09d1b (D-059, D-060 a/b): {@code Deployments} unpacks a package archive into {@code <dataDir>/packages/<name>/<sha256>/},
 * validates the whole package — every problem in one {@link Kind#INVALID} refusal, nothing started before every check
 * passed — builds and starts every flow under its qualified id {@code <package>/<flow>}, lists the flows and packages, and
 * stops them on close. Everything runs in memory: the {@link Doors} component on every endpoint (its producers a
 * {@code RecordingComponent}), in-memory message store, DLQ and work queue, and a fake clock that drives every timer —
 * worker ticks and stop deadlines — so nothing here waits on wall time.
 *
 * <p>The shape these tests assume (P1-09d1b test-author): in {@code io.flottila.runtime.deploy}, {@code Deployments(NodeConfig,
 * Runtime, Map<String, Component>, Clock, List<StepFactory>, List<Expressions>, MessageStore, Dlq, WorkQueue, Meter)} with {@code deploy(String
 * name, Optional<String> revision, Path archive)} (its {@code DeployResult.pkg()} the {@code DeployedPackage} deployed since P1-09d2b, which made
 * a deploy of a running package a redeploy: {@code DeploymentsRedeployTest}), {@code List<DeployedFlow> flows()}, {@code List<DeployedPackage> packages()},
 * {@code long maxPackageBytes()}, {@code Path incomingDir()}, {@code void close()}; {@code DeployRefusedException} with
 * {@code kind()}, {@code error()}, {@code problems()} and the nested enum {@code Kind}; record accessors {@code DeployedPackage}
 * {@code name(), version(), revision(), archiveSha256()} (lower-case hex, no prefix){@code , deployedAt(), flows(), files()} with
 * files' {@code path(), sha256()}; {@code DeployedFlow} {@code id(), pkg(), packageVersion(), revision(), flowVersion(), delivery()}.
 */
class DeploymentsTest {

    static final String NODE = "node-1";
    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);
    static final long DURABLE_MAX = 4096;
    static final long MAX_PACKAGE = 2L * 1024 * 1024;
    static final Duration BOUND = Duration.ofSeconds(10);

    @TempDir Path tmp;
    Path data;
    Path archives;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    TracingTimer timer;
    CountingQueue queue;
    NodeConfig config;
    Deployments deployments;

    @BeforeEach
    void wire() {
        data = tmp.resolve("data");
        archives = tmp.resolve("archives");
        timer = new TracingTimer(rt.timer());
        queue = new CountingQueue(rt.clock());
        config = NodeConfig.from(env("dev"));
        deployments = deployments(config);
    }

    @AfterEach
    void releaseWhatACaseHeld() {
        for (CountDownLatch l : holds) l.countDown();
        doors.releaseAll();
    }

    // --- fixture

    Map<String, String> env(String flottilaEnv) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", flottilaEnv);
        env.put("FLOTTILA_NODE_ID", NODE);
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_STOP_DEADLINE", STOP_DEADLINE.toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", Long.toString(DURABLE_MAX));
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(MAX_PACKAGE));
        return env;
    }

    /** The node's runtime: the fake clock, the tracing timer over the fake one, the fake ids and blob store, {@link Doors} as the only component. */
    Runtime runtime() {
        return new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
    }

    Deployments deployments(NodeConfig c) {
        return new Deployments(c, runtime(), Map.of(Doors.SCHEME, doors), rt.clock(), List.of(), List.of(), messages, dlq, queue, () -> 0L);
    }

    /**
     * {@code name} 1.0.0: {@code orders} (sync, 2.1.0, max 4096 B), {@code audit} (at-least-once, 1.0.3, max 1024 B) and {@code ping}
     * (at-most-once, 0.0.1, max 2048 B), each from its own {@code in} endpoint and each sending to the {@code out} endpoint {@code partner}.
     */
    static PackageDraft shop(String name) {
        return PackageDraft.of(name, "1.0.0")
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders"))
                .endpoint("audit-in", "in", new JsonObject().put("path", "/audit"))
                .endpoint("ping-in", "in", new JsonObject().put("path", "/ping"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("orders", "2.1.0", "sync", "orders-in", 4096, PackageDraft.toPartner())
                .flow("audit", "1.0.3", "at-least-once", "audit-in", 1024, PackageDraft.toPartner())
                .flow("ping", "0.0.1", "at-most-once", "ping-in", 2048, PackageDraft.toPartner());
    }

    /** {@code name} 1.0.0 with one in endpoint and one flow per entry of {@code flows} (flow id → delivery mode), each to {@code partner}. */
    static PackageDraft flows(String name, Map<String, String> flows) {
        PackageDraft p = PackageDraft.of(name, "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        flows.forEach((id, mode) -> p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", mode, id + "-in", 1024, PackageDraft.toPartner()));
        return p;
    }

    /** The package a deploy answers (P1-09d2b: {@code DeployResult.pkg()}). */
    DeployedPackage deploy(String name, Optional<String> revision, Path archive) throws Exception {
        return OnVirtualThread.call(() -> deployments.deploy(name, revision, archive)).pkg();
    }

    DeployedPackage deploy(PackageDraft p) throws Exception {
        return deploy(p.manifest().getString("name"), Optional.empty(), p.zipTo(archives));
    }

    DeployRefusedException refused(Deployments d, String name, Optional<String> revision, Path archive) {
        return assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> d.deploy(name, revision, archive)),
                "the deploy of " + name + " is refused");
    }

    DeployRefusedException refused(String name, Optional<String> revision, Path archive) {
        return refused(deployments, name, revision, archive);
    }

    /** A message sent through a consumer, and its answer. */
    record Sent(Envelope e, Future<Ack> answer) {
        String id() { return e.id(); }
    }

    Envelope envelope(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
    }

    /** A message delivered through the consumer the deploy built with the flow option {@code flow}, as that consumer would. */
    Sent send(String flow) {
        Envelope e = envelope(flow);
        return new Sent(e, doors.door(flow).deliver(e));
    }

    /** The next send to the partner is held until the case ends or counts the latch down. */
    CountDownLatch hold() {
        CountDownLatch l = doors.partner().holdNext(1);
        holds.add(l);
        return l;
    }

    static <T> T within(Future<T> f, String what) throws Exception {
        try {
            return f.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stuck) {
            return fail(what + ": not within " + BOUND);
        }
    }

    static void reached(CountDownLatch latch, String what, OnVirtualThread.Call<?> call) throws InterruptedException {
        if (!latch.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
            fail(what + ": not within " + BOUND + (call == null ? "" : "; the call sits at " + Arrays.toString(call.thread().getStackTrace())));
        }
    }

    /** The refusal {@code call} ends with, within {@link #BOUND}; anything else fails naming {@code what}. */
    static DeployRefusedException joinRefused(OnVirtualThread.Call<?> call, String what) throws Exception {
        try {
            Object ok = call.outcome().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
            return fail(what + ": the deploy was not refused, it answered " + ok);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DeployRefusedException r) return r;
            throw new AssertionError(what + ": the deploy failed with something else than a refusal", e.getCause());
        } catch (TimeoutException stillRunning) {
            return fail(what + ": still running after " + BOUND + ", at " + Arrays.toString(call.thread().getStackTrace()));
        }
    }

    /** Spins (never sleeps) until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /** Every regular file below {@code dir}, relative, {@code /}-separated; empty when {@code dir} does not exist. */
    static List<String> regularFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    /** The entries directly in {@code dir}; empty when it does not exist. */
    static List<String> children(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** A listed flow as one line: {@code <id> <pkg>@<packageVersion> rev=<revision|-> flow@<flowVersion> <delivery>}. */
    static Set<String> rows(List<DeployedFlow> flows) {
        return flows.stream().map(f -> f.id() + " " + f.pkg() + "@" + f.packageVersion() + " rev=" + f.revision().orElse("-") + " flow@" + f.flowVersion() + " " + f.delivery())
                .collect(Collectors.toSet());
    }

    static List<String> sorted(List<String> ids) {
        return ids.stream().sorted().toList();
    }

    static Map<String, String> files(DeployedPackage p) {
        Map<String, String> files = new TreeMap<>();
        p.files().forEach(f -> files.put(f.path(), f.sha256()));
        return files;
    }

    /** A config value that is an object, whichever map type carries it. */
    @SuppressWarnings("unchecked")
    static JsonObject json(Object value) {
        if (value instanceof JsonObject o) return o;
        if (value instanceof Map<?, ?> m) return new JsonObject((Map<String, Object>) m);
        return fail("an object, was " + value);
    }

    List<String> startEvents() {
        return doors.events().stream().filter(e -> e.startsWith("start ")).toList();
    }

    // --- a first deploy

    @Test
    void aFirstDeployStartsEveryFlowAndListsIt() throws Exception {
        PackageDraft shop = shop("shop");
        byte[] zip = shop.zip();
        assertEquals(MAX_PACKAGE, deployments.maxPackageBytes(), "the package limit is the node's FLOTTILA_MAX_PACKAGE_BYTES");
        assertTrue(deployments.incomingDir().startsWith(data) && deployments.incomingDir().getFileName().toString().equals(".incoming"),
                "uploads and unpacks land in .incoming under the data directory (one file system: the move is atomic): " + deployments.incomingDir());

        DeployedPackage p = deploy("shop", Optional.empty(), PackageDraft.write(archives, zip));

        assertEquals("shop", p.name());
        assertEquals("1.0.0", p.version());
        assertEquals(Optional.empty(), p.revision());
        assertEquals(PackageDraft.sha256(zip), p.archiveSha256(), "the archive's SHA-256 as lower-case hex");
        assertEquals(List.of("shop/audit", "shop/orders", "shop/ping"), sorted(p.flows()), "every flow, by its qualified id");
        assertEquals(Set.of("shop/audit shop@1.0.0 rev=- flow@1.0.3 at-least-once", "shop/orders shop@1.0.0 rev=- flow@2.1.0 sync",
                "shop/ping shop@1.0.0 rev=- flow@0.0.1 at-most-once"), rows(deployments.flows()), "flows() lists every flow of the package");
        assertEquals(List.of("shop"), deployments.packages().stream().map(DeployedPackage::name).toList(), "packages() lists the package");

        Path dir = data.resolve("packages").resolve("shop").resolve(p.archiveSha256());
        for (Map.Entry<String, byte[]> f : shop.files().entrySet()) {
            assertArrayEquals(f.getValue(), Files.readAllBytes(dir.resolve(f.getKey())), "<dataDir>/packages/<name>/<sha256>/" + f.getKey());
        }
        assertEquals(List.of(), children(deployments.incomingDir()), "nothing is left in .incoming: the unpacked directory was moved");
        for (String flow : List.of("shop/orders", "shop/audit", "shop/ping")) assertTrue(doors.door(flow).started(), flow + "'s consumer started: " + doors.events());

        Sent sync = send("shop/orders");
        Ack reply = within(sync.answer(), "the sync answer");
        assertEquals(sync.id(), assertInstanceOf(Ack.Reply.class, reply, "a sync flow answers with its reply").reply().id());
        Sent alo = send("shop/audit");
        assertEquals(new Ack.Accepted(alo.id()), within(alo.answer(), "the at-least-once answer"), "an at-least-once flow accepts");
        Sent amo = send("shop/ping");
        assertInstanceOf(Ack.Accepted.class, within(amo.answer(), "the at-most-once answer"), "an at-most-once flow accepts");
        doors.partner().awaitSent(3);
        assertEquals(Set.of(sync.id(), alo.id(), amo.id()), doors.partner().sentTo("partner").stream().map(Envelope::id).collect(Collectors.toSet()),
                "every flow ran to its partner, the at-least-once one on its worker");
        assertEquals(List.of("shop/audit"), queue.enqueued(), "the at-least-once message was queued under the qualified flow id");

        int before = queue.claims().size();
        rt.clock().advance(Duration.ofHours(1));
        assertTrue(queue.claims().subList(before, queue.claims().size()).contains(NODE + " shop/audit"),
                "the at-least-once worker was started: it polls on its timer, as worker " + NODE + " (the node id): " + queue.claims());
    }

    @Test
    void theConsumerGetsTheFlowIdAndTheFlowsMaxBody() throws Exception {
        deploy(shop("shop"));

        Map<String, Long> maxBody = Map.of("shop/orders", 4096L, "shop/audit", 1024L, "shop/ping", 2048L);
        Map<String, String> path = Map.of("shop/orders", "/orders", "shop/audit", "/audit", "shop/ping", "/ping");
        for (String flow : maxBody.keySet()) {
            Map<String, Object> options = doors.door(flow).cfg().options();
            assertEquals(flow, options.get("flow"), "the consumer option flow is the qualified flow id: " + options);
            assertInstanceOf(Number.class, options.get("maxBodyBytes"), "the consumer option maxBodyBytes is a number: " + options);
            assertEquals(maxBody.get(flow), ((Number) options.get("maxBodyBytes")).longValue(), "the consumer option maxBodyBytes is the flow's limit: " + options);
            assertEquals(path.get(flow), options.get("path"), "the endpoint's own config stays beside them: " + options);
        }
    }

    @Test
    void theEnvironmentOverlayReplacesConfigKeys() throws Exception {
        PackageDraft p = shop("shop");
        JsonObject inBase = new JsonObject().put("path", "/orders").put("level", "wrong").put("tags", new JsonObject().put("a", "x").put("b", "y"));
        p.endpointDoc("orders-in").put("config", inBase).put("environments", new JsonObject()
                .put("dev", new JsonObject().put("config", new JsonObject().put("level", "high").put("tags", new JsonObject().put("c", "z"))))
                .put("prod", new JsonObject().put("config", new JsonObject().put("path", "/prod").put("level", "low"))));
        p.endpointDoc("partner").put("config", new JsonObject().put("url", "rec://partner-base").put("tags", new JsonObject().put("a", "x"))).put("environments", new JsonObject()
                .put("dev", new JsonObject().put("config", new JsonObject().put("url", "rec://partner-dev")))
                .put("prod", new JsonObject().put("config", new JsonObject().put("url", "rec://partner-prod"))));
        assertFalse(Schemas.check(Doors.CONFIG_SCHEMA, inBase).valid(), "fixture: the base config alone fails the component's schema (level: wrong)");

        deploy(p);   // the node is dev: the component's schema judges the config with dev's keys laid over it

        Map<String, Object> in = doors.door("shop/orders").cfg().options();
        assertEquals("high", in.get("level"), "dev's overlay replaced the key: " + in);
        assertEquals(new JsonObject().put("c", "z"), json(in.get("tags")), "an overlaid key is replaced as a whole, never merged: " + in);
        assertEquals("/orders", in.get("path"), "a key the overlay does not name stays; prod's overlay is not dev's: " + in);
        List<io.flottila.api.EndpointConfig> partner = doors.producerConfigs("partner");
        assertFalse(partner.isEmpty(), "fixture: the flows' producers were built");
        for (io.flottila.api.EndpointConfig c : partner) {
            assertEquals("rec://partner-dev", c.options().get("url"), "an out endpoint is overlaid too: " + c.options());
            assertEquals(new JsonObject().put("a", "x"), json(c.options().get("tags")), "and keeps the keys the overlay does not name: " + c.options());
        }
    }

    // --- validation

    static Arguments rule(String name, Consumer<PackageDraft> breaks, List<List<String>> expected) {
        return Arguments.of(name, breaks, expected);
    }

    /** Each rule of the validation, broken alone; then every rule that leaves the documents readable, broken together. */
    static Stream<Arguments> invalidPackages() {
        Map<String, Consumer<PackageDraft>> one = new LinkedHashMap<>();
        Map<String, List<String>> words = new LinkedHashMap<>();
        one.put("a document its schema refuses", p -> p.endpoint("spare", "sideways", new JsonObject()));
        words.put("a document its schema refuses", List.of("endpoints/spare.yaml at '/direction'"));
        one.put("the manifest names another package", p -> p.manifest().put("name", "store"));
        words.put("the manifest names another package", List.of("store", "shop"));
        one.put("dependencies", p -> p.manifest().put("dependencies", new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.0.0"))));
        words.put("dependencies", List.of("dependencies"));
        one.put("a scheme no component on the node serves", p -> p.endpoint("archive", "sftp", "out", new JsonObject().put("url", "sftp://archive")));
        words.put("a scheme no component on the node serves", List.of("archive", "sftp"));
        one.put("a config the component's schema refuses once the environment's keys are laid over it", p -> p.endpointDoc("audit-in")
                .put("environments", new JsonObject().put("dev", new JsonObject().put("config", new JsonObject().put("level", "wrong")))));
        words.put("a config the component's schema refuses once the environment's keys are laid over it", List.of("audit-in", "level"));
        one.put("an in endpoint that sets flow", p -> p.endpointDoc("orders-in").getJsonObject("config").put("flow", "mine"));
        words.put("an in endpoint that sets flow", List.of("orders-in", "flow"));
        one.put("an in endpoint that sets maxBodyBytes", p -> p.endpointDoc("ping-in").getJsonObject("config").put("maxBodyBytes", 10));
        words.put("an in endpoint that sets maxBodyBytes", List.of("ping-in", "maxBodyBytes"));
        one.put("one from endpoint for two flows", p -> p.flow("refunds", "1.0.0", "sync", "orders-in", 1024, PackageDraft.toPartner()));
        words.put("one from endpoint for two flows", List.of("orders-in", "orders", "refunds"));
        one.put("a flow FlowDefinition refuses", p -> p.flowDoc("ping").getJsonObject("limits").put("maxInFlightBytes", 1024));
        words.put("a flow FlowDefinition refuses", List.of("ping", "maxInFlightBytes"));
        one.put("an at-least-once flow above the durable bound", p -> p.flowDoc("audit").getJsonObject("limits").put("maxBodyBytes", DURABLE_MAX + 1));
        words.put("an at-least-once flow above the durable bound", List.of("audit", Long.toString(DURABLE_MAX)));

        Map<String, Consumer<PackageDraft>> build = new LinkedHashMap<>();
        build.put("a sync flow whose pipeline does not build", p -> p.flowDoc("orders").put("steps", new JsonArray().add(new JsonObject().put("type", "frobnicate").put("name", "f"))));
        words.put("a sync flow whose pipeline does not build", List.of("orders", "frobnicate"));
        build.put("an at-least-once flow whose worker does not build", p -> p.flowDoc("audit").put("steps", new JsonArray().add(new JsonObject().put("type", "stamp").put("name", "s"))));
        words.put("an at-least-once flow whose worker does not build", List.of("audit", "stamp"));
        build.put("a consumer its component refuses to build", p -> p.endpointDoc("ping-in").getJsonObject("config").put("refuse", "no door today"));
        words.put("a consumer its component refuses to build", List.of("ping", "no door today"));

        List<Arguments> rows = new ArrayList<>();
        Stream.of(one, build).forEach(rules -> rules.forEach((name, breaks) -> rows.add(rule(name, breaks, List.of(words.get(name))))));
        rows.add(rule("a file that does not parse (load stops at it)", p -> p.file("flows/broken.yaml", "id: [unclosed\n"), List.of(List.of("flows/broken.yaml"))));
        for (Map<String, Consumer<PackageDraft>> rules : List.of(one, build)) {
            List<List<String>> all = rules.keySet().stream().map(words::get).toList();
            rows.add(rule("all at once: " + rules.keySet(), p -> rules.values().forEach(b -> b.accept(p)), all));
        }
        return rows.stream();
    }

    /**
     * One 422 carries every problem (a line per problem, each naming what is wrong), and nothing of the package starts, is listed or
     * stays on disk. A problem line never carries an absolute path. "All at once" rows break several rules in one package: each
     * shows up. The build rules (pipeline, worker, consumer) are broken together in a package whose documents are all valid.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPackages")
    void everyInvalidPackageIs422WithAllProblemsAndNothingStarts(String rule, Consumer<PackageDraft> breaks, List<List<String>> expected) throws Exception {
        PackageDraft p = shop("shop");
        breaks.accept(p);

        DeployRefusedException refused = refused("shop", Optional.empty(), p.zipTo(archives));

        String all = refused.error() + "\n  " + String.join("\n  ", refused.problems());
        assertEquals(Kind.INVALID, refused.kind(), rule + ": " + all);
        assertFalse(refused.error().isBlank(), "the 422 has an error line");
        for (List<String> problem : expected) {
            assertTrue(refused.problems().stream().anyMatch(l -> problem.stream().allMatch(l::contains)), rule + ": a problem line names " + problem + ":\n  " + all);
        }
        for (String l : refused.problems()) {
            assertFalse(l.contains(tmp.toString()) || l.contains(tmp.toRealPath().toString()), "a problem line names no absolute path: " + l);
        }
        assertEquals(List.of(), startEvents(), "no consumer of the invalid package was started: " + doors.events());
        assertEquals(List.of(), deployments.flows(), "nothing is listed");
        assertEquals(List.of(), deployments.packages(), "no package is listed");
        assertEquals(List.of(), regularFiles(data), "the unpacked package is deleted");
        rt.clock().advance(Duration.ofHours(1));
        assertEquals(List.of(), queue.claims(), "no worker of the invalid package polls");
    }

    /** A flow's {@code from} names the endpoint it consumes: one with direction {@code out} is a producer, and the package is refused. */
    @Test
    void aFromEndpointWithDirectionOutIs422() throws Exception {
        PackageDraft p = PackageDraft.of("shop", "1.0.0")
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("orders", "1.0.0", "sync", "orders-in", 1024, PackageDraft.toPartner())
                .flow("loop", "1.0.0", "sync", "partner", 1024, PackageDraft.toPartner());

        DeployRefusedException refused = refused("shop", Optional.empty(), p.zipTo(archives));

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("loop") && l.contains("partner")),
                "a problem names the flow and its out endpoint: " + refused.problems());
        assertEquals(List.of(), doors.events(), "no consumer was built, on the out endpoint or any other: " + doors.events());
        assertEquals(List.of(), deployments.flows());
        assertEquals(List.of(), regularFiles(data), "the unpacked package is deleted");
    }

    @Test
    void atLeastOnceAtTheDurableBoundDeploysOneAboveIs422() throws Exception {
        assertEquals(DURABLE_MAX, config.blobs().durableMax(), "fixture: FLOTTILA_BLOB_DURABLE_MAX");
        PackageDraft edge = flows("edge", Map.of("audit", "at-least-once", "orders", "sync"));
        edge.flowDoc("audit").getJsonObject("limits").put("maxBodyBytes", DURABLE_MAX);
        edge.flowDoc("orders").getJsonObject("limits").put("maxBodyBytes", 2 * DURABLE_MAX);   // the bound is the at-least-once flows' only

        DeployedPackage deployed = deploy(edge);

        assertEquals(List.of("edge/audit", "edge/orders"), sorted(deployed.flows()), "at the bound it deploys; a sync flow above it too");
        assertEquals(DURABLE_MAX, ((Number) doors.door("edge/audit").cfg().options().get("maxBodyBytes")).longValue());

        PackageDraft over = flows("over", Map.of("audit", "at-least-once"));
        over.flowDoc("audit").getJsonObject("limits").put("maxBodyBytes", DURABLE_MAX + 1);
        DeployRefusedException refused = refused("over", Optional.empty(), over.zipTo(archives));

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("audit") && l.contains(Long.toString(DURABLE_MAX))),
                "a problem names the flow and the bound FLOTTILA_BLOB_DURABLE_MAX: " + refused.problems());
        assertFalse(doors.events().stream().anyMatch(e -> e.startsWith("start over/")), "nothing of the refused package started: " + doors.events());
    }

    // --- refusals before the unpack

    @Test
    void aDeployInADeployedEnvironmentIs403() throws Exception {
        NodeConfig prod = NodeConfig.from(env("prod"));
        assertTrue(prod.deployed(), "fixture: prod is a deployed environment");
        Deployments d = deployments(prod);
        Path archive = shop("shop").zipTo(archives);

        DeployRefusedException refused = refused(d, "shop", Optional.empty(), archive);

        assertEquals(Kind.FORBIDDEN, refused.kind(), refused.error());
        assertEquals("manual deploy is non-production only (ARCHITECTURE §9)", refused.error());
        assertEquals(List.of(), refused.problems(), "a 403 is {error} only");
        assertEquals(Kind.FORBIDDEN, refused(d, "shop", Optional.empty(), archives.resolve("no-such.zip")).kind(),
                "refused before the archive is read: a path where no file is gives the same 403");
        assertEquals(Kind.BAD_REQUEST, refused(d, "Shop", Optional.empty(), archive).kind(), "the name is checked first (deploy order 1)");
        assertEquals(Kind.BAD_REQUEST, refused(d, "shop", Optional.of("not a revision"), archive).kind(), "and the revision");
        OnVirtualThread.call(() -> { d.close(); return null; });
        assertEquals(Kind.FORBIDDEN, refused(d, "shop", Optional.empty(), archive).kind(), "a closing node in a deployed environment answers 403 too (order 2 before 3)");
        assertEquals(List.of(), doors.events(), "no consumer was built");
        assertEquals(List.of(), regularFiles(data), "nothing was written");
    }

    // --- identity (D-060 a)

    @Test
    void twoPackagesWithTheSameFlowIdBothDeployAndAreListedQualified() throws Exception {
        PackageDraft shop = PackageDraft.of("shop", "1.0.0")
                .endpoint("in", "in", new JsonObject().put("path", "/shop")).endpoint("partner", "out", new JsonObject().put("url", "rec://shop-partner"))
                .flow("orders", "2.1.0", "sync", "in", 4096, PackageDraft.toPartner());
        PackageDraft store = PackageDraft.of("store", "3.0.0")
                .endpoint("in", "in", new JsonObject().put("path", "/store")).endpoint("partner", "out", new JsonObject().put("url", "rec://store-partner"))
                .flow("orders", "5.0.0", "sync", "in", 4096, PackageDraft.toPartner());

        DeployedPackage a = deploy(shop);
        DeployedPackage b = deploy(store);

        assertEquals(List.of("shop/orders"), a.flows());
        assertEquals(List.of("store/orders"), b.flows());
        assertEquals(Set.of("shop/orders shop@1.0.0 rev=- flow@2.1.0 sync", "store/orders store@3.0.0 rev=- flow@5.0.0 sync"), rows(deployments.flows()),
                "flow ids are unique per package only: both are listed, qualified");
        assertEquals(Set.of("shop", "store"), deployments.packages().stream().map(DeployedPackage::name).collect(Collectors.toSet()));
        assertEquals("/shop", doors.door("shop/orders").cfg().options().get("path"), "each package's consumer has its own endpoint's config");
        assertEquals("/store", doors.door("store/orders").cfg().options().get("path"));

        Map<String, String> version = Map.of("shop/orders", "2.1.0", "store/orders", "5.0.0");
        for (String flow : version.keySet()) {
            Sent m = send(flow);
            assertInstanceOf(Ack.Reply.class, within(m.answer(), flow + "'s answer"), flow + " runs");
            List<Snapshot> timeline = messages.timeline(m.id()).await();
            assertFalse(timeline.isEmpty(), "fixture: " + flow + " records its messages");
            for (Snapshot s : timeline) {
                assertEquals(flow, s.flow(), "a snapshot names the qualified flow");
                assertEquals(flow + "@" + version.get(flow), s.versions().get("flow"), "the flow version is the qualified id's: " + s.versions());
            }
        }
    }

    // --- one deploy at a time

    @Test
    void aDeployWhileOneRunsIs409() throws Exception {
        Doors.Gate gate = doors.holdStart(1);
        Path first = shop("shop").zipTo(archives);
        OnVirtualThread.Call<DeployResult> running = OnVirtualThread.start(() -> deployments.deploy("shop", Optional.empty(), first));
        reached(gate.reached(), "the first deploy starts its first consumer", running);

        Path second = shop("store").zipTo(archives);
        DeployRefusedException refused = joinRefused(OnVirtualThread.start(() -> deployments.deploy("store", Optional.empty(), second)),
                "a deploy while another runs answers at once (it does not wait for the lock)");

        assertEquals(Kind.CONFLICT, refused.kind(), refused.error());
        assertFalse(refused.error().isBlank());
        assertFalse(running.isDone(), "the first deploy still waits on its consumer's start");
        assertFalse(Files.exists(data.resolve("packages").resolve("store")), "the refused package was never unpacked");
        gate.release();
        assertEquals("shop", OnVirtualThread.join(running).pkg().name(), "the first deploy completes");
        assertEquals("store", deploy(shop("store")).name(), "the lock is free again once the first deploy is done");
    }

    /** Deploy order 4: a directory an earlier node left at {@code <name>/<sha256>} (nothing deployed here) is replaced by the unpack, not merged. */
    @Test
    void aLeftoverDirectoryOfTheSameArchiveIsReplaced() throws Exception {
        PackageDraft p = shop("shop");
        byte[] zip = p.zip();
        Path dir = data.resolve("packages").resolve("shop").resolve(PackageDraft.sha256(zip));
        Files.createDirectories(dir.resolve("flows"));
        Files.writeString(dir.resolve("stale.txt"), "left by an earlier node");
        Files.writeString(dir.resolve("package.yaml"), "not the archive's manifest");

        DeployedPackage deployed = deploy("shop", Optional.empty(), PackageDraft.write(archives, zip));

        assertEquals(List.of("shop/audit", "shop/orders", "shop/ping"), sorted(deployed.flows()));
        assertEquals(new ArrayList<>(p.files().keySet()), regularFiles(dir), "the directory holds exactly the archive's files: the leftover was replaced");
        assertArrayEquals(p.files().get("package.yaml"), Files.readAllBytes(dir.resolve("package.yaml")));
    }

    // --- a refusal cleans up

    /** After a refusal at any stage past the name checks no file of the package is left under the data directory. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not a zip", "a zip-slip entry", "an invalid package", "a consumer that does not start"})
    void aRefusedDeployLeavesNoDirectory(String why) throws Exception {
        Path archive;
        Kind expected;
        switch (why) {
            case "not a zip" -> {
                archive = PackageDraft.write(archives, "this is no zip archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                expected = Kind.BAD_REQUEST;
            }
            case "a zip-slip entry" -> {
                Map<String, byte[]> entries = new LinkedHashMap<>(shop("shop").files());   // good entries first
                entries.put("../escaped.txt", "out of the package".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                archive = PackageDraft.write(archives, PackageDraft.zip(entries, List.of()));
                expected = Kind.BAD_REQUEST;
            }
            case "an invalid package" -> {
                PackageDraft p = shop("shop");
                p.manifest().put("dependencies", new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.0.0")));
                archive = p.zipTo(archives);
                expected = Kind.INVALID;
            }
            case "a consumer that does not start" -> {
                doors.failStart(2, "address in use");
                archive = shop("shop").zipTo(archives);
                expected = Kind.FAILED;
            }
            default -> throw new IllegalArgumentException(why);
        }

        DeployRefusedException refused = refused("shop", Optional.empty(), archive);

        assertEquals(expected, refused.kind(), why + ": " + refused.error() + " " + refused.problems());
        assertEquals(List.of(), regularFiles(data), "no file of the package is left under the data directory");
        assertFalse(Files.exists(data.resolve("packages").resolve("shop").resolve(PackageDraft.sha256(archive))), "no <name>/<sha256> directory");
        assertFalse(Files.exists(data.resolve("packages").resolve("shop")), "the refused first deploy of shop leaves no packages/shop/ directory either");
        assertEquals(List.of(), children(deployments.incomingDir()), "nothing is left in .incoming");
        assertTrue(regularFiles(tmp).stream().allMatch(f -> f.startsWith("archives/")), "nothing was written outside the data directory either: " + regularFiles(tmp));
        assertEquals(List.of(), deployments.flows());
        assertEquals(List.of(), deployments.packages());
    }

    // --- start

    /**
     * The package's fourth consumer start fails (whichever flow it is); the three before it started, one of them a sync flow with
     * a message in flight at the partner. The refusal is a 500 naming the flow; every flow of the package stops — deliveries with a
     * zero deadline (the message in flight is cancelled at once, the clock never moves), workers, consumers — its directory is
     * deleted and its name is free again; another package's flow keeps running.
     */
    @Test
    void aFailedStartStopsThePackagesFlowsAnd500s() throws Exception {
        deploy(flows("store", Map.of("orders", "sync")));
        Set<String> otherListed = rows(deployments.flows());
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("audit", "at-least-once");
        modes.put("orders", "sync");
        modes.put("refunds", "sync");
        modes.put("relay", "at-least-once");
        PackageDraft shop = flows("shop", modes);
        Path archive = shop.zipTo(archives);
        Doors.Gate fourth = doors.holdStart(4);
        OnVirtualThread.Call<DeployResult> deploying = OnVirtualThread.start(() -> deployments.deploy("shop", Optional.empty(), archive));
        reached(fourth.reached(), "the package's fourth consumer start", deploying);

        List<Doors.Door> shopDoors = doors.doors().stream().filter(d -> d.flow().startsWith("shop/")).toList();
        List<Doors.Door> started = shopDoors.stream().filter(Doors.Door::started).toList();
        assertEquals(3, started.size(), "fixture: three consumers of the package started before the fourth start: " + doors.events());
        Doors.Door sync = started.stream().filter(d -> d.flow().equals("shop/orders") || d.flow().equals("shop/refunds")).findFirst().orElseThrow();
        hold();
        Envelope inFlight = envelope(sync.flow());
        Future<Ack> inFlightAnswer = sync.deliver(inFlight);
        doors.partner().awaitSent(1);

        fourth.fail("address in use");
        DeployRefusedException refused = joinRefused(deploying, "the failed start answers without the clock moving (its deliveries stop with a zero deadline)");

        assertEquals(Kind.FAILED, refused.kind(), refused.error());
        assertTrue(refused.error().startsWith("flow '" + fourth.door().flow() + "' did not start: "), "names the flow by its qualified id: " + refused.error());
        assertTrue(refused.error().contains("address in use"), "and the cause: " + refused.error());
        Ack cancelled = within(inFlightAnswer, "the answer to the message in flight");
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, cancelled, "the message in flight is cancelled, not drained: " + cancelled);
        assertTrue(nack.retryable(), "its sender may try again: " + nack);
        assertTrue(nack.reason().contains(Duration.ZERO.toString()), "cancelled by a stop with a zero deadline: " + nack.reason());
        for (Doors.Door d : shopDoors) assertTrue(d.stopped(), d + " was stopped: " + doors.events());
        for (Doors.Door d : started) {
            Ack a = within(d.deliver(envelope(d.flow())), d + "'s answer after the failed start");
            assertTrue(a instanceof Ack.Nack n && n.retryable(), d + "'s delivery was stopped: it refuses a new message, was " + a);
        }
        int claims = queue.claims().size();
        rt.clock().advance(Duration.ofHours(1));
        assertEquals(claims, queue.claims().size(), "no worker of the package polls any more: " + queue.claims());
        assertFalse(doors.door("store/orders").stopped(), "another package's flow keeps running");
        assertEquals(otherListed, rows(deployments.flows()), "only the other package's flows are listed");
        assertEquals(List.of("store"), deployments.packages().stream().map(DeployedPackage::name).toList());
        assertFalse(Files.exists(data.resolve("packages").resolve("shop").resolve(PackageDraft.sha256(archive))), "the package's directory is deleted");

        assertEquals(4, deploy(shop).flows().size(), "the name is free: the same package deploys once its consumers start");
    }

    // --- versions on every message

    @Test
    void messagesCarryPackageAndRevisionNoRevisionKeyWithoutOne() throws Exception {
        deploy("shop", Optional.of("9df3f78"), shop("shop").zipTo(archives));

        Sent m = send("shop/orders");
        assertInstanceOf(Ack.Reply.class, within(m.answer(), "the sync answer"));
        List<Snapshot> timeline = messages.timeline(m.id()).await();
        assertFalse(timeline.isEmpty(), "fixture: the flow records its messages");
        for (Snapshot s : timeline) {
            assertEquals("shop/orders", s.flow());
            assertEquals(Map.of("flow", "shop/orders@2.1.0", "package", "shop@1.0.0", "revision", "9df3f78"), s.versions(), "snapshot " + s.stepName());
        }

        doors.partner().failNext(1, () -> new IllegalStateException("partner down"));
        Sent parked = send("shop/audit");
        assertEquals(new Ack.Accepted(parked.id()), within(parked.answer(), "the at-least-once answer"));
        until(() -> dlq.size() == 1, "the worker parks the message the partner refused");
        Parked row = dlq.get(parked.id()).await().orElseThrow();
        assertEquals("shop/audit", row.flow(), "a DLQ row names the qualified flow");
        assertEquals(Map.of("flow", "shop/audit@1.0.3", "package", "shop@1.0.0", "revision", "9df3f78"), row.versions(), "a DLQ row carries the same versions");

        PackageDraft store = shop("store");
        store.manifest().put("version", "3.0.0");
        deploy(store);
        Sent n = send("store/orders");
        assertInstanceOf(Ack.Reply.class, within(n.answer(), "the sync answer"));
        List<Snapshot> noRevision = messages.timeline(n.id()).await();
        assertFalse(noRevision.isEmpty(), "fixture: the flow records its messages");
        for (Snapshot s : noRevision) {
            assertEquals(Map.of("flow", "store/orders@2.1.0", "package", "store@3.0.0"), s.versions(), "without a revision there is no revision key: " + s.stepName());
        }
    }

    // --- close

    /**
     * Close stops every flow in parallel: the worker first (its tick is cancelled when its consumer's stop is called), then the
     * delivery with the node's stop deadline and the consumer together. A message in flight that answers in time drains; one
     * still in flight at the deadline (on the fake clock: not a millisecond before) is cancelled; close returns once both are
     * answered. A deploy while closing, and after, is 503.
     */
    @Test
    void closeStopsWorkersThenDrainsDeliveryAndConsumerAndRefusesDeploys503() throws Exception {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("audit", "at-least-once");
        modes.put("orders", "sync");
        modes.put("refunds", "sync");
        deploy(flows("shop", modes));
        List<TracingTimer.Task> ticks = timer.live();
        assertEquals(1, ticks.size(), "fixture: the at-least-once worker's poll tick is the only live timer task: " + timer.all());
        AtomicReference<Boolean> tickLiveAtAuditStop = new AtomicReference<>();
        doors.onStop(d -> { if (d.flow().equals("shop/audit")) tickLiveAtAuditStop.set(ticks.get(0).live()); });
        CountDownLatch ordersHeld = hold();
        Sent orders = send("shop/orders");
        doors.partner().awaitSent(1);
        hold();
        Sent refunds = send("shop/refunds");
        doors.partner().awaitSent(2);

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);

        until(() -> doors.door("shop/audit").stopped() && doors.door("shop/orders").stopped() && doors.door("shop/refunds").stopped(),
                "close stops every flow's consumer while two of them still drain a message: in parallel, not one flow after the other");
        assertEquals(Boolean.FALSE, tickLiveAtAuditStop.get(), "the worker was stopped before its consumer: its tick was cancelled first");
        assertFalse(orders.answer().isComplete() || refunds.answer().isComplete(), "both messages are still in flight");
        assertFalse(closing.isDone(), "close waits for them");
        assertEquals(Kind.STOPPING, refused("store", Optional.empty(), shop("store").zipTo(archives)).kind(), "a deploy while closing is 503");
        until(() -> timer.all().stream().filter(t -> t.delay().equals(STOP_DEADLINE)).count() >= 2,
                "both deliveries with a message in flight were stopped with the node's stop deadline " + STOP_DEADLINE + " (their deadline timers): " + timer.all());

        ordersHeld.countDown();
        Ack drained = within(orders.answer(), "the drained message's answer");
        assertEquals(orders.id(), assertInstanceOf(Ack.Reply.class, drained, "a message answered in time drains: " + drained).reply().id());
        assertFalse(closing.isDone(), "close still waits for the other message");
        rt.clock().advance(STOP_DEADLINE.minusMillis(1));
        assertFalse(refunds.answer().isComplete(), "not cancelled a millisecond before the stop deadline " + STOP_DEADLINE);
        assertFalse(closing.isDone());
        rt.clock().advance(Duration.ofMillis(1));
        Ack cancelled = within(refunds.answer(), "the answer at the deadline");
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, cancelled, "a message still in flight at the deadline is cancelled: " + cancelled);
        assertTrue(nack.retryable() && nack.reason().contains(STOP_DEADLINE.toString()), "by the node's stop deadline (FLOTTILA_STOP_DEADLINE): " + nack);
        OnVirtualThread.join(closing);

        DeployRefusedException after = refused("store", Optional.empty(), shop("store").zipTo(archives));
        assertEquals(Kind.STOPPING, after.kind(), "a deploy after close is 503: " + after.error());
        assertFalse(after.error().isBlank());
    }

    @Test
    void closeWaitsForARunningDeployThenStopsItsFlows() throws Exception {
        Doors.Gate gate = doors.holdStart(1);
        Path archive = shop("shop").zipTo(archives);
        OnVirtualThread.Call<DeployResult> deploying = OnVirtualThread.start(() -> deployments.deploy("shop", Optional.empty(), archive));
        reached(gate.reached(), "the deploy starts its first consumer", deploying);

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {   // 409 while the deploy holds the lock and close has not begun; 503 once it has
            DeployRefusedException r = refused("store", Optional.empty(), shop("store").zipTo(archives));
            if (r.kind() == Kind.STOPPING) break;
            assertEquals(Kind.CONFLICT, r.kind(), "before close begins a deploy meets the running one: " + r.error());
            if (System.nanoTime() > deadline) fail("close never began refusing deploys");
            Thread.onSpinWait();
        }
        assertFalse(closing.isDone(), "close waits for the running deploy");
        assertFalse(deploying.isDone(), "fixture: the deploy is still held");
        assertFalse(doors.doors().stream().anyMatch(Doors.Door::stopped), "nothing is stopped while the deploy runs: " + doors.events());

        gate.release();
        assertEquals("shop", OnVirtualThread.join(deploying).pkg().name(), "the running deploy completes");
        OnVirtualThread.join(closing);
        for (String flow : List.of("shop/orders", "shop/audit", "shop/ping")) {
            assertTrue(doors.door(flow).stopped(), "close then stops the flows that deploy started: " + flow + " " + doors.events());
        }
    }

    @Test
    void closeRethrowsTheFirstStopFailureAfterEveryFlowStopped() throws Exception {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("audit", "at-least-once");
        modes.put("orders", "sync");
        modes.put("refunds", "sync");
        deploy(flows("shop", modes));
        doors.failStop("shop/orders", "socket stuck");
        CountDownLatch held = hold();
        Sent refunds = send("shop/refunds");
        doors.partner().awaitSent(1);

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);

        until(() -> doors.doors().stream().allMatch(Doors.Door::stopped), "close stops every consumer");
        assertFalse(closing.isDone(), "orders' stop failed at once, but close waits until every flow has stopped: refunds still drains");
        held.countDown();
        assertInstanceOf(Ack.Reply.class, within(refunds.answer(), "the drained answer"));
        Exception thrown = assertThrows(Exception.class, () -> OnVirtualThread.join(closing), "close rethrows the stop failure");
        List<String> chain = new ArrayList<>();
        for (Throwable t = thrown; t != null; t = t.getCause()) chain.add(String.valueOf(t.getMessage()));
        assertTrue(chain.stream().anyMatch(m -> m.contains("socket stuck")), "the failure rethrown is orders' stop failure: " + chain);
        int claims = queue.claims().size();
        rt.clock().advance(Duration.ofHours(1));
        assertEquals(claims, queue.claims().size(), "the at-least-once worker was stopped all the same");
    }

    // --- files (D-060 b)

    @Test
    void aDeployRecordsEveryFileWithItsHash() throws Exception {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) i;
        PackageDraft p = shop("shop").file("flows/map.xsl", "<xsl:stylesheet version=\"3.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\"/>")
                .file("fixtures/deep/sample.bin", binary).file("README.md", "notes");
        byte[] zip = p.zipWithDirectories(List.of("fixtures/", "fixtures/deep/"));
        rt.clock().advance(Duration.ofMinutes(90));
        Instant at = rt.clock().instant();

        DeployedPackage deployed = deploy("shop", Optional.of("r1"), PackageDraft.write(archives, zip));

        Map<String, String> expected = new TreeMap<>();
        p.files().forEach((path, bytes) -> expected.put(path, PackageDraft.sha256(bytes)));
        assertEquals(expected, files(deployed), "every file of the archive (no directory), by its /-separated path from the package root, with its SHA-256");
        assertEquals(at, deployed.deployedAt(), "deployedAt is the node's clock at the deploy");
        assertEquals(Optional.of("r1"), deployed.revision());
        DeployedPackage listed = deployments.packages().stream().filter(x -> x.name().equals("shop")).findFirst().orElseThrow();
        assertEquals(expected, files(listed), "packages() carries the same files");
        assertEquals(at, listed.deployedAt());
        assertEquals(PackageDraft.sha256(zip), listed.archiveSha256());
    }
}
