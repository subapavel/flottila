package dev.rail8.runtime.journal;

import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Keys;
import dev.rail8.api.Props;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.runtime.blob.TieredBlobStore;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.runtime.store.Retention;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.rail8.runtime.journal.JournalWorld.bodyRef;
import static dev.rail8.runtime.journal.JournalWorld.bytes;
import static dev.rail8.runtime.journal.JournalWorld.causes;
import static dev.rail8.runtime.journal.JournalWorld.count;
import static dev.rail8.runtime.journal.JournalWorld.opened;
import static dev.rail8.runtime.journal.JournalWorld.settled;
import static dev.rail8.runtime.journal.JournalWorld.undecided;
import static dev.rail8.runtime.journal.JournalWorld.workRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call between flows hands the callee the caller's body, still keyed by the caller's message. An at-least-once callee's save
 * stores a reference to that body, never a copy: the body's kept ref ({@code body_refs}, keyed by the caller's message) is
 * written in the save's transaction, and the callee's work row points at the same place in the journal. A body still local to
 * the node (in memory, or a spill) is made durable first; a durable one gets its kept ref without a byte copied. The callee
 * reads its body by the caller's key from then on, whatever the caller does: it may finish first, and the node may restart.
 * Retention keeps a kept ref while any work row points at its place, and a parked callee keeps it through its DLQ row; once
 * nothing refers to the body any more, retention removes the kept ref and the segment sweeper frees the segment.
 *
 * <p>No {@code direct} component exists yet: each case builds the callee's arrival itself, an ingress envelope of the callee's
 * id carrying the caller's {@link Body}, and saves it through the node's intake.
 */
@Timeout(240)
class CalleeBodyIT {

    static final SharedPostgres pg = SharedPostgres.database();
    static final String CALLER_FLOW = "orders";
    static final String CALLEE_FLOW = "fulfil";
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration MAX_AGE = Duration.ofDays(7);
    static final SweepConfig SWEEP = new SweepConfig(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofHours(1));
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    /** The node's statements cross this relay, which can hold or cut a COMMIT; the cases' own reads do not. */
    PgWire wire;
    Pool relayed;
    JournalWorld a;
    /** Retention's clock: far ahead of the server's, so every row the case wrote is older than the cutoff. */
    FakeClock later;

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
        db = CaseDatabase.create(pg, vertx, "callee_body_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        relayed = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        a = world();
        later = FakeClock.at(Instant.now().plus(Duration.ofDays(400)).truncatedTo(ChronoUnit.SECONDS));
    }

    @AfterEach
    void close() throws Exception {
        if (a != null) a.close();
        wire.close();
        relayed.close().await();
        db.close();
    }

    JournalWorld world() throws Exception {
        return JournalWorld.open(vertx, relayed, db.pool(), Files.createDirectories(tmp.resolve("node-a")), "node-a");
    }

    // --- the caller and the callee

    /** The caller's at-least-once arrival, saved: its body durable, named by its work row only (its flow keeps no input). */
    Envelope callerSaved(String id, int seed) {
        Envelope in = a.arrive(id, bytes(3_000, seed));
        Future<String> saved = settled(a.intake.save(in, JournalWorld.ingress(in), CALLER_FLOW, null, false, undecided()));
        assertTrue(saved.succeeded(), "fixture: the caller's arrival saved " + (saved.failed() ? causes(saved.cause()) : ""));
        assertNull(bodyRef(db.pool(), in.body().key()), "fixture: the caller keeps no input, so no kept ref names its arrival");
        return in;
    }

    /** {@code body} handed to the callee {@code calleeId}: its arrival saved at least once, its flow keeping no input. */
    Future<String> handOver(Body body, String calleeId) {
        return settled(startHandOver(body, calleeId));
    }

    /** {@link #handOver}, not waited for. */
    Future<String> startHandOver(Body body, String calleeId) {
        Envelope callee = Envelope.ingress(calleeId, CALLEE_FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
        return a.intake.save(callee, JournalWorld.ingress(callee), CALLEE_FLOW, null, false, undecided());
    }

    void handedOver(Body body, String calleeId) {
        Future<String> saved = handOver(body, calleeId);
        assertTrue(saved.succeeded(), "the callee " + calleeId + " is saved: " + (saved.failed() ? causes(saved.cause()) : ""));
        assertEquals(calleeId, saved.result(), "the save answers the callee's own id");
    }

    /**
     * {@code id}'s work item of {@code flow} claimed and completed through a queue that tells {@code store} (its index forgets);
     * the other items the claim took are given back at once (deferred by no delay), so a later claim takes them again.
     */
    void completed(TieredBlobStore store, String flow, String id) {
        PgWorkQueue queue = new PgWorkQueue(relayed, a.node, store);
        List<WorkQueue.Item> items = queue.claim("node-a", flow, 16, LEASE).await();
        WorkQueue.Item item = items.stream().filter(i -> i.messageId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("fixture: " + id + "'s item claimed, among " + items));
        assertTrue(queue.complete(item.seq(), item.attempts()).await(), "fixture: " + id + "'s item completed");
        for (WorkQueue.Item other : items) {
            if (other != item) assertTrue(queue.defer(other.seq(), other.attempts(), Duration.ZERO).await(), "fixture: " + other.messageId() + "'s item given back");
        }
    }

    void readable(TieredBlobStore store, BlobKey key, Buffer expected, String what) {
        Future<Buffer> read = opened(store, key);
        assertTrue(read.succeeded(), what + ": " + key + " is read " + (read.failed() ? causes(read.cause()) : ""));
        assertEquals(expected, read.result(), what + ": the bytes the caller handed over");
    }

    Retention.Run retention() {
        Retention r = new Retention(db.pool(), later, new FakeTimer(later), new Retention.Config(MAX_AGE, Duration.ofHours(1), 1000));
        try {
            Retention.Run run = r.runOnce().await();
            assertEquals(Retention.Outcome.DONE, run.outcome(), "the retention run ends done");
            return run;
        } finally {
            r.close().await();
        }
    }

    SegmentSweeper sweeper() {
        return new SegmentSweeper(a.journal, new PgSegmentRows(db.pool()), a.intake::openSaves, SWEEP, a.opened.clock(), new FakeTimer(FakeClock.at(Instant.EPOCH)));
    }

    /** One pass of a sweeper of node a, past the grace of segment {@code n} (sealed first): the segments it swept. */
    Set<Long> sweptPastTheGrace(long n) throws Exception {
        SegmentSweepIT.seal(a, n);
        a.opened.clock().advance(SWEEP.grace());
        try (SegmentSweeper s = sweeper()) {
            Future<Set<Long>> pass = settled(s.sweep());
            assertTrue(pass.succeeded(), "a pass completes: " + (pass.failed() ? causes(pass.cause()) : ""));
            return pass.result();
        }
    }

    long refs(BlobKey key) {
        return count(db.pool(), "select count(*) from body_refs where message_id = $1 and step = $2 and part = $3", key.messageId(), key.step(), key.part());
    }

    // --- the cases

    @Test
    void aDurableCallerArrivalIsReferencedWithoutACopy() {
        Envelope caller = callerSaved("m-caller", 1);
        BlobKey key = caller.body().key();
        JournalWorld.Ref place = workRef(db.pool(), "m-caller");

        handedOver(caller.body(), "m-callee");

        assertEquals(place, bodyRef(db.pool(), key), "the save wrote the kept ref of the caller's key, naming the record the caller's item names");
        assertEquals(place, workRef(db.pool(), "m-callee"), "the callee's item points at the same place: no byte was copied");
        assertEquals(1, count(db.pool(), "select count(*) from messages where id = 'm-callee' and step = 0"), "the callee's arrival is recorded");
        readable(a.restartedStore(), key, bytes(3_000, 1), "by key, with no location index");
    }

    @Test
    void theCallerFinishingFirstLeavesTheCalleeItsBody() {
        Envelope caller = callerSaved("m-caller", 2);
        handedOver(caller.body(), "m-callee");

        completed(a.blobs, CALLER_FLOW, "m-caller");

        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-caller'"), "fixture: the caller is done");
        readable(a.blobs, caller.body().key(), bytes(3_000, 2), "the callee's read after the caller finished");
        readable(a.restartedStore(), caller.body().key(), bytes(3_000, 2), "the same read with no location index");
    }

    @Test
    void aCallerBodyInTheMemoryTierIsMadeDurableAndKept() {
        BlobKey key = BlobKey.body("m-sync", 2);   // the body after a sync caller's second step, held in memory only
        Body body = settled(a.blobs.write(key, bytes(3_000, 3), JournalWorld.MAX)).result();
        assertEquals(TieredBlobStore.Location.MEMORY, a.blobs.location(key).orElseThrow(), "fixture: the caller's body is in the memory tier");

        handedOver(body, "m-callee");

        assertTrue(a.blobs.location(key).isEmpty(), "the body is durable now: its local copy is gone");
        JournalWorld.Ref kept = bodyRef(db.pool(), key);
        assertNotNull(kept, "the save kept a ref of the caller's key");
        assertEquals(kept, workRef(db.pool(), "m-callee"), "the callee's item points at the kept ref's place");
        readable(a.blobs, key, bytes(3_000, 3), "the caller goes on with its body");

        completed(a.blobs, CALLEE_FLOW, "m-callee");

        readable(a.blobs, key, bytes(3_000, 3), "after the callee's item completed");
        readable(a.restartedStore(), key, bytes(3_000, 3), "with no location index");
    }

    @Test
    void aSpilledCallerBodyIsMadeDurableAndKept() {
        BlobKey key = BlobKey.body("m-sync", 1);
        Buffer large = bytes(300_000, 4);   // above the journal's record size: a spill of its own
        Body body = settled(a.blobs.write(key, large, JournalWorld.MAX)).result();
        assertEquals(TieredBlobStore.Location.SPILL, a.blobs.location(key).orElseThrow(), "fixture: the caller's body is a spill");

        handedOver(body, "m-callee");

        assertTrue(a.blobs.location(key).isEmpty(), "the spill is durable now");
        assertEquals(bodyRef(db.pool(), key), workRef(db.pool(), "m-callee"), "the kept ref and the callee's item name one place");
        readable(a.restartedStore(), key, large, "by key, with no location index");
    }

    @Test
    void theCalleeReadsItsBodyByKeyAfterANodeRestart() throws Exception {
        BlobKey key = BlobKey.body("m-sync", 2);
        Body body = settled(a.blobs.write(key, bytes(3_000, 5), JournalWorld.MAX)).result();
        handedOver(body, "m-callee");

        a.close();
        a = null;
        a = world();   // the node starts again over its journal: an empty location index, no local copy

        readable(a.blobs, key, bytes(3_000, 5), "after the restart");
        assertEquals(1, count(db.pool(), "select count(*) from work where id = 'm-callee'"), "the callee's item waits for its run");
    }

    @Test
    void twoCalleesSavingOneDurableBodyAtOnceBothReferToIt() throws Exception {
        Envelope caller = callerSaved("m-caller", 8);
        JournalWorld.Ref place = workRef(db.pool(), "m-caller");
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> first = startHandOver(caller.body(), "m-callee-1");
        commit.awaitReached();   // the first save's kept ref is written, its transaction not yet committed
        Future<String> second = startHandOver(caller.body(), "m-callee-2");
        SegmentSweepIT.until(() -> count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'") > 0,
                () -> "the second save waits for the first one's kept ref");

        commit.release();

        for (Future<String> save : List.of(first, second)) {
            assertTrue(settled(save).succeeded(), "both saves succeed: " + (save.failed() ? causes(save.cause()) : ""));
        }
        assertEquals(place, workRef(db.pool(), "m-callee-1"), "the first callee's item points at the caller's place");
        assertEquals(place, workRef(db.pool(), "m-callee-2"), "and so does the second's");
        assertEquals(1, refs(caller.body().key()), "one kept ref");
    }

    @Test
    void aSavedBodyWhoseCommitReplyWasLostStaysOpenUntilItsOutcomeIsKnown() throws Exception {
        BlobKey key = BlobKey.body("m-sync", 2);
        Body body = settled(a.blobs.write(key, bytes(3_000, 9), JournalWorld.MAX)).result();
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> save = startHandOver(body, "m-callee");
        commit.awaitReached();
        commit.cut();
        assertTrue(settled(save).failed(), "fixture: the save whose commit reply was lost fails");

        assertEquals(1, List.copyOf(a.intake.openSaves()).size(), "its outcome unknown: the record it forced stays an open save, kept from the sweeper");
        assertTrue(a.blobs.location(key).isPresent(), "its body stays local meanwhile");

        commit.commitAfterCut();
        SegmentSweepIT.until(() -> count(db.pool(), "select count(*) from work where id = 'm-callee'") == 1
                        && count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and state like 'idle in transaction%'") == 0,
                () -> "the COMMIT visible and its session gone");

        assertEquals(List.of(), List.copyOf(a.intake.openSaves()), "the commit landed: no open save left");
        assertTrue(a.blobs.location(key).isEmpty(), "committed: the local copy is gone");
        assertEquals(bodyRef(db.pool(), key), workRef(db.pool(), "m-callee"), "the kept ref and the callee's item name one place");
        readable(a.restartedStore(), key, bytes(3_000, 9), "by key, with no location index");
    }

    @Test
    void retentionKeepsTheReferenceWhileAnyWorkRowPointsAtItsPlace() throws Exception {
        Envelope caller = callerSaved("m-caller", 6);
        BlobKey key = caller.body().key();
        JournalWorld.Ref place = workRef(db.pool(), "m-caller");
        handedOver(caller.body(), "m-callee-1");
        handedOver(caller.body(), "m-callee-2");   // the caller's retry hands its message over again
        assertEquals(place, workRef(db.pool(), "m-callee-2"), "both callees' items point at the one kept ref's place");
        assertEquals(1, refs(key), "one kept ref");

        completed(a.blobs, CALLER_FLOW, "m-caller");
        completed(a.blobs, CALLEE_FLOW, "m-callee-1");
        retention();

        assertEquals(0, count(db.pool(), "select count(*) from messages where id = 'm-caller'"), "fixture: the caller's history is gone by age");
        assertEquals(1, refs(key), "the kept ref stays: the second callee's item still points at its place");
        readable(a.restartedStore(), key, bytes(3_000, 6), "the second callee's read");

        completed(a.blobs, CALLEE_FLOW, "m-callee-2");
        retention();

        assertEquals(0, refs(key), "nothing points at the place any more: the kept ref goes");
        assertEquals(Set.of(place.segment()), sweptPastTheGrace(place.segment()), "the sweeper frees what nothing refers to");
    }

    @Test
    void aParkedCalleeKeepsItsBody() throws Exception {
        Envelope caller = callerSaved("m-caller", 7);
        BlobKey key = caller.body().key();
        JournalWorld.Ref place = workRef(db.pool(), "m-caller");
        handedOver(caller.body(), "m-callee");

        Parked parked = new Parked("m-callee", CALLEE_FLOW, 1, "partner", "java.lang.IllegalStateException: 503 from partner", Instant.now(),
                Headers.empty(), Props.empty(), Keys.empty(), caller.body().hash(), caller.body().size(), key, Map.of("flow", "fulfil@0.1.0"));
        a.dlq.park(parked).await();
        completed(a.blobs, CALLEE_FLOW, "m-callee");
        completed(a.blobs, CALLER_FLOW, "m-caller");
        retention();

        assertEquals(1, count(db.pool(), "select count(*) from dlq where id = 'm-callee'"), "the callee is parked");
        assertEquals(place, bodyRef(db.pool(), key), "its DLQ row keeps the kept ref");
        readable(a.restartedStore(), key, bytes(3_000, 7), "the parked callee's body");
        assertEquals(Set.of(), sweptPastTheGrace(place.segment()), "the sweeper keeps the segment of a parked body");
    }
}
