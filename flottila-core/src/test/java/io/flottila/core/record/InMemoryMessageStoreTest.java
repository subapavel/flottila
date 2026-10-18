package io.flottila.core.record;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(List.of("m-1", "m-3"), store.findByKey("invoiceId", "4711").await());
        assertEquals(List.of("m-3"), store.findByKey("orderNo", "9").await());
        assertEquals(List.of(), store.findByKey("invoiceId", "0").await());
        assertThrows(NullPointerException.class, () -> store.record(null));
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
        assertEquals(0L, new Snapshot(ok.messageId(), ok.flow(), 0, ok.stepName(), ok.outcome(), ok.error(), ok.startedAt(), 0, Snapshot.CPU_UNKNOWN,
                ok.headers(), ok.props(), ok.keys(), ok.bodyHash(), 0, ok.bodyKey(), ok.versions()).bodySize(), "an empty body is legal");
    }
}
