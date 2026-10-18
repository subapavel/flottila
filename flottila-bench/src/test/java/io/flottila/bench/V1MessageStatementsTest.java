package io.flottila.bench;

import io.flottila.api.Hasher;
import io.flottila.runtime.batch.PgBatchSink;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The V1 insert shape of the prototype (payloads by hash with ON CONFLICT DO NOTHING, messages by (id, step)). */
class V1MessageStatementsTest {

    @Test
    void payloadsAreDistinctByHashWithinABatchAndMessagesCarryTheirHash() {
        Buffer body = Buffer.buffer("<invoice id='4711'/>");
        List<BenchMessage> batch = List.of(BenchMessage.of("m-1", 0, "invoice", body, Hasher.sha256()),
                BenchMessage.of("m-2", 0, "invoice", body, Hasher.sha256()),
                BenchMessage.of("m-3", 0, "invoice", Buffer.buffer("<other/>"), Hasher.sha256()));
        List<PgBatchSink.Statement<BenchMessage>> statements = V1MessageStatements.all();
        assertEquals(2, statements.size(), "payloads then messages");
        assertTrue(statements.get(0).sql().contains("insert into payloads") && statements.get(0).sql().contains("on conflict (hash) do nothing"));
        assertTrue(statements.get(1).sql().contains("insert into messages") && statements.get(1).sql().contains("unnest"));
        Tuple payloads = statements.get(0).arrays().apply(batch);
        assertEquals(2, ((Object[]) payloads.getValue(0)).length, "two distinct hashes in the batch");
        Tuple messages = statements.get(1).arrays().apply(batch);
        assertEquals(3, ((Object[]) messages.getValue(0)).length);
        assertEquals("m-2", ((Object[]) messages.getValue(0))[1]);
        assertEquals(8, ((Buffer) ((Object[]) messages.getValue(7))[0]).length(), "hash as 8-byte big-endian bytea");
        assertTrue(payloads.getValue(0) instanceof Buffer[] && payloads.getValue(2) instanceof Buffer[] && messages.getValue(7) instanceof Buffer[],
                "bytea[] parameters are Buffer[]: the pg client rejects byte[][] (the first IT run failed every save on it)");
        assertEquals(body, ((Buffer[]) payloads.getValue(2))[0]);
        assertEquals(body.length(), batch.get(0).size());
    }
}
