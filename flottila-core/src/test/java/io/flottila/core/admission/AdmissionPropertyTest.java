package io.flottila.core.admission;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLAUDE.md: property tests for admission. Invariants over any sequence of admits and releases. */
class AdmissionPropertyTest {

    /** An op: positive = try to admit that many bytes, negative = release the (-n)th outstanding permit (mod size). */
    @Provide
    Arbitrary<List<Integer>> ops() {
        return Arbitraries.integers().between(-50, 2048).list().ofMinSize(1).ofMaxSize(400);
    }

    @Property(tries = 300)
    void inFlightNeverExceedsEitherBudgetAndAlwaysEqualsTheOutstandingPermits(
            @ForAll @LongRange(min = 1, max = 16) long maxRequests, @ForAll @LongRange(min = 1, max = 4096) long maxBytes,
            @ForAll("ops") List<Integer> ops) {
        Admission a = new Admission(maxRequests, maxBytes);
        List<Admission.Permit> held = new ArrayList<>();
        for (int op : ops) {
            if (op >= 0) {
                long bytes = Math.min(op, maxBytes);
                boolean fits = held.size() < maxRequests && a.inFlightBytes() + bytes <= maxBytes;
                Optional<Admission.Permit> p = a.tryAcquire(bytes);
                assertEquals(fits, p.isPresent(), "admitted iff it fits: " + bytes + " bytes with " + held.size() + " held");
                p.ifPresent(held::add);
            } else if (!held.isEmpty()) {
                held.remove((-op) % held.size()).close();
            }
            assertTrue(a.inFlightRequests() <= maxRequests);
            assertTrue(a.inFlightBytes() <= maxBytes);
            assertEquals(held.size(), a.inFlightRequests());
            assertEquals(held.stream().mapToLong(Admission.Permit::bytes).sum(), a.inFlightBytes());
        }
        held.forEach(Admission.Permit::close);
        assertEquals(0, a.inFlightRequests());
        assertEquals(0, a.inFlightBytes());
        assertTrue(a.tryAcquire(maxBytes).isPresent(), "a full-budget request is admitted again once everything is released");
    }

    @Property(tries = 100)
    void doubleCloseNeverReleasesTwice(@ForAll @IntRange(min = 1, max = 8) int n, @ForAll @Size(min = 1, max = 20) List<@IntRange(min = 0, max = 7) Integer> closes) {
        Admission a = new Admission(n, 1024);
        List<Admission.Permit> held = new ArrayList<>();
        for (int i = 0; i < n; i++) held.add(a.tryAcquire(1).orElseThrow());
        for (int c : closes) held.get(c % n).close();
        long distinctClosed = closes.stream().map(c -> c % n).distinct().count();
        assertEquals(n - distinctClosed, a.inFlightRequests());
        assertEquals(n - distinctClosed, a.inFlightBytes());
    }
}
