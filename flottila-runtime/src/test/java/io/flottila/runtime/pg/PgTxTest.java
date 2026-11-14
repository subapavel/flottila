package io.flottila.runtime.pg;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-172b P1 / A4: {@code PgTx.inTransaction(pool, fn)} acquires with {@code pool.getConnection()}; a failure of that acquire —
 * before any statement ran, whatever its type or message — fails the result with a {@link PoolRefusedException} (unchecked, the
 * acquire's failure as its cause) and {@code fn} never runs; once a connection is acquired it begins, runs {@code fn}, commits on
 * success and rolls back on failure, closes the connection, and a failure from {@code fn} or the commit is the result's failure as
 * it was, never wrapped.
 *
 * <p>The pool is faked at the client boundary (a {@link Proxy} over {@link Pool}, {@link SqlConnection} and {@link Transaction}
 * recording begin/commit/rollback/close); one case uses a real Vert.x pool whose wait for a connection elapses against a server
 * that accepts and never answers, i.e. the wait timeout FIX-172b is about. API assumed: {@code static <T> Future<T>
 * inTransaction(Pool, Function<SqlConnection, Future<T>>)} and {@code new PoolRefusedException(Throwable cause)}.
 */
@Timeout(30)
class PgTxTest {

    final List<String> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger ran = new AtomicInteger();

    /**
     * {@code iface} answering {@code answers} by method name, Object's methods by identity, a default method by its own body (over
     * these answers); anything else is refused loudly.
     */
    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> iface, Map<String, Supplier<Object>> answers) {
        InvocationHandler h = (proxy, m, args) -> switch (m.getName()) {
            case "toString" -> "fake " + iface.getSimpleName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> {
                Supplier<Object> answer = answers.get(m.getName());
                if (answer != null) yield answer.get();
                if (m.isDefault()) yield InvocationHandler.invokeDefault(proxy, m, args);   // e.g. Pool.withTransaction over this getConnection
                throw new UnsupportedOperationException("the fake " + iface.getSimpleName() + " does not answer " + m);
            }
        };
        return (T) Proxy.newProxyInstance(PgTxTest.class.getClassLoader(), new Class<?>[] {iface}, h);
    }

    Pool pool(Future<SqlConnection> acquired) {
        return fake(Pool.class, Map.of("getConnection", () -> { calls.add("getConnection"); return acquired; },
                "close", Future::succeededFuture));
    }

    /** A connection whose transaction's commit answers {@code commit}. */
    SqlConnection connection(Future<Void> commit) {
        Transaction tx = fake(Transaction.class, Map.of(
                "commit", () -> { calls.add("commit"); return commit; },
                "rollback", () -> { calls.add("rollback"); return Future.succeededFuture(); },
                "completion", Future::succeededFuture));
        return fake(SqlConnection.class, Map.of(
                "begin", () -> { calls.add("begin"); return Future.succeededFuture(tx); },
                "close", () -> { calls.add("close"); return Future.succeededFuture(); }));
    }

    /** A connection whose begin answers {@code begin} (FIX-172b F2). */
    SqlConnection connection(Supplier<Future<Transaction>> begin) {
        return fake(SqlConnection.class, Map.of(
                "begin", () -> { calls.add("begin"); return begin.get(); },
                "close", () -> { calls.add("close"); return Future.succeededFuture(); }));
    }

    /** A transaction whose commit succeeds and whose rollback answers {@code rollback}. */
    Transaction transaction(Future<Void> rollback) {
        return fake(Transaction.class, Map.of(
                "commit", () -> { calls.add("commit"); return Future.succeededFuture(); },
                "rollback", () -> { calls.add("rollback"); return rollback; },
                "completion", Future::succeededFuture));
    }

    /** The failure {@code f} completes with, within 10 s. */
    static Throwable failure(Future<?> f) throws Exception {
        f.toCompletionStage().toCompletableFuture().exceptionally(e -> null).get(10, TimeUnit.SECONDS);
        assertTrue(f.failed(), "the result failed; it succeeded with " + f.result());
        return f.cause();
    }

    @Test
    void anAcquireFailureOfAnyTypeIsAPoolRefusalKeepingItsCauseAndNothingRuns() {
        Map<String, Throwable> causes = new LinkedHashMap<>();
        causes.put("the wait elapsed (exactly a VertxException)", new VertxException("Timeout", true));
        causes.put("the queue full", new ConnectionPoolTooBusyException("Connection pool reached max wait queue size of 1"));
        causes.put("a type and message no pool uses", new IllegalStateException("something else entirely"));
        causes.put("a checked one", new IOException("connection refused"));
        assertAll(causes.entrySet().stream().map(c -> () -> {
            calls.clear();
            int before = ran.get();
            Future<String> result = PgTx.inTransaction(pool(Future.failedFuture(c.getValue())), conn -> {
                ran.incrementAndGet();
                return Future.succeededFuture("ran");
            });
            Throwable refused = failure(result);
            assertInstanceOf(PoolRefusedException.class, refused, c.getKey() + ": an acquire failure is a pool refusal, structurally: " + refused);
            assertInstanceOf(RuntimeException.class, refused, c.getKey() + ": unchecked");
            assertSame(c.getValue(), refused.getCause(), c.getKey() + ": the acquire's failure is kept as the cause");
            assertEquals(before, ran.get(), c.getKey() + ": fn never runs without a connection");
            assertEquals(List.of("getConnection"), calls, c.getKey() + ": the acquire is pool.getConnection(), nothing begun or closed");
        }));
    }

    /**
     * A real pool of one connection against a server that accepts and never answers, its wait PT0.2S: the acquire's wait elapses
     * and the result is a PoolRefusedException with the pool's own failure as its cause; fn never runs.
     */
    @Test
    void aRealPoolsElapsedWaitIsAPoolRefusal() throws Exception {
        Vertx vertx = Vertx.vertx();
        List<NetSocket> accepted = new CopyOnWriteArrayList<>();
        try {
            NetServer silent = vertx.createNetServer().connectHandler(accepted::add).listen(0, "127.0.0.1").await();
            Pool real = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost("127.0.0.1").setPort(silent.actualPort()).setDatabase("d")
                            .setUser("u").setPassword("p"))
                    .with(new PoolOptions().setMaxSize(1).setConnectionTimeout(200).setConnectionTimeoutUnit(TimeUnit.MILLISECONDS)).using(vertx).build();
            Future<String> result = PgTx.inTransaction(real, conn -> {
                ran.incrementAndGet();
                return Future.succeededFuture("ran");
            });
            Throwable refused = failure(result);
            assertInstanceOf(PoolRefusedException.class, refused, "the pool's elapsed wait is a pool refusal: " + refused);
            Throwable cause = refused.getCause();
            assertTrue(cause != null, "the pool's failure is kept as the cause");
            assertEquals(0, ran.get(), "fn never ran (cause: " + cause.getClass().getName() + ": " + cause.getMessage() + ")");
            assertEquals(1, accepted.size(), "witness: the pool did try to connect, and the server held that connection");
            real.close().await(10, TimeUnit.SECONDS);
        } finally {
            vertx.close().await(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aFailureFromFnIsTheResultAsItWasRolledBackAndTheConnectionClosed() throws Exception {
        VertxException inside = new VertxException("Timeout", true);   // exactly what a pool refusal looks like: still not one here
        Future<String> result = PgTx.inTransaction(pool(Future.succeededFuture(connection(Future.succeededFuture()))), conn -> {
            ran.incrementAndGet();
            return Future.failedFuture(inside);
        });
        Throwable failed = failure(result);
        assertSame(inside, failed, "a failure inside fn is the result's failure as it was, not wrapped: " + failed);
        assertEquals(1, ran.get(), "fn ran once");
        assertEquals(List.of("getConnection", "begin", "rollback", "close"), calls, "begun, rolled back, never committed, closed");
    }

    @Test
    void aThrowFromFnIsTheResultAsItWasNotAPoolRefusal() throws Exception {
        IllegalStateException thrown = new IllegalStateException("a bug in fn");
        Future<String> result = PgTx.inTransaction(pool(Future.succeededFuture(connection(Future.succeededFuture()))), conn -> {
            ran.incrementAndGet();
            throw thrown;
        });
        Throwable failed = failure(result);
        assertSame(thrown, failed, "a throw inside fn — after the acquire — is the result's failure as it was: " + failed);
        assertTrue(!calls.contains("commit"), "nothing committed: " + calls);
    }

    @Test
    void aSuccessIsCommittedReturnedAndTheConnectionClosed() throws Exception {
        Future<String> result = PgTx.inTransaction(pool(Future.succeededFuture(connection(Future.succeededFuture()))), conn -> {
            ran.incrementAndGet();
            return Future.succeededFuture("done");
        });
        assertEquals("done", result.await(10, TimeUnit.SECONDS), "fn's result is the result");
        assertEquals(List.of("getConnection", "begin", "commit", "close"), calls, "begun, committed, closed");
    }

    @Test
    void aFailedCommitIsTheResultAsItWasNotAPoolRefusal() throws Exception {
        VertxException commit = new VertxException("Timeout", true);
        Future<String> result = PgTx.inTransaction(pool(Future.succeededFuture(connection(Future.failedFuture(commit)))),
                conn -> Future.succeededFuture("done"));
        assertSame(commit, failure(result), "a commit that failed — after the acquire — is the result's failure as it was");
        assertTrue(calls.contains("close"), "and the connection is closed: " + calls);
    }

    /**
     * FIX-172b (implementer): a throw from fn — after the acquire and the begin — rolls the transaction back and closes the
     * connection, as a failed future from fn does; nothing is committed.
     */
    @Test
    void aThrowFromFnIsRolledBackAndTheConnectionClosed() throws Exception {
        IllegalStateException thrown = new IllegalStateException("a bug in fn");
        Future<String> result = PgTx.inTransaction(pool(Future.succeededFuture(connection(Future.succeededFuture()))), conn -> {
            throw thrown;
        });
        assertSame(thrown, failure(result), "the throw is the result's failure as it was");
        assertEquals(List.of("getConnection", "begin", "rollback", "close"), calls, "begun, rolled back, never committed, closed");
    }

    /**
     * FIX-172b F2: a begin that fails — after the acquire, before fn — is the result's failure as it was, the same instance, never a
     * PoolRefusedException even when it looks like a pool's (exactly a VertxException); fn never runs and the connection is closed.
     */
    @Test
    void aFailedBeginIsTheResultAsItWasNotAPoolRefusalAndTheConnectionClosed() throws Exception {
        VertxException begin = new VertxException("Timeout", true);
        Future<String> result = PgTx.inTransaction(pool(Future.succeededFuture(connection(() -> Future.failedFuture(begin)))), conn -> {
            ran.incrementAndGet();
            return Future.succeededFuture("ran");
        });
        Throwable failed = failure(result);
        assertSame(begin, failed, "the begin's failure is the result's, the same instance, not wrapped: " + failed);
        assertEquals(0, ran.get(), "fn never runs without a transaction");
        assertEquals(List.of("getConnection", "begin", "close"), calls, "acquired, the begin failed, the connection closed");
    }

    /**
     * FIX-172b F2: fn fails and the rollback fails too: fn's failure is the result's (the same instance), not the rollback's, and
     * the connection is closed.
     */
    @Test
    void aFailedRollbackLeavesFnsFailureTheResultAndTheConnectionClosed() throws Exception {
        IllegalStateException inside = new IllegalStateException("fn failed");
        VertxException rollback = new VertxException("the rollback failed", true);
        Transaction tx = transaction(Future.failedFuture(rollback));
        Future<String> result = PgTx.inTransaction(pool(Future.succeededFuture(connection(() -> Future.succeededFuture(tx)))),
                conn -> Future.failedFuture(inside));
        Throwable failed = failure(result);
        assertSame(inside, failed, "fn's failure is the result's, not the rollback's: " + failed);
        assertEquals(List.of("getConnection", "begin", "rollback", "close"), calls, "begun, rolled back (failing), never committed, closed");
    }
}
