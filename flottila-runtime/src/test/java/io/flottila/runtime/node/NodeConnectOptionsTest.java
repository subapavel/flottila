package io.flottila.runtime.node;

import io.vertx.pgclient.PgConnectOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

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

    /**
     * FIX-136 P2 (D-127 draft, D-061 amended): every session of the node commits with {@code synchronous_commit=on} and runs
     * without a statement timeout, under both profiles: startup parameters, so the pool, the membership connection and LISTEN
     * carry them on every (re)connect, above a database's or a role's own setting. {@code jit=off} stays (FIX-130).
     */
    @ParameterizedTest(name = "FLOTTILA_PG_DURABILITY={0}")
    @ValueSource(strings = {"local", "ha"})
    void connectOptionsPinSynchronousCommitAndNoStatementTimeout(String durability) {
        Map<String, String> env = NodeConfigTest.with(NodeConfigTest.minimal(), "FLOTTILA_PG_DURABILITY", durability);
        NodeConfig.Pg pg = NodeConfig.from(env).pg();
        assertEquals(durability, pg.durability().toString(), "fixture: the profile asked for");
        Map<String, String> startup = Node.connectOptions(pg).getProperties();
        assertEquals("on", startup.get("synchronous_commit"), "the commit waits for the WAL flush (and a synchronous standby): " + startup);
        assertEquals("0", startup.get("statement_timeout"), "no timeout cancels a commit's wait for its standby: " + startup);
        assertEquals("off", startup.get("jit"), "FIX-130's pin is kept: " + startup);
    }
}
