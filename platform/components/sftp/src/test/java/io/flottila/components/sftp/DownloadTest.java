package io.flottila.components.sftp;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The download a round hands to the BlobStore: settled after the write, it never leaves the remote stream open or its pump parked. */
@Timeout(30)
class DownloadTest {

    /** A remote stream of {@code size} bytes that records its close. */
    static final class Remote extends ByteArrayInputStream {
        final AtomicBoolean closed = new AtomicBoolean();

        Remote(int size) { super(new byte[size]); }

        @Override public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), what);
    }

    @Test
    void aFailedWriteAbandonsTheStreamTheStorePausedAndWaitsForTheRemoteClose() throws Exception {
        Remote remote = new Remote(1 << 20);
        Download download = new Download(() -> remote);
        CountDownLatch firstChunk = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        download.exceptionHandler(t -> { failures.add(t); failed.countDown(); });
        download.handler(chunk -> { download.pause(); firstChunk.countDown(); });   // the store's limit: paused for good
        await(firstChunk, "the pump delivered a chunk");
        download.settle(true);
        assertTrue(remote.closed.get(), "settle returned only once the remote stream was closed");
        await(failed, "the woken pump failed its next read");
        assertInstanceOf(IOException.class, failures.get(0));
        assertTrue(failures.get(0).getMessage().contains("abandoned"), "" + failures.get(0));
    }

    @Test
    void aSucceededWriteWaitsForTheRemoteClose() throws Exception {
        Remote remote = new Remote(100_000);
        Download download = new Download(() -> remote);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger bytes = new AtomicInteger();
        download.endHandler(v -> ended.countDown());   // before the handler: the pump starts at once
        download.handler(chunk -> bytes.addAndGet(chunk.length()));
        await(ended, "the download ended");
        download.settle(false);
        assertTrue(remote.closed.get(), "closed before settle returned");
        assertEquals(100_000, bytes.get());
    }

    @Test
    void aDownloadAbandonedBeforeItBeganNeverOpensTheRemoteAndIgnoresALatePause() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        Download download = new Download(() -> { opened.incrementAndGet(); return new Remote(10); });
        download.settle(true);   // the store failed before it attached: nothing to wait for
        download.pause();        // a pause landing after the settle: ignored, it would park the pump for good
        CountDownLatch failed = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        download.exceptionHandler(t -> { failures.add(t); failed.countDown(); });
        download.handler(chunk -> { throw new AssertionError("no byte flows from an abandoned download"); });
        await(failed, "the pump started (the late pause was ignored) and the opener refused");
        assertEquals(0, opened.get(), "the remote stream was never opened");
        assertTrue(failures.get(0).getMessage().contains("before it began"), "" + failures.get(0));
    }

    @Test
    void anOpenerThatFailsLeavesNothingToWaitFor() throws Exception {
        Download download = new Download(() -> { throw new IOException("no such file"); });
        CountDownLatch failed = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        download.exceptionHandler(t -> { failures.add(t); failed.countDown(); });
        download.handler(chunk -> { });
        await(failed, "the failed open reached the exception handler");
        assertEquals("no such file", failures.get(0).getMessage());
        download.settle(true);   // returns: the failed open counts as closed
    }

    @Test
    void fetchHandsOnDemandToThePausedPump() throws Exception {
        InputStream remote = new Remote(10);
        Download download = new Download(() -> remote);
        CountDownLatch chunk = new CountDownLatch(1);
        List<Buffer> chunks = new CopyOnWriteArrayList<>();
        download.pause();
        download.handler(b -> { chunks.add(b); chunk.countDown(); });   // paused without credit: the pump does not start
        assertEquals(List.of(), chunks);
        download.fetch(1);
        await(chunk, "the fetched chunk arrived");
        assertEquals(10, chunks.get(0).length());
        download.resume();
    }
}
