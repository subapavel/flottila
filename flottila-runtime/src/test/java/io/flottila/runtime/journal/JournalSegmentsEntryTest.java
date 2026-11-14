package io.flottila.runtime.journal;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-139: a first start makes the entry of {@code segments/} durable in the journal directory — a force of the journal
 * directory is entered once {@code segments/} is there (the forces of the journal directory itself and of {@code NODE} come
 * before it, so they do not cover it).
 */
class JournalSegmentsEntryTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final HeldFiles held = new HeldFiles();
    private Journal journal;

    @AfterEach
    void close() throws Exception {
        held.releaseAll();
        if (journal != null) journal.close();
    }

    @Test
    void aFirstStartForcesTheJournalDirectoryOnceSegmentsIsThere() throws IOException {
        Path dir = tmp.resolve("journal").toAbsolutePath().normalize();
        Path segments = dir.resolve("segments");
        AtomicLong segmentsSeenAt = new AtomicLong(-1);   // the sequence when the journal directory was first opened with segments/ there
        JournalFiles files = (file, options) -> {
            if (file.toAbsolutePath().normalize().equals(dir) && Files.isDirectory(segments)) segmentsSeenAt.compareAndSet(-1, held.now());
            return held.open(file, options);
        };

        journal = Journal.open(settings(dir).segmentAge(Duration.ofHours(1)).config(), clock, timer, keys(JournalFixture.KEY), files);

        assertTrue(segmentsSeenAt.get() >= 0, "the journal directory was opened (to be forced) after segments/ was created: " + held.describe());
        assertTrue(held.opsOnAfter(dir, segmentsSeenAt.get()).stream()
                        .anyMatch(op -> op.kind == HeldFiles.Kind.FORCE && op.returnedOk()),
                "a force of the journal directory returned after segments/ was there: " + held.describe());
    }
}
