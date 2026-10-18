package io.flottila.runtime.store;

import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.schemas.Documents;
import io.flottila.schemas.Schemas;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BACKLOG P1-04 acceptance: the milestone-1 flow leaves a snapshot per step in Postgres, queryable by business key (GATE check 6). */
class InvoiceSyncRecordingIT {

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

    @BeforeAll
    static void start() throws IOException {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        flowDoc = Documents.read(DEMO.resolve("flows/invoice-sync.yaml"));
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
        pool.query("truncate message_keys, messages, payloads").execute().await();
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        rt.withComponent(http);
    }

    @AfterEach
    void close() { store.close(); }

    io.flottila.api.Pipeline pipeline(JsonObject doc) {
        SaxonXml xml = new SaxonXml();
        return new FlowBuilder(rt.runtime(), xml.steps(), xml.languages(), endpoints).recording(store, Meter.system())
                .build(FlowDefinition.from(doc.getMap()), DEMO.resolve("flows"));
    }

    @Test
    void theDomesticInvoiceLeavesSixSnapshotsAndIsFoundByItsInvoiceId() {
        Envelope in = rt.ingress("invoice-sync", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), invoice);
        Envelope out = pipeline(flowDoc).apply(in);
        store.flush().await();
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of("ingress", "validate", "extractKey", "route", "toPartner", "partner"), t.stream().map(Snapshot::stepName).toList());
        assertTrue(t.stream().allMatch(s -> s.outcome() == Snapshot.Outcome.OK && s.versions().equals(Map.of("flow", "invoice-sync@0.1.0"))));
        assertEquals(null, t.get(1).keys().get("invoiceId"), "no key before extractKey");
        assertEquals("4711", t.get(2).keys().get("invoiceId"));
        assertEquals("domestic", t.get(3).props().asMap().get("PARTNER"));
        assertEquals(in.body().hash(), t.get(3).bodyHash(), "route changed no body");
        assertTrue(!t.get(4).bodyHash().equals(in.body().hash()), "the xslt step wrote a new body");
        assertEquals(Optional.of(io.flottila.api.BlobKey.body(in.id(), 4)), t.get(4).bodyKey());
        assertEquals(out.body().hash(), t.get(5).bodyHash(), "the partner's reply is step 5's body");
        assertTrue(t.stream().allMatch(s -> s.wallNanos() >= 0 && s.cpuNanos() >= Snapshot.CPU_UNKNOWN));
        assertEquals(List.of(in.id()), store.findByKey("invoiceId", "4711").await(), "GATE check 6: found by business key");
        assertEquals(0, store.failed());
    }

    @Test
    void recordingEndsKeepsIngressAndTheLastStepAndAPartnerDownIsAFailedLastRow() {
        JsonObject ends = new JsonObject(new HashMap<>(flowDoc.getMap())).put("recording", Map.of("level", "ends"));   // nested values stay plain maps
        Envelope in = rt.ingress("invoice-sync", Headers.empty(), invoice);
        pipeline(ends).apply(in);
        store.flush().await();
        assertEquals(List.of(0, 5), store.timeline(in.id()).await().stream().map(Snapshot::step).toList());
        http.failNext(4, () -> new IllegalStateException("503 from partner"));
        Envelope down = rt.ingress("invoice-sync", Headers.empty(), invoice);
        io.flottila.api.Pipeline p = pipeline(flowDoc);
        java.util.concurrent.atomic.AtomicReference<Object> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> { try { outcome.set(p.apply(down)); } catch (Throwable x) { outcome.set(x); } });
        while (t.isAlive()) { if (rt.timer().pending() > 0) rt.clock().advance(rt.timer().nextDelay()); Thread.onSpinWait(); Thread.yield(); }
        assertTrue(outcome.get() instanceof IllegalStateException, "the partner stayed down: " + outcome.get());
        store.flush().await();
        List<Snapshot> timeline = store.timeline(down.id()).await();
        assertEquals(List.of(0, 1, 2, 3, 4, 5), timeline.stream().map(Snapshot::step).toList());
        assertEquals(Snapshot.Outcome.FAILED, timeline.get(5).outcome());
        assertTrue(timeline.get(5).error().orElseThrow().contains("503 from partner"));
        assertTrue(timeline.subList(0, 5).stream().allMatch(s -> s.outcome() == Snapshot.Outcome.OK));
    }
}
