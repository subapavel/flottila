package io.flottila.runtime.node;

import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.store.Retention;
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

    // --- P2-02L (D-083): poller leases

    /**
     * {@code FLOTTILA_VALKEY_URI} is optional (none: leases in memory), a redis:// or rediss:// URI; {@code FLOTTILA_POLLER_LEASE_TTL}
     * (PT30S) and {@code FLOTTILA_POLLER_LEASE_RENEW} (PT10S) are ISO-8601, each &gt; 0 and &le; PT10M, with 2 × renew &le; ttl; every
     * refusal names its key (one between the two names both). The shape assumed (P2-02L test-author): a component
     * {@code NodeConfig.Leases leases()} — {@code record Leases(Optional<URI> valkey, Duration ttl, Duration renew)}.
     */
    @Test
    void leaseDefaultsAndRefusals() {
        NodeConfig defaults = NodeConfig.from(minimal());
        assertEquals(new NodeConfig.Leases(Optional.empty(), Duration.ofSeconds(30), Duration.ofSeconds(10)), defaults.leases(),
                "no Valkey, ttl PT30S, renew PT10S");

        NodeConfig set = NodeConfig.from(with(with(with(minimal(), "FLOTTILA_VALKEY_URI", "rediss://valkey.internal:6380"),
                "FLOTTILA_POLLER_LEASE_TTL", "PT1M"), "FLOTTILA_POLLER_LEASE_RENEW", "PT20S"));
        assertEquals(new NodeConfig.Leases(Optional.of(URI.create("rediss://valkey.internal:6380")), Duration.ofMinutes(1), Duration.ofSeconds(20)),
                set.leases(), "every key read under its own name");
        assertEquals(Optional.of(URI.create("redis://valkey:6379")), NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", "redis://valkey:6379")).leases().valkey());

        // the edges: ttl PT10M; 2 × renew = ttl; the smallest positive pair
        for (String[] edge : new String[][] {{"PT10M", "PT5M"}, {"PT20S", "PT10S"}, {"PT0.002S", "PT0.001S"}}) {
            NodeConfig c = NodeConfig.from(with(with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", edge[0]), "FLOTTILA_POLLER_LEASE_RENEW", edge[1]));
            assertEquals(List.of(Duration.parse(edge[0]), Duration.parse(edge[1])), List.of(c.leases().ttl(), c.leases().renew()), "accepted: " + List.of(edge));
        }

        List<Map.Entry<String, Map<String, String>>> refused = List.of(
                Map.entry("FLOTTILA_POLLER_LEASE_TTL", with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT0S")),
                Map.entry("FLOTTILA_POLLER_LEASE_TTL", with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT-30S")),
                Map.entry("FLOTTILA_POLLER_LEASE_TTL", with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT10M0.001S")),
                Map.entry("FLOTTILA_POLLER_LEASE_TTL", with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "30s")),
                Map.entry("FLOTTILA_POLLER_LEASE_RENEW", with(minimal(), "FLOTTILA_POLLER_LEASE_RENEW", "PT0S")),
                Map.entry("FLOTTILA_POLLER_LEASE_RENEW", with(minimal(), "FLOTTILA_POLLER_LEASE_RENEW", "PT-10S")),
                Map.entry("FLOTTILA_POLLER_LEASE_RENEW", with(with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT10M"), "FLOTTILA_POLLER_LEASE_RENEW", "PT10M0.001S")),
                Map.entry("FLOTTILA_POLLER_LEASE_RENEW", with(minimal(), "FLOTTILA_POLLER_LEASE_RENEW", "10s")),
                Map.entry("FLOTTILA_VALKEY_URI", with(minimal(), "FLOTTILA_VALKEY_URI", "http://valkey:6379")),
                Map.entry("FLOTTILA_VALKEY_URI", with(minimal(), "FLOTTILA_VALKEY_URI", "valkey:6379")),
                Map.entry("FLOTTILA_VALKEY_URI", with(minimal(), "FLOTTILA_VALKEY_URI", "redis://valkey:6379/ x")));
        for (Map.Entry<String, Map<String, String>> r : refused) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(r.getValue()), "refused: " + r.getValue());
            assertTrue(String.valueOf(e.getMessage()).contains(r.getKey()), "the refusal names " + r.getKey() + ": " + e.getMessage());
        }

        // 2 × renew > ttl: by default ttl, by an explicit pair, by a millisecond (the leases' grain: review r1)
        for (String[] pair : new String[][] {{null, "PT15.001S"}, {"PT1M", "PT31S"}, {"PT20S", "PT10.001S"}}) {
            Map<String, String> env = with(with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", pair[0]), "FLOTTILA_POLLER_LEASE_RENEW", pair[1]);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(env), "refused: " + env);
            assertTrue(String.valueOf(e.getMessage()).contains("FLOTTILA_POLLER_LEASE_RENEW") && e.getMessage().contains("FLOTTILA_POLLER_LEASE_TTL"),
                    "a renew period above half the ttl is refused naming both keys: " + e.getMessage());
        }
    }

    /** The Valkey password never shows: not in the config's line, not in its leases' line, not in a refusal of the URI. */
    @Test
    void theValkeyPasswordIsRedacted() {
        String password = "valkey-pass-4711";
        NodeConfig c = NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", "redis://flottila:" + password + "@valkey.internal:6379"));
        assertEquals(Optional.of(URI.create("redis://flottila:" + password + "@valkey.internal:6379")), c.leases().valkey(),
                "the password is read: the checks below are not vacuous");
        for (Object shown : new Object[] {c, c.leases()}) {
            String text = shown.toString();
            assertFalse(text.contains(password), "a log line of " + shown.getClass().getSimpleName() + " must not carry the Valkey password: " + text);
        }
        assertTrue(c.leases().toString().contains("valkey.internal"), "the rest still shows: " + c.leases());
        assertTrue(c.toString().contains("valkey.internal"), "the config's line shows its Valkey host: " + c);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", "http://flottila:" + password + "@valkey.internal:6379")));
        assertTrue(e.getMessage().contains("FLOTTILA_VALKEY_URI"), e.getMessage());
        assertFalse(e.getMessage().contains(password), "a refusal is logged: it must not carry the password either: " + e.getMessage());
    }

    /**
     * Review r1: of the Valkey URI only {@code scheme://host:port[/db]} is ever printed — not its user info, not its query (a
     * {@code ?password=} is as secret as the user info) — in the leases' line, the config's line and every refusal; the client
     * still gets the whole URI.
     */
    @Test
    void theValkeyUriPrintsOnlySchemeHostPortAndDb() {
        Map<String, String> printedAs = Map.of(
                "redis://valkey:6379?password=s3cr3t", "redis://valkey:6379",
                "rediss://u:p@h:6380/2?password=x", "rediss://h:6380/2");
        List<String> secrets = List.of("s3cr3t", "password=x", "?password", "u:p@");   // the Pg line's own "password=***" is no leak
        for (Map.Entry<String, String> uri : printedAs.entrySet()) {
            NodeConfig c = NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", uri.getKey()));
            assertEquals(Optional.of(URI.create(uri.getKey())), c.leases().valkey(), "the whole URI is read: the client gets its secret");
            for (String text : List.of(c.leases().toString(), c.toString())) {
                assertTrue(text.contains(uri.getValue()), "printed as " + uri.getValue() + ": " + text);
                assertFalse(text.contains(uri.getValue() + "?"), "no query printed after " + uri.getValue() + ": " + text);
                for (String secret : secrets) assertFalse(text.contains(secret), "'" + secret + "' is not printed: " + text);
            }
        }

        List<Map.Entry<String, Map<String, String>>> refused = List.of(
                Map.entry("FLOTTILA_VALKEY_URI", with(minimal(), "FLOTTILA_VALKEY_URI", "http://valkey:6379?password=s3cr3t")),
                Map.entry("FLOTTILA_VALKEY_URI", with(minimal(), "FLOTTILA_VALKEY_URI", "valkey:6379?password=s3cr3t")),
                Map.entry("FLOTTILA_VALKEY_URI", with(minimal(), "FLOTTILA_VALKEY_URI", "redis://valkey:6379/ x?password=s3cr3t")),
                Map.entry("FLOTTILA_VALKEY_URI", with(minimal(), "FLOTTILA_VALKEY_URI", "http://u:p@h:6380/2?password=s3cr3t")),
                Map.entry("FLOTTILA_POLLER_LEASE_TTL", with(with(minimal(), "FLOTTILA_VALKEY_URI", "rediss://u:p@h:6380/2?password=s3cr3t"),
                        "FLOTTILA_POLLER_LEASE_TTL", "PT0S")),
                Map.entry("FLOTTILA_POLLER_LEASE_RENEW", with(with(with(minimal(), "FLOTTILA_VALKEY_URI", "redis://valkey:6379?password=s3cr3t"),
                        "FLOTTILA_POLLER_LEASE_TTL", "PT1M"), "FLOTTILA_POLLER_LEASE_RENEW", "PT31S")));
        for (Map.Entry<String, Map<String, String>> r : refused) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(r.getValue()), "refused: " + r.getKey());
            String text = String.valueOf(e.getMessage());
            assertTrue(text.contains(r.getKey()), "the refusal names " + r.getKey() + ": " + text);
            for (String secret : secrets) assertFalse(text.contains(secret), "a refusal is logged: '" + secret + "' is not in it: " + text);
        }
    }

    /**
     * Review r2: a password with an unencoded {@code /} ({@code redis://:abc/def+g==@valkey:6379}) parses with no host — the
     * {@code /} ends the authority — so it is refused, naming the key and saying it has no host, and never shown.
     */
    @Test
    void aValkeyUriWithNoHostIsRefusedUnshown() {
        List<String> secrets = List.of("abc", "def", "g==");
        IllegalArgumentException hostless = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", "redis://:abc/def+g==@valkey:6379")), "a URI with no host is refused");
        String text = String.valueOf(hostless.getMessage());
        assertTrue(text.contains("FLOTTILA_VALKEY_URI"), "the refusal names the key: " + text);
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("no host"), "the refusal says the URI has no host: " + text);
        for (String secret : secrets) assertFalse(text.contains(secret), "'" + secret + "' is not in the refusal: " + text);
    }

    /** Review r2: the wrong-scheme refusal of a URI whose password holds an unencoded {@code /} does not echo that password. */
    @Test
    void aWrongSchemeRefusalDoesNotEchoAPasswordWithASlash() {
        IllegalArgumentException scheme = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", "http://:abc/def@h:6379")), "a wrong scheme is refused");
        String schemeText = String.valueOf(scheme.getMessage());
        assertTrue(schemeText.contains("FLOTTILA_VALKEY_URI"), "the refusal names the key: " + schemeText);
        for (String secret : List.of("abc", "def")) assertFalse(schemeText.contains(secret), "'" + secret + "' is not in the refusal: " + schemeText);
    }

    /** Review r3 (adopted from the implementer's scratch, mutant P1): a Valkey URI without a port prints none — no {@code :-1}. */
    @Test
    void aValkeyUriWithoutAPortPrintsNone() {
        NodeConfig c = NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", "redis://valkey/2"));
        for (String shown : List.of(c.leases().toString(), c.toString())) {
            assertTrue(shown.contains("redis://valkey/2"), "no port, none printed: " + shown);
            assertFalse(shown.contains(":-1"), "no port number made up: " + shown);
        }
    }

    /** Review r2: of the Valkey URI's path only a {@code /<digits>} database is printed. */
    @Test
    void onlyADbPathOfTheValkeyUriPrints() {
        NodeConfig c = NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", "redis://valkey:6379/notadb"));
        for (String shown : List.of(c.leases().toString(), c.toString())) {
            assertTrue(shown.contains("redis://valkey:6379"), "printed as redis://valkey:6379: " + shown);
            assertFalse(shown.contains("/notadb"), "a path that is no /<digits> database is not printed: " + shown);
        }
        NodeConfig db = NodeConfig.from(with(minimal(), "FLOTTILA_VALKEY_URI", "redis://valkey:6379/3"));
        assertTrue(db.leases().toString().contains("redis://valkey:6379/3"), "a /<digits> database is printed: " + db.leases());
    }

    /**
     * Review r1: the leases count in whole milliseconds (Valkey's PX), so a ttl or a renew period with a part below a millisecond
     * is refused naming its key — however small (PT0.0005S) or large (PT10.0005S) the rest.
     */
    @Test
    void aSubMillisecondTtlOrRenewIsRefused() {
        List<Map.Entry<String, Map<String, String>>> refused = List.of(
                Map.entry("FLOTTILA_POLLER_LEASE_TTL", with(with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT10.0005S"), "FLOTTILA_POLLER_LEASE_RENEW", "PT5S")),
                Map.entry("FLOTTILA_POLLER_LEASE_TTL", with(with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT30.0005S"), "FLOTTILA_POLLER_LEASE_RENEW", "PT10S")),
                Map.entry("FLOTTILA_POLLER_LEASE_RENEW", with(with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT30S"), "FLOTTILA_POLLER_LEASE_RENEW", "PT10.0005S")),
                Map.entry("FLOTTILA_POLLER_LEASE_RENEW", with(with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT30S"), "FLOTTILA_POLLER_LEASE_RENEW", "PT0.0005S")));
        for (Map.Entry<String, Map<String, String>> r : refused) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(r.getValue()), "refused: " + r.getValue());
            assertTrue(String.valueOf(e.getMessage()).contains(r.getKey()), "the refusal names " + r.getKey() + ": " + e.getMessage());
        }
        // both below a millisecond: refused, whichever key it names first
        Map<String, String> both = with(with(minimal(), "FLOTTILA_POLLER_LEASE_TTL", "PT0.0005S"), "FLOTTILA_POLLER_LEASE_RENEW", "PT0.00025S");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(both), "refused: " + both);
        assertTrue(e.getMessage().contains("FLOTTILA_POLLER_LEASE_TTL") || e.getMessage().contains("FLOTTILA_POLLER_LEASE_RENEW"), e.getMessage());
    }

    /** P2-07 (D-088): the retention job's keys default to a max age of P7D, an interval of PT1H and batches of 1000 rows. */
    @Test
    void retentionDefaults() {
        assertEquals(new Retention.Config(Duration.ofDays(7), Duration.ofHours(1), 1000), NodeConfig.from(minimal()).retention(),
                "FLOTTILA_RETENTION_MAX_AGE P7D, FLOTTILA_RETENTION_INTERVAL PT1H, FLOTTILA_RETENTION_BATCH 1000");
    }

    @Test
    void retentionReadsEveryKeyUnderItsOwnName() {
        NodeConfig c = NodeConfig.from(with(with(with(minimal(), "FLOTTILA_RETENTION_MAX_AGE", "P30DT12H"), "FLOTTILA_RETENTION_INTERVAL", "PT15M"),
                "FLOTTILA_RETENTION_BATCH", "250"));
        assertEquals(new Retention.Config(Duration.ofHours(30 * 24 + 12), Duration.ofMinutes(15), 250), c.retention(), "ISO-8601 durations, a whole number");
    }

    /** The edges of each range are accepted: max age PT1H..P366D, interval PT1M..P1D, batch 1..10000. */
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"FLOTTILA_RETENTION_MAX_AGE, PT1H", "FLOTTILA_RETENTION_MAX_AGE, P366D", "FLOTTILA_RETENTION_INTERVAL, PT1M",
            "FLOTTILA_RETENTION_INTERVAL, P1D", "FLOTTILA_RETENTION_BATCH, 1", "FLOTTILA_RETENTION_BATCH, 10000"})
    void retentionAcceptsTheEdgesOfEachRange(String key, String value) {
        Retention.Config c = NodeConfig.from(with(minimal(), key, value)).retention();
        Object read = switch (key) {
            case "FLOTTILA_RETENTION_MAX_AGE" -> c.maxAge();
            case "FLOTTILA_RETENTION_INTERVAL" -> c.interval();
            default -> c.batch();
        };
        assertEquals(key.endsWith("BATCH") ? (Object) Integer.parseInt(value) : Duration.parse(value), read);
    }

    static Stream<Arguments> retentionRefusals() {
        return Stream.of(
                Arguments.of("FLOTTILA_RETENTION_MAX_AGE", "PT59M59.999999999S"),   // a nanosecond below the floor
                Arguments.of("FLOTTILA_RETENTION_MAX_AGE", "P366DT0.000000001S"),   // a nanosecond above the ceiling
                Arguments.of("FLOTTILA_RETENTION_MAX_AGE", "PT0S"),
                Arguments.of("FLOTTILA_RETENTION_MAX_AGE", "-P7D"),
                Arguments.of("FLOTTILA_RETENTION_MAX_AGE", "7d"),
                Arguments.of("FLOTTILA_RETENTION_MAX_AGE", "a week"),
                Arguments.of("FLOTTILA_RETENTION_INTERVAL", "PT59.999999999S"),
                Arguments.of("FLOTTILA_RETENTION_INTERVAL", "P1DT0.000000001S"),
                Arguments.of("FLOTTILA_RETENTION_INTERVAL", "PT0S"),
                Arguments.of("FLOTTILA_RETENTION_INTERVAL", "-PT1H"),
                Arguments.of("FLOTTILA_RETENTION_INTERVAL", "1h"),
                Arguments.of("FLOTTILA_RETENTION_BATCH", "0"),
                Arguments.of("FLOTTILA_RETENTION_BATCH", "10001"),
                Arguments.of("FLOTTILA_RETENTION_BATCH", "-1"),
                Arguments.of("FLOTTILA_RETENTION_BATCH", "many"),
                Arguments.of("FLOTTILA_RETENTION_BATCH", "1.5"),
                Arguments.of("FLOTTILA_RETENTION_BATCH", "4294967297"));   // beyond an int: not wrapped into range
    }

    /**
     * Out of range or unreadable: refused at start naming the key; the refusal prints the key and its bounds, never the value —
     * not as written, not as parsed ("0" is the one value not checked: every refusal of the batch says 10000).
     */
    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("retentionRefusals")
    void retentionRefusalsNameTheKeyAndShowNoValue(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(minimal(), key, value)), key + "=" + value);
        String message = String.valueOf(e.getMessage());
        assertTrue(message.contains(key), "the refusal names " + key + ": " + message);
        if (!value.equals("0")) assertFalse(message.contains(value), "the refusal does not print the value '" + value + "': " + message);
        try {
            String parsed = Duration.parse(value).toString();
            assertFalse(message.contains(parsed), "nor the value as parsed, " + parsed + ": " + message);
        } catch (java.time.format.DateTimeParseException notADuration) {
            assertTrue(notADuration.getMessage().contains("Text cannot be parsed"), "fixture: not a duration: " + notADuration.getMessage());
        }
    }
}
