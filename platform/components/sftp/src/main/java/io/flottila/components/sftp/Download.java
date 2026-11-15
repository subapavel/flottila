package io.flottila.components.sftp;

import io.flottila.api.InputStreamReadStream;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * One remote file as the stream a {@link io.flottila.api.BlobStore} reads: an {@link InputStreamReadStream} over the
 * stream {@code remote} opens (an {@code SftpClient.read}), which the round {@link #settle settles} once the store's write
 * completed. A store that failed the write (the body above {@code maxBodyBytes}) pauses the stream for good and its pumping
 * thread would park with the remote handle open: settling a failed write abandons the stream instead — a pause from then on
 * is ignored, the pump wakes, its next read fails and it closes the remote stream — and waits for that close, so the file
 * is never moved or deleted while the consumer still holds it open.
 */
final class Download implements ReadStream<Buffer> {

    private final Callable<InputStream> remote;
    private final InputStreamReadStream pump = new InputStreamReadStream(this::open);
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile boolean abandoned;   // written under this; read by the pumping thread without it
    private boolean opening;              // guarded by this: the remote stream was asked for, so it will be closed

    Download(Callable<InputStream> remote) {
        this.remote = remote;
    }

    /** On the pumping thread, at the first demand: the remote stream, which reports its close; refused once abandoned. */
    private InputStream open() throws Exception {
        synchronized (this) {
            if (abandoned) throw new IOException("the download was abandoned before it began");
            opening = true;
        }
        InputStream in;
        try {
            in = remote.call();
        } catch (Exception | Error failed) {
            closed.complete(null);   // nothing is open
            throw failed;
        }
        return new FilterInputStream(in) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (abandoned) throw new IOException("the download was abandoned");
                return super.read(b, off, len);
            }

            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    closed.complete(null);
                }
            }
        };
    }

    /**
     * Ends the download once the store's write completed: a failed write abandons the stream (see the class comment); then
     * waits until the remote stream is closed, if it was ever asked for. Blocks: call it on the round's virtual thread.
     */
    void settle(boolean writeFailed) {
        boolean wait;
        synchronized (this) {
            if (writeFailed) {
                abandoned = true;
                pump.resume();   // wakes a pump the store paused; its next read fails and closes the remote stream
            }
            wait = opening;
        }
        if (wait) closed.join();
    }

    @Override public Download exceptionHandler(Handler<Throwable> handler) { pump.exceptionHandler(handler); return this; }

    @Override public Download handler(Handler<Buffer> handler) { pump.handler(handler); return this; }

    /** The store's back-pressure; ignored once abandoned, so a pause that lands after {@link #settle} cannot park the pump again. */
    @Override public synchronized Download pause() {
        if (!abandoned) pump.pause();
        return this;
    }

    @Override public Download resume() { pump.resume(); return this; }

    @Override public Download fetch(long amount) { pump.fetch(amount); return this; }

    @Override public Download endHandler(Handler<Void> handler) { pump.endHandler(handler); return this; }
}
