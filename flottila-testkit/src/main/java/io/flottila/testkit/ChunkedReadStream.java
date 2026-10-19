package io.flottila.testkit;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.ArrayList;
import java.util.List;

/**
 * A cold, synchronous {@link ReadStream} over fixed chunks. Nothing moves until a data handler is
 * set; it then delivers a fresh copy of each chunk as long as there is demand ({@link #pause()},
 * {@link #resume()}, {@link #fetch(long)}), and finally ends once, or fails once when built with a
 * failure. Synchronous: chunks are delivered on the thread that set the handler or created demand. A
 * {@code resume()}, {@code fetch()}, {@code endHandler()} or {@code exceptionHandler()} from another thread that
 * lands while a thread is draining is honoured after the current delivery, on the draining thread, except when a
 * handler throws: the drain then stops at once with the stream drainable again, and a call that landed meanwhile is
 * not re-driven — the next {@code handler()}, {@code resume()}, {@code fetch()}, {@code endHandler()} or
 * {@code exceptionHandler()} drains. The handlers are never invoked under the stream's lock and never re-entered.
 *
 * @since 0.1
 */
public final class ChunkedReadStream implements ReadStream<Buffer> {

    private final List<Buffer> chunks = new ArrayList<>();
    private final Throwable failure;

    private final Object lock = new Object();
    // all guarded by lock
    private Handler<Buffer> dataHandler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;
    private boolean paused;
    private long credit;
    private int next;
    private boolean draining;
    private boolean rerun;
    private boolean finished;
    private Runnable beforeDrainExit;

    /** A stream of {@code chunks} (each a {@code String} or a {@link Buffer}) that ends normally. */
    public ChunkedReadStream(List<?> chunks) {
        this(chunks, null);
    }

    /** As {@link #ChunkedReadStream(List)}, but calls the exception handler instead of ending when {@code failAfterChunks} is set. */
    public ChunkedReadStream(List<?> chunks, Throwable failAfterChunks) {
        for (Object c : chunks) {
            if (c instanceof String s) this.chunks.add(Buffer.buffer(s));
            else if (c instanceof Buffer b) this.chunks.add(b.copy());
            else throw new IllegalArgumentException("a chunk is a String or a Buffer, not " + c);
        }
        this.failure = failAfterChunks;
    }

    /** How many chunks have been handed out so far. */
    public int delivered() {
        synchronized (lock) { return next; }
    }

    /**
     * Test-only: {@code hook} runs on the draining thread, outside the lock, after the delivery loop stopped and the
     * end / failure check ran, before the drain decides between going round again and leaving — the window a call
     * from another thread must be honoured in. {@code null} (the default) = none.
     */
    void beforeDrainExit(Runnable hook) {
        synchronized (lock) { this.beforeDrainExit = hook; }
    }

    @Override public ChunkedReadStream exceptionHandler(Handler<Throwable> handler) {
        synchronized (lock) { this.exceptionHandler = handler; }
        drain();
        return this;
    }

    @Override public ChunkedReadStream handler(Handler<Buffer> handler) {
        synchronized (lock) { this.dataHandler = handler; }
        drain();
        return this;
    }

    @Override public ChunkedReadStream pause() {
        synchronized (lock) {
            paused = true;
            credit = 0;
        }
        return this;
    }

    @Override public ChunkedReadStream resume() {
        synchronized (lock) { paused = false; }
        drain();
        return this;
    }

    @Override public ChunkedReadStream fetch(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        synchronized (lock) {
            if (paused) credit = credit > Long.MAX_VALUE - amount ? Long.MAX_VALUE : credit + amount;
        }
        drain();
        return this;
    }

    @Override public ChunkedReadStream endHandler(Handler<Void> handler) {
        synchronized (lock) { this.endHandler = handler; }
        drain();
        return this;
    }

    /**
     * Delivers while there is demand, then ends or fails once when due. One thread drains at a time; a call that finds
     * the stream draining (another thread, or a handler itself) only asks the drainer to go round once more.
     */
    private void drain() {
        synchronized (lock) {
            if (draining) {
                rerun = true;
                return;
            }
            if (dataHandler == null) return;
            draining = true;
        }
        try {
            while (true) {
                while (true) {
                    Handler<Buffer> h;
                    Buffer chunk;
                    synchronized (lock) {
                        if (dataHandler == null || next >= chunks.size() || (paused && credit <= 0)) break;
                        if (paused) credit--;
                        h = dataHandler;
                        chunk = chunks.get(next++);
                    }
                    h.handle(chunk.copy());
                }
                Runnable terminal;
                Runnable hook;
                synchronized (lock) {
                    terminal = next == chunks.size() && !finished ? terminate() : null;
                    hook = beforeDrainExit;
                }
                if (terminal != null) terminal.run();
                if (hook != null) hook.run();
                synchronized (lock) {
                    if (rerun) {
                        rerun = false;
                    } else {
                        draining = false;
                        return;
                    }
                }
            }
        } catch (Throwable handlerFailure) {   // only unchecked: a handler threw; the stream stays drainable
            synchronized (lock) {
                draining = false;
                rerun = false;
            }
            throw handlerFailure;
        }
    }

    /** Under the lock: marks the stream finished and returns the one terminal call to make outside it, or null. */
    private Runnable terminate() {
        if (failure != null) {
            if (exceptionHandler == null) return null;
            finished = true;
            Handler<Throwable> h = exceptionHandler;
            return () -> h.handle(failure);
        }
        if (endHandler == null) return null;
        finished = true;
        Handler<Void> h = endHandler;
        return () -> h.handle(null);
    }
}
