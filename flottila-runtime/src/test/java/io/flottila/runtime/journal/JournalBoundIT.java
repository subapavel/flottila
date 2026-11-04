package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.runtime.blob.TieredBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static io.flottila.runtime.journal.JournalWorld.workRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4d packet item 3 (CORE-3 item 11): {@code FLOTTILA_JOURNAL_MAX_BYTES} bounds the journal — its segment files, its spill
 * and what is reserved and not yet written. An intake is taken while journal bytes + its record ≤ max − max/16 and refused one
 * byte above with a retryable {@link JournalFullException} (the delivery's retryable Nack: http 503 + Retry-After); a step-body
 * persist (a park's included) uses the reserve up to max and is refused beyond it, with an ERROR. A WARNING when 80 % of max is
 * crossed upward, an ERROR when refusals start; each once, and again after the bytes fell below and crossed again.
 *
 * <p>Max here is 1 MiB: the reserve edge is 983 040 bytes, 80 % is 838 860.8. Journal bytes at rest are counted on disk here —
 * every file under {@code segments/} and {@code spill/} — independently of the journal's own count; a record's encoded length
 * is its ref's length (a work row's or a kept ref's), and its overhead over the body (header, nonce, tag, CRC) is measured once
 * per key shape (ids of one length), then every body is sized from it.
 *
 * <p>The API assumed (CORE-4d, test-author): {@code io.flottila.runtime.journal.JournalFullException} — a
 * {@link RuntimeException} whose message names {@code FLOTTILA_JOURNAL_MAX_BYTES}; an intake fails with it (nothing saved) and
 * so does {@code TieredBlobStore.persist} beyond max; the journal's WARNING and ERROR are logged on the journal package logger
 * and name {@code FLOTTILA_JOURNAL_MAX_BYTES}; the sweeper's unlink lowers the journal's count
 * ({@code SegmentSweeper}, {@link SegmentSweepIT}).
 */
@Timeout(240)
class JournalBoundIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final long MAX = 1L << 20;
    static final long EDGE = MAX - MAX / 16;          // 983 040
    static final double EIGHTY = 0.8 * MAX;           // 838 860.8
    static final String KEY = "FLOTTILA_JOURNAL_MAX_BYTES";
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    JournalWorld a;
    JournalFixture.LogCapture logs;
    int next;

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
        db = CaseDatabase.create(pg, vertx, "bound_").migrated();
        a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a",
                s -> s.maxBytes(MAX).segmentBytes(MAX).pendingBytes(512L << 10),
                new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX));
        logs = new JournalFixture.LogCapture();
    }

    @AfterEach
    void close() throws Exception {
        if (logs != null) logs.close();
        if (a != null) a.close();
        db.close();
    }

    // --- bytes and records

    /** Every byte of the journal's files: its segments (sealed, open and pre-opened) and its spill. */
    long journalBytes() {
        long sum = 0;
        for (String sub : List.of("segments", "spill")) {
            Path d = a.opened.config().dir().resolve(sub);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> l = Files.list(d)) {
                for (Path p : l.filter(Files::isRegularFile).toList()) sum += Files.size(p);
            } catch (IOException e) {
                throw new AssertionError("listing " + d, e);
            }
        }
        return sum;
    }

    /** The next id: every id of these cases has one length, so a record's overhead over its body is one number. */
    String id() {
        return String.format("m-%05d", next++);
    }

    /** An intake of a {@code size}-byte body under a fresh id, settled. */
    Future<String> intake(String id, long size) {
        Envelope in = a.arrive(id, bytes(Math.toIntExact(size), (int) size));
        return a.save(in, false, undecided());
    }

    /** A step-1 body of {@code size} bytes written to the store, then persisted (a step body, or a park's body), settled. */
    Future<Void> persist(String id, long size) {
        BlobKey key = BlobKey.body(id, 1);
        assertTrue(settled(a.blobs.write(key, bytes(Math.toIntExact(size), (int) size + 1), JournalWorld.MAX)).succeeded(), "fixture: written");
        return settled(a.blobs.persist(key));
    }

    /** An intake's record overhead: measured on one taken intake of 1000 bytes. */
    long intakeOverhead() {
        String id = id();
        assertTrue(intake(id, 1_000).succeeded(), "fixture: a first intake");
        return workRef(db.pool(), id).length() - 1_000;
    }

    long persistOverhead() {
        String id = id();
        assertTrue(persist(id, 1_000).succeeded(), "fixture: a first persist");
        return JournalWorld.bodyRef(db.pool(), BlobKey.body(id, 1)).length() - 1_000;
    }

    /** Intakes of at most 150 000 bytes until one more record of up to 200 000 reaches {@code target}; the bytes then. */
    long fillTo(long target, long overhead) {
        while (target - journalBytes() - overhead > 200_000) {
            Future<String> f = intake(id(), 150_000);
            assertTrue(f.succeeded(), "fixture: a filling intake below the edge: " + (f.failed() ? causes(f.cause()) : ""));
        }
        return journalBytes();
    }

    static void full(Future<?> f, String what) {
        assertTrue(f.failed(), what + ": refused");
        JournalFullException e = assertInstanceOf(JournalFullException.class, f.cause(), what + ": " + causes(f.cause()));
        assertTrue(String.valueOf(e.getMessage()).contains(KEY), what + ": names " + KEY + ": " + e.getMessage());
    }

    List<LogRecord> logged(Level level) {
        return logs.records().stream().filter(r -> r.getLevel() == level && String.valueOf(r.getMessage()).contains(KEY)).toList();
    }

    // --- the edges

    @Test
    void theReserveEdgeTakesExactlyAndRefusesOneByteMore() {
        long intakeOverhead = intakeOverhead();
        long persistOverhead = persistOverhead();
        long before = fillTo(EDGE, intakeOverhead);
        long body = EDGE - before - intakeOverhead;
        assertTrue(body > 0 && body <= 200_000, "fixture: a body that ends exactly at the edge: " + body);

        String over = id();
        full(intake(over, body + 1), "an intake one byte above max − max/16");
        assertEquals(before, journalBytes(), "nothing reserved or written for it");
        assertEquals(0, count(db.pool(), "select count(*) from work where id = $1", over), "nothing queued");
        assertEquals(0, count(db.pool(), "select count(*) from messages where id = $1", over), "nothing recorded");

        Future<String> exact = intake(id(), body);
        assertTrue(exact.succeeded(), "an intake ending exactly at max − max/16 is taken: " + (exact.failed() ? causes(exact.cause()) : ""));
        assertEquals(EDGE, journalBytes(), "fixture: the journal is at the reserve edge");
        full(intake(id(), 1), "any intake beyond the edge");

        long step = MAX - EDGE - persistOverhead;
        full(persist(id(), step + 1), "a step body one byte above max");
        assertEquals(EDGE, journalBytes(), "nothing written for it");
        Future<Void> reserve = persist(id(), step);
        assertTrue(reserve.succeeded(), "a step body uses the reserve up to max exactly: " + (reserve.failed() ? causes(reserve.cause()) : ""));
        assertEquals(MAX, journalBytes(), "the journal is at max");
        full(persist(id(), 1), "any step body beyond max");
    }

    /**
     * An intake above the reserve fails its save ({@code JournalFullException}) without failing the journal, and the same
     * arrival is taken once a sweep made room. The 503 with Retry-After holds by composition, not observed here: the delivery
     * answers a failed save with a retryable Nack, and the HTTP consumer answers a retryable Nack 503 with Retry-After.
     */
    @Test
    void anIntakeAboveTheReserveFailsJournalFullAndItsRetryIsTakenOnceThereIsRoom() throws Exception {
        long overhead = intakeOverhead();
        fillTo(EDGE, overhead);
        while (journalBytes() + overhead + 150_000 <= EDGE) assertTrue(intake(id(), 150_000).succeeded(), "fixture: filling");
        String refused = id();
        Future<String> f = intake(refused, 150_000);
        full(f, "an intake above the reserve");   // the delivery answers a failed save with a retryable Nack: 503 + Retry-After

        // retryable: once the bytes fall (the sweeper), the sender's retry of the same arrival is taken
        assertTrue(!a.journal.isFailed(), "a full journal is no failed journal (no fail-stop)");
        long open = newestSegment();
        sql("delete from work");
        SegmentSweepIT.seal(a, open);
        a.opened.clock().advance(SegmentSweepIT.GRACE);
        SegmentSweeper sweeper = new SegmentSweeper(a.journal, new PgSegmentRows(db.pool()), a.intake::openSaves, SegmentSweepIT.CONFIG,
                a.opened.clock(), new SegmentSweepIT.StillTimer());
        try {
            Future<Set<Long>> swept = settled(sweeper.sweep());
            assertTrue(swept.succeeded() && !swept.result().isEmpty(), "fixture: the sweep freed segments: " + swept.result());
        } finally {
            sweeper.close();
        }
        Future<String> retry = a.save(a.arrive(refused, bytes(150_000, 150_000)), false, undecided());
        assertTrue(retry.succeeded(), "the same arrival is taken once there is room: " + (retry.failed() ? causes(retry.cause()) : ""));
    }

    /** The segment the newest record is in (the open one): the highest a work row names. */
    long newestSegment() {
        return count(db.pool(), "select max(body_segment) from work");
    }

    void sql(String statement, Object... params) {
        db.pool().preparedQuery(statement).execute(Tuple.wrap(params)).await();
    }

    // --- the alerts

    @Test
    void eightyPercentWarnsOnce() {
        long overhead = intakeOverhead();
        while (journalBytes() + overhead + 100_000 < EIGHTY) assertTrue(intake(id(), 100_000).succeeded(), "fixture: below 80 %");
        assertEquals(List.of(), logged(Level.WARNING), "no WARNING below 80 %: " + journalBytes());

        assertTrue(intake(id(), 100_000).succeeded(), "fixture: the intake that crosses 80 %");
        assertTrue(journalBytes() >= EIGHTY, "fixture: crossed: " + journalBytes());
        assertEquals(1, logged(Level.WARNING).size(), "one WARNING when 80 % is crossed upward");
        assertTrue(intake(id(), 50_000).succeeded(), "fixture: still below the edge");
        assertEquals(1, logged(Level.WARNING).size(), "and none for the next intake above 80 %");

        assertEquals(List.of(), logged(Level.SEVERE), "no ERROR before a refusal");
        while (intake(id(), 100_000).succeeded()) assertTrue(journalBytes() <= EDGE, "no intake is taken past max − max/16: " + journalBytes());
        assertEquals(1, logged(Level.SEVERE).size(), "one ERROR when refusals start");
        full(intake(id(), 100_000), "a second refusal");
        assertEquals(1, logged(Level.SEVERE).size(), "not one per refusal");
        assertEquals(1, logged(Level.WARNING).size(), "still one WARNING");
    }

    @Test
    void aSecondCrossingLogsAgain() throws Exception {
        long overhead = intakeOverhead();
        while (intake(id(), 100_000).succeeded()) assertTrue(journalBytes() <= EDGE, "no intake is taken past max − max/16: " + journalBytes());
        assertEquals(1, logged(Level.WARNING).size(), "fixture: the first crossing warned");
        assertEquals(1, logged(Level.SEVERE).size(), "fixture: the first refusals logged");

        long open = newestSegment();
        sql("delete from work");
        SegmentSweepIT.seal(a, open);
        a.opened.clock().advance(SegmentSweepIT.GRACE);
        try (SegmentSweeper sweeper = new SegmentSweeper(a.journal, new PgSegmentRows(db.pool()), a.intake::openSaves, SegmentSweepIT.CONFIG,
                a.opened.clock(), new SegmentSweepIT.StillTimer())) {
            assertTrue(settled(sweeper.sweep()).succeeded(), "fixture: swept");
        }
        SegmentSweepIT.until(() -> journalBytes() < EIGHTY, () -> "fixture: the bytes fell below 80 %: " + journalBytes());
        assertTrue(intake(id(), 10_000).succeeded(), "an intake below 80 % is taken");
        assertEquals(1, logged(Level.WARNING).size(), "falling below logs no WARNING of its own");

        while (intake(id(), 100_000).succeeded()) assertTrue(journalBytes() <= EDGE, "no intake is taken past max − max/16 (again): " + journalBytes());
        assertEquals(2, logged(Level.WARNING).size(), "crossing 80 % again warns again");
        assertEquals(2, logged(Level.SEVERE).size(), "refusals starting again log again");
        assertTrue(overhead > 0, "fixture: a record has an overhead");
    }

    /**
     * Beyond max a step body's persist is refused with an ERROR, writes no kept ref and leaves the journal at or below max. That
     * the step is then recorded metadata-only and a park holds follows by composition, not observed here: the recorder and the
     * DLQ take a refused persist as they take any failed persist.
     */
    @Test
    void beyondMaxAStepBodysPersistIsRefusedWithAnErrorAndWritesNoRef() {
        long intakeOverhead = intakeOverhead();
        long persistOverhead = persistOverhead();
        long before = fillTo(EDGE, intakeOverhead);
        assertTrue(intake(id(), EDGE - before - intakeOverhead).succeeded(), "fixture: at the reserve edge");
        assertEquals(EDGE, journalBytes());
        int errorsAtTheEdge = logged(Level.SEVERE).size();

        Future<Void> inReserve = persist(id(), 20_000);
        assertTrue(inReserve.succeeded(), "a step body in the reserve is kept: " + (inReserve.failed() ? causes(inReserve.cause()) : ""));
        long room = MAX - journalBytes() - persistOverhead;
        String beyond = id();
        Future<Void> refused = persist(beyond, room + 1);
        full(refused, "a step body (or a park's body) beyond max");
        assertEquals(0, count(db.pool(), "select count(*) from body_refs where message_id = $1", beyond), "no kept ref: the step is recorded metadata-only, the park holds");
        assertTrue(logged(Level.SEVERE).size() > errorsAtTheEdge, "with an ERROR: " + logs.records());
        assertTrue(journalBytes() <= MAX, "the journal never goes past max: " + journalBytes());
    }
}
