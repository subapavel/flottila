package dev.rail8.runtime.admin;

import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.store.Retention;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import dev.rail8.testkit.SharedPostgres;
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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.admin.AdminPgFixture.BOUND;
import static dev.rail8.runtime.admin.AdminPgFixture.ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-263 (D-156: a failed message is retried under its own id; P2-07b1, D-096 for the retention), end to end over Postgres: a
 * message its worker parked outlives the retention — its rows and its ingress body stay while a DLQ row names it, however old —
 * so after a retention run {@code POST /admin/dlq/{id}/retry} still answers 202 and the worker delivers it, on the bytes it was
 * accepted with. A message that finished ages out whole: its rows and its body go, and a GET of its body answers 404. Through
 * {@link AdminPgFixture}: the real {@link dev.rail8.runtime.blob.TieredBlobStore} over a node's journal, the Postgres stores, the
 * {@link Retention} job (one run) and the {@link AdminServer} over a real {@code Deployments}.
 *
 * <p>It replaces {@code ReplayRetentionIT} (a replay from a step whose body retention took, the withdrawn route's 409).
 */
class RetentionRetryIT {

    static final SharedPostgres pg = SharedPostgres.database();
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

    /**
     * X is parked by its worker, K delivered; both are 30 days old against a 7-day retention. One retention run takes K whole —
     * its rows and its body's ref — and keeps X's rows and X's body (a DLQ row names it). Then the GET of K's ingress body is 404
     * "no step 0 recorded" (never the router's), X's still reads its bytes, and {@code /retry} of X answers 202
     * {@code {id: X, attempt: 2}}: its DLQ row goes and the worker delivers X once more, under its id, as attempt 2, on the bytes it
     * was accepted with. Witness: before the run K's body read 200.
     */
    @Test
    void aParkedMessageOutlivesTheRetentionAndItsRetryDeliversIt() throws Exception {
        String x = node.parked();
        String k = node.delivered();
        // Wait for each message's exact, final rows — the delivery's ingress and the one step's run (OK for K, FAILED for X) — not
        // for "at least two": a late row written after the ageing below would be young, and X would then be kept for its age
        // rather than for its DLQ row. Every row of both is aged before the run; after it, X still holds exactly these rows.
        List<String> kFinal = List.of("ingress OK", "probe OK");
        List<String> xFinal = List.of("ingress OK", "probe FAILED");
        AdminPgFixture.until(() -> rowsOf(k).equals(kFinal) && rowsOf(x).equals(xFinal),
                "fixture: the final rows of " + k + " " + kFinal + " and " + x + " " + xFinal + " are written");
        assertEquals(1, node.count("select count(*) from body_refs where message_id = $1 and step = 0 and part = 'body'", x), "fixture: X's ingress body is kept: its ref");
        assertEquals(200, node.send(HttpMethod.GET, "/admin/messages/" + k + "/steps/0/body").status(), "witness: K's body reads before the run");
        long kRows = node.count("select count(*) from messages where id = $1", k);
        assertEquals(2, kRows, "fixture: K's two rows");
        assertEquals(2, node.count("select count(*) from messages where id = $1", x), "fixture: X's two rows");

        // X and K age out
        for (String id : List.of(x, k)) {
            pool.preparedQuery("update messages set created_at = now() - interval '30 days' where id = $1").execute(Tuple.of(id)).await();
            pool.preparedQuery("update body_refs set created_at = now() - interval '30 days' where message_id = $1").execute(Tuple.of(id)).await();
        }
        FakeClock clock = FakeClock.at(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        Retention job = new Retention(pool, clock, new FakeTimer(clock), new Retention.Config(Duration.ofDays(7), Duration.ofHours(1), 100));
        Retention.Run run;
        try {
            run = job.runOnce().await(BOUND.toSeconds(), TimeUnit.SECONDS);
        } finally {
            job.close().await(BOUND.toSeconds(), TimeUnit.SECONDS);
        }
        assertEquals("DONE", run.outcome().name(), String.valueOf(run));
        assertEquals(kRows, run.messages(), "K's rows went, no other: " + run);
        assertEquals(0, node.count("select count(*) from messages where id = $1", k), "K's rows are gone");
        assertEquals(0, node.count("select count(*) from body_refs where message_id = $1", k), "K's body ref went with them");
        assertTrue(node.count("select count(*) from messages where id = $1", x) > 0, "X's rows stay: a DLQ row names it");
        assertEquals(xFinal, rowsOf(x), "X keeps exactly its final rows");
        assertEquals(0, node.count("select count(*) from messages where id = $1 and created_at > now() - interval '7 days'", x),
                "every row X keeps is older than the retention: kept for its DLQ row, not for its age");
        assertEquals(1, node.count("select count(*) from body_refs where message_id = $1 and step = 0 and part = 'body'", x), "X's body stays");

        AdminPgFixture.Answer kBody = node.send(HttpMethod.GET, "/admin/messages/" + k + "/steps/0/body");
        assertEquals(404, kBody.status(), "the GET of an aged body of a finished message: " + kBody.body());
        assertEquals(new JsonObject().put("error", "no step 0 recorded for message " + k), kBody.json(), "the route's own 404, not the router's");
        AdminPgFixture.Answer xBody = node.send(HttpMethod.GET, "/admin/messages/" + x + "/steps/0/body");
        assertEquals(200, xBody.status(), "X's body still reads: " + xBody.body());
        assertEquals(ORDER, xBody.body().toString(), "its bytes as accepted");

        AdminPgFixture.Answer retried = node.retry(x);
        assertEquals(202, retried.status(), "a parked message older than the retention is retried: " + retried.body());
        assertEquals(new JsonObject().put("id", x).put("attempt", 2), retried.json(), "under its own id, as attempt 2");
        assertEquals(Optional.empty(), node.dlq.get(x).await(), "its DLQ row went");
        int before = node.runs.size();
        assertEquals(1, node.worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "the worker claims the restarted message");
        assertEquals(List.of(x + "@2 " + ORDER), node.runs.subList(before, node.runs.size()), "delivered once, under its id, as attempt 2, on its accepted bytes");
        assertEquals(0, node.count("select count(*) from work where id = $1", x), "its work row completed");
        assertEquals(Optional.empty(), node.dlq.get(x).await(), "not parked again");
    }

    /** {@code id}'s rows in the store as {@code "<step name> <outcome>"}, in step order. */
    List<String> rowsOf(String id) {
        return node.messages.timeline(id).await().stream().map(s -> s.stepName() + " " + s.outcome()).toList();
    }
}
