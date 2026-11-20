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

import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static dev.rail8.runtime.deploy.DeploymentsTest.joinRefused;
import static dev.rail8.runtime.deploy.DeploymentsTest.until;
import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

        /** A node in dev whose {@code RAIL8_GIT_BASE} is {@code base}. */
        Peer(String id, String base) {
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
            PgDeploymentStore records = new PgDeploymentStore(pool, () -> PgConnection.connect(vertx, connect).map(c -> c));
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
        rt.clock().advance(Deployments.FOLLOW_EVERY.minusMillis(1));
        assertEquals(List.of(), b.listed(), "no tick before its time");

        rt.clock().advance(Duration.ofMillis(1));
        until(() -> b.listed().equals(List.of("shop@1.0.0 RUNNING")), "node b's tick installs shop");

        a.undeploy("shop");
        until(() -> !b.deployments.packages().isEmpty() && b.live("shop/a").size() == 1, "fixture: shop still runs on node b");
        rt.clock().advance(Deployments.FOLLOW_EVERY);
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
}
