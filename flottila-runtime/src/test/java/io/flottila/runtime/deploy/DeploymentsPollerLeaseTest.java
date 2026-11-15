package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.SequentialIntake;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.deploy.LeaseLog.Answer;
import io.flottila.runtime.deploy.LeaseLog.Hold;
import io.flottila.runtime.lease.InMemoryPollerLeases;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static io.flottila.runtime.deploy.LeaseLog.quiet;
import static io.flottila.runtime.deploy.LeaseLog.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02L (D-083): the deploy asks each in endpoint's component {@code polls(cfg)} — in the same try as the consumer's build, so
 * a throw is a problem line — and wraps the consumer of a polling one in a {@code LeasedConsumer} under the lease
 * {@code <package>/<flow>/<endpoint uri as the component gets it>}, owned by {@code <nodeId>#<n>} (n counted per Deployments: every
 * leased consumer its own owner), ttl and renew period from the node's config. A consumer that does not poll starts as before
 * and is never leased. A redeploy's phase A releases the old lease (its consumer stopped first), so the new consumer takes it at
 * its first tick, without waiting for the ttl; a closing node releases every lease; a rolled back flow is leased again.
 *
 * <p>The shape these cases assume (P2-02L test-author): {@code Component.polls(EndpointConfig)}; the longest {@link Deployments}
 * constructor takes a {@code PollerLeases} after the intake; {@link NodeConfig} reads {@code FLOTTILA_POLLER_LEASE_TTL} and
 * {@code FLOTTILA_POLLER_LEASE_RENEW}; {@code InMemoryPollerLeases(Clock)}. The key handed to the store carries no
 * {@code flottila:poller:} prefix (the Valkey store's namespace, {@code ValkeyPollerLeasesIT}).
 */
@Timeout(60)
class DeploymentsPollerLeaseTest {

    static final String NODE = "node-1";
    static final Duration TTL = Duration.ofSeconds(40);
    static final Duration RENEW = Duration.ofSeconds(8);
    static final String FEED_KEY = "shop/feed/poll://drop";
    static final String SORT_KEY = "shop/sort/poll://slot";

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final Doors polledDoors = new Doors("poll");
    final Polled polled = new Polled(polledDoors);
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    /** The polling doors' events ({@code "stopped <flow>"} included) and every lease call, in the order made. */
    final List<String> trace = new CopyOnWriteArrayList<>();
    InMemoryPollerLeases store;
    LeaseLog leases;
    Deployments deployments;
    boolean closed;

    @BeforeEach
    void wire() {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", NODE);
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        env.put("FLOTTILA_POLLER_LEASE_TTL", TTL.toString());
        env.put("FLOTTILA_POLLER_LEASE_RENEW", RENEW.toString());
        NodeConfig config = NodeConfig.from(env);
        store = new InMemoryPollerLeases(rt.clock());
        leases = new LeaseLog(rt.clock(), store, trace::add);
        polledDoors.trace(trace::add);
        Map<String, Component> components = Map.of(Doors.SCHEME, doors, "poll", polled);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        CountingQueue queue = new CountingQueue(rt.clock());
        deployments = new Deployments(config, runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq, queue, () -> 0L,
                new InMemoryDeploymentStore(), new DirArchiveStore(config.archives().dir()), new SequentialIntake(messages, queue), leases);
    }

    @AfterEach
    void closeTheNode() throws Exception {
        leases.answerEveryOpenHold();
        doors.releaseAll();
        polledDoors.releaseAll();
        if (!closed) close();
    }

    // --- fixture

    /** The {@code poll} scheme: a {@link Doors} whose {@code polls} answers {@link #answer} for each endpoint, every ask recorded. */
    static final class Polled implements Component {
        final Doors inner;
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile Function<EndpointConfig, Boolean> answer = cfg -> true;

        Polled(Doors inner) { this.inner = inner; }

        @Override public String scheme() { return inner.scheme(); }

        @Override public String configSchema() { return inner.configSchema(); }

        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return inner.consumer(cfg, handler); }

        @Override public Producer producer(EndpointConfig cfg) { return inner.producer(cfg); }

        @Override public Future<Health> health() { return inner.health(); }

        @Override public Optional<InetSocketAddress> listensOn(EndpointConfig cfg) { return inner.listensOn(cfg); }

        @Override
        public boolean polls(EndpointConfig cfg) {
            asked.add(cfg.name());
            return answer.apply(cfg);
        }
    }

    /** {@code shop} at {@code version}: flow {@code feed} from the poll endpoint {@code drop}, flow {@code orders} from a rec one. */
    static PackageDraft shop(String version, String feedVersion, String ordersVersion) {
        return PackageDraft.of("shop", version)
                .endpoint("drop", "poll", "in", new JsonObject().put("path", "/drop"))
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("feed", feedVersion, "sync", "drop", 4096, PackageDraft.toPartner())
                .flow("orders", ordersVersion, "sync", "orders-in", 4096, PackageDraft.toPartner());
    }

    DeployedPackage deploy(PackageDraft p) throws Exception {
        return OnVirtualThread.call(() -> deployments.deploy(p.manifest().getString("name"), Optional.empty(), p.zipTo(tmp.resolve("uploads")))).pkg();
    }

    DeployRefusedException refused(PackageDraft p) {
        return assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> deployments.deploy(p.manifest().getString("name"), Optional.empty(), p.zipTo(tmp.resolve("uploads")))),
                "the deploy is refused");
    }

    void close() throws Exception {
        closed = true;
        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });
    }

    /** The doors built for {@code flow} on the poll scheme, in build order. */
    List<Doors.Door> polledDoorsOf(String flow) {
        return polledDoors.doors().stream().filter(d -> d.flow().equals(flow)).toList();
    }

    /** Fires what is due now (a leased consumer's tick 0) and waits for the {@code n}th hold and its tick. */
    Hold firstTick(int n) {
        rt.clock().advance(Duration.ZERO);
        Hold h = leases.hold(n);
        quiet(h.thread(), "the tick of hold #" + n);
        return h;
    }

    // --- cases

    /** A consumer whose component says it does not poll starts with the deploy, as before, and is never leased. */
    @Test
    void aNonPollingConsumerIsNeverLeased() throws Exception {
        polled.answer = cfg -> false;

        deploy(shop("1.0.0", "1.0.0", "1.0.0"));

        assertEquals(List.of("drop"), polled.asked, "the deploy asked the component about its in endpoint");
        assertTrue(polledDoorsOf("shop/feed").getFirst().started(), "a non-polling consumer is started by the deploy itself, before it returns");
        assertTrue(doors.door("shop/orders").started(), "so is the rec one (Component.polls: false by default)");
        rt.clock().advance(RENEW.multipliedBy(3));
        close();
        assertEquals(List.of(), leases.holds(), "nothing held");
        assertEquals(List.of(), leases.releases(), "nothing released");
    }

    /** A polling flow's consumer runs only under the lease {@code <package>/<flow>/<endpoint uri>}, renewed at the node's period. */
    @Test
    void thePollingFlowIsLeasedUnderItsQualifiedIdAndUri() throws Exception {
        deploy(shop("1.0.0", "1.0.0", "1.0.0"));

        Doors.Door feed = polledDoorsOf("shop/feed").getFirst();
        assertFalse(feed.started(), "a leased consumer does not start with the deploy: only once its lease is held");
        assertTrue(doors.door("shop/orders").started(), "the flow that does not poll started with the deploy");
        Hold first = firstTick(1);

        assertEquals(FEED_KEY, first.key(), "the key: the flow's qualified id and its endpoint's uri");
        assertTrue(first.owner().matches(NODE + "#[0-9]+"), "the owner: <nodeId>#<n>, was " + first.owner());
        assertEquals(TTL, first.ttl(), "the node's FLOTTILA_POLLER_LEASE_TTL");
        until(feed::started, "the consumer starts once its lease is held");
        rt.clock().advance(RENEW);
        Hold renewal = leases.hold(2);
        assertEquals(List.of(FEED_KEY, first.owner(), rt.clock().instant()), List.of(renewal.key(), renewal.owner(), renewal.at()),
                "renewed by the same owner after the node's FLOTTILA_POLLER_LEASE_RENEW");
        assertEquals(Set.of(FEED_KEY), Set.copyOf(leases.holds().stream().map(Hold::key).toList()), "only the polling flow is leased");
    }

    /** A {@code polls} that throws is one problem line of the deploy, worded like a consumer the component refuses to build. */
    @Test
    void aPollsThatThrowsIsAProblemLine() {
        polled.answer = cfg -> {
            throw new IllegalStateException("cannot tell from these options");
        };

        DeployRefusedException refused = refused(shop("1.0.0", "1.0.0", "1.0.0"));

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals(List.of("flow 'feed' does not build: cannot tell from these options"), refused.problems());
        assertTrue(polledDoors.doors().stream().noneMatch(Doors.Door::started) && doors.doors().stream().noneMatch(Doors.Door::started),
                "nothing started");
        assertEquals(List.of(), leases.holds());
    }

    /**
     * A redeploy that changes the polling flow: phase A stops the old consumer, then releases its lease; the new consumer, its own
     * owner, takes the lease at its first tick — at the same instant, not a ttl later.
     */
    @Test
    void aRedeployOnOneNodeTakesTheLeaseWithoutWaitingTheTtl() throws Exception {
        deploy(shop("1.0.0", "1.0.0", "1.0.0"));
        Hold old = firstTick(1);
        Doors.Door v1 = polledDoorsOf("shop/feed").getFirst();
        until(v1::started, "fixture: v1's consumer polls");

        deploy(shop("2.0.0", "1.0.1", "1.0.0"));

        assertEquals(List.of(FEED_KEY + " " + old.owner()), leases.releases().stream().map(r -> r.key() + " " + r.owner()).toList(),
                "phase A released v1's lease");
        assertTrue(trace.indexOf("stopped shop/feed") >= 0 && trace.indexOf("stopped shop/feed") < trace.indexOf("release " + FEED_KEY + " " + old.owner()),
                "after v1's consumer stopped: " + trace);
        Hold taken = firstTick(2);
        assertNotEquals(old.owner(), taken.owner(), "the new consumer holds as an owner of its own");
        assertEquals(Boolean.TRUE, taken.result(), "and takes the lease at once");
        assertEquals(FakeRuntime.T0, rt.clock().instant(), "no ttl passed");
        Doors.Door v2 = polledDoorsOf("shop/feed").get(1);
        until(v2::started, "v2's consumer polls");
    }

    /** A closing node stops every leased consumer, then releases its lease: another node may hold each at once. */
    @Test
    void closeReleasesEveryLease() throws Exception {
        deploy(PackageDraft.of("shop", "1.0.0")
                .endpoint("drop", "poll", "in", new JsonObject().put("path", "/drop"))
                .endpoint("slot", "poll", "in", new JsonObject().put("path", "/slot"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("feed", "1.0.0", "sync", "drop", 4096, PackageDraft.toPartner())
                .flow("sort", "1.0.0", "sync", "slot", 4096, PackageDraft.toPartner()));
        rt.clock().advance(Duration.ZERO);
        quiet(leases.hold(1).thread(), "a tick 0");
        quiet(leases.hold(2).thread(), "the other tick 0");
        until(() -> polledDoors.doors().stream().allMatch(Doors.Door::started), "both consumers poll");
        Map<String, String> owners = new HashMap<>();
        for (Hold h : leases.holds()) owners.put(h.key(), h.owner());
        assertEquals(Set.of(FEED_KEY, SORT_KEY), owners.keySet(), "one lease per polling flow");

        close();

        assertEquals(Set.of(FEED_KEY + " " + owners.get(FEED_KEY), SORT_KEY + " " + owners.get(SORT_KEY)),
                Set.copyOf(leases.releases().stream().map(r -> r.key() + " " + r.owner()).toList()), "each lease released by its owner");
        assertTrue(trace.indexOf("stopped shop/feed") < trace.indexOf("release " + FEED_KEY + " " + owners.get(FEED_KEY)), "feed stopped first: " + trace);
        assertTrue(trace.indexOf("stopped shop/sort") < trace.indexOf("release " + SORT_KEY + " " + owners.get(SORT_KEY)), "sort stopped first: " + trace);
        assertEquals(List.of(true, true), List.of(store.hold(FEED_KEY, "node-2#1", TTL).await(), store.hold(SORT_KEY, "node-2#1", TTL).await()),
                "another node holds both at once, no ttl later");
    }

    /**
     * v2 changes both flows; its rec flow does not start, so the deploy rolls back. v1's feed, built and started again, holds the
     * lease again at its first tick: phase A released v1's, and v2's consumer, which never held it, released nothing.
     */
    @Test
    void aRolledBackFlowIsLeasedAgain() throws Exception {
        deploy(shop("1.0.0", "1.0.0", "1.0.0"));
        Hold v1 = firstTick(1);
        until(polledDoorsOf("shop/feed").getFirst()::started, "fixture: v1's consumer polls");
        doors.failStartOf("shop/orders", "address in use");

        DeployRefusedException refused = refused(shop("2.0.0", "1.0.1", "1.0.1"));

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals(List.of(FEED_KEY + " " + v1.owner()), leases.releases().stream().map(r -> r.key() + " " + r.owner()).toList(),
                "only v1's lease was released (v2's consumer never held it)");
        Hold again = firstTick(2);
        assertEquals(FEED_KEY, again.key());
        assertNotEquals(v1.owner(), again.owner(), "the rebuilt consumer is an owner of its own");
        assertEquals(Boolean.TRUE, again.result(), "the rolled back flow holds the lease again");
        List<Doors.Door> feeds = polledDoorsOf("shop/feed");
        assertEquals(3, feeds.size(), "v1's, v2's and v1's rebuilt consumer: " + feeds);
        assertFalse(feeds.get(1).started(), "v2's never started: it never held the lease");
        until(feeds.get(2)::started, "the rebuilt v1 consumer polls");
    }

    /**
     * Adopted from the implementer's scratch (P2-02L verify): a deployed polling flow that loses its lease and holds it again runs
     * a fresh consumer, built by its component for the same endpoint with the same handler as the first; the stopped one is not
     * started again.
     */
    @Test
    void aRegainedLeaseRunsAFreshConsumerOfTheSameEndpointAndHandler() throws Exception {
        leases.next(Answer.TRUE, Answer.FALSE, Answer.TRUE);
        deploy(shop("1.0.0", "1.0.0", "1.0.0"));
        firstTick(1);
        Doors.Door first = polledDoorsOf("shop/feed").getFirst();
        until(first::started, "fixture: the first consumer polls");

        rt.clock().advance(RENEW);
        quiet(leases.hold(2).thread(), "tick 1: lost");
        until(first::stopped, "fixture: the loss stopped it");
        rt.clock().advance(RENEW);
        quiet(leases.hold(3).thread(), "tick 2: held again");

        List<Doors.Door> built = polledDoorsOf("shop/feed");
        assertEquals(2, built.size(), "a fresh consumer was built at the regain: " + built);
        Doors.Door fresh = built.get(1);
        until(fresh::started, "the fresh consumer polls");
        assertEquals(first.cfg(), fresh.cfg(), "built for the same endpoint, as the component gets it");
        assertSame(first.handler(), fresh.handler(), "with the same handler: the flow's");
        assertEquals(1, first.history().stream().filter("start"::equals).count(), "the stopped consumer was not started again");
    }
}
