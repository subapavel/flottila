package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Health;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static dev.rail8.runtime.deploy.DeploymentsTest.joinRefused;
import static dev.rail8.runtime.deploy.DeploymentsTest.until;
import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two nodes of one pool over one Postgres, on a fake clock: each a {@code Deployments} with its own data directory, git cache and
 * {@link Doors}, both over the pool's {@code deployments} and {@code deploy_pool} tables ({@link PgDeploymentStore}, its lock on a
 * connection of its own per deploy). A deploy or an undeploy on one node holds the pool's lock, and a second one on the other node
 * is refused while it runs; a node behind the pool's generation installs what the records name before it plans its own deploy; the
 * other node installs and undeploys at its next tick ({@code follow}), and a package it could not install, its repository not
 * answering, at a later tick once it does. The ticks run as the node runs them, on its timer, or are called one at a time.
 */
class DeploymentsPoolIT {

    static final Instant NOW = Instant.parse("2026-11-20T09:30:00Z");
    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);
    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    static PgConnectOptions connect;

    @TempDir Path tmp;
    GitRepos repos;
    final FakeRuntime rt = FakeRuntime.create().withClockAt(NOW);
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<Peer> peers = new ArrayList<>();
    CountingQueue queue;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName()).setUser(pg.getUsername())
                .setPassword(pg.getPassword());
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() {
        pool.query("truncate deployments").execute().await();
        repos = new GitRepos(tmp.resolve("repos"));
        queue = new CountingQueue(rt.clock());
    }

    @AfterEach
    void closeEveryPeer() throws Exception {
        for (Peer p : peers) p.doors.releaseAll();
        for (Peer p : peers) p.close();
    }

    /** One node of the pool: its data directory {@code <tmp>/<id>}, its Doors, its Deployments over the pool's Postgres. */
    final class Peer {
        final String id;
        final Doors doors = new Doors();
        final Deployments deployments;

        /** A node in dev reading the pool's repositories. */
        Peer(String id) {
            this(id, repos.base().toString());
        }

        /** A node in dev reading the pool's repositories, its records through {@code records}. */
        Peer(String id, DeploymentStore records) {
            this(id, repos.base().toString(), records);
        }

        /** A node in dev whose {@code RAIL8_GIT_BASE} is {@code base}. */
        Peer(String id, String base) {
            this(id, base, new PgDeploymentStore(pool, () -> PgConnection.connect(vertx, connect).map(c -> c)));
        }

        Peer(String id, String base, DeploymentStore records) {
            this.id = id;
            Map<String, String> e = new HashMap<>();
            e.put("RAIL8_ENV", "dev");
            e.put("RAIL8_NODE_ID", id);
            e.put("RAIL8_DATA_DIR", tmp.resolve(id).toString());
            e.put("RAIL8_STOP_DEADLINE", STOP_DEADLINE.toString());
            e.put("RAIL8_BLOB_DURABLE_MAX", "4096");
            e.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            e.put("RAIL8_GIT_BASE", base);
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                    queue, () -> 0L, records);
            peers.add(this);
        }

        /** Restores, as the node does at its start. */
        Peer restored() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.restore();
                return null;
            });
            return this;
        }

        OnVirtualThread.Call<DeployResult> deploying(String repository, String commit) {
            return OnVirtualThread.start(() -> deployments.deploy(repository, commit, "test"));
        }

        DeployResult deploy(String repository, String commit) throws Exception {
            return OnVirtualThread.join(deploying(repository, commit));
        }

        UndeployResult undeploy(String name) throws Exception {
            return OnVirtualThread.call(() -> deployments.undeploy(name));
        }

        /** One tick, as the node's timer runs it. */
        void follow() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.follow();
                return null;
            });
        }

        void close() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.close();
                return null;
            });
        }

        /** Each package listed, {@code <name>@<version> <state>}, by name. */
        List<String> listed() {
            return deployments.packages().stream().map(p -> p.name() + "@" + p.version() + " " + p.state()).toList();
        }

        /** The doors of {@code flow} that started and were not stopped since. */
        List<Doors.Door> live(String flow) {
            return doors.doors().stream().filter(d -> d.flow().equals(flow) && d.started() && !d.stopped()).toList();
        }

        Ack send(String flow) throws Exception {
            List<Doors.Door> running = live(flow);
            assertEquals(1, running.size(), id + ": one live door of " + flow + ": " + doors.events());
            return within(running.getFirst().deliver(rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"))), "the answer of " + flow + " on " + id);
        }
    }

    /** {@code name} at {@code version} with a sync flow per id, each from its own in endpoint {@code <id>-in} to {@code partner}. */
    static PackageDraft pkg(String name, String version, String... flows) {
        PackageDraft p = PackageDraft.of(name, version).endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : flows) {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 1024, PackageDraft.toPartner());
        }
        return p;
    }

    /** The pool's records through the Postgres store, whose reads fail while {@code failing} is set. */
    static final class Unreadable implements DeploymentStore {
        final DeploymentStore records = new PgDeploymentStore(pool, () -> PgConnection.connect(vertx, connect).map(c -> c));
        volatile boolean failing;

        @Override
        public Future<Snapshot> read() {
            return failing ? Future.failedFuture(new IllegalStateException("the pool's database does not answer")) : records.read();
        }

        @Override
        public Future<Optional<PoolLock>> lock() {
            return records.lock();
        }

        @Override
        public Future<Void> audit(String actor, String repository, JsonObject detail) {
            return records.audit(actor, repository, detail);
        }

        @Override
        public Future<Void> endMarks() {
            return records.endMarks();
        }
    }

    /** The pool's generation, read with plain SQL. */
    static long generation() {
        return pool.query("select generation from deploy_pool").execute().await().iterator().next().getLong(0);
    }

    @Test
    void aSecondDeployIsRefusedOnAnyNodeWhileOneRuns() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        String shop = repos.commit(pkg("shop", "1.0.0", "a"));
        String billing = repos.commit(pkg("billing", "1.0.0", "b"));
        Doors.Gate held = a.doors.holdStartOf("shop/a");
        OnVirtualThread.Call<DeployResult> first = a.deploying("shop", shop);
        reachedWhile(first, held.reached(), "shop's consumer starts on node a, the deploy holding the pool's lock");

        DeployRefusedException second = joinRefused(b.deploying("billing", billing), "a deploy on node b while node a's runs");
        assertEquals(Kind.CONFLICT, second.kind(), second.getMessage());
        assertTrue(second.error().contains("another deploy or undeploy of the pool"), second.error());
        DeployRefusedException undeploy = joinRefused(OnVirtualThread.start(() -> b.deployments.undeploy("shop")), "an undeploy on node b meanwhile");
        assertEquals(Kind.CONFLICT, undeploy.kind(), "an undeploy waits for no deploy either: " + undeploy.getMessage());

        held.release();
        OnVirtualThread.join(first);
        long before = generation();
        b.deploy("billing", billing);
        assertEquals(before + 1, generation(), "once node a's deploy is done, node b's takes the lock and raises the generation");
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed(), "node b installed shop first, then deployed billing");

        Doors.Door shopOnA = a.live("shop/a").getFirst();
        a.follow();
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), a.listed(), "node a installs billing at its next tick");
        assertEquals(List.of(shopOnA), a.live("shop/a"), "and leaves shop, as its own deploy recorded it, running as it runs");
    }

    @Test
    void aNodeBehindTheGenerationInstallsWhatTheRecordsNameBeforeItPlans() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        assertEquals(List.of(), b.listed(), "fixture: node b has not ticked since");

        b.deploy("billing", repos.commit(pkg("billing", "1.0.0", "b")));

        List<String> starts = b.doors.events().stream().filter(e -> e.startsWith("start ")).toList();
        assertEquals(List.of("start shop/a", "start billing/b"), starts, "shop runs on node b before billing's deploy built anything");
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed());
        assertInstanceOf(Ack.Reply.class, b.send("shop/a"), "shop serves on node b");
    }

    @Test
    void theOtherNodeInstallsAndUndeploysAtItsNextTick() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();

        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        b.follow();
        assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed(), "node b installs the record at its next tick");
        assertInstanceOf(Ack.Reply.class, b.send("shop/a"), "shop serves on node b");
        Doors.Door first = b.live("shop/a").getFirst();
        b.follow();
        assertEquals(List.of(first), b.live("shop/a"), "a tick with nothing new changes nothing: the same door runs");

        a.deploy("shop", repos.commit(pkg("shop", "2.0.0", "a", "c")));
        b.follow();
        assertEquals(List.of("shop@2.0.0 RUNNING"), b.listed(), "node b installs the record whose content differs");
        assertEquals(1, b.live("shop/c").size(), "the added flow runs on node b: " + b.doors.events());

        long before = generation();
        a.undeploy("shop");
        assertEquals(before + 1, generation(), "the undeploy raised the generation");
        b.follow();
        assertEquals(List.of(), b.listed(), "node b undeploys the package that has no record");
        assertEquals(List.of(), b.live("shop/a"), "nothing of it runs on node b");
        assertEquals(List.of(), b.live("shop/c"));
        assertEquals(Health.up(), b.deployments.health());
    }

    @Test
    void aFailedPackageIsInstalledAtALaterTickOnceItsRepositoryAnswers() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        Path aside = tmp.resolve("aside");
        Files.move(repos.dir("shop"), aside);   // shop's repository does not answer node b's fetch

        b.follow();
        assertEquals(List.of("shop@1.0.0 FAILED"), b.listed(), "node b cannot read shop's commit");
        assertEquals(Health.Status.DEGRADED, b.deployments.health().status(), b.deployments.health().detail());
        b.follow();
        assertEquals(List.of("shop@1.0.0 FAILED"), b.listed(), "every tick tries again; the repository still does not answer");

        Files.move(aside, repos.dir("shop"));
        b.follow();
        assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed(), "the tick after the repository answers installs it");
        assertEquals(Health.up(), b.deployments.health());
        assertInstanceOf(Ack.Reply.class, b.send("shop/a"));
    }

    @Test
    void theNodesTimerTicksEveryFiveSeconds() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        b.deployments.startFollowing();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        rt.clock().advance(Duration.ofSeconds(5).minusMillis(1));
        assertEquals(Duration.ofMillis(1), rt.timer().nextDelay(), "no tick before its time: the first one is due 1 ms later");
        assertEquals(List.of(), b.listed());

        rt.clock().advance(Duration.ofMillis(1));
        until(() -> b.listed().equals(List.of("shop@1.0.0 RUNNING")), "node b's tick installs shop");

        a.undeploy("shop");
        until(() -> !b.deployments.packages().isEmpty() && b.live("shop/a").size() == 1, "fixture: shop still runs on node b");
        until(() -> rt.timer().pending() > 0 && rt.timer().nextDelay().equals(Duration.ofSeconds(5)), "the next tick is due 5 s after the first ended");
        rt.clock().advance(Duration.ofSeconds(5));
        until(() -> b.listed().isEmpty(), "the next tick, scheduled once the first ended, undeploys it");
    }

    @Test
    void aDeployWaitsForTheTickThatHoldsTheNodesLock() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        Doors.Gate held = b.doors.holdStartOf("shop/a");
        OnVirtualThread.Call<Void> tick = OnVirtualThread.start(() -> {
            b.deployments.follow();
            return null;
        });
        reachedWhile(tick, held.reached(), "node b's tick installs shop");

        OnVirtualThread.Call<DeployResult> deploy = b.deploying("billing", repos.commit(pkg("billing", "1.0.0", "b")));
        assertFalse(deploy.outcome().isDone(), "the deploy waits for the tick, it is not refused");
        held.release();
        OnVirtualThread.join(tick);
        OnVirtualThread.join(deploy);
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed());
    }

    /**
     * A node that cannot install a newer record of a package it runs runs the old version on and is DEGRADED, BEHIND; a deploy on it
     * is refused, BUSY, while it is behind, and an undeploy is not; the tick after the repository answers installs the record.
     */
    @Test
    void aNodeThatCannotInstallANewerRecordRunsTheOldVersionOnAndRefusesDeploysUntilItCatchesUp() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        a.deploy("billing", repos.commit(pkg("billing", "1.0.0", "b")));
        b.follow();
        Doors.Door oldShop = b.live("shop/a").getFirst();
        a.deploy("shop", repos.commit(pkg("shop", "2.0.0", "a", "c")));
        Path aside = tmp.resolve("aside");
        Files.move(repos.dir("shop"), aside);   // shop's repository does not answer node b's fetch of 2.0.0

        b.follow();
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed(), "node b runs shop 1.0.0 on");
        assertEquals(List.of(oldShop), b.live("shop/a"), "its old door serves");
        assertEquals(List.of(), b.live("shop/c"));
        Health behind = b.deployments.health();
        assertEquals(Health.Status.DEGRADED, behind.status(), behind.detail());
        assertTrue(behind.detail().contains("BEHIND: package shop runs version 1.0.0: "), behind.detail());

        DeployRefusedException busy = joinRefused(b.deploying("orders", repos.commit(pkg("orders", "1.0.0", "o"))), "a deploy on node b while shop is behind");
        assertEquals(Kind.BUSY, busy.kind(), busy.getMessage());
        assertEquals(List.of(), b.live("orders/o"), "nothing of it runs");
        long before = generation();
        assertEquals(new UndeployResult("billing", "1.0.0", List.of("billing/b")), b.undeploy("billing"), "an undeploy is not refused for it");
        assertEquals(before + 1, generation());
        assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed());

        Files.move(aside, repos.dir("shop"));
        b.follow();
        assertEquals(List.of("shop@2.0.0 RUNNING"), b.listed(), "the tick after the repository answers installs the record");
        assertEquals(1, b.live("shop/c").size(), b.doors.events().toString());
        assertEquals(Health.up(), b.deployments.health());
    }

    @Test
    void healthIsDegradedWhileThePoolsRecordsCannotBeReadAndUpOnceATickReadsThem() throws Exception {
        Unreadable records = new Unreadable();
        Peer b = new Peer("node-b", records).restored();
        records.failing = true;
        b.follow();
        Health unread = b.deployments.health();
        assertEquals(Health.Status.DEGRADED, unread.status(), unread.detail());
        assertTrue(unread.detail().contains("BEHIND: the pool's records could not be read: "), unread.detail());

        records.failing = false;
        b.follow();
        assertEquals(Health.up(), b.deployments.health());
    }

    @Test
    void healthIsDegradedWhileATickFailsAndUpOnceOneBringsTheNodeToTheRecords() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        Path incoming = b.deployments.incomingDir();
        Files.delete(incoming);
        Files.writeString(incoming, "not a folder");   // node b cannot write its data directory

        assertThrows(java.io.IOException.class, b::follow, "the tick fails");
        Health failed = b.deployments.health();
        assertEquals(Health.Status.DEGRADED, failed.status(), failed.detail());
        assertTrue(failed.detail().contains("BEHIND: the last tick could not bring this node to the pool's records: "), failed.detail());

        Files.delete(incoming);
        b.follow();
        assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed());
        assertEquals(Health.up(), b.deployments.health());
    }

    @Test
    void aDeployRecordsTheQualifiedIdsOfItsAtLeastOnceFlows() throws Exception {
        Peer a = new Peer("node-a").restored();
        PackageDraft shop = pkg("shop", "1.0.0", "a").endpoint("keep-in", "in", new JsonObject().put("path", "/keep"))
                .flow("keep", "1.0.0", "at-least-once", "keep-in", 1024, PackageDraft.toPartner());
        a.deploy("shop", repos.commit(shop));

        List<String> recorded = List.of(pool.query("select at_least_once from deployments where package = 'shop'").execute().await().iterator().next()
                .getArrayOfStrings(0));
        assertEquals(List.of("shop/keep"), recorded, "the at-least-once flow, not the sync one");
    }
}
