package io.flottila.runtime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09d3: {@code io.flottila.runtime.Main} in a JVM of its own, started the way an operator starts it ({@code java -cp …
 * io.flottila.runtime.Main}, the configuration in the environment and nothing else). A refused configuration exits 2 with one
 * stderr line; a Postgres that cannot be reached exits 1; a started node outlives {@code main} on Vert.x's non-daemon threads
 * and serves {@code /health}; SIGTERM runs the shutdown hook, which closes the node — its Postgres connections end with a
 * Terminate message, which a process that merely dies never sends.
 *
 * <p>The child gets the JaCoCo agent when the test JVM runs under one (jacoco:prepare-agent's {@code -javaagent} in the
 * surefire / failsafe {@code argLine}): the same agent jar, its own destfile {@code jacoco-main-process.exec} next to the test
 * JVM's destfile, appended to. The child's environment is cleared first: an inherited {@code JAVA_TOOL_OPTIONS} would add a
 * "Picked up" line to its stderr.
 *
 * <p>The Postgres server logs at DEBUG1 with the database in every line's prefix: a backend whose client went away without a
 * Terminate message logs {@code unexpected EOF on client connection} at DEBUG1 — the witness that tells a closed node from a
 * process that died. {@link #aKilledNodeLeavesAnUnexpectedEofInThePostgresLog} is the witness's own self-test.
 */
class MainProcessIT {

    /** How long a child may take to start, to exit, or to close what it holds. */
    static final Duration BOUND = Duration.ofSeconds(60);
    /** The exit status of a JVM that SIGTERM ended after its shutdown hooks ran: 128 + 15, observed on JDK 25 / Linux. */
    static final int SIGTERM_EXIT = 128 + 15;

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17")
            .withCommand("postgres", "-c", "fsync=off", "-c", "log_min_messages=debug1", "-c", "log_line_prefix=[db=%d] ");
    static final AtomicInteger cases = new AtomicInteger();

    @TempDir Path data;
    @TempDir Path logs;
    String database;
    Process child;
    Path stderr;

    /**
     * The child's destfile starts empty: a destfile a previous run left would otherwise be merged into this run's report (with
     * stale coverage of {@code Main}) whenever no child writes it again — append=true only ever adds to it.
     */
    @BeforeAll
    static void start() throws IOException {
        Optional<Path> destfile = childDestfile();
        if (destfile.isPresent()) Files.deleteIfExists(destfile.get());
        pg.start();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @BeforeEach
    void createTheCaseDatabase() throws SQLException {
        database = "main_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
    }

    /** The child never outlives its case: SIGTERM, then SIGKILL once the bound has passed. */
    @AfterEach
    void destroyTheChild() throws InterruptedException {
        if (child == null) return;
        child.destroy();
        if (!child.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS)) {
            child.destroyForcibly();
            assertTrue(child.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS), "the child " + child.pid() + " survived SIGKILL");
        }
    }

    // --- cases

    @Test
    void aRefusedConfigurationExits2WithOneStderrLine() throws Exception {
        Map<String, String> env = env(closedPort());
        env.remove("FLOTTILA_ENV");
        launch(env);
        int code = exitCode();
        List<String> lines = stderrLines();
        assertEquals(2, code, "a refused configuration exits with 2: " + lines);
        assertEquals(1, lines.size(), "one line on stderr, no stack trace, no log: " + lines);
        assertTrue(lines.get(0).startsWith("flottila: configuration refused: ") && lines.get(0).contains("FLOTTILA_ENV"),
                "the line is the refusal and names the key: " + lines.get(0));
        assertEquals(0, connections(), "the refused node never connected to its database");
    }

    @Test
    void anUnreachablePostgresExits1() throws Exception {
        Map<String, String> env = env(closedPort());
        env.put("FLOTTILA_PG_PORT", String.valueOf(closedPort()));
        launch(env);
        int code = exitCode();
        List<String> lines = stderrLines();
        assertEquals(1, code, "a node that cannot reach Postgres does not start and exits with 1: " + lines);
        List<String> own = lines.stream().filter(l -> l.startsWith("flottila:")).toList();
        assertEquals(1, own.size(), "one line of Main's own on stderr (library log lines aside): " + lines);
        assertTrue(own.get(0).startsWith("flottila: the node did not start: "), "the line says the node did not start: " + own.get(0));
    }

    @Test
    void aStartedNodeOutlivesMainOnNonDaemonThreadsAndServesHealth200() throws Exception {
        int port = freePort();
        launch(env(port));
        untilServing(port);
        until(this::mainReturned, "the child's main thread returned (a DestroyJavaVM thread waits for the non-daemon ones)");
        assertTrue(child.isAlive(), "the JVM outlives main: Vert.x's threads are not daemons");
        assertEquals(200, health(port), "and the node still serves /health after main returned");
    }

    @Test
    void sigtermClosesTheNode() throws Exception {
        int port = freePort();
        launch(env(port));
        untilServing(port);
        until(() -> connections() > 0, "the node holds a pooled connection to its database after /health");
        child.destroy();   // SIGTERM on Linux
        assertTrue(child.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS), "SIGTERM ends the child within " + BOUND + ": " + stderrLines());
        assertEquals(SIGTERM_EXIT, child.exitValue(), "the exit status of a JVM SIGTERM ended: " + stderrLines());
        assertTrue(refuses(port), "the admin port refuses after SIGTERM");
        until(() -> connections() == 0, "no connection to the case database is left");
        List<String> eofs = unexpectedEofs();
        assertEquals(List.of(), eofs, "the shutdown hook closed the node's pool (a Terminate per connection), the process did not just die");
    }

    /** The witness's self-test: a node SIGKILL ends never closes its pool, and Postgres logs each connection's end as an EOF. */
    @Test
    void aKilledNodeLeavesAnUnexpectedEofInThePostgresLog() throws Exception {
        int port = freePort();
        launch(env(port));
        untilServing(port);
        until(() -> connections() > 0, "the node holds a pooled connection to its database after /health");
        child.destroyForcibly();
        assertTrue(child.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS), "SIGKILL ends the child");
        until(() -> connections() == 0, "no connection to the case database is left");
        assertFalse(unexpectedEofs().isEmpty(), "a node that never closed its pool leaves an unexpected EOF in the server log");
    }

    // --- the child

    /** A node's environment on the case database, data under the case's temporary directory; nothing inherited. */
    Map<String, String> env(int adminPort) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", "main-it");
        env.put("FLOTTILA_ADMIN_PORT", String.valueOf(adminPort));
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        return env;
    }

    void launch(Map<String, String> env) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        jacocoAgent().ifPresent(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add("io.flottila.runtime.Main");
        stderr = logs.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(stderr.toFile()).redirectOutput(logs.resolve("stdout.txt").toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        child = builder.start();
    }

    /**
     * The test JVM's JaCoCo agent (jacoco:prepare-agent's {@code -javaagent:<agent jar>=destfile=<module>/target/jacoco.exec,…}), for
     * the child: the same jar and options, the destfile {@link #childDestfile()}, appended to. Empty when the test JVM runs without
     * the agent (an IDE run): the child then runs without it too.
     */
    static Optional<String> jacocoAgent() {
        return testJvmAgent().map(spec -> {
            int eq = spec.indexOf('=');
            String jar = eq < 0 ? spec : spec.substring(0, eq);
            List<String> options = new ArrayList<>();
            for (String option : eq < 0 ? new String[0] : spec.substring(eq + 1).split(",")) {
                if (!option.startsWith("destfile=") && !option.startsWith("append=") && !option.isEmpty()) options.add(option);
            }
            options.add("destfile=" + childDestfile().orElseThrow());
            options.add("append=true");
            return "-javaagent:" + jar + "=" + String.join(",", options);
        });
    }

    /** {@code jacoco-main-process.exec} beside the test JVM's destfile ({@code target/jacoco.exec} when it names none); empty without the agent. */
    static Optional<Path> childDestfile() {
        return testJvmAgent().map(spec -> {
            Path destfile = Path.of("target", "jacoco.exec");
            int eq = spec.indexOf('=');
            for (String option : eq < 0 ? new String[0] : spec.substring(eq + 1).split(",")) {
                if (option.startsWith("destfile=")) destfile = Path.of(option.substring("destfile=".length()));
            }
            return destfile.resolveSibling("jacoco-main-process.exec").toAbsolutePath();
        });
    }

    /** The test JVM's JaCoCo {@code -javaagent:} argument without its prefix: {@code <agent jar>[=<options>]}. */
    static Optional<String> testJvmAgent() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("org.jacoco.agent"))
                .map(arg -> arg.substring("-javaagent:".length())).findFirst();
    }

    /** The child's exit code; it must exit on its own within the bound. */
    int exitCode() throws InterruptedException, IOException {
        assertTrue(child.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS), "the child exits on its own within " + BOUND + ": " + stderrLines());
        return child.exitValue();
    }

    List<String> stderrLines() throws IOException {
        return stderr == null || Files.notExists(stderr) ? List.of() : Files.readAllLines(stderr, StandardCharsets.UTF_8);
    }

    /** Whether the child's main thread has returned: its thread dump lists DestroyJavaVM and no thread named main. */
    boolean mainReturned() {
        try {
            Process jcmd = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(), String.valueOf(child.pid()), "Thread.print")
                    .redirectErrorStream(true).start();
            String dump = new String(jcmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!jcmd.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS)) {
                jcmd.destroyForcibly();
                return fail("jcmd did not answer within " + BOUND);
            }
            List<String> threads = dump.lines().filter(l -> l.startsWith("\"")).toList();
            return threads.stream().noneMatch(l -> l.startsWith("\"main\" ")) && threads.stream().anyMatch(l -> l.startsWith("\"DestroyJavaVM\" "));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted while asking jcmd");
        }
    }

    // --- the outside world

    /** The status of {@code GET /health} on the loopback {@code port}, or -1 when nothing answered (refused, or cut off). */
    static int health(int port) {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException refusedOrCut) {   // nothing listens, or the child died while it answered: no status
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted while asking /health");
        }
    }

    /** Whether a connection to the loopback {@code port} is refused (nothing listens there). */
    static boolean refuses(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port), 5_000);
            return false;
        } catch (ConnectException refused) {
            return true;
        } catch (SocketException reset) {
            return false;
        } catch (IOException other) {
            throw new UncheckedIOException(other);
        }
    }

    /** A loopback port nothing listens on (bound once, released). */
    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    /** A loopback port nothing listens on, for a server the child must not reach. */
    static int closedPort() throws IOException {
        return freePort();
    }

    /** Backends connected to the case database. */
    long connections() {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             PreparedStatement count = c.prepareStatement("select count(*) from pg_stat_activity where datname = ?")) {
            count.setString(1, database);
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The server log's {@code unexpected EOF on client connection} lines of the case database, read once a sentinel this
     * method logs from the case database has reached the container log: an EOF logged before the sentinel is in the log by
     * then, as every backend writes to the one stderr the container log reads in order.
     */
    List<String> unexpectedEofs() throws SQLException {
        String sentinel = "sentinel-" + database + "-" + System.nanoTime();
        try (Connection c = DriverManager.getConnection(caseUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute("DO $$ BEGIN RAISE LOG '" + sentinel + "'; END $$");
        }
        until(() -> pg.getLogs().contains(sentinel), "the sentinel reached the Postgres log");
        String prefix = "[db=" + database + "] ";
        return pg.getLogs().lines().filter(l -> l.contains(prefix) && l.contains("unexpected EOF on client connection")).toList();
    }

    /** The case database's JDBC URL: the container URL with its database path segment swapped (FIX-5). */
    String caseUrl() {
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String suffix = "/" + pg.getDatabaseName();
        assertTrue(path.endsWith(suffix), "the container URL ends with its database: " + base);
        return path.substring(0, path.length() - suffix.length()) + "/" + database + (query < 0 ? "" : base.substring(query));
    }

    /** Waits until the child serves {@code /health} 200 on {@code port}; fails at once, with its stderr, when the child exits first. */
    void untilServing(int port) {
        until(() -> {
            if (!child.isAlive()) {
                try {
                    fail("the child exited with " + child.exitValue() + " before it served: " + stderrLines());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return health(port) == 200;
        }, "the child serves /health 200 on its admin port " + port);
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after {@link #BOUND} naming what never happened. */
    void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                try {
                    fail("not within " + BOUND + ": " + what + (child == null ? "" : "; child alive " + child.isAlive() + ", stderr " + stderrLines()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
