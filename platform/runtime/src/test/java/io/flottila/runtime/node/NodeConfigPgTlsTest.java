package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static io.flottila.runtime.node.NodeConfigTest.minimal;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-146 T1/T2 (D-124): {@code FLOTTILA_PG_SSL_MODE} is {@code disable}, {@code verify-ca} or {@code verify-full}, by default
 * {@code disable} on a loopback {@code FLOTTILA_PG_HOST} ({@code localhost}, {@code 127.0.0.1}, {@code ::1}) and {@code verify-full}
 * on any other; a verifying mode needs a readable {@code FLOTTILA_PG_SSL_ROOT_CERT} — fail closed, the refusal naming both keys.
 */
class NodeConfigPgTlsTest {

    static final String MODE = "FLOTTILA_PG_SSL_MODE";
    static final String ROOT_CERT = "FLOTTILA_PG_SSL_ROOT_CERT";

    @TempDir Path dir;

    Path caFile() throws IOException {
        return Files.writeString(dir.resolve("ca.pem"), "-----BEGIN CERTIFICATE-----\n(not parsed by the configuration)\n-----END CERTIFICATE-----\n");
    }

    static Map<String, String> host(String host) {
        return with(minimal(), "FLOTTILA_PG_HOST", host);
    }

    @Test
    void anUnsetHostIsLocalhostAndDefaultsToDisableWithoutACa() {
        NodeConfig.Pg pg = NodeConfig.from(minimal()).pg();
        assertAll(
                () -> assertEquals(NodeConfig.Pg.SslMode.DISABLE, pg.sslMode()),
                () -> assertEquals(Optional.empty(), pg.sslRootCert()));
    }

    @ParameterizedTest(name = "FLOTTILA_PG_HOST={0}")
    @ValueSource(strings = {"localhost", "127.0.0.1", "::1"})
    void aLoopbackHostDefaultsToDisable(String loopback) {
        assertEquals(NodeConfig.Pg.SslMode.DISABLE, NodeConfig.from(host(loopback)).pg().sslMode(), "no CA needed on loopback");
    }

    @ParameterizedTest(name = "FLOTTILA_PG_HOST={0}")
    @ValueSource(strings = {"db.internal", "postgres", "10.1.2.3", "localhost.example.com", "fd00::5"})
    void aNonLoopbackHostDefaultsToVerifyFull(String remote) throws IOException {
        Path ca = caFile();
        NodeConfig.Pg pg = NodeConfig.from(with(host(remote), ROOT_CERT, ca.toString())).pg();
        assertAll(
                () -> assertEquals(NodeConfig.Pg.SslMode.VERIFY_FULL, pg.sslMode()),
                () -> assertEquals(Optional.of(ca), pg.sslRootCert(), "the CA path as given"));
    }

    @ParameterizedTest(name = "FLOTTILA_PG_HOST={0}")
    @ValueSource(strings = {"db.internal", "10.1.2.3", "localhost.example.com"})
    void aNonLoopbackHostWithoutACaIsRefusedNamingBothKeys(String remote) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(host(remote)),
                "the default verify-full has no CA: never a silent fallback to plaintext");
        assertTrue(e.getMessage().contains(MODE) && e.getMessage().contains(ROOT_CERT), "names both keys: " + e.getMessage());
    }

    @Test
    void anExplicitModeWinsOverTheHostsDefault() throws IOException {
        Path ca = caFile();
        assertAll(
                () -> assertEquals(NodeConfig.Pg.SslMode.DISABLE, NodeConfig.from(with(host("db.internal"), MODE, "disable")).pg().sslMode(),
                        "disable on a remote host, no CA asked"),
                () -> assertEquals(NodeConfig.Pg.SslMode.VERIFY_FULL,
                        NodeConfig.from(with(with(host("localhost"), MODE, "verify-full"), ROOT_CERT, ca.toString())).pg().sslMode(), "verify-full on loopback"),
                () -> assertEquals(NodeConfig.Pg.SslMode.VERIFY_CA,
                        NodeConfig.from(with(with(host("db.internal"), MODE, "verify-ca"), ROOT_CERT, ca.toString())).pg().sslMode(), "verify-ca"),
                () -> assertEquals(Optional.of(ca),
                        NodeConfig.from(with(with(host("db.internal"), MODE, "verify-ca"), ROOT_CERT, ca.toString())).pg().sslRootCert(), "its CA"));
    }

    @ParameterizedTest(name = "FLOTTILA_PG_SSL_MODE={0}")
    @ValueSource(strings = {"verify-ca", "verify-full"})
    void aVerifyingModeWithoutACaIsRefusedNamingBothKeys(String mode) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(host("localhost"), MODE, mode)));
        assertTrue(e.getMessage().contains(MODE) && e.getMessage().contains(ROOT_CERT), "names both keys: " + e.getMessage());
    }

    @ParameterizedTest(name = "FLOTTILA_PG_SSL_MODE={0}")
    @ValueSource(strings = {"verify-ca", "verify-full"})
    void aVerifyingModeWithAnUnreadableCaIsRefusedNamingBothKeys(String mode) throws IOException {
        Path missing = dir.resolve("absent.pem");
        Path directory = Files.createDirectories(dir.resolve("a-directory.pem"));
        for (Path unreadable : new Path[] {missing, directory}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> NodeConfig.from(with(with(host("db.internal"), MODE, mode), ROOT_CERT, unreadable.toString())), "refused: " + unreadable);
            assertTrue(e.getMessage().contains(MODE) && e.getMessage().contains(ROOT_CERT), "names both keys: " + e.getMessage());
        }
        assertEquals(Optional.of(caFile()), NodeConfig.from(with(with(host("db.internal"), MODE, mode), ROOT_CERT, caFile().toString())).pg().sslRootCert(),
                "witness: a readable file is taken");
    }

    @ParameterizedTest(name = "FLOTTILA_PG_SSL_MODE={0}")
    @ValueSource(strings = {"verify-ca", "verify-full"})
    void aVerifyingModeWithAnEmptyCaIsRefusedNamingBothKeys(String mode) throws IOException {
        Path empty = Files.write(dir.resolve("empty.pem"), new byte[0]);
        assertTrue(Files.isReadable(empty) && Files.size(empty) == 0, "fixture: readable and 0 bytes");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(with(host("db.internal"), MODE, mode), ROOT_CERT, empty.toString())),
                "an empty CA trusts nothing: refused at configuration, never at the first handshake");
        assertTrue(e.getMessage().contains(MODE) && e.getMessage().contains(ROOT_CERT), "names both keys: " + e.getMessage());
    }

    @ParameterizedTest(name = "FLOTTILA_PG_SSL_MODE={0}")
    @ValueSource(strings = {"require", "prefer", "allow", "verify_full", "VERIFY-FULL", "on"})
    void anUnknownModeIsRefusedNamingTheKey(String mode) throws IOException {
        Path ca = caFile();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(with(host("db.internal"), MODE, mode), ROOT_CERT, ca.toString())), "TLS without verification is not offered");
        assertTrue(e.getMessage().contains(MODE), "names the key: " + e.getMessage());
    }

    @Test
    void toStringShowsTheModeAndTheCaPathNeverThePassword() throws IOException {
        String password = "pg-tls-secret-4711";
        Path ca = caFile();
        NodeConfig c = NodeConfig.from(with(with(with(host("db.internal"), MODE, "verify-full"), ROOT_CERT, ca.toString()), "FLOTTILA_PG_PASSWORD", password));
        assertEquals(password, c.pg().password(), "the password is read: the check below is not vacuous");
        for (String text : new String[] {c.pg().toString(), c.toString()}) {
            assertTrue(text.contains("verify-full"), "the mode as written: " + text);
            assertTrue(text.contains(ca.toString()), "the CA path (not a secret): " + text);
            assertFalse(text.contains(password), "never the password: " + text);
        }
        String caOnly = NodeConfig.from(with(with(host("db.internal"), MODE, "verify-ca"), ROOT_CERT, ca.toString())).pg().toString();
        assertTrue(caOnly.contains("verify-ca"), "verify-ca as written: " + caOnly);
        String plain = NodeConfig.from(host("localhost")).pg().toString();
        assertTrue(plain.contains("disable"), "disable as written: " + plain);
    }
}
