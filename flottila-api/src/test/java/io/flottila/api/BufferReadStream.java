package io.flottila.api;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/** Minimal cold ReadStream over one Buffer for the api tests: emits the whole buffer once a handler is set, then ends. */
final class BufferReadStream implements ReadStream<Buffer> {

    private final Buffer data;
    private Handler<Buffer> handler;
    private Handler<Void> endHandler;
    private boolean paused;
    private boolean done;

    BufferReadStream(Buffer data) { this.data = data; }

    @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { return this; }

    @Override public ReadStream<Buffer> handler(Handler<Buffer> h) {
        this.handler = h;
        emit();
        return this;
    }

    @Override public ReadStream<Buffer> pause() { paused = true; return this; }
    @Override public ReadStream<Buffer> resume() { paused = false; emit(); return this; }
    @Override public ReadStream<Buffer> fetch(long amount) { return resume(); }

    @Override public ReadStream<Buffer> endHandler(Handler<Void> h) {
        this.endHandler = h;
        if (done && h != null) h.handle(null);
        return this;
    }

    private void emit() {
        if (done || paused || handler == null) return;
        done = true;
        handler.handle(data.copy());
        if (endHandler != null) endHandler.handle(null);
    }
}
