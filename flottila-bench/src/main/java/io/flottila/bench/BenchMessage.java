package io.flottila.bench;

import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.vertx.core.buffer.Buffer;

import java.util.Objects;

/**
 * One message row as the prototype wrote it: id + step, flow, the payload bytes and their content hash (dedup visible as
 * equal hash and algorithm across {@code messages} rows since V11, D-097).
 *
 * @since 0.1
 */
public record BenchMessage(String id, int step, String flow, Buffer body, Hash hash) {

    public BenchMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(hash, "hash");
    }

    public static BenchMessage of(String id, int step, String flow, Buffer body, Hasher hasher) {
        return new BenchMessage(id, step, flow, body, hasher.hashOf(body));
    }

    public long size() { return body.length(); }

    /** The hash as the 8-byte big-endian bytea {@code messages.body_hash} holds. */
    public byte[] hashBytes() {
        byte[] b = new byte[8];
        long v = hash.value();
        for (int i = 7; i >= 0; i--) { b[i] = (byte) (v & 0xff); v >>>= 8; }
        return b;
    }
}
