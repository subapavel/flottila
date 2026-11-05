package io.flottila.bench;

import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * The open-loop load of CORE-15 (GATE.md §CORE-GATE condition 2): request i is sent at an instant fixed in advance by the
 * {@link Schedule}, whatever the answers to the requests before it — the loop never waits for an answer, so a slow target shows
 * as latency and backlog, not as a lower offered rate. At most {@code maxOutstanding} requests are in flight: a request due
 * while the bound is full is never sent and counts {@code clientSaturated}, and the run is then invalid (the client, not the
 * target, set the rate).
 *
 * <p>Sends run on {@code timer} at each due instant read from {@code clock}; a timer that fires late sends everything due by
 * then at once. Every latency runs from the request's <em>scheduled</em> instant to its answer (no coordinated omission).
 * Only requests scheduled inside the window count in the window's figures; the record ({@link #done()}) is complete once the
 * window ended and every request sent has its answer. Thread-safe: answers arrive on any thread.
 *
 * @since 0.1
 */
final class OpenLoop {

    /** One request of the load: sends request {@code index} and answers its HTTP status, or fails. */
    @FunctionalInterface
    interface Target {
        Future<Integer> send(long index);
    }

    /**
     * When each request is due. During the ramp the rate grows linearly from 0 to {@code rate}, so the cumulative count is
     * N(t) = rate·t²/(2·ramp) and request i is due at sqrt(2·ramp·i/rate); after it the rate is constant and request i is due
     * at ramp + (i − rate·ramp/2)/rate. The window is [ramp, ramp + window); nothing is scheduled at or after its end.
     *
     * @param rate the window's rate in requests per second, at least 1
     * @param ramp the ramp's length, zero or more
     * @param window the window's length, more than zero
     */
    record Schedule(int rate, Duration ramp, Duration window) {

        static final long NANOS_PER_SECOND = 1_000_000_000L;

        /** The requests due before the window: those with i < rate·ramp/2. */
        long rampRequests() {
            return Math.ceilDiv(rate * ramp.toNanos(), 2 * NANOS_PER_SECOND);
        }

        /** The requests due inside the window. */
        long windowRequests() {
            return total() - rampRequests();
        }

        /** Every request scheduled: those with i < rate·(ramp/2 + window). */
        long total() {
            return Math.ceilDiv(rate * (ramp.toNanos() + 2 * window.toNanos()), 2 * NANOS_PER_SECOND);
        }

        /** Request {@code i}'s due instant in nanoseconds after the start, rounded to the nearest nanosecond. */
        long dueNanos(long i) {
            if (i < rampRequests()) return Math.round(Math.sqrt(2.0 * ramp.toNanos() * NANOS_PER_SECOND * i / rate));
            long twice = 2 * i * NANOS_PER_SECOND - rate * ramp.toNanos();   // 2·rate·(t_i − ramp) in ns, never negative here
            return ramp.toNanos() + Math.floorDiv(twice + rate, 2L * rate);
        }

        /** Whether request {@code i} is scheduled inside the window. */
        boolean inWindow(long i) {
            return i >= rampRequests() && i < total();
        }

        /** The window's end in nanoseconds after the start. */
        long windowEndNanos() {
            return ramp.toNanos() + window.toNanos();
        }
    }

    private final Schedule schedule;
    private final long maxOutstanding;
    private final int acceptedStatus;
    private final Clock clock;
    private final Timer timer;
    private final Target target;
    private final LongSupplier receipts;
    private final long total;
    /** Backlog samples: the window's start, each second after it, its end. */
    private final long samples;
    private final LatencyStats latency;
    private final Promise<Void> windowStart = Promise.promise();
    private final Promise<Void> windowEnd = Promise.promise();
    private final Promise<JsonObject> done = Promise.promise();

    private Instant start;
    // guarded by this
    private long next;
    private long nextSample;
    private long outstanding;
    private long sent;
    private long sentInWindow;
    private long saturated;
    private long accepted;
    private long acceptedTotal;
    private long refused503;
    private long timeouts;
    private long errors;
    private String firstFailure;
    private final Map<String, Long> refused = new TreeMap<>();
    private final List<Long> backlog = new ArrayList<>();

    /**
     * A loop over {@code schedule} that keeps at most {@code maxOutstanding} requests in flight and counts an answer of
     * {@code acceptedStatus} as accepted; {@code receipts} reads how many requests the partner received so far (the backlog
     * is the accepted requests minus them).
     */
    OpenLoop(Schedule schedule, long maxOutstanding, int acceptedStatus, Clock clock, Timer timer, Target target, LongSupplier receipts) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.maxOutstanding = maxOutstanding;
        this.acceptedStatus = acceptedStatus;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.target = Objects.requireNonNull(target, "target");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.total = schedule.total();
        this.samples = Math.ceilDiv(schedule.window().toNanos(), Schedule.NANOS_PER_SECOND) + 1;
        this.latency = new LatencyStats(Math.toIntExact(schedule.windowRequests()));
    }

    /** Starts the clock: what is due at once is sent on the caller's thread, the rest on the timer. */
    void start() {
        start = clock.instant();
        tick();
    }

    /** Completes when the window starts (its first backlog sample was taken). */
    Future<Void> windowStart() {
        return windowStart.future();
    }

    /** Completes when the window ends (its last backlog sample was taken); answers may still be outstanding. */
    Future<Void> windowEnd() {
        return windowEnd.future();
    }

    /** The run's record once the window ended and every request sent has its answer. */
    Future<JsonObject> done() {
        return done.future();
    }

    private long elapsed() {
        return Duration.between(start, clock.instant()).toNanos();
    }

    private long sampleDue(long k) {
        return schedule.ramp().toNanos() + Math.min(k * Schedule.NANOS_PER_SECOND, schedule.window().toNanos());
    }

    /** Sends every request due by now, takes every backlog sample due by now, then waits on the timer for the next one. */
    private void tick() {
        long now = elapsed();
        while (true) {
            long i;
            synchronized (this) {
                if (next == total || schedule.dueNanos(next) > now) break;
                i = next++;
                if (outstanding == maxOutstanding) {
                    if (schedule.inWindow(i)) saturated++;
                    continue;
                }
                outstanding++;
                if (schedule.inWindow(i)) {
                    sent++;
                    if (now < schedule.windowEndNanos()) sentInWindow++;
                }
            }
            long due = schedule.dueNanos(i);
            target.send(i).onComplete(status -> answered(i, due, status, null), failure -> answered(i, due, 0, failure));
        }
        long wake;
        synchronized (this) {
            while (nextSample < samples && sampleDue(nextSample) <= now) {
                backlog.add(acceptedTotal - receipts.getAsLong());
                nextSample++;
                if (nextSample == 1) windowStart.complete();
                if (nextSample == samples) windowEnd.complete();
            }
            wake = nextSample < samples ? sampleDue(nextSample) : Long.MAX_VALUE;
            if (next < total) wake = Math.min(wake, schedule.dueNanos(next));
        }
        if (wake != Long.MAX_VALUE) {
            timer.schedule(Duration.ofNanos(wake - now), this::tick);
        }
        complete();
    }

    /** Request {@code i}'s answer: a status, or a failure (status 0). */
    private void answered(long i, long due, int status, Throwable failure) {
        long at = elapsed();
        synchronized (this) {
            outstanding--;
            if (failure == null && status == acceptedStatus) acceptedTotal++;
            if (schedule.inWindow(i)) {
                if (failure != null) {
                    if (failure instanceof TimeoutException) {
                        timeouts++;
                    } else {
                        errors++;
                        if (firstFailure == null) firstFailure = String.valueOf(failure);
                    }
                } else if (status == acceptedStatus) {
                    accepted++;
                    latency.record(at - due);
                } else if (status == 503) {
                    refused503++;
                } else {
                    refused.merge(String.valueOf(status), 1L, Long::sum);
                }
            }
        }
        complete();
    }

    /**
     * Completes {@link #done()} once every request was processed, every sample taken and no answer is outstanding: nothing
     * changes the counts after that, so the timer's last tick and the last answer, racing here, build the same record.
     */
    private void complete() {
        JsonObject record;
        synchronized (this) {
            if (next < total || nextSample < samples || outstanding > 0) return;
            record = record();
        }
        done.tryComplete(record);
    }

    private JsonObject record() {
        double windowSeconds = schedule.window().toNanos() / (double) Schedule.NANOS_PER_SECOND;
        long scheduled = schedule.windowRequests();
        double achieved = sentInWindow / windowSeconds;
        double expected = scheduled / windowSeconds;   // the offered rate whenever rate × window is a whole number
        JsonArray reasons = new JsonArray();
        if (saturated > 0) {
            reasons.add("clientSaturated " + saturated + ": requests due while " + maxOutstanding + " were in flight were not sent");
        }
        if (Math.abs(achieved - expected) > expected / 100) {
            reasons.add(String.format(Locale.ROOT, "achievedRate %.3f/s is more than 1 %% off the scheduled %.3f/s", achieved, expected));
        }
        long growth = backlog.getLast() - backlog.getFirst();
        boolean measured = latency.count() > 0;
        JsonObject refusedByStatus = new JsonObject();
        refused.forEach(refusedByStatus::put);
        return new JsonObject().put("offeredRate", schedule.rate()).put("scheduled", scheduled).put("sent", sent)
                .put("achievedRate", achieved).put("accepted", accepted).put("acceptedTotal", acceptedTotal).put("refused", refusedByStatus)
                .put("refused503", refused503).put("timeouts", timeouts).put("errors", errors).put("firstFailure", firstFailure)
                .put("clientSaturated", saturated)
                .put("p50Ms", measured ? latency.percentileMillis(50) : null).put("p99Ms", measured ? latency.percentileMillis(99) : null)
                .put("p999Ms", measured ? latency.percentileMillis(99.9) : null).put("maxMs", measured ? latency.maxMillis() : null)
                .put("receipts", receipts.getAsLong()).put("backlog", new JsonArray(new ArrayList<>(backlog))).put("backlogGrowth", growth)
                .put("backlogGrowthPercent", accepted > 0 ? 100.0 * growth / accepted : null)
                .put("valid", reasons.isEmpty()).put("invalidReasons", reasons);
    }
}
