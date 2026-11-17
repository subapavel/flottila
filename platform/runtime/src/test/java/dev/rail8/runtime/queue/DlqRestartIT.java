package dev.rail8.runtime.queue;

import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepResources;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.NackException;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.flow.FlowBuilder;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.FlowWorker;
import dev.rail8.core.flow.WorkerPolicy;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.Recorder;
import dev.rail8.core.record.RecordingPolicy;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.SharedPostgres;
import dev.rail8.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14b P8 (D-135; FIX-138's case) on a real Postgres: {@code PgWorkQueue.restart(id, flow, key, k)} deletes the message's DLQ
 * row and inserts its work row — same id, step 1, attempts k, {@code max_attempts} k + 1 — in one transaction. An injected failure
 * of either half (a trigger refusing the work insert; one refusing the DLQ delete: whichever order the two run in, one of them
 * fails after the other ran) leaves the DLQ row and no work row; a restart of a message without a DLQ row (a second restart)
 * writes nothing. Then the worker runs the restarted row once, as attempt 2, under the same id: a partner that applied the first
 * send — its answer lost — and deduplicates by message id sees one effect, where {@code /admin/dlq/{id}/replay}'s new copy would be a second (FIX-138a).
 *
 * <p>Seams: {@link PgWorkQueue} and {@link PgDlq} over one pool; the worker (flow {@code orders}, {@code delivery.retries} count 0:
 * the first failure parks at once, attempt 1, so k + 1 = 2 lies past the flow's bound of 1) with core's in-memory message store
 * and blob store; the partner a {@link RecordingComponent} at the SPI boundary. The worker is never started: each round is a
 * {@link FlowWorker#poll} whose future completes once its runs settled.
 *
 * <p>O7: the worker's park keeps the item's key ({@code dlq.partition_key}, V17; {@code Parked.partitionKey()}), null / empty for an
 * unkeyed item.
 *
 * <p>The API assumed: see {@code WorkQueueContract} ({@code WorkQueue.restart}, {@code Item.maxAttempts}); Postgres: false, nothing
 * written, without a DLQ row of the id.
 *
 * <p>FIX-275 (F-57, D-162 (5)), the designer's scenario: a park is two writes, the DLQ row and then the settle of the run's work
 * row W1; {@link Boundary} — the queue as the worker sees it, at the store boundary — fails that settle once, so W1 stays leased
 * beside the DLQ row, and the restart comes then, as {@code /retry} calls it ({@link #retry}). It is refused while W1 is there:
 * {@code dev.rail8.core.queue.UnsettledException} naming W1's seq, the DLQ row kept, one work row. Its two outcomes without the
 * guard: under a bound of 2 the retry's row delivers and W1, handed out again within its bound, delivers too; under a bound of 1
 * W1 comes back past its bound and parks the delivered message again. W1's lease runs on the database clock, so it is moved past
 * by an update of its {@code locked_until} ({@link #expireLease}), never by the fake clock. The flow is unkeyed. The refusal is
 * read by its class name and reflectively ({@link #unsettledSeq}), so this class compiles before the type exists.
 */
@Timeout(120)
class DlqRestartIT {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final String NODE = "node-a";
    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    PgWorkQueue rows;
    PgDlq dlq;
    FlowWorker worker;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        pool.query("create function refuse_test() returns trigger language plpgsql as $$ begin raise exception 'injected failure (test)'; end $$")
                .execute().await();
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() {
        pool.query("truncate work, dlq, body_refs, journal_segments").execute().await();
        rt.withComponent(partner);
        rows = new PgWorkQueue(pool, NODE);
        dlq = new PgDlq(pool);
        Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        worker = new FlowBuilder(rt.runtime(), List.of(), List.of(), endpoints).recording(store, Meter.system()).queue(rows).dlq(dlq)
                .worker(flow(), Path.of(""), NODE, new WorkerPolicy(LEASE, Duration.ofDays(7)));
    }

    @AfterEach
    void close() throws Exception {
        pool.query("drop trigger if exists refuse_insert on work").execute().await();
        pool.query("drop trigger if exists refuse_delete on dlq").execute().await();
        worker.stop();
        worker.close().await(20, TimeUnit.SECONDS);
    }

    /** At-least-once {@code orders}: one {@code to partner}; {@code delivery.retries} count 0. */
    static FlowDefinition flow() {
        return flow(0);
    }

    /** At-least-once {@code orders}: one {@code to partner}; {@code delivery.retries} count {@code retries} — a bound of retries + 1 hand-outs. */
    static FlowDefinition flow(int retries) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h", "retries", Map.of("count", retries, "interval", "1m")));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("steps", List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        return FlowDefinition.from(doc);
    }

    /** A message of {@code orders}: its ingress recorded, its body's kept ref on {@link #NODE}, its row queued. */
    Envelope queued() {
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        ParkedRows.keepRef(pool, in.id(), NODE);
        rows.enqueue(in.id(), "orders", 1, null).await();
        return in;
    }

    long workRows() {
        return ParkedRows.count(pool, "select count(*) from work");
    }

    Optional<Parked> dlqRow(String id) {
        return dlq.get(id).await();
    }

    /** {@code rows.restart(id, orders, null, k)} fails: the injected failure, nothing changed. */
    void refused(Envelope in, Parked before, String what) {
        Throwable failed = assertThrows(Throwable.class, () -> rows.restart(in.id(), "orders", null, 1).await(20, TimeUnit.SECONDS), what);
        assertTrue(String.valueOf(failed.getMessage()).contains("injected failure"), what + ": the restart failed with the injected failure: " + failed);
        assertEquals(Optional.of(before), dlqRow(in.id()), what + ": the DLQ row stays as it was");
        assertEquals(0, workRows(), what + ": no work row");
    }

    @Test
    void restartIsOneTransactionWithTheSameId() throws Exception {
        partner.failNext(1, () -> new NackException("applied, its answer lost (test)", true));
        Envelope in = queued();
        assertEquals(1, worker.poll().await(20, TimeUnit.SECONDS), "fixture: the first run");
        Parked before = dlqRow(in.id()).orElseThrow(() -> new AssertionError("count 0: the failure parks at once"));
        assertEquals(1, before.step(), "fixture: the worker parks the ingress at step 1");
        assertEquals(Optional.of(1), before.props().get(Envelope.ATTEMPT), "fixture: after attempt 1");
        assertEquals(0, workRows(), "fixture: its row completed by the park");
        assertEquals(1, partner.sent().size());
        assertEquals(Optional.empty(), before.partitionKey(), "O7: an unkeyed item's park keeps no key");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from dlq where id = $1 and partition_key is null", in.id()), "O7: dlq.partition_key null");

        pool.query("create trigger refuse_insert before insert on work for each row execute function refuse_test()").execute().await();
        refused(in, before, "the work insert refused");
        pool.query("drop trigger refuse_insert on work").execute().await();
        pool.query("create trigger refuse_delete before delete on dlq for each row execute function refuse_test()").execute().await();
        refused(in, before, "the DLQ delete refused");
        pool.query("drop trigger refuse_delete on dlq").execute().await();

        assertTrue(rows.restart(in.id(), "orders", null, 1).await(20, TimeUnit.SECONDS), "restarted");
        assertEquals(Optional.empty(), dlqRow(in.id()), "its DLQ row is gone");
        List<Row> written = new java.util.ArrayList<>();
        for (Row r : pool.query("select id, step, attempts, max_attempts from work").execute().await()) written.add(r);
        assertEquals(1, written.size(), "one work row");
        Row row = written.get(0);
        assertEquals(Arrays.asList(in.id(), 1, 1, 2), Arrays.asList(row.getString(0), row.getInteger(1), row.getInteger(2), row.getInteger(3)),
                "same id, step 1, handed out k = 1 times, max_attempts k + 1 = 2");
        assertFalse(rows.restart(in.id(), "orders", null, 1).await(20, TimeUnit.SECONDS), "no DLQ row any more: a second restart writes nothing");
        assertEquals(1, workRows(), "still one work row");

        assertEquals(1, worker.poll().await(20, TimeUnit.SECONDS), "the restarted row is claimed");
        assertEquals(2, partner.sent().size(), "sent once more");
        assertEquals(List.of(in.id(), in.id()), partner.sent().stream().map(Envelope::id).toList(), "under the same message id");
        assertEquals(Optional.of(2), partner.sent().get(1).get(Envelope.ATTEMPT), "as attempt k + 1 = 2, past the flow's bound of 1");
        assertEquals(1, partner.sent().stream().map(Envelope::id).distinct().count(), "a partner deduplicating by message id: one effect");
        assertEquals(0, workRows(), "the restarted run succeeded: completed");
        assertEquals(Optional.empty(), dlqRow(in.id()), "not parked again");
    }

    /** The test's {@code header} language: an expression is a header name, its value the header's (core's BuiltInStepsTest.HEADERS). */
    static final Expressions HEADER = new Expressions() {
        @Override public String language() { return "header"; }
        @Override public Predicate<Envelope> predicate(String expression, StepResources resources) {
            throw new UnsupportedOperationException("no conditions in this test");
        }
        @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) {
            return e -> e.header(expression);
        }
    };

    /** At-least-once {@code orders} keyed by {@code customer} (header {@code x-customer}), onFailure park; count 0. */
    static FlowDefinition keyedFlow() {
        Map<String, Object> doc = new HashMap<>(Map.of("schemaVersion", 1, "kind", "flow", "id", "orders", "version", "0.1.0"));
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h", "retries", Map.of("count", 0, "interval", "1m")));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("partitionBy", "customer");
        doc.put("onFailure", "park");
        doc.put("steps", List.of(Map.of("type", "extractKey", "name", "key", "language", "header", "key", "customer", "xpath", "x-customer"),
                Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        return FlowDefinition.from(doc);
    }

    /**
     * O7: the worker's park of a keyed item writes the item's key into {@code dlq.partition_key}, and {@code Parked.partitionKey()}
     * reads it back; the restart under it writes the work row under that key.
     */
    @Test
    void theWorkersParkKeepsTheItemsKey() throws Exception {
        worker.stop();
        Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        worker = new FlowBuilder(rt.runtime(), List.of(), List.of(HEADER), endpoints).recording(store, Meter.system()).queue(rows).dlq(dlq)
                .worker(keyedFlow(), Path.of(""), NODE, new WorkerPolicy(LEASE, Duration.ofDays(7)));
        partner.failNext(1, () -> new NackException("partner down (test)", true));
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml").with("x-customer", "c1"),
                Buffer.buffer("<order id=\"1\"/>"));
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        ParkedRows.keepRef(pool, in.id(), NODE);
        rows.enqueue(in.id(), "orders", 1, "c1").await();

        assertEquals(1, worker.poll().await(20, TimeUnit.SECONDS), "fixture: the keyed item's run");
        assertEquals(1, partner.sent().size(), "fixture: sent once and failed");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from dlq where id = $1 and partition_key = 'c1'", in.id()),
                "the park wrote the item's key c1");
        assertEquals(Optional.of("c1"), dlqRow(in.id()).orElseThrow().partitionKey(), "and the DLQ row reads it back");

        assertTrue(rows.restart(in.id(), "orders", "c1", 1).await(20, TimeUnit.SECONDS), "restarted under its key");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work where id = $1 and partition_key = 'c1' and max_attempts = 2", in.id()),
                "the work row under the kept key");
    }

    // --- FIX-275 (F-57, D-162 (5)): a retry before the park's settle landed

    /** The type the refusal is, by name: it does not exist before FIX-275. */
    static final String UNSETTLED = "dev.rail8.core.queue.UnsettledException";

    /** What the worker's queue settles a row with: every one of them is a settle the boundary may fail. */
    static final Set<String> SETTLES = Set.of("complete", "completeRow", "completeUnkeyed", "completeAndTakeNext");

    /**
     * {@link #rows} as the worker sees it — the store boundary: every claim's items recorded once answered, and the first settle it
     * is asked for failed once without reaching the queue (the park's second write did not land), its seq recorded.
     */
    final class Boundary {
        final List<WorkQueue.Item> claimed = new CopyOnWriteArrayList<>();
        final List<Long> failedSettles = new CopyOnWriteArrayList<>();
        final AtomicBoolean failNextSettle = new AtomicBoolean(true);
        @SuppressWarnings("unchecked")
        final WorkQueue queue = (WorkQueue) Proxy.newProxyInstance(WorkQueue.class.getClassLoader(), new Class<?>[] {WorkQueue.class}, (p, m, args) -> {
            if (SETTLES.contains(m.getName()) && failNextSettle.compareAndSet(true, false)) {
                failedSettles.add((Long) args[0]);
                return Future.failedFuture(new IllegalStateException("the settle of work item " + args[0] + " did not land (test)"));
            }
            Object answer;
            try {
                answer = m.invoke(rows, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (m.getName().equals("claim")) ((Future<List<WorkQueue.Item>>) answer).onSuccess(claimed::addAll);
            return answer;
        });

        /** The attempts of each hand-out of {@code seq}, in claim order. */
        List<Integer> handOuts(long seq) {
            return claimed.stream().filter(i -> i.seq() == seq).map(WorkQueue.Item::attempts).toList();
        }
    }

    /** F-57's state: the message, the seq of its work row W1 left leased, the DLQ row its park wrote. */
    record Unsettled(Envelope in, long w1, Parked parked) {}

    /** What a restart answered: {@code restarted} when it completed, else the failure it failed with. */
    record Answer(Boolean restarted, Throwable refused) {}

    final List<Envelope> accepted = new CopyOnWriteArrayList<>();
    Boundary boundary;

    /**
     * The scenario up to the retry (the designer's A, steps 1 and 2): the worker of {@code orders} with {@code retries} retries over
     * a {@link Boundary}; the partner refuses the first send for good (a final NACK), so the worker parks the message at attempt 1
     * whatever its bound; the boundary fails that item's settle, so W1 stays leased to {@link #NODE} beside the DLQ row. Every send
     * the partner accepts is recorded in {@link #accepted}.
     */
    Unsettled parkedButNotSettled(int retries) throws Exception {
        worker.stop();
        worker.close().await(20, TimeUnit.SECONDS);
        boundary = new Boundary();
        Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        worker = new FlowBuilder(rt.runtime(), List.of(), List.of(), endpoints).recording(store, Meter.system()).queue(boundary.queue).dlq(dlq)
                .worker(flow(retries), Path.of(""), NODE, new WorkerPolicy(LEASE, Duration.ofDays(7)));
        partner.failNext(1, () -> new NackException("refused for good (test)", false));
        partner.replyWith(e -> {
            accepted.add(e);
            return e;
        });
        Envelope in = queued();

        Throwable round = assertThrows(Throwable.class, () -> worker.poll().await(20, TimeUnit.SECONDS), "fixture: the round fails with its settle");
        assertTrue(String.valueOf(round.getMessage()).contains("did not land (test)"), "fixture: the injected settle failure: " + round);
        assertEquals(1, boundary.claimed.size(), "fixture: one hand-out: " + boundary.claimed);
        long w1 = boundary.claimed.get(0).seq();
        assertEquals(List.of(w1), boundary.failedSettles, "fixture: W1's settle failed");
        Parked parked = dlqRow(in.id()).orElseThrow(() -> new AssertionError("fixture: the final NACK parked the message"));
        assertEquals(Optional.of(1), parked.props().get(Envelope.ATTEMPT), "fixture: parked at attempt 1");
        assertEquals(List.of(w1), workSeqs(in.id()), "fixture: W1 is the id's only work row");
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work where seq = $1 and locked_by = $2 and locked_until > now()", w1, NODE),
                "fixture: W1 stays leased to its node beside the DLQ row");
        assertEquals(List.of(in.id()), partner.sent().stream().map(Envelope::id).toList(), "fixture: sent once");
        assertEquals(List.of(), accepted, "fixture: refused, nothing accepted yet");
        return new Unsettled(in, w1, parked);
    }

    /** The restart of {@code id} as {@code /retry} calls it: the DLQ row's flow, its kept key and its attempt k, read first. */
    Answer retry(String id) {
        Parked row = dlqRow(id).orElseThrow(() -> new AssertionError("no DLQ row of " + id + " to retry"));
        int k = row.props().get(Envelope.ATTEMPT).orElseThrow(() -> new AssertionError("the DLQ row of " + id + " has no attempt"));
        try {
            return new Answer(rows.restart(id, row.flow(), row.partitionKey().orElse(null), k).await(20, TimeUnit.SECONDS), null);
        } catch (Throwable failed) {
            return new Answer(null, failed);
        }
    }

    /** The first retry of an outcome case: answered true (today) or refused as unsettled; any other answer fails the case. */
    static void tolerated(Answer first) {
        boolean refused = first.refused() != null && UNSETTLED.equals(first.refused().getClass().getName());
        if (!Boolean.TRUE.equals(first.restarted()) && !refused) {
            throw new AssertionError("the first retry answers true or is refused as unsettled: " + first, first.refused());
        }
    }

    /** {@code failed} is the unsettled refusal of {@code messageId}: its {@code seq()}, read reflectively (the type is new). */
    static long unsettledSeq(Throwable failed, String messageId) throws ReflectiveOperationException {
        assertEquals(UNSETTLED, failed == null ? "no failure" : failed.getClass().getName(), "refused as unsettled: " + failed);
        assertEquals(messageId, failed.getClass().getMethod("messageId").invoke(failed), "the refusal names the message");
        return (Long) failed.getClass().getMethod("seq").invoke(failed);
    }

    /** The seqs of {@code id}'s work rows, in seq order. */
    List<Long> workSeqs(String id) {
        List<Long> seqs = new java.util.ArrayList<>();
        for (Row r : pool.preparedQuery("select seq from work where id = $1 order by seq").execute(Tuple.of(id)).await()) seqs.add(r.getLong(0));
        return seqs;
    }

    /** W1's lease passes on the database clock — the claim compares {@code locked_until} with {@code now()} —, never on the fake one. */
    void expireLease(long seq) {
        long moved = pool.preparedQuery("update work set locked_until = now() - interval '1 second' where seq = $1").execute(Tuple.of(seq)).await().rowCount();
        assertEquals(1, moved, "fixture: W1's lease moved past");
    }

    /** Rounds of the worker until one claims nothing, each awaited until its runs settled. */
    void runUntilNothingIsLeft() throws Exception {
        for (int round = 0; round < 10; round++) {
            if (worker.poll().await(20, TimeUnit.SECONDS) == 0) return;
        }
        throw new AssertionError("still claiming after 10 rounds: " + boundary.claimed);
    }

    long acceptedOf(String id) {
        return accepted.stream().filter(e -> e.id().equals(id)).count();
    }

    /** Acceptance 1: the retry while W1 is unsettled is refused, writes nothing and names W1. Today: true, the DLQ row gone, two rows. */
    @Test
    void aRetryBeforeTheParksSettleLandedIsRefused() throws Exception {
        Unsettled s = parkedButNotSettled(1);   // a bound of 2 hand-outs
        String id = s.in().id();
        Answer answer = retry(id);
        assertAll("a retry beside W1, whose settle did not land",
                () -> assertEquals(s.w1(), unsettledSeq(answer.refused(), id), "refused as unsettled, naming W1's seq; answered " + answer),
                () -> assertEquals(Optional.of(s.parked()), dlqRow(id), "the DLQ row is kept"),
                () -> assertEquals(List.of(s.w1()), workSeqs(id), "the id has exactly one work row, W1"));
    }

    /**
     * Acceptance 2, a bound of 2: after the retry the partner accepts, W1's lease passes, and every row runs. The partner accepts the
     * message once. Today twice: the retry's row delivers, and W1, handed out again as attempt 2 within its bound, delivers too.
     */
    @Test
    void aMessageRetriedBeforeItsParkSettledIsAcceptedByThePartnerOnce() throws Exception {
        Unsettled s = parkedButNotSettled(1);   // a bound of 2 hand-outs
        String id = s.in().id();
        tolerated(retry(id));
        runUntilNothingIsLeft();
        expireLease(s.w1());
        runUntilNothingIsLeft();

        assertEquals(List.of(1, 2), boundary.handOuts(s.w1()), "W1 was claimed again, as attempt 2 of its bound of 2");
        assertEquals(List.of(), workSeqs(id), "fixture: every row of the id has run");
        assertEquals(1, acceptedOf(id), "the partner accepted the message exactly once; sent " + partner.sent().stream()
                .map(e -> e.id() + "@" + e.get(Envelope.ATTEMPT).orElse(1)).toList());
    }

    /**
     * Acceptance 2, a bound of 1: after the retry the partner accepts, W1's lease passes, every row runs, and — only when the first
     * retry was refused — a second retry runs once W1 settled. The message is accepted once and is in no list: no DLQ row, no work row.
     * Today the retry's row delivers, and W1, handed out again past its bound, parks the delivered message again, unsent.
     */
    @Test
    void aMessageRetriedBeforeItsParkSettledDoesNotComeBackAfterItsDelivery() throws Exception {
        Unsettled s = parkedButNotSettled(0);   // a bound of 1 hand-out
        String id = s.in().id();
        Answer first = retry(id);
        tolerated(first);
        runUntilNothingIsLeft();
        expireLease(s.w1());
        runUntilNothingIsLeft();
        assertEquals(List.of(1, 2), boundary.handOuts(s.w1()), "W1 was claimed again, as attempt 2: past its bound of 1");
        if (first.refused() != null) {
            Answer second = retry(id);
            assertEquals(Boolean.TRUE, second.restarted(), "once W1 settled, the retry goes through: " + second);
            runUntilNothingIsLeft();
        }

        assertAll("once every row has run",
                () -> assertEquals(1, acceptedOf(id), "the partner accepted the message exactly once"),
                () -> assertEquals(Optional.empty(), dlqRow(id), "the failed list has no row of the delivered message"),
                () -> assertEquals(List.of(), workSeqs(id), "the id has no work row"));
    }
}
