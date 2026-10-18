package io.flottila.runtime.blob;

import io.vertx.core.buffer.Buffer;

import java.io.IOException;

/**
 * Where spilled bytes go, written from one virtual thread in order: the encrypted spool file or an S3
 * (multipart) upload. {@link #finish()} commits, {@link #abort()} leaves nothing behind.
 */
interface Sink {
    void write(Buffer chunk) throws IOException;

    void finish() throws IOException;

    void abort();
}
