package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Hash;
import io.flottila.testkit.BlobStoreContract;
import io.flottila.testkit.GeneratedReadStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs in its own JVM with {@code -Xmx48m} (runtime pom, execution {@code heap-bounded}): a 50 MiB body that
 * is written to the spool and read back can only succeed if neither the write nor the read ever holds the
 * body — a store that buffers it dies with OutOfMemoryError here, deterministically. BACKLOG P1-01:
 * "50 MB body passes through without heap growth".
 */
class SpoolHeapTest {

    @Test
    void fiftyMegabytesPassThroughTheSpoolInsideAFortyEightMegabyteHeap(@TempDir Path dir) throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() < 64L << 20, "this test only means something under the small heap: max " + (Runtime.getRuntime().maxMemory() >> 20) + " MiB");
        TieredBlobStore s = TieredBlobStoreTest.store(dir, 1 << 20, 4L << 20);
        long size = 50L * 1024 * 1024;
        Body body = BlobStoreContract.await(s.write(BlobKey.body("big", 0), new GeneratedReadStream(size, 65536), size));
        Hash readBack = BlobStoreContract.await(new TieredBlobStoreTest.Reader().hashOf(s.open(body.key())));
        assertEquals(size, body.size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, size), body.hash());
        assertEquals(body.hash(), readBack, "read back byte for byte, one 1 MiB segment at a time");
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(body.key()));
        assertTrue(Files.size(TieredBlobStoreTest.spoolFiles(dir).get(0)) >= size, "the bytes are on disk");
    }
}
