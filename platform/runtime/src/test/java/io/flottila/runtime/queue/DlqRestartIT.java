package io.flottila.runtime.queue;

import io.flottila.api.EndpointConfig;
import io.flottila.api.Expressions;
import io.flottila.api.StepResources;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.core.dlq.Parked;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.store.PgDlq;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.PostgresImage;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14b P8 (D-135; FIX-138's case) on a real Postgres: {@code PgWorkQueue.restart(id, flow, key, k)} deletes the message's DLQ
 * row and inserts its work row — same id, step 1, attempts k, {@code max_attempts} k + 1 — in one transaction. An injected failure
 * of either half (a trigger refusing the work insert; one refusing the DLQ delete: whichever order the two run in, one of them
 * fails after the other ran) leaves the DLQ row and no work row; a restart of a message without a DLQ row (a second restart)
 * writes nothing. Then the worker runs the restarted row once, as attempt 2, under the same id: a partner that applied the first
 * send — its answer lost — and deduplicates by message id sees one effect, where {@code /admin/dlq/{id}/replay}'s new copy would be a second (FIX-138a).
 *
 * <p>Seams: {@link PgWorkQueue} and {@link PgDlq} over one pool; the worker (flow {@code orders}, {@code delivery.retries} count 0:
 * the first failure parks at once, attempt 1, so k + 1 = 2 lies past the flow's bound of 1) with core's in-memory message store
 * and blob store; the partner a {@link RecordingComponent} at the SPI boundary. The worker is never started: each round is a
 * {@link FlowWorker#poll} whose future completes once its runs settled.
 *
 * <p>O7: the worker's park keeps the item's key ({@code dlq.partition_key}, V17; {@code Parked.partitionKey()}), null / empty for an
 * unkeyed item.
 *
 * <p>The API assumed: see {@code WorkQueueContract} ({@code WorkQueue.restart}, {@code Item.maxAttempts}); Postgres: false, nothing
 * written, without a DLQ row of the id.
 */
@Timeout(120)
class DlqRestartIT {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final String NODE = "node-a";
    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool pool;

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    PgWorkQueue rows;
    PgDlq dlq;
    FlowWorker worker;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        pool.query("create function refuse_test() returns trigger language plpgsql as $$ begin raise exception 'injected failure (test)'; end $$")
                .execute().await();
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() {
        pool.query("truncate work, dlq, body_refs, journal_segments").execute().await();
        rt.withComponent(partner);
        rows = new PgWorkQueue(pool, NODE);
        dlq = new PgDlq(pool);
        Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        worker = new FlowBuilder(rt.runtime(), List.of(), List.of(), endpoints).recording(store, Meter.system()).queue(rows).dlq(dlq)
                .worker(flow(), Path.of(""), NODE, new WorkerPolicy(LEASE, Duration.ofDays(7)));
    }

    @AfterEach
    void close() throws Exception {
        pool.query("drop trigger if exists refuse_insert on work").execute().await();
        pool.query("drop trigger if exists refuse_delete on dlq").execute().await();
        worker.stop();
        worker.close().await(20, TimeUnit.SECONDS);
    }

    /** At-least-once {@code orders}: one {@code to partner}; {@code delivery.retries} count 0. */
    static FlowDefinition flow() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h", "retries", Map.of("count", 0, "interval", "1m")));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("steps", List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        return FlowDefinition.from(doc);
    }

    /** A message of {@code orders}: its ingress recorded, its body's kept ref on {@link #NODE}, its row queued. */
    Envelope queued() {
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        ParkedRows.keepRef(pool, in.id(), NODE);
        rows.enqueue(in.id(), "orders", 1, null).await();
        return in;
    }

    long workRows() {
        return ParkedRows.count(pool, "select count(*) from work");
    }

    Optional<Parked> dlqRow(String id) {
        return dlq.get(id).await();
    }

    /** {@code rows.restart(id, orders, null, k)} fails: the injected failure, nothing changed. */
    void refused(Envelope in, Parked before, String what) {
        Throwable failed = assertThrows(Throwable.class, () -> rows.restart(in.id(), "orders", null, 1).await(20, TimeUnit.SECONDS), what);
        assertTrue(String.valueOf(failed.getMessage()).contains("injected failure"), what + ": the restart failed with the injected failure: " + failed);
        assertEquals(Optional.of(before), dlqRow(in.id()), what + ": the DLQ row stays as it was");
        assertEquals(0, workRows(), what + ": no work row");
    }

    @Test
    void restartIsOneTransactionWithTheSameId() throws Exception {
        partner.failNext(1, () -> new NackException("applied, its answer lost (test)", true));
        Envelope in = queued();
        assertEquals(1, worker.poll().await(20, TimeUnit.SECONDS), "fixture: the first run");
        Parked before = dlqRow(in.id()).orElseThrow(() -> new AssertionError("count 0: the failure parks at once"));
        assertEquals(1, before.step(), "fixture: the worker parks the ingress at step 1");
        assertEquals(Optional.of(1), before.props().get(Envelope.ATTEMPT), "fixture: after attempt 1");
        assertEquals(0, workRows(), "fixture: its row completed by the park");
        assertEquals(1, partner.sent().size());
        assertEquals(Optional.empty(), before.partitionKey(), "O7: an unkeyed item's park keeps no key");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from dlq where id = $1 and partition_key is null", in.id()), "O7: dlq.partition_key null");

        pool.query("create trigger refuse_insert before insert on work for each row execute function refuse_test()").execute().await();
        refused(in, before, "the work insert refused");
        pool.query("drop trigger refuse_insert on work").execute().await();
        pool.query("create trigger refuse_delete before delete on dlq for each row execute function refuse_test()").execute().await();
        refused(in, before, "the DLQ delete refused");
        pool.query("drop trigger refuse_delete on dlq").execute().await();

        assertTrue(rows.restart(in.id(), "orders", null, 1).await(20, TimeUnit.SECONDS), "restarted");
        assertEquals(Optional.empty(), dlqRow(in.id()), "its DLQ row is gone");
        List<Row> written = new java.util.ArrayList<>();
        for (Row r : pool.query("select id, step, attempts, max_attempts from work").execute().await()) written.add(r);
        assertEquals(1, written.size(), "one work row");
        Row row = written.get(0);
        assertEquals(Arrays.asList(in.id(), 1, 1, 2), Arrays.asList(row.getString(0), row.getInteger(1), row.getInteger(2), row.getInteger(3)),
                "same id, step 1, handed out k = 1 times, max_attempts k + 1 = 2");
        assertFalse(rows.restart(in.id(), "orders", null, 1).await(20, TimeUnit.SECONDS), "no DLQ row any more: a second restart writes nothing");
        assertEquals(1, workRows(), "still one work row");

        assertEquals(1, worker.poll().await(20, TimeUnit.SECONDS), "the restarted row is claimed");
        assertEquals(2, partner.sent().size(), "sent once more");
        assertEquals(List.of(in.id(), in.id()), partner.sent().stream().map(Envelope::id).toList(), "under the same message id");
        assertEquals(Optional.of(2), partner.sent().get(1).get(Envelope.ATTEMPT), "as attempt k + 1 = 2, past the flow's bound of 1");
        assertEquals(1, partner.sent().stream().map(Envelope::id).distinct().count(), "a partner deduplicating by message id: one effect");
        assertEquals(0, workRows(), "the restarted run succeeded: completed");
        assertEquals(Optional.empty(), dlqRow(in.id()), "not parked again");
    }

    /** The test's {@code header} language: an expression is a header name, its value the header's (core's BuiltInStepsTest.HEADERS). */
    static final Expressions HEADER = new Expressions() {
        @Override public String language() { return "header"; }
        @Override public Predicate<Envelope> predicate(String expression, StepResources resources) {
            throw new UnsupportedOperationException("no conditions in this test");
        }
        @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) {
            return e -> e.header(expression);
        }
    };

    /** At-least-once {@code orders} keyed by {@code customer} (header {@code x-customer}), onFailure park; count 0. */
    static FlowDefinition keyedFlow() {
        Map<String, Object> doc = new HashMap<>(Map.of("schemaVersion", 1, "kind", "flow", "id", "orders", "version", "0.1.0"));
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h", "retries", Map.of("count", 0, "interval", "1m")));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("partitionBy", "customer");
        doc.put("onFailure", "park");
        doc.put("steps", List.of(Map.of("type", "extractKey", "name", "key", "language", "header", "key", "customer", "xpath", "x-customer"),
                Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        return FlowDefinition.from(doc);
    }

    /**
     * O7: the worker's park of a keyed item writes the item's key into {@code dlq.partition_key}, and {@code Parked.partitionKey()}
     * reads it back; the restart under it writes the work row under that key.
     */
    @Test
    void theWorkersParkKeepsTheItemsKey() throws Exception {
        worker.stop();
        Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        worker = new FlowBuilder(rt.runtime(), List.of(), List.of(HEADER), endpoints).recording(store, Meter.system()).queue(rows).dlq(dlq)
                .worker(keyedFlow(), Path.of(""), NODE, new WorkerPolicy(LEASE, Duration.ofDays(7)));
        partner.failNext(1, () -> new NackException("partner down (test)", true));
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml").with("x-customer", "c1"),
                Buffer.buffer("<order id=\"1\"/>"));
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        ParkedRows.keepRef(pool, in.id(), NODE);
        rows.enqueue(in.id(), "orders", 1, "c1").await();

        assertEquals(1, worker.poll().await(20, TimeUnit.SECONDS), "fixture: the keyed item's run");
        assertEquals(1, partner.sent().size(), "fixture: sent once and failed");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from dlq where id = $1 and partition_key = 'c1'", in.id()),
                "the park wrote the item's key c1");
        assertEquals(Optional.of("c1"), dlqRow(in.id()).orElseThrow().partitionKey(), "and the DLQ row reads it back");

        assertTrue(rows.restart(in.id(), "orders", "c1", 1).await(20, TimeUnit.SECONDS), "restarted under its key");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work where id = $1 and partition_key = 'c1' and max_attempts = 2", in.id()),
                "the work row under the kept key");
    }
}
