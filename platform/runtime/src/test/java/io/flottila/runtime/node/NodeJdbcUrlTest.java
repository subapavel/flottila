package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * FIX-146 T3 (D-124): Flyway's JDBC URL carries the node's TLS settings as the driver reads them back — the CA's path intact
 * whatever characters it holds (the driver URL-decodes its parameters), the mode as written.
 */
class NodeJdbcUrlTest {

    static Properties parsed(NodeConfig.Pg pg) throws SQLException {
        Properties p = org.postgresql.Driver.parseURL(Node.jdbcUrl(pg), new Properties());
        assertNotNull(p, "the driver accepts the URL: " + Node.jdbcUrl(pg));
        return p;
    }

    @Test
    void aCaPathWithUrlCharactersReachesTheDriverIntact() throws SQLException {
        Path ca = Path.of("/etc/flottila/ca dir/a&b=c?d%20e+f#g.pem");
        NodeConfig.Pg pg = new NodeConfig.Pg("db.internal", 6432, "flows", "node", "pw", 4, NodeConfig.Pg.SslMode.VERIFY_FULL, Optional.of(ca));
        Properties p = parsed(pg);
        assertAll(
                () -> assertEquals(ca.toString(), p.getProperty("sslrootcert"), "the CA's path as configured"),
                () -> assertEquals("verify-full", p.getProperty("sslmode"), "the mode as the driver spells it"),
                () -> assertEquals(pg.host(), p.getProperty("PGHOST")),
                () -> assertEquals(String.valueOf(pg.port()), p.getProperty("PGPORT")),
                () -> assertEquals(pg.database(), p.getProperty("PGDBNAME")));
    }

    @Test
    void verifyCaAndDisableAreWrittenAsTheDriverSpellsThem() throws SQLException {
        Path ca = Path.of("/etc/flottila/ca.pem");
        NodeConfig.Pg verifyCa = new NodeConfig.Pg("db.internal", 5432, "flottila", "node", "pw", 4, NodeConfig.Pg.SslMode.VERIFY_CA, Optional.of(ca));
        NodeConfig.Pg plain = new NodeConfig.Pg("localhost", 5432, "flottila", "node", "pw", 4);
        Properties p = parsed(verifyCa);
        Properties q = parsed(plain);
        assertAll(
                () -> assertEquals("verify-ca", p.getProperty("sslmode")),
                () -> assertEquals(ca.toString(), p.getProperty("sslrootcert")),
                () -> assertEquals("disable", q.getProperty("sslmode"), "disable is explicit: never the driver's default prefer"),
                () -> assertNull(q.getProperty("sslrootcert"), "no CA, no parameter"));
    }
}
