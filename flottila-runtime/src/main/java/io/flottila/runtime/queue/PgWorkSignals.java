package io.flottila.runtime.queue;

import io.flottila.api.Timer;
import io.flottila.core.flow.WorkEvents;
import io.flottila.core.flow.WorkSignals;
import io.flottila.core.flow.Workers;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The node's {@link WorkSignals} over Postgres {@code LISTEN/NOTIFY} on channel {@value #CHANNEL} (D-121 (b) B1–B3, P2-16b), the
 * payloads, windows and wakes of {@link WorkEvents}.
 *
 * <p>Receiving (B2): one dedicated {@link PgConnection} to {@code listen} — never the pool's — runs {@code LISTEN} and hands every
 * notification's payload to {@link WorkEvents#receive}; a payload not of the {@code w1} form is logged at WARNING once per distinct
 * prefix. {@link #start()} completes once its first {@code LISTEN} is acknowledged. A connection lost (closed by the server, the
 * network, a terminated backend) is reopened on the timer after a backoff — {@value #FIRST_BACKOFF_MILLIS} ms, doubled after each
 * failed try up to {@value #MAX_BACKOFF_MILLIS} ms, tried until {@link #close()}, back to the first once a {@code LISTEN} is
 * acknowledged — and after every {@code LISTEN} acknowledged, the first included, every started worker of the node is woken once:
 * an event missed while nobody listened costs one claim (D-121).
 *
 * <p>Sending (B3): an announce — made after the commit it tells of is acknowledged — is sent on the pool in autocommit, never in a
 * transaction: a leading edge as {@code select pg_notify('flottila_work', $1)}, a window's end as one {@code select
 * pg_notify('flottila_work', p) from unnest($1::text[]) p} (one payload alone as the first form). A send that fails logs one WARNING
 * and is dropped, never sent again: the reconciliation covers it. Nothing is sent after {@link #close()}.
 *
 * @since 0.1
 */
public final class PgWorkSignals implements WorkSignals {

    private static final System.Logger LOG = System.getLogger(PgWorkSignals.class.getName());

    /** The channel every node listens and notifies on. */
    public static final String CHANNEL = "flottila_work";
    /** The first reconnect's delay. */
    static final long FIRST_BACKOFF_MILLIS = 100;
    /** The longest delay between two reconnect tries (B7: a constant). */
    static final long MAX_BACKOFF_MILLIS = 5_000;

    private static final String NOTIFY = "select pg_notify('" + CHANNEL + "', $1)";
    private static final String NOTIFY_EACH = "select pg_notify('" + CHANNEL + "', p) from unnest($1::text[]) p";

    private final Vertx vertx;
    private final PgConnectOptions listen;
    private final Pool pool;
    private final Workers workers;
    private final Timer timer;
    private final WorkEvents events;

    private final Object lock = new Object();
    private PgConnection listening;          // guarded by lock: the connection whose LISTEN was acknowledged, null: none now
    private Timer.Cancellable retry;         // guarded by lock: the reconnect armed, null: none
    private long backoff = FIRST_BACKOFF_MILLIS;   // guarded by lock: the next reconnect's delay
    private boolean closed;                  // guarded by lock

    /**
     * Node {@code node}'s signals: listening on a connection of its own to {@code listen}, sending on {@code pool}, waking
     * {@code workers}; windows and reconnects on {@code timer}. Connects nowhere before {@link #start()}.
     */
    public PgWorkSignals(Vertx vertx, PgConnectOptions listen, Pool pool, String node, Workers workers, Timer timer) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.listen = Objects.requireNonNull(listen, "listen");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.events = new WorkEvents(node, workers, timer, this::send);
    }

    /** Opens the dedicated connection and listens; completes once the {@code LISTEN} is acknowledged, fails when it could not be. */
    public Future<Void> start() {
        return open().map(conn -> {
            listening(conn);
            return null;
        });
    }

    /**
     * Sends nothing from now on (what a window kept is dropped), arms no reconnect and closes the dedicated connection; completes
     * once it closed. The pool is the caller's: close this first.
     */
    public Future<Void> close() {
        PgConnection conn;
        synchronized (lock) {
            closed = true;
            conn = listening;
            listening = null;
            if (retry != null) retry.cancel();
            retry = null;
        }
        events.close();
        return conn == null ? Future.succeededFuture() : conn.close();
    }

    @Override public void announce(String targetNode, String flow) {
        events.announce(targetNode, flow);
    }

    /** A connection of its own that listens: the notifications handed on; one whose LISTEN failed is closed and the failure answered. */
    private Future<PgConnection> open() {
        return PgConnection.connect(vertx, listen).compose(conn -> {
            conn.notificationHandler(n -> received(n.getPayload()));
            return conn.query("LISTEN " + CHANNEL).execute().transform(ack -> ack.succeeded() ? Future.succeededFuture(conn)
                    : conn.close().transform(gone -> Future.failedFuture(ack.cause())));
        });
    }

    /**
     * {@code conn}'s LISTEN was acknowledged: its loss reconnects from now on and every worker is woken once — unless this was closed
     * meanwhile, then it is closed. The close handler is set in the task that saw the acknowledgement: no close lands in between.
     */
    private void listening(PgConnection conn) {
        boolean late;
        synchronized (lock) {
            late = closed;
            if (!late) {
                listening = conn;
                backoff = FIRST_BACKOFF_MILLIS;
            }
        }
        if (late) {
            conn.close();
            return;
        }
        conn.closeHandler(gone -> lost());
        workers.wakeAll();   // what was announced while nobody listened (D-121 (b) B2)
    }

    /** The dedicated connection closed: a reconnect armed, unless this is closed (its own close). */
    private void lost() {
        long delay;
        synchronized (lock) {
            if (closed) return;
            listening = null;
            delay = armRetry();
        }
        LOG.log(System.Logger.Level.WARNING, "the work events' listening connection was lost: reconnecting in " + delay + " ms");
    }

    /** Under the lock: the reconnect armed at the current backoff, the next one's doubled up to the bound; answers the delay. */
    private long armRetry() {
        long delay = backoff;
        retry = timer.schedule(Duration.ofMillis(delay), this::reconnect);
        backoff = Math.min(MAX_BACKOFF_MILLIS, delay * 2);
        return delay;
    }

    /** The armed reconnect fired; one firing as this closes opens a connection {@link #listening} closes again. */
    private void reconnect() {
        synchronized (lock) {
            retry = null;
        }
        open().onComplete(this::listening, failure -> {
            long delay;
            synchronized (lock) {
                if (closed) return;
                delay = armRetry();
            }
            LOG.log(System.Logger.Level.WARNING, "the work events' listening connection could not be reopened: trying again in " + delay
                    + " ms: " + failure, failure);
        });
    }

    private void received(String payload) {
        events.receive(payload).ifPresent(prefix -> LOG.log(System.Logger.Level.WARNING,
                "a work event of an unknown form was ignored (prefix " + prefix + "): " + payload));
    }

    /** One send on the pool, autocommit; a failure logged once and dropped. */
    private void send(List<String> payloads) {
        Future<?> sent = payloads.size() == 1 ? pool.preparedQuery(NOTIFY).execute(Tuple.of(payloads.getFirst()))
                : pool.preparedQuery(NOTIFY_EACH).execute(Tuple.of(payloads.toArray(String[]::new)));
        sent.onFailure(failure -> LOG.log(System.Logger.Level.WARNING, "work events were not sent and are dropped (the reconciliation covers them): "
                + payloads + ": " + failure, failure));
    }
}
