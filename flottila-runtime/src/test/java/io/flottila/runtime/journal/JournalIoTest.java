package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-139: {@link JournalIo#createDirectoriesDurably(JournalFiles, Path)} creates every missing level of a directory and
 * makes each new entry durable in its parent — it forces the parent of every level it created, then the deepest level
 * itself, through {@link JournalFiles#open} (Linux fsync(2): a force of a directory does not make its own entry in its
 * parent durable). A directory that was already there is not forced. Forces are recorded by {@link HeldFiles} over real
 * channels in a temp dir.
 */
class JournalIoTest {

    @TempDir Path tmp;

    private final List<HeldFiles> seams = new ArrayList<>();

    @AfterEach
    void releaseHolds() {
        seams.forEach(HeldFiles::releaseAll);
    }

    private HeldFiles seam() {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        return files;
    }

    private Path root() throws IOException {
        return Files.createDirectories(tmp.resolve("root")).toAbsolutePath().normalize();
    }

    /** The files of every force entered so far, in the order they were entered. */
    private static List<Path> forced(HeldFiles files) {
        return files.ops().stream().filter(op -> op.kind == HeldFiles.Kind.FORCE).map(op -> op.file).toList();
    }

    @Test
    void creatingANestedDirectoryForcesEachNewLevelsParent() throws IOException {
        Path root = root();
        Path a = root.resolve("a");
        Path b = a.resolve("b");
        Path c = b.resolve("c");
        HeldFiles files = seam();

        JournalIo.createDirectoriesDurably(files, c);

        assertTrue(Files.isDirectory(c), "a/b/c was created");
        assertEquals(List.of(root, a, b, c), forced(files),
                "root (a's parent), a (b's parent), b (c's parent), then c itself, in that order and once each; ops: " + files.describe());
        assertTrue(files.ops().stream().allMatch(HeldFiles.Op::returnedOk), "every force returned: " + files.describe());
    }

    @Test
    void eachParentIsForcedOnceItsNewEntryIsThere() throws Exception {
        Path root = root();
        Path a = root.resolve("a");
        Path b = a.resolve("b");
        Path c = b.resolve("c");
        HeldFiles files = seam();
        HeldFiles.Hold rootForce = files.holdForceOn(root, 0);
        HeldFiles.Hold aForce = files.holdForceOn(a, 0);
        HeldFiles.Hold bForce = files.holdForceOn(b, 0);

        Outcome<Path> created = onVirtual(() -> {
            JournalIo.createDirectoriesDurably(files, c);
            return c;
        });

        rootForce.awaitReached();
        assertTrue(Files.isDirectory(a), "root is forced once its entry a is there (a force before it proves nothing for a)");
        rootForce.release();
        aForce.awaitReached();
        assertTrue(Files.isDirectory(b), "a is forced once its entry b is there");
        aForce.release();
        bForce.awaitReached();
        assertTrue(Files.isDirectory(c), "b is forced once its entry c is there");
        bForce.release();
        assertEquals(c, created.join());
    }

    @Test
    void anExistingDirectoryIsNotForced() throws IOException {
        Path root = root();
        Path a = Files.createDirectories(root.resolve("a"));
        HeldFiles files = seam();

        JournalIo.createDirectoriesDurably(files, a);
        assertEquals(List.of(), forced(files), "a directory already there is not forced again (a normal restart): " + files.describe());

        // witness on the same seam: the forces of a creation are seen, and none reaches above the nearest existing ancestor
        Path b = a.resolve("b");
        Path c = b.resolve("c");
        JournalIo.createDirectoriesDurably(files, c);
        assertEquals(List.of(a, b, c), forced(files),
                "below the existing a: a (b's parent), b (c's parent), c; never root nor anything above it: " + files.describe());
    }

    /** {@code which} names the directory whose force fails, relative to root ("." = root itself). */
    @ParameterizedTest(name = "the force of {0} fails")
    @ValueSource(strings = {".", "a", "a/b", "a/b/c"})
    void aFailedForceFailsTheCreation(String which) throws IOException {
        Path root = root();
        Path c = root.resolve("a").resolve("b").resolve("c");
        HeldFiles files = seam();
        IOException boom = new IOException("the force of " + which + " failed (test)");
        files.holdForceOn(root.resolve(which).normalize(), 0).fail(boom);

        IOException thrown = assertThrows(IOException.class, () -> JournalIo.createDirectoriesDurably(files, c));

        assertTrue(carries(thrown, boom), "the failed force surfaces (itself or as a cause), never swallowed: " + thrown);
        assertTrue(files.ops().stream().anyMatch(op -> op.kind == HeldFiles.Kind.FORCE && !op.ok),
                "the failed force was entered: " + files.describe());
    }

    private static boolean carries(Throwable t, Throwable cause) {
        for (Throwable at = t; at != null; at = at.getCause()) if (at == cause) return true;
        return false;
    }
}
