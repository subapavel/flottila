package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.api.Timer;
import io.flottila.runtime.deploy.LeaseLog.Answer;
import io.flottila.runtime.deploy.LeaseLog.Hold;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.flottila.runtime.deploy.LeaseLog.ended;
import static io.flottila.runtime.deploy.LeaseLog.quiet;
import static io.flottila.runtime.deploy.LeaseLog.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02L (D-083): the node's lease around a polling consumer. {@code LeasedConsumer} starts at once and ticks on the injected
 * timer, each tick on a virtual thread of its own, at a fixed delay: it reads t0, then holds the endpoint's lease, the wait
 * bounded by the renew period on the same timer. A hold that answers true makes the lease valid until t0 + ttl and starts an
 * inner consumer if none runs — the one it was given first, a fresh one from its supplier after that (a stopped consumer is
 * never started again), never before the old one's stop settled. A hold that answers false stops the inner consumer at once. A
 * hold that fails or does not answer in time leaves the inner consumer polling only while now &lt; the validity, stops it there,
 * and schedules the next tick at min(renew period, validity − now). A start that fails is a WARNING, a release and a retry at
 * the next tick. {@code stop()} cancels the next tick, waits for a tick in flight, stops the inner consumer (its stop awaited),
 * then releases — only what it held; a failed release is logged and the stop succeeds.
 *
 * <p>Time is the fake clock's: the timer fires on the test thread, a tick runs on its own virtual thread, and a negative ("nothing
 * started", "nothing released") is checked once the thread that would act is quiet ({@link LeaseLog#quiet}). The lease store is
 * {@link LeaseLog} (scripted answers at the store boundary); the inner consumers come from {@link Pollers}, a component counting
 * every consumer it built, started and stopped on the case's one log.
 *
 * <p>The shape these cases assume (P2-02L test-author): in {@code io.flottila.runtime.lease}, {@code public interface PollerLeases}
 * with {@code Future<Boolean> hold(String key, String owner, Duration ttl)} and {@code Future<Void> release(String key, String owner)};
 * in this package, {@code LeasedConsumer implements Consumer} with the package-private constructor {@code LeasedConsumer(PollerLeases
 * leases, String key, String owner, Duration ttl, Duration renewEvery, Timer timer, Clock clock, Consumer first, Supplier<Consumer>
 * fresh)}; every tick calls {@code hold} once; a settled hold's bound is cancelled, so between ticks the next tick is the one task
 * the timer holds.
 */
@Timeout(60)
class LeasedConsumerTest {

    static final Instant T0 = FakeRuntime.T0;
    static final String KEY = "shop/feed/poll://drop";
    static final String OWNER = "node-1#1";
    static final Duration TTL = Duration.ofSeconds(25);
    static final Duration RENEW = Duration.ofSeconds(10);
    static final EndpointConfig CFG = new EndpointConfig("drop", URI.create("poll://drop"), Map.of("flow", "shop/feed", "maxBodyBytes", 1024));
    static final Function<Envelope, Future<Ack>> HANDLER = e -> Future.succeededFuture(new Ack.Accepted(e.id()));

    final FakeClock clock = FakeClock.at(T0);
    final FakeTimer timer = new FakeTimer(clock);
    /** Every consumer built, started and stopped, and every lease call and answer, in the order made. */
    final List<String> log = new CopyOnWriteArrayList<>();
    final LeaseLog leases = new LeaseLog(clock, null, log::add);
    final Pollers pollers = new Pollers(log);
    final AtomicInteger freshCalls = new AtomicInteger();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    LeasedConsumer leased;
    /** What {@link #leased} builds each fresh consumer with: counts the call, then a new consumer of {@link #pollers}. */
    Supplier<Consumer> fresh;
    /** Whether the case stopped {@link #leased} itself: the cleanup stops it otherwise. */
    boolean stopped;

    @BeforeEach
    void build() {
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
        Consumer first = pollers.consumer(CFG, HANDLER);
        fresh = () -> {
            freshCalls.incrementAndGet();
            return pollers.consumer(CFG, HANDLER);
        };
        leased = new LeasedConsumer(leases, KEY, OWNER, TTL, RENEW, timer, clock, first, fresh);
    }

    @AfterEach
    void stopWhatACaseLeftRunning() throws Exception {
        try {
            leases.answerEveryOpenHold();
            pollers.releaseEveryStop();
            if (!stopped) OnVirtualThread.join(OnVirtualThread.start(() -> leased.stop().await()));
        } finally {
            flottila.removeHandler(capture);
        }
    }

    // --- fixture

    /**
     * A component of the scheme {@code poll} whose consumers count: each built one is {@code c<n>} (from 1), and its
     * {@code "build c<n>"}, {@code "start c<n>"}, {@code "start failed c<n>"}, {@code "stop c<n>"} and {@code "stopped c<n>"}
     * (its stop future completed, logged before the caller sees it) go to the case's log. A consumer is alive from its start call
     * until its stop settled (a failed start: not alive); {@link #peakAlive()} is the most alive at once. A start can be failed
     * ({@link #failStart}), a stop held until the case completes it ({@link #holdStop}).
     */
    static final class Pollers implements Component {
        final List<Poller> built = new CopyOnWriteArrayList<>();
        private final List<String> log;
        private final Map<Integer, String> startFailures = new ConcurrentHashMap<>();
        private final Map<Integer, Promise<Void>> heldStops = new ConcurrentHashMap<>();
        private final Map<Integer, Promise<Void>> heldStarts = new ConcurrentHashMap<>();
        private final Map<Integer, String> stopFailures = new ConcurrentHashMap<>();
        private final AtomicInteger alive = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        Pollers(List<String> log) { this.log = log; }

        @Override public String scheme() { return "poll"; }

        @Override public String configSchema() { return "{}"; }

        @Override
        public synchronized Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
            Poller p = new Poller(built.size() + 1);
            built.add(p);
            log.add("build c" + p.n);
            return p;
        }

        @Override public Producer producer(EndpointConfig cfg) { throw new UnsupportedOperationException("fixture: pollers only consume"); }

        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

        /** The start of the {@code n}th consumer built fails with {@code message}. */
        void failStart(int n, String message) { startFailures.put(n, message); }

        /** The stop of the {@code n}th consumer built completes only once the case completes the promise returned. */
        Promise<Void> holdStop(int n) {
            Promise<Void> p = Promise.promise();
            heldStops.put(n, p);
            return p;
        }

        /** The start of the {@code n}th consumer built completes only once the case completes the promise returned; it polls from then. */
        Promise<Void> holdStart(int n) {
            Promise<Void> p = Promise.promise();
            heldStarts.put(n, p);
            return p;
        }

        /** The stop of the {@code n}th consumer built fails with {@code message}, logged {@code "stop failed c<n>"}; it polls no more. */
        void failStop(int n, String message) { stopFailures.put(n, message); }

        /** Completes every held stop and every held start. */
        void releaseEveryStop() {
            heldStops.values().forEach(Promise::tryComplete);
            heldStarts.values().forEach(Promise::tryComplete);
        }

        Poller c(int n) { return built.get(n - 1); }

        int alive() { return alive.get(); }

        int peakAlive() { return peak.get(); }

        final class Poller implements Consumer {
            final int n;
            final AtomicInteger starts = new AtomicInteger();
            final AtomicInteger stops = new AtomicInteger();
            final List<Thread> startThreads = new CopyOnWriteArrayList<>();
            volatile boolean running;
            volatile boolean stopSettled;

            Poller(int n) { this.n = n; }

            @Override
            public Future<Void> start() {
                starts.incrementAndGet();
                startThreads.add(Thread.currentThread());
                log.add("start c" + n);
                String failure = startFailures.get(n);
                if (failure != null) {
                    log.add("start failed c" + n);
                    return Future.failedFuture(new IllegalStateException(failure));
                }
                Promise<Void> held = heldStarts.get(n);
                Future<Void> started = held == null ? Future.succeededFuture() : held.future();
                return started.andThen(done -> {
                    running = true;
                    peak.accumulateAndGet(alive.incrementAndGet(), Math::max);
                });
            }

            @Override
            public Future<Void> stop() {
                stops.incrementAndGet();
                log.add("stop c" + n);
                Promise<Void> held = heldStops.get(n);
                Future<Void> stop = held == null ? Future.succeededFuture() : held.future();
                String failure = stopFailures.get(n);
                return stop.transform(done -> {
                    if (running) {
                        running = false;
                        alive.decrementAndGet();
                    }
                    stopSettled = true;
                    if (failure == null) {
                        log.add("stopped c" + n);
                        return Future.succeededFuture();
                    }
                    log.add("stop failed c" + n);
                    return Future.failedFuture(new IllegalStateException(failure));
                });
            }
        }
    }

    /**
     * Moves the clock to the next tick, which must be the one task the timer holds and due at {@code due}, and returns its hold,
     * the {@code n}th: called at {@code due} (t0).
     */
    Hold tickAt(Instant due, int n) {
        assertEquals(1, timer.pending(), "between ticks the next tick is the one pending task (a settled hold's bound is cancelled); log " + log);
        assertEquals(Duration.between(clock.instant(), due), timer.nextDelay(), "the next tick is due at " + due + "; now " + clock.instant() + ", log " + log);
        clock.advance(timer.nextDelay());
        Hold h = leases.hold(n);
        assertEquals(due, h.at(), "hold #" + n + " is called when its tick runs");
        assertEquals(List.of(KEY, OWNER, TTL), List.of(h.key(), h.owner(), h.ttl()), "every hold names the endpoint's key, this instance and the ttl");
        return h;
    }

    /** {@link #tickAt}, then waits until the tick's thread is quiet: what it does with the answer is done. */
    Hold settledTickAt(Instant due, int n) {
        Hold h = tickAt(due, n);
        quiet(h.thread(), "tick " + n + " at " + due);
        return h;
    }

    static Instant t(long seconds) { return T0.plusSeconds(seconds); }

    /** Calls {@code leased.stop()} on a virtual thread of its own and returns at once; the call awaits the stop's future. */
    OnVirtualThread.Call<Void> stopping() {
        stopped = true;
        return OnVirtualThread.start(() -> leased.stop().await());
    }

    int indexOf(String line) {
        int i = log.indexOf(line);
        assertTrue(i >= 0, "'" + line + "' is in the log: " + log);
        return i;
    }

    /** Every text a log record carries: its message, its parameters and every throwable's message in its cause chain. */
    static List<String> texts(LogRecord r) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(r.getMessage()));
        if (r.getParameters() != null) for (Object p : r.getParameters()) texts.add(String.valueOf(p));
        for (Throwable t = r.getThrown(); t != null; t = t.getCause()) texts.add(String.valueOf(t.getMessage()));
        return texts;
    }

    List<LogRecord> warningsCarrying(String text) {
        return logged.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .filter(r -> texts(r).stream().anyMatch(s -> s.contains(text))).toList();
    }

    // --- start

    /** start() completes at once and schedules tick 0; nothing starts before the hold answered true. */
    @Test
    void startCompletesAtOnceAndStartsNothingBeforeTheHold() {
        leases.next(Answer.HANG);

        Future<Void> started = leased.start();

        assertTrue(started.succeeded(), "start completes at once: a partner or a lease store down never fails a deploy");
        Hold h = tickAt(T0, 1);   // tick 0: due at once, on the timer
        quiet(h.thread(), "tick 0 waits for its hold");
        assertEquals(List.of("build c1", "hold " + OWNER), log, "nothing starts while the hold has not answered");
        h.answer().complete(true);
        until(() -> log.contains("start c1"), "the consumer starts once the hold answered true");
        assertEquals(List.of("build c1", "hold " + OWNER, "held " + OWNER + " true", "start c1"), log);
        assertEquals(0, freshCalls.get(), "the first start uses the consumer given");
    }

    /** A standby (every hold false) never starts a consumer, however many ticks pass. */
    @Test
    void theStandbyInstanceStartsNoConsumer() {
        leases.otherwise(Answer.FALSE);
        leased.start();

        for (int k = 0; k < 4; k++) {
            Hold h = settledTickAt(t(10L * k), k + 1);
            assertEquals(Boolean.FALSE, h.result(), "fixture: another instance holds the lease");
        }

        assertEquals(0, pollers.c(1).starts.get(), "the standby never starts the consumer it was given: " + log);
        assertEquals(0, freshCalls.get(), "nor builds another");
        assertEquals(0, pollers.peakAlive());
    }

    /** The holder renews every renew period and keeps its one consumer polling across many ttls. */
    @Test
    void theHolderRenewsAndPollsAcrossManyTtls() {
        leases.otherwise(Answer.TRUE);
        leased.start();

        for (int k = 0; k < 10; k++) settledTickAt(t(10L * k), k + 1);   // 90 s: 3.6 ttls

        assertEquals(1, pollers.c(1).starts.get(), "started once");
        assertEquals(0, pollers.c(1).stops.get(), "never stopped while every renew succeeds: " + log);
        assertEquals(0, freshCalls.get(), "no other consumer built");
        assertEquals(1, pollers.alive());
    }

    // --- loss, failure, hang

    /** A hold that answers false stops the consumer at once, not at the end of the validity. */
    @Test
    void aLostLeaseStopsTheConsumer() {
        leases.next(Answer.TRUE, Answer.TRUE, Answer.FALSE).otherwise(Answer.FALSE);
        leased.start();
        settledTickAt(T0, 1);
        settledTickAt(t(10), 2);   // valid until T0+35
        assertEquals(0, pollers.c(1).stops.get(), "fixture: running while held");

        settledTickAt(t(20), 3);

        assertTrue(pollers.c(1).stopSettled, "the false at T0+20 stopped the consumer then, while its validity ran to T0+35: " + log);
        settledTickAt(t(30), 4);
        assertEquals(1, pollers.c(1).starts.get(), "nothing starts while the lease is someone else's");
        assertEquals(0, freshCalls.get());
    }

    /**
     * A renew that fails keeps the consumer polling while now &lt; t0 + ttl of the last hold that answered true — t0 read before
     * that hold, not when its answer came — and stops it exactly there; the next tick after a failure comes at min(renew period,
     * validity − now). Tick 0 (t0 = T0) answers true 4 s late: valid until T0+25, not T0+29. Fixed delay: next at T0+14.
     */
    @Test
    void aRenewFailureKeepsPollingUntilValidityThenStopsExactlyThere() {
        leases.next(Answer.HANG).otherwise(Answer.FAIL);
        leased.start();
        Hold first = tickAt(T0, 1);
        quiet(first.thread(), "tick 0 waits for its hold");
        clock.advance(Duration.ofSeconds(4));   // the hold's bound (T0+10) is not due yet
        first.answer().complete(true);
        until(() -> log.contains("start c1"), "the consumer starts on the late true");
        ended(first.thread(), "tick 0 ends");

        settledTickAt(t(14), 2);   // fails: T0+14 < T0+25
        assertEquals(0, pollers.c(1).stops.get(), "a failed renew inside the validity keeps the consumer polling: " + log);
        settledTickAt(t(24), 3);   // fails: next at min(10 s, 1 s)
        assertEquals(0, pollers.c(1).stops.get(), "still inside the validity at T0+24: " + log);

        tickAt(t(25), 4);

        until(() -> pollers.c(1).stops.get() > 0, "at T0+25 = t0 + ttl the consumer is stopped; log " + log);
        assertEquals(0, freshCalls.get());
    }

    /**
     * A renew that never answers is bounded by the renew period on the injected timer: at T0+20 the tick gives up, the consumer
     * keeps polling (valid until T0+25) and the next tick comes at T0+25, where the consumer stops though that hold hangs too.
     */
    @Test
    void aHangingRenewIsBoundedAndStopsAtValidity() {
        leases.next(Answer.TRUE).otherwise(Answer.HANG);
        leased.start();
        settledTickAt(T0, 1);   // valid until T0+25
        Hold hanging = tickAt(t(10), 2);
        quiet(hanging.thread(), "tick 1 waits for its hold");

        clock.advance(RENEW);   // T0+20: the bound fires

        ended(hanging.thread(), "the bound ends the tick whose hold hangs");
        assertEquals(0, pollers.c(1).stops.get(), "T0+20 < T0+25: the consumer keeps polling: " + log);
        assertEquals(1, timer.pending(), "the next tick is scheduled");
        assertEquals(Duration.ofSeconds(5), timer.nextDelay(), "at min(renew period, validity − now) = 5 s");

        clock.advance(Duration.ofSeconds(5));   // T0+25

        Hold atValidity = leases.hold(3);   // waited for, so the cleanup answers it: a hang left open would keep stop() waiting
        assertEquals(t(25), atValidity.at(), "the tick at the validity's end holds, and that hold hangs too: " + log);
        until(() -> pollers.c(1).stops.get() > 0, "at T0+25 the consumer stops, though this tick's hold hangs as well; log " + log);
        quiet(atValidity.thread(), "the tick at T0+25 waits for its hold");
    }

    /**
     * A failed renew at T0+10 brings the next tick at T0+20 (min(10 s, 15 s)); its hold hangs past the validity's end (T0+25).
     * The consumer still stops at T0+25: it polls only while now &lt; t0 + ttl of the last true, whatever hold is in flight then.
     */
    @Test
    void aHoldInFlightAtTheValidityStopsTheConsumerThere() {
        leases.next(Answer.TRUE, Answer.FAIL).otherwise(Answer.HANG);
        leased.start();
        settledTickAt(T0, 1);      // valid until T0+25
        settledTickAt(t(10), 2);   // fails: next at T0+20
        Hold hanging = tickAt(t(20), 3);
        quiet(hanging.thread(), "tick 2 waits for its hold");
        assertEquals(0, pollers.c(1).stops.get(), "fixture: still inside the validity: " + log);

        clock.advance(Duration.ofSeconds(5));   // T0+25

        until(() -> pollers.c(1).stops.get() > 0, "at T0+25 the consumer stops while the hold of T0+20 is still in flight; log " + log);
    }

    // --- regain

    /** A lease regained after a loss starts a fresh consumer from the supplier; the stopped one is never started again. */
    @Test
    void aRegainedLeaseStartsAFreshConsumer() {
        leases.next(Answer.TRUE, Answer.FALSE, Answer.TRUE).otherwise(Answer.TRUE);
        leased.start();
        settledTickAt(T0, 1);
        settledTickAt(t(10), 2);   // lost
        assertTrue(pollers.c(1).stopSettled, "fixture: the loss stopped c1: " + log);
        assertEquals(0, freshCalls.get(), "nothing built before the lease is regained");

        settledTickAt(t(20), 3);   // regained
        until(() -> log.contains("start c2"), "the regained lease starts a consumer");
        settledTickAt(t(30), 4);   // held on: nothing new

        assertEquals(1, pollers.c(1).starts.get(), "the stopped consumer is never started again: " + log);
        assertEquals(1, freshCalls.get(), "one fresh consumer, built at the regain");
        assertEquals(1, pollers.c(2).starts.get());
        assertTrue(indexOf("stopped c1") < indexOf("build c2"), "built after the old one's stop settled: " + log);
    }

    /** Across a loss and a regain, a fresh consumer starts only once the old one's stop settled: never two alive. */
    @Test
    void neverTwoConsumersAliveAcrossALoss() {
        leases.next(Answer.TRUE, Answer.FALSE).otherwise(Answer.TRUE);
        Promise<Void> c1Stops = pollers.holdStop(1);
        leased.start();
        settledTickAt(T0, 1);
        Hold lost = tickAt(t(10), 2);
        until(() -> log.contains("stop c1"), "the loss stops c1");
        quiet(lost.thread(), "the losing tick");

        // while c1's stop has not settled, every tick that comes holds the lease again (true)
        for (int i = 0; i < 3 && timer.pending() == 1; i++) {
            int n = leases.holds().size() + 1;
            clock.advance(timer.nextDelay());
            quiet(leases.hold(n).thread(), "tick " + n + " while c1's stop is held");
        }
        assertFalse(log.contains("start c2"), "no fresh consumer starts before c1's stop settled: " + log);

        c1Stops.complete();
        until(() -> log.contains("start c2") || leases.holds().stream().allMatch(h -> h.thread().getState() == Thread.State.TERMINATED)
                && timer.pending() == 1, "c2 starts, or the next tick is due");
        if (!log.contains("start c2")) {
            int n = leases.holds().size() + 1;
            clock.advance(timer.nextDelay());
            leases.hold(n);
            until(() -> log.contains("start c2"), "the next tick starts c2");
        }

        assertTrue(indexOf("stopped c1") < indexOf("start c2"), "c2 starts after c1's stop settled: " + log);
        assertEquals(1, pollers.peakAlive(), "never two consumers alive at once: " + log);
    }

    // --- stop

    /** stop() stops the consumer, waits for that stop to settle (the file in flight, D-056), then releases the lease. */
    @Test
    void stopStopsTheConsumerThenReleases() throws Exception {
        leases.otherwise(Answer.TRUE);
        Promise<Void> c1Stops = pollers.holdStop(1);
        leased.start();
        settledTickAt(T0, 1);

        OnVirtualThread.Call<Void> stopping = stopping();
        until(() -> log.contains("stop c1"), "stop() stops the consumer");
        quiet(stopping.thread(), "stop() waits for the consumer's stop");

        assertEquals(List.of(), leases.releases(), "nothing is released while the consumer's stop has not settled: " + log);
        assertFalse(stopping.isDone(), "stop() completes only once the consumer stopped");
        c1Stops.complete();
        OnVirtualThread.join(stopping);

        assertEquals(List.of(KEY + " " + OWNER), leases.releases().stream().map(r -> r.key() + " " + r.owner()).toList(), "one release, of its own lease");
        assertTrue(indexOf("stopped c1") < indexOf("release " + KEY + " " + OWNER), "released after the consumer's stop settled: " + log);
        assertEquals(0, timer.pending(), "the next tick is cancelled");
        int holds = leases.holds().size();
        clock.advance(RENEW.multipliedBy(3));
        assertEquals(holds, leases.holds().size(), "no tick after the stop");
    }

    /** A release that fails is logged (the lease lapses after its ttl) and the stop succeeds. */
    @Test
    void aFailedReleaseIsLoggedAndTheStopStillSucceeds() throws Exception {
        leases.otherwise(Answer.TRUE);
        leases.failReleases("the lease store went away");
        leased.start();
        settledTickAt(T0, 1);

        OnVirtualThread.join(stopping());

        assertEquals(1, leases.releases().size(), "the release was asked");
        assertTrue(pollers.c(1).stopSettled, "the consumer stopped");
        until(() -> !warningsCarrying("the lease store went away").isEmpty(), "the failed release is logged at WARNING or above");
    }

    /** An instance that never held the lease releases nothing when it stops. */
    @Test
    void stopOnStandbyReleasesNothing() throws Exception {
        leases.otherwise(Answer.FALSE);
        leased.start();
        settledTickAt(T0, 1);

        OnVirtualThread.join(stopping());

        assertEquals(List.of(), leases.releases(), "a standby holds nothing, so it releases nothing (another node's lease stays): " + log);
        assertEquals(0, timer.pending(), "the next tick is cancelled");
    }

    /**
     * A stop while a tick waits for its hold waits for that tick; the hold then answers true: the stop leaves nothing running —
     * every consumer started is stopped, no tick is pending — and releases the lease that hold took.
     */
    @Test
    void aStopDuringATickLeavesNothingRunning() throws Exception {
        leases.next(Answer.HANG).otherwise(Answer.TRUE);
        leased.start();
        Hold inFlight = tickAt(T0, 1);
        quiet(inFlight.thread(), "tick 0 waits for its hold");

        OnVirtualThread.Call<Void> stopping = stopping();
        quiet(stopping.thread(), "stop() waits for the tick in flight");
        assertFalse(stopping.isDone(), "stop() awaits the tick in flight");
        inFlight.answer().complete(true);
        OnVirtualThread.join(stopping);

        assertEquals(0, pollers.alive(), "nothing runs after the stop: " + log);
        for (Pollers.Poller p : pollers.built) {
            if (p.starts.get() > 0) assertTrue(p.stopSettled, "c" + p.n + " started, so the stop stopped it: " + log);
        }
        assertEquals(0, timer.pending(), "no tick is left pending: " + log);
        assertEquals(List.of(KEY + " " + OWNER), leases.releases().stream().map(r -> r.key() + " " + r.owner()).toList(),
                "the lease the in-flight tick took is released: " + log);
    }

    /**
     * A consumer whose start fails is a WARNING; it is stopped, and once that stop settled the lease is released (review r1, as
     * Deployments does with a consumer that did not start); the next tick retries with a fresh consumer.
     */
    @Test
    void aConsumerStartFailureReleasesAndRetries() {
        leases.otherwise(Answer.TRUE);
        pollers.failStart(1, "the partner refused the session");
        Promise<Void> c1Stops = pollers.holdStop(1);
        leased.start();

        Hold h = tickAt(T0, 1);
        until(() -> log.contains("stop c1"), "the consumer whose start failed is stopped: " + log);
        quiet(h.thread(), "tick 0 waits for c1's stop");
        assertFalse(log.contains("release " + KEY + " " + OWNER), "no release before c1's stop settled: " + log);
        c1Stops.complete();
        ended(h.thread(), "tick 0 ends");

        assertTrue(log.contains("start failed c1"), "fixture: c1's start failed: " + log);
        assertTrue(indexOf("start failed c1") < indexOf("stop c1") && indexOf("stopped c1") < indexOf("release " + KEY + " " + OWNER),
                "the failed consumer is stopped, then the lease is released: " + log);
        assertTrue(warningsCarrying("the partner refused the session").stream().anyMatch(r -> r.getLevel() == Level.WARNING),
                "the failed start is a WARNING carrying its cause: " + logged.stream().map(LogRecord::getMessage).toList());

        settledTickAt(t(10), 2);
        until(() -> log.contains("start c2"), "the next tick retries");
        assertEquals(1, pollers.c(1).starts.get(), "the failed consumer is not started again");
        assertEquals(1, freshCalls.get(), "the retry builds a fresh consumer");
    }

    /** Every tick runs on a virtual thread of its own, never on the timer's thread (here: the test thread that moves the clock). */
    @Test
    void ticksRunOnVirtualThreads() {
        leases.otherwise(Answer.TRUE);
        leased.start();

        Hold h0 = settledTickAt(T0, 1);
        Hold h1 = settledTickAt(t(10), 2);

        assertTrue(h0.thread().isVirtual() && h1.thread().isVirtual(), "ticks hold on virtual threads: " + h0.thread() + ", " + h1.thread());
        assertNotSame(Thread.currentThread(), h0.thread(), "never on the timer's thread");
        assertNotSame(h0.thread(), h1.thread(), "each tick on its own thread");
        assertTrue(pollers.c(1).startThreads.getFirst().isVirtual(), "the consumer is started from the tick's virtual thread");
    }

    // --- adopted from the implementer's scratch (P2-02L verify): the guards the cases above leave unpinned

    /**
     * A timer whose cancel can come too late, as a real timer's does: once {@link #late}, a cancel finds its task already taken by
     * the timer's thread — it answers false and the task runs when the case says so ({@link #taken}).
     */
    static final class LateCancel implements Timer {
        final FakeTimer fake;
        final List<Runnable> taken = new CopyOnWriteArrayList<>();
        volatile boolean late;

        LateCancel(FakeTimer fake) { this.fake = fake; }

        @Override
        public Cancellable schedule(Duration delay, Runnable task) {
            Cancellable pending = fake.schedule(delay, task);
            return () -> {
                if (!late) return pending.cancel();
                if (pending.cancel()) taken.add(task);   // the timer's thread holds it: it fires all the same
                return false;
            };
        }
    }

    /**
     * The next tick fires as stop() cancels it (the cancel comes too late): that tick holds nothing, starts nothing, schedules
     * nothing, and a stop asked after it waits for nothing.
     */
    @Test
    void aTickFiredAsStopCancelledItRunsNothing() throws Exception {
        LateCancel late = new LateCancel(timer);
        leased = new LeasedConsumer(leases, KEY, OWNER, TTL, RENEW, late, clock, pollers.c(1), fresh);   // c1, on the late timer
        leased.start();
        leases.otherwise(Answer.TRUE);
        clock.advance(Duration.ZERO);
        quiet(leases.hold(1).thread(), "tick 0");
        assertEquals(1, timer.pending(), "fixture: the next tick is pending");

        late.late = true;
        stopped = true;
        OnVirtualThread.join(OnVirtualThread.start(() -> leased.stop().await()));
        assertEquals(1, late.taken.size(), "fixture: the timer's thread took the next tick as stop() cancelled it");
        leases.next(Answer.HANG);
        late.taken.getFirst().run();   // the timer's thread runs it now

        assertTrue(leased.stop().isComplete(), "the late tick is no tick in flight: a stop asked now waits for nothing: " + log);
        assertEquals(1, leases.holds().size(), "the late tick held nothing: " + log);
        assertEquals(List.of(1, 0), List.of(pollers.c(1).starts.get(), freshCalls.get()), "and started nothing, built nothing: " + log);
        assertEquals(0, timer.pending(), "and scheduled nothing: " + log);
    }

    /**
     * A stop before any start holds, starts and releases nothing and completes at once; a start after it runs no tick — a tick that
     * ran would still be in flight (its hold hangs), and a stop asked after it would wait for it.
     */
    @Test
    void aStopBeforeTheStartDoesNothing() {
        stopped = true;
        assertTrue(leased.stop().succeeded(), "nothing to wait for");
        leases.otherwise(Answer.HANG);

        leased.start();
        clock.advance(RENEW.multipliedBy(3));

        assertTrue(leased.stop().isComplete(), "no tick ran: a stop asked now waits for nothing: " + log);
        assertEquals(List.of("build c1"), log, "no hold, no start, no release: " + log);
    }

    /**
     * c1's start fails at T0 and the lease, valid until T0+25, is released; the hold at T0+10 fails: no consumer is built or
     * started on it — the released lease's validity counts no more, only a hold confirmed true starts one.
     */
    @Test
    void afterAFailedStartAFailedHoldStartsNothing() {
        leased.start();
        leases.next(Answer.TRUE, Answer.FAIL).otherwise(Answer.FALSE);
        pollers.failStart(1, "the partner refused the session");
        clock.advance(Duration.ZERO);
        quiet(leases.hold(1).thread(), "tick 0");
        assertTrue(log.contains("release " + KEY + " " + OWNER), "fixture: the failed start released the lease: " + log);
        assertEquals(RENEW, timer.nextDelay(), "fixture: the next tick at the renew period");

        clock.advance(RENEW);
        Hold failed = leases.hold(2);
        quiet(failed.thread(), "tick 1");

        assertEquals(Boolean.FALSE, failed.result(), "fixture: the hold at T0+10 failed");
        assertEquals(0, freshCalls.get(), "no consumer is built on a failed hold: " + log);
        assertEquals(0, pollers.alive(), "none polls: " + log);
    }

    // --- review r1

    /**
     * Tick 0 holds at T0 (valid until T0+25) and c1's start takes 30 s: the tick reads the clock again once the start completed,
     * finds the validity over and stops c1 at once — it never polls past its validity; with none running the next tick comes a
     * renew period after now.
     */
    @Test
    void aStartThatOutlastsTheValidityStopsTheConsumerAtOnce() {
        leases.otherwise(Answer.TRUE);
        Promise<Void> c1Starts = pollers.holdStart(1);
        leased.start();
        Hold h = tickAt(T0, 1);
        until(() -> log.contains("start c1"), "tick 0 holds and starts c1");
        quiet(h.thread(), "tick 0 waits for c1's start");
        assertEquals(0, timer.pending(), "fixture: nothing is due while tick 0 waits (the hold's bound was cancelled): " + log);

        clock.advance(TTL.plusSeconds(5));
        c1Starts.complete();
        ended(h.thread(), "tick 0 ends");

        assertTrue(log.contains("stopped c1"), "past its validity c1 is stopped by tick 0 itself, not by a later tick: " + log);
        assertEquals(0, pollers.alive(), "nothing polls past the validity: " + log);
        assertEquals(1, timer.pending(), "the next tick is the one pending task: " + log);
        assertEquals(RENEW, timer.nextDelay(), "none running: the next tick a renew period after now, T0+40: " + log);
    }

    /**
     * Tick 0 holds at T0 (valid until T0+25) and c1's start takes 20 s: the next tick is at min(renew, validUntil − now) with now
     * read after the start — T0+25, 5 s on — not 10 s after it (T0+30, five seconds of polling past the validity).
     */
    @Test
    void theNextTickIsMeasuredFromTheClockReadAfterTheStart() {
        leases.otherwise(Answer.TRUE);
        Promise<Void> c1Starts = pollers.holdStart(1);
        leased.start();
        Hold h = tickAt(T0, 1);
        until(() -> log.contains("start c1"), "tick 0 holds and starts c1");
        quiet(h.thread(), "tick 0 waits for c1's start");

        clock.advance(Duration.ofSeconds(20));
        c1Starts.complete();
        ended(h.thread(), "tick 0 ends");

        assertEquals(1, pollers.alive(), "c1 polls: T0+20 is within the validity: " + log);
        assertEquals(1, timer.pending(), "the next tick is the one pending task: " + log);
        assertEquals(Duration.ofSeconds(5), timer.nextDelay(), "the next tick at the validity's end, T0+25: " + log);
    }

    /**
     * A failed hold is a WARNING only on the change of state — the first failure after a hold confirmed true — not on every tick
     * that fails again; a hold confirmed true again re-arms it.
     */
    @Test
    void aFailedHoldWarnsOnceOnTheChangeOfStateNotOnEveryTick() {
        leases.next(Answer.TRUE, Answer.FAIL, Answer.FAIL, Answer.FAIL, Answer.TRUE, Answer.FAIL);
        leased.start();
        settledTickAt(T0, 1);
        assertEquals(List.of(), warningsCarrying(LeaseLog.DOWN), "fixture: a confirmed hold warns nothing");

        settledTickAt(t(10), 2);
        assertEquals(1, warningsCarrying(LeaseLog.DOWN).size(), "the first failure after a confirmed hold is a WARNING: " + messages());
        settledTickAt(t(20), 3);
        settledTickAt(t(25), 4);   // the validity's end: c1 stopped; the hold fails again
        assertEquals(1, warningsCarrying(LeaseLog.DOWN).size(), "failing again is no change of state: no WARNING per tick: " + messages());

        settledTickAt(t(35), 5);   // held again: c2 polls
        settledTickAt(t(45), 6);
        assertEquals(2, warningsCarrying(LeaseLog.DOWN).size(), "the first failure after the regain warns again: " + messages());
    }

    /** A standby that never held, its lease store failing tick after tick, logs no WARNING with a stack trace, nor one per tick. */
    @Test
    void aStandbyThatNeverHeldLogsNoStackTrace() {
        leases.next(Answer.FALSE, Answer.FAIL, Answer.FAIL, Answer.FAIL);
        leased.start();
        settledTickAt(T0, 1);
        settledTickAt(t(10), 2);
        settledTickAt(t(20), 3);
        settledTickAt(t(30), 4);

        assertEquals(0, pollers.c(1).starts.get(), "fixture: the standby never polled: " + log);
        assertEquals(List.of(), logged.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue() && r.getThrown() != null)
                .map(LogRecord::getMessage).toList(), "a standby that never held logs no WARNING with a stack trace");
        assertTrue(warningsCarrying(LeaseLog.DOWN).size() <= 1, "nor a WARNING per failing tick: " + messages());
    }

    /**
     * Reviewer mutant M2: stop() releases the lease even when the inner consumer's stop fails (after it settled), and completes as
     * that stop did.
     */
    @Test
    void aFailedInnerStopStillReleasesTheLease() {
        leases.otherwise(Answer.TRUE);
        pollers.failStop(1, "the session broke at close");
        leased.start();
        settledTickAt(T0, 1);
        assertEquals(1, pollers.alive(), "fixture: c1 polls");

        stopped = true;
        Future<Void> stop = leased.stop();
        until(stop::isComplete, "stop() settles");

        assertTrue(stop.failed() && "the session broke at close".equals(stop.cause().getMessage()), "stop() completes as c1's stop did: " + stop.cause());
        assertTrue(indexOf("stop failed c1") < indexOf("release " + KEY + " " + OWNER), "the lease is released after c1's failed stop all the same: " + log);
    }

    /** Every record's level and message, for a failure message. */
    List<String> messages() {
        return logged.stream().map(r -> r.getLevel() + " " + r.getMessage() + (r.getThrown() == null ? "" : " [" + r.getThrown() + "]")).toList();
    }

    // --- adopted from the implementer's scratch (P2-02L r2 verify): R9, R10, R11

    /** R9: the first failed hold of an instance whose consumer polls is a WARNING carrying the failure as its stack trace. */
    @Test
    void aHolderWhoseHoldFailsWarnsWithTheStackTrace() {
        leases.next(Answer.TRUE, Answer.FAIL);
        leased.start();
        settledTickAt(T0, 1);
        assertEquals(1, pollers.alive(), "fixture: c1 polls");

        settledTickAt(t(10), 2);

        List<LogRecord> warnings = logged.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()).toList();
        assertEquals(1, warnings.size(), "one WARNING: " + messages());
        Throwable thrown = warnings.getFirst().getThrown();
        assertTrue(thrown != null && LeaseLog.DOWN.equals(thrown.getMessage()), "with the failure's stack trace: " + thrown);
    }

    /** A timer whose {@code failAt}th schedule throws (the earlier and later ones go to the fake timer). */
    static final class FailingTimer implements Timer {
        final FakeTimer fake;
        final int failAt;
        final AtomicInteger calls = new AtomicInteger();

        FailingTimer(FakeTimer fake, int failAt) {
            this.fake = fake;
            this.failAt = failAt;
        }

        @Override
        public Cancellable schedule(Duration delay, Runnable task) {
            if (calls.incrementAndGet() == failAt) throw new IllegalStateException("the timer is closing");
            return fake.schedule(delay, task);
        }
    }

    /**
     * R10: the schedules are tick 0 (start), tick 0's hold bound, the next tick — that one throws. Tick 0 ends all the same: a
     * stop() asked then stops c1 and releases the lease instead of waiting for ever.
     */
    @Test
    void aTickWhoseReschedulingThrowsStillEnds() {
        FailingTimer failing = new FailingTimer(timer, 3);
        leased = new LeasedConsumer(leases, KEY, OWNER, TTL, RENEW, failing, clock, pollers.c(1), fresh);   // c1, on the failing timer
        leased.start();
        leases.otherwise(Answer.TRUE);
        clock.advance(Duration.ZERO);
        ended(leases.hold(1).thread(), "tick 0 ends, its rescheduling thrown");
        assertEquals(3, failing.calls.get(), "fixture: the third schedule was the one that threw");

        stopped = true;
        Future<Void> stop = leased.stop();
        until(stop::isComplete, "stop() does not wait for a tick that ended: " + log);
        assertTrue(stop.succeeded(), "c1 stopped: " + stop.cause());
        assertTrue(log.contains("stopped c1") && log.contains("release " + KEY + " " + OWNER), "c1 stopped, the lease released: " + log);
    }

    /**
     * R11: held, lost (c1 stopped), held again — and the component refuses to build a fresh consumer. That is a failed start (a
     * WARNING carrying the refusal, the lease released), not a tick that dies holding it; the next tick, held, asks again.
     */
    @Test
    void aFreshConsumerTheComponentRefusesIsAFailedStart() {
        AtomicInteger refusals = new AtomicInteger();
        leased = new LeasedConsumer(leases, KEY, OWNER, TTL, RENEW, timer, clock, pollers.c(1), () -> {
            refusals.incrementAndGet();
            throw new IllegalArgumentException("the endpoint is refused");
        });
        leases.next(Answer.TRUE, Answer.FALSE, Answer.TRUE, Answer.TRUE);
        leased.start();
        settledTickAt(T0, 1);
        settledTickAt(t(10), 2);
        assertTrue(log.contains("stopped c1"), "fixture: the loss stopped c1: " + log);

        settledTickAt(t(20), 3);

        assertEquals(1, refusals.get(), "the component was asked once: " + log);
        assertEquals("release " + KEY + " " + OWNER, log.getLast(), "the lease is released after the refusal: " + log);
        assertTrue(logged.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getThrown() != null
                && "the endpoint is refused".equals(r.getThrown().getMessage())), "a WARNING carries the refusal: " + messages());
        assertEquals(1, timer.pending(), "the next tick is scheduled: " + log);
        assertEquals(RENEW, timer.nextDelay(), "a renew period on: " + log);

        settledTickAt(t(30), 4);
        assertEquals(2, refusals.get(), "the next tick asks the component again: " + log);
    }
}
