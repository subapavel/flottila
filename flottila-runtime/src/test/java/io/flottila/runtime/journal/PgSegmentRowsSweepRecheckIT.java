package io.flottila.runtime.journal;

import io.flottila.runtime.db.Migrations;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-127 (D-105, the later-snapshot re-check): a pass locks its unpinned candidates, then deletes those still unpinned
 * under the delete's own, later snapshot. A pin the lock's snapshot did not see — committed by another session after the
 * pass's first statement, before its second — keeps its segment: the pass removes only the candidate nothing pins.
 *
 * <p>The seam is the client boundary: the pool handed to {@link PgSegmentRows} is the real one, its transaction's
 * connection the real one, except that once the transaction's first statement has answered, the pin is committed on
 * another connection before that answer is passed on. The pin is a raw row (no {@code for key share} on the segment row,
 * which the lock would make it wait for): it stands for a pin committed after the lock statement's snapshot was taken.
 */
class PgSegmentRowsSweepRecheckIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final String NODE = "n1";
    static final Instant KEPT_SINCE = Instant.parse("2026-03-01T00:00:00Z");
    static final Instant FRESH = Instant.parse("2026-06-01T00:00:00Z");

    static Vertx vertx;
    static Pool plain;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        plain = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(2)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() { plain.close().await(); vertx.close().await(); pg.stop(); }

    @BeforeEach
    void fill() {
        plain.query("truncate work, body_refs, dlq, journal_segments").execute().await();
        plain.query("insert into journal_segments(node, segment, copies) values ('" + NODE + "', 1, array['" + NODE + "']), ('"
                + NODE + "', 2, array['" + NODE + "'])").execute().await();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a live work row", "a fresh kept ref"})
    void aPinCommittedAfterTheLockStatementKeepsItsSegment(String pin) {
        String insert = pin.equals("a live work row")
                ? "insert into work(id, flow, step, body_node, body_segment, body_position, body_length) values ('late', 't', 1, '" + NODE + "', 1, 0, 132)"
                : "insert into body_refs(message_id, step, part, node, segment, position, length, created_at) values ('late', 1, 'body', '"
                        + NODE + "', 1, 0, 132, '" + FRESH + "'::timestamptz)";
        List<Integer> firstAnswer = new ArrayList<>();
        PgSegmentRows rows = new PgSegmentRows(afterFirstStatement(plain, answered -> {
            firstAnswer.add(answered.size());
            return plain.query(insert).execute().mapEmpty();
        }));

        Set<Long> swept = new TreeSet<>(rows.sweep(NODE, Set.of(1L, 2L), KEPT_SINCE).await());

        assertEquals(List.of(2), firstAnswer, "fixture: the pass's first statement saw both candidates unpinned");
        assertEquals(Set.of(2L), swept, pin + " committed after the lock statement keeps segment 1; segment 2 is swept");
        List<Long> left = new ArrayList<>();
        for (Row r : plain.query("select segment from journal_segments where node = '" + NODE + "' order by segment").execute().await()) {
            left.add(r.getLong(0));
        }
        assertEquals(List.of(1L), left, "segment 1's row stays");
    }

    /** The pool, whose connections run {@code between} once the first prepared statement executed on them has answered. */
    @SuppressWarnings("unchecked")
    private static Pool afterFirstStatement(Pool real, Function<RowSet<Row>, Future<Void>> between) {
        return proxy(Pool.class, (self, method, args) -> {
            if (method.getName().equals("getConnection") && method.getParameterCount() == 0) {
                return ((Future<SqlConnection>) call(real, method, args)).map(conn -> connection(conn, between));
            }
            return method.isDefault() ? InvocationHandler.invokeDefault(self, method, args) : call(real, method, args);
        });
    }

    @SuppressWarnings("unchecked")
    private static SqlConnection connection(SqlConnection real, Function<RowSet<Row>, Future<Void>> between) {
        AtomicInteger executed = new AtomicInteger();
        return proxy(SqlConnection.class, (self, method, args) -> {
            Object answer = call(real, method, args);
            if (!method.getName().equals("preparedQuery")) return answer;
            PreparedQuery<RowSet<Row>> query = (PreparedQuery<RowSet<Row>>) answer;
            return proxy(PreparedQuery.class, (q, m, a) -> {
                Object result = call(query, m, a);
                if (!(m.getName().equals("execute") && a != null && a.length == 1 && a[0] instanceof Tuple)) return result;
                if (executed.getAndIncrement() != 0) return result;
                return ((Future<RowSet<Row>>) result).compose(rs -> between.apply(rs).map(v -> rs));
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(PgSegmentRowsSweepRecheckIT.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
