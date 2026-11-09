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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-139 at the journal's and the master key's starts: a first start on a journal path whose parents are missing makes the
 * entry of every directory it created durable in its parent (each new level's parent forced through {@link JournalFiles}),
 * never forcing a directory above the nearest one already there; a second start on the same path forces no directory above
 * the journal directory (an existing directory is not forced again).
 */
class JournalDurableDirTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();
    private final List<HeldFiles> seams = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (HeldFiles f : seams) f.releaseAll();
        for (Journal j : journals) j.close();
    }

    private HeldFiles seam() {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        return files;
    }

    /** {@code <tmp>/<name>}, there; the journal path below it, {@code x/y/journal}, is not. */
    private Path base(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name)).toAbsolutePath().normalize();
    }

    private Journal open(Path dir, JournalFiles files) throws IOException {
        Journal j = Journal.open(settings(dir).segmentAge(Duration.ofHours(1)).config(), clock, timer, keys(JournalFixture.KEY), files);
        journals.add(j);
        return j;
    }

    private void close(Journal j) throws IOException {
        journals.remove(j);
        j.close();
    }

    /** The master key of {@code dir}, generated on its first load ({@code FLOTTILA_JOURNAL_KEY_GENERATE=true}). */
    private static SecretKey generatedKey(Path dir, JournalFiles files) throws IOException {
        return MasterKeySource.fromEnv(Map.of(MasterKeySourceTest.GENERATE, "true")).load(dir, files);
    }

    /** The forces entered on each of {@code dirs}, failed ones included, in the order given. */
    private static List<Integer> forcesOn(HeldFiles files, Path... dirs) {
        List<Integer> n = new ArrayList<>();
        for (Path d : dirs) n.add(files.forces(d));
        return n;
    }

    private static void assertEachNewParentForced(HeldFiles files, Path base, Path x, Path y, Path journal) {
        assertTrue(files.forcesOn(base).stream().anyMatch(HeldFiles.Op::returnedOk), "base (x's parent) forced: " + files.describe());
        assertTrue(files.forcesOn(x).stream().anyMatch(HeldFiles.Op::returnedOk), "x (y's parent) forced: " + files.describe());
        assertTrue(files.forcesOn(y).stream().anyMatch(HeldFiles.Op::returnedOk), "y (the journal directory's parent) forced: " + files.describe());
        assertTrue(files.forcesOn(journal).stream().anyMatch(HeldFiles.Op::returnedOk), "the journal directory forced: " + files.describe());
        assertEquals(0, files.forces(base.getParent()), "nothing above the nearest existing ancestor is forced: " + files.describe());
    }

    @Test
    void aJournalsFirstStartOnAMissingNestedPathForcesEveryNewParent() throws IOException {
        Path base = base("first");
        Path x = base.resolve("x");
        Path y = x.resolve("y");
        Path journal = y.resolve("journal");
        HeldFiles files = seam();

        open(journal, files);

        assertTrue(Files.isDirectory(journal.resolve("segments")), "the journal started");
        assertEachNewParentForced(files, base, x, y, journal);
    }

    @Test
    void aJournalsSecondStartForcesNoParentAboveTheJournalDir() throws IOException {
        Path base = base("second");
        Path x = base.resolve("x");
        Path y = x.resolve("y");
        Path journal = y.resolve("journal");
        HeldFiles first = seam();
        close(open(journal, first));
        // witness: the first start's parent forces are seen by this recorder on this very path
        assertEachNewParentForced(first, base, x, y, journal);

        HeldFiles second = seam();
        open(journal, second);

        assertEquals(List.of(0, 0, 0, 0), forcesOn(second, base.getParent(), base, x, y),
                "a restart forces no directory above the journal directory: " + second.describe());
    }

    @Test
    void aGeneratedMasterKeysFirstLoadOnAMissingNestedPathForcesEveryNewParent() throws IOException {
        Path base = base("key-first");
        Path x = base.resolve("x");
        Path y = x.resolve("y");
        Path journal = y.resolve("journal");
        HeldFiles files = seam();

        generatedKey(journal, files);

        assertTrue(Files.isRegularFile(journal.resolve("MASTER")), "the key was generated");
        assertEachNewParentForced(files, base, x, y, journal);
    }

    @Test
    void aGeneratedMasterKeysSecondLoadForcesNoParentAboveTheJournalDir() throws IOException {
        Path base = base("key-second");
        Path x = base.resolve("x");
        Path y = x.resolve("y");
        Path journal = y.resolve("journal");
        HeldFiles first = seam();
        SecretKey generated = generatedKey(journal, first);
        assertEachNewParentForced(first, base, x, y, journal);   // witness, as above

        HeldFiles second = seam();
        SecretKey again = generatedKey(journal, second);

        assertEquals(generated, again, "the second load reads the generated key");
        assertEquals(List.of(0, 0, 0, 0), forcesOn(second, base.getParent(), base, x, y),
                "a second load forces no directory above the journal directory: " + second.describe());
    }
}
