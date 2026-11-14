package io.flottila.runtime.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.flottila.runtime.deploy.DeploymentsTest.children;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c3a (D-070): the directory archive store (dev; {@code FLOTTILA_ARCHIVE_DIR}, default {@code <dataDir>/archives}) passes the
 * archive store contract. Its directory does not exist before the first put, as on a fresh node; the archive of a key is the file
 * {@code <dir>/<sha256>.zip}, written as a temporary file in the directory and moved over it.
 */
class DirArchiveStoreTest extends ArchiveStoreContract {

    Path dir;

    @Override
    protected ArchiveStore emptyStore() {
        dir = work.resolve("data").resolve("archives");
        return new DirArchiveStore(dir, cap());
    }

    @Override
    protected long cap() {
        return 1L << 20;
    }

    @Override
    protected String keyOf(String sha256) {
        return dir.resolve(sha256 + ".zip").toString();
    }

    /** {@code <dir>/<sha256>.zip} and nothing else, after two puts and after a put that failed: no temporary file stays. */
    @Test
    void aPutLeavesOnlyItsSha256ZipInTheDir() throws Exception {
        byte[] zip = bytes(7, 4_096);
        String sha = sha256(zip);
        Path upload = archive("shop.zip", zip);
        put(sha, upload);
        put(sha, upload);
        assertEquals(List.of(sha + ".zip"), children(dir), "the archive is <dir>/<sha256>.zip; no temporary file stays");
        assertArrayEquals(zip, Files.readAllBytes(dir.resolve(sha + ".zip")));
        String other = sha256(bytes(8, 16));
        assertThrows(IOException.class, () -> put(other, work.resolve("uploads").resolve("absent.zip")), "a put of a file that is not there");
        assertEquals(List.of(sha + ".zip"), children(dir), "a failed put leaves no temporary file and no object");
    }

    @Test
    void aCapBelowOneIsRefusedAtConstructionNamingTheValue() {
        for (long bad : new long[] {0L, -1L}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new DirArchiveStore(work.resolve("cap"), bad), "cap " + bad);
            assertTrue(String.valueOf(e.getMessage()).contains(String.valueOf(bad)), "the refusal names " + bad + ": " + e.getMessage());
        }
    }

    @Test
    void aCapOfOneIsTheSmallestLegalAndFetchesAOneByteObject() throws Exception {
        ArchiveStore tiny = new DirArchiveStore(work.resolve("tiny"), 1L);
        byte[] one = bytes(14, 1);
        put(tiny, sha256(one), archive("one.zip", one));
        Path out = target("one-fetched.zip");
        fetch(tiny, sha256(one), out);
        assertArrayEquals(one, Files.readAllBytes(out), "a cap of 1 admits a one-byte object");
    }

    /** The javadoc of {@code copyBounded}: on any failure no file stays at the target. Here the source fails after one chunk. */
    @Test
    void aCopyWhoseSourceFailsMidwayLeavesNoFileAtTheTarget() throws Exception {
        Path out = target("midway.zip");
        Files.writeString(out, "stale");   // a target that existed is replaced by nothing, not left stale or truncated
        InputStream failing = new InputStream() {
            private boolean sent;

            @Override
            public int read() {
                throw new AssertionError("copyBounded reads chunks");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (sent) throw new IOException("source broke");
                sent = true;
                java.util.Arrays.fill(b, off, off + 1_000, (byte) 7);
                return 1_000;
            }
        };
        IOException e = assertThrows(IOException.class, () -> DirArchiveStore.copyBounded(failing, out, 1L << 20));
        assertEquals("source broke", e.getMessage());
        assertFalse(Files.exists(out), "the failed copy leaves no file at the target");
        assertEquals(List.of(), children(out.getParent()), "and nothing else beside it");
    }
}
