package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.testkit.BlobStoreContract;
import io.flottila.testkit.GeneratedReadStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A spool write whose file cannot be moved into place (CORE-4b, implementer: the failing-sink cases went with the S3 tier):
 * the write fails, the sink is aborted, no {@code .part} file stays and the key is not indexed. The spool file's name is the
 * documented one (FIX-35: {@code <messageId>/<step>-<part>-<n>.blob}, {@code n} counting the store's spool names from 1);
 * a non-empty directory under that name makes the final move fail.
 */
class SpoolSinkFailureTest {

    private static TieredBlobStore spoolOnly(Path spool) {
        return TieredBlobStoreTest.store(spool, 0, 1L << 20);   // no memory tier: every body is spooled
    }

    private static Path blockFirstSpoolFile(Path spool, String id) throws IOException {
        Path target = spool.resolve(id).resolve("0-body-1.blob");
        Files.createDirectories(target);
        Files.writeString(target.resolve("in-the-way"), "x");
        return target;
    }

    private static List<String> partFiles(Path dir) throws IOException {
        try (Stream<Path> l = Files.list(dir)) {
            return l.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".part")).toList();
        }
    }

    @Test
    void aWholeWriteWhoseFileCannotBeMovedInFailsAndLeavesNoPartFile(@TempDir Path spool) throws Exception {
        TieredBlobStore s = spoolOnly(spool);
        Path target = blockFirstSpoolFile(spool, "w-1");
        BlobKey key = BlobKey.body("w-1", 0);

        Future<Body> w = BlobStoreContract.awaitOutcome(s.write(key, Buffer.buffer("small body"), 4096));

        assertTrue(w.failed(), "the write fails");
        assertInstanceOf(IOException.class, w.cause());
        assertEquals(List.of(), partFiles(target.getParent()), "the aborted sink left no .part file");
        assertEquals(Optional.empty(), s.location(key), "a failed write is not indexed");
    }

    @Test
    void aStreamedWriteWhoseFileCannotBeMovedInFailsAndLeavesNoPartFile(@TempDir Path spool) throws Exception {
        TieredBlobStore s = spoolOnly(spool);
        Path target = blockFirstSpoolFile(spool, "s-1");
        BlobKey key = BlobKey.body("s-1", 0);

        Future<Body> w = BlobStoreContract.awaitOutcome(s.write(key, new GeneratedReadStream(5000, 600), 10_000));

        assertTrue(w.failed(), "the write fails");
        assertInstanceOf(IOException.class, w.cause());
        assertEquals(List.of(), partFiles(target.getParent()), "the aborted sink left no .part file");
        assertEquals(Optional.empty(), s.location(key), "a failed write is not indexed");
    }
}
