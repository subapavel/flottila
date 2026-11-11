package io.flottila.runtime.node;

import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.pgclient.PgConnectOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    static final NodeConfig.Pg TLS = new NodeConfig.Pg("pg.example.internal", 6543, "flottila_orders", "intake", "s3cret-pw", 7,
            NodeConfig.Pg.SslMode.VERIFY_FULL, Optional.of(Path.of("/etc/flottila/pg-ca.pem")), NodeConfig.Pg.Durability.HA);

    /**
     * CORE-17 P3 (D-130 draft): each role's options are {@link Node#connectOptions(NodeConfig.Pg)} — still the one source of the
     * server, the credentials, the session pins and TLS — plus {@code application_name} {@code flottila-<role>@<nodeId>}, so a
     * session in {@code pg_stat_activity} names the pool or connection it belongs to.
     */
    @ParameterizedTest(name = "role {0}")
    @ValueSource(strings = {"intake", "work", "membership", "listen"})
    void everyRoleCarriesThePinsTlsAndItsName(String role) {
        PgConnectOptions options = Node.connectOptions(TLS, role, "node-7");
        Map<String, String> startup = options.getProperties();
        ClientSSLOptions ssl = options.getSslOptions();
        assertAll(
                () -> assertEquals("flottila-" + role + "@node-7", startup.get("application_name"), "the session names its role and its node: " + startup),
                () -> assertEquals("pg.example.internal", options.getHost(), "FLOTTILA_PG_HOST"),
                () -> assertEquals(6543, options.getPort(), "FLOTTILA_PG_PORT"),
                () -> assertEquals("flottila_orders", options.getDatabase(), "FLOTTILA_PG_DATABASE"),
                () -> assertEquals("intake", options.getUser(), "FLOTTILA_PG_USER"),
                () -> assertEquals("s3cret-pw", options.getPassword(), "FLOTTILA_PG_PASSWORD"),
                () -> assertTrue(options.getCachePreparedStatements(), "the prepared-statement cache (F-26)"),
                () -> assertTrue(options.getPreparedStatementCacheSqlFilter().test("x".repeat(8_192)), "the cache's 8192-character limit"),
                () -> assertFalse(options.getPreparedStatementCacheSqlFilter().test("x".repeat(8_193)), "and not above it"),
                () -> assertEquals("on", startup.get("synchronous_commit"), "FIX-136's pin: " + startup),
                () -> assertEquals("0", startup.get("statement_timeout"), "FIX-136's pin: " + startup),
                () -> assertEquals("off", startup.get("jit"), "FIX-130's pin: " + startup),
                () -> assertEquals(io.vertx.pgclient.SslMode.VERIFY_FULL, options.getSslMode(), "FLOTTILA_PG_SSL_MODE verify-full (D-124)"),
                () -> assertTrue(ssl != null && ssl.getTrustOptions() instanceof PemTrustOptions pem && pem.getCertPaths().equals(List.of("/etc/flottila/pg-ca.pem")),
                        "the link trusts FLOTTILA_PG_SSL_ROOT_CERT only: " + (ssl == null ? null : ssl.getTrustOptions())),
                () -> assertEquals("HTTPS", ssl == null ? null : ssl.getHostnameVerificationAlgorithm(), "verify-full checks the server's name"));

        // nothing else differs from the one source: a pin added there reaches every role
        JsonObject expected = Node.connectOptions(TLS).toJson();
        expected.getJsonObject("properties").put("application_name", "flottila-" + role + "@node-7");
        assertEquals(expected, options.toJson(), "connectOptions(pg) plus the session's name, nothing else");
    }
}
