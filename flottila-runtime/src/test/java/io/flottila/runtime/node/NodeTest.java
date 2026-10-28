package io.flottila.runtime.node;

import io.flottila.api.Component;
import io.flottila.components.http.HttpComponent;
import io.flottila.components.mock.DisguisedMock;
import io.flottila.components.mock.nested.NestedMock;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09b: a deployed node refuses a mock binding (§11, D-057 amended) — by the scheme {@code mock} and by the component's
 * package ({@code io.flottila.components.mock..}, {@code io.flottila.testkit..}) — from {@code Node.start}, before it
 * touches Postgres and with nothing left listening. Postgres here is a port nothing listens on: a node that connects before
 * the check fails with a refused connection, not with the refusal. The positive side (dev and ci accept a mock, a deployed
 * node accepts a plain component) needs a running node: {@code io.flottila.runtime.NodeIT}.
 */
class NodeTest {

    static Vertx vertx;

    @TempDir Path data;

    @BeforeAll
    static void startVertx() { vertx = Vertx.vertx(); }

    @AfterAll
    static void closeVertx() { vertx.close().await(); }

    /** A loopback port nothing listens on: bound by the kernel, then released. */
    static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    static Stream<Arguments> mocks() {
        return Stream.of(
                Arguments.of("the scheme mock, any package", (Supplier<Component>) () -> new PlainComponent("mock")),
                Arguments.of("the testkit's package, scheme http", (Supplier<Component>) () -> new RecordingComponent("http")),
                Arguments.of("the mock's package, scheme http", (Supplier<Component>) DisguisedMock::new),
                Arguments.of("below the mock's package, scheme sftp", (Supplier<Component>) NestedMock::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mocks")
    void startRefusesAMockComponentWhenDeployed(String variant, Supplier<Component> mock) throws Exception {
        NodeConfig config = NodeConfig.from(Map.of(
                "FLOTTILA_ENV", "test", "FLOTTILA_NODE_ID", "node-1", "FLOTTILA_ADMIN_PORT", "0", "FLOTTILA_DATA_DIR", data.toString(),
                "FLOTTILA_PG_HOST", "127.0.0.1", "FLOTTILA_PG_PORT", String.valueOf(closedPort())));
        assertTrue(config.deployed(), "§11: test is a deployed environment");
        Component offender = mock.get();
        Set<String> before = Set.copyOf(vertx.deploymentIDs());
        // the offender comes second: every component is checked, not only the first; its scheme is no offender's (no duplicate scheme)
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new PlainComponent("jms"), offender), List.of(), List.of())),
                variant);
        assertTrue(String.valueOf(refused.getMessage()).contains(offender.getClass().getName()),
                "the refusal names the mock binding's class: " + refused.getMessage());
        assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "nothing left listening: no verticle (the admin server is one) stays deployed");
    }

    /**
     * Two components bound to one scheme are refused at start in every environment (architect's decision, P1-09b): which one
     * serves the scheme is never a matter of list order. Refused like a mock binding, before Postgres.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dev", "prod"})
    void twoComponentsWithOneSchemeAreRefused(String env) throws Exception {
        NodeConfig config = NodeConfig.from(Map.of(
                "FLOTTILA_ENV", env, "FLOTTILA_NODE_ID", "node-1", "FLOTTILA_ADMIN_PORT", "0", "FLOTTILA_DATA_DIR", data.toString(),
                "FLOTTILA_PG_HOST", "127.0.0.1", "FLOTTILA_PG_PORT", String.valueOf(closedPort())));
        Set<String> before = Set.copyOf(vertx.deploymentIDs());
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> OnVirtualThread.call(() -> Node.start(config, vertx,
                ctx -> List.of(new PlainComponent("jms"), new PlainComponent("http"), new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())), List.of(), List.of())));
        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains(PlainComponent.class.getName()) && message.contains(HttpComponent.class.getName()),
                "the refusal names both classes bound to http: " + message);
        assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "nothing left listening");
    }

    // --- P1-09c3a (D-070): a deployed node's archive store

    /** A node's environment over a Postgres port nothing listens on: a node that gets as far as Postgres fails with a refused connection. */
    static Map<String, String> closedPostgres(String env, Path data) throws IOException {
        Map<String, String> m = new java.util.HashMap<>(Map.of(
                "FLOTTILA_ENV", env, "FLOTTILA_NODE_ID", "node-1", "FLOTTILA_ADMIN_PORT", "0", "FLOTTILA_DATA_DIR", data.toString(),
                "FLOTTILA_PG_HOST", "127.0.0.1", "FLOTTILA_PG_PORT", String.valueOf(closedPort())));
        return m;
    }

    /** {@code env} with an s3 archive store at {@code endpoint}, keys given (the SDK's default chain is never asked). */
    static Map<String, String> s3(Map<String, String> env, String endpoint) {
        env.put("FLOTTILA_ARCHIVE_STORE", "s3");
        env.put("FLOTTILA_S3_BUCKET", "flottila-archives");
        env.put("FLOTTILA_S3_REGION", "eu-central-1");
        env.put("FLOTTILA_S3_ENDPOINT", endpoint);
        env.put("FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE");
        env.put("FLOTTILA_S3_SECRET_KEY", "secret-example");
        return env;
    }

    /** What {@code Node.start} throws with {@code env} and plain components; it must throw. */
    Throwable startFails(Map<String, String> env, List<Component> components) {
        NodeConfig config = NodeConfig.from(env);
        return assertThrows(Throwable.class, () -> OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> components, List.of(), List.of())),
                "the node does not start: " + env);
    }

    static boolean refusedConnection(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) if (c instanceof java.net.ConnectException) return true;
        return false;
    }

    /** The node passed the archive checks and got as far as Postgres: its start failed on the refused connection, nothing else. */
    static void reachedPostgres(Throwable failure, String what) {
        assertTrue(refusedConnection(failure), what + ": the start got past the archive checks to Postgres (a refused connection), was " + failure);
    }

    /**
     * A deployed node refuses the dir archive store — the default — with an IllegalStateException naming FLOTTILA_ARCHIVE_STORE,
     * after the mock check (a mock is refused first) and before it touches Postgres, with nothing left listening; dev takes it.
     */
    @Test
    void aDeployedNodeRefusesADirArchiveStoreBeforePostgres() throws Exception {
        Set<String> before = Set.copyOf(vertx.deploymentIDs());
        Map<String, String> byDefault = closedPostgres("test", data);
        Map<String, String> explicit = closedPostgres("prod", data);
        explicit.put("FLOTTILA_ARCHIVE_STORE", "dir");
        for (Map<String, String> env : List.of(byDefault, explicit)) {
            Throwable refused = startFails(env, List.of(new PlainComponent("jms")));
            assertInstanceOf(IllegalStateException.class, refused, "a deployed node refuses the dir archive store: " + env);
            assertTrue(String.valueOf(refused.getMessage()).contains("FLOTTILA_ARCHIVE_STORE"), "the refusal names the key: " + refused.getMessage());
            assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "nothing left listening");
        }
        Throwable mockFirst = startFails(byDefault, List.of(new PlainComponent("jms"), new PlainComponent("mock")));
        assertInstanceOf(IllegalStateException.class, mockFirst);
        assertTrue(String.valueOf(mockFirst.getMessage()).contains("mock") && !String.valueOf(mockFirst.getMessage()).contains("FLOTTILA_ARCHIVE_STORE"),
                "the mock check comes first: its refusal, not the archive store's: " + mockFirst.getMessage());
        reachedPostgres(startFails(closedPostgres("dev", data), List.of(new PlainComponent("jms"))), "dev with the dir store");
        assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "nothing left listening");
    }

    /**
     * A deployed node refuses an S3 endpoint over plain http with an IllegalStateException naming FLOTTILA_S3_ENDPOINT, before it
     * touches Postgres; https passes the check, and so does http in dev (LocalStack).
     */
    @Test
    void aDeployedNodeRefusesAPlainHttpS3Endpoint() throws Exception {
        Set<String> before = Set.copyOf(vertx.deploymentIDs());
        String http = "http://127.0.0.1:" + closedPort();
        Throwable refused = startFails(s3(closedPostgres("test", data), http), List.of(new PlainComponent("jms")));
        assertInstanceOf(IllegalStateException.class, refused, "a deployed node refuses a plain-http S3 endpoint");
        assertTrue(String.valueOf(refused.getMessage()).contains("FLOTTILA_S3_ENDPOINT"), "the refusal names the key: " + refused.getMessage());
        assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "nothing left listening");
        reachedPostgres(startFails(s3(closedPostgres("test", data), "https://127.0.0.1:" + closedPort()), List.of(new PlainComponent("jms"))),
                "a deployed node with an https endpoint");
        reachedPostgres(startFails(s3(closedPostgres("dev", data), http), List.of(new PlainComponent("jms"))), "dev with a plain-http endpoint");
        assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "nothing left listening");
    }
}
