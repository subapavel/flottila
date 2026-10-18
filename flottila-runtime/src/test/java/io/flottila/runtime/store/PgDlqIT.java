package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Parked;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                Headers.empty().with("content-type", "application/xml"), Props.empty().with(new Key<String>("PARTNER"), "domestic").with(new Key<Duration>("odd"), Duration.ofSeconds(2)),
                Keys.empty().with("invoiceId", "4711"), new Hash("sha256-64", -7L), 187, BlobKey.body(id, step - 1), Map.of("flow", "invoice-sync@0.1.0"));
    }

    @Test
    void parksByReferenceListsOldestFirstGetsAndRemoves() {
        Parked b = parked("m-2", 5, Instant.parse("2026-01-01T00:00:02.5Z"));
        Parked a = parked("m-1", 5, Instant.parse("2026-01-01T00:00:01Z"));
        dlq.park(b).await();
        dlq.park(a).await();
        dlq.park(parked("m-1", 2, Instant.parse("2026-01-01T00:00:09Z"))).await();
        List<Parked> rows = dlq.list().await();
        assertEquals(List.of("m-1", "m-2"), rows.stream().map(Parked::messageId).toList(), "oldest first; the second park of m-1 was ignored");
        Parked back = rows.get(0);
        assertEquals("PT2S", back.props().asMap().get("odd"), "a prop that is not JSON-native comes back as its string form");
        Props expectedProps = Props.empty().with(new Key<String>("PARTNER"), "domestic").with(new Key<String>("odd"), "PT2S");
        assertEquals(new Parked(a.messageId(), a.flow(), a.step(), a.stepName(), a.error(), a.parkedAt(), a.headers(), expectedProps, a.keys(), a.bodyHash(), a.bodySize(), a.bodyKey(), a.versions()), back);
        assertEquals(Optional.of(back), dlq.get("m-1").await());
        assertEquals(Optional.empty(), dlq.get("m-9").await());
        dlq.remove("m-1").await();
        dlq.remove("m-9").await();
        assertEquals(List.of("m-2"), dlq.list().await().stream().map(Parked::messageId).toList());
    }
}
