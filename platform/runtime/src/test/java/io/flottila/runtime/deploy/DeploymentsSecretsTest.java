package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.components.sftp.SftpComponent;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-04 (D-081): an endpoint option whose whole string value is {@code secret://<name>} — at any depth of nested maps and lists —
 * reaches the component, as its consumer and as every producer of it, resolved from the env map the node was configured with
 * ({@code FLOTTILA_SECRET_<NAME>}, through {@link NodeConfig#from}); resolved after the env overlay and the schema check, only in
 * the effective options. A string that merely contains {@code secret://} is left alone, a malformed reference is a problem, and a
 * variable that is not set refuses the deploy (a restore: lists the package FAILED) with a problem naming the endpoint, the
 * reference and the variable. The value lives only in the EndpointConfig handed to the component: what the node shows — the
 * listing (package, files, artifacts, flows and their effective hash), the record, the unpacked files, problems, logs and the
 * timeline — carries the reference or nothing.
 *
 * <p>Where "the reference is what is observable" is testable today: {@code packages()}/{@code flows()} (what {@code GET
 * /admin/packages} and {@code /admin/flows} encode) carry no endpoint option at all, so their text must hold no value and the
 * flow's effective hash must not vary with it ({@link FlowHash}: the same content hashes the same on any node); the unpacked
 * endpoint file holds the reference; the record, problems, logs and the timeline must hold no value.
 *
 * <p>The shape these cases assume (P2-04 test-author): the node's secrets come from the env map given to {@link NodeConfig#from}
 * — no other wiring between that map and {@link Deployments}' existing constructors.
 */
class DeploymentsSecretsTest {

    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);
    static final String HOST_KEY = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<Node> nodes = new ArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void listen() {
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void closeEveryNode() throws Exception {
        try {
            for (Node n : nodes) n.doors.releaseAll();
            for (Node n : nodes) OnVirtualThread.call(() -> {
                n.deployments.close();
                return null;
            });
        } finally {
            flottila.removeHandler(capture);
        }
    }

    // --- fixture

    /**
     * A component of the scheme {@code sftp} with the real sftp configSchema ({@code password} and {@code privateKey} plain strings)
     * that records the config of every consumer it builds; it never connects. No package here sends to it.
     */
    static final class SftpShaped implements Component {
        final List<EndpointConfig> consumers = new CopyOnWriteArrayList<>();
        private final String schema;

        SftpShaped(FakeRuntime rt) {
            this.schema = new SftpComponent(rt.blobs(), rt.ids(), rt.timer(), rt.clock()).configSchema();
        }

        @Override public String scheme() { return "sftp"; }

        @Override public String configSchema() { return schema; }

        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
            consumers.add(cfg);
            return new Consumer() {
                @Override public Future<Void> start() { return Future.succeededFuture(); }
                @Override public Future<Void> stop() { return Future.succeededFuture(); }
            };
        }

        @Override public Producer producer(EndpointConfig cfg) {
            throw new UnsupportedOperationException("fixture: no package of these cases sends to sftp");
        }

        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    /** One node: its data directory {@code <tmp>/<id>}, its env (with {@code secrets} added), its Doors and sftp stand-in. */
    final class Node {
        final Path data;
        final Map<String, String> env = new HashMap<>();
        final Doors doors = new Doors();
        final SftpShaped sftp = new SftpShaped(rt);
        final Deployments deployments;

        Node(String id, String flottilaEnv, Map<String, String> secrets, DeploymentStore records, ArchiveStore archives) {
            data = tmp.resolve(id);
            env.put("FLOTTILA_ENV", flottilaEnv);
            env.put("FLOTTILA_NODE_ID", id);
            env.put("FLOTTILA_DATA_DIR", data.toString());
            env.put("FLOTTILA_STOP_DEADLINE", STOP_DEADLINE.toString());
            env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
            env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            env.putAll(secrets);
            Map<String, Component> components = Map.of(Doors.SCHEME, doors, "sftp", sftp);
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
            NodeConfig config = NodeConfig.from(env);
            deployments = records == null
                    ? new Deployments(config, runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                            new CountingQueue(rt.clock()), () -> 0L)
                    : new Deployments(config, runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                            new CountingQueue(rt.clock()), () -> 0L, records, archives);
            nodes.add(this);
        }

        DeployedPackage deploy(PackageDraft p) throws Exception {
            return OnVirtualThread.call(() -> deployments.deploy(name(p), Optional.empty(), p.zipTo(tmp.resolve("uploads")))).pkg();
        }

        DeployRefusedException refused(PackageDraft p) {
            return assertThrows(DeployRefusedException.class,
                    () -> OnVirtualThread.call(() -> deployments.deploy(name(p), Optional.empty(), p.zipTo(tmp.resolve("uploads")))),
                    "the deploy is refused");
        }

        void restore() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.restore();
                return null;
            });
        }

        /** The options of the consumer the deploy built for {@code flow}, its qualified id {@code <package>/<flow>} (a rec in endpoint). */
        Map<String, Object> consumed(String flow) {
            return doors.door(flow).cfg().options();
        }

        /** The options of every producer built for {@code endpoint}; fails the case when there is none. */
        List<Map<String, Object>> produced(String endpoint) {
            List<EndpointConfig> configs = doors.producerConfigs(endpoint);
            assertFalse(configs.isEmpty(), "fixture: a producer of " + endpoint + " was built: " + doors.producers());
            return configs.stream().map(EndpointConfig::options).toList();
        }
    }

    Node node(Map<String, String> secrets) {
        return new Node("node-" + (nodes.size() + 1), "dev", secrets, null, null);
    }

    static String name(PackageDraft p) {
        return p.manifest().getString("name");
    }

    /**
     * {@code name} 1.0.0: {@code orders} (sync), {@code audit} (at-least-once) and {@code ping} (at-most-once), each from its own rec
     * in endpoint with {@code in} as its config (plus a path) and each sending to {@code partner}, whose config is {@code partner}.
     */
    static PackageDraft shop(String name, JsonObject in, JsonObject partner) {
        return PackageDraft.of(name, "1.0.0")
                .endpoint("orders-in", "in", in.copy().put("path", "/orders"))
                .endpoint("audit-in", "in", in.copy().put("path", "/audit"))
                .endpoint("ping-in", "in", in.copy().put("path", "/ping"))
                .endpoint("partner", "out", partner)
                .flow("orders", "1.0.0", "sync", "orders-in", 4096, PackageDraft.toPartner())
                .flow("audit", "1.0.0", "at-least-once", "audit-in", 1024, PackageDraft.toPartner())
                .flow("ping", "1.0.0", "at-most-once", "ping-in", 2048, PackageDraft.toPartner());
    }

    /** {@code name} 1.0.0: one sync flow {@code orders} from {@code orders-in} (config {@code in} plus a path) to {@code partner}. */
    static PackageDraft one(String name, JsonObject in, JsonObject partner) {
        return PackageDraft.of(name, "1.0.0")
                .endpoint("orders-in", "in", in.copy().put("path", "/orders"))
                .endpoint("partner", "out", partner)
                .flow("orders", "1.0.0", "sync", "orders-in", 4096, PackageDraft.toPartner());
    }

    static JsonObject url(String url) {
        return new JsonObject().put("url", url);
    }

    /** Every text a log record carries: its message, its parameters and every throwable's message in its cause and suppressed chain. */
    static List<String> texts(LogRecord r) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(r.getMessage()));
        if (r.getParameters() != null) Arrays.stream(r.getParameters()).map(String::valueOf).forEach(texts::add);
        List<Throwable> todo = new ArrayList<>();
        if (r.getThrown() != null) todo.add(r.getThrown());
        while (!todo.isEmpty()) {
            Throwable t = todo.removeFirst();
            texts.add(String.valueOf(t));
            if (t.getCause() != null) todo.add(t.getCause());
            todo.addAll(List.of(t.getSuppressed()));
        }
        return texts;
    }

    void assertNotLogged(String value) {
        for (LogRecord r : logged) {
            for (String text : texts(r)) assertFalse(text.contains(value), "a log line carries the secret's value: " + text);
        }
    }

    /** Every regular file below {@code dir} whose bytes contain {@code value}. */
    static List<Path> filesHolding(Path dir, String value) throws IOException {
        if (!Files.exists(dir)) return List.of();
        byte[] needle = value.getBytes(StandardCharsets.UTF_8);
        List<Path> holding = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(p);
                for (int i = 0; i + needle.length <= bytes.length; i++) {
                    if (Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) {
                        holding.add(p);
                        break;
                    }
                }
            }
        }
        return holding;
    }

    // --- both paths, resolved

    /** An sftp in endpoint's {@code password: secret://drop-password} passes the real sftp schema and reaches the consumer resolved. */
    @Test
    void anSftpPasswordReferenceReachesTheComponentResolved() throws Exception {
        Node n = node(Map.of("FLOTTILA_SECRET_DROP_PASSWORD", "pw-from-the-env-4711"));
        PackageDraft p = PackageDraft.of("drop", "1.0.0")
                .endpoint("drop", "sftp", "in", new JsonObject().put("user", "flottila").put("password", "secret://drop-password").put("hostKey", HOST_KEY))
                .endpoint("partner", "out", url("rec://partner"))
                .flow("from-drop", "1.0.0", "sync", "drop", 4096, PackageDraft.toPartner());

        n.deploy(p);

        assertEquals(1, n.sftp.consumers.size(), "one consumer built: " + n.sftp.consumers);
        Map<String, Object> options = n.sftp.consumers.getFirst().options();
        assertEquals("pw-from-the-env-4711", options.get("password"), "the component gets the value: " + options);
        assertEquals("flottila", options.get("user"), "the other options as written: " + options);
        assertEquals(HOST_KEY, options.get("hostKey"), "the other options as written: " + options);
    }

    /** An out endpoint's reference reaches every producer built of it — the sync and at-most-once deliveries', the worker's, the replays'. */
    @Test
    void producerOptionsResolvedToo() throws Exception {
        Node n = node(Map.of("FLOTTILA_SECRET_PARTNER_URL", "rec://partner-from-the-env"));

        n.deploy(shop("shop", new JsonObject(), url("secret://partner-url")));

        List<Map<String, Object>> produced = n.produced("partner");
        for (Map<String, Object> options : produced) {
            assertEquals("rec://partner-from-the-env", options.get("url"), "every producer of partner gets the value: " + produced);
        }
        assertTrue(produced.size() >= 4, "at least the three flows' sends and the worker's: " + produced.size() + " " + n.doors.producers());
    }

    /** The consumer's options carry the value; the deploy's own options (flow, maxBodyBytes) are still there. */
    @Test
    void consumerOptionsResolved() throws Exception {
        Node n = node(Map.of("FLOTTILA_SECRET_INBOUND_URL", "rec://inbound-from-the-env"));

        n.deploy(one("one", url("secret://inbound-url"), url("rec://partner")));

        Map<String, Object> options = n.consumed("one/orders");
        assertEquals("rec://inbound-from-the-env", options.get("url"), "the consumer gets the value: " + options);
        assertEquals(4096L, ((Number) options.get("maxBodyBytes")).longValue(), "the deploy's options stay: " + options);
        assertEquals("/orders", options.get("path"), options.toString());
    }

    /** References at every depth of nested maps and lists are resolved; every other value — numbers, booleans, plain strings — is kept. */
    @Test
    void nestedListAndMapResolved() throws Exception {
        Node n = node(Map.of("FLOTTILA_SECRET_A", "value-a", "FLOTTILA_SECRET_B.KEY", "decoy", "FLOTTILA_SECRET_B_KEY", "value-b",
                "FLOTTILA_SECRET_C", "value-c"));
        JsonObject tags = new JsonObject()
                .put("token", "secret://a")
                .put("list", new JsonArray().add("secret://b.key").add(new JsonObject().put("deep", new JsonArray().add("secret://c"))).add("plain").add(7).add(true))
                .put("note", "see secret://a")
                .put("count", 3);

        n.deploy(one("nested", new JsonObject().put("tags", tags), url("rec://partner").put("tags", tags.copy())));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("token", "value-a");
        expected.put("list", List.of("value-b", Map.of("deep", List.of("value-c")), "plain", 7, true));
        expected.put("note", "see secret://a");
        expected.put("count", 3);
        assertEquals(expected, n.consumed("nested/orders").get("tags"), "the consumer's nested options");
        for (Map<String, Object> options : n.produced("partner")) assertEquals(expected, options.get("tags"), "a producer's nested options");
    }

    /** A string that is not exactly a reference — text around {@code secret://}, a URL holding one — is the component's as written. */
    @ParameterizedTest
    @ValueSource(strings = {"see secret://partner-token", "https://partner.example/?t=secret://partner-token", "xsecret://partner-token"})
    void aStringContainingSecretIsUntouched(String written) throws Exception {
        Node n = node(Map.of("FLOTTILA_SECRET_PARTNER_TOKEN", "the-token-value"));

        n.deploy(one("contains", url(written), url(written)));

        assertEquals(written, n.consumed("contains/orders").get("url"), "the consumer's option as written");
        for (Map<String, Object> options : n.produced("partner")) assertEquals(written, options.get("url"), "a producer's option as written");
    }

    /** The environment's overlay may name a reference: the effective option is resolved. */
    @Test
    void envOverlayReferenceResolved() throws Exception {
        Node n = node(Map.of("FLOTTILA_SECRET_DEV_URL", "rec://dev-partner-from-the-env"));
        PackageDraft p = one("overlay", url("rec://inbound"), url("rec://partner-as-written"));
        p.endpointDoc("partner").put("environments", new JsonObject().put("dev", new JsonObject().put("config", url("secret://dev-url"))));

        n.deploy(p);

        for (Map<String, Object> options : n.produced("partner")) {
            assertEquals("rec://dev-partner-from-the-env", options.get("url"), "the overlay's reference, resolved");
        }
    }

    /** Only the effective options are resolved: a reference in another environment's overlay needs no variable here. */
    @Test
    void aReferenceInAnotherEnvironmentNeedsNoVariable() throws Exception {
        Node n = node(Map.of());
        PackageDraft p = one("prod-only", url("rec://inbound"), url("rec://partner-as-written"));
        p.endpointDoc("partner").put("environments", new JsonObject().put("prod", new JsonObject().put("config", url("secret://prod-url"))));

        n.deploy(p);

        for (Map<String, Object> options : n.produced("partner")) assertEquals("rec://partner-as-written", options.get("url"));
    }

    /**
     * The schema checks the reference, not the value: {@code level} takes low or high, the variable holds {@code high}, the option
     * is {@code secret://level} — refused at {@code /level}, nothing built.
     */
    @Test
    void theSchemaChecksTheReferenceNotTheValue() {
        Node n = node(Map.of("FLOTTILA_SECRET_LEVEL", "high"));

        DeployRefusedException refused = n.refused(one("level", new JsonObject().put("level", "secret://level"), url("rec://partner")));

        assertEquals(Kind.INVALID, refused.kind(), refused.problems().toString());
        assertTrue(refused.problems().stream().anyMatch(l -> l.startsWith("endpoint 'orders-in' config (environment dev) at '/level': ")),
                refused.problems().toString());
        assertEquals(List.of(), n.doors.doors(), "no consumer built");
    }

    // --- problems

    /** {@code secret://} and {@code secret://Bad Name} are not references to resolve: the deploy is refused naming endpoint and reference. */
    @ParameterizedTest
    @ValueSource(strings = {"secret://", "secret://Bad Name"})
    void malformedReferenceIsAProblem(String reference) {
        Node n = node(Map.of("FLOTTILA_SECRET_", "x", "FLOTTILA_SECRET_BAD_NAME", "x"));

        DeployRefusedException refused = n.refused(one("malformed", url("rec://inbound"), url(reference)));

        assertEquals(Kind.INVALID, refused.kind(), refused.problems().toString());
        assertTrue(refused.problems().stream().anyMatch(l -> l.startsWith("endpoint 'partner'") && l.contains(reference)),
                "a problem names the endpoint and the reference: " + refused.problems());
        assertEquals(List.of(), n.deployments.packages(), "nothing is listed");
        assertEquals(List.of(), n.doors.openProducers(), "no producer left open");
    }

    /**
     * Two variables not set: the deploy is refused (422) with one problem each, naming endpoint, reference and variable; the value
     * of the reference that is set is in no problem, no error text and no log line; nothing is listed or left open.
     */
    @Test
    void missingVariableRefusesTheDeployNamingTheVariable() {
        String value = "present-value-0815";
        Node n = node(Map.of("FLOTTILA_SECRET_PRESENT", value));
        JsonObject in = new JsonObject().put("tags", new JsonObject().put("present", "secret://present").put("key", "secret://inbound.key"));

        DeployRefusedException refused = n.refused(one("missing", in, url("secret://partner-token")));

        assertEquals(Kind.INVALID, refused.kind(), refused.problems().toString());
        assertTrue(refused.problems().contains("endpoint 'partner': secret 'secret://partner-token' is not set (FLOTTILA_SECRET_PARTNER_TOKEN)"),
                refused.problems().toString());
        assertTrue(refused.problems().contains("endpoint 'orders-in': secret 'secret://inbound.key' is not set (FLOTTILA_SECRET_INBOUND_KEY)"),
                refused.problems().toString());
        for (String text : List.of(String.valueOf(refused.problems()), String.valueOf(refused.getMessage()), String.valueOf(refused.error()), String.valueOf(refused))) {
            assertFalse(text.contains(value), "no value in the refusal: " + text);
        }
        assertNotLogged(value);
        assertEquals(List.of(), n.deployments.packages(), "nothing is listed");
        assertEquals(List.of(), n.doors.doors(), "no consumer built");
        assertEquals(List.of(), n.doors.openProducers(), "no producer left open");
    }

    /** A variable that is set but empty is refused as a missing one: the same problem line, nothing listed, built or left open. */
    @Test
    void anEmptyVariableRefusesTheDeployAsAMissingOne() {
        Node n = node(Map.of("FLOTTILA_SECRET_PARTNER_TOKEN", ""));

        DeployRefusedException refused = n.refused(one("empty", url("rec://inbound"), url("secret://partner-token")));

        assertEquals(Kind.INVALID, refused.kind(), refused.problems().toString());
        assertTrue(refused.problems().contains("endpoint 'partner': secret 'secret://partner-token' is not set (FLOTTILA_SECRET_PARTNER_TOKEN)"),
                refused.problems().toString());
        assertEquals(List.of(), n.deployments.packages(), "nothing is listed");
        assertEquals(List.of(), n.doors.doors(), "no consumer built");
        assertEquals(List.of(), n.doors.openProducers(), "no producer left open");
    }

    /** {@code roll} at {@code version}: {@code from-drop} (sftp, password by reference) and {@code orders} (rec), both sync to partner by reference. */
    static PackageDraft rolled(String version) {
        return PackageDraft.of("roll", version)
                .endpoint("drop", "sftp", "in", new JsonObject().put("user", "flottila").put("password", "secret://drop-password").put("hostKey", HOST_KEY))
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders"))
                .endpoint("partner", "out", url("secret://partner-url"))
                .flow("from-drop", version, "sync", "drop", 4096, PackageDraft.toPartner())
                .flow("orders", version, "sync", "orders-in", 4096, PackageDraft.toPartner());
    }

    /**
     * The rollback builds v1 again from what its build took: v2 changes both flows and its orders does not start (phase B), so
     * v1's flows are rebuilt and run again — the rebuilt consumer and producers get the values, never a reference, and each
     * flow lists v1's hash again.
     */
    @Test
    void aRollbackRebuildsWithTheResolvedValues() throws Exception {
        Node n = node(Map.of("FLOTTILA_SECRET_DROP_PASSWORD", "pw-from-the-env-4711", "FLOTTILA_SECRET_PARTNER_URL", "rec://partner-from-the-env"));
        n.deploy(rolled("1.0.0"));
        Map<String, String> v1Hashes = new LinkedHashMap<>();
        n.deployments.flows().forEach(f -> v1Hashes.put(f.id(), f.hash()));
        assertEquals(Set.of("roll/from-drop", "roll/orders"), v1Hashes.keySet(), v1Hashes.toString());
        int consumersBefore = n.sftp.consumers.size();
        int producersBefore = n.doors.producerConfigs("partner").size();
        n.doors.failStartOf("roll/orders", "address in use");

        DeployRefusedException refused = n.refused(rolled("2.0.0"));

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.error().contains("2.0.0 did not start") && refused.error().contains("1.0.0 runs again") && !refused.error().contains("FAILED"),
                refused.error());
        List<EndpointConfig> consumers = n.sftp.consumers;
        assertEquals(consumersBefore + 2, consumers.size(), "v2's consumer, then v1's built again: " + consumers);
        assertEquals("pw-from-the-env-4711", consumers.getLast().options().get("password"), "the rebuilt consumer gets the value: " + consumers.getLast());
        List<Map<String, Object>> produced = n.produced("partner");
        assertTrue(produced.size() > producersBefore, "producers were built after v1's: " + produced);
        for (Map<String, Object> options : produced.subList(producersBefore, produced.size())) {
            assertEquals("rec://partner-from-the-env", options.get("url"), "every producer built by v2 and the rollback gets the value: " + produced);
        }
        Map<String, String> after = new LinkedHashMap<>();
        n.deployments.flows().forEach(f -> after.put(f.id(), f.hash()));
        assertEquals(v1Hashes, after, "v1's flows run again with v1's hashes");
        assertTrue(n.deployments.flows().stream().allMatch(f -> f.state() == DeployedFlow.State.RUNNING), n.deployments.flows().toString());
    }

    /**
     * A restore reads the variables of the node that restores (D-081: values at each deploy/restore, from the env captured at
     * start): a node without one lists the package FAILED with the problem naming it, a node with another value runs it with that.
     */
    @Test
    void aRestoreWithoutTheVariableListsThePackageFailedNamingIt() throws Exception {
        InMemoryDeploymentStore records = new InMemoryDeploymentStore();
        DirArchiveStore archives = new DirArchiveStore(tmp.resolve("pool-archives"));
        Node first = new Node("node-1", "dev", Map.of("FLOTTILA_SECRET_PARTNER_URL", "rec://first-value"), records, archives);
        first.deploy(one("restored", url("rec://inbound"), url("secret://partner-url")));
        for (Map<String, Object> options : first.produced("partner")) assertEquals("rec://first-value", options.get("url"));

        Node without = new Node("node-2", "dev", Map.of(), records, archives);
        without.restore();
        Node other = new Node("node-3", "dev", Map.of("FLOTTILA_SECRET_PARTNER_URL", "rec://third-value"), records, archives);
        other.restore();

        DeployedPackage failed = without.deployments.packages().stream().filter(p -> p.name().equals("restored")).findFirst()
                .orElseGet(() -> fail("node-2 lists no package restored: " + without.deployments.packages()));
        assertEquals(DeployedPackage.State.FAILED, failed.state(), failed.toString());
        assertTrue(failed.problems().contains("endpoint 'partner': secret 'secret://partner-url' is not set (FLOTTILA_SECRET_PARTNER_URL)"),
                failed.problems().toString());
        assertEquals(List.of(), without.doors.openProducers(), "node-2 left no producer open");
        DeployedPackage running = other.deployments.packages().stream().filter(p -> p.name().equals("restored")).findFirst()
                .orElseGet(() -> fail("node-3 lists no package restored: " + other.deployments.packages()));
        assertEquals(DeployedPackage.State.RUNNING, running.state(), running.toString());
        for (Map<String, Object> options : other.produced("partner")) assertEquals("rec://third-value", options.get("url"), "node-3's own value");
    }

    // --- the value is the component's only

    /**
     * A deploy with references on both paths and one message sent through: the components got the values; the listing (packages,
     * files, artifacts, flows), the record, every file the node wrote, the timeline and the logs hold neither value; the unpacked
     * endpoint files hold the references.
     */
    @Test
    void listingAndRecordShowTheReference() throws Exception {
        String partnerValue = "rec://partner-V4LUE-7f3a";
        String inboundValue = "inbound-V4LUE-9c1d";
        InMemoryDeploymentStore records = new InMemoryDeploymentStore();
        DirArchiveStore archives = new DirArchiveStore(tmp.resolve("pool-archives"));
        Node n = new Node("node-1", "dev", Map.of("FLOTTILA_SECRET_PARTNER_URL", partnerValue, "FLOTTILA_SECRET_INBOUND", inboundValue),
                records, archives);

        n.deploy(one("listed", new JsonObject().put("tags", new JsonObject().put("key", "secret://inbound")), url("secret://partner-url")));
        Envelope e = rt.ingress("listed/orders", Headers.empty(), Buffer.buffer("<order/>"));
        within(n.doors.door("listed/orders").deliver(e), "the sync flow answers");

        assertEquals(Map.of("key", inboundValue), n.consumed("listed/orders").get("tags"), "the consumer got the value: the checks below are not vacuous");
        for (Map<String, Object> options : n.produced("partner")) assertEquals(partnerValue, options.get("url"), "the producer got the value");
        for (String value : List.of(partnerValue, inboundValue)) {
            assertFalse(String.valueOf(n.deployments.packages()).contains(value), "the package listing: " + n.deployments.packages());
            assertFalse(String.valueOf(n.deployments.flows()).contains(value), "the flow listing: " + n.deployments.flows());
            assertFalse(String.valueOf(within(records.list(), "the records")).contains(value), "the record");
            assertFalse(String.valueOf(within(messages.timeline(e.id()), "the timeline")).contains(value), "the timeline");
            assertEquals(List.of(), filesHolding(tmp, value), "no file the node wrote holds the value");
            assertNotLogged(value);
        }
        List<Path> endpointFiles;
        try (Stream<Path> walk = Files.walk(n.data)) {
            endpointFiles = walk.filter(f -> f.endsWith(Path.of("endpoints", "partner.yaml"))).toList();
        }
        assertFalse(endpointFiles.isEmpty(), "the package is unpacked below the data directory");
        for (Path f : endpointFiles) assertTrue(Files.readString(f).contains("secret://partner-url"), "the unpacked file holds the reference: " + f);
    }

    /**
     * The effective hash covers the reference, not the value: the same package on two nodes whose variables differ lists the same
     * hash for every flow (the same content hashes the same on any node), while each node's producers got their own value.
     */
    @Test
    void theFlowHashIsTheSameWhateverTheValue() throws Exception {
        Node first = node(Map.of("FLOTTILA_SECRET_PARTNER_URL", "rec://first-value", "FLOTTILA_SECRET_INBOUND", "first-inbound"));
        Node second = node(Map.of("FLOTTILA_SECRET_PARTNER_URL", "rec://second-value", "FLOTTILA_SECRET_INBOUND", "second-inbound"));
        PackageDraft p = shop("hashed", new JsonObject().put("tags", new JsonObject().put("key", "secret://inbound")), url("secret://partner-url"));

        first.deploy(p);
        second.deploy(p);

        for (Map<String, Object> options : first.produced("partner")) assertEquals("rec://first-value", options.get("url"));
        for (Map<String, Object> options : second.produced("partner")) assertEquals("rec://second-value", options.get("url"));
        assertEquals(Map.of("key", "second-inbound"), second.consumed("hashed/orders").get("tags"));
        Map<String, String> firstHashes = new LinkedHashMap<>();
        first.deployments.flows().forEach(f -> firstHashes.put(f.id(), f.hash()));
        Map<String, String> secondHashes = new LinkedHashMap<>();
        second.deployments.flows().forEach(f -> secondHashes.put(f.id(), f.hash()));
        assertEquals(firstHashes, secondHashes, "one hash per flow, whatever the node's values");
        assertEquals(Set.of("hashed/orders", "hashed/audit", "hashed/ping"), firstHashes.keySet(), firstHashes.toString());
        assertNotEquals(firstHashes.get("hashed/orders"), firstHashes.get("hashed/ping"), "fixture: the hashes are the flows' own");
    }
}
