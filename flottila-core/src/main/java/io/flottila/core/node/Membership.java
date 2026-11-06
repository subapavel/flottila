package io.flottila.core.node;

import io.vertx.core.Future;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The store of the node leases (D-101 (1)): one row per node with its epoch and its lease, measured by the store's clock. The
 * Postgres one lives in runtime ({@code PgMembership}, V14's {@code nodes}), the in-memory one here ({@link InMemoryMembership}).
 *
 * <p>A row whose lease ran out and that nobody released still renews (a store outage takes nobody over); a released row never
 * renews again — its node's next renew is superseded — and neither does a row of an older epoch (the node restarted).
 *
 * @since 0.1
 */
public interface Membership {

    /**
     * What one renew found.
     *
     * @param renewed the renewing node's row was renewed: its epoch is the current one and nobody released it
     * @param live the members whose lease runs now, the renewing one included when renewed
     * @param expired the other members whose lease ran out and that nobody released, by node id
     * @since 0.1
     */
    record Renewal(boolean renewed, int live, List<String> expired) {
        public Renewal {
            expired = List.copyOf(Objects.requireNonNull(expired, "expired"));
        }
    }

    /**
     * Joins {@code node} as a new incarnation: its epoch bumped (1 for a node never seen), its lease {@code lease} from now. Its
     * own unheld work items wait for the previous incarnation's lease to end (its fence closed half its own lease before, D-112);
     * {@code lease} plays no part in that wait.
     *
     * @return the new epoch
     */
    Future<Long> join(String node, Duration lease);

    /** Renews the lease of {@code node}'s incarnation {@code epoch} to {@code lease} from now and reports the other members. */
    Future<Renewal> renew(String node, long epoch, Duration lease);

    /** Releases those of {@code peers} whose lease ran out and that nobody released; answers the peers it released, by node id. */
    Future<List<String>> release(List<String> peers);

    /**
     * Drops what an unanswered renew hangs on (F-28): a membership over a connection closes it — failing the calls on it — and
     * the next call opens another. {@link NodeLease} asks it of a renew still unanswered half a lease after its send. One with
     * nothing to drop does nothing, which is this default.
     */
    default void reconnect() {
    }
}
