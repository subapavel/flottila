package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.dlq.Parked;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.Replay;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.schemas.Documents;
import io.flottila.schemas.Schemas;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BACKLOG P1-05 acceptance, GATE check 7 data: the demo flow as at-least-once — partner down → retries per the flow's policy →
 * DLQ row by reference in Postgres; the retry delivers once the partner is back; replay from step n of a delivered message
 * reproduces the run as a new message marked REPLAY_OF.
 */
class InvoiceSyncReplayIT {

    static final Path DEMO = Path.of("../packages/demo");
    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    static Buffer invoice;
    static Map<String, EndpointConfig> endpoints;
    static JsonObject flowDoc;

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent http = new RecordingComponent("http");
    PgMessageStore store;
    PgDlq dlq;

    @BeforeAll
    static void start() throws IOException {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        JsonObject sync = Documents.read(DEMO.resolve("flows/invoice-sync.yaml"));
        flowDoc = new JsonObject(new HashMap<>(sync.getMap())).put("delivery", Map.of("mode", "at-least-once"));   // the same flow, parking instead of answering
        assertTrue(Schemas.FLOW.validate(flowDoc).valid());
        endpoints = new HashMap<>();
        try (Stream<Path> files = Files.list(DEMO.resolve("endpoints"))) {
            for (Path file : files.toList()) {
                JsonObject ep = Documents.read(file);
                endpoints.put(ep.getString("id"), new EndpointConfig(ep.getString("id"), URI.create(ep.getString("scheme") + "://" + ep.getString("id")), ep.getJsonObject("config").getMap()));
            }
        }
        invoice = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")));
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() {
        pool.query("truncate message_keys, messages, payloads, dlq").execute().await();
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        dlq = new PgDlq(pool);
        rt.withComponent(http);
    }

    @AfterEach
    void close() { store.close(); }

    FlowBuilder builder() {
        SaxonXml xml = new SaxonXml();
        return new FlowBuilder(rt.runtime(), Stream.concat(new XsdValidate().steps().stream(), xml.steps().stream()).toList(), xml.languages(), endpoints).recording(store, Meter.system()).dlq(dlq);
    }

    /** Runs on a virtual thread (retries park it) and drives the fake clock through the backoff. */
    Object run(Supplier<Envelope> body) {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> { try { outcome.set(body.get()); } catch (Throwable x) { outcome.set(x); } });
        while (t.isAlive()) { if (rt.timer().pending() > 0) rt.clock().advance(rt.timer().nextDelay()); Thread.onSpinWait(); Thread.yield(); }
        return outcome.get();
    }

    @Test
    void partnerDownParksByReferenceAndTheRetryDeliversOnceThePartnerIsBack() {
        FlowDefinition def = FlowDefinition.from(flowDoc.getMap());
        Pipeline p = builder().build(def, DEMO.resolve("flows"));
        http.failNext(4, () -> new IllegalStateException("503 from partner"));
        Envelope in = rt.ingress("invoice-sync", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), invoice);
        Object outcome = run(() -> p.apply(in));
        assertTrue(outcome instanceof ParkedException, String.valueOf(outcome));
        Parked parked = ((ParkedException) outcome).parked();
        assertEquals(4, http.sentTo("partner-domestic").size(), "one try and three retries before the park");
        assertEquals(List.of(parked), dlq.list().await(), "durable in Postgres before the failure surfaced");
        assertEquals(in.id(), parked.messageId());
        assertEquals(5, parked.step());
        assertEquals("partner", parked.stepName());
        assertEquals(BlobKey.body(in.id(), 4), parked.bodyKey(), "the mapped body by reference, no copy");
        assertTrue(parked.error().contains("503 from partner"), parked.error());
        assertEquals("4711", parked.keys().get("invoiceId"));
        assertEquals("domestic", parked.props().asMap().get("PARTNER"));
        store.flush().await();
        List<Snapshot> original = store.timeline(in.id()).await();
        assertEquals(Snapshot.Outcome.FAILED, original.get(5).outcome());
        Replay replay = builder().replay(def, DEMO.resolve("flows"));
        Object delivered = run(() -> replay.retry(in.id()));
        assertTrue(delivered instanceof Envelope, String.valueOf(delivered));
        Envelope out = (Envelope) delivered;
        assertNotEquals(in.id(), out.id());
        assertEquals(Optional.of(in.id()), out.get(Envelope.REPLAY_OF));
        assertEquals(5, out.step());
        assertEquals(5, http.sentTo("partner-domestic").size(), "exactly one more delivery: the parked step only");
        assertTrue(http.sentTo("partner-domestic").get(4).body().whole().toString().contains("<PartnerInvoice number=\"4711\""), "the parked mapped body went out");
        assertEquals(List.of(), dlq.list().await(), "GATE check 7: the retry delivered, the row is gone");
        store.flush().await();
        List<Snapshot> retried = store.timeline(out.id()).await();
        assertEquals(List.of(4, 5), retried.stream().map(Snapshot::step).toList());
        assertEquals(List.of(Replay.REPLAY_INPUT, "partner"), retried.stream().map(Snapshot::stepName).toList());
        assertEquals(List.of(in.id(), out.id()), store.findByKey("invoiceId", "4711").await());
    }

    @Test
    void replayFromStepFourOfADeliveredMessageMapsAndSendsAgainAsANewMessage() {
        FlowDefinition def = FlowDefinition.from(flowDoc.getMap());
        Envelope in = rt.ingress("invoice-sync", Headers.empty(), invoice);
        Envelope first = builder().build(def, DEMO.resolve("flows")).apply(in);
        store.flush().await();
        Envelope again = builder().replay(def, DEMO.resolve("flows")).from(in.id(), 4);
        assertEquals(2, http.sentTo("partner-domestic").size());
        assertEquals(http.sentTo("partner-domestic").get(0).body().hash(), http.sentTo("partner-domestic").get(1).body().hash(), "the same mapping of the same step-3 body");
        assertEquals(Optional.of(in.id()), again.get(Envelope.REPLAY_OF));
        assertEquals(first.body().hash(), again.body().hash(), "the same ack came back");
        store.flush().await();
        assertEquals(List.of(3, 4, 5), store.timeline(again.id()).await().stream().map(Snapshot::step).toList(), "input, xslt, to");
        assertEquals(List.of(), dlq.list().await(), "nothing parked");
    }
}
