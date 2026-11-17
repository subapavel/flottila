package dev.rail8.runtime.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.components.http.HttpComponent;
import dev.rail8.core.batch.BatchingWriter;
import dev.rail8.core.flow.FlowBuilder;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.FlowWorker;
import dev.rail8.core.flow.WorkerPolicy;
import dev.rail8.core.record.Meter;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.runtime.store.PgMessageStore;
import dev.rail8.runtime.time.VertxTimer;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CONSIDER-24 step 1 (the staged death), cases 6 to 8, on Postgres: these pin today's behaviour, they do not ask for a change. A
 * run's final row commits in the message store's batch; the delete of its work row is another statement of another writer, later.
 * Between the two, the row is committed and visible while the work row stays; a node that dies there is run again by another node
 * once the lease ends, and the partner gets a second request with the same headers — nothing on the wire says "again". A later
 * decision (a run skipped when an earlier attempt recorded its final step; one transaction for the rows and the settle) would turn
 * case 7 red.
 *
 * <p>The real {@link PgMessageStore} and {@link PgWorkQueue} (its unkeyed completions batched, as a node builds it) over one
 * Postgres; nodes are in-process workers, each over a pool of its own whose sessions are named {@code rerun@<node>}; the bodies in
 * the test's in-memory store (both nodes read it: no journal). The partner is a real HTTP server in this JVM, reached through the
 * real {@link HttpComponent}; it records each request's headers and body and answers or holds it. Staging, without sleeps:
 * a session of the test's own holds {@code lock table work in share mode} (the seam of {@code PgWorkQueueBatchedCompletionIT}), so
 * the node's DELETE of the work row waits in Postgres; the node's death is the loss of its database — every session of its pool
 * terminated while the DELETE waits, its pool closed, its worker abandoned — so the DELETE never runs; the lease's end is
 * {@code update work set locked_until = now() - interval '1 second'}, as the queue ITs end one. Waits spin on observed state.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class RerunWindowIT {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration BOUND = Duration.ofSeconds(20);
    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool admin;   // the test's own sessions: fixtures, and the rows as another session sees them

    final FakeRuntime rt = FakeRuntime.create();
    final Partner partner = new Partner();
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<Stall> stalls = new CopyOnWriteArrayList<>();
    HttpServer partnerServer;
    Map<String, EndpointConfig> endpoints;
    FlowDefinition def;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        admin = PgBuilder.pool().connectingTo(connectOptions()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        admin.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void empty() {
        admin.query("truncate message_keys, messages, dlq, work, nodes").execute().await();
        partnerServer = vertx.createHttpServer().requestHandler(partner).listen(0, "127.0.0.1").await();
        endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("http://127.0.0.1:" + partnerServer.actualPort() + "/partner"),
                Map.of("maxBodyBytes", 65_536L)));
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "http-in"));
        doc.put("delivery", Map.of("mode", "at-least-once"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 4));
        doc.put("steps", List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        def = FlowDefinition.from(doc);
    }

    @AfterEach
    void close() {
        stalls.forEach(Stall::release);
        partner.releaseHeld();
        nodes.forEach(Node::close);
        if (partnerServer != null) partnerServer.close().await();
    }

    // --- fixtures

    static PgConnectOptions connectOptions() {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
    }

    static String session(String node) {
        return "rerun@" + node;
    }

    /** Spins (never sleeping) on real I/O until {@code condition} holds; fails after {@link #BOUND} naming what never happened. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within " + BOUND + ": " + what.get());
            Thread.onSpinWait();
        }
    }

    static <T> T await(Future<T> f) {
        try {
            return f.await(BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("still pending after " + BOUND + ": " + f, e);
        }
    }

    static long count(String sql, Object... args) {
        return admin.preparedQuery(sql).execute(Tuple.from(args)).await().iterator().next().getLong(0);
    }

    /** "attempt k outcome" of each {@code messages} row of the final step of {@code id}, by attempt, as the test's own session reads them. */
    static List<String> finalRows(String id) {
        List<String> out = new ArrayList<>();
        for (Row r : admin.preparedQuery("select attempt, outcome from messages where id = $1 and step_name = 'partner' order by attempt")
                .execute(Tuple.of(id)).await()) {
            out.add("attempt " + r.getInteger(0) + " " + r.getString(1));
        }
        return out;
    }

    /** "attempts locked_by" of the work row of {@code id}, or "gone". */
    static String workRow(String id) {
        for (Row r : admin.preparedQuery("select attempts, locked_by from work where id = $1").execute(Tuple.of(id)).await()) {
            return r.getInteger(0) + " " + r.getString(1);
        }
        return "gone";
    }

    /** Sessions of {@code node}'s pool waiting on a lock in a DELETE of work. */
    static long deletesWaiting(String node) {
        return count("select count(*) from pg_stat_activity where application_name = $1 and wait_event_type = 'Lock' and query ilike 'delete from work%'",
                session(node));
    }

    static long sessionsOf(String node) {
        return count("select count(*) from pg_stat_activity where application_name = $1", session(node));
    }

    /** The lease of {@code id}'s work row ends now, on the database's clock. */
    static void endTheLease(String id) {
        assertEquals(1, admin.preparedQuery("update work set locked_until = now() - interval '1 second' where id = $1").execute(Tuple.of(id)).await().rowCount(),
                "fixture: the lease of " + id + " ended");
    }

    /** A request as the partner received it: its headers in arrival order (names lower-cased), its body. */
    record Call(List<String> headers, String body) {
        List<String> valuesOf(String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ": ";
            return headers.stream().filter(h -> h.startsWith(prefix)).map(h -> h.substring(prefix.length())).toList();
        }
    }

    /** The partner: records every request once its body arrived; answers it 200 with the body, or holds it until {@link #releaseHeld}. */
    static final class Partner implements Handler<HttpServerRequest> {
        private final List<Call> calls = new ArrayList<>();                  // guarded by this
        private final List<Runnable> held = new ArrayList<>();               // guarded by this: the answers of the held requests
        private int toHold;                                                  // guarded by this

        synchronized void holdNext() {
            toHold++;
        }

        @Override
        public void handle(HttpServerRequest req) {
            Context ctx = Vertx.currentContext();
            req.body().onSuccess(bytes -> {
                List<String> headers = req.headers().entries().stream().map(e -> e.getKey().toLowerCase(Locale.ROOT) + ": " + e.getValue()).toList();
                Runnable answer = () -> req.response().setStatusCode(200).putHeader("content-type", "application/xml").end(bytes);
                boolean hold;
                synchronized (this) {
                    calls.add(new Call(headers, bytes.toString()));
                    hold = toHold > 0;
                    if (hold) {
                        toHold--;
                        held.add(() -> ctx.runOnContext(v -> answer.run()));
                    }
                }
                if (!hold) answer.run();
            });
        }

        synchronized List<Call> calls() {
            return List.copyOf(calls);
        }

        List<String> ids() {
            return calls().stream().flatMap(c -> c.valuesOf(Envelope.H_MESSAGE_ID).stream()).toList();
        }

        void releaseHeld() {
            List<Runnable> answers;
            synchronized (this) {
                answers = List.copyOf(held);
                held.clear();
            }
            answers.forEach(Runnable::run);
        }
    }

    /** A session of the test's own holding {@code lock table work in share mode}: every DELETE of work waits until {@link #release}. */
    final class Stall {
        final SqlConnection conn;
        final Transaction tx;
        boolean released;

        Stall() {
            conn = await(admin.getConnection());
            tx = await(conn.begin());
            await(conn.query("lock table work in share mode").execute());
            stalls.add(this);
        }

        synchronized void release() {
            if (released) return;
            released = true;
            await(tx.rollback());
            await(conn.close());
        }
    }

    /** A node: the real stores over a pool of its own, the http partner through the real component, a worker of flow {@code orders}. */
    final class Node {
        final String name;
        final Pool pool;
        final PgMessageStore messages;
        final PgWorkQueue work;
        final FlowBuilder builder;
        final FlowWorker worker;
        boolean closed;

        Node(String name) {
            this.name = name;
            pool = PgBuilder.pool().connectingTo(connectOptions().addProperty("application_name", session(name))).with(new PoolOptions().setMaxSize(4))
                    .using(vertx).build();
            VertxTimer timer = new VertxTimer(vertx);
            messages = new PgMessageStore(pool, timer, BatchingWriter.Options.defaults());   // as Node builds it
            work = new PgWorkQueue(pool, name, null, timer);                                  // unkeyed completions batched, as Node builds it
            HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids(), rt.timer());
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(http.scheme(), http));
            builder = new FlowBuilder(runtime, List.of(), List.of(), endpoints).recording(messages, Meter.system()).queue(work).dlq(new PgDlq(pool));
            worker = builder.worker(def, NO_FILES, name, new WorkerPolicy(LEASE, Duration.ofSeconds(1)));
            nodes.add(this);
        }

        /** A fresh message through the flow's at-least-once delivery on this node: its ingress row and work row committed, nothing run. */
        String accept() {
            Envelope in = rt.ingress(def.id(), Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
            assertEquals(new Ack.Accepted(in.id()), await(builder.delivery(def, NO_FILES).apply(in)), "fixture: accepted");
            return in.id();
        }

        /**
         * The node loses its database while its round is in {@code round}: every session of its pool terminated — its DELETE waiting
         * on the stall among them, so it never runs — its round ended by that failure, its worker stopped, its pool closed.
         */
        void losesItsDatabase(Future<Integer> round) {
            assertTrue(count("select count(*) from (select pg_terminate_backend(pid) from pg_stat_activity where application_name = $1) t", session(name)) >= 1,
                    "fixture: " + name + " had sessions to terminate");
            until(() -> sessionsOf(name) == 0, () -> "every session of " + name + " gone");
            until(round::isComplete, () -> name + "'s round ends with its lost settle");
            assertTrue(round.failed(), "fixture: " + name + "'s settle failed with its sessions: " + round.result());
            close();
            assertEquals(0, sessionsOf(name), "fixture: " + name + " has no session left");
        }

        void close() {
            if (closed) return;
            closed = true;
            worker.stop();
            messages.close();
            await(work.close());
            await(pool.close());
        }
    }

    /** Case 6's state: node-a's run finished — the partner answered, its rows committed — and its DELETE of the work row waits on a stall. */
    record Gap(Node a, String id, Future<Integer> round, Stall stall) {}

    Gap stalledBetweenTheTwoCommits() {
        Node a = new Node("node-a");
        String id = a.accept();
        assertEquals("0 null", workRow(id), "fixture: queued, never handed out");
        partner.holdNext();   // the run waits at the partner, its claim committed, while the stall is taken
        Future<Integer> round = a.worker.poll();
        until(() -> partner.calls().size() == 1, () -> "node-a's request reached the partner");
        assertEquals("1 node-a", workRow(id), "fixture: claimed by node-a, attempt 1");
        Stall stall = new Stall();
        partner.releaseHeld();
        until(() -> deletesWaiting("node-a") == 1, () -> "node-a's run finished and its DELETE of the work row waits on the stall; work row "
                + workRow(id) + ", final rows " + finalRows(id) + ", round " + round);
        return new Gap(a, id, round, stall);
    }

    /** Case 7's staging: from case 6's state node-a dies, the stall is released, the lease ends, node-b takes the row and runs it. */
    String runAgainByAnotherNodeAfterADeathBetweenTheTwoCommits() {
        Gap g = stalledBetweenTheTwoCommits();
        g.a().losesItsDatabase(g.round());
        g.stall().release();
        assertEquals("1 node-a", workRow(g.id()), "the DELETE died with node-a's session: the work row stays, attempt 1, node-a's");
        assertEquals(List.of("attempt 1 ok"), finalRows(g.id()), "the final row of attempt 1 stays");

        Node b = new Node("node-b");
        assertEquals(0, await(b.worker.poll()), "before the lease's end node-b takes nothing");
        assertEquals("1 node-a", workRow(g.id()), "the row still node-a's");
        endTheLease(g.id());
        assertEquals(1, await(b.worker.poll()), "at the lease's end node-b takes the row and runs it");
        return g.id();
    }

    // --- the cases

    /**
     * Case 6: the two commits are apart. With node-a's DELETE of the work row stalled and its run finished, the test's own session
     * reads the final step's row, ok, attempt 1 — committed and visible — while {@code work} still holds the item's row. Witness:
     * released, the same DELETE runs, the round ends and the row is gone.
     */
    @Test
    void theFinalRowIsCommittedAndVisibleWhileTheDeleteOfTheWorkRowStillWaits() {
        Gap g = stalledBetweenTheTwoCommits();
        assertEquals(List.of("attempt 1 ok"), finalRows(g.id()), "messages holds the final step's row, ok, attempt 1: committed, read by another session");
        assertEquals("1 node-a", workRow(g.id()), "while work still holds the item's row: attempt 1, leased to node-a");
        assertFalse(g.round().isComplete(), "node-a's round has not ended: its settle waits");

        g.stall().release();
        assertEquals(1, await(g.round()), "released, node-a's settle answers and its round ends");
        assertEquals("gone", workRow(g.id()), "the DELETE ran: the work row is gone");
        assertEquals(List.of("attempt 1 ok"), finalRows(g.id()), "one final row");
        assertEquals(1, partner.calls().size(), "the partner called once");
    }

    /**
     * Case 7: a death between the two commits delivers again. From case 6's state node-a loses its database before its DELETE runs;
     * the stall released, the row stays; the lease ends and node-b takes it. Then the partner has received two calls with the same
     * id, {@code messages} holds final OK rows for attempts 1 and 2 of the one id, and {@code work} is empty.
     */
    @Test
    void aNodeThatDiesBetweenTheTwoCommitsIsRunAgainByAnotherNodeAndThePartnerGetsTheIdTwice() {
        String id = runAgainByAnotherNodeAfterADeathBetweenTheTwoCommits();
        assertEquals(List.of(id, id), partner.ids(), "the partner received two calls with the same x-message-id");
        assertEquals(List.of("attempt 1 ok", "attempt 2 ok"), finalRows(id), "final OK rows for attempts 1 and 2 of the one id");
        assertEquals(0, count("select count(*) from work"), "work is empty");
    }

    /**
     * Case 8: what the partner is told, on the wire. The second request of case 7 carries exactly the first's headers — names,
     * values, order; nothing excluded — and the same body: no header says "again".
     */
    @Test
    void theSecondRequestCarriesTheFirstRequestsHeadersAndNothingSaysAgain() {
        String id = runAgainByAnotherNodeAfterADeathBetweenTheTwoCommits();
        List<Call> calls = partner.calls();
        assertEquals(2, calls.size(), "fixture: two requests");
        assertEquals(List.of(id), calls.get(0).valuesOf(Envelope.H_MESSAGE_ID), "the first carries the message id once");
        assertEquals(calls.get(0).headers(), calls.get(1).headers(), "the second request's headers are the first's");
        assertEquals(calls.get(0).body(), calls.get(1).body(), "and its body");
    }
}
