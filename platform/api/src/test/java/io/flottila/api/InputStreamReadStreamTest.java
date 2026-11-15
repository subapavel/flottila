package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A blocking InputStream pumped by a virtual thread, with Vert.x flow control and failure semantics. */
class InputStreamReadStreamTest {

    static byte[] bytes(int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) (i * 7); return b; }

    @Test
    void pumpsTheStreamInChunksAndEndsOnce() throws Exception {
        byte[] src = bytes(InputStreamReadStream.CHUNK * 2 + 5);
        InputStreamReadStream s = new InputStreamReadStream(() -> new ByteArrayInputStream(src));
        Buffer all = Buffer.buffer();
        CountDownLatch ended = new CountDownLatch(1);
        int[] ends = {0};
        s.exceptionHandler(t -> { throw new AssertionError(t); });
        s.endHandler(v -> { ends[0]++; ended.countDown(); });
        s.handler(all::appendBuffer);
        assertTrue(ended.await(10, TimeUnit.SECONDS));
        assertEquals(Buffer.buffer(src), all);
        assertEquals(1, ends[0]);
        assertTrue(s.done());
    }

    @Test
    void pausedBeforeTheHandlerNothingIsReadUntilFetchOrResume() throws Exception {
        byte[] src = bytes(InputStreamReadStream.CHUNK * 3);
        InputStreamReadStream s = new InputStreamReadStream(() -> new ByteArrayInputStream(src));
        List<Integer> seen = new ArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        s.pause();
        s.endHandler(v -> ended.countDown());
        s.handler(c -> { synchronized (seen) { seen.add(c.length()); } first.countDown(); });
        assertFalse(s.done());
        assertEquals(0, seen.size(), "paused: the pump has not even started");
        s.fetch(1);
        assertTrue(first.await(10, TimeUnit.SECONDS));
        assertEquals(1, seen.size(), "one chunk of credit, one chunk delivered");
        assertFalse(s.done(), "the pump parks for more demand instead of ending");
        s.fetch(Long.MAX_VALUE - 1);
        s.fetch(5);                       // saturates
        assertTrue(ended.await(10, TimeUnit.SECONDS));
        assertEquals(3, seen.size());
        assertThrows(IllegalArgumentException.class, () -> s.fetch(-1));
        InputStreamReadStream flowing = new InputStreamReadStream(() -> new ByteArrayInputStream(bytes(3)));
        flowing.resume();                 // resume and fetch before any handler: nothing starts without a consumer
        flowing.fetch(1);
        assertFalse(flowing.done());
        CountDownLatch done = new CountDownLatch(1);
        flowing.endHandler(v -> done.countDown());
        flowing.handler(c -> {});
        flowing.handler(c -> {});         // setting the handler again does not start a second pump
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    @Test
    void resumeAfterAPauseMidStreamContinuesWhereItStopped() throws Exception {
        byte[] src = bytes(InputStreamReadStream.CHUNK * 4);
        InputStreamReadStream s = new InputStreamReadStream(() -> new ByteArrayInputStream(src));
        Buffer all = Buffer.buffer();
        CountDownLatch ended = new CountDownLatch(1);
        CountDownLatch pausedAfterFirstChunk = new CountDownLatch(1);
        s.endHandler(v -> ended.countDown());
        s.handler(c -> {
            synchronized (all) { all.appendBuffer(c); }
            if (all.length() == InputStreamReadStream.CHUNK) { s.pause(); pausedAfterFirstChunk.countDown(); }
        });
        assertTrue(pausedAfterFirstChunk.await(10, TimeUnit.SECONDS));
        assertFalse(s.done(), "paused after the first chunk: the pump waits");
        s.resume();
        assertTrue(ended.await(10, TimeUnit.SECONDS));
        assertEquals(Buffer.buffer(src), all);
    }

    @Test
    void anOpenerThatFailsReachesTheExceptionHandlerWrappedWhenItIsNotAnIoOrRuntimeException() throws Exception {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CountDownLatch got = new CountDownLatch(1);
        InputStreamReadStream io = new InputStreamReadStream(() -> { throw new IOException("gone"); });
        io.exceptionHandler(t -> { seen.set(t); got.countDown(); });
        io.handler(c -> {});
        assertTrue(got.await(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, seen.get());
        assertEquals("gone", seen.get().getMessage());
        assertTrue(io.done());

        CountDownLatch got2 = new CountDownLatch(1);
        AtomicReference<Throwable> seen2 = new AtomicReference<>();
        InputStreamReadStream rt = new InputStreamReadStream(() -> { throw new IllegalStateException("bad state"); });
        rt.exceptionHandler(t -> { seen2.set(t); got2.countDown(); });
        rt.handler(c -> {});
        assertTrue(got2.await(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, seen2.get());

        CountDownLatch got3 = new CountDownLatch(1);
        AtomicReference<Throwable> seen3 = new AtomicReference<>();
        InputStreamReadStream checked = new InputStreamReadStream(() -> { throw new Exception("checked"); });
        checked.exceptionHandler(t -> { seen3.set(t); got3.countDown(); });
        checked.handler(c -> {});
        assertTrue(got3.await(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, seen3.get());
        assertEquals("checked", seen3.get().getCause().getMessage());
    }

    @Test
    void withoutEndOrExceptionHandlersTheStreamStillFinishesQuietly() throws Exception {
        InputStreamReadStream s = new InputStreamReadStream(() -> new ByteArrayInputStream(bytes(10)));
        s.handler(c -> {});
        waitUntil(s::done);
        InputStreamReadStream failing = new InputStreamReadStream(() -> { throw new IOException("x"); });
        failing.handler(c -> {});
        waitUntil(failing::done);
    }

    static void waitUntil(java.util.function.BooleanSupplier state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!state.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("state not reached");
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
