package io.flottila.runtime.node;

import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a acceptance 2 (D-101 (2), fail-stop): a node whose row a peer released finds itself superseded at its next renew and
 * the process exits with {@link Node#SUPERSEDED}. The node runs in a child JVM ({@link NodeChild}) with a short lease
 * ({@code FLOTTILA_NODE_LEASE} PT2S, renewed every PT0.5S); the release is the peer's statement, run here. The witness: the same
 * child, not released, runs on and exits 0 when told to stop.
 */
class NodeFailStopIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final String NODE = "node-failstop";
    static Vertx vertx;

    @TempDir Path data;
    final List<Process> children = new ArrayList<>();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @AfterEach
    void killChildren() {
        children.forEach(Process::destroyForcibly);
    }

    /** A child node on the container's database; its JaCoCo agent, if this JVM runs one, goes with it. */
    Process child() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), NodeChild.class.getName()));
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", NODE);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", pg.getDatabaseName());
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "2");
        env.put("FLOTTILA_NODE_LEASE", "PT2S");
        env.put("FLOTTILA_NODE_LEASE_RENEW", "PT0.5S");
        TestJournalKey.with(env);
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(data.resolve("child-stderr.txt").toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        Process child = builder.start();
        children.add(child);
        BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        OnVirtualThread.Call<String> first = OnVirtualThread.start((java.util.concurrent.Callable<String>) out::readLine);   // bounded: a child that never speaks fails the case
        assertEquals("started", OnVirtualThread.join(first), "the child node started: " + stderr());
        return child;
    }

    String stderr() throws Exception {
        Path f = data.resolve("child-stderr.txt");
        return Files.exists(f) ? Files.readString(f) : "";
    }

    static Object leaseUntil(PgConnection admin) {
        return admin.preparedQuery("select lease_until from nodes where node = $1").execute(Tuple.of(NODE)).await().iterator().next().getValue(0);
    }

    @Test
    void aReleasedNodeExitsSupersededAtItsNextRenew() throws Exception {
        PgConnection admin = PgConnection.connect(vertx, new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword())).await();
        try {
            // witness: a node nobody releases runs on through its renews and exits 0 when told to stop
            Process alive = child();
            Object joined = leaseUntil(admin);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (java.util.Objects.equals(leaseUntil(admin), joined) && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(java.util.Objects.equals(leaseUntil(admin), joined), "fixture: the child renews its lease");
            assertTrue(alive.isAlive(), "renewing, the node runs on: " + stderr());
            alive.getOutputStream().close();
            assertTrue(alive.waitFor(60, TimeUnit.SECONDS), "the child stops when told to");
            assertEquals(0, alive.exitValue(), stderr());

            // the case: a peer releases the row of a running node; its next renew finds it superseded and the process exits
            Process released = child();
            int rows = admin.preparedQuery("update nodes set lease_until = null where node = $1").execute(Tuple.of(NODE)).await().rowCount();
            assertEquals(1, rows, "fixture: the peer's release");
            assertTrue(released.waitFor(60, TimeUnit.SECONDS), "the superseded node halts on its own: " + stderr());
            assertEquals(Node.SUPERSEDED, released.exitValue(), "fail-stop with the superseded status: " + stderr());
            assertTrue(stderr().contains("was superseded"), "its ERROR says why: " + stderr());
        } finally {
            admin.close().await();
        }
    }
}
