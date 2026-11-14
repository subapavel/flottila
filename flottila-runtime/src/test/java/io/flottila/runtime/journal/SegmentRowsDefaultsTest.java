package io.flottila.runtime.journal;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link SegmentRows} that implements only its insert keeps no rows (CORE-4c1; the implementer's): it names no journal
 * and no segment — a start then reconciles nothing — and a register or a drop asked of it succeeds without effect.
 */
class SegmentRowsDefaultsTest {

    @Test
    void anInsertAloneKeepsNoRows() {
        SegmentRows rows = (node, segment) -> Future.succeededFuture();

        assertNull(rows.journalOf("n1").result(), "no journal id is registered");
        assertNull(rows.segments("n1").result(), "no rows are kept: null, not an empty set");
        Future<Void> registered = rows.register("n1", new UUID(1, 2));
        Future<Void> dropped = rows.drop("n1", Set.of(1L, 2L));
        assertTrue(registered.succeeded() && dropped.succeeded(), "register " + registered + ", drop " + dropped);
        assertNull(rows.journalOf("n1").result(), "a register keeps nothing either");
    }
}
