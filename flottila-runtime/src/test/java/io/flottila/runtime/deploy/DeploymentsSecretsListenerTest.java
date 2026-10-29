package io.flottila.runtime.deploy;

import io.flottila.api.Component;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.flottila.runtime.deploy.DeploymentsListenerTest.flowOf;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.line;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.listening;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.on;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.pkg;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P2-04 (D-081): a component never sees a reference — {@code listensOn} is asked on the in endpoint's config with its
 * {@code secret://} references resolved, so the one-listener-per-port check (D-072) compares the addresses the consumers would
 * really listen on.
 */
class DeploymentsSecretsListenerTest {

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    Deployments deployments;

    @AfterEach
    void close() throws Exception {
        doors.releaseAll();
        if (deployments != null) OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    /** A dev node whose admin API is on 127.0.0.1:8181, with {@code FLOTTILA_SECRET_LISTEN_HOST} 10.0.0.5 unless {@code host} is false. */
    Deployments node(boolean host) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("node-1").toString());
        env.put("FLOTTILA_ADMIN_PORT", "8181");
        if (host) env.put("FLOTTILA_SECRET_LISTEN_HOST", "10.0.0.5");
        Map<String, Component> components = Map.of(Doors.SCHEME, doors);
        deployments = new Deployments(NodeConfig.from(env), new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components),
                components, rt.clock(), rt.timer(), List.of(), List.of(), new InMemoryMessageStore(), new InMemoryDlq(),
                new InMemoryWorkQueue(rt.clock()), () -> 0L);
        return deployments;
    }

    DeployRefusedException refused(Deployments d, PackageDraft p) {
        Path archive = p.zipTo(tmp.resolve("uploads"));
        String name = p.manifest().getString("name");
        DeployRefusedException refused = assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> d.deploy(name, Optional.empty(), archive)), "the deploy is refused");
        assertEquals(Kind.INVALID, refused.kind(), refused.problems().toString());
        return refused;
    }

    /** {@code a-in}'s host is a reference to 10.0.0.5, {@code b-in}'s is 10.0.0.5 as written, both on 9100: they overlap. */
    @Test
    void listensOnIsAskedOnTheResolvedConfig() {
        PackageDraft p = listening(listening(pkg("hosts"), "a", on("secret://listen-host", 9100)), "b", on("10.0.0.5", 9100));

        DeployRefusedException refused = refused(node(true), p);

        assertEquals(List.of(line("b", "b-in", "10.0.0.5:9100", "10.0.0.5:9100", flowOf("a", "a-in"))), refused.problems());
    }

    /**
     * An in endpoint whose reference is not set is not asked where it listens: its config lacks that value. Here the host would
     * fall back to 127.0.0.1 on the admin API's port 8181 — the one problem is the missing variable, no overlap is made up.
     */
    @Test
    void anUnresolvedEndpointIsNotAskedWhereItListens() {
        PackageDraft p = listening(pkg("unset"), "a", on("secret://listen-host", 8181));

        DeployRefusedException refused = refused(node(false), p);

        assertEquals(List.of("endpoint 'a-in': secret 'secret://listen-host' is not set (FLOTTILA_SECRET_LISTEN_HOST)"), refused.problems());
        assertEquals(List.of(), doors.listensOnAsked(), "the component was not asked");
    }
}
