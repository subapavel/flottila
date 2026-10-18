package io.flottila.runtime.blob;

import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** One encrypted spool file, written to a temporary name and moved into place on finish. */
final class SpoolSink implements Sink {

    private final Path target;
    private final Path temp;
    private final OutputStream out;

    SpoolSink(SpoolCipher cipher, Path target) throws IOException {
        this.target = target;
        this.temp = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(target.getParent());
        this.out = cipher.encrypting(temp);
    }

    @Override public void write(Buffer chunk) throws IOException { out.write(chunk.getBytes()); }

    @Override public void finish() throws IOException {
        out.close();
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    @Override public void abort() {
        try { out.close(); } catch (IOException ignored) { /* the file is deleted next */ }
        try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* best effort: a .part file is never read */ }
    }
}
