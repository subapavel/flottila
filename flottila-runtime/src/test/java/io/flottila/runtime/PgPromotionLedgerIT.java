package io.flottila.runtime;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.TestJournalKey;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-136 (D-127 draft, D-114, D-115, review F3.1): what an accepted (202) id survives, measured with a ledger — the ids the
 * node answered 202 for, looked up on the standby once it is promoted. Two postgres:17 containers on one Testcontainers network:
 * the primary ({@code primary}), and a standby cloned from it with {@code pg_basebackup -R} streaming as {@code standby1}
 * ({@code cluster_name}). The standby's receipt is stopped by {@code primary_conninfo=''} and a reload (the primary's
 * {@code pg_stat_replication} empties); the primary is SIGKILLed (docker kill) and the standby promoted with {@code pg_promote}.
 * The node is closed before the kill: the ledger measures what the server kept of what was acknowledged, not a dying node.
 * Every case builds its own pair. No sleep: every wait is a condition (a log line, a {@code pg_stat_replication} row, a
 * setting a new session shows).
 */
@Timeout(300)
class PgPromotionLedgerIT {

    static final String IMAGE = "postgres:17";
    static final String DB = "ledger";
    static final String STANDBY = "standby1";
    static final String INTAKE = "intake";
    static Vertx vertx;

    @TempDir Path data;
    Network network;
    PostgreSQLContainer primary;
    GenericContainer<?> standby;
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    int nodesStarted;

    @BeforeAll
    static void startVertx() { vertx = Vertx.vertx(); }

    @AfterAll
    static void stopVertx() { vertx.close().await(); }

    /** The pair: the primary with a replication line in pg_hba, then the standby cloned from it, streaming. */
    @BeforeEach
    void startThePair() throws Exception {
        network = Network.newNetwork();
        primary = new PostgreSQLContainer(IMAGE).withNetwork(network).withNetworkAliases("primary");
        primary.start();
        Container.ExecResult hba = primary.execInContainer("sh", "-c", "echo 'host replication all all scram-sha-256' >> /var/lib/postgresql/data/pg_hba.conf");
        assertEquals(0, hba.getExitCode(), "fixture: replication allowed: " + hba.getStderr());
        onPrimary("select pg_reload_conf()");
        onPrimary("create database " + DB);
        standby = new GenericContainer<>(DockerImageName.parse(IMAGE)).withNetwork(network).withNetworkAliases("standby").withExposedPorts(5432)
                .withEnv("PGPASSWORD", primary.getPassword())
                .withCommand("bash", "-c", "set -e; chmod 700 /var/lib/postgresql/data; "
                        + "gosu postgres pg_basebackup -h primary -p 5432 -U " + primary.getUsername() + " -D /var/lib/postgresql/data -R -X stream -c fast; "
                        + "exec gosu postgres postgres -c hot_standby=on -c cluster_name=" + STANDBY)
                .waitingFor(Wait.forLogMessage(".*ready to accept read-only connections.*", 1).withStartupTimeout(Duration.ofSeconds(120)));
        standby.start();
        until(() -> streaming().contains(STANDBY + " streaming async"), () -> "fixture: the standby streams: " + streaming());
    }

    @AfterEach
    void stopThePair() throws Exception {
        try {
            for (Node n : nodes) OnVirtualThread.call(() -> { n.close(); return null; });
            for (HttpServer s : servers) s.close().await();
        } finally {
            if (standby != null) standby.stop();
            if (primary != null) primary.stop();
            if (network != null) network.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- cases

    /**
     * Done-when (P1, P4): under {@code ha} over a primary whose {@code synchronous_standby_names} names the streaming standby,
     * every id the node accepted (202) is on the standby after the primary is killed and the standby promoted. Postgres already
     * enforces this once the names are set (this case may pass before FIX-136); its partners below pin what FIX-136 adds.
     */
    @Test
    void everyAcceptedIdSurvivesPromotionUnderTheHaProfile() throws Exception {
        synchronousStandbys(STANDBY);
        Map<String, String> env = env(primary.getUsername(), primary.getPassword());
        env.put("FLOTTILA_PG_DURABILITY", "ha");
        Node node = startNode(env);
        List<String> ledger = new ArrayList<>();
        try (HttpClient client = NodeIT.http()) {
            int inPort = deploy(client, node);
            for (int i = 1; i <= 20; i++) ledger.add(accept(client, inPort, "INV-H" + i));
        }
        close(node);
        killThePrimaryAndPromote();
        assertEquals(20, ledger.size(), "witness: twenty ids accepted");
        assertEquals(Set.of(), missing(ledger), "every accepted id is on the promoted standby");
    }

    /**
     * P2: a role's own {@code statement_timeout} (1 s here, set by {@code ALTER ROLE}) would cancel a commit that waits for its
     * synchronous standby — and Postgres then reports the local commit as a success (syncrep.c): a 202 for an id no standby
     * holds. The node's startup parameter {@code statement_timeout=0} overrides the role: with the standby's receipt stopped, the
     * save waits until the flow's saveTimeout (5 s) and the sender gets 503, while the commit still waits in Postgres (P6: the
     * node never cancels a commit). Once the standby receives again the waiting commit completes. Observed before FIX-136 (red
     * run): the commit waited past the role's 1 s and the 503 held — Postgres 17 does not let statement_timeout cut this wait
     * (presumably postgres.c disables the statement timeout before the commit runs); so the red is the pin itself — a BEFORE INSERT trigger on
     * {@code messages} notes the intake session's own {@code statement_timeout}, which must be 0, not the role's 1s.
     */
    @Test
    void aRoleStatementTimeoutCannotTurnAWaitingCommitInto202() throws Exception {
        synchronousStandbys(STANDBY);
        intakeRole(true);
        onPrimary("alter role " + INTAKE + " set statement_timeout = '1s'");
        assertEquals("1s", query(primaryUrl(DB), INTAKE, INTAKE, "show statement_timeout"), "witness: a session of the role runs with its 1 s statement timeout");
        Map<String, String> env = env(INTAKE, INTAKE);
        env.put("FLOTTILA_PG_DURABILITY", "ha");
        Node node = startNode(env);
        try (HttpClient client = NodeIT.http()) {
            int inPort = deploy(client, node);
            for (String ddl : List.of("create table commit_settings(st text, sc text)",
                    "create function note_settings() returns trigger language plpgsql as $$ begin insert into commit_settings values "
                            + "(current_setting('statement_timeout'), current_setting('synchronous_commit')); return new; end $$",
                    "create trigger note_settings before insert on messages for each row execute function note_settings()")) {
                execute(primaryUrl(DB), INTAKE, INTAKE, ddl);
            }
            accept(client, inPort, "INV-T1");   // fixture: the standby streams, a save commits

            String conninfo = stopReceipt();
            HttpResponse<String> waited = NodeIT.send(client, "POST", inPort, "/in", InvoiceAsyncIT.invoice("INV-T2", "S1"), "application/xml");
            assertEquals(503, waited.statusCode(), "the save waited for the standby until saveTimeout, never cut short to a 202 by the role's "
                    + "statement timeout: " + waited.statusCode() + " " + waited.body());
            assertTrue(syncRepWaiters(INTAKE) >= 1, "the node did not cancel the commit: it still waits for its standby (SyncRep)");

            resumeReceipt(conninfo);
            until(() -> syncRepWaiters(INTAKE) == 0, () -> "the waiting commit completes once the standby receives: " + streaming());
        }
        List<String> seen = rows(primaryUrl(DB), "select st || ' ' || sc from commit_settings");
        assertFalse(seen.isEmpty(), "witness: the trigger saw the intake's inserts of ingress rows");
        assertEquals(List.of(), seen.stream().filter(t -> !t.equals("0 on")).toList(),
                "the intake's sessions run without a statement timeout over the role's 1 s (the startup parameter, P2): " + seen);
    }

    /**
     * P4: {@code ha} refuses at start, before migrating, a primary whose standby streams asynchronously — with
     * {@code synchronous_standby_names} empty, and with it naming no standby that streams (the standby stays async). Witness:
     * the same server, the names naming the standby, starts the same node.
     */
    @Test
    void haRefusesAServerWithOnlyAnAsyncStandby() throws Exception {
        Map<String, String> env = env(primary.getUsername(), primary.getPassword());
        env.put("FLOTTILA_PG_DURABILITY", "ha");
        assertEquals("", query(primary.getJdbcUrl(), primary.getUsername(), primary.getPassword(), "show synchronous_standby_names"), "fixture: empty");

        List<String> empty = refusedStart(env);
        assertTrue(empty.stream().anyMatch(c -> c.contains("synchronous_standby_names")), "names the empty setting: " + empty);
        assertEquals(List.of(), tables(), "refused before migrating: the database is untouched");

        onPrimary("alter system set synchronous_standby_names = 'nobody'");
        onPrimary("select pg_reload_conf()");
        until(() -> query(primary.getJdbcUrl(), primary.getUsername(), primary.getPassword(), "show synchronous_standby_names").equals("nobody")
                && streaming().equals(List.of(STANDBY + " streaming async")), () -> "fixture: names set, the standby still async: " + streaming());
        List<String> noSync = refusedStart(env);
        assertTrue(noSync.stream().anyMatch(c -> c.contains("standby")), "names the missing synchronous standby: " + noSync);
        assertEquals(List.of(), tables(), "refused before migrating: the database is untouched");

        synchronousStandbys(STANDBY);
        Node node = startNode(env);
        assertEquals(200, NodeIT.get(node.adminPort(), "/health").statusCode(), "witness: with a sync standby streaming the ha node starts");
        assertTrue(tables().contains("public.flyway_schema_history"), "witness for 'untouched': a started node migrates: " + tables());
    }

    /**
     * P4: a role without {@code pg_read_all_stats} sees the standby's row in {@code pg_stat_replication} but not its state: ha
     * cannot tell a streaming sync standby from none and refuses, naming {@code GRANT pg_monitor TO intake}. Witness: after
     * that grant the same node starts.
     */
    @Test
    void haRefusesARoleThatCannotSeeReplication() throws Exception {
        synchronousStandbys(STANDBY);
        intakeRole(false);
        Map<String, String> env = env(INTAKE, INTAKE);
        env.put("FLOTTILA_PG_DURABILITY", "ha");

        List<String> refused = refusedStart(env);
        assertTrue(refused.stream().anyMatch(c -> c.contains("GRANT pg_monitor TO " + INTAKE)), "names the grant: " + refused);
        assertEquals(List.of(), tables(), "refused before migrating: the database is untouched");

        onPrimary("grant pg_monitor to " + INTAKE);
        Node node = startNode(env);
        assertEquals(200, NodeIT.get(node.adminPort(), "/health").statusCode(), "witness: with pg_monitor the ha node starts");
        assertTrue(tables().contains("public.flyway_schema_history"), "witness for 'untouched': a started node migrates: " + tables());
    }

    /**
     * FIX-137 B5 (owner decision, D-128): under {@code ha} the duplicate's commit barrier runs {@code pg_logical_emit_message}, so
     * a role that may not run it is refused at start, before migrating, naming the GRANT for its user — never found out at the
     * first duplicate. The signature is read from postgres:17, not assumed: the text variant the barrier resolves to is
     * {@code (boolean,text,text,boolean)} there (17.11; the three-argument form does not exist). EXECUTE is revoked from PUBLIC
     * and from the role (pg_monitor granted, so only the EXECUTE is missing); the role's own barrier statement is denied. Witness:
     * the GRANT the refusal names, run verbatim on the primary, lets the same node start and migrate.
     */
    @Test
    void haRefusesARoleThatMayNotRunTheDuplicateBarrier() throws Exception {
        synchronousStandbys(STANDBY);
        intakeRole(true);
        String url = primaryUrl(DB);
        String signature = query(url, primary.getUsername(), primary.getPassword(), "select string_agg(oid::regprocedure::text, ' | ') from pg_proc "
                + "where proname = 'pg_logical_emit_message' and pronamespace = 'pg_catalog'::regnamespace and proargtypes[2] = 'text'::regtype");
        assertEquals("pg_logical_emit_message(boolean,text,text,boolean)", signature, "fixture: postgres:17's text variant");
        assertEquals(null, query(url, primary.getUsername(), primary.getPassword(),
                "select to_regprocedure('pg_catalog.pg_logical_emit_message(boolean,text,text)')::text"), "fixture: no three-argument form on 17");
        execute(url, "revoke execute on function pg_catalog." + signature + " from public");   // pg_proc is per database: the node's
        execute(url, "revoke execute on function pg_catalog." + signature + " from " + INTAKE);
        assertEquals("f", query(url, primary.getUsername(), primary.getPassword(),
                "select has_function_privilege('" + INTAKE + "', 'pg_catalog." + signature + "', 'EXECUTE')"), "fixture: EXECUTE revoked");
        SQLException denied = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> execute(url, INTAKE, INTAKE, "select pg_current_xact_id(), pg_logical_emit_message(true, 'flottila', '')"),
                "fixture: the role's barrier statement is denied");
        assertTrue(denied.getMessage().contains("permission denied for function pg_logical_emit_message"), "fixture: " + denied.getMessage());

        Map<String, String> env = env(INTAKE, INTAKE);
        env.put("FLOTTILA_PG_DURABILITY", "ha");
        List<String> refused = refusedStart(env);
        java.util.regex.Matcher grant = Pattern.compile("GRANT EXECUTE ON FUNCTION pg_catalog\\.pg_logical_emit_message\\([^)]*\\) TO "
                + INTAKE + "(?![A-Za-z0-9_])").matcher(String.join("\n", refused));
        assertTrue(grant.find(), "names GRANT EXECUTE ON FUNCTION pg_catalog.pg_logical_emit_message(...) TO " + INTAKE + ": " + refused);
        assertEquals(List.of(), tables(), "refused before migrating: the database is untouched");

        execute(url, grant.group());   // the fix the refusal names, verbatim, in the node's database: it must name a function 17 has
        assertEquals("t", query(url, primary.getUsername(), primary.getPassword(),
                "select has_function_privilege('" + INTAKE + "', 'pg_catalog." + signature + "', 'EXECUTE')"), "the named GRANT restores the EXECUTE");
        Node node = startNode(env);
        assertEquals(200, NodeIT.get(node.adminPort(), "/health").statusCode(), "witness: with the EXECUTE granted the ha node starts");
        assertTrue(tables().contains("public.flyway_schema_history"), "witness for 'untouched': a started node migrates: " + tables());
    }

    /**
     * The ledger's self-test (it can see a loss) and P5's local notice. Under {@code local} with the standby streaming
     * asynchronously: five ids accepted and seen on the standby, then the standby's receipt stopped and five more accepted —
     * local does not wait for a standby. After the kill and the promotion exactly the five later ids are missing. The node's
     * health, read at its start, states the local promise and names the streaming standby and the ha profile.
     */
    @Test
    void anAsyncStandbyLosesAcceptedIdsUnderTheLocalProfile() throws Exception {
        Node node = startNode(env(primary.getUsername(), primary.getPassword()));   // ci, FLOTTILA_PG_DURABILITY unset: local
        JsonArray notices = NodeIT.health(node.adminPort(), 200).getJsonArray("notices", new JsonArray());
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        try (HttpClient client = NodeIT.http()) {
            int inPort = deploy(client, node);
            for (int i = 1; i <= 5; i++) before.add(accept(client, inPort, "INV-B" + i));
            until(() -> onStandby(before) == before.size(), () -> "fixture: the standby replayed the first five ids");
            stopReceipt();
            for (int i = 1; i <= 5; i++) after.add(accept(client, inPort, "INV-A" + i));
        }
        close(node);
        killThePrimaryAndPromote();
        List<String> ledger = new ArrayList<>(before);
        ledger.addAll(after);
        assertEquals(new TreeSet<>(after), missing(ledger), "the ledger sees the loss: the ids accepted while the standby received nothing are gone");

        List<String> texts = notices.stream().map(String::valueOf).toList();
        assertTrue(texts.stream().anyMatch(t -> t.contains("survives a Postgres crash") && t.contains("not a promotion")), "the local promise: " + texts);
        assertTrue(texts.stream().anyMatch(t -> t.contains(STANDBY) && Pattern.compile("(?<![A-Za-z_-])ha(?![A-Za-z_-])").matcher(t).find()),
                "a notice names the streaming standby and ha: " + texts);
    }

    /**
     * FIX-137 (D-128, review F3.2): a save whose commit waited for its standby and was ended by a backend termination is committed
     * locally only, and its sender got 503. The sender's retry with the same dedupe key finds the key taken (D-084): under
     * {@code ha} it is answered 202 only once that earlier save is on the synchronous standby (the duplicate's commit barrier
     * waits with it), never at once from a key no standby holds. The in endpoint dedupes by its path ({@link KeyedByPath}): every
     * POST to {@code /in} carries one key. Receipt stopped: the first POST's COMMIT is seen waiting (SyncRep) before its backend is
     * terminated; its retry waits too and gets 503 at saveTimeout (5 s); receipt resumed, a retry gets 202 naming the one message,
     * and that message is on the standby promoted after a SIGKILL.
     */
    @Test
    void aRetriedDuplicateAfterATerminatedSyncWaitIsNot202UntilReplicated() throws Exception {
        synchronousStandbys(STANDBY);
        intakeRole(true);
        Map<String, String> env = env(INTAKE, INTAKE);
        env.put("FLOTTILA_PG_DURABILITY", "ha");
        Node node = startKeyedNode(env);
        String id;
        try (HttpClient client = NodeIT.http()) {
            int inPort = deploy(client, node);
            String conninfo = stopReceipt();

            java.util.concurrent.CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                    NodeIT.request("POST", inPort, "/in", InvoiceAsyncIT.invoice("INV-D1", "S1"), "application/xml"), HttpResponse.BodyHandlers.ofString());
            until(() -> commitWaiters(INTAKE) == 1, () -> "fixture: the first save's COMMIT waits for the standby (SyncRep): " + activity(INTAKE));
            assertEquals("1", query(primary.getJdbcUrl(), primary.getUsername(), primary.getPassword(),
                    "select count(*) filter (where pg_terminate_backend(pid)) from pg_stat_activity where usename = '" + INTAKE
                            + "' and wait_event = 'SyncRep' and query ~* '^\\s*commit'"), "fixture: the waiting backend terminated");
            HttpResponse<String> terminated = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(503, terminated.statusCode(), "fixture: the terminated save is no 202: " + terminated.statusCode() + " " + terminated.body());
            assertEquals(List.of("1"), rows(primaryUrl(DB), "select count(*) from messages where step = 0"),
                    "fixture: the terminated save committed locally (the residual the barrier closes)");

            HttpResponse<String> retried = NodeIT.send(client, "POST", inPort, "/in", InvoiceAsyncIT.invoice("INV-D1", "S1"), "application/xml");
            assertEquals(503, retried.statusCode(), "the retry finds its key taken by a save no standby holds: no 202 while receipt is stopped, "
                    + "503 at saveTimeout: " + retried.statusCode() + " " + retried.body());
            assertTrue(commitWaiters(INTAKE) >= 1, "the retry's commit still waits for the standby (SyncRep): " + activity(INTAKE));

            resumeReceipt(conninfo);
            until(() -> commitWaiters(INTAKE) == 0, () -> "the waiting commits complete once the standby receives: " + streaming());
            id = accept(client, inPort, "INV-D1");
        }
        assertEquals(List.of(id), rows(primaryUrl(DB), "select id from messages where step = 0"),
                "the 202 names the one message: the terminated save's, never a second one");
        close(node);
        killThePrimaryAndPromote();
        assertEquals(Set.of(), missing(List.of(id)), "the id the sender holds a 202 for is on the promoted standby");
    }

    // ------------------------------------------------------------------------------------------------------------- the node

    Map<String, String> env(String user, String password) throws Exception {
        String id = "ledger-" + (++nodesStarted);
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", id);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve(id)).toString());
        env.put("FLOTTILA_PG_HOST", primary.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(primary.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", DB);
        env.put("FLOTTILA_PG_USER", user);
        env.put("FLOTTILA_PG_PASSWORD", password);
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        TestJournalKey.with(env);
        return env;
    }

    Node startNode(Map<String, String> env) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env);
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        nodes.add(node);
        return node;
    }

    /** {@link #startNode} with the http component deduping its in endpoints by the request path ({@link KeyedByPath}). */
    Node startKeyedNode(Map<String, String> env) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env);
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new KeyedByPath(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids()))),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        nodes.add(node);
        return node;
    }

    /** The real http component, its in endpoints naming {@link HttpComponent#PATH} as their dedupe key (D-084): one key per path. */
    record KeyedByPath(HttpComponent http) implements io.flottila.api.Component {
        @Override public String scheme() { return http.scheme(); }
        @Override public String configSchema() { return http.configSchema(); }
        @Override public io.flottila.api.Consumer consumer(io.flottila.api.EndpointConfig cfg,
                java.util.function.Function<io.flottila.api.Envelope, io.vertx.core.Future<io.flottila.api.Ack>> handler) {
            return http.consumer(cfg, handler);
        }
        @Override public io.flottila.api.Producer producer(io.flottila.api.EndpointConfig cfg) { return http.producer(cfg); }
        @Override public io.vertx.core.Future<io.flottila.api.Health> health() { return http.health(); }
        @Override public java.util.Optional<java.net.InetSocketAddress> listensOn(io.flottila.api.EndpointConfig cfg) { return http.listensOn(cfg); }
        @Override public boolean polls(io.flottila.api.EndpointConfig cfg) { return http.polls(cfg); }
        @Override public java.util.Optional<String> target(io.flottila.api.EndpointConfig cfg) { return http.target(cfg); }
        @Override public java.util.Optional<io.flottila.api.Key<String>> dedupeBy(io.flottila.api.EndpointConfig cfg) {
            return java.util.Optional.of(HttpComponent.PATH);
        }
    }

    /** The causes of the refused start of a node over {@code env}; fails when it starts. */
    List<String> refusedStart(Map<String, String> env) throws Exception {
        Node started = null;
        try {
            started = startNode(env);
        } catch (Exception refused) {
            return NodeIT.causes(refused);
        }
        return fail("the node was to be refused, yet it started on admin port " + started.adminPort());
    }

    void close(Node node) throws Exception {
        nodes.remove(node);
        OnVirtualThread.call(() -> { node.close(); return null; });
    }

    /** Deploys invoice-async (at-least-once, saveTimeout 5 s) with a partner answering at once; returns its in port. */
    int deploy(HttpClient client, Node node) throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(InvoiceAsyncIT.DEMO.resolve("fixtures/partner-ack.xml")));
        HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b ->
                req.response().putHeader("content-type", "application/xml").end(ack))).listen(0, "127.0.0.1").await();
        servers.add(partner);
        int inPort = NodeIT.freePort();
        InvoiceAsyncIT.deploy(client, node, NodeIT.zip(InvoiceAsyncIT.asyncFiles(inPort, "http://127.0.0.1:" + partner.actualPort())));
        return inPort;
    }

    /** POSTs invoice {@code id}; asserts the 202 and returns the message id it names. */
    static String accept(HttpClient client, int inPort, String id) throws Exception {
        HttpResponse<String> r = NodeIT.send(client, "POST", inPort, "/in", InvoiceAsyncIT.invoice(id, "S1"), "application/xml");
        assertEquals(202, r.statusCode(), "invoice " + id + " accepted: " + r.body());
        String messageId = r.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElse(null);
        assertNotNull(messageId, "the 202 names its message id: " + r.headers());
        return messageId;
    }

    // ------------------------------------------------------------------------------------------------------------ the servers

    /** A login role {@code intake} owning the node's database (recreated), with or without {@code pg_monitor}. */
    void intakeRole(boolean monitor) throws SQLException {
        onPrimary("create role " + INTAKE + " login password '" + INTAKE + "'");
        onPrimary("drop database " + DB);
        onPrimary("create database " + DB + " owner " + INTAKE);
        if (monitor) onPrimary("grant pg_monitor to " + INTAKE);
    }

    /** {@code synchronous_standby_names} set on the primary, once the standby streams as {@code sync}. */
    void synchronousStandbys(String names) throws SQLException {
        onPrimary("alter system set synchronous_standby_names = '" + names + "'");
        onPrimary("select pg_reload_conf()");
        until(() -> streaming().equals(List.of(STANDBY + " streaming sync")), () -> "fixture: the standby streams as sync: " + streaming());
    }

    /** The standby stops receiving ({@code primary_conninfo=''}, reload) until the primary shows no sender; returns the old conninfo. */
    String stopReceipt() throws SQLException {
        String conninfo = query(standbyUrl(), primary.getUsername(), primary.getPassword(), "show primary_conninfo");
        assertFalse(conninfo.isEmpty(), "fixture: pg_basebackup -R wrote a primary_conninfo");
        onStandby("alter system set primary_conninfo = ''");
        onStandby("select pg_reload_conf()");
        until(() -> streaming().isEmpty(), () -> "fixture: the standby stopped receiving: " + streaming());
        return conninfo;
    }

    void resumeReceipt(String conninfo) throws SQLException {
        onStandby("alter system set primary_conninfo = '" + conninfo.replace("'", "''") + "'");
        onStandby("select pg_reload_conf()");
    }

    /** SIGKILL to the primary's postmaster (no shutdown, no last WAL sent), then the standby promoted and writable. */
    void killThePrimaryAndPromote() throws SQLException {
        primary.getDockerClient().killContainerCmd(primary.getContainerId()).withSignal("KILL").exec();
        until(() -> !primary.isRunning(), () -> "fixture: the primary is dead");
        assertEquals("t", query(standbyUrl(), primary.getUsername(), primary.getPassword(), "select pg_promote(true, 60)"), "fixture: promoted");
        assertEquals("f", query(standbyUrl(), primary.getUsername(), primary.getPassword(), "select pg_is_in_recovery()"), "fixture: out of recovery");
    }

    /** The ledger's ids that have no ingress row on the (promoted) standby. */
    Set<String> missing(List<String> ledger) throws SQLException {
        Set<String> missing = new TreeSet<>(ledger);
        try (Connection c = DriverManager.getConnection(standbyUrl(DB), primary.getUsername(), primary.getPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery("select id from messages where step = 0")) {
            while (r.next()) missing.remove(r.getString(1));
        }
        return missing;
    }

    /** How many of {@code ids} have an ingress row on the standby. */
    long onStandby(List<String> ids) {
        try (Connection c = DriverManager.getConnection(standbyUrl(DB), primary.getUsername(), primary.getPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery("select id from messages where step = 0")) {
            long n = 0;
            while (r.next()) if (ids.contains(r.getString(1))) n++;
            return n;
        } catch (SQLException e) {
            throw new AssertionError("reading the standby: " + e, e);
        }
    }

    /** The primary's senders as {@code application_name state sync_state}. */
    List<String> streaming() {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(primary.getJdbcUrl(), primary.getUsername(), primary.getPassword());
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select application_name || ' ' || state || ' ' || sync_state from pg_stat_replication order by 1")) {
            while (r.next()) rows.add(r.getString(1));
        } catch (SQLException e) {
            throw new AssertionError("reading pg_stat_replication: " + e, e);
        }
        return rows;
    }

    /** Backends of {@code user} waiting for a synchronous standby to confirm their commit. */
    long syncRepWaiters(String user) {
        try (Connection c = DriverManager.getConnection(primary.getJdbcUrl(), primary.getUsername(), primary.getPassword());
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select count(*) from pg_stat_activity where usename = '" + user + "' and wait_event = 'SyncRep'")) {
            r.next();
            return r.getLong(1);
        } catch (SQLException e) {
            throw new AssertionError("reading pg_stat_activity: " + e, e);
        }
    }

    /** Backends of {@code user} whose COMMIT waits for a synchronous standby to confirm it (SyncRep). */
    long commitWaiters(String user) {
        return Long.parseLong(query(primary.getJdbcUrl(), primary.getUsername(), primary.getPassword(), "select count(*) from pg_stat_activity where usename = '"
                + user + "' and wait_event = 'SyncRep' and query ~* '^\\s*commit'"));
    }

    /** {@code user}'s backends as {@code wait_event query}, for a failure message. */
    List<String> activity(String user) {
        try {
            return rows(primary.getJdbcUrl(), "select coalesce(wait_event, '-') || ' ' || coalesce(query, '') from pg_stat_activity where usename = '" + user + "'");
        } catch (SQLException e) {
            return List.of("reading pg_stat_activity: " + e);
        }
    }

    /** The node database's tables outside the system schemas. */
    List<String> tables() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(primaryUrl(DB), primary.getUsername(), primary.getPassword());
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select schemaname || '.' || tablename from pg_tables where schemaname not in ('pg_catalog', 'information_schema')")) {
            while (r.next()) names.add(r.getString(1));
        }
        return names;
    }

    String primaryUrl(String database) {
        return "jdbc:postgresql://" + primary.getHost() + ":" + primary.getMappedPort(5432) + "/" + database;
    }

    String standbyUrl() { return standbyUrl(primary.getDatabaseName()); }

    String standbyUrl(String database) {
        return "jdbc:postgresql://" + standby.getHost() + ":" + standby.getMappedPort(5432) + "/" + database;
    }

    void onPrimary(String sql) throws SQLException {
        execute(primary.getJdbcUrl(), sql);
    }

    void onStandby(String sql) throws SQLException {
        execute(standbyUrl(), sql);
    }

    void execute(String url, String sql) throws SQLException {
        execute(url, primary.getUsername(), primary.getPassword(), sql);
    }

    static void execute(String url, String user, String password, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    List<String> rows(String url, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, primary.getUsername(), primary.getPassword()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            while (r.next()) rows.add(r.getString(1));
        }
        return rows;
    }

    static String query(String url, String user, String password, String sql) {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next(), "one row: " + sql);
            return r.getString(1);
        } catch (SQLException e) {
            throw new AssertionError(sql + ": " + e, e);
        }
    }

    /** Bounded wait (60 s) for {@code condition}: containers and a real node, no clock a case could drive. */
    static void until(BooleanSupplier condition, java.util.function.Supplier<String> what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 60 s: " + what.get());
            Thread.onSpinWait();
        }
    }
}
