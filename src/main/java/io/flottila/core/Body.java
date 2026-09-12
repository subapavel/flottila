package io.flottila.core;

import io.vertx.core.buffer.Buffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bytes, never a consumable stream. open() always returns a fresh stream. */
public sealed interface Body permits Body.Bytes, Body.Spilled, Body.Empty {

    InputStream open();
    long size();

    default byte[] bytes() {
        try (InputStream in = open()) { return in.readAllBytes(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    default String text() { return new String(bytes(), StandardCharsets.UTF_8); }

    record Bytes(Buffer buf) implements Body {
        public InputStream open() { return new ByteArrayInputStream(buf.getBytes()); }
        public long size() { return buf.length(); }
    }

    record Spilled(Path file, long size) implements Body {
        public InputStream open() {
            try { return Files.newInputStream(file); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }

    record Empty() implements Body {
        public InputStream open() { return InputStream.nullInputStream(); }
        public long size() { return 0; }
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
