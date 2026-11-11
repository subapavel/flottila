package io.flottila.runtime.node;

import io.flottila.testkit.PostgresImage;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-15c (D-101, D-112, D-113): the node lease end to end, on child JVM nodes ({@code io.flottila.runtime.Main}, as
 * {@link NodeFailStopIT} starts its child) over one Postgres, each case on a database of its own (FIX-5). Every node runs the
 * package {@code ledger}: an at-least-once flow whose one step is {@code to partner}; the partner is an http server in this JVM
 * that records each call's {@code x-message-id} (first-wins: the distinct ids in arrival order, and every raw call), answers
 * or holds it. Node A and node B share the package and its archive directory but each has its own http door (the in endpoint's
 * host is {@code secret://in-host}: A on 127.0.0.2, B on 127.0.0.3, one port) and its own partner path ({@code /a}, {@code /b}
 * through {@code secret://partner-url}), so the partner tells who sent a call. Node leases PT2S renewed every PT0.5S, but in the
 * ledger case (see there). Waits spin on observed state (the partner's, the database's, the child's
 * {@code /proc} state); none sleeps.
 *
 * <p>Two ledger triggers on {@code work}, installed by the test once the first node migrated the database, are its witnesses:
 * {@code ledger_takes} logs every hand-out (attempts raised) with the previous holder and the database's {@code now()} of the
 * claiming statement, {@code ledger_done} every completed row's {@code doubts} — a completed row is deleted, so the doubts it
 * carried are read there.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class NodeTakeoverLedgerIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final Duration BOUND = Duration.ofSeconds(120);
    static final String A = "node-a";
    static final String B = "node-b";
    static final String HOST_A = "127.0.0.2";
    static final String HOST_B = "127.0.0.3";
    static final String FINAL = "step_name = 'partner' and outcome = 'ok'";
    static final String TRIGGERS = """
            create table ledger_takes (n bigserial primary key, seq bigint not null, id text not null, prev_holder text, holder text,
                                       doubts int not null, at timestamptz not null);
            create table ledger_done (id text not null, attempts int not null, doubts int not null);
            create function ledger_taken() returns trigger language plpgsql as $$ begin
              if new.attempts > old.attempts then
                insert into ledger_takes(seq, id, prev_holder, holder, doubts, at) values (new.seq, new.id, old.locked_by, new.locked_by, new.doubts, now());
              end if;
              return new;
            end $$;
            create function ledger_completed() returns trigger language plpgsql as $$ begin
              insert into ledger_done(id, attempts, doubts) values (old.id, old.attempts, old.doubts);
              return old;
            end $$;
            create trigger ledger_taken after update on work for each row execute function ledger_taken();
            create trigger ledger_completed after delete on work for each row execute function ledger_completed();
            """;
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;
    static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build();

    @TempDir Path dir;
    final List<Process> children = new ArrayList<>();
    final Partner partner = new Partner();
    HttpServer partnerServer;
    String database;
    PgConnection db;   // the case database, as another session sees it
    int inPort;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void caseDatabaseAndPartner() throws Exception {
        database = "ledger_" + cases.incrementAndGet();
        PgConnection admin = PgConnection.connect(vertx, options(pg.getDatabaseName())).await();
        try {
            admin.query("create database " + database).execute().await();
        } finally {
            admin.close().await();
        }
        db = PgConnection.connect(vertx, options(database)).await();
        partnerServer = vertx.createHttpServer().requestHandler(partner).listen(0, "127.0.0.1").await();
        inPort = freePort();
    }

    @AfterEach
    void killChildren() throws Exception {
        for (Process p : children) p.destroyForcibly().waitFor(60, TimeUnit.SECONDS);   // SIGKILL ends a stopped child too
        if (partnerServer != null) partnerServer.close().await();
        if (db != null) db.close().await();
    }

    static PgConnectOptions options(String database) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    /**
     * The ledger (kill -9). A ACKs 1000 POSTs; the partner answers its first 500 sends and holds every later one, so A's
     * in-flight items are certain at the kill (its in-flight limit, 8, held at the partner); no POST is in flight to A then, so
     * every saved message was ACKed. A is SIGKILLed, the other 1000 go to B, and A restarts on its own id and data directory —
     * its journal (one copy until CORE-5, D-101 (5)): B claims nothing whose body lies in A's journal. A's first incarnation
     * runs a 45 s lease, longer than the work item lease (30 s, WorkerPolicy.DEFAULT): its old {@code lease_until} then
     * outlasts every item lease, so "the restart reruns its old items only after the old lease_until" (D-112) is not masked by
     * the item leases running out later anyway; B and A's restart run PT10S renewed every PT2.5S (nothing asserted needs a short
 * one). Then: every message ACKed and with a final-step row, the partner's distinct
     * ids are the ACKed ids, DLQ and work empty, Σdoubts = the reruns of A's old items, B never halted, raw duplicates no more than A's in-flight at the
     * kill.
     */
    @Test
    void aKilledNodesMessagesAllReachThePartnerOnceItRestartsAndItsPeerTakesTheRest() throws Exception {
        long t0 = System.nanoTime();
        partner.holdAAfter(500);
        Path dataA = Files.createDirectories(dir.resolve("data-a"));
        Child a1 = start(A, HOST_A, dataA, "PT45S", "PT0.5S", "/a", 1);
        deploy(a1, 8);
        db.query(TRIGGERS).execute().await();
        Child b = start(B, HOST_B, Files.createDirectories(dir.resolve("data-b")), "PT10S", "PT2.5S", "/b", 1);
        assertRunning(b);

        List<String> ackedByA = post(HOST_A, 0, 1000);
        assertEquals(1000, new HashSet<>(ackedByA).size(), "fixture: A ACKed 1000 messages under 1000 ids");
        partner.await(() -> partner.held() == 8, "fixture: the partner holds A's in-flight limit of sends (8)");
        long takesAtKill = db.query("select coalesce(max(n), 0) from ledger_takes").execute().await().iterator().next().getLong(0);
        a1.process.destroyForcibly();
        assertTrue(a1.process.waitFor(60, TimeUnit.SECONDS), "A died");
        assertEquals(137, a1.process.exitValue(), "fixture: A was SIGKILLed");
        Row atKill = db.preparedQuery("select (select count(*) from work where locked_by = $1), (select lease_until from nodes where node = $1)")
                .execute(Tuple.of(A)).await().iterator().next();
        long inFlightAtKill = atKill.getLong(0);
        OffsetDateTime oldLeaseUntil = atKill.getOffsetDateTime(1);
        assertTrue(inFlightAtKill >= 1, "fixture: A left items in flight at the kill");
        partner.releaseA();

        ExecutorService toB = Executors.newVirtualThreadPerTaskExecutor();
        Future<List<String>> ackedByB;
        try {
            ackedByB = toB.submit(() -> post(HOST_B, 1000, 1000));   // the rest to B, while A restarts
            Child a2 = start(A, HOST_A, dataA, "PT10S", "PT2.5S", "/a", 2);
            assertRunning(a2);
            List<String> acked = new ArrayList<>(ackedByA);
            acked.addAll(ackedByB.get(BOUND.toSeconds(), TimeUnit.SECONDS));
            Set<String> ackedIds = new TreeSet<>(acked);
            partner.await(() -> partner.distinct().containsAll(ackedIds) && partner.heldRerun(),
                    "every ACKed id reached the partner and every send held at the kill came again");
            until(() -> count("select count(*) from work") == 0, "the work queue drained");

            long finalRows = db.preparedQuery("select count(distinct id) from messages where " + FINAL + " and id = any($1)")
                    .execute(Tuple.tuple().addArrayOfString(ackedIds.toArray(String[]::new))).await().iterator().next().getLong(0);
            long sumDoubts = count("select coalesce(sum(doubts), 0) from ledger_done");
            Row reruns = db.preparedQuery("select count(*), count(*) filter (where at < $3) from ledger_takes where n > $1 and prev_holder = $2 and holder = $2")
                    .execute(Tuple.of(takesAtKill, A, oldLeaseUntil)).await().iterator().next();
            int raw = partner.raw();
            int distinct = partner.distinct().size();
            System.out.printf("P2-15c ledger: acked %d (%d distinct), with a final-step row %d, partner distinct %d, raw %d, raw duplicates %d, "
                            + "A in flight at the kill %d, Σdoubts %d, A's old items rerun %d (before its old lease_until %s: %d), run %d s%n",
                    acked.size(), ackedIds.size(), finalRows, distinct, raw, raw - distinct, inFlightAtKill, sumDoubts, reruns.getLong(0), oldLeaseUntil,
                    reruns.getLong(1), TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0));

            assertEquals(2000, ackedIds.size(), "2000 messages ACKed, each under its own id");
            assertEquals(2000, finalRows, "every ACKed message has its final-step row");
            assertEquals(ackedIds, new TreeSet<>(partner.distinct()), "the partner's distinct ids are the ACKed ids");
            assertEquals(0, count("select count(*) from dlq"), "nothing parked");
            assertEquals(0, count("select count(*) from work"), "no work left");
            assertTrue(reruns.getLong(0) >= 1, "A's restart reran the items its first incarnation left in flight");
            assertEquals(reruns.getLong(0), sumDoubts, "each rerun of what A left in flight is counted in doubt, nothing else is");
            assertTrue(raw - distinct <= inFlightAtKill, "raw duplicates " + (raw - distinct) + " ≤ A's in flight at the kill " + inFlightAtKill);
            assertEquals(0, reruns.getLong(1), "A's restart reran its old items only after its old lease_until " + oldLeaseUntil + " (D-112)");
            assertTrue(b.process.isAlive(), "B never halted: " + b.log());
            assertFalse(b.log().contains("was superseded"), "B was never superseded: " + b.log());
        } finally {
            toB.shutdownNow();
        }
    }

    /**
     * SIGSTOP for longer than half the lease, with held sends. A (in-flight limit 4) has 8 messages: the partner holds its 4
     * sends — every permit — and 4 items wait in the queue. A is stopped until its lease ran out in the database (more than L
     * since its last renew: its fence, L/2, closed); meanwhile this session holds A's {@code nodes} row, so after SIGCONT A's
     * renews wait and its fence stays closed until the release this session then commits (the peer's statement, as in
     * {@link NodeFailStopIT}). After SIGCONT: A resets the 4 held sends while it is alive and unsuperseded, nothing from A
     * reaches the partner, the queued items are not taken; once released A halts with {@link Node#SUPERSEDED}.
     */
    @Test
    void aStoppedNodeSendsNothingPastItsFenceResetsItsHeldSendsAndHaltsWhenSuperseded() throws Exception {
        long t0 = System.nanoTime();
        partner.holdAAfter(0);
        Child a = start(A, HOST_A, Files.createDirectories(dir.resolve("data-a")), "PT2S", "PT0.5S", "/a", 1);
        deploy(a, 4);
        assertEquals(8, new HashSet<>(post(HOST_A, 0, 8)).size(), "fixture: A ACKed 8 messages");
        partner.await(() -> partner.held() == 4, "fixture: the partner holds A's 4 sends");
        until(() -> count("select count(*) from work where attempts = 0") == 4, "fixture: 4 items wait in A's queue");

        signal(a, "STOP");
        until(() -> state(a) == 'T', "fixture: A is stopped");
        PgConnection holder = PgConnection.connect(vertx, options(database)).await();
        try {
            Transaction tx = holder.begin().await();
            holder.preparedQuery("select 1 from nodes where node = $1 for update").execute(Tuple.of(A)).await();
            until(() -> db.preparedQuery("select lease_until < now() from nodes where node = $1").execute(Tuple.of(A)).await().iterator().next().getBoolean(0),
                    "A's lease ran out while it was stopped");
            int callsAtResume = partner.callsOf("a");
            assertEquals(4, callsAtResume, "fixture: A's only calls are the 4 held ones");
            signal(a, "CONT");

            partner.await(() -> partner.closedHeld() == 4, "A's 4 held sends are reset after SIGCONT");
            assertTrue(a.process.isAlive(), "the resets came from A's closed fence, A alive and not superseded: " + a.log());
            until(() -> count("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query like '%update nodes%'") >= 3,
                    "A renewed into its held row again and again: it ran on for more than its lease after SIGCONT");
            assertEquals(callsAtResume, partner.callsOf("a"), "nothing from A reached the partner after its fence");
            assertEquals(4, count("select count(*) from work where attempts = 0"), "A took none of its queued items after its fence");

            int released = holder.preparedQuery("update nodes set lease_until = null where node = $1").execute(Tuple.of(A)).await().rowCount();
            assertEquals(1, released, "fixture: the peer's release");
            tx.commit().await();
        } finally {
            holder.close().await();
        }
        assertTrue(a.process.waitFor(60, TimeUnit.SECONDS), "A halts on its own once superseded: " + a.log());
        assertEquals(Node.SUPERSEDED, a.process.exitValue(), "fail-stop with the superseded status: " + a.log());
        assertTrue(a.log().contains("was superseded"), "its ERROR says why: " + a.log());
        assertEquals(4, partner.callsOf("a"), "nothing from A reached the partner after its fence, to its halt");
        assertEquals(4, count("select count(*) from work where attempts = 0"), "A took none of its queued items, to its halt");
        assertEquals(0, count("select count(*) from dlq"), "a fenced send is never parked");
        System.out.printf("P2-15c stop: A calls %d, held closed %d, work %d, run %d s%n", partner.callsOf("a"), partner.closedHeld(),
                count("select count(*) from work"), TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0));
    }

    // --- the nodes

    record Child(String id, int admin, Process process, Path logFile) {
        String log() throws IOException {
            return Files.exists(logFile) ? Files.readString(logFile) : "";
        }
    }

    /**
     * {@code io.flottila.runtime.Main} as node {@code id}, incarnation {@code n}, on its data directory, its node lease {@code lease}
     * renewed every {@code renew}; returns once it serves {@code /health} 200.
     */
    Child start(String id, String host, Path data, String lease, String renew, String partnerPath, int n) throws Exception {
        int admin = freePort();
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", id);
        env.put("FLOTTILA_ADMIN_PORT", String.valueOf(admin));
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_ARCHIVE_DIR", dir.resolve("archives").toString());   // shared: B and A's restart restore the package from it
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        env.put("FLOTTILA_NODE_LEASE", lease);
        env.put("FLOTTILA_NODE_LEASE_RENEW", renew);
        env.put("FLOTTILA_SECRET_IN_HOST", host);
        env.put("FLOTTILA_SECRET_PARTNER_URL", "http://127.0.0.1:" + partnerServer.actualPort() + partnerPath);
        TestJournalKey.with(env);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(x -> x.startsWith("-javaagent:") && x.contains("jacoco")).forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "io.flottila.runtime.Main"));
        Path log = dir.resolve(id + "-" + n + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        Child child = new Child(id, admin, builder.start(), log);
        children.add(child.process);
        until(() -> {
            if (!child.process.isAlive()) {
                try {
                    fail(id + " exited with " + child.process.exitValue() + " before it served: " + child.log());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return get(admin, "/health").statusCode() == 200;
        }, id + " serves /health 200");
        return child;
    }

    /** PUTs the package with in-flight limit {@code maxInFlight} to {@code child}; its flow runs there. */
    void deploy(Child child, int maxInFlight) throws Exception {
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + child.admin() + "/admin/packages/ledger"))
                .timeout(BOUND).header("content-type", "application/zip").PUT(HttpRequest.BodyPublishers.ofByteArray(ledgerPackage(maxInFlight))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode(), "fixture: the package deploys: " + put.body());
        assertRunning(child);
    }

    void assertRunning(Child child) throws IOException {
        JsonArray flows = new JsonObject(get(child.admin(), "/admin/flows").body()).getJsonArray("items");
        assertEquals(1, flows.size(), "fixture: one flow on " + child.id() + ": " + flows + " " + child.log());
        assertEquals("ledger/ledger", flows.getJsonObject(0).getString("id"), "fixture: the qualified flow id: " + flows);
        assertEquals("RUNNING", flows.getJsonObject(0).getString("state"), "fixture: the flow runs on " + child.id() + ": " + flows + " " + child.log());
    }

    byte[] ledgerPackage(int maxInFlight) throws IOException {
        Map<String, JsonObject> files = new TreeMap<>();
        files.put("package.yaml", doc("package").put("name", "ledger").put("version", "0.1.0"));
        files.put("endpoints/in.yaml", doc("endpoint").put("id", "in").put("scheme", "http").put("direction", "in")
                .put("config", new JsonObject().put("host", "secret://in-host").put("port", inPort).put("path", "/in")));
        files.put("endpoints/partner.yaml", doc("endpoint").put("id", "partner").put("scheme", "http").put("direction", "out")
                .put("config", new JsonObject().put("url", "secret://partner-url").put("maxBodyBytes", 65536).put("timeoutMs", 300_000).put("poolSize", 8)));
        files.put("flows/ledger.yaml", doc("flow").put("id", "ledger").put("version", "0.1.0").put("from", new JsonObject().put("endpoint", "in"))
                .put("delivery", new JsonObject().put("mode", "at-least-once").put("timeout", "5m"))
                .put("recording", new JsonObject().put("level", "all").put("payload", "metadata-only"))
                .put("limits", new JsonObject().put("maxBodyBytes", 65536).put("maxInFlight", maxInFlight))
                .put("steps", new JsonArray().add(new JsonObject().put("type", "to").put("name", "partner").put("endpoint", "partner"))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, JsonObject> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue().encodePrettily().getBytes(StandardCharsets.UTF_8));   // JSON is YAML
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static JsonObject doc(String kind) {
        return new JsonObject().put("schemaVersion", 1).put("kind", kind);
    }

    /**
     * POSTs messages {@code from} .. {@code from + count - 1} to the in door on {@code host} from 8 virtual threads, each until
     * its 202; answers the {@code x-message-id}s of the 202s. Returns once every one was ACKed: nothing is in flight after it.
     */
    List<String> post(String host, int from, int count) throws Exception {
        ConcurrentLinkedQueue<String> acked = new ConcurrentLinkedQueue<>();
        AtomicInteger next = new AtomicInteger(from);
        List<Future<?>> senders = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int s = 0; s < 8; s++) {
                senders.add(pool.submit(() -> {
                    for (int i = next.getAndIncrement(); i < from + count; i = next.getAndIncrement()) {
                        acked.add(ack(host, i));
                    }
                    return null;
                }));
            }
            for (Future<?> sender : senders) sender.get(BOUND.toSeconds(), TimeUnit.SECONDS);
        }
        return List.copyOf(acked);
    }

    /** How long {@link #ack} retries a 503 before it fails with the last answer (FIX-156). */
    static final Duration ACK_RETRY = Duration.ofSeconds(10);

    /**
     * Message {@code i} POSTed until a 202; its id. A 503 (admission refused: retryable backpressure, a refused try is not saved,
     * D-082) is retried with a short growing backoff (10 ms doubling to 200 ms) until {@link #ACK_RETRY} passed, then fails with
     * the last answer; any other status fails at once.
     */
    String ack(String host, int i) throws Exception {
        List<String> tries = new ArrayList<>();
        long deadline = System.nanoTime() + ACK_RETRY.toNanos();
        long backoffMs = 10;
        while (true) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + inPort + "/in")).timeout(Duration.ofSeconds(60))
                    .header("content-type", "application/xml").POST(HttpRequest.BodyPublishers.ofString("<Invoice><Id>" + i + "</Id></Invoice>")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 202) return r.headers().firstValue("x-message-id").orElseThrow(() -> new AssertionError("a 202 without x-message-id"));
            tries.add(r.statusCode() + " " + r.body());
            if (r.statusCode() != 503) return fail("message " + i + " to " + host + " was not ACKed, not retryable: " + tries);
            if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMs) > deadline) {
                return fail("message " + i + " to " + host + " was not ACKed within " + ACK_RETRY + " (" + tries.size() + " tries, last: "
                        + tries.getLast() + ")");
            }
            // FIX-156: a client's backoff against the child node's real-time admission backpressure (a 503 is retryable, D-082),
            // not sleep-based timing of an asserted behaviour; no assertion depends on how long this waits.
            Thread.sleep(backoffMs);
            backoffMs = Math.min(200, backoffMs * 2);
        }
    }

    static HttpResponse<String> get(int port, String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException notListening) {
            return new Refused();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted");
        }
    }

    /** No answer: nothing listens yet. */
    static final class Refused implements HttpResponse<String> {
        @Override public int statusCode() { return -1; }
        @Override public HttpRequest request() { return null; }
        @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public String body() { return ""; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
        @Override public URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /** {@code kill -<sig> <pid>}. */
    static void signal(Child child, String sig) throws Exception {
        Process kill = new ProcessBuilder("kill", "-" + sig, String.valueOf(child.process().pid())).redirectErrorStream(true).start();
        assertTrue(kill.waitFor(30, TimeUnit.SECONDS), "kill -" + sig + " returned");
        assertEquals(0, kill.exitValue(), "kill -" + sig + ": " + new String(kill.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** The child's state letter in {@code /proc/<pid>/stat} ({@code T}: stopped). */
    static char state(Child child) {
        try {
            String stat = Files.readString(Path.of("/proc", String.valueOf(child.process().pid()), "stat"));
            return stat.charAt(stat.lastIndexOf(')') + 2);
        } catch (IOException gone) {
            return '?';
        }
    }

    long count(String sql) {
        return db.query(sql).execute().await().iterator().next().getLong(0);
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after {@link #BOUND} naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within " + BOUND + ": " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    // --- the partner

    /**
     * Records every call — its path's node ({@code a}, {@code b}) and {@code x-message-id} — and the distinct ids first-wins; holds
     * A's calls after the first {@link #holdAAfter} until {@link #releaseA}, never answering a held one and counting the held
     * ones whose connection closed (reset by A, or A dead); answers every other call with its body. Its waits are woken by each
     * call and each close.
     */
    static final class Partner implements Handler<HttpServerRequest> {
        private final List<String> raw = new ArrayList<>();               // guarded by this: "<node> <id>" per call
        private final Set<String> distinct = new LinkedHashSet<>();       // guarded by this
        private final Map<String, Integer> callsPerId = new HashMap<>();  // guarded by this
        private final List<String> held = new ArrayList<>();              // guarded by this: ids of the held calls
        private int answerA = Integer.MAX_VALUE;                          // guarded by this
        private int callsA;                                               // guarded by this
        private boolean releasedA;                                        // guarded by this
        private int closedHeld;                                           // guarded by this

        synchronized void holdAAfter(int answered) {
            answerA = answered;
        }

        synchronized void releaseA() {
            releasedA = true;
        }

        @Override
        public void handle(HttpServerRequest req) {
            String node = req.path().substring(1);
            String id = req.getHeader("x-message-id");
            boolean hold;
            synchronized (this) {
                raw.add(node + " " + id);
                distinct.add(id);
                callsPerId.merge(id, 1, Integer::sum);
                hold = node.equals("a") && !releasedA && ++callsA > answerA;
                if (hold) held.add(id);
                notifyAll();
            }
            if (hold) {
                req.response().closeHandler(closed -> {
                    synchronized (this) {
                        closedHeld++;
                        notifyAll();
                    }
                });
                req.body();
            } else {
                req.body().onSuccess(bytes -> req.response().putHeader("content-type", "application/xml").end(bytes));
            }
        }

        synchronized int raw() {
            return raw.size();
        }

        synchronized Set<String> distinct() {
            return new LinkedHashSet<>(distinct);
        }

        synchronized int callsOf(String node) {
            return (int) raw.stream().filter(c -> c.startsWith(node + " ")).count();
        }

        /** A's calls held so far. */
        synchronized int held() {
            return held.size();
        }

        synchronized int closedHeld() {
            return closedHeld;
        }

        /** Every held call's id came at least twice: what A left in flight was sent again. */
        synchronized boolean heldRerun() {
            return held.stream().allMatch(id -> callsPerId.getOrDefault(id, 0) >= 2);
        }

        /** Waits — woken by each call and close, at most {@link #BOUND} — until {@code condition} holds. */
        void await(BooleanSupplier condition, String what) throws InterruptedException {
            long deadline = System.nanoTime() + BOUND.toNanos();
            synchronized (this) {
                while (!condition.getAsBoolean()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) fail("not within " + BOUND + ": " + what + " (partner now: " + raw.size() + " calls, " + distinct.size() + " distinct, "
                            + held.size() + " held, " + closedHeld + " of them closed)");
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                }
            }
        }
    }
}
