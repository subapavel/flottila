package io.flottila.runtime.journal;

import io.flottila.api.Envelope;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4d packet item 4 (the CORE-4c3 r1 carry): the intake passes the delivery's answer future — core {@code Intake.save}'s
 * {@code abandoned}, a {@code Future<?>} now — to the store's {@code forced(key, abandoned)}, so an ingress record waiting for
 * room under {@code FLOTTILA_JOURNAL_PENDING_BYTES} stops waiting when the answer is decided (the save timeout's Nack, the
 * stop's deadline), not only at {@code FLOTTILA_JOURNAL_APPEND_WAIT}. Seam: a force held ({@link HeldFiles}), 3000-byte bodies
 * under an 8192-byte pending bound, the fake clock never advanced. Witness: a save of an undecided answer behind the same held
 * force still waits.
 */
@Timeout(120)
class IntakeAppendWaitIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration APPEND_WAIT = Duration.ofSeconds(5);
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
        db = CaseDatabase.create(pg, vertx, "append_wait_").migrated();
        a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a",
                s -> s.recordMax(4096).pendingBytes(8192).appendWait(APPEND_WAIT).segmentAge(Duration.ofHours(1)),
                new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX));
    }

    @AfterEach
    void close() throws Exception {
        if (a != null) a.close();
        db.close();
    }

    /**
     * An append waits for room: a task due within APPEND_WAIT (5 s) is pending on the fake timer — the wait's deadline. aw-0's
     * segment-age task (1 h) is pending from its append on, so a count of pending tasks alone cannot tell a waiting save from
     * one not yet appended. Read under the timer's monitor: pending and the earliest delay at one instant.
     */
    boolean waitsForRoom() {
        FakeTimer timer = a.opened.timer();
        synchronized (timer) {
            return timer.pending() > 0 && timer.nextDelay().compareTo(APPEND_WAIT) <= 0;
        }
    }

    @Test
    void theAnswerFutureEndsTheIntakesAppendWait() throws Exception {
        HeldFiles.Hold held = a.files.holdForceAfter("aw-0");
        List<Future<String>> taken = new ArrayList<>();
        taken.add(a.start(a.arrive("aw-0", bytes(3_000, 0)), false, undecided()));
        held.awaitReached();
        // fill the pending bound behind the held force: the saves taken are open saves; the first that is not waits for room
        Future<String> waiting = null;
        Promise<Void> answer = null;
        for (int i = 1; i <= 5 && waiting == null; i++) {
            int openBefore = a.intake.openSaves().size();
            Promise<Void> decided = Promise.promise();
            Future<String> f = a.start(a.arrive("aw-" + i, bytes(3_000, i)), false, decided.future());
            SegmentSweepIT.until(() -> f.isComplete() || a.intake.openSaves().size() > openBefore || waitsForRoom(),
                    () -> "the save is taken or waits for room on the fake timer");
            if (f.isComplete() || a.intake.openSaves().size() > openBefore) {
                taken.add(f);
            } else {
                waiting = f;
                answer = decided;
            }
        }
        assertTrue(waiting != null, "fixture: 3000-byte bodies behind a held force wait for room under an 8192-byte bound");
        Future<String> witness = a.start(a.arrive("aw-witness", bytes(3_000, 9)), false, undecided());
        Instant before = a.opened.clock().instant();

        answer.complete();   // the delivery's answer is decided: the save is abandoned while its record waits for room

        Future<String> ended = settled(waiting);
        assertTrue(ended.failed(), "the waiting save ends when its answer completes");
        assertInstanceOf(JournalBusyException.class, ended.cause(), "retryable, as an APPEND_WAIT would end it: " + causes(ended.cause()));
        assertEquals(before, a.opened.clock().instant(), "with no clock advance: APPEND_WAIT did not end it");
        assertFalse(witness.isComplete(), "witness: a save whose answer is undecided still waits behind the held force");
        String id = "aw-" + taken.size();
        assertEquals(0, count(db.pool(), "select count(*) from work where id = $1", id), "the abandoned save queued nothing");

        held.release();
        for (Future<String> t : taken) assertTrue(settled(t).succeeded(), "the taken saves commit: " + (t.failed() ? causes(t.cause()) : ""));
        assertTrue(settled(witness).succeeded(), "the witness is taken once there is room: " + (witness.failed() ? causes(witness.cause()) : ""));
    }
}
