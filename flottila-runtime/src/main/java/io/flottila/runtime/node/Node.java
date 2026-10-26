package io.flottila.runtime.node;

import io.flottila.api.Component;
import io.flottila.api.Expressions;
import io.flottila.api.Hasher;
import io.flottila.api.Health;
import io.flottila.api.IdGenerator;
import io.flottila.api.Runtime;
import io.flottila.api.StepFactory;
import io.flottila.api.Timer;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.record.MessageStore;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A running node (D-057 b amended): the Postgres stores, the blob store, the components and the admin API on its caller's
 * Vert.x. {@link #start} and {@link #close} block on Vert.x futures: call them on a virtual thread.
 *
 * <p>{@code start} clears {@code <dataDir>/spool} (and nothing else under the data directory), builds the blob store over it
 * with a fresh key, the timer, ids and
 * clock, builds the components from them, refuses two components for one scheme (in every environment) and, in a deployed
 * environment, a mock binding — the scheme {@code mock}, or a component class in {@code io.flottila.components.mock..} or
 * {@code io.flottila.testkit..} — before it touches Postgres. It then migrates the database, opens the pool and the stores and starts the admin server; when the admin server
 * does not start, the store and the pool close again, so nothing is left listening or pooled. {@code close} stops the admin
 * server, then flushes and closes the message store, then closes the pool; Vert.x stays its caller's.
 *
 * @since 0.1
 */
public final class Node implements AutoCloseable {

    /** Where a mock lives, whatever scheme it is bound to (the testkit's RecordingComponent included); names, never classes (F-4). */
    private static final List<String> MOCK_PACKAGES = List.of("io.flottila.components.mock", "io.flottila.testkit");

    private final AdminServer admin;
    private final int adminPort;
    private final PgMessageStore messages;
    private final Pool pool;
    // What the deploy hands to FlowBuilder (P1-09d); no stage captures the Runtime.
    private final Runtime runtime;
    private final List<StepFactory> steps;
    private final List<Expressions> languages;

    private Node(AdminServer admin, int adminPort, PgMessageStore messages, Pool pool, Runtime runtime, List<StepFactory> steps,
                 List<Expressions> languages) {
        this.admin = admin;
        this.adminPort = adminPort;
        this.messages = messages;
        this.pool = pool;
        this.runtime = runtime;
        this.steps = steps;
        this.languages = languages;
    }

    /**
     * Starts a node on {@code vertx}; see the class comment for the order.
     *
     * @param components builds the node's components, once, from the node's own context
     * @throws IllegalStateException two components for one scheme, or a deployed environment and a mock binding among the components
     * @throws IOException the spool a previous run left cannot be cleared
     */
    public static Node start(NodeConfig config, Vertx vertx, Function<ComponentContext, List<Component>> components, List<StepFactory> steps,
                             List<Expressions> languages) throws IOException {
        List<StepFactory> stepFactories = List.copyOf(steps);
        List<Expressions> expressions = List.copyOf(languages);
        Hasher hasher = Hasher.sha256();
        Timer timer = new VertxTimer(vertx);
        IdGenerator ids = () -> UUID.randomUUID().toString();
        Clock clock = Clock.systemUTC();
        NodeConfig.Blobs tiers = config.blobs();
        Path spool = config.dataDir().resolve("spool");
        clearSpool(spool);
        TieredBlobStore blobs = new TieredBlobStore(new TieredBlobStore.Config(tiers.treeLimit(), tiers.memoryTier(), tiers.memoryBudget(),
                spool, spoolKey(), null, false), hasher);
        List<Component> built = List.copyOf(components.apply(new ComponentContext(vertx, blobs, ids, timer, clock)));
        Map<String, Component> byScheme = byScheme(built);
        if (config.deployed()) refuseMocks(config.env(), built);

        NodeConfig.Pg pg = config.pg();
        Migrations.migrate("jdbc:postgresql://" + pg.host() + ":" + pg.port() + "/" + pg.database(), pg.user(), pg.password());
        Runtime runtime = new Runtime(clock, timer, ids, blobs, hasher, byScheme);
        Pool pool = PgBuilder.pool()
                .connectingTo(new PgConnectOptions().setHost(pg.host()).setPort(pg.port()).setDatabase(pg.database()).setUser(pg.user()).setPassword(pg.password()))
                .with(new PoolOptions().setMaxSize(pg.poolSize())).using(vertx).build();
        PgMessageStore messages = new PgMessageStore(pool, timer, BatchingWriter.Options.defaults());
        AdminServer admin = new AdminServer(config.admin(), messages, new PgDlq(pool), blobs,
                new AdminServer.HealthProbe(Map.of("postgres", () -> pool.query("select 1").execute().map(Health.up())), messages::failed));
        boolean started = false;
        try {
            Node node = new Node(admin, admin.start(vertx).await(), messages, pool, runtime, stepFactories, expressions);
            started = true;
            return node;
        } finally {
            // nothing listens (a port in use, an address not bound; a checked failure arrives sneakily): close what was opened for it
            if (!started) closeStores(messages, pool);
        }
    }

    /** The port the admin server bound: the actual one when {@code FLOTTILA_ADMIN_PORT} is 0. */
    public int adminPort() { return adminPort; }

    /** The node's message store over Postgres. */
    public MessageStore messages() { return messages; }

    /** Stops the admin server, then flushes and closes the message store, then closes the pool — each step even when the one before failed. */
    @Override
    public void close() {
        try {
            admin.stop().await();
        } finally {
            closeStores(messages, pool);
        }
    }

    /** Flushes and closes the store, then closes the pool — even when the flush failed. */
    private static void closeStores(PgMessageStore messages, Pool pool) {
        try {
            messages.close();
        } finally {
            pool.close().await();
        }
    }

    private static void refuseMocks(String env, List<Component> components) {
        for (Component c : components) {
            String home = c.getClass().getPackageName();
            if ("mock".equals(c.scheme()) || MOCK_PACKAGES.stream().anyMatch(p -> home.equals(p) || home.startsWith(p + "."))) {
                throw new IllegalStateException("the deployed environment " + env + " refuses the mock binding " + c.getClass().getName()
                        + " (scheme " + c.scheme() + "): mocks run in dev and ci only (ARCHITECTURE §11)");
            }
        }
    }

    /** The components by scheme; two for one scheme are refused, in every environment — list order never picks one. */
    private static Map<String, Component> byScheme(List<Component> components) {
        Map<String, Component> byScheme = new HashMap<>();
        for (Component c : components) {
            Component other = byScheme.putIfAbsent(c.scheme(), c);
            if (other != null) {
                throw new IllegalStateException("two components for the scheme " + c.scheme() + ": " + other.getClass().getName() + " and "
                        + c.getClass().getName() + "; a scheme has one component");
            }
        }
        return byScheme;
    }

    /**
     * Deletes {@code spool} with everything in it: what a previous run spooled is sealed with that run's key, which is gone, and the
     * store's index of it did not survive the restart either. The store creates the directory again when it first spills.
     */
    private static void clearSpool(Path spool) throws IOException {
        if (Files.notExists(spool, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> left = Files.walk(spool)) {
            for (Path p : left.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** A fresh AES-256 key per start: nothing a run spools can be read by the next one, which clears the spool before it opens the store. */
    private static SecretKey spoolKey() {
        byte[] material = new byte[32];
        new SecureRandom().nextBytes(material);
        return new SecretKeySpec(material, "AES");
    }
}
