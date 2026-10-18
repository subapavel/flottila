package io.flottila.bench;

import io.flottila.core.admission.Admission;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spike-2 load generator (P0-11): per-scope fork/join wall time and admission acquire latency under many virtual threads. */
class ScopeBenchTest {

    @Test
    void scopeRunOpensForksJoinsAndClosesEveryScopeAndReportsItsDistribution() throws Exception {
        FakeClock clock = FakeClock.at(Instant.EPOCH);
        FakeTimer timer = new FakeTimer(clock);
        ScopeBench.ScopeReport r = ScopeBench.scopes(300, 3, 300, clock, timer);
        assertEquals(300, r.scopes());
        assertEquals(3, r.children());
        assertEquals(300, r.concurrency());
        assertEquals(0, r.failed(), "every child's result was seen by its scope");
        assertEquals(0, timer.pending(), "every scope dropped its deadline timer");
        assertTrue(r.p99Millis() >= r.p50Millis());
        assertTrue(r.scopesPerSecond() > 0);
        JsonObject json = r.toJson();
        assertEquals("scope-fork-join", json.getString("name"));
        assertEquals(300, json.getInteger("scopes"));
        assertEquals(900, json.getInteger("childrenRun"));
        assertEquals(300, json.getInteger("concurrency"));
        assertTrue(json.containsKey("p99Ms") && json.containsKey("scopesPerSecond") && json.containsKey("elapsedMs"));
    }

    @Test
    void scopeRunWithFewerWorkersThanScopesStillRunsEveryScopeExactlyOnce() throws Exception {
        FakeClock clock = FakeClock.at(Instant.EPOCH);
        FakeTimer timer = new FakeTimer(clock);
        ScopeBench.ScopeReport r = ScopeBench.scopes(250, 2, 7, clock, timer);   // 250 is not a multiple of 7
        assertEquals(250, r.scopes());
        assertEquals(7, r.concurrency());
        assertEquals(0, r.failed(), "250 recorded: no scope skipped, none run twice (the stats would overflow their capacity)");
        assertEquals(0, timer.pending());
    }

    @Test
    void admissionRunNeverExceedsTheBudgetAndReportsTheAcquireLatency() throws Exception {
        Admission admission = new Admission(8, 8 * 1024);
        ScopeBench.AdmissionReport r = ScopeBench.admission(500, admission, 1024, Duration.ofMillis(1));   // a real hold: the 8 slots are contended
        assertEquals(500, r.threads());
        assertEquals(1_000_000L, r.holdNanos());
        assertTrue(r.elapsedNanos() >= 500 / 8 * 1_000_000L, "500 holds of 1 ms through 8 slots take at least 62 ms of wall time: the budget really throttled");
        assertEquals(0, r.failed());
        assertTrue(r.maxInFlightRequests() <= 8, "observed " + r.maxInFlightRequests());
        assertTrue(r.maxInFlightBytes() <= 8 * 1024, "observed " + r.maxInFlightBytes());
        assertTrue(r.maxInFlightRequests() > 1, "the threads really contended");
        assertEquals(0, admission.inFlightRequests());
        assertEquals(0, admission.waiting());
        assertTrue(r.p99Millis() >= r.p50Millis());
        JsonObject json = r.toJson();
        assertEquals("admission-acquire", json.getString("name"));
        assertEquals(8L, json.getLong("maxRequests"));
        assertEquals(8L * 1024, json.getLong("maxBytes"));
        assertEquals(500, json.getInteger("threads"));
        assertTrue(json.containsKey("acquiresPerSecond") && json.containsKey("p99Ms") && json.containsKey("maxInFlightRequests"));
    }
}
