package io.flottila.runtime.batch;

import io.flottila.core.batch.BatchingWriter;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres side of the batcher: one batch = one transaction over fixed-shape unnest inserts
 * (prepared-statement cache on), atomic per batch, usable as the writer's sink. Testcontainers (-Pit).
 */
class PgBatchSinkIT {

    record Item(String id, int n) {}

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;

    static final PgBatchSink.Statement<Item> INSERT = new PgBatchSink.Statement<>(
            "insert into items(id, n) select * from unnest($1::text[], $2::int[])",
            // Tuple.wrap(Object[]): Tuple.of(Object, Object...) would spread the second array into varargs
            batch -> Tuple.wrap(new Object[] {batch.stream().map(Item::id).toArray(String[]::new), batch.stream().map(Item::n).toArray(Integer[]::new)}));

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword())
                .setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        pool.query("create table items(id text primary key, n int not null)").execute().await();
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void clean() {
        pool.query("drop table if exists item_keys").execute().await();   // a failed case must not leave a FK on items
        pool.query("truncate items").execute().await();
    }

    private static long count() {
        return pool.query("select count(*) from items").execute().await().iterator().next().getLong(0);
    }

    @Test
    void aBatchIsOneCommit() {
        PgBatchSink<Item> sink = new PgBatchSink<>(pool, List.of(INSERT));
        sink.write(List.of(new Item("a", 1), new Item("b", 2), new Item("c", 3))).await();
        assertEquals(3, count());
        List<Integer> ns = new ArrayList<>();
        for (Row r : pool.query("select n from items order by id").execute().await()) ns.add(r.getInteger("n"));
        assertEquals(List.of(1, 2, 3), ns);
    }

    @Test
    void aFailingRowFailsTheWholeBatchAndWritesNothing() throws Exception {
        PgBatchSink<Item> sink = new PgBatchSink<>(pool, List.of(INSERT));
        sink.write(List.of(new Item("dup", 1))).await();
        Future<Void> f = sink.write(List.of(new Item("x", 1), new Item("dup", 2), new Item("y", 3)));
        Throwable cause = f.toCompletionStage().toCompletableFuture().handle((v, t) -> t).get(30, TimeUnit.SECONDS);
        assertTrue(cause != null, "duplicate key must fail the batch");
        assertTrue(cause.getMessage().contains("items_pkey"), cause.getMessage());
        assertEquals(1, count(), "x and y were rolled back with the batch");
    }

    @Test
    void severalStatementsRunInTheSameTransaction() {
        pool.query("create table item_keys(id text references items(id), k text)").execute().await();
        PgBatchSink.Statement<Item> keys = new PgBatchSink.Statement<>(
                "insert into item_keys(id, k) select * from unnest($1::text[], $2::text[])",
                b -> Tuple.wrap(new Object[] {b.stream().map(Item::id).toArray(String[]::new), b.stream().map(i -> "k" + i.n()).toArray(String[]::new)}));
        PgBatchSink<Item> sink = new PgBatchSink<>(pool, List.of(INSERT, keys));
        sink.write(List.of(new Item("p", 7), new Item("q", 8))).await();
        assertEquals(2, pool.query("select count(*) from item_keys").execute().await().iterator().next().getLong(0));
    }

    @Test
    void aFailureInTheSecondStatementRollsBackTheFirst() throws Exception {
        pool.query("create table item_keys(id text references items(id), k text not null)").execute().await();
        PgBatchSink.Statement<Item> keysWithANull = new PgBatchSink.Statement<>(
                "insert into item_keys(id, k) select * from unnest($1::text[], $2::text[])",
                b -> Tuple.wrap(new Object[] {b.stream().map(Item::id).toArray(String[]::new), b.stream().map(i -> (String) null).toArray(String[]::new)}));
        PgBatchSink<Item> sink = new PgBatchSink<>(pool, List.of(INSERT, keysWithANull));
        Throwable cause = sink.write(List.of(new Item("r", 1), new Item("s", 2)))
                .toCompletionStage().toCompletableFuture().handle((v, t) -> t).get(30, TimeUnit.SECONDS);
        assertTrue(cause != null && cause.getMessage().contains("null value"), String.valueOf(cause));
        assertEquals(0, count(), "the items insert of the same batch was rolled back; autocommit would have left 2 rows");
    }

    @Test
    void anEmptyBatchIsANoOpAndASinkNeedsAtLeastOneStatement() {
        PgBatchSink<Item> sink = new PgBatchSink<>(pool, List.of(INSERT));
        sink.write(List.of()).await();
        assertEquals(0, count());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new PgBatchSink<Item>(pool, List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new PgBatchSink.Statement<Item>(null, b -> Tuple.tuple()));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new PgBatchSink.Statement<Item>("select 1", null));
    }

    @Test
    void theWriterCommitsEverySaveThroughTheSink() throws Exception {
        PgBatchSink<Item> sink = new PgBatchSink<>(pool, List.of(INSERT));
        try (BatchingWriter<Item> writer = new BatchingWriter<>(sink, new VertxTimer(vertx),
                new BatchingWriter.Options(100, Duration.ofMillis(10), 4, 6_400))) {
            List<Future<Void>> saves = new ArrayList<>();
            for (int i = 0; i < 250; i++) saves.add(writer.save(new Item("m-" + i, i)));
            Future.all(saves).await(30, TimeUnit.SECONDS);
            assertEquals(250, count());
            assertEquals(0, writer.backlog());
            assertEquals(0, writer.inFlight());
        }
    }
}
