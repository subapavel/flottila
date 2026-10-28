package io.flottila.runtime.node;

import io.flottila.runtime.admin.AdminServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09b: a node reads its whole configuration from the environment (D-057 b): every key's default, every key read under
 * its own name, every refusal an {@link IllegalArgumentException} naming the key, and which environments are deployed
 * (§11: every one but {@code dev} and {@code ci} — {@code test} is deployed).
 */
class NodeConfigTest {

    static final long MIB = 1024L * 1024;
    static final long GIB = 1024 * MIB;

    /** The least a node needs: its environment and a node id. */
    static Map<String, String> minimal() {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", "node-1");
        return env;
    }

    /** {@code minimal()} with {@code key} set to {@code value}, or removed when {@code value} is null. */
    static Map<String, String> with(Map<String, String> env, String key, String value) {
        if (value == null) env.remove(key);
        else env.put(key, value);
        return env;
    }

    @Test
    void defaults() {
        NodeConfig c = NodeConfig.from(Map.of("FLOTTILA_ENV", "dev", "HOSTNAME", "node-1"));
        assertAll(
                () -> assertEquals("node-1", c.nodeId(), "the node id defaults to $HOSTNAME"),
                () -> assertEquals("dev", c.env()),
                () -> assertFalse(c.deployed(), "dev is not deployed"),
                () -> assertEquals(new AdminServer.Config("127.0.0.1", 8081, Optional.empty()), c.admin(), "loopback, 8081, no token"),
                () -> assertEquals(Path.of("/var/lib/flottila"), c.dataDir()),
                () -> assertEquals(Duration.ofSeconds(10), c.stopDeadline()),
                () -> assertEquals(64 * MIB, c.maxPackageBytes()),
                () -> assertEquals(new NodeConfig.Pg("localhost", 5432, "flottila", "flottila", "", 16), c.pg()),
                () -> assertEquals(new NodeConfig.Blobs(8 * MIB, MIB, 256 * MIB, MIB), c.blobs(), "durableMax defaults to 1 MiB (D-059)"));
    }

    @Test
    void readsEveryKeyUnderItsOwnName() {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_NODE_ID", "edge-7");
        env.put("HOSTNAME", "build-host");
        env.put("FLOTTILA_ENV", "prod");
        env.put("FLOTTILA_ADMIN_HOST", "0.0.0.0");
        env.put("FLOTTILA_ADMIN_PORT", "9443");
        env.put("FLOTTILA_ADMIN_TOKEN", "s3cret");
        env.put("FLOTTILA_DATA_DIR", "/srv/flottila");
        env.put("FLOTTILA_STOP_DEADLINE", "PT1M30S");
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", "1048576");
        env.put("FLOTTILA_PG_HOST", "db.internal");
        env.put("FLOTTILA_PG_PORT", "6432");
        env.put("FLOTTILA_PG_DATABASE", "flows");
        env.put("FLOTTILA_PG_USER", "node");
        env.put("FLOTTILA_PG_PASSWORD", "pw");
        env.put("FLOTTILA_PG_POOL_SIZE", "32");
        env.put("FLOTTILA_BLOB_TREE_LIMIT", "4194304");
        env.put("FLOTTILA_BLOB_MEMORY_TIER", "524288");
        env.put("FLOTTILA_BLOB_MEMORY_BUDGET", "8589934592");   // 8 GiB: sizes are long
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "2097152");
        NodeConfig c = NodeConfig.from(env);
        assertAll(
                () -> assertEquals("edge-7", c.nodeId(), "FLOTTILA_NODE_ID wins over $HOSTNAME"),
                () -> assertEquals("prod", c.env()),
                () -> assertTrue(c.deployed(), "prod is deployed"),
                () -> assertEquals(new AdminServer.Config("0.0.0.0", 9443, Optional.of("s3cret")), c.admin(), "a non-loopback bind with its token"),
                () -> assertEquals(Path.of("/srv/flottila"), c.dataDir()),
                () -> assertEquals(Duration.ofSeconds(90), c.stopDeadline(), "ISO-8601"),
                () -> assertEquals(MIB, c.maxPackageBytes()),
                () -> assertEquals(new NodeConfig.Pg("db.internal", 6432, "flows", "node", "pw", 32), c.pg()),
                () -> assertEquals(new NodeConfig.Blobs(4 * MIB, 512 * 1024, 8 * GIB, 2 * MIB), c.blobs()));
    }

    @Test
    void aBlankTokenIsNoToken() {
        NodeConfig c = NodeConfig.from(with(minimal(), "FLOTTILA_ADMIN_TOKEN", "   "));
        assertEquals(Optional.empty(), c.admin().token());
    }

    static Stream<Arguments> refusals() {
        String sixtyFour = "n" + "x".repeat(63);
        return Stream.of(
                Arguments.of("FLOTTILA_ENV absent", with(minimal(), "FLOTTILA_ENV", null), "FLOTTILA_ENV"),
                Arguments.of("FLOTTILA_ENV blank", with(minimal(), "FLOTTILA_ENV", "  "), "FLOTTILA_ENV"),
                Arguments.of("no node id and no HOSTNAME", with(minimal(), "FLOTTILA_NODE_ID", null), "FLOTTILA_NODE_ID"),
                Arguments.of("HOSTNAME not a node id", with(with(minimal(), "FLOTTILA_NODE_ID", null), "HOSTNAME", "Build.Host"), "FLOTTILA_NODE_ID"),
                Arguments.of("node id with an upper-case letter", with(minimal(), "FLOTTILA_NODE_ID", "Node-1"), "FLOTTILA_NODE_ID"),
                Arguments.of("node id starting with a dash", with(minimal(), "FLOTTILA_NODE_ID", "-node"), "FLOTTILA_NODE_ID"),
                Arguments.of("node id with an underscore", with(minimal(), "FLOTTILA_NODE_ID", "node_1"), "FLOTTILA_NODE_ID"),
                Arguments.of("node id of 64 characters", with(minimal(), "FLOTTILA_NODE_ID", sixtyFour), "FLOTTILA_NODE_ID"),
                Arguments.of("admin port not a number", with(minimal(), "FLOTTILA_ADMIN_PORT", "http"), "FLOTTILA_ADMIN_PORT"),
                Arguments.of("admin port below 0", with(minimal(), "FLOTTILA_ADMIN_PORT", "-1"), "FLOTTILA_ADMIN_PORT"),
                Arguments.of("admin port above 65535", with(minimal(), "FLOTTILA_ADMIN_PORT", "65536"), "FLOTTILA_ADMIN_PORT"),
                Arguments.of("a non-loopback admin host without a token", with(minimal(), "FLOTTILA_ADMIN_HOST", "0.0.0.0"), "FLOTTILA_ADMIN_TOKEN"),
                Arguments.of("a non-loopback admin host with a blank token", with(with(minimal(), "FLOTTILA_ADMIN_HOST", "0.0.0.0"), "FLOTTILA_ADMIN_TOKEN", " "), "FLOTTILA_ADMIN_TOKEN"),
                Arguments.of("stop deadline negative", with(minimal(), "FLOTTILA_STOP_DEADLINE", "PT-1S"), "FLOTTILA_STOP_DEADLINE"),
                Arguments.of("stop deadline not ISO-8601", with(minimal(), "FLOTTILA_STOP_DEADLINE", "10s"), "FLOTTILA_STOP_DEADLINE"),
                Arguments.of("max package bytes 0", with(minimal(), "FLOTTILA_MAX_PACKAGE_BYTES", "0"), "FLOTTILA_MAX_PACKAGE_BYTES"),
                Arguments.of("max package bytes above 1 GiB", with(minimal(), "FLOTTILA_MAX_PACKAGE_BYTES", String.valueOf(GIB + 1)), "FLOTTILA_MAX_PACKAGE_BYTES"),
                Arguments.of("max package bytes not a number", with(minimal(), "FLOTTILA_MAX_PACKAGE_BYTES", "64MiB"), "FLOTTILA_MAX_PACKAGE_BYTES"),
                Arguments.of("Postgres port not a number", with(minimal(), "FLOTTILA_PG_PORT", "pg"), "FLOTTILA_PG_PORT"),
                Arguments.of("Postgres pool size not a number", with(minimal(), "FLOTTILA_PG_POOL_SIZE", "many"), "FLOTTILA_PG_POOL_SIZE"),
                Arguments.of("blob tree limit not a number", with(minimal(), "FLOTTILA_BLOB_TREE_LIMIT", "8M"), "FLOTTILA_BLOB_TREE_LIMIT"),
                Arguments.of("blob tree limit above Integer.MAX_VALUE (a body below it is one buffer)",
                        with(minimal(), "FLOTTILA_BLOB_TREE_LIMIT", String.valueOf((long) Integer.MAX_VALUE + 1)), "FLOTTILA_BLOB_TREE_LIMIT"),
                Arguments.of("blob memory tier not a number", with(minimal(), "FLOTTILA_BLOB_MEMORY_TIER", "1M"), "FLOTTILA_BLOB_MEMORY_TIER"),
                Arguments.of("blob memory budget not a number", with(minimal(), "FLOTTILA_BLOB_MEMORY_BUDGET", "256M"), "FLOTTILA_BLOB_MEMORY_BUDGET"),
                Arguments.of("memory tier above the default memory budget", with(minimal(), "FLOTTILA_BLOB_MEMORY_TIER", String.valueOf(256 * MIB + 1)),
                        "FLOTTILA_BLOB_MEMORY_TIER"),
                Arguments.of("memory tier above the configured memory budget",
                        with(with(minimal(), "FLOTTILA_BLOB_MEMORY_BUDGET", String.valueOf(4 * MIB)), "FLOTTILA_BLOB_MEMORY_TIER", String.valueOf(4 * MIB + 1)),
                        "FLOTTILA_BLOB_MEMORY_TIER"),
                Arguments.of("blob durable max 0", with(minimal(), "FLOTTILA_BLOB_DURABLE_MAX", "0"), "FLOTTILA_BLOB_DURABLE_MAX"),
                Arguments.of("blob durable max above 64 MiB", with(minimal(), "FLOTTILA_BLOB_DURABLE_MAX", String.valueOf(64 * MIB + 1)), "FLOTTILA_BLOB_DURABLE_MAX"),
                Arguments.of("blob durable max not a number", with(minimal(), "FLOTTILA_BLOB_DURABLE_MAX", "1MiB"), "FLOTTILA_BLOB_DURABLE_MAX"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void refuses(String what, Map<String, String> env, String key) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(env), what);
        assertTrue(String.valueOf(e.getMessage()).contains(key), "the refusal names " + key + ": " + e.getMessage());
    }

    static Stream<Arguments> edges() {
        String sixtyThree = "0" + "-".repeat(61) + "z";
        return Stream.of(
                Arguments.of("FLOTTILA_ADMIN_PORT", "0", (Function<NodeConfig, Object>) c -> c.admin().port(), 0),
                Arguments.of("FLOTTILA_ADMIN_PORT", "65535", (Function<NodeConfig, Object>) c -> c.admin().port(), 65535),
                Arguments.of("FLOTTILA_MAX_PACKAGE_BYTES", "1", (Function<NodeConfig, Object>) NodeConfig::maxPackageBytes, 1L),
                Arguments.of("FLOTTILA_MAX_PACKAGE_BYTES", String.valueOf(GIB), (Function<NodeConfig, Object>) NodeConfig::maxPackageBytes, GIB),
                Arguments.of("FLOTTILA_STOP_DEADLINE", "PT0S", (Function<NodeConfig, Object>) NodeConfig::stopDeadline, Duration.ZERO),
                Arguments.of("FLOTTILA_STOP_DEADLINE", "PT0.25S", (Function<NodeConfig, Object>) NodeConfig::stopDeadline, Duration.ofMillis(250)),
                Arguments.of("FLOTTILA_NODE_ID", "7", (Function<NodeConfig, Object>) NodeConfig::nodeId, "7"),
                Arguments.of("FLOTTILA_NODE_ID", sixtyThree, (Function<NodeConfig, Object>) NodeConfig::nodeId, sixtyThree),
                Arguments.of("FLOTTILA_BLOB_MEMORY_TIER", String.valueOf(256 * MIB), (Function<NodeConfig, Object>) c -> c.blobs().memoryTier(), 256 * MIB),
                Arguments.of("FLOTTILA_BLOB_TREE_LIMIT", String.valueOf(Integer.MAX_VALUE), (Function<NodeConfig, Object>) c -> c.blobs().treeLimit(),
                        (long) Integer.MAX_VALUE),
                Arguments.of("FLOTTILA_BLOB_DURABLE_MAX", "1", (Function<NodeConfig, Object>) c -> c.blobs().durableMax(), 1L),
                Arguments.of("FLOTTILA_BLOB_DURABLE_MAX", String.valueOf(64 * MIB), (Function<NodeConfig, Object>) c -> c.blobs().durableMax(), 64 * MIB));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("edges")
    void acceptsTheEdgesOfEachRange(String key, String value, Function<NodeConfig, Object> read, Object expected) {
        assertEquals(expected, read.apply(NodeConfig.from(with(minimal(), key, value))));
    }

    @Test
    void toStringHidesThePasswordAndTheToken() {
        String password = "pg-secret-4711";
        String token = "admin-token-0815";
        NodeConfig c = NodeConfig.from(with(with(with(minimal(), "FLOTTILA_PG_PASSWORD", password), "FLOTTILA_ADMIN_TOKEN", token),
                "FLOTTILA_PG_HOST", "db.internal"));
        assertEquals(password, c.pg().password(), "the password is read: the checks below are not vacuous");
        assertEquals(Optional.of(token), c.admin().token(), "the token is read");
        for (Object shown : new Object[] {c, c.pg(), c.admin()}) {
            String text = shown.toString();
            assertFalse(text.contains(password), "a log line of " + shown.getClass().getSimpleName() + " must not carry the Postgres password: " + text);
            assertFalse(text.contains(token), "a log line of " + shown.getClass().getSimpleName() + " must not carry the admin token: " + text);
        }
        assertTrue(c.pg().toString().contains("db.internal"), "the rest still shows: " + c.pg());
        assertTrue(c.admin().toString().contains("127.0.0.1"), "the rest still shows: " + c.admin());
        assertTrue(c.toString().contains("node-1"), "the rest still shows: " + c);
    }

    @ParameterizedTest(name = "{0} deployed: {1}")
    @CsvSource({"dev, false", "ci, false", "test, true", "prod, true", "staging, true"})
    void testIsADeployedEnvironment(String env, boolean deployed) {
        assertEquals(deployed, NodeConfig.from(with(minimal(), "FLOTTILA_ENV", env)).deployed(),
                "§11 and D-057: every environment but dev and ci is deployed and refuses a mock binding");
    }

    /** A config built without {@link NodeConfig#from} cannot claim "prod, not deployed": the flag is derived from env, never given. */
    @ParameterizedTest(name = "{0} deployed: {1}")
    @CsvSource({"dev, false", "ci, false", "test, true", "prod, true", "staging, true"})
    void deployedIsDerivedFromTheEnvironment(String env, boolean deployed) {
        NodeConfig read = NodeConfig.from(minimal());
        NodeConfig built = new NodeConfig(read.nodeId(), env, read.admin(), read.dataDir(), read.stopDeadline(), read.maxPackageBytes(), read.pg(), read.blobs());
        assertEquals(deployed, built.deployed(), "deployed() follows the environment the config was built with");
        assertFalse(java.util.Arrays.stream(NodeConfig.class.getRecordComponents()).anyMatch(c -> c.getName().equals("deployed")),
                "deployed is no record component: no constructor takes a flag that could contradict env");
    }

    // --- P1-09c3a (D-070): the archive store

    /** {@code minimal()} with the archive store s3, a bucket and a region: the least an s3 node needs. */
    static Map<String, String> s3() {
        return with(with(with(minimal(), "FLOTTILA_ARCHIVE_STORE", "s3"), "FLOTTILA_S3_BUCKET", "flottila-archives"), "FLOTTILA_S3_REGION", "eu-central-1");
    }

    @Test
    void archiveStoreDefaultsToDirUnderTheDataDir() {
        NodeConfig c = NodeConfig.from(with(minimal(), "FLOTTILA_DATA_DIR", "/srv/flottila"));
        assertAll(
                () -> assertEquals(NodeConfig.Archives.Store.DIR, c.archives().store(), "FLOTTILA_ARCHIVE_STORE defaults to dir"),
                () -> assertEquals(Path.of("/srv/flottila/archives"), c.archives().dir(), "FLOTTILA_ARCHIVE_DIR defaults to <dataDir>/archives"),
                () -> assertEquals(Optional.empty(), c.s3(), "no S3 for a dir store"));
        assertEquals(Path.of("/var/lib/flottila/archives"), NodeConfig.from(minimal()).archives().dir(), "under the default data dir too");
        NodeConfig explicit = NodeConfig.from(with(with(minimal(), "FLOTTILA_ARCHIVE_STORE", "dir"), "FLOTTILA_ARCHIVE_DIR", "/mnt/archives"));
        assertEquals(NodeConfig.Archives.Store.DIR, explicit.archives().store());
        assertEquals(Path.of("/mnt/archives"), explicit.archives().dir(), "FLOTTILA_ARCHIVE_DIR is read under its own name");
        NodeConfig read = NodeConfig.from(with(minimal(), "FLOTTILA_DATA_DIR", "/srv/flottila"));
        NodeConfig built = new NodeConfig(read.nodeId(), read.env(), read.admin(), read.dataDir(), read.stopDeadline(), read.maxPackageBytes(), read.pg(), read.blobs());
        assertEquals(NodeConfig.Archives.Store.DIR, built.archives().store(), "the 8-argument constructor stays: a dir store");
        assertEquals(Path.of("/srv/flottila/archives"), built.archives().dir(), "under the data dir it was given");
        assertEquals(Optional.empty(), built.s3());
    }

    @Test
    void s3NeedsBucketAndRegion() {
        NodeConfig c = NodeConfig.from(s3());
        assertEquals(NodeConfig.Archives.Store.S3, c.archives().store());
        NodeConfig.S3 s3 = c.s3().orElseThrow(() -> new AssertionError("an s3 store has its S3 settings: " + c));
        assertAll(
                () -> assertEquals("flottila-archives", s3.bucket()),
                () -> assertEquals("eu-central-1", s3.region()),
                () -> assertEquals(Optional.empty(), s3.endpoint(), "no endpoint: the SDK's for the region"),
                () -> assertFalse(s3.pathStyle(), "FLOTTILA_S3_PATH_STYLE defaults to false"),
                () -> assertEquals("flottila", s3.prefix(), "FLOTTILA_S3_PREFIX defaults to flottila"),
                () -> assertEquals(Optional.empty(), s3.accessKey(), "no keys: the SDK's default chain"),
                () -> assertEquals(Optional.empty(), s3.secretKey()));
        for (String key : List.of("FLOTTILA_S3_BUCKET", "FLOTTILA_S3_REGION")) {
            IllegalArgumentException absent = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(s3(), key, null)), key + " absent");
            assertTrue(String.valueOf(absent.getMessage()).contains(key), "the refusal names " + key + ": " + absent.getMessage());
            IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(s3(), key, " ")), key + " blank");
            assertTrue(String.valueOf(blank.getMessage()).contains(key), "the refusal names " + key + ": " + blank.getMessage());
        }
    }

    @Test
    void s3ReadsEveryKeyUnderItsOwnName() {
        Map<String, String> env = s3();
        env.put("FLOTTILA_S3_ENDPOINT", "https://s3.example.net:9000");
        env.put("FLOTTILA_S3_PATH_STYLE", "true");
        env.put("FLOTTILA_S3_PREFIX", "fleet/eu");
        env.put("FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE");
        env.put("FLOTTILA_S3_SECRET_KEY", "secret-example");
        NodeConfig.S3 s3 = NodeConfig.from(env).s3().orElseThrow();
        assertAll(
                () -> assertEquals("flottila-archives", s3.bucket()),
                () -> assertEquals("eu-central-1", s3.region()),
                () -> assertEquals(Optional.of(URI.create("https://s3.example.net:9000")), s3.endpoint()),
                () -> assertTrue(s3.pathStyle()),
                () -> assertEquals("fleet/eu", s3.prefix()),
                () -> assertEquals(Optional.of("AKIAEXAMPLE"), s3.accessKey()),
                () -> assertEquals(Optional.of("secret-example"), s3.secretKey()));
    }

    @Test
    void anS3EndpointMustBeAnAbsoluteHttpUri() {
        for (String ok : List.of("http://127.0.0.1:4566", "https://s3.example.net")) {
            assertEquals(Optional.of(URI.create(ok)), NodeConfig.from(with(s3(), "FLOTTILA_S3_ENDPOINT", ok)).s3().orElseThrow().endpoint(), ok);
        }
        for (String bad : List.of("s3.example.net", "s3.example.net:9000", "/s3", "ftp://s3.example.net", "file:///srv/s3", "http://s3 example.net")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(s3(), "FLOTTILA_S3_ENDPOINT", bad)), bad);
            assertTrue(String.valueOf(e.getMessage()).contains("FLOTTILA_S3_ENDPOINT"), "the refusal of '" + bad + "' names the key: " + e.getMessage());
        }
    }

    @Test
    void s3KeysComeInPairs() {
        IllegalArgumentException accessOnly = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(s3(), "FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE")), "an access key without its secret");
        assertTrue(String.valueOf(accessOnly.getMessage()).contains("FLOTTILA_S3_SECRET_KEY"), "the refusal names the missing key: " + accessOnly.getMessage());
        IllegalArgumentException secretOnly = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(s3(), "FLOTTILA_S3_SECRET_KEY", "secret-example")), "a secret without its access key");
        assertTrue(String.valueOf(secretOnly.getMessage()).contains("FLOTTILA_S3_ACCESS_KEY"), "the refusal names the missing key: " + secretOnly.getMessage());
        NodeConfig.S3 both = NodeConfig.from(with(with(s3(), "FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE"), "FLOTTILA_S3_SECRET_KEY", "secret-example")).s3().orElseThrow();
        assertEquals(Optional.of("AKIAEXAMPLE"), both.accessKey());
        assertEquals(Optional.of("secret-example"), both.secretKey());
        NodeConfig.S3 neither = NodeConfig.from(s3()).s3().orElseThrow();
        assertEquals(Optional.empty(), neither.accessKey(), "neither: the SDK's default chain");
        assertEquals(Optional.empty(), neither.secretKey());
    }

    @Test
    void anUnknownArchiveStoreIsRefused() {
        for (String unknown : List.of("gcs", "file", "", "s3 ", "dir/")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> NodeConfig.from(with(minimal(), "FLOTTILA_ARCHIVE_STORE", unknown)), "FLOTTILA_ARCHIVE_STORE '" + unknown + "'");
            assertTrue(String.valueOf(e.getMessage()).contains("FLOTTILA_ARCHIVE_STORE"), "the refusal names the key: " + e.getMessage());
        }
    }

    @Test
    void theSecretKeyIsRedacted() {
        String secret = "wJalrXUtnFEMI-K7MDENG-4711";
        NodeConfig c = NodeConfig.from(with(with(s3(), "FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE"), "FLOTTILA_S3_SECRET_KEY", secret));
        NodeConfig.S3 s3 = c.s3().orElseThrow();
        assertEquals(Optional.of(secret), s3.secretKey(), "the secret is read: the checks below are not vacuous");
        for (Object shown : new Object[] {c, s3, c.s3()}) {
            String text = shown.toString();
            assertFalse(text.contains(secret), "a log line of " + shown.getClass().getSimpleName() + " must not carry the S3 secret key: " + text);
        }
        assertTrue(s3.toString().contains("flottila-archives"), "the rest still shows: " + s3);
        assertTrue(c.toString().contains("flottila-archives"), "the config's line shows its S3 settings, redacted: " + c);
    }
}
