package io.flottila.core.node;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Membership} the test scripts (the store boundary of {@link NodeLease}, P2-15a): every join succeeds with the next
 * epoch; every renew is recorded with the instant it was sent and answered by the current {@link Mode} — {@code OK} (renewed,
 * naming the {@link #expired} peers), {@code FAIL} (a failed future: the database unreachable), {@code HANG} (a pending future
 * the test answers later by {@link #answer}), {@code SUPERSEDED} (renewed = false: the row was released or re-joined); every
 * release is recorded with its instant and answers the peers it was given.
 *
 * <p>The API assumed (P2-15a, test-author): {@code Membership.join(String node, Duration lease)} → {@code Future<Long>} (the new
 * epoch), {@code Membership.renew(String node, long epoch, Duration lease)} → {@code Future<Membership.Renewal>} with
 * {@code Renewal(boolean renewed, int live, List<String> expired)} (live: the live members, this one included; expired: the
 * other members whose lease ran out and that nobody released yet), {@code Membership.release(List<String> peers)} →
 * {@code Future<List<String>>} (the peers released: those still expired).
 */
public final class ScriptedMembership implements Membership {

    /** How the next renews are answered. */
    public enum Mode { OK, FAIL, HANG, SUPERSEDED }

    /** One renew as sent, and the promise of a {@code HANG} one (null otherwise). */
    public record Call(String node, long epoch, Duration lease, Instant at, Promise<Renewal> pending) {}

    /** One release as asked. */
    public record Release(Instant at, List<String> peers) {}

    private final Clock clock;
    private final AtomicLong epochs = new AtomicLong();
    private final List<Call> renews = new CopyOnWriteArrayList<>();
    private final List<Release> releases = new CopyOnWriteArrayList<>();
    private final List<Instant> joins = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.OK;
    private volatile List<String> expired = List.of();

    public ScriptedMembership(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ScriptedMembership mode(Mode next) {
        mode = Objects.requireNonNull(next, "next");
        return this;
    }

    /** The peers every renewed answer from now on names as expired. */
    public ScriptedMembership expired(List<String> peers) {
        expired = List.copyOf(peers);
        return this;
    }

    public List<Call> renews() { return List.copyOf(renews); }

    public List<Release> releases() { return List.copyOf(releases); }

    public List<Instant> joins() { return List.copyOf(joins); }

    /** Answers the {@code HANG} renew number {@code index} (in send order, every renew counted) with {@code renewed}. */
    public void answer(int index, boolean renewed) {
        Call call = renews.get(index);
        if (call.pending() == null) throw new IllegalStateException("renew " + index + " was not left hanging: " + call);
        call.pending().complete(renewal(renewed));
    }

    /** A renewed or superseded answer as this membership gives it: one live member (the caller), the scripted expired peers. */
    public Renewal renewal(boolean renewed) {
        return new Renewal(renewed, 1, renewed ? expired : List.of());
    }

    @Override public Future<Long> join(String node, Duration lease) {
        joins.add(clock.instant());
        return Future.succeededFuture(epochs.incrementAndGet());
    }

    @Override public Future<Renewal> renew(String node, long epoch, Duration lease) {
        Mode now = mode;
        Promise<Renewal> pending = now == Mode.HANG ? Promise.promise() : null;
        renews.add(new Call(node, epoch, lease, clock.instant(), pending));
        return switch (now) {
            case OK -> Future.succeededFuture(renewal(true));
            case FAIL -> Future.failedFuture(new IllegalStateException("scripted: the database is unreachable"));
            case HANG -> pending.future();
            case SUPERSEDED -> Future.succeededFuture(renewal(false));
        };
    }

    @Override public Future<List<String>> release(List<String> peers) {
        releases.add(new Release(clock.instant(), List.copyOf(peers)));
        return Future.succeededFuture(List.copyOf(peers));
    }
}
