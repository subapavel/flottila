package dev.rail8.runtime.journal;

import dev.rail8.runtime.journal.JournalFixture.Outcome;
import dev.rail8.runtime.journal.JournalFixture.Settings;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.journal.JournalFixture.KEY;
import static dev.rail8.runtime.journal.JournalFixture.keys;
import static dev.rail8.runtime.journal.JournalFixture.onVirtual;
import static dev.rail8.runtime.journal.JournalFixture.until;

/**
 * The fixture of the tests that hold a persist's own force of {@code segments/} (FIX-168, FIX-227): a hold takes the force of
 * one thread, never "the next force of the directory by any thread".
 *
 * <p>A first {@link Journal#open} forces {@code segments/} twice on the opening thread (its creation, segment 1) and starts the
 * pre-open of segment 2 on a thread of its own ({@code journal-open-2}), which ends with a force of {@code segments/} of its own.
 * A hold on "the next force" armed after the opener's two can be met by that late force and fail it as the persist's.
 * {@link Preopen} keeps the pre-open at its file's force until the persist's hold is armed; {@link Persisting} arms the hold
 * with the thread of the persist before the persist runs.
 */
final class OwnDirForce {

    private OwnDirForce() {}

    /** A journal whose pre-open of segment 2 is held at its file's force, before its force of {@code segments/}. */
    static final class Preopen {
        final Journal journal;
        private final HeldFiles files;
        private final Path segments;
        private final HeldFiles.Hold hold;

        /**
         * Opens off the test thread with a bounded join: an open that forced 2.seg itself, or waited for its pre-open, would sit in
         * the held force; it fails here within the bound instead of hanging the run. The caller adds {@link #journal} to its closes.
         */
        Preopen(Settings s, HeldFiles files, FakeClock clock, FakeTimer timer, SegmentRows rows) throws Exception {
            this.files = files;
            this.segments = s.dir.resolve("segments");
            this.hold = files.holdForceOn(segments.resolve("2.seg"), 0);
            Outcome<Journal> opening = onVirtual(() -> Journal.open(s.config(), clock, timer, keys(KEY), files, rows));
            hold.awaitReached();
            if (hold.thread() == opening.thread()) throw new AssertionError("fixture: 2.seg is forced by the opening thread, not a pre-open: " + hold.op());
            try {
                journal = opening.join();
            } catch (AssertionError e) {
                throw new AssertionError("fixture: Journal.open returns while its pre-open's force of 2.seg is held (" + hold.op() + ")", e);
            }
        }

        /** Lets the pre-open go (call after the persists' holds are armed) and waits until its own force of {@code segments/} returned. */
        void letGo() throws InterruptedException {
            hold.release();
            until(() -> files.forcesOn(segments).stream().anyMatch(op -> op.thread == hold.thread() && op.returnedOk()),
                    () -> "the pre-open's force of segments/ returned: " + files.forcesOn(segments));
        }
    }

    /**
     * A persist of {@code spill} on a virtual thread of its own, gated on a latch, whose first force of {@code segments} is held by
     * that thread: the hold is armed before {@link #go()}, so it can only meet this persist's own directory force.
     */
    static final class Persisting {
        final CountDownLatch started = new CountDownLatch(1);
        final Outcome<JournalRef> call;
        final HeldFiles.Hold force;

        Persisting(HeldFiles files, Journal.Spill spill, Path segments) {
            call = onVirtual(() -> {
                if (!started.await(JournalFixture.BOUND.toSeconds(), TimeUnit.SECONDS)) throw new AssertionError("fixture: the persist was never let go");
                return spill.persist();
            });
            force = files.holdForceOn(segments, files.now(), call.thread());
        }

        void go() { started.countDown(); }
    }
}
