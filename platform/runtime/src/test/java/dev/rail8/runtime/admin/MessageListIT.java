package dev.rail8.runtime.admin;

import dev.rail8.api.Hash;
import dev.rail8.api.Headers;
import dev.rail8.api.Keys;
import dev.rail8.api.Props;
import dev.rail8.core.record.Snapshot;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.admin.AdminPgFixture.BOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list of recent messages ({@code GET /admin/messages?from=&to=}) over Postgres, through {@link AdminPgFixture}: a deployed
 * at-least-once flow whose messages complete, fail and are parked, are cancelled by an operator, are retried and complete, or
 * wait in the work queue, and a sync message whose step failed, recorded as its run records it. The list returns them newest
 * first, each with its status, start, duration and keys, and narrows them by flow, status, time, key and step, a page at a time.
 */
class MessageListIT {

    static final Instant T0 = FakeRuntime.T0;
    static final String SYNC = "shop/sync";

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
    void listsTheMessagesNewestFirstWithTheirStatusNarrowsThemAndRefusesWhatItCannotList() throws Exception {
        node.rt.clock().set(T0.plus(Duration.ofMinutes(1)));
        String completed = node.delivered();
        node.rt.clock().set(T0.plus(Duration.ofMinutes(2)));
        String failed = node.parked();
        node.rt.clock().set(T0.plus(Duration.ofMinutes(4)));
        String cancelled = node.parked();
        assertEquals(200, node.send(HttpMethod.POST, "/admin/dlq/" + cancelled + "/cancel").status(), "fixture: the operator cancels it");
        node.rt.clock().set(T0.plus(Duration.ofMinutes(5)));
        String retried = node.parked();
        assertEquals(202, node.retry(retried).status(), "fixture: the operator retries it");
        assertEquals(1, node.worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "fixture: its attempt 2 runs and completes");
        node.rt.clock().set(T0.plus(Duration.ofMinutes(5).plusSeconds(30)));
        String running = node.accepted();   // the last one queued: nothing claims it
        String sync = syncFailed(T0.plus(Duration.ofMinutes(6)));
        node.messages.flush().await();

        JsonObject all = list("");
        assertEquals(List.of(sync, running, retried, cancelled, failed, completed), ids(all), "newest first: " + all);
        assertEquals(List.of("failed", "running", "completed", "cancelled", "failed", "completed"), field(all, "status"), "each one's status: " + all);
        assertEquals(List.of(SYNC, AdminPgFixture.FLOW, AdminPgFixture.FLOW, AdminPgFixture.FLOW, AdminPgFixture.FLOW, AdminPgFixture.FLOW), field(all, "flow"));
        assertEquals(T0.plus(Duration.ofMinutes(1)).toString(), item(all, completed).getString("startedAt"), "the start of its arrival");
        assertEquals(1_002_000_000L, item(all, sync).getLong("durationNanos"), "from its arrival to the end of its failed step");
        assertEquals(new JsonObject().put("invoiceId", "4711"), item(all, sync).getJsonObject("keys"), "the keys its last row carries");
        assertEquals(new JsonObject(), item(all, completed).getJsonObject("keys"));
        assertNull(all.getString("next"), "one page");

        assertEquals(List.of(sync, failed), ids(list("&status=failed")), "parked, or a failed step in a sync run");
        assertEquals(List.of(running), ids(list("&status=running")), "in the work queue");
        assertEquals(List.of(cancelled), ids(list("&status=cancelled")), "cancelled by the operator");
        assertEquals(List.of(retried, completed), ids(list("&status=completed")), "an earlier failed attempt does not count");
        assertEquals(List.of(running, retried, cancelled, failed, completed), ids(list("&flow=" + AdminPgFixture.FLOW)), "by flow");
        assertEquals(List.of(failed), ids(list("&flow=" + AdminPgFixture.FLOW + "&status=failed")), "by flow and status");
        assertEquals(List.of(sync), ids(list("&key=invoiceId:4711")), "by business key");
        assertEquals(List.of(sync), ids(list("&step=send")), "by the step a message passed");
        assertEquals(List.of(), ids(list("&key=invoiceId:4712")));

        JsonObject window = send("/admin/messages?from=" + T0.plus(Duration.ofMinutes(2)) + "&to=" + T0.plus(Duration.ofMinutes(5)));
        assertEquals(List.of(cancelled, failed), ids(window), "from is in the range, to is not: " + window);

        List<String> paged = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            JsonObject page = list("&limit=2" + (after == null ? "" : "&after=" + URLEncoder.encode(after, StandardCharsets.UTF_8)));
            paged.addAll(ids(page));
            after = page.getString("next");
            pages++;
        } while (after != null);
        assertEquals(ids(all), paged, "the pages, one after the other, are the whole list");
        assertEquals(3, pages);

        // refused: a range that is missing, reversed or too long, a status it does not know, a cursor it did not hand out
        assertRefused("/admin/messages?from=" + T0, "to is required");
        assertRefused("/admin/messages?from=" + T0 + "&to=" + T0, "from must be before to");
        assertRefused("/admin/messages?from=" + T0 + "&to=" + T0.plus(Duration.ofDays(32)), "longer than 31 days");
        assertRefused("/admin/messages?from=yesterday&to=" + T0, "from must be an ISO-8601 instant");
        assertRefused("/admin/messages?from=" + T0 + "&to=" + T0.plus(Duration.ofHours(1)) + "&status=lost", "status must be");
        assertRefused("/admin/messages?from=" + T0 + "&to=" + T0.plus(Duration.ofHours(1)) + "&after=bm9wZQ", "not a cursor");
    }

    void assertRefused(String path, String says) throws Exception {
        AdminPgFixture.Answer a = node.send(HttpMethod.GET, path);
        assertEquals(400, a.status(), path + ": " + a.body());
        assertTrue(a.json().getString("error").contains(says), path + ": " + a.body());
    }

    /** A sync message of {@value #SYNC} as its run records it: its arrival at {@code at}, then its step {@code send} failing a second later. */
    String syncFailed(Instant at) {
        String id = node.rt.ids().next();
        Keys keys = new Keys(Map.of("invoiceId", "4711"));
        node.messages.record(new Snapshot(id, SYNC, 0, 1, "ingress", Snapshot.Outcome.OK, Optional.empty(), at, 1_000_000, Snapshot.CPU_UNKNOWN,
                Headers.empty(), Props.empty(), Keys.empty(), new Hash("sha256-64", 1), 5, Optional.empty(), Map.of()));
        node.messages.record(new Snapshot(id, SYNC, 1, 1, "send", Snapshot.Outcome.FAILED, Optional.of("partner refused"), at.plusSeconds(1), 2_000_000,
                Snapshot.CPU_UNKNOWN, Headers.empty(), Props.empty(), keys, new Hash("sha256-64", 1), 5, Optional.empty(), Map.of()));
        return id;
    }

    /** The list over the hour from {@link #T0}, narrowed by {@code more}. */
    JsonObject list(String more) throws Exception {
        return send("/admin/messages?from=" + T0 + "&to=" + T0.plus(Duration.ofHours(1)) + more);
    }

    JsonObject send(String path) throws Exception {
        AdminPgFixture.Answer a = node.send(HttpMethod.GET, path);
        assertEquals(200, a.status(), path + ": " + a.body());
        return a.json();
    }

    static List<String> ids(JsonObject page) {
        return field(page, "id");
    }

    static List<String> field(JsonObject page, String name) {
        JsonArray items = page.getJsonArray("items");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) values.add(items.getJsonObject(i).getString(name));
        return values;
    }

    static JsonObject item(JsonObject page, String id) {
        JsonArray items = page.getJsonArray("items");
        for (int i = 0; i < items.size(); i++) if (items.getJsonObject(i).getString("id").equals(id)) return items.getJsonObject(i);
        throw new AssertionError(id + " is not listed: " + page);
    }
}
