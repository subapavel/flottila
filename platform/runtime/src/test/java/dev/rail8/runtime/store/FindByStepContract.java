package dev.rail8.runtime.store;

import dev.rail8.api.Hash;
import dev.rail8.api.Headers;
import dev.rail8.api.Keys;
import dev.rail8.api.Props;
import dev.rail8.core.record.MessageStore;
import dev.rail8.core.record.Page;
import dev.rail8.core.record.Snapshot;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What every {@link MessageStore} answers to a search by step: the ids of a flow's messages with a recorded row of the named
 * step, each once, newest first by the start of the message's row of that step in its latest attempt that has one, then by id;
 * a page at a time, the next page after the last one's cursor. Run over core's in-memory store and over the Postgres store.
 */
@Timeout(60)
public abstract class FindByStepContract {

    static final String FLOW = "orders";
    static final Instant T0 = Instant.parse("2026-11-18T10:00:00.123456Z");

    /** A store with no rows, for one case. */
    protected abstract MessageStore emptyStore();

    MessageStore store;

    @BeforeEach
    void openAnEmptyStore() {
        store = emptyStore();
    }

    /** A row of {@code id}'s step {@code step}, named {@code name}, of {@code attempt} in {@code flow}, started {@code seconds} after {@link #T0}. */
    static Snapshot row(String id, String flow, int step, int attempt, String name, long seconds) {
        return new Snapshot(id, flow, step, attempt, name, Snapshot.Outcome.OK, Optional.empty(), T0.plusSeconds(seconds), 1_000L, 10L,
                Headers.empty(), Props.empty(), Keys.empty(), new Hash("sha256-64", 7), 12, Optional.empty(), Map.of("flow", flow + "@1.0.0"));
    }

    void record(Snapshot... rows) {
        for (Snapshot s : rows) store.record(s).await();
    }

    Page<String> find(String flow, String step, Optional<String> after, int limit) {
        return store.findByStep(flow, step, after, limit).await();
    }

    /** Every id a walk of pages of {@code limit} lists, in order, following each page's cursor to the last page. */
    List<String> walk(String flow, String step, int limit) {
        List<String> ids = new ArrayList<>();
        Optional<String> after = Optional.empty();
        for (int pages = 0; pages < 100; pages++) {
            Page<String> page = find(flow, step, after, limit);
            assertTrue(page.items().size() <= limit, "a page holds at most its limit: " + page);
            ids.addAll(page.items());
            if (page.next().isEmpty()) return ids;
            after = page.next();
        }
        return fail("a walk of " + limit + " ends: " + ids);
    }

    @Test
    void theFlowsMessagesThatPassedTheStepNewestFirst() {
        record(row("m-1", FLOW, 0, 1, "ingress", 0), row("m-1", FLOW, 1, 1, "enrich/map", 1),
                row("m-2", FLOW, 0, 1, "ingress", 2), row("m-2", FLOW, 1, 1, "enrich/map", 30),
                row("m-3", FLOW, 0, 1, "ingress", 4), row("m-3", FLOW, 1, 1, "enrich/map", 5),
                row("m-4", FLOW, 0, 1, "ingress", 6),
                row("x-1", "invoices", 0, 1, "ingress", 7), row("x-1", "invoices", 1, 1, "enrich/map", 99));

        Page<String> page = find(FLOW, "enrich/map", Optional.empty(), Page.MAX_LIMIT);

        assertEquals(List.of("m-2", "m-3", "m-1"), page.items(), "by the start of the step's row, newest first; m-4 never got there, x-1 is another flow's");
        assertEquals(Optional.empty(), page.next(), "everything fit: the last page");
        assertEquals(List.of("m-4", "m-3", "m-2", "m-1"), find(FLOW, "ingress", Optional.empty(), Page.MAX_LIMIT).items(), "every arrival is found at ingress");
    }

    @Test
    void rowsThatStartedTogetherAreOrderedByIdDescendingAcrossPages() {
        for (String id : List.of("m-b", "m-d", "m-a", "m-c")) record(row(id, FLOW, 1, 1, "toPartner", 3));
        record(row("m-e", FLOW, 1, 1, "toPartner", 1));

        assertEquals(List.of("m-d", "m-c", "m-b", "m-a", "m-e"), walk(FLOW, "toPartner", 1), "the same start: by id, descending, a page boundary between them");
        assertEquals(List.of("m-d", "m-c", "m-b", "m-a", "m-e"), walk(FLOW, "toPartner", 2));
    }

    @Test
    void aWalkFollowsTheCursorToALastPageWithoutANext() {
        List<String> newestFirst = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            record(row("m-" + i, FLOW, 2, 1, "toPartner", i));
            newestFirst.addFirst("m-" + i);
        }

        Page<String> first = find(FLOW, "toPartner", Optional.empty(), 3);
        assertEquals(newestFirst.subList(0, 3), first.items());
        assertTrue(first.next().isPresent(), "more are left: a next cursor");
        Page<String> second = find(FLOW, "toPartner", first.next(), 3);
        assertEquals(newestFirst.subList(3, 6), second.items(), "the next page goes on where the first stopped");
        Page<String> last = find(FLOW, "toPartner", second.next(), 3);
        assertEquals(newestFirst.subList(6, 7), last.items());
        assertEquals(Optional.empty(), last.next(), "the last page has no next");
        assertEquals(Optional.empty(), find(FLOW, "toPartner", Optional.empty(), 7).next(), "a page that holds exactly what is left is the last");
    }

    @Test
    void aRetriedMessageIsListedOnceAtItsLatestAttemptThatReachedTheStep() {
        record(row("m-r", FLOW, 0, 1, "ingress", 0), row("m-r", FLOW, 2, 1, "toPartner", 10),
                row("m-r", FLOW, 0, 2, "ingress", 40), row("m-r", FLOW, 2, 2, "toPartner", 50));
        record(row("m-s", FLOW, 0, 1, "ingress", 1), row("m-s", FLOW, 2, 1, "toPartner", 20),
                row("m-s", FLOW, 0, 2, "ingress", 60));
        record(row("m-x", FLOW, 2, 1, "toPartner", 30));

        assertEquals(List.of("m-r", "m-x", "m-s"), find(FLOW, "toPartner", Optional.empty(), Page.MAX_LIMIT).items(),
                "m-r once, at its second attempt's row; m-s's second attempt has no row of the step, so its first attempt's counts");
        assertEquals(List.of("m-r", "m-x", "m-s"), walk(FLOW, "toPartner", 1), "page by page too, each once");
        assertEquals(List.of("m-s", "m-r"), find(FLOW, "ingress", Optional.empty(), Page.MAX_LIMIT).items(), "at ingress, each at its latest arrival");
    }

    @Test
    void thePartsOfASplitAreListedEachUnderItsOwnId() {
        record(row("m-p", FLOW, 0, 1, "ingress", 0), row("m-p", FLOW, 1, 1, "split", 1));
        record(row("m-p.0", FLOW, 2, 1, "toPartner", 2), row("m-p.1", FLOW, 2, 1, "toPartner", 2), row("m-p.2", FLOW, 2, 1, "toPartner", 3));

        assertEquals(List.of("m-p.2", "m-p.1", "m-p.0"), walk(FLOW, "toPartner", 2), "each part once, under its own id; the parent did not pass the step");
        assertEquals(List.of("m-p"), find(FLOW, "split", Optional.empty(), Page.MAX_LIMIT).items());
    }

    @Test
    void aWalkListsEachIdOnceWhileMessagesReachTheStepAgain() {
        for (int i = 1; i <= 5; i++) record(row("m-" + i, FLOW, 1, 1, "toPartner", i));
        Page<String> first = find(FLOW, "toPartner", Optional.empty(), 2);
        assertEquals(List.of("m-5", "m-4"), first.items());

        record(row("m-4", FLOW, 1, 2, "toPartner", 100), row("m-1", FLOW, 1, 2, "toPartner", 101));
        Page<String> second = find(FLOW, "toPartner", first.next(), 2);

        assertEquals(List.of("m-3", "m-2"), second.items(), "m-4, already listed, reached the step again: it moved to the front, not into the rest of the walk");
        assertEquals(Optional.empty(), second.next(), "m-1 moved to the front too: the walk ends without it");
        assertEquals(List.of("m-1", "m-4", "m-5", "m-3", "m-2"), find(FLOW, "toPartner", Optional.empty(), Page.MAX_LIMIT).items());
    }

    @Test
    void anUnknownFlowOrStepGivesAnEmptyLastPage() {
        record(row("m-1", FLOW, 1, 1, "toPartner", 1));

        for (Page<String> page : List.of(find("nobody", "toPartner", Optional.empty(), 10), find(FLOW, "nothing", Optional.empty(), 10),
                find(FLOW, "toPartne", Optional.empty(), 10), find(FLOW, "TOPARTNER", Optional.empty(), 10))) {
            assertEquals(new Page<>(List.of(), Optional.empty()), page, "names match exactly");
        }
    }

    @Test
    void aLimitOutsideOneToFiveHundredIsRefused() {
        record(row("m-1", FLOW, 1, 1, "toPartner", 1));

        for (int limit : List.of(0, -1, Page.MAX_LIMIT + 1)) refused(() -> store.findByStep(FLOW, "toPartner", Optional.empty(), limit), "limit " + limit);
        assertEquals(List.of("m-1"), find(FLOW, "toPartner", Optional.empty(), 1).items(), "1 is the smallest page");
    }

    @Test
    void aCursorTheSearchByStepDidNotHandOutIsRefused() {
        for (int i = 0; i < 3; i++) {
            record(row("m-" + i, FLOW, 1, 1, "toPartner", i));
            store.record(new Snapshot("m-" + i, FLOW, 0, 1, "ingress", Snapshot.Outcome.OK, Optional.empty(), T0, 0, 0, Headers.empty(), Props.empty(),
                    Keys.empty().with("orderNo", "o-1"), new Hash("sha256-64", 7), 12, Optional.empty(), Map.of())).await();
        }
        String keyCursor = store.findByKey("orderNo", "o-1", Optional.empty(), 1).await().next().orElseThrow();
        String stepCursor = find(FLOW, "toPartner", Optional.empty(), 1).next().orElseThrow();

        for (String cursor : List.of("!!!", Page.cursor(""), Page.cursor("garbage"), Page.cursor("99999999999999999999:m-1"), keyCursor)) {
            refused(() -> store.findByStep(FLOW, "toPartner", Optional.of(cursor), 10), "cursor " + cursor);
        }
        refused(() -> store.findByKey("orderNo", "o-1", Optional.of(stepCursor), 10), "a cursor of the search by step, given to the key search");
    }

    /** {@code call} is refused with an IllegalArgumentException, thrown or as its future's cause. */
    static void refused(Supplier<Future<?>> call, String what) {
        Future<?> answer;
        try {
            answer = call.get();
        } catch (IllegalArgumentException expected) {
            return;
        }
        try {
            answer.await();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (RuntimeException other) {
            fail(what + ": refused with " + other + ", not an IllegalArgumentException");
        }
        fail(what + ": answered, not refused");
    }
}
