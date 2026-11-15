package io.flottila.runtime.lease;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-02L (D-083), jqwik: whatever owners hold, renew, release and let lapse on two keys, at most one owner holds a key at any
 * instant. "Holds" is what an owner may act on: from each hold answered true at c with ttl, [c, c + ttl), ended early by its own
 * next answer (a later true starts a new span, a false ends it) or its release. Every answer is also checked against that model
 * — a hold is true exactly when no other owner holds the key at that instant — so a store that never answers true fails too.
 *
 * <p>Named {@code ...PropertyTest} (not {@code ...Properties}, as the packet has it): Surefire's default includes run
 * {@code *Test} classes only, and the property runs with the module's tests.
 */
class InMemoryPollerLeasesPropertyTest {

    static final List<String> OWNERS = List.of("node-a#1", "node-b#1", "node-c#1");
    static final List<String> KEYS = List.of("shop/feed/sftp://drop", "shop/sort/sftp://drop");

    sealed interface Op permits Hold, Release, Advance {}

    record Hold(int owner, int key, long ttlMs) implements Op {}

    record Release(int owner, int key) implements Op {}

    record Advance(long ms) implements Op {}

    /** The span an owner holds a key in, per the answers it got: [from, until). */
    record Span(String owner, Instant from, Instant until) {}

    @Provide
    Arbitrary<List<Op>> scripts() {
        Arbitrary<Integer> owner = Arbitraries.integers().between(0, OWNERS.size() - 1);
        Arbitrary<Integer> key = Arbitraries.integers().between(0, KEYS.size() - 1);
        Arbitrary<Op> hold = Combinators.combine(owner, key, Arbitraries.of(1L, 5L, 10L, 30L)).as((o, k, t) -> (Op) new Hold(o, k, t));
        Arbitrary<Op> release = Combinators.combine(owner, key).as((o, k) -> (Op) new Release(o, k));
        Arbitrary<Op> advance = Arbitraries.of(0L, 1L, 4L, 5L, 9L, 10L, 29L, 30L, 31L).map(ms -> (Op) new Advance(ms));
        return Arbitraries.frequencyOf(Tuple.of(6, hold), Tuple.of(2, release), Tuple.of(4, advance)).list().ofMinSize(1).ofMaxSize(40);
    }

    @Property(tries = 500)
    void atMostOneOwnerHoldsAKeyAtAnyInstant(@ForAll("scripts") List<Op> script) throws Exception {
        FakeClock clock = FakeClock.at(FakeRuntime.T0);
        InMemoryPollerLeases leases = new InMemoryPollerLeases(clock);
        Map<String, List<Span>> spans = new HashMap<>();             // by key: every span any owner held, ended or not
        Map<String, Integer> open = new HashMap<>();                 // key + owner -> index of its span still open
        for (Op op : script) {
            Instant now = clock.instant();
            switch (op) {
                case Advance a -> clock.advance(Duration.ofMillis(a.ms()));
                case Hold h -> {
                    String key = KEYS.get(h.key());
                    String owner = OWNERS.get(h.owner());
                    List<Span> onKey = spans.computeIfAbsent(key, k -> new ArrayList<>());
                    boolean free = onKey.stream().noneMatch(s -> !s.owner().equals(owner) && covers(s, now));
                    boolean answer = leases.hold(key, owner, Duration.ofMillis(h.ttlMs())).await(5, TimeUnit.SECONDS);
                    assertEquals(free, answer, "hold(" + key + ", " + owner + ", " + h.ttlMs() + " ms) at +"
                            + Duration.between(FakeRuntime.T0, now).toMillis() + " ms, spans " + onKey);
                    end(onKey, open, key, owner, now);
                    if (answer) {
                        open.put(key + " " + owner, onKey.size());
                        onKey.add(new Span(owner, now, now.plusMillis(h.ttlMs())));
                    }
                }
                case Release r -> {
                    String key = KEYS.get(r.key());
                    String owner = OWNERS.get(r.owner());
                    leases.release(key, owner).await(5, TimeUnit.SECONDS);
                    end(spans.computeIfAbsent(key, k -> new ArrayList<>()), open, key, owner, now);
                }
            }
        }
        for (Map.Entry<String, List<Span>> e : spans.entrySet()) {
            List<Span> onKey = e.getValue();
            for (int i = 0; i < onKey.size(); i++) {
                for (int j = i + 1; j < onKey.size(); j++) {
                    Span x = onKey.get(i);
                    Span y = onKey.get(j);
                    if (!x.owner().equals(y.owner()) && x.from().isBefore(y.until()) && y.from().isBefore(x.until())) {
                        fail("two owners held " + e.getKey() + " at once: " + x + " and " + y);
                    }
                }
            }
        }
    }

    static boolean covers(Span s, Instant at) {
        return !at.isBefore(s.from()) && at.isBefore(s.until());
    }

    /** Ends the span {@code owner} holds on {@code key} at {@code now}, if one is open and runs past it. */
    static void end(List<Span> onKey, Map<String, Integer> open, String key, String owner, Instant now) {
        Integer i = open.remove(key + " " + owner);
        if (i == null) return;
        Span s = onKey.get(i);
        if (s.until().isAfter(now)) onKey.set(i, new Span(owner, s.from(), now));
    }
}
