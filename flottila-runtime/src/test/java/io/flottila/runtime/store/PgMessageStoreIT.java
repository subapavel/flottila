package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Vertx;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres MessageStore (ARCHITECTURE §5, §7, §8): snapshots through the P0-06 batcher into `messages` / `payloads` /
 * `message_keys` (V1 + V2), the timeline back by message id, messages by business key — every field round-trips.
 */
class PgMessageStoreIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    PgMessageStore store;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        Migrations.Result r = Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        assertEquals("3", r.schemaVersion(), "V2 adds the snapshot columns, V3 the DLQ columns");
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
    }

    @AfterEach
    void close() { store.close(); }

    static Snapshot snap(String id, int step, String name, Snapshot.Outcome outcome, Optional<String> error, Keys keys, Optional<BlobKey> bodyKey) {
        return new Snapshot(id, "invoice-sync", step, name, outcome, error, Instant.parse("2026-01-01T00:00:00.123456Z").plusSeconds(step), 1_500_000L * step,
                step == 2 ? Snapshot.CPU_UNKNOWN : 20_000L * step, Headers.empty().with("content-type", "application/xml").with("n", String.valueOf(step)),
                Props.empty().with(new Key<String>("PARTNER"), "domestic").with(new Key<Integer>("splitIndex"), step).with(new Key<Boolean>("flag"), true),
                keys, new Hash("sha256-64", -42L + step), 187 + step, bodyKey, Map.of("flow", "invoice-sync@0.1.0"));
    }

    @Test
    void everyFieldRoundTripsAndTheTimelineIsOrderedByStep() {
        Keys keys = Keys.empty().with("invoiceId", "4711");
        store.record(snap("m-1", 2, "route", Snapshot.Outcome.OK, Optional.empty(), keys, Optional.of(BlobKey.body("m-1", 0)))).await();
        store.record(snap("m-1", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.of(BlobKey.body("m-1", 0)))).await();
        store.record(snap("m-1", 1, "validate", Snapshot.Outcome.FAILED, Optional.of("validation failed: /Invoice/Id"), keys, Optional.empty())).await();
        List<Snapshot> t = store.timeline("m-1").await();
        assertEquals(List.of(0, 1, 2), t.stream().map(Snapshot::step).toList());
        assertEquals(snap("m-1", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.of(BlobKey.body("m-1", 0))), t.get(0));
        assertEquals(snap("m-1", 1, "validate", Snapshot.Outcome.FAILED, Optional.of("validation failed: /Invoice/Id"), keys, Optional.empty()), t.get(1), "metadata-only: no body key, hash and size kept; cpu unknown stays unknown");
        assertEquals(snap("m-1", 2, "route", Snapshot.Outcome.OK, Optional.empty(), keys, Optional.of(BlobKey.body("m-1", 0))), t.get(2));
        assertEquals(List.of(), store.timeline("nobody").await());
        long payloads = pool.query("select count(*) from payloads").execute().await().iterator().next().getLong(0);
        assertEquals(3, payloads, "one payloads row per distinct hash, no bytes in it");
        assertEquals(List.of("m-1"), store.findByKey("invoiceId", "4711").await(), "once, although two steps carry the key");
        Snapshot odd = new Snapshot("m-odd", "f", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Instant.EPOCH, 0, 0, Headers.empty(),
                Props.empty().with(new Key<Duration>("deadline"), Duration.ofSeconds(3)), Keys.empty(), new Hash("sha256-64", 1), 1, Optional.empty(), Map.of());
        store.record(odd).await();
        assertEquals("PT3S", store.timeline("m-odd").await().get(0).props().asMap().get("deadline"), "a prop that is not JSON-native is kept by its string form");
    }

    @Test
    void aBatchCarriesManyMessagesAndDuplicatesAreIgnored() {
        List<io.vertx.core.Future<Void>> saves = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            saves.add(store.record(snap("m-" + i, 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty().with("invoiceId", "4711").with("orderNo", "o-" + i), Optional.of(BlobKey.body("m-" + i, 0)))));
            saves.add(store.record(snap("m-" + i, 1, "to", Snapshot.Outcome.FILTERED, Optional.empty(), Keys.empty(), Optional.empty())));
        }
        store.flush().await();   // batches commit concurrently (D-044): only a committed row is deterministically "first"
        saves.add(store.record(snap("m-7", 1, "to-again", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.empty())));
        store.flush().await();
        assertTrue(saves.stream().allMatch(io.vertx.core.Future::succeeded));
        assertEquals("to", store.timeline("m-7").await().get(1).stepName(), "a duplicate (id, step) after the committed row is ignored: ON CONFLICT DO NOTHING");
        assertEquals(50, store.findByKey("invoiceId", "4711").await().size());
        assertEquals(List.of("m-9"), store.findByKey("orderNo", "o-9").await());
        long rows = pool.query("select count(*) from messages").execute().await().iterator().next().getLong(0);
        assertEquals(100, rows);
        assertEquals(0, store.failed(), "no commit failed");
    }

    @Test
    void closeDrainsWhatIsQueuedAndAFailedCommitIsCounted() {
        io.vertx.core.Future<Void> queued = store.record(snap("m-x", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.empty()));
        store.close();
        assertTrue(queued.succeeded(), "close flushes");
        assertEquals(1, store.timeline("m-x").await().size());
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        pool.query("alter table messages add constraint no_m_bad check (id <> 'm-bad')").execute().await();
        try {
            io.vertx.core.Future<Void> bad = store.record(snap("m-bad", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.empty()));
            store.flush().otherwiseEmpty().await();   // the flush reports the failure too; the snapshot's own future is what the test checks
            assertTrue(bad.failed());
            assertTrue(bad.cause().getMessage().contains("no_m_bad"), bad.cause().getMessage());
            assertEquals(1, store.failed(), "the message path does not await the write; the counter is what monitoring sees");
        } finally {
            pool.query("alter table messages drop constraint no_m_bad").execute().await();
        }
    }
}
