package io.flottila.runtime.node;

import io.flottila.core.node.Membership;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Postgres {@link Membership} (D-101 (1), F-28) on V14's {@code nodes} table, over one dedicated connection that is not the
 * pool's: a renew never waits behind the node's own statements for a pooled connection, and the pool's load never delays it. The
 * leases are measured by the database clock. A renew is one statement — the renew and what it found together — and releases
 * nothing; a release asks for expired rows only, so a renew and a release racing on two connections are serialised by the row
 * lock and exactly one of them wins. A join is one transaction: the old row locked, the node's own unheld work items moved to
 * no earlier than the previous incarnation's lease end ({@code old lease_until}: its fence closed by then whatever lease it ran
 * under, D-112), the row's epoch bumped and its lease started.
 *
 * <p>{@link #PgMembership(SqlConnection)} works on the connection it is given, its caller's to close; {@link #connecting} opens
 * its own on the first call and again on the call after it closed (Postgres restarted, the connection reset), so a node whose
 * renews failed for a while renews again once the database is back; {@link #reconnect} — asked by the node lease of a renew
 * unanswered half a lease after its send (F-28) — closes it at once, failing the calls on it, so a renew hung on a black-holed
 * connection is not waited for: the next call opens another. A given connection is never reconnected (its caller's). {@link
 * #close} closes it and fails every later call.
 *
 * @since 0.1
 */
public final class PgMembership implements Membership {

    /**
     * The node's own unheld items wait for its previous incarnation's lease to end — that incarnation's fence closed half its own
     * lease before, a lease the new one need not share; the old row locked for the rest of the join. No old row, or a released one
     * ({@code lease_until} null), moves nothing.
     */
    private static final String RERUN_AFTER_OLD_LEASE = """
            with old as (select lease_until from nodes where node = $1 for update)
            update work set available_at = greatest(available_at, (select lease_until from old))
             where locked_by = $1 and held_at is null and exists (select 1 from old where lease_until is not null)""";

    /** The row's epoch bumped and its lease started; its peer address ($3, null: none) written, a rejoin's overwriting the last (V18). */
    private static final String UPSERT = "insert into nodes(node, epoch, lease_until, peer_address) values ($1, 1, now() + make_interval(secs => $2), $3)"
            + " on conflict (node) do update set epoch = nodes.epoch + 1, lease_until = excluded.lease_until, peer_address = excluded.peer_address returning epoch";

    /**
     * Renews and reports in one statement: the SELECT reads the rows as they were before the UPDATE, so the renewing row is counted
     * by {@code me}; the live others' peer addresses come as two arrays in the same order (node, address), the address-less left out.
     */
    private static final String RENEW = """
            with me as (update nodes set lease_until = now() + make_interval(secs => $3)
                         where node = $1 and epoch = $2 and lease_until is not null returning node),
            peers as (select node, peer_address from nodes where node <> $1 and lease_until >= now() and peer_address is not null)
            select (select count(*) from me) = 1,
                   ((select count(*) from me) + (select count(*) from nodes where node <> $1 and lease_until >= now()))::int,
                   array(select node from nodes where node <> $1 and lease_until < now() order by node),
                   array(select node from peers order by node), array(select peer_address from peers order by node)""";

    private static final String RELEASE = "update nodes set lease_until = null where node = any($1) and lease_until < now() returning node";

    private static final String OTHERS = "select count(*)::int from nodes where node <> $1 and lease_until >= now()";

    private final Supplier<Future<SqlConnection>> connector;   // null: the one connection given
    private final String peerAddress;                           // null: none (a node without the peer link's address)
    private Future<SqlConnection> connection;                   // guarded by this: null until the next call connects
    private boolean closed;                                     // guarded by this
    private volatile Map<String, String> peers = Map.of();      // the last renew's answer
    private volatile long epoch;                                // the last join's answer

    /** A membership on {@code connection}, dedicated to it; closing it is the caller's. Its joins write no peer address. */
    public PgMembership(SqlConnection connection) {
        this(connection, null);
    }

    /**
     * A membership on {@code connection}, dedicated to it; closing it is the caller's. Its joins write {@code peerAddress}
     * ({@code host:port}, where this node's peer link listens; null: none) into the node's row (V18).
     */
    public PgMembership(SqlConnection connection, String peerAddress) {
        this.connector = null;
        this.peerAddress = peerAddress;
        this.connection = Future.succeededFuture(Objects.requireNonNull(connection, "connection"));
    }

    private PgMembership(Supplier<Future<SqlConnection>> connector, String peerAddress) {
        this.connector = connector;
        this.peerAddress = peerAddress;
    }

    /** A membership on a connection of its own to {@code options}, opened on the first call and again after it closed; no peer address. */
    public static PgMembership connecting(Vertx vertx, PgConnectOptions options) {
        return connecting(vertx, options, null);
    }

    /**
     * A membership on a connection of its own to {@code options}, opened on the first call and again after it closed; its joins
     * write {@code peerAddress} (null: none).
     */
    public static PgMembership connecting(Vertx vertx, PgConnectOptions options, String peerAddress) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(options, "options");
        return new PgMembership(() -> PgConnection.connect(vertx, options).map(c -> c), peerAddress);
    }

    @Override public Future<Long> join(String node, Duration lease) {
        return connection().compose(conn -> conn.begin().compose(tx -> conn.preparedQuery(RERUN_AFTER_OLD_LEASE).execute(Tuple.of(node))
                .compose(moved -> conn.preparedQuery(UPSERT).execute(Tuple.of(node, seconds(lease), peerAddress)))
                .compose(rows -> tx.commit().map(rows.iterator().next().getLong(0)).onSuccess(e -> epoch = e),
                        failure -> tx.rollback().transform(rolledBack -> Future.<Long>failedFuture(failure)))));
    }

    @Override public Future<Renewal> renew(String node, long epoch, Duration lease) {
        return connection().compose(conn -> conn.preparedQuery(RENEW).execute(Tuple.of(node, epoch, seconds(lease)))).map(rows -> {
            Row r = rows.iterator().next();
            String[] nodes = r.getArrayOfStrings(3);
            String[] addresses = r.getArrayOfStrings(4);
            Map<String, String> peers = new HashMap<>();
            for (int i = 0; i < nodes.length; i++) peers.put(nodes[i], addresses[i]);
            Renewal renewal = new Renewal(r.getBoolean(0), r.getInteger(1), List.of(r.getArrayOfStrings(2)), peers);
            this.peers = renewal.peers();
            return renewal;
        });
    }

    /** The epoch the last join answered; 0 before one did. */
    public long epoch() {
        return epoch;
    }

    /**
     * Where {@code node}'s peer link listens as the last answered renew found it ({@link Renewal#peers}): empty for this node, a
     * node whose lease ran out or that was released, one without an address, and before the first renew answered.
     */
    public Optional<String> peerAddress(String node) {
        return Optional.ofNullable(peers.get(node));
    }

    @Override public Future<List<String>> release(List<String> peers) {
        return connection().compose(conn -> conn.preparedQuery(RELEASE).execute(Tuple.tuple().addArrayOfString(peers.toArray(String[]::new))))
                .map(PgMembership::nodes);
    }

    /** The members other than {@code node} whose lease runs now. */
    public Future<Integer> others(String node) {
        return connection().compose(conn -> conn.preparedQuery(OTHERS).execute(Tuple.of(node))).map(rows -> rows.iterator().next().getInteger(0));
    }

    /** Drops the connection {@link #connecting} opened, failing the calls on it — closed once it opened, if it is opening; the next call opens another. */
    @Override public void reconnect() {
        Future<SqlConnection> dropped;
        synchronized (this) {
            if (connector == null) return;   // the given connection is its caller's
            dropped = connection;
            connection = null;
        }
        if (dropped != null) dropped.onSuccess(SqlConnection::close);
    }

    /** Closes the connection {@link #connecting} opened (one given to the constructor is its caller's); every later call fails. */
    public Future<Void> close() {
        Future<SqlConnection> open;
        synchronized (this) {
            closed = true;
            open = connector == null ? null : connection;
            connection = null;
        }
        return open == null ? Future.succeededFuture() : open.transform(c -> c.succeeded() ? c.result().close() : Future.succeededFuture());
    }

    /** The connection of the next call: the one given, the one open, or a new one once the last closed. */
    private synchronized Future<SqlConnection> connection() {
        if (closed) return Future.failedFuture(new IllegalStateException("the membership is closed"));
        if (connection == null) {
            Future<SqlConnection> opening = connector.get();
            connection = opening;
            opening.onComplete(conn -> conn.closeHandler(gone -> dropped(opening)), failed -> dropped(opening));
        }
        return connection;
    }

    /** The connection {@code gone} closed or never opened: the next call opens another. */
    private synchronized void dropped(Future<SqlConnection> gone) {
        if (connection == gone) connection = null;
    }

    private static List<String> nodes(RowSet<Row> rows) {
        List<String> nodes = new ArrayList<>();
        for (Row r : rows) nodes.add(r.getString(0));
        return nodes;
    }

    private static double seconds(Duration d) {
        return d.toNanos() / 1e9;
    }
}
