package io.flottila.bench;

import io.flottila.runtime.batch.PgBatchSink;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Tuple;

import java.util.List;

/**
 * The prototype's fixed-shape unnest insert into {@code messages} by (id, step, attempt), the attempt V19's default 1 (FIX-175),
 * each row carrying its body hash and the hash's algorithm ({@code payloads} is gone since V11, D-097); one batch = one statement in one transaction through
 * {@link PgBatchSink}.
 *
 * @since 0.1
 */
public final class V1MessageStatements {

    private V1MessageStatements() {}

    static final String MESSAGES = "insert into messages(id, step, flow, headers, props, keys, attachments, body_hash, hash_algorithm)"
            + " select * from unnest($1::text[], $2::int[], $3::text[], $4::jsonb[], $5::jsonb[], $6::jsonb[], $7::jsonb[], $8::bytea[], $9::text[])"
            + " on conflict (id, step, attempt) do nothing";

    public static List<PgBatchSink.Statement<BenchMessage>> all() {
        return List.of(new PgBatchSink.Statement<>(MESSAGES, V1MessageStatements::messages));
    }

    static Tuple messages(List<BenchMessage> batch) {
        int n = batch.size();
        String[] ids = new String[n]; Integer[] steps = new Integer[n]; String[] flows = new String[n];
        String[] empty = new String[n]; String[] algorithms = new String[n];
        // bytea[] parameters: the Vert.x pg client encodes them from Buffer[] only (javap DataType.BYTEA_ARRAY), never from byte[][]
        Buffer[] hashes = new Buffer[n];
        for (int i = 0; i < n; i++) {
            BenchMessage m = batch.get(i);
            ids[i] = m.id(); steps[i] = m.step(); flows[i] = m.flow(); empty[i] = "{}";
            hashes[i] = Buffer.buffer(m.hashBytes()); algorithms[i] = m.hash().algorithm();
        }
        return Tuple.wrap(new Object[] {ids, steps, flows, empty, empty, empty, empty, hashes, algorithms});
    }
}
