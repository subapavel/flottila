package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.testkit.BlobStoreContract;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A spool stream opens its file lazily, when it is first read. Without a durable tier there is nowhere else to read a body
 * from: a file evicted after the stream was opened fails the stream with the missing file — the store does not reach for a
 * durable tier it does not have (with one, {@code PgBodiesReleaseIT} reads the bytes from Postgres instead).
 */
class TieredBlobStoreOpenedStreamTest {

    @Test
    void aSpoolStreamOpenedBeforeAnEvictFailsWithTheMissingFileWithoutADurableTier(@TempDir Path dir) {
        TieredBlobStore s = TieredBlobStoreTest.store(dir, 0, 1 << 20);   // no memory tier: the body is spooled
        BlobKey key = BlobKey.body("m-evicted", 0);
        BlobStoreContract.await(s.write(key, Buffer.buffer(new byte[700]), 4096));
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(key), "fixture");
        ReadStream<Buffer> opened = s.open(key);   // nothing read yet: the file is opened on the first demand
        BlobStoreContract.await(s.evict(key));
        assertEquals(0, TieredBlobStoreTest.spoolFiles(dir).size(), "fixture: the spool file is gone");
        Future<Buffer> read = BlobStoreContract.awaitOutcome(BlobStoreContract.collect(opened));
        assertTrue(read.failed(), "the evicted body cannot be read");
        assertInstanceOf(NoSuchFileException.class, read.cause(), "the missing file, not a durable read: " + read.cause());
    }
}
