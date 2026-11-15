package io.flottila.runtime.deploy;

import io.flottila.api.Consumer;
import io.flottila.api.Timer;
import io.flottila.runtime.lease.PollerLeases;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A polling consumer run under its endpoint's poller lease (D-083): the inner consumer polls only between a hold confirmed true
 * and t0 + ttl, t0 read before that hold was asked.
 *
 * <p>{@link #start} completes at once and schedules tick 0 on the timer. Each tick runs on a virtual thread of its own, the next
 * one scheduled when it ended (fixed delay): it reads t0; stops the inner consumer if its validity ran out (no hold confirmed in
 * time); asks the lease store to hold the key, the wait bounded on the timer by the renew period — while a consumer runs, by the
 * validity left at t0 if that is shorter — the bound cancelled once the answer came. True: valid until t0 + ttl, and an inner
 * consumer starts if none runs — the one given first, a fresh one from the supplier after that (a stopped consumer is never
 * started again; a new one starts only once the old one's stop settled, which the tick awaits). False: the inner consumer stops at
 * once. Failed or no answer within the bound: the inner consumer polls on while now &lt; the validity, else stops; that is a
 * WARNING on the change of state only — the first failure since an answer, its stack trace only while a consumer runs — and FINE
 * on every tick that fails again. After an inner start the clock is read again: a start that outlasted the validity stops the
 * consumer at once. An inner start that fails is a WARNING; that consumer is stopped, once its stop settled the lease is released,
 * and the next tick tries again. The next tick comes after the renew period, while a consumer runs no later than its validity's
 * end, both measured from the last clock read.
 *
 * <p>{@link #stop} cancels the next tick, waits for a tick in flight, stops the inner consumer that runs (its stop awaits the file
 * in flight, D-056), then releases the lease — a failed release is a WARNING, the lease lapses after its ttl — and completes as
 * that inner stop did. An instance whose consumer does not run (never held, lost, lapsed) releases nothing.
 */
final class LeasedConsumer implements Consumer {

    private static final System.Logger LOG = System.getLogger(LeasedConsumer.class.getName());
    /** No validity: nothing held yet, lost, or released. */
    private static final Instant NONE = Instant.MIN;

    private enum Outcome { HELD, REFUSED, UNKNOWN }

    private final PollerLeases leases;
    private final String key;
    private final String owner;
    private final Duration ttl;
    private final Duration renewEvery;
    private final Timer timer;
    private final Clock clock;
    private final Supplier<Consumer> fresh;

    // written by the ticks, which run one at a time, and by stop() once the tick in flight ended
    /** The consumer given, until its first start. */
    private volatile Consumer unused;
    /** The inner consumer started and not asked to stop: it polls. */
    private volatile Consumer inner;
    /** t0 + ttl of the last hold confirmed true, {@link #NONE} after a loss or a release. */
    private volatile Instant validUntil = NONE;
    /** The last hold failed or found no answer in time: a failure again is no change of state. */
    private volatile boolean failing;

    // guarded by this
    private boolean stopping;
    private Timer.Cancellable next = () -> false;
    /** Completes once the tick in flight ended; complete when none runs. */
    private Future<Void> ticking = Future.succeededFuture();

    LeasedConsumer(PollerLeases leases, String key, String owner, Duration ttl, Duration renewEvery, Timer timer, Clock clock, Consumer first,
                   Supplier<Consumer> fresh) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.key = Objects.requireNonNull(key, "key");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.renewEvery = Objects.requireNonNull(renewEvery, "renewEvery");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.unused = Objects.requireNonNull(first, "first");
        this.fresh = Objects.requireNonNull(fresh, "fresh");
    }

    /** Schedules tick 0 and completes at once: a partner or a lease store down never fails a deploy. Called once. */
    @Override
    public synchronized Future<Void> start() {
        next = timer.schedule(Duration.ZERO, this::fire);
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> stop() {
        Future<Void> tick;
        synchronized (this) {
            stopping = true;
            next.cancel();
            tick = ticking;
        }
        return tick.compose(ended -> {
            Consumer running = inner;
            inner = null;
            if (running == null) return Future.succeededFuture();   // it holds nothing: another node's lease stays
            return call(running::stop).eventually(this::release);
        });
    }

    /** The timer's task: the tick on a virtual thread of its own, never on the timer's thread. */
    private void fire() {
        Promise<Void> ended = Promise.promise();
        synchronized (this) {
            if (stopping) return;   // fired as stop() cancelled it
            ticking = ended.future();
        }
        Thread.ofVirtual().name("flottila-lease-" + owner).start(() -> tick(ended));
    }

    private void tick(Promise<Void> ended) {
        Duration delay = renewEvery;
        try {
            try {
                Instant t0 = clock.instant();
                if (!t0.isBefore(validUntil)) stopInner();   // its validity ran out while no hold was confirmed
                Outcome answer = hold(upTo(t0));
                failing = answer == Outcome.UNKNOWN;
                if (answer == Outcome.HELD) validUntil = t0.plus(ttl);
                else if (answer == Outcome.REFUSED) validUntil = NONE;   // lost: another owner holds it
                Instant now = clock.instant();
                if (inner == null && now.isBefore(validUntil)) {   // valid with none running: this tick's hold was confirmed
                    startInner();
                    now = clock.instant();   // a start can take long: the validity left is measured after it
                }
                if (!now.isBefore(validUntil)) stopInner();
                delay = upTo(now);
            } finally {
                synchronized (this) {
                    if (!stopping) next = timer.schedule(delay, this::fire);
                }
            }
        } finally {
            ended.complete();   // whatever the scheduling did: a stop() waiting on this tick never waits for ever
        }
    }

    /** The renew period, cut to the validity left at {@code at} while a consumer runs: no wait outlasts that validity. */
    private Duration upTo(Instant at) {
        if (inner == null) return renewEvery;
        Duration left = Duration.between(at, validUntil);
        return left.compareTo(renewEvery) < 0 ? left : renewEvery;
    }

    /** Asks the store to hold the key, the wait bounded by {@code bound} on the timer; a failure or no answer in time is logged. */
    private Outcome hold(Duration bound) {
        Promise<Boolean> answered = Promise.promise();
        Timer.Cancellable limit = timer.schedule(bound, () -> answered.tryFail(new TimeoutException("no answer within " + bound)));
        Future.<Boolean>future(asked -> leases.hold(key, owner, ttl).onComplete(asked)).onComplete(answered::tryComplete, answered::tryFail);
        return answered.future().andThen(settled -> limit.cancel())
                .map(held -> held ? Outcome.HELD : Outcome.REFUSED)
                .otherwise(this::unanswered).await();
    }

    /**
     * A hold that failed or found no answer in time: a WARNING on the change of state (the first since an answer) — with its stack
     * trace while a consumer runs on it, without on a standby — and FINE when it fails again.
     */
    private Outcome unanswered(Throwable failed) {
        String what = "the poller lease " + key + " was not held by " + owner + " (" + failed + ")";
        if (failing) LOG.log(System.Logger.Level.DEBUG, what + "; it failed before", failed);
        else if (inner != null) LOG.log(System.Logger.Level.WARNING, what + "; its consumer polls on until its last hold lapses", failed);
        else LOG.log(System.Logger.Level.WARNING, what + "; no consumer of it polls, and the next tick asks again");
        return Outcome.UNKNOWN;
    }

    /**
     * Starts the consumer given, or a fresh one once that was used; one that does not start is a WARNING, it is stopped, and once
     * that stop settled the lease is released.
     */
    private void startInner() {
        Future<Consumer> taken = Future.future(done -> done.complete(take()));   // the supplier's throw fails it
        Future<Void> started = taken.compose(c -> call(c::start));
        started.otherwiseEmpty().await();
        if (started.succeeded()) {
            inner = taken.result();
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "the consumer under the poller lease " + key + " of " + owner + " did not start (" + started.cause()
                + "); it is stopped, the lease is released and the next tick tries again", started.cause());
        // a consumer whose start failed may hold a session half open; none was taken if the supplier threw
        taken.compose(c -> settled(call(c::stop), "the consumer under the poller lease " + key + " of " + owner + " that did not start "
                + "did not stop cleanly")).otherwiseEmpty().await();
        release().await();
        validUntil = NONE;
    }

    /** Releases the lease; completes once that settled, never failed: a failure is a WARNING, the lease lapses after its ttl. */
    private Future<Void> release() {
        return settled(call(() -> leases.release(key, owner)),
                "the poller lease " + key + " of " + owner + " was not released; it lapses after its ttl " + ttl);
    }

    private Consumer take() {
        Consumer given = unused;
        unused = null;
        return given != null ? given : fresh.get();
    }

    /** Stops the inner consumer, if one runs, and waits until that stop settled (a failure logged). */
    private void stopInner() {
        Consumer running = inner;
        if (running == null) return;
        inner = null;
        settled(call(running::stop), "the consumer under the poller lease " + key + " of " + owner + " did not stop cleanly").await();
    }

    /** {@code call}'s future; a throw fails it. */
    private static Future<Void> call(Supplier<Future<Void>> call) {
        return Future.future(done -> call.get().onComplete(done));
    }

    /** {@code call} once settled, never failed: a failure is a WARNING saying {@code what}. */
    private static Future<Void> settled(Future<Void> call, String what) {
        return call.onFailure(failed -> LOG.log(System.Logger.Level.WARNING, what + ": " + failed, failed)).otherwiseEmpty();
    }
}
