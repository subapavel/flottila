package io.flottila.runtime.journal;

import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.runtime.queue.PgWorkSignals;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16b (D-121 (b) B2), beyond {@link PgWorkSignalsIT}: a reconnect whose {@code LISTEN} fails is tried again after a doubled
 * backoff, logged, and wakes nothing until a {@code LISTEN} is acknowledged; a close while a reconnect is armed cancels it. The
 * node listens through {@link PgWire} {@code listenWire}, whose relay answers the next {@code LISTEN} with a FATAL error where a
 * case asks it to; its listener's backend is terminated by {@code pg_terminate_backend}; the fake clock drives the backoff.
 */
@Timeout(120)
class PgWorkSignalsReconnectIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static final String LISTENING = "from pg_stat_activity where datname = current_database() and query ilike 'listen%' and pid <> pg_backend_pid()";

    CaseDatabase db;
    PgWire listenWire;
    Pool pool;
    PgWorkSignals signals;
    final FakeRuntime rt = FakeRuntime.create();
    final Workers workers = new Workers();
    final Map<String, PgWorkSignalsIT.Counting> queues = new HashMap<>();
    final List<FlowWorker> started = new ArrayList<>();
    final java.util.logging.Logger signalsLog = java.util.logging.Logger.getLogger(PgWorkSignals.class.getName());
    final NodeJournalIT.Logs logs = new NodeJournalIT.Logs();
    /** Every delay the node's signals scheduled on the fake timer, in schedule order (no announce here: only reconnects). */
    final List<Duration> scheduled = new java.util.concurrent.CopyOnWriteArrayList<>();
    final io.flottila.api.Timer recording = (delay, task) -> {
        scheduled.add(delay);
        return rt.timer().schedule(delay, task);
    };

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
    void open() throws Exception {
        signalsLog.addHandler(logs);
        db = CaseDatabase.create(pg, vertx, "reconnect_");
        listenWire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        pool = PgBuilder.pool().connectingTo(db.options()).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        signals = new PgWorkSignals(vertx, db.options().setHost("127.0.0.1").setPort(listenWire.port()), pool, "n-a", workers, recording);
        signals.start().await(20, TimeUnit.SECONDS);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of());
        for (String f : List.of("orders", "invoices")) {
            PgWorkSignalsIT.Counting q = new PgWorkSignalsIT.Counting(new InMemoryWorkQueue(rt.clock()));
            FlowWorker w = new FlowBuilder(runtime, List.of(PgWorkSignalsIT.PASS), List.of(), Map.of()).recording(new InMemoryMessageStore(), Meter.system())
                    .queue(q).dlq(new InMemoryDlq()).workers(workers).worker(PgWorkSignalsIT.flow(f), Path.of(""), "n-a",
                            new WorkerPolicy(Duration.ofSeconds(30), 5, Duration.ofMinutes(10)));
            queues.put(f, q);
            w.start();
            started.add(w);
        }
        PgWorkSignalsIT.until(() -> claims("orders") == 1 && claims("invoices") == 1, () -> "the start claims");
        PgWorkSignalsIT.until(() -> db.count("select count(*) " + LISTENING) == 1, () -> "fixture: the node's listener is the one listening backend");
    }

    @AfterEach
    void close() throws Exception {
        signalsLog.removeHandler(logs);
        started.forEach(FlowWorker::stop);
        signals.close().await(20, TimeUnit.SECONDS);
        pool.close().await();
        listenWire.close();
        db.close();
    }

    int claims(String flow) { return queues.get(flow).claims.get(); }

    long listens(int mark) {
        return listenWire.since(mark).stream().filter(s -> s.sql() != null && s.sql().strip().toLowerCase(Locale.ROOT).startsWith("listen")).count();
    }

    List<String> warnings() {
        return logs.lines.stream().filter(l -> l.startsWith("WARNING ")).toList();
    }

    /** Waits until the earliest pending timer task is due in {@code delay} — the reconnect armed — and fires it. */
    void armedIn(Duration delay) {
        PgWorkSignalsIT.until(() -> rt.timer().pending() > 2 && rt.timer().nextDelay().equals(delay),
                () -> "a reconnect armed in " + delay + "; pending " + rt.timer().pending() + ", next " + rt.timer().nextDelay() + ", logs " + logs.all());
        rt.clock().advance(delay);
    }

    @Test
    void aReconnectWhoseListenFailsIsTriedAgainAfterADoubledBackoffAndWakesOnceItListens() throws Exception {
        int mark = listenWire.mark();
        PgWire.FatalReply refused = listenWire.fatalReplyTo(PgWire.query("LISTEN " + PgWorkSignals.CHANNEL));
        assertEquals(1, db.count("select count(pg_terminate_backend(pid)) " + LISTENING), "fixture: the listener terminated");

        armedIn(Duration.ofMillis(100));   // the first reconnect: its LISTEN answered FATAL
        refused.awaitDone();
        armedIn(Duration.ofMillis(200));   // tried again, the backoff doubled
        PgWorkSignalsIT.until(() -> claims("orders") == 2 && claims("invoices") == 2, () -> "woken once the second LISTEN was acknowledged; " + queues);
        assertEquals(2, listens(mark), "two LISTENs on new connections: the refused one, the acknowledged one");
        PgWorkSignalsIT.until(() -> warnings().size() == 2, () -> "the loss and the refused LISTEN logged: " + warnings());
        assertTrue(warnings().get(0).contains("lost") && warnings().get(1).contains("could not be reopened"), "each named: " + warnings());

        PgWorkSignalsIT.until(() -> db.count("select count(*) " + LISTENING) == 1, () -> "listening again");
        assertEquals(1, db.count("select count(pg_terminate_backend(pid)) " + LISTENING), "the new listener terminated too");
        armedIn(Duration.ofMillis(100));   // a LISTEN acknowledged set the backoff back to its first delay
        PgWorkSignalsIT.until(() -> claims("orders") == 3 && claims("invoices") == 3, () -> "woken once more; " + queues);
    }

    /**
     * Review r1 V1: the relay gone — the listener's connection closed with it, every reconnect refused — the reconnect is tried after
     * 100 ms, then after a delay doubled each time up to the 5 s bound, and at the bound from then on: 100, 200, 400, 800, 1600,
     * 3200, 5000, 5000 ms, as the fake timer was asked to schedule them.
     */
    @Test
    void reconnectsRefusedEveryTimeBackOffDoublingUpToFiveSecondsAndStayThere() throws Exception {
        assertEquals(List.of(), scheduled, "fixture: nothing scheduled by the signals before the loss");
        listenWire.close();   // the listener's relayed connection closed; the relay's port refuses every connection from now on
        List<Duration> expected = List.of(100L, 200L, 400L, 800L, 1600L, 3200L, 5000L, 5000L).stream().map(Duration::ofMillis).toList();
        for (int i = 0; i < expected.size(); i++) {
            int armed = i + 1;
            PgWorkSignalsIT.until(() -> scheduled.size() == armed, () -> "reconnect " + armed + " armed; scheduled " + scheduled + ", logs " + logs.all());
            assertEquals(expected.get(i), scheduled.get(i), "reconnect " + armed + "'s delay; scheduled " + scheduled);
            if (armed < expected.size()) rt.clock().advance(scheduled.get(i));   // fires it: refused, the next armed
        }
        assertEquals(expected, scheduled, "the backoff: doubled from 100 ms, bounded at 5 s");
        assertEquals(1, claims("orders"), "nothing woken while no LISTEN was acknowledged: " + queues);
    }

    /** The hot standby's answer to {@code LISTEN}: a plain ERROR, the connection left open and usable (review r1 V2). */
    PgWire.ErrorReply standbyRefusesTheNextListen() {
        return listenWire.errorReplyTo(PgWire.query("LISTEN " + PgWorkSignals.CHANNEL), "25006", "cannot execute LISTEN during recovery");
    }

    /** The relayed connection that sent the first LISTEN after {@code mark}. */
    int listenConnection(int mark) {
        return listenWire.since(mark).stream().filter(s -> s.sql() != null && s.sql().startsWith("LISTEN")).findFirst().orElseThrow().conn();
    }

    /** Whether the client sent Terminate on relayed connection {@code conn} after {@code mark}. */
    boolean terminated(int mark, int conn) {
        return listenWire.since(mark).stream().anyMatch(s -> s.type() == 'X' && s.conn() == conn);
    }

    /** Review r1 V2 on start(): a LISTEN answered with a plain ERROR fails the start, and its connection is closed by the client. */
    @Test
    void aStartWhoseListenIsAnsweredWithAnErrorFailsAndClosesItsConnection() throws Exception {
        int mark = listenWire.mark();
        PgWire.ErrorReply refused = standbyRefusesTheNextListen();
        PgWorkSignals standby = new PgWorkSignals(vertx, db.options().setHost("127.0.0.1").setPort(listenWire.port()), pool, "n-s", new Workers(), rt.timer());
        try {
            Throwable failed = org.junit.jupiter.api.Assertions.assertThrows(Throwable.class, () -> standby.start().await(20, TimeUnit.SECONDS),
                    "the start fails with its LISTEN refused");
            boolean named = false;
            for (Throwable t = failed; t != null && !named; t = t.getCause()) named = String.valueOf(t.getMessage()).contains("cannot execute LISTEN during recovery");
            assertTrue(named, "failed with the server's error: " + failed);
            refused.awaitDone();
            int conn = listenConnection(mark);
            PgWorkSignalsIT.until(() -> terminated(mark, conn),
                    () -> "the refused connection, still open after an ERROR, closed by the client (Terminate): " + listenWire.since(mark));
        } finally {
            standby.close().await(20, TimeUnit.SECONDS);
        }
    }

    /** Review r1 V2 on a reconnect: its LISTEN answered with a plain ERROR, its connection is closed and the next try armed. */
    @Test
    void aReconnectWhoseListenIsAnsweredWithAnErrorClosesItsConnectionAndTriesAgain() throws Exception {
        int mark = listenWire.mark();
        PgWire.ErrorReply refused = standbyRefusesTheNextListen();
        assertEquals(1, db.count("select count(pg_terminate_backend(pid)) " + LISTENING), "fixture: the listener terminated");
        armedIn(Duration.ofMillis(100));   // the first reconnect: its LISTEN answered ERROR
        refused.awaitDone();
        int conn = listenConnection(mark);
        PgWorkSignalsIT.until(() -> terminated(mark, conn),
                () -> "the refused connection, still open after an ERROR, closed by the client (Terminate): " + listenWire.since(mark));
        armedIn(Duration.ofMillis(200));   // tried again
        PgWorkSignalsIT.until(() -> claims("orders") == 2 && claims("invoices") == 2, () -> "woken once the second LISTEN was acknowledged; " + queues);
        assertEquals(2, listens(mark), "the refused LISTEN, the acknowledged one");
    }

    /** The node's listener terminated and its reconnect fired: the reconnect's LISTEN is held at the relay, {@code held}. */
    PgWire.Held reconnectHeldAtItsListen() throws Exception {
        PgWire.Held held = listenWire.holdNext(PgWire.query("LISTEN " + PgWorkSignals.CHANNEL));
        assertEquals(1, db.count("select count(pg_terminate_backend(pid)) " + LISTENING), "fixture: the listener terminated");
        armedIn(Duration.ofMillis(100));
        held.awaitReached();
        return held;
    }

    /** A LISTEN acknowledged after the close: its connection is closed again, nobody woken. */
    @Test
    void aReconnectAcknowledgedAfterTheCloseIsClosedAndWakesNothing() throws Exception {
        int mark = listenWire.mark();
        PgWire.Held held = reconnectHeldAtItsListen();
        int conn = listenWire.since(mark).stream().filter(s -> s.sql() != null && s.sql().startsWith("LISTEN")).findFirst().orElseThrow().conn();
        try {
            signals.close().await(20, TimeUnit.SECONDS);
            held.release();
            PgWorkSignalsIT.until(() -> listenWire.since(mark).stream().anyMatch(s -> s.type() == 'X' && s.conn() == conn),
                    () -> "the late connection, its LISTEN acknowledged, closed by the client (Terminate): " + listenWire.since(mark));
        } finally {
            held.releaseQuietly();
        }
        assertEquals(1, claims("orders"), "nothing woken: " + queues);
        assertEquals(1, claims("invoices"));
    }

    /** A reconnect failing after the close arms nothing and logs nothing beyond the loss. */
    @Test
    void aReconnectFailingAfterTheCloseArmsNothing() throws Exception {
        int pending = rt.timer().pending();
        PgWire.Held held = reconnectHeldAtItsListen();
        try {
            signals.close().await(20, TimeUnit.SECONDS);
            held.cut();
            held.abort();
        } finally {
            held.releaseQuietly();
        }
        PgWorkSignalsIT.until(() -> db.count("select count(*) " + LISTENING) == 0, () -> "nothing listens");
        assertEquals(pending, rt.timer().pending(), "no reconnect armed after the close");
        assertEquals(1, warnings().size(), "the loss only: " + warnings());
    }

    /** The node's own close is no loss: no reconnect armed, nothing logged. */
    @Test
    void aCloseIsNoLoss() throws Exception {
        int pending = rt.timer().pending();
        signals.close().await(20, TimeUnit.SECONDS);
        PgWorkSignalsIT.until(() -> db.count("select count(*) " + LISTENING) == 0, () -> "the listener closed");
        assertEquals(pending, rt.timer().pending(), "no reconnect armed");
        assertEquals(List.of(), warnings(), "nothing logged");
    }

    @Test
    void aCloseWhileAReconnectIsArmedCancelsIt() throws Exception {
        int pending = rt.timer().pending();
        int mark = listenWire.mark();
        assertEquals(1, db.count("select count(pg_terminate_backend(pid)) " + LISTENING), "fixture: the listener terminated");
        PgWorkSignalsIT.until(() -> rt.timer().pending() == pending + 1, () -> "the reconnect armed");
        signals.close().await(20, TimeUnit.SECONDS);
        assertEquals(pending, rt.timer().pending(), "the close cancelled it");
        rt.clock().advance(Duration.ofSeconds(10));
        assertEquals(0, listens(mark), "no reconnect after the close");
        assertEquals(1, claims("orders"), "nothing woken");
    }
}
