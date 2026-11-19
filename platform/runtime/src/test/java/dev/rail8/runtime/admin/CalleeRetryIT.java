package dev.rail8.runtime.admin;

import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.Recorder;
import dev.rail8.core.record.RecordingPolicy;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.store.PgIntake;
import dev.rail8.runtime.store.Retention;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.json.JsonObject;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.admin.AdminPgFixture.BOUND;
import static dev.rail8.runtime.admin.AdminPgFixture.FLOW;
import static dev.rail8.runtime.admin.AdminPgFixture.MIB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry of a parked callee of a call between flows, end to end over Postgres. The callee's arrival is saved with its
 * caller's body, still keyed by the caller's message: the save keeps that body's ref under the caller's key, and the callee's
 * arrival record and its DLQ row name that key. Once the caller is done and its history is gone, {@code POST
 * /admin/dlq/{id}/retry} of the callee answers 202: its new work row points at the kept ref's place in the journal, and the
 * worker runs the callee once more on the caller's bytes, read by the caller's key.
 *
 * <p>Through {@link AdminPgFixture}, whose worker runs {@value AdminPgFixture#FLOW}, the callee's flow. Both arrivals are saved
 * through a {@link PgIntake} over the fixture's stores; the caller's flow, {@value #CALLER_FLOW}, has no worker.
 */
class CalleeRetryIT {

    static final String CALLER_FLOW = "shop/caller";
    static final String CALLER_BYTES = "<order>called</order>";

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    static HttpClientAgent client;

    @TempDir Path tmp;
    AdminPgFixture node;
    PgIntake intake;

    @BeforeAll
    static void up() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void down() {
        client.close().await();
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() throws Exception {
        node = new AdminPgFixture(vertx, pool, client, tmp);
        intake = new PgIntake(pool, node.blobs, node.messages, node.queue);
    }

    @AfterEach
    void stop() throws Exception {
        node.close();
    }

    @Test
    void aParkedCalleeWhoseCallerIsGoneIsRetriedOnTheCallersKeptBody() throws Exception {
        String caller = node.rt.ids().next();
        Body body = node.blobs.write(BlobKey.body(caller, 0), Buffer.buffer(CALLER_BYTES), MIB).await();
        assertEquals(caller, saved(Envelope.ingress(caller, CALLER_FLOW, headers(), body), CALLER_FLOW), "fixture: the caller's arrival saved");
        String callee = node.rt.ids().next();
        assertEquals(callee, saved(Envelope.ingress(callee, FLOW, headers(), body), FLOW), "fixture: the callee saved with the caller's body");
        BlobKey key = body.key();
        List<Object> place = keptRef(key);

        node.down.set(true);
        try {
            assertEquals(1, node.worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "fixture: the worker claims the callee");
        } finally {
            node.down.set(false);
        }
        Parked parked = node.dlq.get(callee).await().orElseThrow(() -> new AssertionError("fixture: the callee parked by its worker"));
        assertEquals(key, parked.bodyKey(), "fixture: the callee's DLQ row names the caller's key");
        assertEquals(0, node.count("select count(*) from work where id = $1", callee), "fixture: the callee's work row completed by the park");

        // the caller is done, and its history ages out
        List<WorkQueue.Item> items = node.queue.claim("w-caller", CALLER_FLOW, 1, Duration.ofSeconds(30)).await();
        assertEquals(1, items.size(), "fixture: the caller's item claimed");
        assertTrue(node.queue.complete(items.getFirst().seq(), items.getFirst().attempts()).await(), "fixture: the caller's item completed");
        pool.preparedQuery("update messages set created_at = now() - interval '30 days' where id = $1").execute(Tuple.of(caller)).await();
        pool.preparedQuery("update body_refs set created_at = now() - interval '30 days' where message_id = $1").execute(Tuple.of(caller)).await();
        retention();
        assertEquals(0, node.count("select count(*) from work where id = $1", caller), "fixture: the caller's work row is gone");
        assertEquals(0, node.count("select count(*) from messages where id = $1", caller), "fixture: the caller's history is gone");
        assertEquals(place, keptRef(key), "fixture: the callee's DLQ row keeps the caller's kept ref");

        AdminPgFixture.Answer retried = node.retry(callee);

        assertEquals(202, retried.status(), "the parked callee is retried: " + retried.body());
        assertEquals(new JsonObject().put("id", callee).put("attempt", 2), retried.json(), "under its own id, as attempt 2");
        assertEquals(Optional.empty(), node.dlq.get(callee).await(), "its DLQ row went");
        assertEquals(place, workRef(callee), "its new work row points at the kept ref's place in the journal");
        int before = node.runs.size();
        assertEquals(1, node.worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "the worker claims the retried callee");
        assertEquals(List.of(callee + "@2 " + CALLER_BYTES), node.runs.subList(before, node.runs.size()),
                "the callee runs once more, as attempt 2, on the bytes its caller handed over");
        assertEquals(Buffer.buffer(CALLER_BYTES), node.blobs.read(key).await(), "the callee's body reads by the caller's key");
    }

    /** {@code e}'s arrival saved at least once for {@code flow} through the intake, unkeyed, its flow keeping no input: its id. */
    String saved(Envelope e, String flow) throws Exception {
        Recorder recorder = new Recorder(new InMemoryMessageStore(), Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC), Meter.system(), Map.of());
        return intake.save(e, recorder.startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT), flow, null, false, Promise.<Void>promise().future())
                .await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    }

    static Headers headers() {
        return Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml");
    }

    /** One retention run, 7 days kept, over the fixture's pool. */
    static void retention() throws Exception {
        FakeClock clock = FakeClock.at(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        Retention job = new Retention(pool, clock, new FakeTimer(clock), new Retention.Config(Duration.ofDays(7), Duration.ofHours(1), 100));
        try {
            assertEquals("DONE", job.runOnce().await(BOUND.toSeconds(), TimeUnit.SECONDS).outcome().name(), "fixture: the retention run ends done");
        } finally {
            job.close().await(BOUND.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /** The place in the journal of {@code key}'s kept ref: node, segment, position, length. */
    static List<Object> keptRef(BlobKey key) {
        return place(pool.preparedQuery("select node, segment, position, length from body_refs where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(key.messageId(), key.step(), key.part())).await().iterator().next());
    }

    /** The place in the journal {@code id}'s one work row points at. */
    static List<Object> workRef(String id) {
        return place(pool.preparedQuery("select body_node, body_segment, body_position, body_length from work where id = $1")
                .execute(Tuple.of(id)).await().iterator().next());
    }

    static List<Object> place(Row r) {
        return List.of(r.getString(0), r.getLong(1), r.getLong(2), r.getLong(3));
    }
}
