package io.flottila.runtime.deploy;

import io.flottila.runtime.node.OnVirtualThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c3a (D-070): what every {@link ArchiveStore} guarantees. A package archive is stored once under its SHA-256 (64 lower-case
 * hex digits; anything else is an {@link IllegalArgumentException} from {@code put} and {@code fetch} alike), {@code put} always
 * writes — a second put of the same key is quiet, and it is a write, not a skip (D-070 rejected HEAD-then-skip) — and leaves the
 * caller's file as it was; {@code fetch} writes the object's bytes, byte for byte, over whatever the target held, any number of
 * times; a key never stored is a {@link NoSuchFileException} naming the store's key for it. Both calls block: they run here on a
 * virtual thread, as the deploy and the restore call them. Run by {@code DirArchiveStoreTest} and {@code S3ArchiveStoreIT}.
 *
 * <p>The shape these cases assume (P1-09c3a test-author): in {@code io.flottila.runtime.deploy}, {@code interface ArchiveStore}
 * with {@code void put(String sha256, Path archive) throws IOException} and {@code void fetch(String sha256, Path target) throws
 * IOException}.
 */
public abstract class ArchiveStoreContract {

    @TempDir protected Path work;

    /** The case's store, empty. */
    protected ArchiveStore store;

    /** A fresh, empty store for one case; {@link #work} is set. */
    protected abstract ArchiveStore emptyStore() throws Exception;

    /** The name the case's store gives the archive of {@code sha256}: its object key, or its file. */
    protected abstract String keyOf(String sha256);

    @BeforeEach
    void openTheStore() throws Exception {
        store = emptyStore();
    }

    // --- fixture

    /** {@code size} pseudo-random bytes, the same for the same seed. */
    protected static byte[] bytes(long seed, int size) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    protected static String sha256(byte[] bytes) {
        return PackageDraft.sha256(bytes);
    }

    /** {@code bytes} as the file {@code name} in the case's upload directory. */
    protected Path archive(String name, byte[] bytes) throws Exception {
        Path dir = Files.createDirectories(work.resolve("uploads"));
        return Files.write(dir.resolve(name), bytes);
    }

    /** A path in the case's output directory, not created. */
    protected Path target(String name) throws Exception {
        return Files.createDirectories(work.resolve("out")).resolve(name);
    }

    protected void put(String sha256, Path archive) throws Exception {
        put(store, sha256, archive);
    }

    protected void fetch(String sha256, Path target) throws Exception {
        fetch(store, sha256, target);
    }

    /** {@code s.put} on a virtual thread of its own: what it throws, unwrapped. */
    protected static void put(ArchiveStore s, String sha256, Path archive) throws Exception {
        OnVirtualThread.call(() -> {
            s.put(sha256, archive);
            return null;
        });
    }

    /** {@code s.fetch} on a virtual thread of its own: what it throws, unwrapped. */
    protected static void fetch(ArchiveStore s, String sha256, Path target) throws Exception {
        OnVirtualThread.call(() -> {
            s.fetch(sha256, target);
            return null;
        });
    }

    // --- the contract

    @Test
    void aPutArchiveFetchesByteForByte() throws Exception {
        byte[] zip = bytes(1, 300_007);
        String sha = sha256(zip);
        Path upload = archive("shop.zip", zip);
        put(sha, upload);
        assertArrayEquals(zip, Files.readAllBytes(upload), "put copies: the caller's file is left as it was");
        Path out = target("fetched.zip");
        fetch(sha, out);
        assertArrayEquals(zip, Files.readAllBytes(out), "fetch writes the archive put, byte for byte");
        Path again = target("again.zip");
        fetch(sha, again);
        assertArrayEquals(zip, Files.readAllBytes(again), "an archive is fetched any number of times");
    }

    @Test
    void aSecondPutOfTheSameSha256IsQuiet() throws Exception {
        byte[] zip = bytes(2, 70_001);
        String sha = sha256(zip);
        Path upload = archive("shop.zip", zip);
        put(sha, upload);
        put(sha, upload);
        Path out = target("fetched.zip");
        fetch(sha, out);
        assertArrayEquals(zip, Files.readAllBytes(out), "a second put of the same archive changes nothing");
        // put always writes (D-070: no HEAD-then-skip): a put under a key already stored replaces the object, it is not skipped
        byte[] other = bytes(3, 1_234);
        put(sha, archive("other.zip", other));
        Path replaced = target("replaced.zip");
        fetch(sha, replaced);
        assertArrayEquals(other, Files.readAllBytes(replaced), "the third put was written, not skipped because the key existed");
    }

    @Test
    void fetchOfAnUnknownSha256IsNoSuchFileNamingIt() throws Exception {
        byte[] zip = bytes(4, 5_000);
        put(sha256(zip), archive("stored.zip", zip));   // the store is not empty: an unknown key is not answered with another object
        String unknown = sha256("never stored".getBytes(StandardCharsets.UTF_8));
        Path out = target("unknown.zip");
        NoSuchFileException missing = assertThrows(NoSuchFileException.class, () -> fetch(unknown, out), "a key never put");
        assertTrue(String.valueOf(missing.getMessage()).contains(keyOf(unknown)),
                "the NoSuchFileException names the key " + keyOf(unknown) + ": " + missing.getMessage());
    }

    @Test
    void aKeyNotLowerHexSha256IsRefused() throws Exception {
        byte[] zip = bytes(5, 2_048);
        String sha = sha256(zip);
        Path upload = archive("shop.zip", zip);
        put(sha, upload);   // the valid key is taken: a refusal is not a lookup that missed
        String upper = "ABCDEF" + sha.substring(6);
        List<String> refused = List.of(
                "",
                sha.substring(1),                      // 63 digits
                sha + "0",                             // 65 digits
                upper,                                 // upper-case hex
                sha.substring(0, 63) + "g",            // not hex
                sha + "\n",                            // a trailing line end: '$' of a find() would take it
                " " + sha.substring(1),
                "../" + sha.substring(3),              // out of the store's place
                sha.substring(0, 60) + ".zip");
        for (String bad : refused) {
            String shown = "'" + bad.replace("\n", "\\n") + "'";
            assertThrows(IllegalArgumentException.class, () -> put(bad, upload), "put refuses the key " + shown);
            assertThrows(IllegalArgumentException.class, () -> fetch(bad, target("bad.zip")), "fetch refuses the key " + shown);
        }
        Path out = target("fetched.zip");
        fetch(sha, out);
        assertArrayEquals(zip, Files.readAllBytes(out), "the refused puts changed nothing stored");
    }

    @Test
    void fetchReplacesTheTarget() throws Exception {
        byte[] zip = bytes(6, 10_000);
        String sha = sha256(zip);
        put(sha, archive("shop.zip", zip));
        Path out = target("existing.zip");
        Files.writeString(out, "stale ".repeat(5_000));   // 30 000 bytes, longer than the archive: a write that does not truncate shows
        fetch(sha, out);
        assertArrayEquals(zip, Files.readAllBytes(out), "the target holds the archive and nothing of what it held before");
    }
}
