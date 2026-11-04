package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.GeneratedInput.Digest;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs in its own JVM with {@code -Xmx48m} (runtime pom, execution {@code heap-bounded}, as blob/SpoolHeapTest): a large
 * body four times the heap is spilled, read back from the spill, persisted and read back by its ref. Only a journal that
 * never holds the body (the write, the hash, both reads and the persist all stream) passes; one that buffers it dies with
 * OutOfMemoryError, deterministically. The persist moves the spill file: the segment file is the spill's inode, so not one
 * byte was copied (CORE-3 "Rejected": copying spill at persist).
 */
class LargeBodyHeapTest {

    @Test
    void persistMovesTheSpillWithoutACopy(@TempDir Path tmp) throws Exception {
        long heap = Runtime.getRuntime().maxMemory();
        assertTrue(heap < 64L << 20, "this test only means something under the small heap: max " + (heap >> 20) + " MiB");
        long size = (192L << 20) + 7;
        Digest expected = GeneratedInput.expected(48, size);
        Path dir = tmp.resolve("journal");
        FakeClock clock = FakeClock.at(JournalFixture.START);
        RecordingRows rows = new RecordingRows();
        JournalConfig config = JournalFixture.settings(dir).segmentAge(Duration.ofHours(1)).config();

        try (Journal j = Journal.open(config, clock, new FakeTimer(clock), JournalFixture.keys(JournalFixture.KEY), JournalFiles.real(), rows)) {
            GeneratedInput input = new GeneratedInput(48, size);
            Journal.Spill spill = j.spill(BlobKey.body("heap-1", 0), JournalFixture.KEY, input, size, Hasher.sha256());
            assertEquals(size, input.delivered(), "the whole body went in");
            assertEquals(size, spill.size());
            assertEquals(expected.hash(), spill.hash(), "hashed while streaming");
            Path spilled = LargeBodyTest.onlyFile(LargeBodyTest.spillDir(dir));
            Object inode = Files.getAttribute(spilled, "unix:ino");
            try (InputStream in = spill.open()) {
                assertEquals(expected, GeneratedInput.digestOf(in), "the spill reads back streaming before persist");
            }

            JournalRef ref = spill.persist();

            Path segment = LargeBodyTest.segmentFile(dir, ref.segment());
            assertEquals(inode, Files.getAttribute(segment, "unix:ino"), "persist moved the spill: the segment file is its inode, not a copy");
            assertFalse(Files.exists(spilled), "nothing is left under spill/");
            assertTrue(Files.size(segment) > size, "the bytes are on disk: " + Files.size(segment));
            assertEquals(1, rows.insertsOf(ref.segment()).size(), "one segment row");
            try (InputStream in = j.stream(ref)) {
                assertEquals(expected, GeneratedInput.digestOf(in), "read back streaming by its ref");
            }
        }
    }
}
