package io.flottila.runtime.node;

import io.flottila.api.Component;
import io.flottila.components.http.HttpComponent;
import io.flottila.components.mock.DisguisedMock;
import io.flottila.components.mock.nested.NestedMock;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
}
