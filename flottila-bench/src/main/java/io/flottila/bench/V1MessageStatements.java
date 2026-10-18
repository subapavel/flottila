package io.flottila.bench;

import io.flottila.runtime.batch.PgBatchSink;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The prototype's fixed-shape unnest inserts into the V1 tables ({@code payloads} by hash with
 * {@code ON CONFLICT DO NOTHING}, then {@code messages} by (id, step)); one batch = one transaction
 * through {@link PgBatchSink}.
 *
 * @since 0.1
 */
public final class V1MessageStatements {

    private V1MessageStatements() {}

    static final String PAYLOADS = "insert into payloads(hash, size, body) select * from unnest($1::bytea[], $2::bigint[], $3::bytea[]) on conflict (hash) do nothing";
    static final String MESSAGES = "insert into messages(id, step, flow, headers, props, keys, attachments, body_hash)"
            + " select * from unnest($1::text[], $2::int[], $3::text[], $4::jsonb[], $5::jsonb[], $6::jsonb[], $7::jsonb[], $8::bytea[])"
            + " on conflict (id, step) do nothing";

    public static List<PgBatchSink.Statement<BenchMessage>> all() {
        return List.of(new PgBatchSink.Statement<>(PAYLOADS, V1MessageStatements::payloads),
                new PgBatchSink.Statement<>(MESSAGES, V1MessageStatements::messages));
    }

    static Tuple payloads(List<BenchMessage> batch) {
        Map<String, BenchMessage> distinct = new LinkedHashMap<>();
        for (BenchMessage m : batch) distinct.putIfAbsent(m.hash().hex(), m);
        // bytea[] parameters: the Vert.x pg client encodes them from Buffer[] only (javap DataType.BYTEA_ARRAY), never from byte[][]
        List<Buffer> hashes = new ArrayList<>(); List<Long> sizes = new ArrayList<>(); List<Buffer> bodies = new ArrayList<>();
        for (BenchMessage m : distinct.values()) { hashes.add(Buffer.buffer(m.hashBytes())); sizes.add(m.size()); bodies.add(m.body()); }
        return Tuple.wrap(new Object[] {hashes.toArray(new Buffer[0]), sizes.toArray(new Long[0]), bodies.toArray(new Buffer[0])});
    }

    static Tuple messages(List<BenchMessage> batch) {
        int n = batch.size();
        String[] ids = new String[n]; Integer[] steps = new Integer[n]; String[] flows = new String[n];
        String[] empty = new String[n]; Buffer[] hashes = new Buffer[n];
        for (int i = 0; i < n; i++) {
            BenchMessage m = batch.get(i);
            ids[i] = m.id(); steps[i] = m.step(); flows[i] = m.flow(); empty[i] = "{}"; hashes[i] = Buffer.buffer(m.hashBytes());
        }
        return Tuple.wrap(new Object[] {ids, steps, flows, empty, empty, empty, empty, hashes});
    }
}
