package io.flottila.runtime.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.flottila.runtime.deploy.DeploymentsTest.children;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        return new DirArchiveStore(dir);
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
}
