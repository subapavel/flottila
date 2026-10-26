package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Parked;
import io.flottila.core.record.Page;
import io.flottila.runtime.db.Migrations;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Postgres DLQ: a row by reference per parked message, every field back, oldest first, the first park wins. */
class PgDlqIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    PgDlq dlq;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() {
        pool.query("truncate dlq").execute().await();
        dlq = new PgDlq(pool);
    }

    static Parked parked(String id, int step, Instant at) {
        return new Parked(id, "invoice-sync", step, "partner", "java.lang.IllegalStateException: 503 from partner", at,
                Headers.empty().with("content-type", "application/xml"),
                Props.empty().with(new Key<String>("PARTNER"), "domestic").with(new Key<Duration>("odd"), Duration.ofSeconds(2)).with(new Key<Boolean>("flag"), true).with(new Key<Integer>("n"), 7),
                Keys.empty().with("invoiceId", "4711"), new Hash("sha256-64", -7L), 187, BlobKey.body(id, step - 1), Map.of("flow", "invoice-sync@0.1.0"));
    }

    @Test
    void parksByReferenceListsOldestFirstGetsAndRemoves() {
        Parked b = parked("m-2", 5, Instant.parse("2026-01-01T00:00:02.5Z"));
        Parked a = parked("m-1", 5, Instant.parse("2026-01-01T00:00:01Z"));
        dlq.park(b).await();
        dlq.park(a).await();
        dlq.park(parked("m-1", 2, Instant.parse("2026-01-01T00:00:09Z"))).await();
        List<Parked> rows = dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items();
        assertEquals(List.of("m-1", "m-2"), rows.stream().map(Parked::messageId).toList(), "oldest first; the second park of m-1 was ignored");
        Parked back = rows.get(0);
        assertEquals("PT2S", back.props().asMap().get("odd"), "a prop that is not JSON-native comes back as its string form");
        Props expectedProps = Props.empty().with(new Key<String>("PARTNER"), "domestic").with(new Key<String>("odd"), "PT2S").with(new Key<Boolean>("flag"), true).with(new Key<Integer>("n"), 7);
        assertEquals(new Parked(a.messageId(), a.flow(), a.step(), a.stepName(), a.error(), a.parkedAt(), a.headers(), expectedProps, a.keys(), a.bodyHash(), a.bodySize(), a.bodyKey(), a.versions()), back);
        assertEquals(Optional.of(back), dlq.get("m-1").await());
        assertEquals(Optional.empty(), dlq.get("m-9").await());
        dlq.remove("m-1").await();
        dlq.remove("m-9").await();
        assertEquals(List.of("m-2"), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(Parked::messageId).toList());
    }

    // --- D-057 (a): the DLQ paged by (parked_at in microseconds, id)

    static List<String> ids(Page<Parked> page) {
        return page.items().stream().map(Parked::messageId).toList();
    }

    @Test
    void dlqPagesOldestFirstWithTiesBrokenById() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00.999999Z"), t2 = Instant.parse("2026-01-01T00:00:01.000000Z"), t3 = t2.plusNanos(1_000);
        dlq.park(parked("d-b", 3, t2)).await();
        dlq.park(parked("d-z", 3, t1)).await();
        dlq.park(parked("d-a", 3, t1)).await();
        dlq.park(parked("d-c", 3, t3)).await();
        dlq.park(parked("d-m", 3, t1)).await();
        assertEquals(List.of("d-a", "d-m", "d-z", "d-b", "d-c"), ids(dlq.list(Optional.empty(), Page.MAX_LIMIT).await()), "parked_at, then id — not parking order");
        Page<Parked> first = dlq.list(Optional.empty(), 2).await();
        assertEquals(List.of("d-a", "d-m"), ids(first));
        Page<Parked> second = dlq.list(first.next(), 2).await();
        assertEquals(List.of("d-z", "d-b"), ids(second), "the tie at t1 continues across the page boundary (the cursor keeps the microseconds), then the next second");
        Page<Parked> third = dlq.list(second.next(), 2).await();
        assertEquals(List.of("d-c"), ids(third), "one microsecond later");
        assertEquals(Optional.empty(), third.next());
        // the cursor is a position (parked_at, id), not an offset — a retry removing an earlier row skips nothing (InMemoryDlq alike)
        dlq.remove("d-a").await();
        assertEquals(List.of("d-z", "d-b"), ids(dlq.list(first.next(), 2).await()), "the cursor handed out before the remove still continues at d-z");
    }

    @Test
    void withinOneSecondTheEarlierRowComesFirstWhateverItsId() {
        Instant second = Instant.parse("2026-01-01T00:00:05Z");
        dlq.park(parked("d-x", 3, second.plusMillis(200))).await();
        dlq.park(parked("d-w", 3, second.plusMillis(300))).await();
        dlq.park(parked("d-y", 3, second.plusMillis(100))).await();
        assertEquals(List.of("d-y", "d-x", "d-w"), ids(dlq.list(Optional.empty(), Page.MAX_LIMIT).await()), "sub-second parkedAt orders before the id");
        Page<Parked> first = dlq.list(Optional.empty(), 1).await();
        assertEquals(List.of("d-y"), ids(first));
        Page<Parked> second2 = dlq.list(first.next(), 1).await();
        assertEquals(List.of("d-x"), ids(second2), "the cursor carries the sub-second part: d-w (a smaller id, later) does not jump ahead");
        assertEquals(List.of("d-w"), ids(dlq.list(second2.next(), 1).await()));
    }

    @Test
    void removingTheCursorRowAndOneBeforeItSkipsNothing() {
        for (int i = 1; i <= 5; i++) dlq.park(parked("m-" + i, 2, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i))).await();
        Page<Parked> first = dlq.list(Optional.empty(), 2).await();
        assertEquals(List.of("m-1", "m-2"), ids(first));
        dlq.remove("m-1").await();   // a row before the cursor (a retry took it)
        dlq.remove("m-2").await();   // the cursor's own row
        Page<Parked> second = dlq.list(first.next(), 2).await();
        assertEquals(List.of("m-3", "m-4"), ids(second), "the cursor is a position (parkedAt, id), not an offset or a row that must still exist");
        Page<Parked> third = dlq.list(second.next(), 2).await();
        assertEquals(List.of("m-5"), ids(third));
        assertEquals(Optional.empty(), third.next());
    }

    @Test
    void theLastPageHasNoNextCursor() {
        for (int i = 1; i <= 3; i++) dlq.park(parked("m-" + i, 2, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i))).await();
        Page<Parked> exact = dlq.list(Optional.empty(), 3).await();
        assertEquals(List.of("m-1", "m-2", "m-3"), ids(exact));
        assertEquals(Optional.empty(), exact.next(), "exactly limit rows left: limit + 1 fetch, no empty page after");
        assertEquals(Optional.empty(), dlq.list(Optional.empty(), 4).await().next());
        Page<Parked> first = dlq.list(Optional.empty(), 2).await();
        assertEquals(List.of("m-1", "m-2"), ids(first));
        assertTrue(first.next().isPresent(), "a third row exists");
        Page<Parked> last = dlq.list(first.next(), 2).await();
        assertEquals(List.of("m-3"), ids(last));
        assertEquals(Optional.empty(), last.next());
        pool.query("truncate dlq").execute().await();
        Page<Parked> empty = dlq.list(Optional.empty(), 2).await();
        assertEquals(List.of(), empty.items());
        assertEquals(Optional.empty(), empty.next());
    }

    @Test
    void aGarbledCursorOrLimitOutsideOneToFiveHundredIsRefused() {
        dlq.park(parked("m-1", 2, Instant.parse("2026-01-01T00:00:01Z"))).await();
        dlq.park(parked("m-2", 2, Instant.parse("2026-01-01T00:00:02Z"))).await();
        for (int limit : new int[] {0, -1, Page.MAX_LIMIT + 1, Integer.MIN_VALUE}) refused(() -> dlq.list(Optional.empty(), limit).await(), "limit " + limit);
        String notACursor = Base64.getUrlEncoder().withoutPadding().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("!!!", notACursor)) refused(() -> dlq.list(Optional.of(cursor), 1).await(), "cursor " + cursor);
        assertEquals(List.of("m-1"), ids(dlq.list(Optional.empty(), 1).await()), "1 is a limit");
        assertEquals(List.of("m-1", "m-2"), ids(dlq.list(Optional.empty(), Page.MAX_LIMIT).await()), "500 is a limit");
    }

    @Test
    void aCursorFarOutOfPostgresRangeIsRefused() {
        dlq.park(parked("m-1", 2, Instant.parse("2026-01-01T00:00:01Z"))).await();
        for (long micros : new long[] {-1_000_000_000_000_000_000L, Long.MIN_VALUE}) {   // ~29 700 BC and ~290 000 BC: before 4713 BC
            String wellFormed = Base64.getUrlEncoder().withoutPadding().encodeToString((micros + ":m-1").getBytes(StandardCharsets.UTF_8));
            refused(() -> dlq.list(Optional.of(wellFormed), 1).await(), "a cursor at " + micros + " µs");
        }
        assertEquals(List.of("m-1"), ids(dlq.list(Optional.empty(), 1).await()), "the store still answers");
    }

    /** Refused with IllegalArgumentException (the admin API's 400), thrown at the call or as the failed future's cause. */
    static void refused(org.junit.jupiter.api.function.Executable call, String what) {
        Throwable t = assertThrows(Throwable.class, call, what + " is refused");
        assertInstanceOf(IllegalArgumentException.class, t, what + ": " + t);
    }
}
