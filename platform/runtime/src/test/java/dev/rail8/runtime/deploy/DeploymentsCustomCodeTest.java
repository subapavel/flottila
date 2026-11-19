package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Cache;
import dev.rail8.api.Component;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.NackException;
import dev.rail8.api.Runtime;
import dev.rail8.api.Secret;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.components.http.HttpComponent;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Page;
import dev.rail8.core.record.Snapshot;
import dev.rail8.core.record.StepLog;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.sample.HmacSignPlugin;
import dev.rail8.runtime.sample.HmacSignPlugin.HmacSign;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Custom code on a node, deployed as a package. The sample plugin step {@code hmac-sign} takes the secret its config names from the
 * node's {@code RAIL8_SECRET_*} variables and the node's cache, and signs each message of a deployed flow; a secret that is not set
 * refuses the deploy, naming the step, the key and the variable. A step that fails with a secret's value in its failure shows only
 * the reference: in the answer the sender gets, in the recorded step, in the parked row and in the node's log. The line the sample
 * step logs is kept in the message's history, and so is a line a step logs through SLF4J, with only the reference of a secret's
 * value. The node's cache is shared by the steps of one type in one package, and kept across a redeploy.
 */
@Timeout(60)
class DeploymentsCustomCodeTest {

    static final String VALUE = "partner-hmac-value-4711";
    static final String REFERENCE = "secret://partner-hmac";

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger rail8 = Logger.getLogger("dev.rail8");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    Deployments deployments;

    /** A step that takes the secret its option {@code key} names and fails with its value in a retryable NACK. */
    static final StepFactory LEAK = new StepFactory() {
        @Override public String type() { return "leak"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Stage create(StepConfig step, StepResources resources) {
            Secret secret = resources.services().secret(step, "key");
            return Stage.of(e -> { throw new NackException("the partner refused the signature of " + secret.value(), true); });
        }
    };

    /** A step that takes the secret its option {@code key} names and logs its value at WARNING through SLF4J. */
    static final StepFactory TELL = new StepFactory() {
        @Override public String type() { return "tell"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Stage create(StepConfig step, StepResources resources) {
            Secret secret = resources.services().secret(step, "key");
            org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("partner.client");
            return Stage.of(e -> { log.warn("calling the partner with {}", secret.value()); return e; });
        }
    };

    /** How often a {@link #counting} step's loader has run, over every type and package. */
    final AtomicInteger loads = new AtomicInteger();

    /** A step of {@code type} that asks its cache for the key {@code token}; its loader counts in {@link #loads}. */
    StepFactory counting(String type) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources resources) {
                Cache cache = resources.services().cache();
                return Stage.of(e -> e.withHeader("x-token",
                        cache.get("token", () -> new Cache.Entry("token-" + loads.incrementAndGet(), Duration.ofHours(1)))));
            }
        };
    }

    @BeforeEach
    void listen() {
        capture.setLevel(Level.ALL);
        rail8.addHandler(capture);
    }

    @AfterEach
    void close() throws Exception {
        try {
            doors.releaseAll();
            if (deployments != null) OnVirtualThread.call(() -> { deployments.close(); return null; });
        } finally {
            rail8.removeHandler(capture);
        }
    }

    Deployments node(Map<String, String> extra) {
        return node(extra, false);
    }

    /** A node whose env adds {@code extra}; with {@code placeholders}, its secrets are those of {@code rail8 validate} and {@code rail8 test}. */
    Deployments node(Map<String, String> extra, boolean placeholders) {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", DeploymentsTest.NODE);
        env.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        env.put("RAIL8_STOP_DEADLINE", DeploymentsTest.STOP_DEADLINE.toString());
        env.put("RAIL8_BLOB_DURABLE_MAX", Long.toString(DeploymentsTest.DURABLE_MAX));
        env.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(DeploymentsTest.MAX_PACKAGE));
        env.put("RAIL8_RETRIES_COUNT", "0");
        env.putAll(extra);
        Map<String, Component> components = Map.of(Doors.SCHEME, doors);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        List<StepFactory> steps = new ArrayList<>(new HmacSignPlugin().steps());
        steps.add(LEAK);
        steps.add(TELL);
        steps.add(counting("count"));
        steps.add(counting("count-too"));
        NodeConfig config = placeholders ? NodeConfig.from(env).withSecrets(Secrets.placeholder()) : NodeConfig.from(env);
        deployments = new Deployments(config, runtime, components, rt.clock(), rt.timer(), steps, List.of(), messages, dlq,
                new CountingQueue(rt.clock()), () -> 0L);
        return deployments;
    }

    /** Package {@code shop} 1.0.0: flow {@code orders} ({@code mode}) from {@code orders-in}, step {@code type} with {@code key}, then to the partner. */
    static PackageDraft shop(String mode, String type, String key) {
        JsonArray steps = new JsonArray().add(new JsonObject().put("type", type).put("name", "sign").put("key", key)).addAll(PackageDraft.toPartner());
        return PackageDraft.of("shop", "1.0.0")
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("orders", "1.0.0", mode, "orders-in", 4096, steps);
    }

    void deploy(PackageDraft p) throws Exception {
        OnVirtualThread.call(() -> deployments.deploy(p.name(), p.folderIn(tmp.resolve("uploads"))));
    }

    /** Package {@code name} @ {@code version}: flows {@code a} and {@code b} with a {@code count} step, flow {@code c} with a {@code count-too} step. */
    static PackageDraft counters(String name, String version) {
        PackageDraft p = PackageDraft.of(name, version).endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String flow : List.of("a", "b", "c")) {
            JsonArray steps = new JsonArray().add(new JsonObject().put("type", flow.equals("c") ? "count-too" : "count").put("name", "count"));
            p.endpoint(flow + "-in", "in", new JsonObject().put("path", "/" + flow))
                    .flow(flow, version, "sync", flow + "-in", 4096, steps.addAll(PackageDraft.toPartner()));
            p.flowDoc(flow).put("forwardHeaders", new JsonArray().add("x-token"));
        }
        return p;
    }

    /** Sends one message to {@code flow} ({@code <package>/<id>}) and returns the token its step put on it. */
    String send(String flow) throws Exception {
        Envelope e = rt.ingress(flow, Headers.empty(), Buffer.buffer("<x/>"));
        assertTrue(within(doors.door(flow).deliver(e), "the answer of " + flow) instanceof Ack.Reply);
        return doors.partner().sent().getLast().header("x-token").orElseThrow();
    }

    @Test
    void theSampleStepSignsTheMessagesOfADeployedFlow() throws Exception {
        node(Map.of("RAIL8_SECRET_PARTNER_HMAC", VALUE));
        PackageDraft shop = shop("sync", HmacSign.TYPE, REFERENCE);
        shop.flowDoc("orders").put("forwardHeaders", new JsonArray().add(HmacSign.HEADER));   // the partner gets the signature
        deploy(shop);
        Envelope e = rt.ingress("shop/orders", Headers.empty(), Buffer.buffer("<order id='4711'/>"));

        assertTrue(within(doors.door("shop/orders").deliver(e), "the answer") instanceof Ack.Reply);

        Envelope sent = doors.partner().sent().getFirst();
        byte[] dayKey = HmacSign.hmac(VALUE.getBytes(StandardCharsets.UTF_8), "2026-01-01");
        assertEquals(Base64.getEncoder().encodeToString(HmacSign.hmac(dayKey, "<order id='4711'/>".getBytes(StandardCharsets.UTF_8))),
                sent.header(HmacSign.HEADER).orElseThrow());
        assertEquals("2026-01-01", sent.key(HmacSign.KEY).orElseThrow());
        List<Snapshot> history = messages.timeline(e.id()).await();
        assertTrue(history.stream().anyMatch(s -> s.stepName().equals("sign") && s.outcome() == Snapshot.Outcome.OK), history.toString());
        assertNotLogged(VALUE);
    }

    @Test
    void theSampleStepsLineIsInTheMessagesHistory() throws Exception {
        node(Map.of("RAIL8_SECRET_PARTNER_HMAC", VALUE));
        deploy(shop("sync", HmacSign.TYPE, REFERENCE));
        Envelope e = rt.ingress("shop/orders", Headers.empty(), Buffer.buffer("<order id='4711'/>"));
        assertTrue(within(doors.door("shop/orders").deliver(e), "the answer") instanceof Ack.Reply);

        Snapshot sign = messages.timeline(e.id()).await().stream().filter(s -> s.stepName().equals("sign")).findFirst().orElseThrow();
        assertEquals(new StepLog(List.of(new StepLog.Line(rt.clock().instant(), System.Logger.Level.INFO, HmacSign.class.getName(),
                "signed 18 bytes with the key of 2026-01-01", Optional.empty())), 0), sign.log());
    }

    @Test
    void aLineLoggedThroughSlf4jIsInTheHistoryWithOnlyTheSecretsReference() throws Exception {
        node(Map.of("RAIL8_SECRET_PARTNER_HMAC", VALUE));
        deploy(shop("sync", "tell", REFERENCE));
        Envelope e = rt.ingress("shop/orders", Headers.empty(), Buffer.buffer("<order/>"));
        assertTrue(within(doors.door("shop/orders").deliver(e), "the answer") instanceof Ack.Reply);

        Snapshot tell = messages.timeline(e.id()).await().stream().filter(s -> s.stepName().equals("sign")).findFirst().orElseThrow();
        assertEquals(List.of(new StepLog.Line(rt.clock().instant(), System.Logger.Level.WARNING, "partner.client", "calling the partner with " + REFERENCE,
                Optional.empty())), tell.log().lines());
    }

    @Test
    void offTheNodeEverySecretIsThePlaceholder() throws Exception {
        node(Map.of(), true);
        PackageDraft shop = shop("sync", HmacSign.TYPE, REFERENCE);
        shop.flowDoc("orders").put("forwardHeaders", new JsonArray().add(HmacSign.HEADER));
        deploy(shop);
        Envelope e = rt.ingress("shop/orders", Headers.empty(), Buffer.buffer("<x/>"));
        within(doors.door("shop/orders").deliver(e), "the answer");
        byte[] dayKey = HmacSign.hmac(Secrets.PLACEHOLDER.getBytes(StandardCharsets.UTF_8), "2026-01-01");
        assertEquals(Base64.getEncoder().encodeToString(HmacSign.hmac(dayKey, "<x/>".getBytes(StandardCharsets.UTF_8))),
                doors.partner().sent().getFirst().header(HmacSign.HEADER).orElseThrow());
    }

    @Test
    void aSecretThatIsNotSetRefusesTheDeployNamingTheStepTheKeyAndTheVariable() throws Exception {
        node(Map.of());
        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> deploy(shop("sync", HmacSign.TYPE, REFERENCE)));
        assertEquals(Kind.INVALID, refused.kind(), refused.problems().toString());
        assertTrue(refused.problems().stream().anyMatch(l -> l.startsWith("flow 'orders' does not build: ") && l.contains("step 'sign'")
                && l.contains("'key'") && l.contains("RAIL8_SECRET_PARTNER_HMAC")), refused.problems().toString());
        assertEquals(List.of(), doors.doors(), "no consumer built");
    }

    @Test
    void aSyncFailureShowsOnlyTheReferenceInTheAnswerTheRecordedStepAndTheLog() throws Exception {
        node(Map.of("RAIL8_SECRET_PARTNER_HMAC", VALUE));
        deploy(shop("sync", "leak", REFERENCE));
        Envelope e = rt.ingress("shop/orders", Headers.empty(), Buffer.buffer("<order/>"));

        Future<Ack> answer = doors.door("shop/orders").deliver(e);
        Throwable failure = assertThrows(Exception.class, () -> within(answer, "the answer"));
        String body = String.valueOf(failure.getMessage());   // what the http door answers the sender with
        assertFalse(body.contains(VALUE), body);
        assertTrue(body.contains(REFERENCE), body);
        assertEquals(503, HttpComponent.status(null, failure), "the retry answer is kept: " + failure);

        Snapshot failed = messages.timeline(e.id()).await().stream().filter(s -> s.outcome() == Snapshot.Outcome.FAILED).findFirst().orElseThrow();
        assertFalse(failed.error().orElseThrow().contains(VALUE), failed.error().orElseThrow());
        assertTrue(failed.error().orElseThrow().contains(REFERENCE), failed.error().orElseThrow());
        assertNotLogged(VALUE);
    }

    @Test
    void anAtLeastOnceFailureIsParkedWithOnlyTheReference() throws Exception {
        node(Map.of("RAIL8_SECRET_PARTNER_HMAC", VALUE));
        deploy(shop("at-least-once", "leak", REFERENCE));
        Envelope e = rt.ingress("shop/orders", Headers.empty(), Buffer.buffer("<order/>"));
        assertEquals(new Ack.Accepted(e.id()), within(doors.door("shop/orders").deliver(e), "the answer"));

        until(() -> messages.timeline(e.id()).await().stream().anyMatch(s -> s.outcome() == Snapshot.Outcome.FAILED), "the run failed");
        rt.clock().advance(Duration.ofSeconds(61));
        until(() -> !parked().isEmpty(), "parked");

        Parked row = parked().getFirst();
        assertFalse(row.error().contains(VALUE), row.error());
        assertTrue(row.error().contains(REFERENCE), row.error());
        assertNotLogged(VALUE);
    }

    @Test
    void theCacheIsSharedByTheStepsOfOneTypeInOnePackageAndKeptAcrossARedeploy() throws Exception {
        node(Map.of());
        deploy(counters("shop", "1.0.0"));
        assertEquals("token-1", send("shop/a"));
        assertEquals("token-1", send("shop/b"), "two flows of one package share the key of their step type");
        assertEquals("token-2", send("shop/c"), "another step type has its own key");

        deploy(counters("shop", "1.0.1"));   // a new version of every flow: each one is built again
        assertEquals("token-1", send("shop/a"), "the entry is kept across a redeploy of the package");

        deploy(counters("other", "1.0.0"));
        assertEquals("token-3", send("other/a"), "another package has its own key");
        assertEquals(3, loads.get());
    }

    List<Parked> parked() {
        return dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items();
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 10 s: " + what);
            Thread.onSpinWait();
        }
    }

    void assertNotLogged(String value) {
        for (LogRecord r : logged) {
            for (String text : DeploymentsSecretsTest.texts(r)) assertFalse(text.contains(value), "a log line carries the secret's value: " + text);
        }
    }
}
