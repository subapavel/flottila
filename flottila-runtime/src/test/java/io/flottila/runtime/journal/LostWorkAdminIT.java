package io.flottila.runtime.journal;

import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.WorkSignals;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-141 (D-126 L2-L4) over the admin API and a real Postgres: a keyed message whose row an accepted ACCEPT_LOSS gave up (marked by
 * {@code update work set lost_at}, what the start writes) heads its key, so its successor waits. {@code GET /admin/work/lost} lists
 * the head with {@code blocks: 1}; {@code /admin/work/held} rows carry {@code blocks} too. {@code POST /admin/work/{seq}/discard}
 * answers 200 {@code {seq, auditId}}, writes one {@code audit} row whose actor is the {@code X-Flottila-Actor} header (absent:
 * {@code unknown}; over 128 characters: 400, nothing discarded) and whose detail names flow, message, key, lost_at and the remote
 * address; it wakes the flow here — the started worker, whose start claim found nothing, then runs the successor to completion
 * (the fake timer never fires: only the wake can start that claim) — and announces the successor's body node like a release. A
 * second discard is 404, a non-number 400; both routes need the token.
 *
 * <p>The API assumed: see {@code PgWorkQueueLostIT} (WorkQueue {@code lost}, {@code discard}, {@code Held.blocks}); the JSON of a
 * lost row {@code {seq, messageId, flow, partitionKey, lostAt, blocks}}, of a discard {@code {seq, auditId}}.
 */
@Timeout(120)
class LostWorkAdminIT {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final Duration BOUND = Duration.ofSeconds(20);
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Instant LOST_AT = Instant.parse("2026-11-04T10:00:00Z");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    static HttpClientAgent client;

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final Workers workers = new Workers();
    final List<String> announced = new CopyOnWriteArrayList<>();
    final WorkSignals signals = (target, flow) -> announced.add(target + " " + flow);
    final AtomicInteger claimsDone = new AtomicInteger();
    PgWorkQueue rows;
    FlowWorker worker;
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
        pool.query("truncate work, audit").execute().await();
        rows = new PgWorkQueue(pool);
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), store, new InMemoryDlq(), rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.empty(), rows, workers, signals);
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        if (worker != null) worker.stop();
        server.stop().await(10, TimeUnit.SECONDS);
    }

    /** The worker of flow {@code orders} over {@code rows}, each claim it made counted once answered; started, its start claim answered. */
    void startWorker() {
        WorkQueue counted = (WorkQueue) Proxy.newProxyInstance(WorkQueue.class.getClassLoader(), new Class<?>[] {WorkQueue.class}, (p, m, args) -> {
            Object r;
            try {
                r = m.invoke(rows, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (m.getName().equals("claim")) ((Future<?>) r).onComplete(x -> claimsDone.incrementAndGet());
            return r;
        });
        worker = new FlowBuilder(rt.runtime(), List.of(PgWorkSignalsIT.PASS), List.of(), Map.of()).recording(store, Meter.system())
                .queue(counted).dlq(new InMemoryDlq()).workers(workers).worker(PgWorkSignalsIT.flow("orders"), Path.of(""), "node-a",
                        new WorkerPolicy(LEASE, 5, Duration.ofMinutes(10)));
        worker.start();
        until(() -> claimsDone.get() == 1, "the worker's start claim answered");
    }

    /** A message of flow {@code orders} recorded (its ingress) and queued under {@code key}; answers its seq. */
    long queued(String key) {
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        rows.enqueue(in.id(), "orders", 1, key).await();
        return pool.preparedQuery("select seq from work where id = $1").execute(Tuple.of(in.id())).await().iterator().next().getLong(0);
    }

    /** A row inserted as is (no recorded ingress) of {@code flow}, key {@code key}, its body on {@code bodyNode}; held or not. */
    static long raw(String flow, String id, String key, String bodyNode, boolean held) {
        return pool.preparedQuery("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length, held_at)"
                + " values ($1, $2, 1, $3, $4, 1, 0, 10, case when $5 then now() end) returning seq")
                .execute(Tuple.of(id, flow, key, bodyNode, held)).await().iterator().next().getLong(0);
    }

    String idOf(long seq) {
        return pool.preparedQuery("select id from work where seq = $1").execute(Tuple.of(seq)).await().iterator().next().getString(0);
    }

    static void lose(long seq) {
        pool.preparedQuery("update work set lost_at = $2 where seq = $1").execute(Tuple.of(seq, LOST_AT.atOffset(ZoneOffset.UTC))).await();
    }

    static boolean exists(long seq) {
        return count("select count(*) from work where seq = " + seq) == 1;
    }

    static long count(String sql) {
        return pool.query(sql).execute().await().iterator().next().getLong(0);
    }

    record Answer(int status, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    Answer send(HttpMethod method, String path, String authorization, String actor) {
        return client.request(new RequestOptions().setMethod(method).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    if (actor != null) req.putHeader("X-Flottila-Actor", actor);
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

    Answer discard(long seq, String actor) {
        return send(HttpMethod.POST, "/admin/work/" + seq + "/discard", AUTH, actor);
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within " + BOUND + ": " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    @Test
    void aLostHeadIsListedWithWhatItBlocksAndItsDiscardIsAuditedAndLetsTheSuccessorRun() {
        long head = queued("K");
        long otherKey = raw("orders", "m-L", "L", "node-c", true);     // same flow, other key, held: it stays in work, unclaimed
        long otherFlow = raw("returns", "m-R", "K", "node-d", false);  // other flow, same key: this worker never claims it
        long next = queued("K");
        assertTrue(head < otherKey && otherKey < otherFlow && otherFlow < next, "fixture: seq order head < m-L < m-R < successor");
        String headId = idOf(head);
        String nextId = idOf(next);
        lose(head);
        pool.preparedQuery("update work set body_node = 'node-b' where seq = $1").execute(Tuple.of(next)).await();
        startWorker();
        assertEquals(0, count("select attempts from work where seq = " + next), "fixture: the start claim did not take the successor");

        Answer lost = send(HttpMethod.GET, "/admin/work/lost", AUTH, null);
        assertEquals(200, lost.status(), lost.body().toString());
        JsonArray items = lost.json().getJsonArray("items");
        assertEquals(1, items.size(), "only the lost row: " + lost.body());
        JsonObject row = items.getJsonObject(0);
        assertEquals(List.of(head, headId, "orders", "K", LOST_AT.toString(), 1L),
                List.of(row.getLong("seq"), row.getString("messageId"), row.getString("flow"), row.getString("partitionKey"),
                        Instant.parse(row.getString("lostAt")).toString(), row.getLong("blocks")), "the head and the one row it blocks: " + row);

        Answer d = discard(head, "  ops-anna  ");
        assertEquals(200, d.status(), d.body().toString());
        assertEquals(1, count("select count(*) from audit"), "one audit row");
        Row a = pool.query("select id, actor, action, subject, detail->>'flow', detail->>'messageId', detail->>'partitionKey',"
                + " (detail->>'lostAt')::timestamptz, detail->>'remote' from audit").execute().await().iterator().next();
        assertEquals(Long.valueOf(head), d.json().getLong("seq"), "the answer names the seq");
        assertEquals(a.getLong(0), d.json().getLong("auditId"), "the answer names the audit row");
        assertEquals(List.of("ops-anna", "work.discard", "work/" + head, "orders", headId, "K", LOST_AT, "127.0.0.1"),
                List.of(a.getString(1), a.getString(2), a.getString(3), a.getString(4), a.getString(5), a.getString(6),
                        a.getOffsetDateTime(7).toInstant(), a.getString(8)), "the actor from the header, trimmed; the detail with the remote address");
        assertFalse(exists(head), "the lost row is gone");

        until(() -> !exists(next) && store.timeline(nextId).await().size() > 1, "the successor run to completion after the discard woke its flow");
        List<String> steps = store.timeline(nextId).await().stream().map(Snapshot::stepName).toList();
        assertEquals(Recorder.INGRESS, steps.getFirst(), "its run recorded after its ingress: " + steps);
        assertTrue(claimsDone.get() >= 2, "a claim after the start claim: the wake");
        assertEquals(List.of("node-b orders"), announced,
                "the key's next row of this flow announced to its body node, once (not node-c, another key; not node-d, another flow)");
        assertTrue(exists(otherKey) && exists(otherFlow), "fixture: the other key's and the other flow's rows are still in work");

        Answer again = discard(head, "ops-anna");
        assertEquals(404, again.status(), again.body().toString());
        assertTrue(again.json().getString("error") != null, "a JSON error: " + again.body());
        assertEquals(1, count("select count(*) from audit"), "the 404 audits nothing");
        assertEquals(List.of(), send(HttpMethod.GET, "/admin/work/lost", AUTH, null).json().getJsonArray("items").getList(), "nothing lost any more");
    }

    @Test
    void theActorIsTheHeaderUpTo128CharactersAndUnknownWithoutIt() {
        long over = queued(null);
        long exact = queued(null);
        long absent = queued(null);
        for (long seq : new long[] {over, exact, absent}) lose(seq);

        Answer refused = discard(over, "a".repeat(129));
        assertEquals(400, refused.status(), refused.body().toString());
        assertTrue(exists(over), "a refused actor discards nothing");
        assertEquals(0, count("select count(*) from audit"), "and audits nothing");

        assertEquals(200, discard(exact, "b".repeat(128)).status(), "128 characters are accepted");
        assertEquals(200, discard(absent, null).status());
        List<String> actors = new ArrayList<>();
        for (Row r : pool.query("select actor from audit order by id").execute().await()) actors.add(r.getString(0));
        assertEquals(List.of("b".repeat(128), "unknown"), actors, "the header as sent; absent: unknown");
        assertTrue(exists(over), "the refused row is still lost, for a discard with a valid actor");
    }

    @Test
    void heldRowsShowWhatTheyBlock() {
        long head = queued("K");
        queued("K");
        queued("K");
        long unkeyed = queued(null);
        pool.query("update work set held_at = now() where seq in (" + head + ", " + unkeyed + ")").execute().await();
        Answer held = send(HttpMethod.GET, "/admin/work/held", AUTH, null);
        assertEquals(200, held.status(), held.body().toString());
        List<String> got = new ArrayList<>();
        for (Object o : held.json().getJsonArray("items")) {
            JsonObject j = (JsonObject) o;
            got.add(j.getLong("seq") + " " + j.getLong("blocks"));
        }
        assertEquals(List.of(head + " 2", unkeyed + " 0"), got, "a held head blocks the two rows of its key behind it; an unkeyed row nothing");
    }

    @Test
    void aSeqThatIsNotANumberIs400AndAPlainRowIs404() {
        long plain = queued("K");
        Answer bad = send(HttpMethod.POST, "/admin/work/abc/discard", AUTH, "ops-anna");
        assertEquals(400, bad.status(), bad.body().toString());
        Answer notLost = discard(plain, "ops-anna");
        assertEquals(404, notLost.status(), notLost.body().toString());
        assertTrue(exists(plain), "a row not lost is never discarded");
        assertEquals(0, count("select count(*) from audit"), "nothing audited");
    }

    @Test
    void bothRoutesNeedTheToken() {
        long head = queued("K");
        lose(head);
        for (String auth : new String[] {null, "Bearer wrong", TOKEN}) {
            assertEquals(401, send(HttpMethod.GET, "/admin/work/lost", auth, null).status(), "GET lost with Authorization " + auth);
            assertEquals(401, send(HttpMethod.POST, "/admin/work/" + head + "/discard", auth, "ops-anna").status(), "POST discard with Authorization " + auth);
        }
        assertTrue(exists(head), "no discard without the token");
        assertEquals(0, count("select count(*) from audit"), "nothing audited");
        Answer listed = send(HttpMethod.GET, "/admin/work/lost", AUTH, null);
        assertEquals(200, listed.status(), "witness: with the token the route answers: " + listed.body());
        assertEquals(1, listed.json().getJsonArray("items").size(), "witness: and lists the lost row");
        assertEquals(200, discard(head, "ops-anna").status(), "witness: and discards it");
    }
}
