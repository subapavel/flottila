package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.admission.TargetLimit;
import io.flottila.core.admission.Targets;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.SequentialIntake;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.lease.InMemoryPollerLeases;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static io.flottila.runtime.deploy.DeploymentsTest.until;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * P2-09b (D-099): the node builds one {@link Targets} and its {@link Deployments} hands it to every FlowBuilder it builds — a
 * deploy, a redeploy and a restore — so every flow whose {@code to} names one target shares that target's limit, across
 * packages and across the old and the new version of a flow during a swap.
 *
 * <p>Every node here is a {@link Deployments} over in-memory stores and its own {@link Doors}, whose producers name the target
 * their endpoint's {@code url} option holds ({@link Doors#targets}); its {@link Targets} gives {@value #PARTNER} one call at a
 * time and a maxWait of an hour on the fake timer (never reached: nothing here is refused). The partner holds a send until the
 * case releases it. A wait at the limit is seen in the gauge of the node's own {@code Targets} ({@code waiting}), and the partner
 * then has not received the waiting send.
 *
 * <p>The shape assumed (P2-09b test-author): the longest constructor of {@link Deployments} takes the node's {@link Targets} as
 * its last parameter — {@code Deployments(NodeConfig, Runtime, Map<String, Component>, Clock, Timer, List<StepFactory>,
 * List<Expressions>, MessageStore, Dlq, WorkQueue, Meter, DeploymentStore, ArchiveStore, Intake, PollerLeases, Targets)}.
 */
class DeploymentsTargetsTest {

    static final String PARTNER = "rec://partner";
    static final Duration NEVER = Duration.ofHours(1);

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    final List<Peer> peers = new ArrayList<>();
    CountingQueue queue;

    @BeforeEach
    void wire() {
        queue = new CountingQueue(rt.clock());
    }

    @AfterEach
    void closeEveryNode() throws Exception {
        for (CountDownLatch l : holds) l.countDown();
        for (Peer p : peers) p.doors.releaseAll();
        for (Peer p : peers) OnVirtualThread.call(() -> {
            p.deployments.close();
            return null;
        });
    }

    // --- fixture

    /** One node: its data directory {@code <tmp>/<id>}, its Doors (naming targets by url), its Targets, its Deployments. */
    final class Peer {
        final Doors doors = new Doors();
        final Targets targets = new Targets(new Targets.Limit(16, NEVER), Map.of(PARTNER, new Targets.Limit(1, NEVER)), rt.timer());
        final Deployments deployments;

        Peer(String id, DeploymentStore rows, ArchiveStore archives) {
            doors.targets(cfg -> Optional.ofNullable(cfg.options().get("url")).map(String::valueOf));
            Map<String, String> e = new HashMap<>();
            e.put("FLOTTILA_ENV", "dev");
            e.put("FLOTTILA_NODE_ID", id);
            e.put("FLOTTILA_DATA_DIR", tmp.resolve(id).toString());
            e.put("FLOTTILA_STOP_DEADLINE", "PT37S");
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(),
                    messages, dlq, queue, () -> 0L, rows, archives, new SequentialIntake(messages, queue), new InMemoryPollerLeases(rt.clock()), targets);
            peers.add(this);
        }

        DeployResult deploy(PackageDraft p) throws Exception {
            Path archive = p.zipTo(tmp.resolve("uploads"));
            return OnVirtualThread.call(() -> deployments.deploy(p.manifest().getString("name"), Optional.empty(), archive));
        }

        /** The next send to the partner is held until the case ends or counts the latch down. */
        CountDownLatch hold() {
            CountDownLatch l = doors.partner().holdNext(1);
            holds.add(l);
            return l;
        }

        /** The gauge of the node's limit of {@link #PARTNER}. */
        TargetLimit.Gauge partner() {
            return targets.of(PARTNER).gauge();
        }
    }

    Peer node(String id) {
        return new Peer(id, new InMemoryDeploymentStore(), new DirArchiveStore(tmp.resolve(id + "-archives"), 64L << 20));
    }

    Envelope envelope(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
    }

    /** {@code name} 1.0.0: the sync flow {@code orders} from its own in endpoint to the out endpoint {@code endpoint}, at {@link #PARTNER}. */
    static PackageDraft calling(String name, String endpoint) {
        return PackageDraft.of(name, "1.0.0")
                .endpoint(endpoint, "out", new JsonObject().put("url", PARTNER))
                .endpoint("orders-in", "in", new JsonObject().put("path", "/" + name + "/orders"))
                .flow("orders", "1.0.0", "sync", "orders-in", 1024, DeploymentsRedeployTest.to(endpoint));
    }

    /** {@code name} 1.0.0: the at-least-once flow {@code audit} to the out endpoint {@code partner}, at {@link #PARTNER}. */
    static PackageDraft auditing(String name) {
        return PackageDraft.of(name, "1.0.0")
                .endpoint("partner", "out", new JsonObject().put("url", PARTNER))
                .endpoint("audit-in", "in", new JsonObject().put("path", "/" + name + "/audit"))
                .flow("audit", "1.0.0", "at-least-once", "audit-in", 1024, PackageDraft.toPartner());
    }

    // --- cases

    /**
     * Packages a and b each send to {@link #PARTNER}, through out endpoints of different names. a's message holds the one call
     * the target allows; b's message then waits at the limit — the node's gauge shows it waiting and the partner has not
     * received it — and is sent once a's send was answered. Both reply.
     */
    @Test
    void twoPackagesNamingOnePartnerShareOneLimit() throws Exception {
        Peer n = node("node-1");
        n.deploy(calling("a", "partner"));
        n.deploy(calling("b", "erp"));
        CountDownLatch held = n.hold();

        Envelope first = envelope("a/orders");
        Future<Ack> a = n.doors.door("a/orders").deliver(first);
        n.doors.partner().awaitSent(1);
        until(() -> n.partner().inFlight() == 1, "a's send holds the node's permit of " + PARTNER + ": " + n.targets.gauges());
        Envelope second = envelope("b/orders");
        Future<Ack> b = n.doors.door("b/orders").deliver(second);
        until(() -> n.partner().waiting() == 1, "b's send waits for the permit a's send holds: " + n.targets.gauges());

        assertEquals(1, n.doors.partner().sent().size(), "the partner has a's send only while b's waits: " + n.doors.partner().sent());
        assertFalse(b.isComplete(), "b has not answered while its send waits");
        held.countDown();

        assertInstanceOf(Ack.Reply.class, within(a, "a's answer"), "a replies");
        assertInstanceOf(Ack.Reply.class, within(b, "b's answer"), "b replies once a's send was answered");
        assertEquals(List.of(first.id()), n.doors.partner().sentTo("partner").stream().map(Envelope::id).toList(), "a sent through its endpoint");
        assertEquals(List.of(second.id()), n.doors.partner().sentTo("erp").stream().map(Envelope::id).toList(), "b sent through its endpoint, once");
        until(() -> n.partner().inFlight() == 0, "both permits given back: " + n.targets.gauges());
        assertEquals(List.of(PARTNER), n.targets.gauges().stream().map(TargetLimit.Gauge::target).toList(), "one limit for the one target both name");
    }

    /**
     * v1 of a/audit (at-least-once) runs message A, held at the partner, when v2 swaps it in; the deploy answers while A runs on
     * through v1's producer. B, accepted by v2, waits at the limit A's send holds — the old and the new version share the node's
     * limit — and is sent once A's send was answered. Both complete.
     */
    @Test
    void aRedeployKeepsTheLimitSharedAcrossVersions() throws Exception {
        Peer n = node("node-1");
        n.deploy(auditing("a"));
        Doors.Door v1 = n.doors.door("a/audit");
        CountDownLatch held = n.hold();
        Envelope first = envelope("a/audit");
        assertEquals(new Ack.Accepted(first.id()), within(v1.deliver(first), "A's accept"));
        n.doors.partner().awaitSent(1);
        until(() -> n.partner().inFlight() == 1, "v1's send of A holds the node's permit: " + n.targets.gauges());

        n.deploy(DeploymentsRedeployTest.changed(auditing("a"), "audit", "v2"));
        Doors.Door v2 = n.doors.door("a/audit");
        assertNotSame(v1, v2, "fixture: the redeploy swapped a/audit");
        Envelope second = envelope("a/audit");
        assertEquals(new Ack.Accepted(second.id()), within(v2.deliver(second), "B's accept"));
        rt.clock().advance(DeploymentsRedeployTest.TICK);   // v2's worker claims B on its tick, if not at once

        until(() -> n.partner().waiting() == 1, "v2's send of B waits for the permit v1's send of A holds: " + n.targets.gauges() + ", claimed " + queue.claimedIds());
        assertEquals(List.of(first.id()), n.doors.partner().sent().stream().map(Envelope::id).toList(), "the partner has A only while B waits");
        held.countDown();

        n.doors.partner().awaitSent(2);
        until(() -> queue.size() == 0, "A and B complete");
        assertEquals(List.of(first.id(), second.id()), n.doors.partner().sent().stream().map(Envelope::id).toList(), "A, then B, each once");
        until(() -> n.partner().inFlight() == 0 && n.partner().waiting() == 0, "both permits given back: " + n.targets.gauges());
        assertEquals(0, dlq.size(), "nothing parked");
    }

    /**
     * Node 1 deploys a (sync, to {@link #PARTNER}) into the pool's rows and archive store; node 2 restores it. On node 2 the
     * restored flow sends under node 2's limit: with one send held, the next waits in node 2's gauge — node 1's limit sees none
     * of it — and is sent once the first was answered.
     */
    @Test
    void aRestoredPackageUsesTheNodesTargets() throws Exception {
        Ledger rows = new Ledger(line -> {});
        Shelf shelf = new Shelf(tmp.resolve("bucket"), tmp.resolve("shelf-reads"), line -> {});
        Peer one = new Peer("node-1", rows, shelf);
        one.deploy(calling("a", "partner"));
        Peer two = new Peer("node-2", rows, shelf);
        OnVirtualThread.call(() -> {
            two.deployments.restore();
            return null;
        });
        assertEquals(List.of("a/orders"), two.deployments.flows().stream().map(DeployedFlow::id).toList(), "fixture: node 2 restored a");

        CountDownLatch held = two.hold();
        Future<Ack> first = two.doors.door("a/orders").deliver(envelope("a/orders"));
        two.doors.partner().awaitSent(1);
        until(() -> two.partner().inFlight() == 1, "the restored flow's send holds node 2's permit: " + two.targets.gauges());
        Future<Ack> second = two.doors.door("a/orders").deliver(envelope("a/orders"));
        until(() -> two.partner().waiting() == 1, "the next send waits at node 2's limit: " + two.targets.gauges());

        assertEquals(1, two.doors.partner().sent().size(), "node 2's partner has the first send only while the second waits");
        assertEquals(0, one.partner().inFlight() + one.partner().waiting(), "node 1's limit sees nothing of node 2's sends: " + one.targets.gauges());
        held.countDown();

        assertInstanceOf(Ack.Reply.class, within(first, "the first answer"));
        assertInstanceOf(Ack.Reply.class, within(second, "the second answer, once the first was answered"));
        assertEquals(2, two.doors.partner().sent().size(), "both sent, each once");
    }
}
