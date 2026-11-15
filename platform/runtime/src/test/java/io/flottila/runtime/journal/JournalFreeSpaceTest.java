package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c3 packet item 5 (CORE-3 review r2): a start refuses when the journal's file system has too little room for the journal
 * to reach its bound — usable space + the journal's bytes &lt; {@code FLOTTILA_JOURNAL_MAX_BYTES} — so a node never starts into
 * an ENOSPC fail-stop crash loop; the refusal names both numbers and changes nothing on disk. Exactly MAX_BYTES starts. The
 * journal's bytes are its segment files (its spill is cleared at start; CORE-3 item 11 counts segments, spill and
 * reservations). The space source is the seam: {@link SpaceFiles} answers a usable space the case sets, so no disk is filled.
 *
 * <p>The API assumed (CORE-4c3, test-author): {@code default FileStore JournalFiles.fileStore(Path dir) throws IOException}
 * ({@code Files.getFileStore(dir)} in the real one) asked for the journal's directory; {@code JournalConfig.maxBytes()} (set here
 * through {@link Settings#maxBytes(long)}); the refusal an {@link IllegalStateException} like the start's other refusals.
 */
class JournalFreeSpaceTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    /** Real channels; the file system's usable (and unallocated) space is {@link #usable}, whatever the disk holds. */
    static final class SpaceFiles implements JournalFiles {
        volatile long usable = 1L << 50;
        final List<Path> asked = new CopyOnWriteArrayList<>();

        @Override public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
            return FileChannel.open(file, options);
        }

        @Override public FileStore fileStore(Path dir) throws IOException {
            asked.add(dir.toAbsolutePath().normalize());
            return new Fixed(Files.getFileStore(dir), usable);
        }
    }

    /** A file store whose usable and unallocated space is fixed; the rest is the real one's. */
    static final class Fixed extends FileStore {
        private final FileStore real;
        private final long space;

        Fixed(FileStore real, long space) {
            this.real = real;
            this.space = space;
        }

        @Override public String name() { return real.name(); }
        @Override public String type() { return real.type(); }
        @Override public boolean isReadOnly() { return false; }
        @Override public long getTotalSpace() throws IOException { return Math.max(space, real.getTotalSpace()); }
        @Override public long getUsableSpace() { return space; }
        @Override public long getUnallocatedSpace() { return space; }
        @Override public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) { return real.supportsFileAttributeView(type); }
        @Override public boolean supportsFileAttributeView(String name) { return real.supportsFileAttributeView(name); }
        @Override public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) { return real.getFileStoreAttributeView(type); }
        @Override public Object getAttribute(String attribute) throws IOException { return real.getAttribute(attribute); }
    }

    private Journal open(Settings s, JournalFiles files) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), files);
        journals.add(j);
        return j;
    }

    /** The bytes of every segment file: what the journal holds on disk after a clean close. */
    static long segmentBytes(Path dir) throws IOException {
        long n = 0;
        try (Stream<Path> l = Files.list(dir.resolve("segments"))) {
            for (Path p : l.toList()) n += Files.size(p);
        }
        return n;
    }

    /** Every file under {@code dir} by its path below it, with its bytes. */
    static Map<String, String> filesUnder(Path dir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) files.put(dir.relativize(p).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        }
        return files;
    }

    @Test
    void tooLittleFreeSpaceRefusesStartAndExactlyMaxBytesStarts() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("space"));
        long max = 1L << 20;
        Settings s = settings(dir).segmentAge(Duration.ofHours(1)).maxBytes(max);
        SpaceFiles files = new SpaceFiles();
        Journal first = open(s, files);
        for (int i = 0; i < 3; i++) await(first.append(record("fs-" + i, 20_000), live()).forced());
        journals.remove(first);
        first.close();
        long held = segmentBytes(dir);
        assertTrue(held > 60_000 && held < max / 2, "fixture: the journal holds its three records and some headers: " + held);
        Map<String, String> before = filesUnder(dir);
        files.asked.clear();

        files.usable = max - held - 1;   // usable + the journal's bytes = MAX_BYTES - 1
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> open(s, files), "one byte short of MAX_BYTES");

        String text = String.valueOf(e.getMessage());
        assertTrue(text.contains("FLOTTILA_JOURNAL_MAX_BYTES"), "names the key: " + text);
        assertTrue(text.contains(String.valueOf(max)), "names MAX_BYTES " + max + ": " + text);
        assertTrue(text.contains(String.valueOf(max - 1)), "names what the file system and the journal hold together, " + (max - 1) + ": " + text);
        assertFalse(files.asked.isEmpty(), "the journal's file system was asked");
        assertTrue(files.asked.getFirst().startsWith(dir.toAbsolutePath().normalize()), "for the journal's directory: " + files.asked);
        assertEquals(before, filesUnder(dir), "nothing changed on disk: no segment begun on a file system that cannot hold the journal");

        files.usable = max - held;   // exactly MAX_BYTES
        Journal again = open(s, files);
        Journal.Appended a = again.append(record("fs-next", 100), live());
        await(a.forced());
        assertArrayEquals(body("fs-next", 100), again.read(a.ref()), "usable + journal bytes = MAX_BYTES starts the journal");
    }
}
