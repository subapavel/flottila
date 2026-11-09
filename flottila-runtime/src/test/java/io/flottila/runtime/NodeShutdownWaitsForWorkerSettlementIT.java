package io.flottila.runtime;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.deploy.PackageDraft;
import io.flottila.runtime.node.ComponentContext;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.TestJournalKey;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-142 (review F4.3, F-29): a node's shutdown awaits each running flow's worker — the items it claimed settle in the work
 * queue before the node lease, the journal, the message store and the pool close — within the one stop deadline
 * ({@code FLOTTILA_STOP_DEADLINE}); past it the node closes all the same, says per flow how many items it leaves unsettled and
 * leaves their work rows claimed (they rerun after their lease: at-least-once). A hot-swap keeps not waiting for the old
 * worker (D-063, FIX-30).
 *
 * <p>Every case runs a node on a Postgres database of its own with the {@link Doors} component (scheme {@code rec}) as its
 * only one: the at-least-once flow {@value #FLOW} takes a message through its door ({@link Doors.Door#deliver}, accepted once
 * the intake saved it) and its worker sends it to the endpoint {@code partner} — the Doors' {@link
 * io.flottila.testkit.RecordingComponent}, whose {@code holdNext} keeps the send pending "at the partner" until the case
 * counts its latch down. "Close has started" is the Doors' stop hook ({@link Doors#onStop}): {@code Deployments.close} stops a
 * flow's worker first and its consumer — the door — right after, so the hook runs once the close is under way and the worker
 * claims nothing more. The stop deadline runs on the node's own (wall-clock) timer: the expiry case keeps it at PT1S.
 */
class NodeShutdownWaitsForWorkerSettlementIT {

    static final String FLOW = "settle/relay";

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path data;
    String database;
    String url;
    final List<Node> open = new ArrayList<>();                     // closed after the case unless the case closed it
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    OnVirtualThread.Call<Void> closing;                            // a close the case started on another thread, joined after it

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
    void createTheCaseDatabase() throws SQLException {
        database = "shutdown_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        // getJdbcUrl() is jdbc:postgresql://host:port/<db> followed by the container's query string (?loggerLevel=OFF)
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String suffix = "/" + pg.getDatabaseName();
        assertTrue(path.endsWith(suffix), "the container URL ends with its database: " + base);
        url = path.substring(0, path.length() - suffix.length()) + "/" + database + (query < 0 ? "" : base.substring(query));
    }

    @AfterEach
    void releaseAndClose() throws Exception {
        holds.forEach(CountDownLatch::countDown);
        if (closing != null) OnVirtualThread.join(closing);
        for (Node n : open) OnVirtualThread.call(() -> { n.close(); return null; });
    }

    // --- fixture

    /** The environment of a node on the case database, admin on any free port, data under the case's directory, {@code deadline} to stop. */
    Map<String, String> env(String deadline) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", "node-it");
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        env.put("FLOTTILA_STOP_DEADLINE", deadline);
        TestJournalKey.with(env);
        return env;
    }

    /** A started node with {@code doors} as its only component; its context lands in {@code ctx}. */
    Node startWith(Map<String, String> env, Doors doors, AtomicReference<ComponentContext> ctx) throws Exception {
        NodeConfig config = NodeConfig.from(env);
        Node n = OnVirtualThread.call(() -> Node.start(config, vertx, c -> {
            ctx.set(c);
            return List.of(doors);
        }, List.of(), List.of()));
        open.add(n);
        return n;
    }

    /** Package {@code settle} @ {@code version}: the at-least-once flow {@code relay} from its door {@code in} to {@code partner}. */
    static PackageDraft settle(String version) {
        return PackageDraft.of("settle", version)
                .endpoint("in", "in", new JsonObject().put("path", "/relay"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("relay", "1.0.0", "at-least-once", "in", 1024, PackageDraft.toPartner());
    }

    /** {@code PUT /admin/packages/settle} within the request's 20 s; its answer. */
    static HttpResponse<String> put(int admin, byte[] zip) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin + "/admin/packages/settle")).timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/zip").PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** One message delivered through the flow's door as its consumer would; asserted accepted (the intake saved it). */
    static String accepted(Doors doors, ComponentContext ctx) throws Exception {
        String id = ctx.ids().next();
        Envelope e = Envelope.ingress(id, FLOW, Headers.empty(),
                ctx.blobs().write(BlobKey.body(id, 0), Buffer.buffer("<invoice/>".getBytes(StandardCharsets.UTF_8)), 1024).await());
        Ack ack = doors.door(FLOW).deliver(e).await(20, TimeUnit.SECONDS);
        assertInstanceOf(Ack.Accepted.class, ack, "fixture: the at-least-once flow accepts " + id);
        return id;
    }

    CountDownLatch holdNextSend(Doors doors) {
        CountDownLatch hold = doors.partner().holdNext(1);
        holds.add(hold);
        return hold;
    }

    /** How often the partner was sent message {@code id}. */
    static long sendsOf(Doors doors, String id) {
        return doors.partner().sent().stream().filter(e -> e.id().equals(id)).count();
    }

    /** The work rows of message {@code id} on the case database: "locked_by attempts" each. */
    List<String> workRows(String id) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
             PreparedStatement q = c.prepareStatement("select locked_by, attempts from work where id = ? order by seq")) {
            q.setString(1, id);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) rows.add(r.getString(1) + " " + r.getInt(2));
            }
        }
        return rows;
    }

    /** The node's {@code nodes.lease_until} read on {@code c}; null without a row or once released. */
    static Timestamp nodeLeaseUntil(Connection c) {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select lease_until from nodes where node = 'node-it'")) {
            return r.next() ? r.getTimestamp(1) : null;
        } catch (SQLException e) {
            throw new AssertionError("reading the node lease", e);
        }
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /** Every record of the {@code io.flottila} loggers as "LEVEL message", from now until the handler is removed. */
    static Handler capture(List<String> lines) {
        Handler all = new Handler() {
            @Override public void publish(LogRecord r) { lines.add(r.getLevel() + " " + new SimpleFormatter().formatMessage(r)); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        all.setLevel(Level.ALL);
        Logger.getLogger("io.flottila").addHandler(all);
        return all;
    }

    // --- the cases

    /**
     * One message accepted, its send held at the partner; {@code Node.close} begins on another thread (stop deadline PT2M). The
     * hold is released once close has started (the door's stop hook) and then either close returned (the bug: it never waited)
     * or the node lease was renewed twice after that signal (FLOTTILA_NODE_LEASE PT2S, renewed every PT0.5S: close is still
     * draining, the lease still live) — whichever comes first. After close returns the work row is gone (settled through the
     * still-open pool) and the partner saw one send; a restart on the same database and data directory sends nothing more of it,
     * while its worker runs: a second message, delivered after the restart, is the only send the new partner sees.
     */
    @Test
    void aHeldSendSettlesBeforeTheStoresClose() throws Exception {
        Map<String, String> env = env("PT2M");
        env.put("FLOTTILA_NODE_LEASE", "PT2S");
        env.put("FLOTTILA_NODE_LEASE_RENEW", "PT0.5S");
        Doors doors = new Doors();
        CountDownLatch closeStarted = new CountDownLatch(1);
        doors.onStop(door -> closeStarted.countDown());
        AtomicReference<ComponentContext> ctx = new AtomicReference<>();
        Node node = startWith(env, doors, ctx);
        assertEquals(200, put(node.adminPort(), settle("1.0.0").zip()).statusCode(), "fixture: settle deploys");

        CountDownLatch hold = holdNextSend(doors);
        String id = accepted(doors, ctx.get());
        doors.partner().awaitSent(1);
        List<String> claimed = workRows(id);
        assertEquals(1, claimed.size(), "fixture: one work row for " + id + ": " + claimed);
        assertFalse(claimed.get(0).startsWith("null "), "fixture: its row is claimed by the worker whose send is held: " + claimed);

        boolean returnedWhileHeld;
        Timestamp atSignal;
        Timestamp atRelease;
        try (Connection reader = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword())) {
            open.remove(node);
            closing = OnVirtualThread.start(node::close);
            assertTrue(closeStarted.await(20, TimeUnit.SECONDS), "close has started: the flow's door is stopped (its worker was stopped first)");
            atSignal = nodeLeaseUntil(reader);
            assertNotNull(atSignal, "fixture: the node lease is live when close starts");
            Timestamp[] seen = {atSignal, null};
            until(() -> {
                if (closing.isDone()) return true;
                Timestamp now = nodeLeaseUntil(reader);
                if (now != null && now.after(seen[0])) {
                    if (seen[1] != null) return true;   // the second renewal after the signal
                    seen[1] = now;
                    seen[0] = now;
                }
                return false;
            }, "close returns, or the node lease is renewed twice while close drains");
            returnedWhileHeld = closing.isDone();
            atRelease = seen[0];
        }
        hold.countDown();
        OnVirtualThread.join(closing);

        assertEquals(List.of(), workRows(id), "the held send settled before the stores closed: its work row is gone "
                + "(Node.close had " + (returnedWhileHeld ? "already returned" : "not returned") + " when the send was released)");
        assertFalse(returnedWhileHeld, "Node.close waits for the worker's held send (stop deadline PT2M)");
        assertTrue(atRelease.after(atSignal), "the node lease kept being renewed while close drained: " + atSignal + " → " + atRelease);
        assertEquals(1, sendsOf(doors, id), "the partner saw the message once: " + doors.partner().sent());

        Doors again = new Doors();
        AtomicReference<ComponentContext> restarted = new AtomicReference<>();
        startWith(env, again, restarted);
        String next = accepted(again, restarted.get());
        assertNotEquals(id, next, "fixture: the restart mints another id");
        again.partner().awaitSent(1);
        List<String> sentAfterRestart = again.partner().sent().stream().map(Envelope::id).toList();
        assertEquals(List.of(next), sentAfterRestart, "after the restart only the new message is sent; " + id + " was settled, never sent again");
    }

    /**
     * The send is never released before close returns; FLOTTILA_STOP_DEADLINE PT1S (wall time: the node's timer). Close returns
     * within 20 s; one WARNING names the flow and its 1 unsettled item; the work row is still there as it was claimed (same
     * holder, same attempts: neither settled nor deleted by the shutdown). Releasing the hold afterwards lets the worker's run
     * end — its failure logged, naming the message, and its producers closed at its chain's end — and still nothing settles,
     * and no thread dies of an uncaught throwable.
     */
    @Test
    void aDrainBudgetExpiryLeavesTheItemUnsettledAndSaysSo() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        Handler handler = capture(lines);
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        List<String> uncaught = new CopyOnWriteArrayList<>();
        try {
            Doors doors = new Doors();
            AtomicReference<ComponentContext> ctx = new AtomicReference<>();
            Node node = startWith(env("PT1S"), doors, ctx);
            assertEquals(200, put(node.adminPort(), settle("1.0.0").zip()).statusCode(), "fixture: settle deploys");

            CountDownLatch hold = holdNextSend(doors);
            String id = accepted(doors, ctx.get());
            doors.partner().awaitSent(1);
            List<String> claimed = workRows(id);
            assertEquals(1, claimed.size(), "fixture: one work row for " + id + ": " + claimed);
            assertFalse(claimed.get(0).startsWith("null "), "fixture: its row is claimed: " + claimed);
            List<Doors.Out> carriers = doors.producers().stream().filter(o -> o.sentIds().contains(id)).toList();
            assertEquals(1, carriers.size(), "fixture: one producer carries the held send: " + doors.producers());
            Doors.Out carrier = carriers.get(0);

            open.remove(node);
            closing = OnVirtualThread.start(node::close);
            try {
                closing.outcome().get(20, TimeUnit.SECONDS);
            } catch (TimeoutException stillRunning) {
                throw new AssertionError("close did not return within 20 s with a PT1S stop deadline: the wait for the worker is unbounded", stillRunning);
            }

            List<String> unsettled = lines.stream().filter(l -> l.contains("unsettled")).toList();
            assertEquals(1, unsettled.size(), "one line on the unsettled items of the flow: " + unsettled);
            String warning = unsettled.get(0);
            assertTrue(warning.startsWith("WARNING "), "it is a WARNING: " + warning);
            assertTrue(warning.contains(FLOW), "it names the flow " + FLOW + ": " + warning);
            assertTrue(warning.contains("1 unsettled item"), "it names the count, 1 unsettled item: " + warning);
            assertEquals(claimed, workRows(id), "the work row stays as claimed, neither settled nor deleted by the shutdown");

            Thread.setDefaultUncaughtExceptionHandler((t, failure) -> uncaught.add(t + ": " + failure));
            int logged = lines.size();
            hold.countDown();
            until(() -> lines.subList(logged, lines.size()).stream().anyMatch(l -> (l.startsWith("WARNING ") || l.startsWith("SEVERE ")) && l.contains(id)),
                    "the worker's run ends after the release and its failure is logged naming " + id + ": " + lines.subList(logged, lines.size()));
            until(() -> carrier.closes() > 0, "the worker's run ended (its settle tried or skipped): the worker closes its producers at its chain's end: " + carrier);
            assertEquals(claimed, workRows(id), "a worker finishing after the stores closed does not settle: the row is as claimed");
            assertEquals(List.of(), uncaught, "the late finish throws nowhere");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
            Logger.getLogger("io.flottila").removeHandler(handler);
        }
    }

    /**
     * Control (D-063, FIX-30; green before the fix): a hot-swap of the flow (its yaml changed) while the old version's send is
     * held at the partner (stop deadline PT2M) answers 200 within the request's 20 s, swapping the flow, and the producer the
     * held send went through is still open then — the old worker's run is still in flight. Released, the old run ends, closes its
     * producers and settles its row through the node's open pool; the partner saw the message once.
     */
    @Test
    void aRedeployStillDoesNotWaitForTheOldWorker() throws Exception {
        Doors doors = new Doors();
        AtomicReference<ComponentContext> ctx = new AtomicReference<>();
        Node node = startWith(env("PT2M"), doors, ctx);
        assertEquals(200, put(node.adminPort(), settle("1.0.0").zip()).statusCode(), "fixture: settle 1.0.0 deploys");

        CountDownLatch hold = holdNextSend(doors);
        String id = accepted(doors, ctx.get());
        doors.partner().awaitSent(1);
        List<Doors.Out> carriers = doors.producers().stream().filter(o -> o.sentIds().contains(id)).toList();
        assertEquals(1, carriers.size(), "fixture: one producer carries the held send: " + doors.producers());
        Doors.Out carrier = carriers.get(0);

        PackageDraft v2 = settle("1.0.1");
        v2.flowDoc("relay").put("description", "changed in 1.0.1");
        HttpResponse<String> swap;
        try {
            swap = put(node.adminPort(), v2.zip());
        } catch (HttpTimeoutException waited) {
            throw new AssertionError("the redeploy did not answer within 20 s: it waits for the old worker's held send", waited);
        }
        assertEquals(200, swap.statusCode(), swap.body());
        assertEquals(new JsonArray().add(FLOW), new JsonObject(swap.body()).getJsonArray("swapped"), "the flow was swapped: " + swap.body());
        assertEquals(0, carrier.closes(), "the old worker's producer is still open when the redeploy answered: its run is in flight: " + carrier);
        assertEquals(1, workRows(id).size(), "its work row is still there, claimed by the old worker");

        hold.countDown();
        until(() -> carrier.closes() > 0, "the old worker closes its producers once its run ended: " + carrier);
        until(() -> {
            try {
                return workRows(id).isEmpty();
            } catch (SQLException e) {
                throw new AssertionError("reading the work rows", e);
            }
        }, "the old run settles its row through the node's open pool");
        assertEquals(1, sendsOf(doors, id), "the partner saw the message once: " + doors.partner().sent());
    }
}
