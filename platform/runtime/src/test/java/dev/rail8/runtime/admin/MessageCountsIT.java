package dev.rail8.runtime.admin;

import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counts by status of the list of recent messages ({@code GET /admin/message-counts?from=&to=}) over Postgres, through
 * {@link AdminPgFixture}: messages of a deployed at-least-once flow that complete, are parked, are cancelled by an operator or
 * wait in the work queue, and sync messages whose step failed, in and after the hour counted. The counts match the list
 * filtered to each status, narrow by flow and time, name every status, and refuse the ranges the list refuses.
 */
class MessageCountsIT {

    static final Instant T0 = FakeRuntime.T0;
    static final String SYNC = MessageListIT.SYNC;

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

    @Test
    void countsTheMessagesOfATimeRangeAndAFlowByStatusAsTheListListsThem() throws Exception {
        node.rt.clock().set(T0.plus(Duration.ofMinutes(1)));
        node.delivered();
        node.rt.clock().set(T0.plus(Duration.ofMinutes(2)));
        node.delivered();
        node.rt.clock().set(T0.plus(Duration.ofMinutes(3)));
        node.parked();
        node.rt.clock().set(T0.plus(Duration.ofMinutes(4)));
        String cancelled = node.parked();
        assertEquals(200, node.send(HttpMethod.POST, "/admin/dlq/" + cancelled + "/cancel").status(), "fixture: the operator cancels it");
        node.rt.clock().set(T0.plus(Duration.ofMinutes(5)));
        node.accepted();   // the last one queued: nothing claims it
        MessageListIT.syncFailed(node, T0.plus(Duration.ofMinutes(6)));
        MessageListIT.syncFailed(node, T0.plus(Duration.ofHours(2)));   // after the hour counted
        node.messages.flush().await();

        JsonObject all = counts("");
        assertEquals(new JsonObject().put("completed", 2L).put("failed", 2L).put("running", 1L).put("cancelled", 1L), all, "the hour of every flow");
        for (String status : List.of("completed", "failed", "running", "cancelled")) {
            assertEquals(all.getLong(status), (long) MessageListIT.ids(list("&status=" + status)).size(), "as many as the list filtered to " + status + " shows");
        }
        assertEquals(new JsonObject().put("completed", 2L).put("failed", 1L).put("running", 1L).put("cancelled", 1L), counts("&flow=" + AdminPgFixture.FLOW),
                "the at-least-once flow");
        assertEquals(new JsonObject().put("completed", 0L).put("failed", 1L).put("running", 0L).put("cancelled", 0L), counts("&flow=" + SYNC), "the sync flow");
        assertEquals(new JsonObject().put("completed", 0L).put("failed", 0L).put("running", 0L).put("cancelled", 0L), counts("&flow=shop/none"),
                "a flow with no messages: every status at 0");
        assertEquals(new JsonObject().put("completed", 0L).put("failed", 1L).put("running", 0L).put("cancelled", 0L),
                send("/admin/message-counts?from=" + T0.plus(Duration.ofHours(1)) + "&to=" + T0.plus(Duration.ofHours(3))), "the later range");

        assertRefused("/admin/message-counts?from=" + T0, "to is required");
        assertRefused("/admin/message-counts", "from is required");
        assertRefused("/admin/message-counts?from=" + T0 + "&to=" + T0, "from must be before to");
        assertRefused("/admin/message-counts?from=" + T0 + "&to=" + T0.plus(Duration.ofDays(32)), "longer than 31 days");
        assertRefused("/admin/message-counts?from=" + T0 + "&to=tomorrow", "to must be an ISO-8601 instant");
    }

    /** The counts by status over the hour from {@link #T0}, narrowed by {@code more}. */
    JsonObject counts(String more) throws Exception {
        return send("/admin/message-counts?from=" + T0 + "&to=" + T0.plus(Duration.ofHours(1)) + more);
    }

    /** The list over the hour from {@link #T0}, narrowed by {@code more}. */
    JsonObject list(String more) throws Exception {
        return send("/admin/messages?from=" + T0 + "&to=" + T0.plus(Duration.ofHours(1)) + more);
    }

    void assertRefused(String path, String says) throws Exception {
        AdminPgFixture.Answer a = node.send(HttpMethod.GET, path);
        assertEquals(400, a.status(), path + ": " + a.body());
        assertTrue(a.json().getString("error").contains(says), path + ": " + a.body());
    }

    JsonObject send(String path) throws Exception {
        AdminPgFixture.Answer a = node.send(HttpMethod.GET, path);
        assertEquals(200, a.status(), path + ": " + a.body());
        return a.json();
    }
}
