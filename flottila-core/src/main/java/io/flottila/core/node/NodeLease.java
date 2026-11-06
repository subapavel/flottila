package io.flottila.core.node;

import io.flottila.api.Timer;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * A node's lease in the {@link Membership} and its self-fence (D-101 (1), (2); F-28), on an injected monotonic clock
 * ({@code nanos}, any origin) and {@link Timer}. {@link #start} joins; from then on the node renews every {@code renew}, at most
 * one renew in flight: a period that finds one outstanding sends nothing, and the fence keeps counting from the outstanding
 * send. With {@code s} the clock read just before a join or renew is sent, each renewed answer sets
 * {@code fenceUntil = max(fenceUntil, s + lease/2)}; the fence ({@link #getAsBoolean}) is open while the node is not superseded
 * and the clock is before {@code fenceUntil} — closed before the join. Counted from the send, the fence ends at least half a
 * lease before the store's lease, which the store started no earlier than {@code s}: a peer that releases the node's row after
 * it expired takes its items over at least half a lease after its last possible send.
 *
 * <p>A renew that answers not renewed (its row released by a peer, or joined again by a newer incarnation) supersedes the node:
 * its fence closes for good, it renews no more and {@code halt} runs, once (fail-stop: the process exits). A renew that fails
 * changes nothing; the next period tries again. A renew still unanswered half a lease after its send (the fence closed by then)
 * fails there: it is abandoned — its answer, if one ever comes, is ignored — the membership {@linkplain Membership#reconnect
 * reconnects}, and the next period renews again, so a renew hung on a black-holed connection never stops the renewals. A renewed answer naming expired peers releases them only when this node's fence
 * stayed open for the whole last lease — since the latest answer that found it closed, or the join — so a store outage, which
 * stops every node's renews alike, takes nobody over when it ends; a release that fails is a WARNING, and the next renewed answer
 * that names them asks again.
 *
 * @since 0.1
 */
public final class NodeLease implements BooleanSupplier, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(NodeLease.class.getName());

    private final String node;
    private final Membership membership;
    private final Duration lease;
    private final Duration renew;
    private final long leaseNanos;
    private final long halfNanos;
    private final LongSupplier nanos;
    private final Timer timer;
    private final Runnable halt;

    private final Object lock = new Object();
    private long epoch;                  // guarded by lock: the join's
    private boolean joined;              // guarded by lock
    private long fenceUntil;             // guarded by lock: on nanos; meaningful once joined
    private long openSince;              // guarded by lock: the start of the fence's current open stretch
    private boolean superseded;          // guarded by lock
    private long renews;                 // guarded by lock: the renews sent
    private long outstanding;            // guarded by lock: the number of the renew sent and neither answered nor abandoned, 0 for none
    private boolean failing;             // guarded by lock: the last renew failed or was abandoned (its WARNING logged)
    private boolean closed;              // guarded by lock
    private Timer.Cancellable tick;      // guarded by lock
    private Timer.Cancellable deadline;  // guarded by lock: the outstanding renew's, half a lease after its send

    public NodeLease(String node, Membership membership, Duration lease, Duration renew, LongSupplier nanos, Timer timer, Runnable halt) {
        this.node = Objects.requireNonNull(node, "node");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.renew = Objects.requireNonNull(renew, "renew");
        if (!lease.isPositive() || !renew.isPositive()) throw new IllegalArgumentException("lease " + lease + " and renew " + renew + " must be positive");
        this.leaseNanos = lease.toNanos();
        this.halfNanos = leaseNanos / 2;
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.halt = Objects.requireNonNull(halt, "halt");
    }

    /** Joins the membership; once joined the fence is open and the renewals run. Fails as the join fails. */
    public Future<Void> start() {
        long s = nanos.getAsLong();
        return Future.<Long>future(p -> membership.join(node, lease).onComplete(p)).map(joinedAs -> {
            synchronized (lock) {
                epoch = joinedAs;
                joined = true;
                fenceUntil = s + halfNanos;
                openSince = s;
                if (!closed) tick = timer.schedule(renew, this::tick);
            }
            return null;
        });
    }

    /** The fence: open while the node is not superseded and its last renewed send is less than half a lease ago. */
    @Override public boolean getAsBoolean() {
        synchronized (lock) {
            return open(nanos.getAsLong());
        }
    }

    /** Stops the renewals; an answer in flight is ignored and its deadline cancelled. The fence closes as it would without renewals. */
    @Override public void close() {
        synchronized (lock) {
            closed = true;
            outstanding = 0;   // an answer or a deadline still to come finds no renew of its own
            if (tick != null) tick.cancel();
            if (deadline != null) deadline.cancel();
        }
    }

    private boolean open(long now) {
        return joined && !superseded && now - fenceUntil < 0;
    }

    private void tick() {
        long s;
        long incarnation;
        long sent;
        synchronized (lock) {
            if (closed || superseded) return;
            tick = timer.schedule(renew, this::tick);
            if (outstanding != 0) return;   // at most one renew in flight: the fence counts from the one outstanding
            sent = ++renews;
            outstanding = sent;
            s = nanos.getAsLong();
            incarnation = epoch;
            deadline = timer.schedule(lease.dividedBy(2), () -> unanswered(sent));   // the fence closed by then
        }
        Future.<Membership.Renewal>future(p -> membership.renew(node, incarnation, lease).onComplete(p)).onComplete(answer -> answered(sent, s, answer));
    }

    /** Renew {@code sent}'s deadline: still unanswered, it is abandoned and the membership reconnects; the next period renews again. */
    private void unanswered(long sent) {
        synchronized (lock) {
            if (outstanding != sent) return;   // answered in time, or the lease closed: the cancel came too late
            outstanding = 0;
            failing(sent + " is unanswered half a lease after its send: abandoned, the membership reconnects");
        }
        membership.reconnect();
    }

    private void failing(String what) {
        if (!failing) LOG.log(System.Logger.Level.WARNING, "node " + node + " could not renew its lease (its fence closes half a lease after its last "
                + "renewed send; logged once until a renew succeeds): renew " + what);
        failing = true;
    }

    private void answered(long sent, long s, AsyncResult<Membership.Renewal> answer) {
        List<String> release = List.of();
        boolean halting;
        long incarnation;
        synchronized (lock) {
            if (outstanding != sent) return;   // abandoned at its deadline, or the lease closed: a later renew counts, never this one
            outstanding = 0;
            deadline.cancel();
            if (answer.failed()) {
                failing(sent + " failed: " + answer.cause());
                return;
            }
            failing = false;
            long now = nanos.getAsLong();
            incarnation = epoch;
            halting = !answer.result().renewed();
            if (halting) {
                superseded = true;
            } else {
                if (!open(now)) openSince = now;   // the fence was closed: open again from now, after a gap in its own lease
                fenceUntil = s + halfNanos;   // max(fenceUntil, s + L/2): one renew in flight, each sent R after the last, so s only grows
                if (now - openSince >= leaseNanos) release = answer.result().expired();
            }
        }
        if (halting) {
            LOG.log(System.Logger.Level.ERROR, "node " + node + " (epoch " + incarnation + ") was superseded: a peer released its lease or a newer "
                    + "incarnation joined; its fence is closed for good and it halts (D-101 (2))");
            halt.run();
        } else if (!release.isEmpty()) {
            List<String> peers = release;
            membership.release(peers).onComplete(done -> LOG.log(System.Logger.Level.WARNING, "node " + node + " found the members " + peers
                    + " expired and released " + done + ": their unsettled items are taken over in doubt"), failed -> LOG.log(System.Logger.Level.WARNING,
                    "node " + node + " found the members " + peers + " expired and could not release them (the next renew asks again): " + failed));
        }
    }
}
