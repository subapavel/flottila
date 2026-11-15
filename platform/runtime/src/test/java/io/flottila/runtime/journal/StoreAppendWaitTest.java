package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.journal.JournalFixture.LoggedTimer;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.failureOf;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c3 packet item 8 (CORE-3 item 6: "its completion ends an append's wait"): the store's durable write for the intake —
 * {@code durably()} behind {@link TieredBlobStore#forced} — passes the save's answer on to the journal's append, so an append
 * that waits for room under {@code FLOTTILA_JOURNAL_PENDING_BYTES} ends the moment the answer completes (the save abandoned), not
 * only at {@code FLOTTILA_JOURNAL_APPEND_WAIT}: it fails {@link JournalBusyException} with no clock advance, its timer cancelled,
 * nothing reserved (not in the open saves), the body still local — once room frees the same key is forced. The journal is full
 * because the writer's force of the first record is held ({@link HeldFiles}); no Postgres (the rows are the intake's).
 *
 * <p>The API assumed (CORE-4c3, test-author): {@code Future<Durable> TieredBlobStore.forced(BlobKey key, Future<?> abandoned)};
 * how the intake hands its answer down (core {@code Intake.save}'s abandoned signal as a future, CORE-3 item 6) is the
 * architect's to decide and is not pinned here.
 */
class StoreAppendWaitTest {

    static final Duration WAIT = Duration.ofSeconds(2);

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final LoggedTimer timer = new LoggedTimer(new FakeTimer(clock));
    private final HeldFiles files = new HeldFiles();
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        files.releaseAll();
        for (Journal j : journals) j.close();
    }

    @Test
    void durablysAppendWaitEndsWhenTheAnswerCompletes() throws Exception {
        Journal journal = Journal.open(settings(Files.createDirectories(tmp.resolve("journal"))).segmentAge(Duration.ofHours(1)).recordMax(4096)
                .pendingBytes(8192).appendWait(WAIT).config(), clock, timer, keys(KEY), files);
        journals.add(journal);
        TieredBlobStore store = new TieredBlobStore(new TieredBlobStore.Config(1L << 20, 4096, 1L << 20, 1L << 20), Hasher.sha256(), journal, KEY);

        HeldFiles.Hold held = files.holdForceAfter("aw-0");
        List<Future<TieredBlobStore.Durable>> taken = new ArrayList<>();
        taken.add(forced(store, "aw-0", Promise.<Void>promise().future()));
        held.awaitReached();
        Future<TieredBlobStore.Durable> waiting = null;
        Promise<Void> answer = null;
        BlobKey waitingKey = null;
        for (int i = 1; i <= 5 && waiting == null; i++) {
            int waitsBefore = timer.withDelay(WAIT).size();
            int savesBefore = store.openSaves().size();
            String id = "aw-" + i;
            Promise<Void> a = Promise.promise();
            Future<TieredBlobStore.Durable> f = forced(store, id, a.future());
            // taken: its record reserved, an open save until its force (held) returns; or waiting for room on the fake timer
            until(() -> f.isComplete() || store.openSaves().size() > savesBefore || timer.withDelay(WAIT).size() > waitsBefore,
                    () -> id + " is taken or waits on the fake timer: " + timer.log() + ", open saves " + store.openSaves());
            if (f.isComplete() || store.openSaves().size() > savesBefore) {
                taken.add(f);
            } else {
                waiting = f;
                answer = a;
                waitingKey = BlobKey.body(id, 0);
            }
        }
        assertTrue(waiting != null, "fixture: 3000-byte bodies behind a held force wait for room under an 8192-byte bound");
        int open = store.openSaves().size();

        answer.complete();   // the save's answer was decided: the save is abandoned

        Throwable failed = failureOf(waiting);
        assertInstanceOf(JournalBusyException.class, failed, "the wait ends when the answer completes, retryable: " + failed);
        assertEquals(START, clock.instant(), "with no clock advance");
        LoggedTimer.Scheduled its = timer.withDelay(WAIT).getLast();
        assertTrue(its.cancelled.get() && !its.fired.get(), "its APPEND_WAIT timer is cancelled, not left pending: " + timer.log());
        assertEquals(open, store.openSaves().size(), "nothing reserved: no open save was added for it");
        assertTrue(store.location(waitingKey).isPresent(), "its body stays local");

        held.release();
        for (Future<TieredBlobStore.Durable> t : taken) assertTrue(JournalWorld.settled(t).succeeded(), "the taken ones are forced: " + t.cause());
        Future<TieredBlobStore.Durable> again = JournalWorld.settled(store.forced(waitingKey, Promise.<Void>promise().future()));
        assertTrue(again.succeeded(), "with room, the same body is forced: " + again.cause());
        assertArrayEquals(body(waitingKey.messageId(), 3000), journal.read(again.result().ref()), "and its record holds its bytes");
        assertFalse(timer.withDelay(WAIT).stream().anyMatch(s -> s.fired.get()), "no APPEND_WAIT ever fired");
    }

    /** {@code id}'s 3000-byte body written to the store (memory), then forced with {@code answer} as its abandoned signal. */
    private static Future<TieredBlobStore.Durable> forced(TieredBlobStore store, String id, Future<?> answer) {
        BlobKey key = BlobKey.body(id, 0);
        assertTrue(JournalWorld.settled(store.write(key, Buffer.buffer(body(id, 3000)), 1L << 20)).succeeded(), "fixture: written");
        return store.forced(key, answer);
    }
}
