package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BlobStore;
import io.flottila.api.BodyLostException;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Parked;
import io.flottila.runtime.blob.RemoteBodyException;
import io.flottila.runtime.blob.TieredBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static io.flottila.runtime.journal.JournalWorld.bodyRef;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.opened;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.workRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c3 packet item 4 (CORE-3 "Review r2 additions"): a body given up by an accepted {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} —
 * its {@code body_refs} row, or for an ingress key its message's work row, marked {@code lost_at} — reads as "body lost by
 * ACCEPT_LOSS": {@code read}, {@code open} and {@code exists} fail with a {@link BodyLostException} naming the key, never a
 * {@link BlobNotFoundException} (it was not removed: it was given up) and never a retryable failure (no other node or later try
 * has it); also when its segment row is gone. Losses are accepted at start, before any store indexes a key, so the reads are
 * made by a store whose location index is empty ({@link JournalWorld#restartedStore()}); the {@code lost_at} marks are made by
 * SQL here (the start's marking is NodeJournalIT's). Witness: an unmarked body beside it reads its bytes, and one in the same
 * gone segment reads as removed.
 *
 * <p>The API assumed (CORE-4c3, test-author): {@code io.flottila.api.BodyLostException(BlobKey)} — {@code key()}, its message
 * carrying "body lost by ACCEPT_LOSS" — a {@link RuntimeException} that is no {@link BlobNotFoundException}; V13's
 * {@code work.lost_at}.
 */
@Timeout(120)
class LostBodyReadIT {

    static final String LOST = "body lost by ACCEPT_LOSS";
    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    JournalWorld a;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() throws Exception {
        db = CaseDatabase.create(pg, vertx, "lost_read_").migrated();
        a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a");
    }

    @AfterEach
    void close() throws Exception {
        if (a != null) a.close();
        db.close();
    }

    Envelope saved(String id, Buffer bytes, boolean keepInput) {
        Envelope in = a.arrive(id, bytes);
        Future<String> saved = a.save(in, keepInput, JournalWorld.undecided());
        assertTrue(saved.succeeded(), "fixture: saved " + saved.cause());
        return in;
    }

    void sql(String statement, Object... params) {
        db.pool().preparedQuery(statement).execute(Tuple.wrap(params)).await();
    }

    /** What a read answered, without dumping bytes into the report. */
    static String describe(Object answer) {
        if (answer instanceof io.vertx.core.buffer.Buffer b) return "a buffer of " + b.length() + " bytes";
        if (answer instanceof byte[] b) return b.length + " bytes";
        return String.valueOf(answer);
    }

    /** {@code f} failed with a {@link BodyLostException} naming {@code key}, saying so — not the miss signal, not retryable. */
    static void lost(Future<?> f, BlobKey key, String what) {
        assertTrue(f.failed(), what + ": a lost body is no answer: " + (f.succeeded() ? describe(f.result()) : null));
        Throwable cause = f.cause();
        assertFalse(cause instanceof BlobNotFoundException, what + ": never BlobNotFound (it was given up, not removed): " + causes(cause));
        assertFalse(cause instanceof RemoteBodyException || cause instanceof JournalFailedException || cause instanceof JournalBusyException,
                what + ": never retryable: " + causes(cause));
        BodyLostException e = assertInstanceOf(BodyLostException.class, cause, what + ": " + causes(cause));
        assertEquals(key, e.key(), what + ": names the key");
        assertTrue(String.valueOf(e.getMessage()).contains(LOST), what + ": says why: " + e.getMessage());
    }

    static void lostEverywhere(BlobStore store, BlobKey key, String what) {
        lost(settled(store.read(key)), key, what + " (read)");
        lost(opened(store, key), key, what + " (open)");
        lost(settled(store.exists(key)), key, what + " (exists)");
    }

    @Test
    void aLostKeptRefReadsBodyLostNeverNotFound() {
        Envelope gone = saved("m-lost", bytes(3_000, 1), true);
        Envelope live = saved("m-live", bytes(3_000, 2), true);
        assertNotNull(bodyRef(db.pool(), gone.body().key()), "fixture: a kept ref");
        sql("update body_refs set lost_at = now() where message_id = $1", gone.id());
        TieredBlobStore store = a.restartedStore();

        lostEverywhere(store, gone.body().key(), "a lost kept ref");

        assertEquals(bytes(3_000, 2), settled(store.read(live.body().key())).result(), "witness: the unmarked ref beside it reads");
    }

    @Test
    void aLostWorkRowReadsBodyLost() {
        Envelope gone = saved("m-work", bytes(2_000, 3), false);
        Envelope live = saved("m-work-live", bytes(2_000, 4), false);
        assertNull(bodyRef(db.pool(), gone.body().key()), "fixture: no kept ref: the work row alone names the body");
        assertNotNull(workRef(db.pool(), gone.id()), "fixture: the work row names it");
        sql("update work set lost_at = now() where id = $1", gone.id());
        TieredBlobStore store = a.restartedStore();

        lostEverywhere(store, gone.body().key(), "a lost work row");

        assertEquals(bytes(2_000, 4), settled(store.read(live.body().key())).result(), "witness: an unmarked work row reads");
    }

    /** Lost wins over removed: a lost ref whose segment row went is still "body lost", while an unmarked one there is removed. */
    @Test
    void aLostRefWhoseSegmentRowIsGoneIsStillLost() {
        Envelope gone = saved("m-lost-swept", bytes(1_500, 5), true);
        Envelope removed = saved("m-swept", bytes(1_500, 6), true);
        JournalWorld.Ref ref = bodyRef(db.pool(), gone.body().key());
        assertEquals(ref.segment(), bodyRef(db.pool(), removed.body().key()).segment(), "fixture: both in one segment");
        sql("update body_refs set lost_at = now() where message_id = $1", gone.id());
        sql("delete from journal_segments where node = $1 and segment = $2", "node-a", ref.segment());
        TieredBlobStore store = a.restartedStore();

        lostEverywhere(store, gone.body().key(), "a lost ref in a segment whose row is gone");

        Future<Buffer> swept = settled(store.read(removed.body().key()));
        assertTrue(swept.failed() && swept.cause() instanceof BlobNotFoundException, "witness: the unmarked ref there reads as removed: " + causes(swept.cause()));
        assertEquals(Boolean.FALSE, settled(store.exists(removed.body().key())).result());
    }

    // CORE-4c3 review r1 (2): a lost source feeds no new pin. A persist of a key held nowhere locally (REFERENCE), an item
    // enqueued from a key's ref (ENQUEUE_FROM) and a park (PARK) each take a source row; a lost one counts as no source: the call
    // fails with a BodyLostException and inserts nothing. Witness: the same three calls on an unmarked body beside it succeed and
    // insert their rows, so "nothing inserted" is not the fixture's.

    static Parked parkedOn(String id, BlobKey body) {
        return new Parked(id, JournalWorld.FLOW, 1, "partner", "java.lang.IllegalStateException: 503 from partner (test)",
                Instant.parse("2026-11-04T10:00:00Z"), Headers.empty(), Props.empty(), Keys.empty(), new Hash("sha256-64", -7L), 3_000,
                body, Map.of("flow", JournalWorld.FLOW + "@0.1.0"));
    }

    long rows(String sql, Object... params) {
        return JournalWorld.count(db.pool(), sql, params);
    }

    /** The witness: persist, enqueueFrom and park of an unmarked body succeed and each inserts its row. */
    void sourcesFeed(TieredBlobStore store, BlobKey key, String next) {
        Future<Void> persisted = settled(store.persist(key));
        assertTrue(persisted.succeeded(), "witness: an unmarked source persists: " + causes(persisted.cause()));
        assertNotNull(bodyRef(db.pool(), key), "witness: the persist's ref is there");
        Future<Void> enqueued = settled(a.queue.enqueue(db.pool(), next, JournalWorld.FLOW, 1, null, key));
        assertTrue(enqueued.succeeded(), "witness: an unmarked ref feeds an item: " + causes(enqueued.cause()));
        assertEquals(1, rows("select count(*) from work where id = $1", next), "witness: the item is inserted");
        Future<Void> parked = settled(a.dlq.park(parkedOn(key.messageId(), key)));
        assertTrue(parked.succeeded(), "witness: an unmarked ref admits a park: " + causes(parked.cause()));
        assertEquals(1, rows("select count(*) from dlq where id = $1", key.messageId()), "witness: the DLQ row is inserted");
    }

    /** A kept ref marked lost beside an unmarked one; the store restarted (an empty index: no local copy). */
    record Sources(TieredBlobStore store, BlobKey lost, BlobKey live) { }

    Sources lostKeptRef() {
        Envelope gone = saved("m-src-lost", bytes(3_000, 7), true);
        Envelope live = saved("m-src-live", bytes(3_000, 8), true);
        assertNotNull(bodyRef(db.pool(), gone.body().key()), "fixture: a kept ref");
        sql("update body_refs set lost_at = now() where message_id = $1", gone.id());
        return new Sources(a.restartedStore(), gone.body().key(), live.body().key());
    }

    /** A work row marked lost beside an unmarked one, neither with a kept ref; the store restarted. */
    Sources lostWorkRow() {
        Envelope gone = saved("m-wsrc-lost", bytes(2_000, 9), false);
        Envelope live = saved("m-wsrc-live", bytes(2_000, 10), false);
        assertNull(bodyRef(db.pool(), gone.body().key()), "fixture: no kept ref: the work row alone names the body");
        assertNotNull(workRef(db.pool(), gone.id()), "fixture: the work row names it");
        sql("update work set lost_at = now() where id = $1", gone.id());
        return new Sources(a.restartedStore(), gone.body().key(), live.body().key());
    }

    @Test
    void aLostKeptRefFeedsNoPersist() {
        Sources s = lostKeptRef();
        long refs = rows("select count(*) from body_refs");

        lost(settled(s.store().persist(s.lost())), s.lost(), "persist of a lost kept ref, no local copy");
        assertEquals(refs, rows("select count(*) from body_refs"), "the persist inserts no ref");

        sourcesFeed(s.store(), s.live(), "m-src-live-next");
    }

    @Test
    void aLostKeptRefFeedsNoEnqueuedItem() {
        Sources s = lostKeptRef();
        long work = rows("select count(*) from work");

        lost(settled(a.queue.enqueue(db.pool(), "m-src-lost-next", JournalWorld.FLOW, 1, null, s.lost())), s.lost(), "an item enqueued from a lost ref");
        assertEquals(work, rows("select count(*) from work"), "no item inserted");

        sourcesFeed(s.store(), s.live(), "m-src-live-next");
    }

    @Test
    void aLostKeptRefAdmitsNoPark() {
        Sources s = lostKeptRef();

        lost(settled(a.dlq.park(parkedOn(s.lost().messageId(), s.lost()))), s.lost(), "a park on a lost ref");
        assertEquals(0, rows("select count(*) from dlq"), "no DLQ row inserted");

        sourcesFeed(s.store(), s.live(), "m-src-live-next");
    }

    @Test
    void aLostWorkRowFeedsNoPersist() {
        Sources s = lostWorkRow();

        lost(settled(s.store().persist(s.lost())), s.lost(), "persist of a lost work row's body, no local copy");
        assertEquals(0, rows("select count(*) from body_refs"), "the persist inserts no ref from a lost work row");

        sourcesFeed(s.store(), s.live(), "m-wsrc-live-next");
    }

    /**
     * ENQUEUE_FROM and PARK take a ref row only, so a lost work row reaches them through the ref its persist would insert: after
     * that persist (whatever it answers) an item enqueued from the key and a park on it fail and insert nothing (their failure
     * for a key with no ref is theirs, not pinned here).
     */
    @Test
    void aLostWorkRowFeedsNoItemOrParkAfterItsPersist() {
        Sources s = lostWorkRow();
        settled(s.store().persist(s.lost()));
        long work = rows("select count(*) from work");

        Future<Void> enqueued = settled(a.queue.enqueue(db.pool(), "m-wsrc-lost-next", JournalWorld.FLOW, 1, null, s.lost()));
        assertTrue(enqueued.failed(), "no item from a lost work row's body");
        assertEquals(work, rows("select count(*) from work"), "no item inserted");
        Future<Void> parked = settled(a.dlq.park(parkedOn(s.lost().messageId(), s.lost())));
        assertTrue(parked.failed(), "no park on a lost work row's body");
        assertEquals(0, rows("select count(*) from dlq"), "no DLQ row inserted");

        sourcesFeed(s.store(), s.live(), "m-wsrc-live-next");
    }
}
