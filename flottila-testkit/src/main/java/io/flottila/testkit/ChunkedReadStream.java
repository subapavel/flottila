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
 * failure. For single-threaded use in tests.
 *
 * @since 0.1
 */
public final class ChunkedReadStream implements ReadStream<Buffer> {

    private final List<Buffer> chunks = new ArrayList<>();
    private final Throwable failure;

    private Handler<Buffer> dataHandler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;
    private boolean paused;
    private long credit;
    private int next;
    private boolean draining;
    private boolean finished;

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
    public int delivered() { return next; }

    @Override public ChunkedReadStream exceptionHandler(Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        drain();
        return this;
    }

    @Override public ChunkedReadStream handler(Handler<Buffer> handler) {
        this.dataHandler = handler;
        drain();
        return this;
    }

    @Override public ChunkedReadStream pause() {
        paused = true;
        credit = 0;
        return this;
    }

    @Override public ChunkedReadStream resume() {
        paused = false;
        drain();
        return this;
    }

    @Override public ChunkedReadStream fetch(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        if (paused) credit = credit > Long.MAX_VALUE - amount ? Long.MAX_VALUE : credit + amount;
        drain();
        return this;
    }

    @Override public ChunkedReadStream endHandler(Handler<Void> handler) {
        this.endHandler = handler;
        drain();
        return this;
    }

    private void drain() {
        if (draining || dataHandler == null) return;
        draining = true;
        try {
            while (dataHandler != null && next < chunks.size() && (!paused || credit > 0)) {
                if (paused) credit--;
                dataHandler.handle(chunks.get(next++).copy());
            }
            if (next == chunks.size() && !finished) terminate();
        } finally {
            draining = false;
        }
    }

    private void terminate() {
        if (failure != null) {
            if (exceptionHandler == null) return;
            finished = true;
            exceptionHandler.handle(failure);
        } else if (endHandler != null) {
            finished = true;
            endHandler.handle(null);
        }
    }
}
