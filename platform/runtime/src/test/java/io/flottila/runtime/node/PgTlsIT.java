package io.flottila.runtime.node;

import io.flottila.runtime.db.Migrations;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgNotification;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-146 (review F9.1, F-29, D-124): the node reaches a TLS-only Postgres ({@code ssl=on}, a pg_hba of {@code hostssl} lines only)
 * with the CA it is given and, under {@code verify-full}, the server's name checked — the pool, the membership, the LISTEN
 * connection and the Flyway migration alike; a wrong CA, a misnamed server under verify-full and {@code disable} are refused.
 * Two servers, one CA: {@link #pg} presents a certificate naming the host the client dials; {@link #misnamed} one naming only
 * {@code db.flottila.test}. The admin channel is psql over the container's local socket (trust), independent of TLS. A third,
 * {@link #plain}, runs with {@code ssl} off: a verifying node never falls back to plaintext against it (review r1 R2).
 */
class PgTlsIT {

    static final String MISNAMED_HOST = "db.flottila.test";
    static final String NODE = "node-tls";

    @TempDir static Path certs;
    static TestCertificates ca;
    static TestCertificates otherCa;
    static PostgreSQLContainer pg;
    static PostgreSQLContainer misnamed;
    static PostgreSQLContainer plain;
    static Vertx vertx;

    @TempDir Path data;
    final List<Node> nodes = new ArrayList<>();
    static final AtomicInteger DATABASES = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        ca = TestCertificates.ca(certs, "ca");
        otherCa = TestCertificates.ca(certs, "other-ca");
        String dockerHost = DockerClientFactory.instance().dockerHostIpAddress();
        String san = "dns:localhost,ip:127.0.0.1" + (dockerHost.equals("localhost") || dockerHost.equals("127.0.0.1") ? ""
                : dockerHost.chars().allMatch(c -> c == '.' || Character.isDigit(c)) ? ",ip:" + dockerHost : ",dns:" + dockerHost);
        pg = tlsOnly(ca.server("server", "localhost", san));
        misnamed = tlsOnly(ca.server("misnamed", MISNAMED_HOST, "dns:" + MISNAMED_HOST));
        plain = new PostgreSQLContainer(PostgresImage.name());   // the image's defaults: ssl off, pg_hba "host all all all scram-sha-256"
        pg.start();
        misnamed.start();
        plain.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        if (vertx != null) vertx.close().await();
        if (pg != null) pg.stop();
        if (misnamed != null) misnamed.stop();
        if (plain != null) plain.stop();
    }

    @AfterEach
    void closeNodes() throws Exception {
        for (Node n : nodes) OnVirtualThread.call(() -> { n.close(); return null; });
    }

    /**
     * The Postgres of {@link PostgresImage#name()} serving {@code server} with TLS on and a pg_hba that admits TCP only over TLS ({@code hostssl}); the key is copied
     * in, then owned by postgres with mode 0600 (the server refuses to start otherwise) before the image's entrypoint runs.
     */
    static PostgreSQLContainer tlsOnly(TestCertificates.Server server) {
        String hba = "local all all trust\nhostssl all all all scram-sha-256\n";
        String script = "mkdir -p /certs && cp /certs-in/* /certs/ && chown postgres:postgres /certs/* && chmod 600 /certs/server.key"
                + " && exec docker-entrypoint.sh postgres -c fsync=off -c ssl=on -c ssl_cert_file=/certs/server.crt"
                + " -c ssl_key_file=/certs/server.key -c hba_file=/certs/pg_hba.conf";
        return new PostgreSQLContainer(PostgresImage.name())
                .withCopyToContainer(Transferable.of(server.certificate(), 0644), "/certs-in/server.crt")
                .withCopyToContainer(Transferable.of(server.key(), 0600), "/certs-in/server.key")
                .withCopyToContainer(Transferable.of(hba.getBytes(StandardCharsets.US_ASCII), 0644), "/certs-in/pg_hba.conf")
                .withCommand("sh", "-c", script);
    }

    /** psql on {@code server}'s local socket (trust, no TLS): the answer's text, trimmed; a failed statement fails the case. */
    static String psql(PostgreSQLContainer server, String database, String sql) throws Exception {
        Container.ExecResult r = server.execInContainer("psql", "-U", server.getUsername(), "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", sql);
        assertEquals(0, r.getExitCode(), "fixture: psql " + sql + ": " + r.getStderr());
        return r.getStdout().trim();
    }

    /** A fresh database on {@code server} for one case: what a case migrates or refuses is its own. */
    static String database(PostgreSQLContainer server) throws Exception {
        String name = "tls_" + DATABASES.incrementAndGet();
        psql(server, server.getDatabaseName(), "create database " + name);
        return name;
    }

    /** A node's environment on {@code server}'s {@code database} with {@code mode} and {@code rootCert} (null: the key unset). */
    Map<String, String> env(PostgreSQLContainer server, String database, String mode, Path rootCert) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", NODE);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve(database + "-" + mode)).toString());
        env.put("FLOTTILA_PG_HOST", server.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(server.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", server.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", server.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "2");
        env.put("FLOTTILA_NODE_LEASE", "PT2S");
        env.put("FLOTTILA_NODE_LEASE_RENEW", "PT0.5S");
        env.put("FLOTTILA_PG_SSL_MODE", mode);
        if (rootCert != null) env.put("FLOTTILA_PG_SSL_ROOT_CERT", rootCert.toString());
        return TestJournalKey.with(env);
    }

    Node start(Map<String, String> env) throws Exception {
        Node n = OnVirtualThread.call(() -> Node.start(NodeConfig.from(env), vertx, ctx -> List.of(), List.of(), List.of()));
        nodes.add(n);
        return n;
    }

    /** Every class name and message down the cause chain of {@code t}, one line: what the refusal says, wherever it was wrapped. */
    static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) b.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" <- ");
        return b.toString();
    }

    /** {@code t} is a TLS refusal (the handshake, or the client's check of the server's name), never the server's pg_hba. */
    static boolean tls(Throwable t) {
        String text = chain(t).toLowerCase(java.util.Locale.ROOT);
        return (text.contains("handshake") || text.contains("hostname")) && !text.contains("pg_hba.conf");
    }

    /** One statement through a one-connection pool built from the node's connect options; its failure, else null. */
    static Throwable poolRefusal(NodeConfig.Pg config) {
        Pool pool = PgBuilder.pool().connectingTo(Node.connectOptions(config)).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        try {
            pool.query("select 1").execute().await(30, TimeUnit.SECONDS);
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            pool.close().await();
        }
    }

    /** Flyway over the node's JDBC URL; its failure, else null. */
    static Throwable migrationRefusal(NodeConfig.Pg config) {
        try {
            Migrations.migrate(Node.jdbcUrl(config), config.user(), config.password());
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    static boolean migrated(PostgreSQLContainer server, String database) throws Exception {
        return !psql(server, database, "select coalesce(to_regclass('flyway_schema_history')::text, '')").isEmpty();
    }

    @Test
    void aNodeWithTheRightCaAndVerifyFullStartsAndEveryConnectionRunsOverTls() throws Exception {
        String db = database(pg);
        Map<String, String> env = env(pg, db, "verify-full", ca.certificate());
        start(env);

        assertTrue(migrated(pg, db), "the migrations ran over the JDBC URL (TCP is TLS-only here)");
        assertNotEquals("0", psql(pg, db, "select count(*) from flyway_schema_history where success"), "Flyway recorded its migrations");
        assertEquals("1", psql(pg, db, "select count(*) from journal_keys"), "the pool answered: the node's start stored its journal key through it");
        assertEquals("t", psql(pg, db, "select bool_and(s.ssl) and count(*) >= 3 from pg_stat_activity a join pg_stat_ssl s using (pid)"
                + " where a.datname = '" + db + "' and a.backend_type = 'client backend' and a.pid <> pg_backend_pid()"), "the pool, the membership and the LISTEN sessions are TLS");
        assertEquals("1", psql(pg, db, "select count(*) from pg_stat_activity a join pg_stat_ssl s using (pid) where a.datname = '" + db
                + "' and s.ssl and a.query = 'LISTEN " + io.flottila.runtime.queue.PgWorkSignals.CHANNEL + "'"), "the node listens on a TLS session of its own");

        String joined = psql(pg, db, "select lease_until::text from nodes where node = '" + NODE + "'");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (joined.equals(psql(pg, db, "select lease_until::text from nodes where node = '" + NODE + "'")) && System.nanoTime() < deadline) Thread.onSpinWait();
        assertNotEquals(joined, psql(pg, db, "select lease_until::text from nodes where node = '" + NODE + "'"), "the membership renews over its TLS connection");

        // a LISTEN on a connection built from the node's options is delivered a NOTIFY sent from elsewhere
        PgConnection listener = PgConnection.connect(vertx, Node.connectOptions(NodeConfig.from(env).pg())).await(30, TimeUnit.SECONDS);
        try {
            CompletableFuture<PgNotification> heard = new CompletableFuture<>();
            listener.notificationHandler(heard::complete);
            listener.query("LISTEN fix146").execute().await(30, TimeUnit.SECONDS);
            psql(pg, db, "notify fix146, 'over-tls'");
            assertEquals("over-tls", heard.get(30, TimeUnit.SECONDS).getPayload(), "the LISTEN delivers over TLS");
        } finally {
            listener.close().await();
        }
    }

    @Test
    void aWrongCaIsRefusedByThePoolTheMigrationAndTheStart() throws Exception {
        String db = database(pg);
        Map<String, String> env = env(pg, db, "verify-full", otherCa.certificate());
        NodeConfig.Pg config = NodeConfig.from(env).pg();

        Throwable pool = poolRefusal(config);
        assertTrue(pool != null && tls(pool), "the pool refuses a server its CA did not sign: " + (pool == null ? "answered" : chain(pool)));
        Throwable migration = migrationRefusal(config);
        assertTrue(migration != null && tls(migration),
                "Flyway's JDBC refuses a server its CA did not sign: " + (migration == null ? "migrated" : chain(migration)));
        Throwable start = assertThrows(Throwable.class, () -> start(env), "a node with the wrong CA does not start");
        assertTrue(tls(start), "refused at the TLS handshake: " + chain(start));
        assertFalse(migrated(pg, db), "nothing was migrated");

        // witness: the same database, the right CA — the refusals above were the CA's
        assertEquals(null, poolRefusal(NodeConfig.from(env(pg, db, "verify-full", ca.certificate())).pg()), "fixture: the right CA is answered");
    }

    @Test
    void aServerNamedOtherwiseFailsVerifyFullAndPassesVerifyCa() throws Exception {
        String db = database(misnamed);
        Map<String, String> full = env(misnamed, db, "verify-full", ca.certificate());
        NodeConfig.Pg fullConfig = NodeConfig.from(full).pg();
        Throwable pool = poolRefusal(fullConfig);
        assertTrue(pool != null && tls(pool), "verify-full: the pool refuses at TLS a certificate naming only " + MISNAMED_HOST + " for "
                + misnamed.getHost() + ": " + (pool == null ? "answered" : chain(pool)));
        Throwable migration = migrationRefusal(fullConfig);
        assertTrue(migration != null && tls(migration), "verify-full: Flyway's JDBC refuses the misnamed server at TLS: "
                + (migration == null ? "migrated" : chain(migration)));
        Throwable start = assertThrows(Throwable.class, () -> start(full), "verify-full: a node does not start against a server named otherwise");
        assertTrue(tls(start), "refused at TLS: " + chain(start));
        assertFalse(migrated(misnamed, db), "nothing was migrated under verify-full");

        Map<String, String> caOnly = env(misnamed, db, "verify-ca", ca.certificate());
        assertEquals(null, poolRefusal(NodeConfig.from(caOnly).pg()), "verify-ca: the CA signed it, its name is not checked");
        start(caOnly);
        assertTrue(migrated(misnamed, db), "verify-ca: the node started and migrated over JDBC");
        assertEquals("t", psql(misnamed, db, "select bool_and(s.ssl) and count(*) >= 3 from pg_stat_activity a join pg_stat_ssl s using (pid)"
                + " where a.datname = '" + db + "' and a.backend_type = 'client backend' and a.pid <> pg_backend_pid()"), "verify-ca: every node session is TLS");
    }

    @Test
    void disableAgainstATlsOnlyServerIsRefused() throws Exception {
        String db = database(pg);
        Map<String, String> env = env(pg, db, "disable", null);
        NodeConfig.Pg config = NodeConfig.from(env).pg();
        Throwable pool = poolRefusal(config);
        assertTrue(pool != null && chain(pool).contains("no encryption"), "the server refuses a plaintext session (hostssl only): "
                + (pool == null ? "answered" : chain(pool)));
        Throwable migration = migrationRefusal(config);
        assertTrue(migration != null && chain(migration).contains("no encryption"), "and a plaintext JDBC session: " + (migration == null ? "migrated" : chain(migration)));
        assertThrows(Throwable.class, () -> start(env), "a node set to disable does not start against a TLS-only server");
        assertFalse(migrated(pg, db), "nothing was migrated");
    }

    /** {@code t} is the client's refusal of a server that answered "no SSL": Vert.x's or pgjdbc's words, never a credentials or pg_hba failure. */
    static boolean refusedPlaintext(Throwable t) {
        String text = chain(t).toLowerCase(java.util.Locale.ROOT);
        return (text.contains("does not handle ssl") || text.contains("does not support ssl")) && !text.contains("pg_hba.conf")
                && !text.contains("password authentication failed");
    }

    @ParameterizedTest(name = "FLOTTILA_PG_SSL_MODE={0}")
    @ValueSource(strings = {"verify-full", "verify-ca"})
    void aVerifyingModeAgainstAPlaintextServerIsRefusedByThePoolTheMigrationAndTheStart(String mode) throws Exception {
        assertEquals("off", psql(plain, plain.getDatabaseName(), "show ssl"), "fixture: the server offers no TLS");
        String db = database(plain);
        Map<String, String> env = env(plain, db, mode, ca.certificate());
        NodeConfig.Pg config = NodeConfig.from(env).pg();

        Throwable pool = poolRefusal(config);
        assertTrue(pool != null && refusedPlaintext(pool), mode + ": the pool never falls back to plaintext: " + (pool == null ? "answered" : chain(pool)));
        Throwable migration = migrationRefusal(config);
        assertTrue(migration != null && refusedPlaintext(migration),
                mode + ": Flyway's JDBC never falls back to plaintext: " + (migration == null ? "migrated" : chain(migration)));
        Throwable start = assertThrows(Throwable.class, () -> start(env), mode + ": a node does not start against a plaintext server");
        assertTrue(refusedPlaintext(start), "refused for the missing TLS: " + chain(start));
        assertFalse(migrated(plain, db), "nothing was migrated");

        // witness: the same server and database answer a plaintext client — the refusals above were the mode's, not the server's
        assertEquals(null, poolRefusal(NodeConfig.from(env(plain, db, "disable", null)).pg()), "fixture: disable is answered");
    }

    /**
     * R1: Node.start migrates over {@link Node#jdbcUrl}, never a URL without the node's TLS settings (a bare one is pgjdbc's
     * {@code prefer}: TLS without any check). A forwarder sends the pool's first connection (the synchronous_commit check) to the
     * server named as dialled and every later one — the migration's — to {@link #misnamed}, the same database name on both: under
     * verify-full the migration is refused at the name check, the start fails and the misnamed server holds no schema history.
     */
    @Test
    void theStartsMigrationIsVerifiedAsThePoolIs() throws Exception {
        String db = "tls_" + DATABASES.incrementAndGet();
        psql(pg, pg.getDatabaseName(), "create database " + db);
        psql(misnamed, misnamed.getDatabaseName(), "create database " + db);
        try (Forwarder forwarder = new Forwarder(pg, misnamed)) {
            Map<String, String> env = env(pg, db, "verify-full", ca.certificate());
            env.put("FLOTTILA_PG_HOST", "127.0.0.1");   // named in pg's certificate, never in misnamed's
            env.put("FLOTTILA_PG_PORT", String.valueOf(forwarder.port()));

            Throwable start = assertThrows(Throwable.class, () -> start(env), "the migration's connection reaches a server named otherwise");
            assertTrue(tls(start), "refused at TLS: " + chain(start));
            assertEquals(1, forwarder.first.get(), "the pool's one connection went to the server named as dialled (and passed: the start went on)");
            assertTrue(forwarder.later.get() >= 1, "the migration connected through the forwarder to the misnamed server");
            assertFalse(migrated(misnamed, db), "nothing was migrated on the misnamed server: the migration checked its name");
            assertFalse(migrated(pg, db), "nor on the right one");
        }
    }

    /**
     * A TCP forwarder on 127.0.0.1: its first accepted connection is piped to {@code first}, every later one to {@code later}.
     * Each pipe ends when either side closes (a refused TLS handshake resets it); an accept failure other than the close fails close().
     */
    static final class Forwarder implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger later = new AtomicInteger();
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        final AtomicReference<IOException> failure = new AtomicReference<>();
        final Thread acceptor;

        Forwarder(PostgreSQLContainer named, PostgreSQLContainer otherwise) throws IOException {
            acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket client = server.accept();
                        sockets.add(client);
                        boolean isFirst = first.get() == 0 && later.get() == 0;
                        PostgreSQLContainer target = isFirst ? named : otherwise;
                        (isFirst ? first : later).incrementAndGet();
                        Socket upstream = new Socket(target.getHost(), target.getMappedPort(5432));
                        sockets.add(upstream);
                        pipe(client, upstream);
                        pipe(upstream, client);
                    }
                } catch (IOException e) {
                    if (!server.isClosed()) failure.set(e);
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        static void pipe(Socket from, Socket to) {
            Thread.ofVirtual().start(() -> {
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    in.transferTo(out);
                } catch (IOException ended) {
                    // the other side closed or reset: the pipe ends with it
                } finally {
                    closeQuietly(from);
                    closeQuietly(to);
                }
            });
        }

        static void closeQuietly(Socket s) {
            try {
                s.close();
            } catch (IOException alreadyClosed) {
                // closing a socket twice is the pipe's normal end
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            acceptor.join();
            sockets.forEach(Forwarder::closeQuietly);
            if (failure.get() != null) throw failure.get();
        }
    }
}
