package io.flottila.core.node;

import io.vertx.core.Future;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-memory {@link Membership} with the rules of the Postgres one, its leases on an injected {@link Clock}: a lease is live
 * up to and including its end, expired after it. Single process only; for tests and for {@code InMemoryWorkQueue}, which asks
 * it whether a holder is {@link #live} and whether a restarted node's old items {@link #waits wait} for its old lease to end.
 *
 * @since 0.1
 */
public final class InMemoryMembership implements Membership {

    /**
     * One node's row: {@code leaseUntil} null once released; {@code oldFence} the latest previous incarnation's lease end (null:
     * none), before which an item handed out under an older epoch does not run again: that incarnation's fence closed by then,
     * whatever lease it ran under (D-112).
     */
    private record Member(long epoch, Instant leaseUntil, Instant oldFence) {}

    private final Clock clock;
    private final Map<String, Member> members = new TreeMap<>();   // by node id: the expired peers come in that order

    public InMemoryMembership(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public synchronized Future<Long> join(String node, Duration lease) {
        Objects.requireNonNull(node, "node");
        Instant now = clock.instant();
        Member old = members.get(node);
        long epoch = 1;
        Instant fence = null;
        if (old != null) {
            epoch = old.epoch() + 1;
            fence = old.oldFence();
            if (old.leaseUntil() != null) fence = later(fence, old.leaseUntil());
        }
        members.put(node, new Member(epoch, now.plus(lease), fence));
        return Future.succeededFuture(epoch);
    }

    @Override public synchronized Future<Renewal> renew(String node, long epoch, Duration lease) {
        Instant now = clock.instant();
        Member m = members.get(node);
        boolean renewed = m != null && m.epoch() == epoch && m.leaseUntil() != null;
        if (renewed) members.put(node, new Member(epoch, now.plus(lease), m.oldFence()));
        int live = 0;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Member> e : members.entrySet()) {
            Instant until = e.getValue().leaseUntil();
            if (until == null) continue;
            if (!until.isBefore(now)) live++;
            else if (!e.getKey().equals(node)) expired.add(e.getKey());
        }
        return Future.succeededFuture(new Renewal(renewed, live, expired));
    }

    @Override public synchronized Future<List<String>> release(List<String> peers) {
        Instant now = clock.instant();
        Set<String> asked = new HashSet<>(peers);
        List<String> released = new ArrayList<>();
        for (Map.Entry<String, Member> e : members.entrySet()) {
            Member m = e.getValue();
            if (!asked.contains(e.getKey()) || m.leaseUntil() == null || !m.leaseUntil().isBefore(now)) continue;
            e.setValue(new Member(m.epoch(), null, m.oldFence()));
            released.add(e.getKey());
        }
        return Future.succeededFuture(released);
    }

    /** {@code node} has a row nobody released: its items are its own, whether its lease ran out or not. */
    public synchronized boolean live(String node) {
        Member m = members.get(node);
        return m != null && m.leaseUntil() != null;
    }

    /** {@code node}'s current epoch; 0 for a node that never joined. */
    public synchronized long epoch(String node) {
        Member m = members.get(node);
        return m == null ? 0 : m.epoch();
    }

    /**
     * An item {@code node} was handed under {@code epoch} waits at {@code now}: the node joined again since and its previous
     * incarnation's lease (its fence closed half that lease before) has not ended.
     */
    public synchronized boolean waits(String node, long epoch, Instant now) {
        Member m = members.get(node);
        return m != null && m.epoch() > epoch && m.oldFence() != null && now.isBefore(m.oldFence());
    }

    private static Instant later(Instant a, Instant b) {
        return a == null || b.isAfter(a) ? b : a;
    }
}
