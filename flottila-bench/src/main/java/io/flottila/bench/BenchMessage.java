package io.flottila.bench;

import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.vertx.core.buffer.Buffer;

import java.util.Objects;

/**
 * One message row of the V1 schema as the prototype wrote it: id + step, flow, the payload bytes and
 * their content hash (dedup by hash in {@code payloads}).
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

    /** The hash as the 8-byte big-endian bytea the V1 schema keys payloads by. */
    public byte[] hashBytes() {
        byte[] b = new byte[8];
        long v = hash.value();
        for (int i = 7; i >= 0; i--) { b[i] = (byte) (v & 0xff); v >>>= 8; }
        return b;
    }
}
