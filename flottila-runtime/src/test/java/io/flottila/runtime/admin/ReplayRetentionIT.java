package io.flottila.runtime.admin;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.IdGenerator;
import io.flottila.api.Runtime;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.dlq.InMemoryDlq;
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
import io.flottila.runtime.store.Retention;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.FakeTimer;
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
import java.time.temporal.ChronoUnit;
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
 * P2-07b1 (D-096, F-20) end to end over Postgres: a replay's input row names the original's body by reference (no copy, D-064),
 * so when the original message ages out whole, the retention job takes its rows and its body while the replay's rows stay. A
 * replay from the replay's input step is then 409 {@code {error}} "removed by retention", before anything ran, and a GET of that
 * step's body 404 "(removed by retention)" (D-057) — through the real {@link TieredBlobStore} over the node's journal, the
 * {@link PgMessageStore}, the {@link Retention} job (one run) and the {@link AdminServer} over a real {@link Deployments}.
 *
 * <p>CORE-4c2 changed the fixture lines, for one reason: X's durable body is a journal record named by its {@code body_refs}
 * row, which retention removes with X's rows (phase 2), never a {@code bodies} row (D-105). The refusal and the 404 stay as
 * D-096 has them, on the store whose index named X's body when it was saved: the deleted ref must reach that store's reads.
 */
class ReplayRetentionIT {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final Duration BOUND = Duration.ofSeconds(20);
    static final long MIB = 1L << 20;

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
        rowsOf(id, 5);
        return e;
    }

    /** Waits until {@code n} rows of {@code id} are written (the store's writer batches). */
    List<Snapshot> rowsOf(String id, int n) {
        until(() -> messages.timeline(id).await().size() == n, "fixture: " + n + " rows of " + id);
        return messages.timeline(id).await();
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

    /**
     * The original X is replayed from step 2 as R: R's input row (step 1, "replay") names X's ingress body. X ages out — its rows
     * and its body 30 days old against a 7-day retention — R does not. One retention run takes X whole and X's body (no row of X
     * names it any more; R's reference does not keep it, F-20). Then a replay of R from step 2 is 409 "removed by retention",
     * nothing ran, and GET of R's step 1 body is 404 "(removed by retention)". Witness: before the run the same replay was 200 and
     * the body read 200; after it, a message that did not age (K) replays.
     */
    @Test
    void aReplayFromAStepRetentionRemovedIs409AndItsBodyRead404() throws Exception {
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), AdminServerReplayTest.shop().zipTo(tmp.resolve("archives"))));
        Envelope x = recorded();
        Envelope k = recorded();
        BlobKey input = BlobKey.body(x.id(), 0);
        assertEquals(1, count("select count(*) from body_refs where message_id = $1 and step = 0 and part = 'body'", x.id()), "fixture: X's ingress body is durable: its ref");
        assertEquals(0, count("select count(*) from bodies where message_id = $1", x.id()), "fixture: and no bodies row");

        Answer replayed = send(HttpMethod.POST, "/admin/messages/" + x.id() + "/replay?from=2");
        assertEquals(200, replayed.status(), replayed.body().toString());
        String r = replayed.json().getString("id");
        List<Snapshot> rRows = rowsOf(r, 4);   // its input (step 1), then b, h, c
        Snapshot rInput = messages.timeline(r).await().stream().filter(s -> s.step() == 1).findFirst().orElseThrow();
        assertEquals("replay", rInput.stepName(), "fixture: R's step 1 row is its input: " + rRows);
        assertEquals(Optional.of(input), rInput.bodyKey(), "fixture: R's input row names X's body by reference, no copy");
        assertEquals(200, send(HttpMethod.POST, "/admin/messages/" + r + "/replay?from=2").status(), "witness: R replays from step 2 while the body is stored");
        assertEquals(200, send(HttpMethod.GET, "/admin/messages/" + r + "/steps/1/body").status(), "witness: and its step 1 body reads");

        // X ages out; R and K stay young
        pool.preparedQuery("update messages set created_at = now() - interval '30 days' where id = $1").execute(Tuple.of(x.id())).await();
        pool.preparedQuery("update body_refs set created_at = now() - interval '30 days' where message_id = $1").execute(Tuple.of(x.id())).await();
        FakeClock clock = FakeClock.at(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        Retention job = new Retention(pool, clock, new FakeTimer(clock), new Retention.Config(Duration.ofDays(7), Duration.ofHours(1), 100));
        Retention.Run run;
        try {
            run = job.runOnce().await(BOUND.toSeconds(), TimeUnit.SECONDS);
        } finally {
            job.close().await(BOUND.toSeconds(), TimeUnit.SECONDS);
        }
        assertEquals("DONE", run.outcome().name(), String.valueOf(run));
        assertEquals(5, run.messages(), "fixture: X's five rows went: " + run);
        assertEquals(0, run.bodies(), "fixture: no bodies row to remove: " + run);
        assertEquals(0, count("select count(*) from body_refs where message_id = $1", x.id()), "fixture: X's refs went with its rows");
        assertTrue(count("select count(*) from messages where id = $1", r) > 0, "fixture: R's rows stay");

        int ran = probe.total();
        int mintedBefore = minted.size();
        Answer refused = send(HttpMethod.POST, "/admin/messages/" + r + "/replay?from=2");

        assertEquals(409, refused.status(), refused.body().toString());
        assertEquals(new JsonObject().put("error", "the body of step 1 of message " + r + " (" + input + ") was removed by retention"), refused.json());
        assertEquals(ran, probe.total(), "no step ran");
        List<String> mintedNow = List.copyOf(minted);   // a snapshot: the id generator appends from other threads
        for (String id : mintedNow.subList(mintedBefore, mintedNow.size())) assertEquals(List.of(), messages.timeline(id).await(), "no row under " + id);

        Answer body = send(HttpMethod.GET, "/admin/messages/" + r + "/steps/1/body");
        assertEquals(404, body.status(), body.body().toString());
        assertEquals(new JsonObject().put("error", "the bytes of step 1 of message " + r + " are no longer stored (removed by retention)"), body.json());

        assertEquals(200, send(HttpMethod.POST, "/admin/messages/" + k.id() + "/replay?from=2").status(), "witness: a message that did not age replays");
    }
}
