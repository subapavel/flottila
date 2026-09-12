package io.flottila.core;

import io.vertx.core.buffer.Buffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bytes, never a consumable stream. open() always returns a fresh stream.
 * Payloads are content-addressed: hash() is the xxh3-64 of the content, computed lazily once
 * per Body instance — envelopes sharing a Body share the hash without rehashing.
 * (Implementations are final classes, not records, exactly so the hash can be memoized.)
 */
public sealed interface Body permits Body.Bytes, Body.Spilled, Body.Empty {

    InputStream open();
    long size();
    /** Content hash (xxh3-64), lazily memoized per instance. */
    long hash();

    default byte[] bytes() {
        try (InputStream in = open()) { return in.readAllBytes(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    default String text() { return new String(bytes(), StandardCharsets.UTF_8); }

    final class Bytes implements Body {
        private final Buffer buf;
        private volatile Long hash;                       // benign race: recompute is idempotent

        public Bytes(Buffer buf) { this.buf = buf; }
        public Buffer buf() { return buf; }
        public InputStream open() { return new ByteArrayInputStream(buf.getBytes()); }
        public long size() { return buf.length(); }
        public long hash() { Long h = hash; if (h == null) hash = h = Hashing.hash(buf.getBytes()); return h; }
    }

    final class Spilled implements Body {
        private final Path file;
        private final long size;
        private volatile Long hash;

        public Spilled(Path file, long size) { this.file = file; this.size = size; }
        public Path file() { return file; }
        public InputStream open() {
            try { return Files.newInputStream(file); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        public long size() { return size; }
        public long hash() { Long h = hash; if (h == null) hash = h = Hashing.hash(bytes()); return h; }
    }

    final class Empty implements Body {
        private static final long EMPTY_HASH = Hashing.hash(new byte[0]);
        public InputStream open() { return InputStream.nullInputStream(); }
        public long size() { return 0; }
        public long hash() { return EMPTY_HASH; }
    }

    static Body empty() { return new Empty(); }
    static Body of(String s) { return new Bytes(Buffer.buffer(s, "UTF-8")); }
    static Body of(byte[] b) { return new Bytes(Buffer.buffer(b)); }

    /** Decide in-memory vs. spilled at ingress; the rest of the pipeline never cares. */
    static Body of(Buffer buf, int inMemoryLimit) {
        if (buf.length() <= inMemoryLimit) return new Bytes(buf);
        try {
            Path tmp = Files.createTempFile("flottila-", ".body");
            Files.write(tmp, buf.getBytes());
            tmp.toFile().deleteOnExit();
            return new Spilled(tmp, buf.length());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
