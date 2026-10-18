package io.flottila.runtime.blob;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;

/**
 * A blocking {@link InputStream} (spool file, S3 object) as a Vert.x {@link ReadStream}: a virtual thread
 * pumps 64 KiB chunks to the handlers and parks while the consumer is paused. The stream is opened lazily
 * on the first demand, so {@code open()} itself never blocks and a failure to open reaches the exception
 * handler like any other.
 */
final class InputStreamReadStream implements ReadStream<Buffer> {

    static final int CHUNK = 64 * 1024;

    private final Callable<InputStream> opener;
    private Handler<Buffer> dataHandler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;
    private boolean paused;
    private long credit;
    private boolean started;
    private boolean done;

    InputStreamReadStream(Callable<InputStream> opener) { this.opener = opener; }

    @Override public synchronized InputStreamReadStream exceptionHandler(Handler<Throwable> handler) { this.exceptionHandler = handler; return this; }

    @Override public synchronized InputStreamReadStream handler(Handler<Buffer> handler) {
        this.dataHandler = handler;
        startIfReady();
        return this;
    }

    @Override public synchronized InputStreamReadStream pause() { paused = true; credit = 0; return this; }

    @Override public synchronized InputStreamReadStream resume() { paused = false; notifyAll(); startIfReady(); return this; }

    @Override public synchronized InputStreamReadStream fetch(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        if (paused) credit = credit > Long.MAX_VALUE - amount ? Long.MAX_VALUE : credit + amount;
        notifyAll();
        startIfReady();
        return this;
    }

    @Override public synchronized InputStreamReadStream endHandler(Handler<Void> handler) { this.endHandler = handler; return this; }

    private void startIfReady() {
        if (started || dataHandler == null || (paused && credit == 0)) return;
        started = true;
        Thread.ofVirtual().name("flottila-blob-read").start(this::pump);
    }

    private void pump() {
        try (InputStream in = opener.call()) {
            byte[] buf = new byte[CHUNK];
            while (true) {
                awaitDemand();
                int n = in.read(buf);
                if (n < 0) break;
                Buffer chunk = Buffer.buffer(n).appendBytes(buf, 0, n);
                Handler<Buffer> h;
                synchronized (this) { h = dataHandler; }
                h.handle(chunk);
            }
            Handler<Void> end;
            synchronized (this) { done = true; end = endHandler; }
            if (end != null) end.handle(null);
        } catch (Throwable t) {
            Handler<Throwable> eh;
            synchronized (this) { done = true; eh = exceptionHandler; }
            if (eh != null) eh.handle(t instanceof IOException || t instanceof RuntimeException ? t : new IOException(t));
        }
    }

    private synchronized void awaitDemand() throws InterruptedException {
        while (paused && credit == 0) wait();
        if (paused) credit--;
    }

    synchronized boolean done() { return done; }
}
