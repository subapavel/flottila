package io.flottila.bench;

import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.flottila.runtime.batch.PgBatchSink;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bench's batch shape: the prototype's messages insert by (id, step). P2-07c (D-097): {@code payloads} is gone, so a batch
 * is that one statement, each row carrying its hash and the hash's algorithm.
 */
class V1MessageStatementsTest {

    /** The column list of an {@code insert into messages(...)} statement, in order. */
    static List<String> columns(String sql) {
        int open = sql.indexOf("messages(") + "messages(".length();
        return Arrays.stream(sql.substring(open, sql.indexOf(')', open)).split(",")).map(String::trim).toList();
    }

    @Test
    void aBatchIsOneMessagesInsertAndEveryRowCarriesItsHashAndAlgorithm() {
        Buffer body = Buffer.buffer("<invoice id='4711'/>");
        List<BenchMessage> batch = List.of(BenchMessage.of("m-1", 0, "invoice", body, Hasher.sha256()),
                BenchMessage.of("m-2", 0, "invoice", body, Hasher.sha256()),
                new BenchMessage("m-3", 0, "invoice", Buffer.buffer("<other/>"), new Hash("xxh3-64", 0x0102030405060708L)));
        List<PgBatchSink.Statement<BenchMessage>> statements = V1MessageStatements.all();
        assertEquals(1, statements.size(), "the messages insert only: no payloads table (V11)");
        String sql = statements.get(0).sql();
        assertTrue(sql.contains("insert into messages") && sql.contains("unnest") && sql.contains("on conflict (id, step, attempt) do nothing"), sql);
        List<String> columns = columns(sql);
        int hashes = columns.indexOf("body_hash"), algorithms = columns.indexOf("hash_algorithm");
        assertTrue(hashes >= 0 && algorithms >= 0, "body_hash and hash_algorithm are written: " + columns);
        Tuple messages = statements.get(0).arrays().apply(batch);
        assertEquals(columns.size(), messages.size(), "one array per column");
        assertEquals(List.of("m-1", "m-2", "m-3"), List.of((Object[]) messages.getValue(columns.indexOf("id"))));
        assertTrue(messages.getValue(hashes) instanceof Buffer[],
                "bytea[] parameters are Buffer[]: the pg client rejects byte[][] (the first IT run failed every save on it)");
        Buffer[] hashBytes = (Buffer[]) messages.getValue(hashes);
        assertEquals(8, hashBytes[0].length(), "hash as 8-byte big-endian bytea");
        assertEquals(Buffer.buffer(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}), hashBytes[2], "m-3's hash, big-endian");
        assertArrayEquals(new Object[] {"sha256-64", "sha256-64", "xxh3-64"}, (Object[]) messages.getValue(algorithms), "each row's own algorithm");
        assertEquals(body.length(), batch.get(0).size());
    }
}
