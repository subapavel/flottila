package dev.rail8.runtime.admin;

import dev.rail8.api.BlobKey;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.record.Snapshot;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.admin.AdminPgFixture.BOUND;
import static dev.rail8.runtime.admin.AdminPgFixture.ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c3 packet item 4 end to end (CORE-3 "Review r2 additions", D-105's exception to D-062): a message whose body an accepted
 * {@code RAIL8_JOURNAL_ACCEPT_LOSS} gave up — its {@code body_refs} row marked {@code lost_at}, as the start marks it — is refused
 * for a retry ({@code POST /admin/dlq/{id}/retry}, FIX-263: the same-id restart, D-156) with 409 {@code {error}} saying "body lost
 * by ACCEPT_LOSS" (never "removed by retention"; its DLQ row stays, nothing is queued or run), and the admin GET of that body
 * answers <b>410 Gone</b> with that reason: unlike a body retention removed (404, D-057) the message's rows say a body was
 * stored, and unlike a 409 no state of the server can be changed to make it readable again — it is gone for good. Over Postgres,
 * through {@link AdminPgFixture}: an at-least-once flow whose worker parked both messages, the real
 * {@link dev.rail8.runtime.blob.TieredBlobStore} over a node's journal, a real {@code Deployments} and the {@link AdminServer}.
 * Witness: before the mark X's body reads; a sibling message that was not given up still reads after it, and its retry restarts
 * it.
 */
class LostBodyAdminIT {

    static final String LOST = "body lost by ACCEPT_LOSS";

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool pool;
    static HttpClientAgent client;

    @TempDir Path tmp;
    AdminPgFixture node;

    @BeforeAll
    static void up() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void down() {
        client.close().await();
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() throws Exception {
        node = new AdminPgFixture(vertx, pool, client, tmp);
    }

    @AfterEach
    void stop() throws Exception {
        node.close();
    }

    static void saysLost(AdminPgFixture.Answer a, int status, String id, String what) {
        assertEquals(status, a.status(), what + ": " + a.body());
        String error = a.json().getString("error");
        assertTrue(error != null && error.contains(LOST) && error.contains(id), what + ": the error names the message and says why: " + a.body());
        assertTrue(!error.contains("removed by retention"), what + ": never retention's reason: " + error);
    }

    @Test
    void aLostBodyIsRefused409ForRetryAndItsGetIs410() throws Exception {
        String x = node.parked();
        String k = node.parked();
        AdminPgFixture.until(() -> node.messages.timeline(x).await().stream().anyMatch(s -> s.step() == 0), "fixture: X's ingress row is written");
        Snapshot ingress = node.messages.timeline(x).await().stream().filter(s -> s.step() == 0).findFirst().orElseThrow();
        assertEquals(Optional.of(BlobKey.body(x, 0)), ingress.bodyKey(), "fixture: X's step 0 row names its ingress body");
        assertEquals(1, node.count("select count(*) from body_refs where message_id = $1 and step = 0 and part = 'body'", x), "fixture: a kept ref");
        Parked row = node.dlq.get(x).await().orElseThrow();
        assertEquals(200, node.send(HttpMethod.GET, "/admin/messages/" + x + "/steps/0/body").status(), "witness: X's body reads before the loss");

        pool.preparedQuery("update body_refs set lost_at = now() where message_id = $1").execute(Tuple.of(x)).await();   // the accepted loss
        int ran = node.runs.size();

        saysLost(node.retry(x), 409, x, "a retry of a message whose body was lost");
        assertEquals(Optional.of(row), node.dlq.get(x).await(), "the refused retry keeps its DLQ row, unchanged, for the operator");
        assertEquals(0, node.count("select count(*) from work where id = $1", x), "nothing queued");
        assertEquals(0, node.worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "nothing to claim");
        assertEquals(ran, node.runs.size(), "nothing ran");

        saysLost(node.send(HttpMethod.GET, "/admin/messages/" + x + "/steps/0/body"), 410, x, "the GET of a lost body: gone for good");

        AdminPgFixture.Answer kBody = node.send(HttpMethod.GET, "/admin/messages/" + k + "/steps/0/body");
        assertEquals(200, kBody.status(), "witness: K, not given up, reads: " + kBody.body());
        assertEquals(ORDER, kBody.body().toString());
        AdminPgFixture.Answer kRetried = node.retry(k);
        assertEquals(202, kRetried.status(), "witness: and its retry restarts it: " + kRetried.body());
        assertEquals(new JsonObject().put("id", k).put("attempt", 2), kRetried.json());
        assertEquals(1, node.worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "witness: the worker claims K");
        assertEquals(List.of(k + "@2 " + ORDER), node.runs.subList(ran, node.runs.size()), "witness: K delivered once, as attempt 2");
        assertEquals(Optional.of(row), node.dlq.get(x).await(), "X's row still stays");
        assertTrue(node.dlq.get(k).await().isEmpty(), "K's went");
    }
}
