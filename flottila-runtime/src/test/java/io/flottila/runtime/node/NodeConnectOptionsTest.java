package io.flottila.runtime.node;

import io.vertx.pgclient.PgConnectOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-6a (F-26): the node's Postgres connect options, shared by the node's pool and the counting ITs, carry every
 * {@link NodeConfig.Pg} field and cache prepared statements (§4 Writer: "prepared-statement cache"), on a connection that
 * pipelines (D-109: a save's statements of one turn are sent together).
 */
class NodeConnectOptionsTest {

    static final NodeConfig.Pg PG = new NodeConfig.Pg("pg.example.internal", 6543, "flottila_orders", "intake", "s3cret-pw", 7);

    @Test
    void theOptionsCarryTheConfiguredServerAndCredentials() {
        PgConnectOptions options = Node.connectOptions(PG);
        assertEquals("pg.example.internal", options.getHost(), "FLOTTILA_PG_HOST");
        assertEquals(6543, options.getPort(), "FLOTTILA_PG_PORT");
        assertEquals("flottila_orders", options.getDatabase(), "FLOTTILA_PG_DATABASE");
        assertEquals("intake", options.getUser(), "FLOTTILA_PG_USER");
        assertEquals("s3cret-pw", options.getPassword(), "FLOTTILA_PG_PASSWORD");
    }

    @Test
    void theOptionsCachePreparedStatements() {
        assertTrue(Node.connectOptions(PG).getCachePreparedStatements(), "a statement is parsed once per connection (F-26)");
    }

    @Test
    void theOptionsLetAConnectionPipeline() {
        int limit = Node.connectOptions(PG).getPipeliningLimit();
        assertTrue(limit > 1, "a connection sends a turn's statements before their replies (D-109): pipelining limit " + limit);
    }
}
