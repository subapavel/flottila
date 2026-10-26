package io.flottila.core.dlq;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.record.Page;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-memory DLQ: park by reference, list oldest first (parkedAt, then id) in pages by position, take a row out; the first park of an id wins. */
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
        assertEquals(List.of(a, b), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "parking order; a second park of m-1 is ignored");
        assertEquals(Optional.of(b), dlq.get("m-2").await());
        assertEquals(Optional.empty(), dlq.get("m-9").await());
        dlq.remove("m-1").await();
        dlq.remove("m-9").await();
        assertEquals(List.of(b), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items());
        assertEquals(1, dlq.size());
        assertThrows(NullPointerException.class, () -> dlq.park(null));
    }

    List<String> ids(Page<Parked> page) {
        return page.items().stream().map(Parked::messageId).toList();
    }

    @Test
    void dlqPagesOldestFirstWithTiesBrokenById() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00.999999Z"), t2 = Instant.parse("2026-01-01T00:00:01.000000Z"), t3 = t2.plusNanos(1_000);
        dlq.park(parked("d-b", 3, t2)).await();
        dlq.park(parked("d-z", 3, t1)).await();
        dlq.park(parked("d-a", 3, t1)).await();
        dlq.park(parked("d-c", 3, t3)).await();
        dlq.park(parked("d-m", 3, t1)).await();
        assertEquals(List.of("d-a", "d-m", "d-z", "d-b", "d-c"), ids(dlq.list(Optional.empty(), Page.MAX_LIMIT).await()), "parked_at, then id — not parking order");
        Page<Parked> first = dlq.list(Optional.empty(), 2).await();
        assertEquals(List.of("d-a", "d-m"), ids(first));
        Page<Parked> second = dlq.list(first.next(), 2).await();
        assertEquals(List.of("d-z", "d-b"), ids(second), "the tie at t1 continues across the page boundary, then the next second");
        Page<Parked> third = dlq.list(second.next(), 2).await();
        assertEquals(List.of("d-c"), ids(third), "one microsecond later");
        assertEquals(Optional.empty(), third.next());
    }

    @Test
    void withinOneSecondTheEarlierRowComesFirstWhateverItsId() {
        Instant second = Instant.parse("2026-01-01T00:00:05Z");
        dlq.park(parked("d-x", 3, second.plusMillis(200))).await();
        dlq.park(parked("d-w", 3, second.plusMillis(300))).await();
        dlq.park(parked("d-y", 3, second.plusMillis(100))).await();
        assertEquals(List.of("d-y", "d-x", "d-w"), ids(dlq.list(Optional.empty(), Page.MAX_LIMIT).await()), "sub-second parkedAt orders before the id");
        Page<Parked> first = dlq.list(Optional.empty(), 1).await();
        assertEquals(List.of("d-y"), ids(first));
        Page<Parked> second2 = dlq.list(first.next(), 1).await();
        assertEquals(List.of("d-x"), ids(second2), "the cursor carries the sub-second part: d-w (a smaller id, later) does not jump ahead");
        assertEquals(List.of("d-w"), ids(dlq.list(second2.next(), 1).await()));
    }

    @Test
    void removingTheCursorRowAndOneBeforeItSkipsNothing() {
        for (int i = 1; i <= 5; i++) dlq.park(parked("m-" + i, 2, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i))).await();
        Page<Parked> first = dlq.list(Optional.empty(), 2).await();
        assertEquals(List.of("m-1", "m-2"), ids(first));
        dlq.remove("m-1").await();   // a row before the cursor (a retry took it)
        dlq.remove("m-2").await();   // the cursor's own row
        Page<Parked> second = dlq.list(first.next(), 2).await();
        assertEquals(List.of("m-3", "m-4"), ids(second), "the cursor is a position (parkedAt, id), not an offset or a row that must still exist");
        Page<Parked> third = dlq.list(second.next(), 2).await();
        assertEquals(List.of("m-5"), ids(third));
        assertEquals(Optional.empty(), third.next());
    }

    @Test
    void theLastPageHasNoNextCursor() {
        for (int i = 1; i <= 3; i++) dlq.park(parked("m-" + i, 2, Instant.EPOCH.plusSeconds(i))).await();
        Page<Parked> exact = dlq.list(Optional.empty(), 3).await();
        assertEquals(List.of("m-1", "m-2", "m-3"), ids(exact));
        assertEquals(Optional.empty(), exact.next(), "exactly limit rows left: limit + 1 fetch, no empty page after");
        assertEquals(Optional.empty(), dlq.list(Optional.empty(), 4).await().next());
        Page<Parked> first = dlq.list(Optional.empty(), 2).await();
        assertEquals(List.of("m-1", "m-2"), ids(first));
        assertTrue(first.next().isPresent(), "a third row exists");
        Page<Parked> last = dlq.list(first.next(), 2).await();
        assertEquals(List.of("m-3"), ids(last));
        assertEquals(Optional.empty(), last.next());
        Page<Parked> empty = new InMemoryDlq().list(Optional.empty(), 2).await();
        assertEquals(List.of(), empty.items());
        assertEquals(Optional.empty(), empty.next());
    }

    @Test
    void aGarbledCursorOrLimitOutsideOneToFiveHundredIsRefused() {
        dlq.park(parked("m-1", 2, Instant.EPOCH.plusSeconds(1))).await();
        dlq.park(parked("m-2", 2, Instant.EPOCH.plusSeconds(2))).await();
        for (int limit : new int[] {0, -1, Page.MAX_LIMIT + 1, Integer.MIN_VALUE}) refused(() -> dlq.list(Optional.empty(), limit).await(), "limit " + limit);
        String notACursor = Base64.getUrlEncoder().withoutPadding().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("!!!", notACursor)) refused(() -> dlq.list(Optional.of(cursor), 1).await(), "cursor " + cursor);
        assertEquals(List.of("m-1"), ids(dlq.list(Optional.empty(), 1).await()), "1 is a limit");
        assertEquals(List.of("m-1", "m-2"), ids(dlq.list(Optional.empty(), Page.MAX_LIMIT).await()), "500 is a limit");
    }

    /** Refused with IllegalArgumentException (the admin API's 400), thrown at the call or as the failed future's cause. */
    static void refused(org.junit.jupiter.api.function.Executable call, String what) {
        Throwable t = assertThrows(Throwable.class, call, what + " is refused");
        assertInstanceOf(IllegalArgumentException.class, t, what + ": " + t);
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
