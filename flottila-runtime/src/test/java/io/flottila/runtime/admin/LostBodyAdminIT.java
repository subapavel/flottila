package io.flottila.runtime.admin;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.IdGenerator;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.api.Runtime;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.journal.TestJournals;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-4c3 packet item 4 end to end (CORE-3 "Review r2 additions", D-105's exception to D-062): a message whose body an accepted
 * {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} gave up — its {@code body_refs} row marked {@code lost_at}, as the start marks it — is
 * refused for a replay and for a DLQ retry with 409 {@code {error}} saying "body lost by ACCEPT_LOSS" (D-096's 409 for a body
 * that is not there; never "removed by retention", nothing runs, the DLQ row stays), and the admin GET of that body answers
 * <b>410 Gone</b> with that reason: unlike a body retention removed (404, D-057) the message's rows say a body was stored, and
 * unlike a 409 no state of the server can be changed to make it readable again — it is gone for good. Through the real
 * {@link TieredBlobStore} over a node's journal, the {@link PgMessageStore}, a real {@link Deployments} and the
 * {@link AdminServer} (ReplayRetentionIT's wiring). Witness: before the mark the same replay, retry and GET succeed, and a sibling
 * message that was not given up still does after it.
 *
 * <p>The API assumed (CORE-4c3, test-author): core {@code ReplayRefusedException.Reason.BODY_LOST} answered 409 by the admin
 * API; the GET of a lost body 410 (test-author's pick, packet item 4).
 */
class LostBodyAdminIT {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final Duration BOUND = Duration.ofSeconds(20);
    static final long MIB = 1L << 20;
    static final String LOST = "body lost by ACCEPT_LOSS";

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    final FakeIds replayIds = new FakeIds("replay");
    final List<String> minted = new CopyOnWriteArrayList<>();
    final AdminServerReplayTest.Probe probe = new AdminServerReplayTest.Probe();
    TestJournals.Opened journal;
    TieredBlobStore blobs;
    PgMessageStore messages;
    Deployments deployments;
    AdminServer server;
    int port;

    @BeforeAll
    static void up() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void down() {
        client.close().await();
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() throws Exception {
        journal = TestJournals.open(pool, Files.createDirectories(tmp.resolve("journal")), "node-1");
        blobs = new TieredBlobStore(new TieredBlobStore.Config(MIB, 64 * 1024, 16 * MIB, MIB), Hasher.sha256(), journal.journal(), journal.tenant(), pool);
        messages = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), blobs, rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), probe.factories(), List.of(),
                messages, dlq, queue, () -> 0L);
        IdGenerator ids = () -> {
            String id = replayIds.next();
            minted.add(id);
            return id;
        };
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, blobs,
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments), queue, ids);
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        probe.holds.forEach(AdminServerReplayTest.Hold::release);
        doors.releaseAll();
        server.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
        messages.close();
        journal.close();
    }

    record Answer(int status, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    Answer send(HttpMethod method, String path) {
        return client.request(new RequestOptions().setMethod(method).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    req.putHeader("Authorization", AUTH);
                    req.response().onSuccess(HttpClientResponse::pause);
                    return req.send();
                })
                .compose(res -> {
                    var body = res.body();
                    res.resume();
                    return body.map(b -> new Answer(res.statusCode(), b));
                })
                .await();
    }

    /** A sync orders message whose ingress body is written to the tiered store, delivered, its five rows written. */
    Envelope recorded() throws Exception {
        String id = rt.ids().next();
        Envelope e = Envelope.ingress(id, "shop/orders", Headers.empty(), blobs.write(BlobKey.body(id, 0), Buffer.buffer("<order/>"), 1024).await());
        assertInstanceOf(Ack.Reply.class, doors.door("shop/orders").deliver(e).await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "fixture: orders answers");
        until(() -> messages.timeline(id).await().size() == 5, "fixture: the five rows of " + id);
        return e;
    }

    /** {@code e} parked at step 1 (a) of shop/orders on its ingress body, as a failed worker run would have left it. */
    void parked(Envelope e) {
        dlq.park(new Parked(e.id(), "shop/orders", 1, "a", "partner down (test)", Instant.parse("2026-11-04T10:00:00Z"), Headers.empty(), Props.empty(),
                Keys.empty(), e.body().hash(), e.body().size(), BlobKey.body(e.id(), 0), Map.of())).await();
    }

    long count(String sql, String param) {
        return pool.preparedQuery(sql).execute(Tuple.of(param)).await().iterator().next().getLong(0);
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within " + BOUND + ": " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    static void saysLost(Answer a, int status, String id, String what) {
        assertEquals(status, a.status(), what + ": " + a.body());
        String error = a.json().getString("error");
        assertTrue(error != null && error.contains(LOST) && error.contains(id), what + ": the error names the message and says why: " + a.body());
        assertTrue(!error.contains("removed by retention"), what + ": never retention's reason: " + error);
    }

    @Test
    void aLostBodyIsRefused409ForReplayAndRetryAndItsGetIs410() throws Exception {
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), AdminServerReplayTest.shop().zipTo(tmp.resolve("archives"))));
        Envelope x = recorded();
        Envelope k = recorded();
        Snapshot ingress = messages.timeline(x.id()).await().stream().filter(s -> s.step() == 0).findFirst().orElseThrow();
        assertEquals(Optional.of(BlobKey.body(x.id(), 0)), ingress.bodyKey(), "fixture: X's step 0 row names its ingress body");
        assertEquals(1, count("select count(*) from body_refs where message_id = $1 and step = 0 and part = 'body'", x.id()), "fixture: a kept ref");
        parked(x);
        parked(k);
        assertEquals(200, send(HttpMethod.GET, "/admin/messages/" + x.id() + "/steps/0/body").status(), "witness: X's body reads before the loss");
        assertEquals(200, send(HttpMethod.POST, "/admin/messages/" + x.id() + "/replay?from=1").status(), "witness: X replays before the loss");

        pool.preparedQuery("update body_refs set lost_at = now() where message_id = $1").execute(Tuple.of(x.id())).await();   // the accepted loss
        int ran = probe.total();
        int mintedBefore = minted.size();

        saysLost(send(HttpMethod.POST, "/admin/messages/" + x.id() + "/replay?from=1"), 409, x.id(), "a replay of a lost body");
        saysLost(send(HttpMethod.POST, "/admin/dlq/" + x.id() + "/retry"), 409, x.id(), "a DLQ retry of a lost body");
        assertEquals(ran, probe.total(), "no step ran");
        for (String id : minted.subList(mintedBefore, minted.size())) assertEquals(List.of(), messages.timeline(id).await(), "no row under " + id);
        assertTrue(dlq.get(x.id()).await().isPresent(), "the refused retry keeps its DLQ row, for the operator");

        saysLost(send(HttpMethod.GET, "/admin/messages/" + x.id() + "/steps/0/body"), 410, x.id(), "the GET of a lost body: gone for good");

        assertEquals(200, send(HttpMethod.GET, "/admin/messages/" + k.id() + "/steps/0/body").status(), "witness: K, not given up, reads");
        assertEquals(200, send(HttpMethod.POST, "/admin/messages/" + k.id() + "/replay?from=1").status(), "witness: and replays");
        assertEquals(200, send(HttpMethod.POST, "/admin/dlq/" + k.id() + "/retry").status(), "witness: and its DLQ row retries");
    }
}
