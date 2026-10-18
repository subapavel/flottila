package io.flottila.core.dlq;

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

/** The in-memory DLQ: park by reference, list in parking order, take a row out; the first park of an id wins. */
class InMemoryDlqTest {

    final InMemoryDlq dlq = new InMemoryDlq();

    static Parked parked(String id, int step, Instant at) {
        return new Parked(id, "invoice-sync", step, "partner", "java.lang.IllegalStateException: 503", at, Headers.empty().with("h", "v"),
                Props.empty(), Keys.empty().with("invoiceId", "4711"), new Hash("sha256-64", 9), 187, BlobKey.body(id, step - 1), Map.of("flow", "invoice-sync@0.1.0"));
    }

    @Test
    void parksListsGetsAndRemovesByReference() {
        Parked a = parked("m-1", 5, Instant.EPOCH.plusSeconds(1));
        Parked b = parked("m-2", 5, Instant.EPOCH.plusSeconds(2));
        dlq.park(a).await();
        dlq.park(b).await();
        dlq.park(parked("m-1", 2, Instant.EPOCH.plusSeconds(3))).await();
        assertEquals(List.of(a, b), dlq.list().await(), "parking order; a second park of m-1 is ignored");
        assertEquals(Optional.of(b), dlq.get("m-2").await());
        assertEquals(Optional.empty(), dlq.get("m-9").await());
        dlq.remove("m-1").await();
        dlq.remove("m-9").await();
        assertEquals(List.of(b), dlq.list().await());
        assertEquals(1, dlq.size());
        assertThrows(NullPointerException.class, () -> dlq.park(null));
    }

    @Test
    void aParkedRowValidatesItsShape() {
        Parked ok = parked("m", 1, Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> new Parked(ok.messageId(), ok.flow(), 0, ok.stepName(), ok.error(), ok.parkedAt(), ok.headers(), ok.props(), ok.keys(),
                ok.bodyHash(), ok.bodySize(), ok.bodyKey(), ok.versions()), "ingress never parks: step starts at 1");
        assertThrows(NullPointerException.class, () -> new Parked(ok.messageId(), ok.flow(), 1, ok.stepName(), null, ok.parkedAt(), ok.headers(), ok.props(), ok.keys(),
                ok.bodyHash(), ok.bodySize(), ok.bodyKey(), ok.versions()));
        assertThrows(IllegalArgumentException.class, () -> new Parked(ok.messageId(), ok.flow(), 1, ok.stepName(), ok.error(), ok.parkedAt(), ok.headers(), ok.props(), ok.keys(),
                ok.bodyHash(), -1, ok.bodyKey(), ok.versions()));
        assertEquals("m", ok.messageId());
        assertEquals(0L, new Parked(ok.messageId(), ok.flow(), 1, ok.stepName(), ok.error(), ok.parkedAt(), ok.headers(), ok.props(), ok.keys(),
                ok.bodyHash(), 0, ok.bodyKey(), ok.versions()).bodySize(), "an empty body is legal");
    }
}
