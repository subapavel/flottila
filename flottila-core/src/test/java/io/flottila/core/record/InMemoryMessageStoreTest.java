package io.flottila.core.record;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-memory MessageStore (tests, bench, the CLI's dry run): the same contract the Postgres one keeps. */
class InMemoryMessageStoreTest {

    final InMemoryMessageStore store = new InMemoryMessageStore();

    static Snapshot snap(String id, int step, String name, Keys keys) {
        return new Snapshot(id, "f", step, name, Snapshot.Outcome.OK, Optional.empty(), Instant.EPOCH.plusSeconds(step), 1_000L * step, 10L * step,
                Headers.empty().with("h", "v" + step), Props.empty(), keys, new Hash("sha256-64", 7L + step), 42, Optional.of(BlobKey.body(id, step)), Map.of("flow", "f@1"));
    }

    @Test
    void theTimelineIsOrderedByStepAndTheFirstRowPerStepWins() {
        store.record(snap("m-1", 2, "to", Keys.empty())).await();
        store.record(snap("m-1", 0, "ingress", Keys.empty())).await();
        store.record(snap("m-1", 1, "validate", Keys.empty().with("invoiceId", "4711"))).await();
        store.record(snap("m-1", 1, "validate-again", Keys.empty())).await();
        store.record(snap("m-2", 0, "ingress", Keys.empty())).await();
        List<Snapshot> timeline = store.timeline("m-1").await();
        assertEquals(List.of(0, 1, 2), timeline.stream().map(Snapshot::step).toList());
        assertEquals("validate", timeline.get(1).stepName(), "a second snapshot for the same (id, step) is ignored, like ON CONFLICT DO NOTHING");
        assertEquals(List.of(), store.timeline("nobody").await());
        assertEquals(4, store.size());
    }

    @Test
    void findByKeyReturnsEveryMessageOnceInRecordingOrder() {
        store.record(snap("m-1", 1, "k", Keys.empty().with("invoiceId", "4711"))).await();
        store.record(snap("m-2", 1, "k", Keys.empty().with("invoiceId", "4712"))).await();
        store.record(snap("m-3", 1, "k", Keys.empty().with("invoiceId", "4711").with("orderNo", "9"))).await();
        store.record(snap("m-1", 2, "k2", Keys.empty().with("invoiceId", "4711"))).await();
        assertEquals(List.of("m-1", "m-3"), store.findByKey("invoiceId", "4711", Optional.empty(), Page.MAX_LIMIT).await().items());
        assertEquals(List.of("m-3"), store.findByKey("orderNo", "9", Optional.empty(), Page.MAX_LIMIT).await().items());
        assertEquals(List.of(), store.findByKey("invoiceId", "0", Optional.empty(), Page.MAX_LIMIT).await().items());
        assertThrows(NullPointerException.class, () -> store.record(null));
    }

    static Page<String> page(InMemoryMessageStore store, String value, Optional<String> after, int limit) {
        return store.findByKey("invoiceId", value, after, limit).await();
    }

    @Test
    void aMessageRecordedAtManyStepsIsFoundOnce() {
        for (int step = 0; step < 5; step++) store.record(snap("m-1", step, "s" + step, Keys.empty().with("invoiceId", "4711"))).await();
        store.record(snap("m-2", 1, "s1", Keys.empty().with("invoiceId", "4711"))).await();
        store.record(snap("m-1", 7, "s7", Keys.empty().with("invoiceId", "4711"))).await();
        assertEquals(List.of("m-1", "m-2"), page(store, "4711", Optional.empty(), Page.MAX_LIMIT).items(), "once each, in the order each first carried the key");
        Page<String> first = page(store, "4711", Optional.empty(), 1);
        assertEquals(List.of("m-1"), first.items());
        Page<String> second = page(store, "4711", first.next(), 1);
        assertEquals(List.of("m-2"), second.items(), "m-1's later steps do not come back on a later page");
        assertEquals(Optional.empty(), second.next());
    }

    @Test
    void theLastPageHasNoNextCursor() {
        for (String id : List.of("m-1", "m-2", "m-3")) store.record(snap(id, 0, "ingress", Keys.empty().with("invoiceId", "4711"))).await();
        Page<String> exact = page(store, "4711", Optional.empty(), 3);
        assertEquals(List.of("m-1", "m-2", "m-3"), exact.items());
        assertEquals(Optional.empty(), exact.next(), "exactly limit items left: limit + 1 fetch, no empty page after");
        assertEquals(Optional.empty(), page(store, "4711", Optional.empty(), 4).next());
        Page<String> first = page(store, "4711", Optional.empty(), 2);
        assertEquals(List.of("m-1", "m-2"), first.items());
        assertTrue(first.next().isPresent(), "a third item exists");
        Page<String> last = page(store, "4711", first.next(), 2);
        assertEquals(List.of("m-3"), last.items());
        assertEquals(Optional.empty(), last.next());
        Page<String> none = page(store, "0", Optional.empty(), 2);
        assertEquals(List.of(), none.items());
        assertEquals(Optional.empty(), none.next());
    }

    @Test
    void aGarbledCursorOrLimitOutsideOneToFiveHundredIsRefused() {
        store.record(snap("m-1", 0, "ingress", Keys.empty().with("invoiceId", "4711"))).await();
        store.record(snap("m-2", 0, "ingress", Keys.empty().with("invoiceId", "4711"))).await();
        for (int limit : new int[] {0, -1, Page.MAX_LIMIT + 1, Integer.MIN_VALUE}) refused(() -> page(store, "4711", Optional.empty(), limit), "limit " + limit);
        String notANumber = Base64.getUrlEncoder().withoutPadding().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("!!!", notANumber)) refused(() -> page(store, "4711", Optional.of(cursor), 1), "cursor " + cursor);
        assertEquals(List.of("m-1"), page(store, "4711", Optional.empty(), 1).items(), "1 is a limit");
        assertEquals(List.of("m-1", "m-2"), page(store, "4711", Optional.empty(), Page.MAX_LIMIT).items(), "500 is a limit");
    }

    /** Refused with IllegalArgumentException (the admin API's 400), thrown at the call or as the failed future's cause. */
    static void refused(org.junit.jupiter.api.function.Executable call, String what) {
        Throwable t = assertThrows(Throwable.class, call, what + " is refused");
        assertInstanceOf(IllegalArgumentException.class, t, what + ": " + t);
    }

    @Test
    void aSnapshotValidatesItsShape() {
        Snapshot ok = snap("m", 0, "ingress", Keys.empty());
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(ok.messageId(), ok.flow(), -1, ok.stepName(), ok.outcome(), ok.error(), ok.startedAt(), ok.wallNanos(), ok.cpuNanos(),
                ok.headers(), ok.props(), ok.keys(), ok.bodyHash(), ok.bodySize(), ok.bodyKey(), ok.versions()));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(ok.messageId(), ok.flow(), 0, ok.stepName(), ok.outcome(), ok.error(), ok.startedAt(), -1, ok.cpuNanos(),
                ok.headers(), ok.props(), ok.keys(), ok.bodyHash(), ok.bodySize(), ok.bodyKey(), ok.versions()), "wall time is never negative");
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(ok.messageId(), ok.flow(), 0, ok.stepName(), ok.outcome(), ok.error(), ok.startedAt(), 0, -2,
                ok.headers(), ok.props(), ok.keys(), ok.bodyHash(), ok.bodySize(), ok.bodyKey(), ok.versions()), "cpu is a value or -1 (unknown)");
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(ok.messageId(), ok.flow(), 0, ok.stepName(), Snapshot.Outcome.FAILED, Optional.empty(), ok.startedAt(), 0, 0,
                ok.headers(), ok.props(), ok.keys(), ok.bodyHash(), ok.bodySize(), ok.bodyKey(), ok.versions()), "a failure names its error");
        assertEquals(Snapshot.CPU_UNKNOWN, -1L);
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(ok.messageId(), ok.flow(), 0, ok.stepName(), ok.outcome(), ok.error(), ok.startedAt(), 0, 0,
                ok.headers(), ok.props(), ok.keys(), ok.bodyHash(), -1, ok.bodyKey(), ok.versions()), "a negative size is no size");
        assertEquals(0L, new Snapshot(ok.messageId(), ok.flow(), 0, ok.stepName(), ok.outcome(), ok.error(), ok.startedAt(), 0, Snapshot.CPU_UNKNOWN,
                ok.headers(), ok.props(), ok.keys(), ok.bodyHash(), 0, ok.bodyKey(), ok.versions()).bodySize(), "an empty body is legal");
    }

    /**
     * FIX-66 (D-071), the same case as PgMessageStoreIT's: rows written through {@code recordWaiting} from many threads at once
     * all land. The in-memory store has no backlog to wait for: the interface's default hands each row to {@code record}
     * (the first row per (id, step) still wins).
     */
    @Test
    void recordWaitingPastTheBacklogLandsEveryRow() throws Exception {
        MessageStore rows = store;   // through the interface: the default method
        List<io.vertx.core.Future<Void>> saves = new CopyOnWriteArrayList<>();
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String id = "m-" + i;
            callers.add(Thread.ofVirtual().start(() -> saves.add(rows.recordWaiting(snap(id, 0, "ingress", Keys.empty().with("invoiceId", "4711"))))));
        }
        for (Thread t : callers) assertTrue(t.join(java.time.Duration.ofSeconds(10)), "the call returned: " + t);
        assertEquals(50, saves.size());
        io.vertx.core.Future.join(new ArrayList<>(saves)).otherwiseEmpty().await(10, TimeUnit.SECONDS);
        assertTrue(saves.stream().allMatch(io.vertx.core.Future::succeeded), saves.toString());
        for (int i = 0; i < 50; i++) assertEquals(List.of("ingress"), store.timeline("m-" + i).await().stream().map(Snapshot::stepName).toList(), "m-" + i + " landed");
        assertEquals(50, store.size());
        assertEquals(50, store.findByKey("invoiceId", "4711", Optional.empty(), Page.MAX_LIMIT).await().items().size());
        rows.recordWaiting(snap("m-0", 0, "ingress-again", Keys.empty())).await();
        assertEquals("ingress", store.timeline("m-0").await().getFirst().stepName(), "the first row per (id, step) wins, as with record");
    }
}
