package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-6a (F-26, architect decision 1): the node's connect options cache a prepared statement whose SQL text is at most 8192
 * characters — above the client's default 2048, so the intake's KEEP (2514) is cached — and never one longer: the limit is finite.
 */
class NodeConnectOptionsCacheLimitTest {

    static final NodeConfig.Pg PG = new NodeConfig.Pg("pg.example.internal", 6543, "flottila_orders", "intake", "s3cret-pw", 7);

    @Test
    void aStatementOf8192CharactersIsCachedAndOneLongerIsNot() {
        Predicate<String> cached = Node.connectOptions(PG).getPreparedStatementCacheSqlFilter();
        assertTrue(cached.test("x".repeat(2_514)), "the intake's KEEP, longer than the client's default 2048, is cached");
        assertTrue(cached.test("x".repeat(8_192)), "8192 characters: cached");
        assertFalse(cached.test("x".repeat(8_193)), "8193 characters: not cached (the limit is finite)");
    }
}
