package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CORE-7 (D-122) property: whatever the mix of rows and calls and however they gather into batches, the batched
 * {@code completeUnkeyed} answers each call — and leaves the table — as the single-row model says. The model (written here, not
 * read from the queue): a call completes its row, answering done with nothing next and no node, iff the row exists, has no partition
 * key, is not held, and was last handed out with the call's token (attempts ≥ 1); every other call answers STALE and changes nothing.
 *
 * <p>Each try (bounded: 15 tries, ≤ 24 rows) gets its own flow and a batched queue over one shared database. Rows are generated in
 * five kinds — current, re-claimed since (row at attempts 2, call token 1), held, never handed out (call token 0), keyed — plus calls
 * for unknown seqs; seqs are distinct within a try (one settle per lease token, as a worker issues them). With {@code gather} true
 * the first call is sent while a fixture session holds {@code lock table work in share mode}, so the rest gather behind it; else
 * the calls go one after the other without waiting. After every call answered, the rows of the try are compared with the model's.
 */
class PgWorkQueueBatchedCompletionPropertyIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final AtomicInteger TRIES = new AtomicInteger();
    static final Duration LEASE = Duration.ofMinutes(5);
    static Vertx vertx;
    static Pool pool;

    @BeforeContainer
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true))
                .with(new PoolOptions().setMaxSize(6)).using(vertx).build();
    }

    @AfterContainer
    static void stop() {
        if (pool != null) pool.close().await();
        if (vertx != null) vertx.close().await();
        pg.stop();
    }

    enum Kind { CURRENT, RECLAIMED, HELD, NEVER, KEYED, UNKNOWN }

    @Provide
    Arbitrary<List<Kind>> kinds() {
        return Arbitraries.of(Kind.class).list().ofMinSize(1).ofMaxSize(24);
    }

    static <T> T await(Future<T> f) {
        try {
            return f.await(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("still pending after 20 s: " + f, e);
        }
    }

    /** One call: the row's name, its seq, the token the call carries, and what the model answers. */
    record Call(String name, long seq, int token, boolean completes) {}

    @Property(tries = 15)
    void batchedCompletionsMatchTheSingleRowModel(@ForAll("kinds") List<Kind> kinds, @ForAll boolean gather) throws Exception {
        String flow = "prop-" + TRIES.incrementAndGet();
        PgWorkQueue fixture = new PgWorkQueue(pool);
        PgWorkQueue queue = new PgWorkQueue(pool, "n1", null, new FakeTimer(FakeClock.at(Instant.parse("2026-11-11T10:00:00Z"))));
        try {
            List<Call> calls = new ArrayList<>();
            for (int i = 0; i < kinds.size(); i++) {
                String name = flow + "-" + i + "-" + kinds.get(i);
                Kind kind = kinds.get(i);
                if (kind == Kind.UNKNOWN) {
                    calls.add(new Call(name, -1, 1, false));
                    continue;
                }
                await(fixture.enqueue(name, flow, 1, kind == Kind.KEYED ? "K-" + i : null));
                long seq = pool.preparedQuery("select seq from work where id = $1").execute(Tuple.of(name)).await().iterator().next().getLong(0);
                if (kind != Kind.NEVER) {
                    pool.preparedQuery("update work set attempts = $2, locked_by = 'n1', locked_until = now() + interval '5 minutes', "
                            + "held_at = case when $3 then now() end where seq = $1").execute(Tuple.of(seq, kind == Kind.RECLAIMED ? 2 : 1, kind == Kind.HELD)).await();
                }
                int token = kind == Kind.NEVER ? 0 : 1;
                calls.add(new Call(name, seq, token, kind == Kind.CURRENT));
            }
            long max = pool.query("select coalesce(max(seq), 0) from work").execute().await().iterator().next().getLong(0);
            for (int i = 0; i < calls.size(); i++) {
                Call c = calls.get(i);
                if (c.seq() == -1) calls.set(i, new Call(c.name(), max + 1_000 + i, 1, false));
            }

            SqlConnection holder = null;
            Transaction tx = null;
            Map<String, Future<WorkQueue.Completion>> answers = new TreeMap<>();
            try {
                if (gather) {
                    holder = await(pool.getConnection());
                    tx = await(holder.begin());
                    await(holder.query("lock table work in share mode").execute());
                }
                for (Call c : calls) answers.put(c.name(), queue.completeUnkeyed(c.seq(), c.token()));
            } finally {
                if (tx != null) await(tx.rollback());
                if (holder != null) await(holder.close());
            }

            Map<String, WorkQueue.Completion> expected = new TreeMap<>();
            Map<String, WorkQueue.Completion> got = new TreeMap<>();
            WorkQueue.Completion done = new WorkQueue.Completion(true, Optional.empty(), Optional.empty());
            for (Call c : calls) expected.put(c.name(), c.completes() ? done : WorkQueue.Completion.STALE);
            answers.forEach((name, f) -> got.put(name, await(f)));
            assertEquals(expected, got, "each call answered as the single-row model says: " + kinds + " gather " + gather);

            List<String> left = new ArrayList<>();
            for (Row r : pool.preparedQuery("select id from work where flow = $1").execute(Tuple.of(flow)).await()) left.add(r.getString(0));
            left.sort(null);
            List<String> model = calls.stream().filter(c -> c.seq() <= max && !c.completes()).map(Call::name).sorted().toList();
            assertEquals(model, left, "exactly the completed rows are gone");
        } finally {
            await(queue.close());
        }
    }
}
