package io.flottila.core.record;

import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-057 (a): key search is paged by an opaque cursor in insertion order. Walking the pages of {@code findByKey} yields every
 * message that carried the key exactly once, in the order it first carried it — also when messages keep being recorded
 * between two pages (the store only grows; a cursor handed out stays valid).
 */
class FindByKeyPagingPropertyTest {

    /** One snapshot to record: message {@code m-<message>} at {@code step}, carrying {@code invoiceId=<value>} unless value is empty. */
    record Rec(int message, int step, String value) {}

    @Provide
    Arbitrary<List<Rec>> recordings() {
        return Combinators.combine(Arbitraries.integers().between(0, 11), Arbitraries.integers().between(0, 4), Arbitraries.of("4711", "4712", ""))
                .as(Rec::new).list().ofMaxSize(40);
    }

    static Snapshot snap(Rec r) {
        Keys keys = r.value().isEmpty() ? Keys.empty() : Keys.empty().with("invoiceId", r.value());
        return new Snapshot("m-" + r.message(), "f", r.step(), "s" + r.step(), Snapshot.Outcome.OK, Optional.empty(), Instant.EPOCH, 0, 0,
                Headers.empty(), Props.empty(), keys, new Hash("sha256-64", r.step()), 1, Optional.empty(), Map.of());
    }

    /** Records each (message, step) once — a repeat is ignored by the store anyway — and keeps the expected order for 4711. */
    static void record(InMemoryMessageStore store, List<Rec> recs, Set<String> seen, List<String> order) {
        for (Rec r : recs) {
            if (!seen.add(r.message() + "/" + r.step())) continue;
            store.record(snap(r)).await();
            String id = "m-" + r.message();
            if (r.value().equals("4711") && !order.contains(id)) order.add(id);
        }
    }

    @Property(tries = 400)
    void findByKeyPagesConcatenateToTheFullOrderWithoutGapsOrRepeats(@ForAll("recordings") List<Rec> before, @ForAll("recordings") List<Rec> after,
                                                                      @ForAll @IntRange(min = 1, max = 13) int limit) {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();
        record(store, before, seen, order);
        List<String> beforeOrder = List.copyOf(order);

        Page<String> page = store.findByKey("invoiceId", "4711", Optional.empty(), limit).await();
        assertTrue(page.items().size() <= limit, "a page holds at most limit items: " + page);
        boolean continued = page.next().isPresent();
        if (continued) assertEquals(limit, page.items().size(), "a page with a next cursor is full: " + page);
        else assertEquals(beforeOrder, page.items(), "no next cursor: the first page is everything");
        List<String> walked = new ArrayList<>(page.items());

        record(store, after, seen, order);   // the store grows between two pages
        int pages = 1;
        while (page.next().isPresent()) {
            page = store.findByKey("invoiceId", "4711", page.next(), limit).await();
            pages++;
            assertTrue(page.items().size() <= limit, "a page holds at most limit items: " + page);
            assertFalse(page.items().isEmpty(), "a next cursor is handed out only when another item exists (limit + 1 fetch)");
            if (page.next().isPresent()) assertEquals(limit, page.items().size(), "a page with a next cursor is full: " + page);
            walked.addAll(page.items());
            assertTrue(pages <= order.size() + 1, "the walk ends");
        }
        assertEquals(continued ? order : beforeOrder, walked,
                "pages concatenate to the full order (the first-carrying order), without gaps or repeats; limit " + limit);
    }
}
