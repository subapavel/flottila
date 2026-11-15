package io.flottila.runtime.blob;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/** A memory-tier body as a fresh cold stream: the buffer in chunks, honouring pause / resume / fetch. */
final class BufferReadStream implements ReadStream<Buffer> {

    static final int CHUNK = 64 * 1024;

    private final Buffer buffer;
    private int offset;
    private Handler<Buffer> dataHandler;
    private Handler<Void> endHandler;
    private boolean paused;
    private long credit;
    private boolean draining;
    private boolean ended;

    BufferReadStream(Buffer buffer) { this.buffer = buffer; }

    @Override public BufferReadStream exceptionHandler(Handler<Throwable> handler) { return this; }

    @Override public BufferReadStream handler(Handler<Buffer> handler) { this.dataHandler = handler; drain(); return this; }

    @Override public BufferReadStream pause() { paused = true; credit = 0; return this; }

    @Override public BufferReadStream resume() { paused = false; drain(); return this; }

    @Override public BufferReadStream fetch(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        if (paused) credit = credit > Long.MAX_VALUE - amount ? Long.MAX_VALUE : credit + amount;
        drain();
        return this;
    }

    @Override public BufferReadStream endHandler(Handler<Void> handler) { this.endHandler = handler; drain(); return this; }

    private void drain() {
        if (draining || dataHandler == null) return;
        draining = true;
        try {
            while (!ended && offset < buffer.length() && (!paused || credit > 0)) {
                if (paused) credit--;
                int n = Math.min(CHUNK, buffer.length() - offset);
                Buffer c = buffer.slice(offset, offset + n).copy();
                offset += n;
                dataHandler.handle(c);
            }
            if (!ended && offset >= buffer.length() && (!paused || credit > 0) && endHandler != null) {
                ended = true;
                endHandler.handle(null);
            }
        } finally {
            draining = false;
        }
    }
}
