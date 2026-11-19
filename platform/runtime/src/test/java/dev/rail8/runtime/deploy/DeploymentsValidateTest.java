package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Producer;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.settings.Settings;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.pg.PoolRefusedException;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03a (D-079, F-16): {@code Deployments.validate(String name, Path dir)} is the dry run of a deploy over a package directory —
 * load, check, build — that answers every problem as a list (empty = valid) and then discards what it built: nothing is
 * unpacked, no deploy lock is taken, no archive is put, no record written, no consumer built or started, nothing enqueued or
 * scheduled, and the listing stays as it was.
 *
 * <p>The shape these tests assume: {@code public List<String> Deployments.validate(String name, Path dir)} (blocking: called on
 * a virtual thread, as a deploy is).
 *
 * <p>The demo cases run over the shipped components, steps and languages (the modules the class path names, {@link Plugins}) on
 * a Vert.x of the case's own,
 * from copies of {@code examples/demo} rewritten in a temp directory (each rewrite asserted); the dry-build cases over
 * {@link Doors}, whose events, producers and gates witness what was built, started and held.
 */
class DeploymentsValidateTest {

    static final Path DEMO = Path.of("../../examples/demo");
    static final String NODE = "node-1";

    @TempDir Path tmp;
    Path data;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryDeploymentStore records = new InMemoryDeploymentStore();
    final List<String> shelfTrace = new CopyOnWriteArrayList<>();
    TracingTimer timer;
    CountingQueue queue;
    Shelf shelf;
    Vertx vertx;

    @BeforeEach
    void wire() {
        data = tmp.resolve("data");
        timer = new TracingTimer(rt.timer());
        queue = new CountingQueue(rt.clock());
        shelf = new Shelf(tmp.resolve("archives"), tmp.resolve("reads"), shelfTrace::add);
    }

    @AfterEach
    void close() {
        doors.releaseAll();
        if (vertx != null) vertx.close().await();
    }

    // --- fixture

    Map<String, String> env(String rail8Env) {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", rail8Env);
        env.put("RAIL8_NODE_ID", NODE);
        env.put("RAIL8_DATA_DIR", data.toString());
        // the demo's sftp-inbox keeps its password and host key as secret:// references (P2-06): a node that validates it has both set
        env.put("RAIL8_SECRET_SFTP_PASSWORD", "demo");
        env.put("RAIL8_SECRET_SFTP_HOST_KEY", "SHA256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU");
        return env;
    }

    /** Deployments over {@link Doors} only, its archives on the {@link Shelf}, its records in memory. */
    Deployments overDoors() {
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        return new Deployments(NodeConfig.from(env("dev")), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, DeploymentsTest.STEPS, List.of(),
                messages, dlq, queue, () -> 0L, records, shelf);
    }

    /** Deployments of the environment {@code rail8Env} over the node's modules: their components, steps and languages. */
    Deployments shipped(String rail8Env) {
        return shipped(rail8Env, UnaryOperator.identity());
    }

    /** As {@link #shipped(String)}, each of the node's components put through {@code replace} first. */
    Deployments shipped(String rail8Env, UnaryOperator<Component> replace) {
        if (vertx == null) vertx = Vertx.vertx();
        Plugins shipped = Plugins.fromEnv(Map.of());
        List<Component> built = shipped.components(new ComponentContext(vertx, rt.blobs(), rt.ids(), timer, rt.clock()));
        Map<String, Component> byScheme = built.stream().map(replace).collect(Collectors.toMap(Component::scheme, c -> c));
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), byScheme);
        return new Deployments(NodeConfig.from(env(rail8Env)), runtime, byScheme, rt.clock(), timer, shipped.steps(), shipped.languages(),
                messages, dlq, queue, () -> 0L, records, shelf);
    }

    static List<String> validate(Deployments d, String name, Path dir) throws Exception {
        return OnVirtualThread.call(() -> d.validate(name, dir));
    }

    /** A copy of the demo at {@code tmp/<as>}, each file rewritten by {@code rewrites} (path → (from → to)); every rewrite must hit. */
    Path demoCopy(String as, Map<String, Map<String, String>> rewrites) throws IOException {
        Path copy = tmp.resolve(as);
        try (Stream<Path> walk = Files.walk(DEMO)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path target = copy.resolve(DEMO.relativize(p).toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target);
            }
        }
        for (Map.Entry<String, Map<String, String>> file : rewrites.entrySet()) {
            Path f = copy.resolve(file.getKey());
            String text = Files.readString(f);
            for (Map.Entry<String, String> r : file.getValue().entrySet()) {
                assertTrue(text.contains(r.getKey()), "fixture: " + file.getKey() + " holds '" + r.getKey() + "': " + text);
                text = text.replace(r.getKey(), r.getValue());
            }
            Files.writeString(f, text);
        }
        return copy;
    }

    /** {@code draft}'s files written below {@code tmp/<as>}: a package directory as the CLI hands it over. */
    Path written(PackageDraft draft, String as) throws IOException {
        Path dir = tmp.resolve(as);
        for (Map.Entry<String, byte[]> f : draft.files().entrySet()) {
            Path target = dir.resolve(f.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, f.getValue());
        }
        return dir;
    }

    /** Every path below {@code dir}, relative, sorted; none when it does not exist. */
    static TreeSet<String> tree(Path dir) throws IOException {
        TreeSet<String> paths = new TreeSet<>();
        if (!Files.exists(dir)) return paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.forEach(p -> paths.add(dir.relativize(p).toString()));
        }
        return paths;
    }

    /** No line of {@code problems} names {@code dir} by an absolute path (D-078), its real path included. */
    static void noAbsolutePath(List<String> problems, Path dir) throws IOException {
        for (String abs : List.of(dir.toAbsolutePath().toString(), dir.toRealPath().toString())) {
            assertTrue(problems.stream().noneMatch(l -> l.contains(abs)), "no problem names " + abs + ": " + problems);
        }
    }

    /**
     * Every line of {@code problems} starts with one of {@code prefixes} and each prefix starts at least one line: one schema violation
     * may be several lines (json-schema's Basic output), so the count of lines per violation is not pinned.
     */
    static void assertOnly(List<String> problems, String... prefixes) {
        for (String prefix : prefixes) {
            assertTrue(problems.stream().anyMatch(l -> l.startsWith(prefix)), "a line '" + prefix + "…': " + problems);
        }
        assertTrue(problems.stream().allMatch(l -> Stream.of(prefixes).anyMatch(l::startsWith)), "nothing but " + List.of(prefixes) + ": " + problems);
    }

    // --- cases

    /** The demo as it is in the repository validates over what the node ships: no problem, and nothing written into it. */
    @Test
    void demoValidates() throws Exception {
        TreeSet<String> before = tree(DEMO);

        List<String> problems = validate(shipped("dev"), "demo", DEMO);

        assertEquals(List.of(), problems, "the demo is valid");
        assertEquals(before, tree(DEMO), "the validate wrote nothing into the package directory");
    }

    /**
     * A stylesheet that does not compile is found only by the dry build (Package.problems() compiles no XSLT): one problem per flow, the
     * flow's build line, naming the stylesheet by its path from the package root and never by an absolute path.
     */
    @Test
    void aBrokenXsltNamesTheFile() throws Exception {
        Path broken = demoCopy("broken", Map.of("flows/invoice-to-partner.xsl",
                Map.of("select=\"sum(Lines/Line/(Qty * Price))\"", "select=\"sum(Lines/Line/(Qty * Price)\"")));

        List<String> problems = validate(shipped("dev"), "demo", broken);

        // the demo's three flows share the stylesheet (P2-06): the dry build reports one problem per flow
        assertEquals(3, problems.size(), "one problem per flow, the build's: " + problems);
        for (String flow : List.of("invoice-async", "invoice-sync", "sftp-invoice")) {
            String line = problems.stream().filter(p -> p.startsWith("flow '" + flow + "' does not build: ")).findFirst().orElse(null);
            assertTrue(line != null, "the build line of flow '" + flow + "': " + problems);
            assertTrue(line.contains("flows/invoice-to-partner.xsl"), "it names the stylesheet from the package root: " + line);
        }
        noAbsolutePath(problems, broken);
    }

    /**
     * The http component as {@code inner}, with a configSchema() that refuses every config: with its settings description when
     * {@code described}, else with none.
     */
    record RefusingSchema(Component inner, boolean described) implements Component {
        @Override public String scheme() { return inner.scheme(); }
        @Override public Settings settings() { return described ? inner.settings() : Settings.NONE; }
        @Override public String configSchema() { return "{\"not\":{}}"; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return inner.consumer(cfg, handler); }
        @Override public Producer producer(EndpointConfig cfg) { return inner.producer(cfg); }
        @Override public Future<Health> health() { return inner.health(); }
        @Override public Optional<InetSocketAddress> listensOn(EndpointConfig cfg) { return inner.listensOn(cfg); }
        @Override public boolean polls(EndpointConfig cfg) { return inner.polls(cfg); }
        @Override public Optional<String> target(EndpointConfig cfg) { return inner.target(cfg); }
    }

    /**
     * A component that describes its settings is judged by that description only, never by its configSchema(): the demo's
     * http endpoints pass with an http component whose schema refuses every config, and that schema refuses all five of
     * them once the component describes nothing.
     */
    @Test
    void aDescribedComponentIsJudgedByItsDescriptionOnly() throws Exception {
        assertEquals(List.of(), validate(shipped("dev", c -> "http".equals(c.scheme()) ? new RefusingSchema(c, true) : c), "demo", DEMO));

        List<String> undescribed = validate(shipped("dev", c -> "http".equals(c.scheme()) ? new RefusingSchema(c, false) : c), "demo", DEMO);

        assertEquals(new TreeSet<>(List.of("http-in", "http-in-async", "http-in-order", "partner-domestic", "partner-foreign")),
                undescribed.stream().map(l -> l.substring("endpoint '".length(), l.indexOf("' config (environment dev) at "))).collect(Collectors.toCollection(TreeSet::new)),
                "every http endpoint, and nothing else, is refused by the schema: " + undescribed);
    }

    /** An endpoint config its component's settings description refuses (http: port is a whole number) is a problem naming endpoint, environment and pointer, and nothing else is. */
    @Test
    void anEndpointConfigOutsideItsComponentSchemaIsAProblem() throws Exception {
        Path bad = demoCopy("bad-config", Map.of("endpoints/http-in.yaml", Map.of("port: 8080", "port: eighty")));

        List<String> problems = validate(shipped("dev"), "demo", bad);

        assertOnly(problems, "endpoint 'http-in' config (environment dev) at '/port': ");
        noAbsolutePath(problems, bad);
    }

    /**
     * The node's environment is laid over each endpoint's config before its component checks it: partner-domestic's base
     * {@code timeoutMs: 0} is refused in dev, replaced by prod's {@code 5000} in prod; partner-foreign's ci overlay
     * {@code poolSize: 0} breaks it in ci only. A deployed environment (prod) is validated, not refused.
     */
    @Test
    void envOverlayApplies() throws Exception {
        Path overlaid = demoCopy("env-overlay", Map.of(
                "endpoints/partner-domestic.yaml", Map.of(
                        "  maxBodyBytes: 1048576\n", "  maxBodyBytes: 1048576\n  timeoutMs: 0\n",
                        "      url: https://partner.example/invoices", "      timeoutMs: 5000\n      url: https://partner.example/invoices"),
                "endpoints/partner-foreign.yaml", Map.of(
                        "  maxBodyBytes: 1048576\n", "  maxBodyBytes: 1048576\nenvironments:\n  ci:\n    config:\n      poolSize: 0\n")));

        List<String> dev = validate(shipped("dev"), "demo", overlaid);
        List<String> prod = validate(shipped("prod"), "demo", overlaid);
        List<String> ci = validate(shipped("ci"), "demo", overlaid);

        assertOnly(dev, "endpoint 'partner-domestic' config (environment dev) at '/timeoutMs': ");
        assertEquals(List.of(), prod, "prod replaces timeoutMs with 5000 and leaves partner-foreign alone");
        assertOnly(ci, "endpoint 'partner-domestic' config (environment ci) at '/timeoutMs': ",
                "endpoint 'partner-foreign' config (environment ci) at '/poolSize': ");
    }

    /**
     * A dry build over a node that runs {@code shop}: a new version of shop (one flow changed, one at-least-once flow added) and a new
     * package validate clean; the build ran — it opened the flows' producers — and closed what it built: every producer it opened is
     * closed, no consumer was built or started and none of shop's running ones stopped, nothing was scheduled, enqueued, claimed,
     * recorded, parked, put in the archive store or written as a record, nothing appeared under the data directory, and the
     * listing is the one before.
     */
    @Test
    void dryBuildStartsNothingAndStoresNothing() throws Exception {
        Deployments deployments = overDoors();
        PackageDraft shop = DeploymentsTest.shop("shop");
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), shop.zipTo(tmp.resolve("zips"))));
        PackageDraft shopV2 = DeploymentsTest.shop("shop")
                .endpoint("relay-in", "in", new JsonObject().put("path", "/relay"))
                .flow("relay", "1.0.0", "at-least-once", "relay-in", 1024, PackageDraft.toPartner());
        shopV2.flowDoc("orders").put("version", "2.2.0");
        Path v2 = written(shopV2, "shop-v2");
        Path store = written(DeploymentsTest.shop("store"), "store");

        List<?> flows = deployments.flows();
        List<?> packages = deployments.packages();
        List<String> events = doors.events();
        int producers = doors.producers().size();
        int open = doors.openProducers().size();
        int scheduled = timer.all().size();
        List<String> puts = shelf.puts();
        List<?> recorded = records.list().await();
        TreeSet<String> dataTree = tree(data);
        TreeSet<String> archiveTree = tree(tmp.resolve("archives"));
        List<String> enqueued = queue.enqueued();
        List<String> claims = queue.claims();

        assertEquals(List.of(), validate(deployments, "shop", v2), "shop v2 is valid");
        assertEquals(List.of(), validate(deployments, "store", store), "store is valid");

        assertTrue(doors.producers().size() > producers, "witness: the dry build built the flows, opening their producers: " + doors.producers().size());
        assertEquals(open, doors.openProducers().size(), "every producer the dry build opened is closed again");
        assertEquals(events, doors.events(), "no consumer built, started or stopped");
        assertEquals(scheduled, timer.all().size(), "nothing scheduled on the node's timer");
        assertEquals(enqueued, queue.enqueued(), "nothing enqueued");
        assertEquals(claims, queue.claims(), "nothing claimed");
        assertEquals(0, messages.size(), "nothing recorded");
        assertEquals(0, dlq.size(), "nothing parked");
        assertEquals(puts, shelf.puts(), "no archive put");
        assertEquals(archiveTree, tree(tmp.resolve("archives")), "the archive store holds what it held");
        assertEquals(recorded, records.list().await(), "no deploy record written");
        assertEquals(dataTree, tree(data), "nothing unpacked or written under the data directory");
        assertFalse(Files.exists(data.resolve("packages").resolve("store")), "store was never unpacked");
        assertEquals(flows, deployments.flows(), "the flows listed are the ones before");
        assertEquals(packages, deployments.packages(), "the packages listed are the ones before");
    }

    /** A validate takes no deploy lock: it answers while a deploy holds it, waiting on its first consumer's start. */
    @Test
    void validateAnswersWhileADeployHoldsTheLock() throws Exception {
        Deployments deployments = overDoors();
        Doors.Gate gate = doors.holdStart(1);
        Path archive = DeploymentsTest.shop("shop").zipTo(tmp.resolve("zips"));
        OnVirtualThread.Call<DeployResult> deploying = OnVirtualThread.start(() -> deployments.deploy("shop", Optional.empty(), archive));
        DeploymentsTest.reached(gate.reached(), "the deploy starts its first consumer", deploying);
        Path store = written(DeploymentsTest.shop("store"), "store");

        List<String> problems = validate(deployments, "store", store);

        assertEquals(List.of(), problems, "store is valid");
        assertFalse(deploying.isDone(), "the deploy still holds the lock, waiting on its consumer's start");
        gate.release();
        assertEquals(List.of("shop/audit", "shop/orders", "shop/ping"), OnVirtualThread.join(deploying).pkg().flows(), "the held deploy completes");
    }

    /**
     * validate checks against what the node runs, as a deploy does (M08): shop runs the at-least-once audit and the work queue holds a
     * row of it (leased by another node); a version without audit answers the deploy's own "work queue holds items" problem — the
     * same lines the deploy of that version is refused with — and leaves the listing, the doors and the queue's rows as they were.
     */
    @Test
    void validateChecksAgainstTheFlowsTheNodeRuns() throws Exception {
        Deployments deployments = overDoors();
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), DeploymentsTest.shop("shop").zipTo(tmp.resolve("zips"))));
        queue.enqueue("m-left", "shop/audit", 1, null).await();
        assertEquals(1, queue.claim("node-2", "shop/audit", 1, Duration.ofMinutes(5)).await().size(), "fixture: node-2 leases the row");
        PackageDraft withoutAudit = DeploymentsTest.shop("shop").without("flows/audit.yaml").without("endpoints/audit-in.yaml");
        Path dir = written(withoutAudit, "shop-without-audit");
        List<?> flows = deployments.flows();
        List<?> packages = deployments.packages();
        List<String> events = doors.events();
        String rows = queue.rows().toString();

        List<String> problems = validate(deployments, "shop", dir);

        assertEquals(1, problems.size(), "one problem: " + problems);
        assertTrue(problems.getFirst().startsWith("flow 'audit' runs at-least-once and the work queue holds items of it"), problems.getFirst());
        assertEquals(flows, deployments.flows(), "the listing is the one before");
        assertEquals(packages, deployments.packages());
        assertEquals(events, doors.events(), "nothing stopped or started");
        assertEquals(rows, queue.rows().toString(), "the queue's rows are as they were");
        DeployRefusedException refused = assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), withoutAudit.zipTo(tmp.resolve("zips")))));
        assertEquals(refused.problems(), problems, "the same problem lines a deploy of that version is refused with");
    }

    /**
     * FIX-172c: a deploy turns a work-queue question the node's Postgres pool refused into BUSY (503), a refusal with no problem
     * lines; validate asks the same question and answers only problems, so that refusal is the node's IOException there, as any
     * other queue failure is — never an empty list that says "valid". Witness: the queue answering, the version without audit validates.
     */
    @Test
    void aQueueQuestionThePoolRefusedFailsTheValidateAsTheNodes() throws Exception {
        Deployments deployments = overDoors();
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), DeploymentsTest.shop("shop").zipTo(tmp.resolve("zips"))));
        Path dir = written(DeploymentsTest.shop("shop").without("flows/audit.yaml").without("endpoints/audit-in.yaml"), "shop-without-audit");
        PoolRefusedException refusal = new PoolRefusedException(new VertxException("Timeout", true));
        queue.failHasItems(refusal);

        IOException failed = assertThrows(IOException.class, () -> validate(deployments, "shop", dir), "the pool's refusal is the node's failure");

        assertTrue(failed.getMessage().contains("shop/audit") && failed.getMessage().contains(refusal.getMessage()),
                "it names the flow and the pool's refusal: " + failed.getMessage());
        queue.failHasItems(null);
        assertEquals(List.of(), validate(deployments, "shop", dir), "witness: the queue answering, the version validates");
    }
}
