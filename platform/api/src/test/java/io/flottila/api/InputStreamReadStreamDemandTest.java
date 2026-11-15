package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demand on the public stream (P2-02, D-076): a fetch delivers exactly that many chunks and the pump parks for
 * more, a pause drops the outstanding demand, a resume or a fetch starts a stream paused before its handler,
 * only end-of-stream ends it, a read failure reaches the exception handler and never the end handler, and the
 * input stream is closed once either way. "Parked" is witnessed by the pump thread's own state (WAITING in the
 * demand wait, or TERMINATED had it run on), so a count taken after it cannot be vacuous.
 */
class InputStreamReadStreamDemandTest {

    static final int CHUNK = InputStreamReadStream.CHUNK;

    @Test
    void everyFluentCallReturnsTheStreamItself() {
        InputStreamReadStream s = new InputStreamReadStream(() -> new ByteArrayInputStream(new byte[1]));
        assertSame(s, s.pause());
        assertSame(s, s.exceptionHandler(t -> { }));
        assertSame(s, s.endHandler(v -> { }));
        assertSame(s, s.fetch(0));
        assertSame(s, s.handler(b -> { }));
        assertSame(s, s.fetch(1));
        assertSame(s, s.resume());
    }

    @Test
    void aFetchDeliversExactlyThatManyChunksOnAVirtualThreadAndThePumpParksForMore() {
        Counting src = new Counting(6 * CHUNK);
        InputStreamReadStream s = new InputStreamReadStream(() -> src);
        Pump pump = new Pump();
        s.pause();
        s.endHandler(v -> pump.ends.incrementAndGet());
        s.handler(pump::take);

        s.fetch(2);
        pump.parkedAfter(2);
        assertEquals(2, pump.chunks.size(), "fetch(2) is two chunks, then the pump waits for demand");
        assertTrue(pump.thread.get().isVirtual(), "the pump is a virtual thread, never a platform thread");
        assertFalse(s.done());

        s.fetch(1);
        pump.parkedAfter(3);
        assertEquals(3, pump.chunks.size(), "a fetch wakes the parked pump for exactly one more chunk");

        assertDoesNotThrow(() -> s.fetch(0), "a zero fetch is no error");
        s.fetch(1);
        pump.parkedAfter(4);
        assertEquals(4, pump.chunks.size(), "fetch(0) added no demand: the next fetch(1) is one chunk, not two");
        assertFalse(s.done());

        s.resume();
        pump.finished();
        assertEquals(6, pump.chunks.size());
        assertTrue(pump.chunks.stream().allMatch(b -> b.length() == CHUNK));
        assertEquals(Set.of(pump.thread.get()), pump.threads, "every chunk comes from the one pump");
        assertEquals(1, pump.ends.get());
        assertTrue(s.done());
    }

    @Test
    void aFetchBeforeTheHandlerStartsThePumpOnceTheHandlerIsSet() {
        InputStreamReadStream s = new InputStreamReadStream(() -> new ByteArrayInputStream(new byte[3 * CHUNK]));
        Pump pump = new Pump();
        s.pause();
        s.fetch(1);
        s.handler(pump::take);
        pump.parkedAfter(1);
        assertEquals(1, pump.chunks.size(), "the credit given before the handler is spent once the handler is set");
        assertFalse(s.done());
    }

    @Test
    void aResumeStartsAStreamThatWasPausedBeforeItsHandler() {
        byte[] bytes = InputStreamReadStreamTest.bytes(2 * CHUNK + 3);
        InputStreamReadStream s = new InputStreamReadStream(() -> new ByteArrayInputStream(bytes));
        Pump pump = new Pump();
        s.pause();
        s.endHandler(v -> pump.ends.incrementAndGet());
        s.handler(pump::take);
        s.resume();
        pump.finished();
        assertEquals(Buffer.buffer(bytes), pump.all());
        assertEquals(1, pump.ends.get());
    }

    @Test
    void aPauseMidStreamDropsTheOutstandingDemand() {
        byte[] bytes = InputStreamReadStreamTest.bytes(5 * CHUNK);
        InputStreamReadStream s = new InputStreamReadStream(() -> new ByteArrayInputStream(bytes));
        Pump pump = new Pump();
        s.pause();
        s.endHandler(v -> pump.ends.incrementAndGet());
        s.handler(b -> { pump.take(b); if (pump.chunks.size() == 1) s.pause(); });
        s.fetch(4);
        pump.parkedAfter(1);
        assertEquals(1, pump.chunks.size(), "the pause after the first chunk dropped the three chunks of credit left");
        assertFalse(s.done());

        s.resume();
        pump.finished();
        assertEquals(Buffer.buffer(bytes), pump.all());
        assertEquals(1, pump.ends.get());
    }

    @Test
    void onlyEndOfStreamEndsTheStreamAZeroByteReadDoesNot() {
        byte[] a = InputStreamReadStreamTest.bytes(100);
        byte[] b = InputStreamReadStreamTest.bytes(7);
        Scripted src = new Scripted().data(a).zero().data(b);
        InputStreamReadStream s = new InputStreamReadStream(() -> src);
        Pump pump = new Pump();
        s.endHandler(v -> pump.ends.incrementAndGet());
        s.exceptionHandler(t -> { throw new AssertionError(t); });
        s.handler(pump::take);
        pump.finished();
        assertEquals(Buffer.buffer(a).appendBytes(b), pump.all(), "the bytes after a zero-byte read are not lost");
        assertEquals(1, pump.ends.get());
        assertEquals(1, src.closes.get(), "the input stream is closed once after the end");
    }

    @Test
    void aReadFailureMidStreamReachesTheExceptionHandlerNeverTheEndHandlerAndClosesTheStream() {
        IOException gone = new IOException("disk gone");
        Scripted src = new Scripted().data(InputStreamReadStreamTest.bytes(10)).fail(gone).data(new byte[5]);
        InputStreamReadStream s = new InputStreamReadStream(() -> src);
        Pump pump = new Pump();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        s.endHandler(v -> pump.ends.incrementAndGet());
        s.exceptionHandler(failed::set);
        s.handler(pump::take);
        pump.finished();
        assertSame(gone, failed.get(), "the read's own IOException, unwrapped");
        assertEquals(1, pump.chunks.size(), "nothing is read after the failure");
        assertEquals(0, pump.ends.get(), "a failed stream never ends");
        assertEquals(1, src.closes.get(), "the input stream is closed once after the failure");
        assertTrue(s.done());
    }

    /** Records the chunks and the thread that delivered them, and waits on that thread's state. */
    static final class Pump {
        final List<Buffer> chunks = new CopyOnWriteArrayList<>();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final AtomicInteger ends = new AtomicInteger();

        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        void take(Buffer b) {
            thread.compareAndSet(null, Thread.currentThread());
            threads.add(Thread.currentThread());
            chunks.add(b);
        }

        Buffer all() {
            Buffer all = Buffer.buffer();
            chunks.forEach(all::appendBuffer);
            return all;
        }

        /** At least {@code n} chunks arrived and the pump then parked for demand or finished: the count is final. */
        void parkedAfter(int n) {
            until(() -> chunks.size() >= n && thread.get() != null, "chunk " + n + " never arrived");
            Thread t = thread.get();
            until(() -> t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TERMINATED,
                    "the pump neither parked nor finished");
        }

        /** The pump thread has terminated: every handler call it would make has been made. */
        void finished() {
            until(() -> thread.get() != null, "no chunk ever arrived");
            Thread t = thread.get();
            until(() -> t.getState() == Thread.State.TERMINATED, "the pump never finished");
        }
    }

    /** A byte array input stream that counts its closes. */
    static final class Counting extends ByteArrayInputStream {
        final AtomicInteger closes = new AtomicInteger();
        Counting(int size) { super(new byte[size]); }
        @Override public void close() { closes.incrementAndGet(); }
    }

    /** An input stream that plays a script: data, a zero-byte read, or a failure; then end of stream. */
    static final class Scripted extends InputStream {
        private final Deque<Object> script = new ArrayDeque<>();
        final AtomicInteger closes = new AtomicInteger();

        Scripted data(byte[] bytes) { script.add(bytes); return this; }
        Scripted zero() { script.add(0); return this; }
        Scripted fail(IOException e) { script.add(e); return this; }

        @Override public int read() { throw new UnsupportedOperationException("the pump reads in chunks"); }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            Object next = script.poll();
            if (next == null) return -1;
            if (next instanceof IOException e) throw e;
            if (next instanceof Integer) return 0;
            byte[] bytes = (byte[]) next;
            System.arraycopy(bytes, 0, b, off, bytes.length);
            return bytes.length;
        }

        @Override public void close() { closes.incrementAndGet(); }
    }

    static void until(BooleanSupplier state, String failure) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!state.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError(failure);
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
