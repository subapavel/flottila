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
import io.flottila.core.record.Meter;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.blob.PgBodies;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.deploy.ArchiveStore;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.DirArchiveStore;
import io.flottila.runtime.deploy.PgDeploymentStore;
import io.flottila.runtime.deploy.S3ArchiveStore;
import io.flottila.runtime.lease.InMemoryPollerLeases;
import io.flottila.runtime.lease.PollerLeases;
import io.flottila.runtime.lease.ValkeyPollerLeases;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgIntake;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.store.Retention;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.redis.client.Redis;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A running node (D-057 b amended): the Postgres stores, the blob store, the components and the admin API on its caller's
 * Vert.x. {@link #start} and {@link #close} block on Vert.x futures: call them on a virtual thread.
 *
 * <p>{@code start} clears {@code <dataDir>/spool} (and nothing else under the data directory), builds the pool (no connection
 * yet), the blob store over the spool with a fresh key and over the pool's {@code bodies} tier bounded by
 * {@code FLOTTILA_BLOB_DURABLE_MAX} (D-058), the timer, ids and clock, builds the components from them, refuses two components
 * for one scheme (in every environment) and, in a deployed environment, a mock binding — the scheme {@code mock}, or a component
 * class in {@code io.flottila.components.mock..} or {@code io.flottila.testkit..} — and, in a deployed environment, the dir archive
 * store and an S3 endpoint over plain http (D-070) before it touches Postgres. It then refuses a
 * {@code synchronous_commit} other than {@code on}, {@code remote_write} or {@code remote_apply} (D-061) before it migrates, so a
 * refused node leaves its database untouched, migrates the database, opens the stores and the archive store (an s3 store over
 * one S3 client the node builds) and the poller leases (D-083: in the Valkey of {@code FLOTTILA_VALKEY_URI} over one client the
 * node builds; without it in the node's memory, with one WARNING), builds the {@link Deployments} (D-059) over them, restores
 * what the pool's records name ({@link Deployments#restore}, D-070: rows that cannot be read or a data directory that cannot be
 * written fail the start; a package or a flow that cannot be restored is listed FAILED and the node starts), builds the
 * {@link Retention} job over the pool, the clock and the timer ({@code FLOTTILA_RETENTION_*}, D-088) and only then starts the admin
 * server with the deploy, work, replay and retry routes and the health checks {@code postgres} and {@code deployments} (DOWN while
 * anything is listed FAILED, {@link Deployments#health}), then the retention job, whose first run begins at once and does not hold
 * the start; when any of this fails, what was opened closes again — the deployments first (the
 * restored flows stop while the pool is open; a stop that fails is added to the start's failure as suppressed), then the store,
 * the pool, the S3 client, the Valkey client — so nothing is left running, listening or pooled. {@code close} first closes the
 * deployments — no deploy is taken any more and every deployed flow stops within {@code FLOTTILA_STOP_DEADLINE}, its lease
 * released, while the admin port still answers — then stops the admin server, then closes the retention job (no next run; the
 * batch in flight commits or rolls back first), then flushes and closes the message store, then closes the pool, then the S3
 * client, then the Valkey client, each step even when the one before failed; Vert.x stays its caller's.
 *
 * @since 0.1
 */
public final class Node implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Node.class.getName());

    /** Where a mock lives, whatever scheme it is bound to (the testkit's RecordingComponent included); names, never classes (F-4). */
    private static final List<String> MOCK_PACKAGES = List.of("io.flottila.components.mock", "io.flottila.testkit");

    /**
     * The {@code synchronous_commit} values a node accepts (D-061). {@code off} does not wait for the WAL flush; {@code local}
     * waits for the local flush only and is refused because it does not wait for a standby.
     */
    private static final Set<String> SYNCHRONOUS_COMMIT = Set.of("on", "remote_write", "remote_apply");

    private final Deployments deployments;
    private final AdminServer admin;
    private final int adminPort;
    private final Retention retention;
    private final PgMessageStore messages;
    private final Pool pool;
    /** Closes the S3 client of an s3 archive store; nothing for a dir store. */
    private final Runnable closeS3;
    /** Closes the Valkey client of the poller leases; nothing when they are held in memory. */
    private final Runnable closeValkey;

    private Node(Deployments deployments, AdminServer admin, int adminPort, Retention retention, PgMessageStore messages, Pool pool,
                 Runnable closeS3, Runnable closeValkey) {
        this.deployments = deployments;
        this.admin = admin;
        this.adminPort = adminPort;
        this.retention = retention;
        this.messages = messages;
        this.pool = pool;
        this.closeS3 = closeS3;
        this.closeValkey = closeValkey;
    }

    /**
     * Starts a node on {@code vertx}; see the class comment for the order.
     *
     * @param components builds the node's components, once, from the node's own context
     * @throws IllegalStateException two components for one scheme, a deployed environment and a mock binding among the components,
     *                               the dir archive store or an S3 endpoint over plain http, or a {@code synchronous_commit} other than on, remote_write or remote_apply ({@code off} does
     *                               not wait for the WAL flush, {@code local} for a synchronous standby)
     * @throws IOException the spool a previous run left cannot be cleared, or the restore's data directory cannot be written
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
        NodeConfig.Pg pg = config.pg();
        // building the pool connects nowhere (connections open on the first query), so the refusals below still come before
        // anything touches Postgres; from here on every failed start closes it again
        Pool pool = PgBuilder.pool()
                .connectingTo(new PgConnectOptions().setHost(pg.host()).setPort(pg.port()).setDatabase(pg.database()).setUser(pg.user()).setPassword(pg.password()))
                .with(new PoolOptions().setMaxSize(pg.poolSize())).using(vertx).build();
        PgMessageStore messages = null;
        Runnable closeS3 = () -> {};   // the S3 client once built: closed after the pool
        Runnable closeValkey = () -> {};   // the Valkey client once built: closed last
        Deployments deployments = null;   // once built: closed first, its flows stopping while the pool is open
        try {
            TieredBlobStore blobs = new TieredBlobStore(new TieredBlobStore.Config(tiers.treeLimit(), tiers.memoryTier(), tiers.memoryBudget(),
                    spool, spoolKey(), null, false), hasher, new PgBodies(pool, tiers.durableMax()));
            List<Component> built = List.copyOf(components.apply(new ComponentContext(vertx, blobs, ids, timer, clock)));
            Map<String, Component> byScheme = byScheme(built);
            if (config.deployed()) {
                refuseMocks(config.env(), built);
                refuseArchiveStore(config);
            }

            requireSynchronousCommit(pool);   // before the migration: a refused node leaves its database untouched
            Migrations.migrate("jdbc:postgresql://" + pg.host() + ":" + pg.port() + "/" + pg.database(), pg.user(), pg.password());
            Runtime runtime = new Runtime(clock, timer, ids, blobs, hasher, byScheme);
            messages = new PgMessageStore(pool, timer, BatchingWriter.Options.defaults());
            PgDlq dlq = new PgDlq(pool);
            PgWorkQueue work = new PgWorkQueue(pool);
            ArchiveStore archives = new DirArchiveStore(config.archives().dir());
            if (config.s3().isPresent()) {
                NodeConfig.S3 settings = config.s3().get();
                S3Client s3 = s3Client(settings);
                closeS3 = s3::close;
                archives = new S3ArchiveStore(s3, settings.bucket(), settings.prefix());
            }
            PollerLeases leases;
            if (config.leases().valkey().isPresent()) {
                Redis valkey = Redis.createClient(vertx, config.leases().valkey().get().toString());   // connects on its first command
                closeValkey = () -> valkey.close().await();
                leases = new ValkeyPollerLeases(valkey);
            } else {
                LOG.log(System.Logger.Level.WARNING, "FLOTTILA_VALKEY_URI is not set: this node holds its poller leases in its own memory. "
                        + "Alone that is enough; two such nodes on one polling endpoint both poll it (duplicates, never a loss): give a pool of "
                        + "nodes one Valkey (D-083)");
                leases = new InMemoryPollerLeases(clock);
            }
            // an at-least-once ingress saves its body, its record and its item in one transaction (D-082)
            Deployments restored = new Deployments(config, runtime, byScheme, clock, timer, stepFactories, expressions, messages, dlq, work,
                    Meter.system(), new PgDeploymentStore(pool), archives, new PgIntake(pool, blobs, messages, work), leases);
            deployments = restored;
            restored.restore();   // before the admin port opens: what the pool recorded runs before a deploy can be taken (D-070)
            Retention retention = new Retention(pool, clock, timer, config.retention());
            AdminServer admin = new AdminServer(config.admin(), messages, dlq, blobs,
                    new AdminServer.HealthProbe(Map.of("postgres", () -> pool.query("select 1").execute().map(Health.up()),
                            "deployments", () -> Future.succeededFuture(restored.health())), messages::failed),
                    Optional.of(restored), work, ids);
            int port = admin.start(vertx).await();
            retention.start();   // its first run on a virtual thread of its own: the start does not wait for it
            return new Node(restored, admin, port, retention, messages, pool, closeS3, closeValkey);
        } catch (Throwable failure) {
            // refused, the records unreadable, or nothing listens (a port in use, an address not bound; a checked failure arrives
            // sneakily): close what was opened for it — the deployments first, so the restored flows stop while the pool is open —
            // and throw the start's failure, a failed stop added to it
            try {
                if (deployments != null) deployments.close();
            } catch (Throwable unstopped) {
                failure.addSuppressed(unstopped);
            } finally {
                closeStores(messages, pool, closeS3, closeValkey);
            }
            throw failure;
        }
    }

    /**
     * The one S3 client of a node with an s3 archive store, as the S3 tier's tests build theirs: the JDK's HttpURLConnection, the
     * region, path-style when asked, the endpoint when one is given (else the SDK's for the region) and static keys when both are
     * given (else the SDK's default credentials chain). Building it contacts nothing.
     */
    static S3Client s3Client(NodeConfig.S3 s3) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(s3.region())).forcePathStyle(s3.pathStyle())
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        s3.endpoint().ifPresent(builder::endpointOverride);
        s3.accessKey().map(key -> StaticCredentialsProvider.create(AwsBasicCredentials.create(key, s3.secretKey().orElseThrow())))
                .ifPresent(keys -> builder.credentialsProvider(keys));
        return builder.build();
    }

    /**
     * D-070: a deployed node stores package archives where every node of the pool restores them from — S3, never its own disk —
     * and reaches S3 over https only.
     */
    private static void refuseArchiveStore(NodeConfig config) {
        if (config.archives().store() == NodeConfig.Archives.Store.DIR) {
            throw new IllegalStateException("the deployed environment " + config.env() + " refuses FLOTTILA_ARCHIVE_STORE dir: a deployed node stores "
                    + "package archives in S3, where every node of the pool restores them from (D-070)");
        }
        Optional<URI> plain = config.s3().flatMap(NodeConfig.S3::endpoint).filter(endpoint -> "http".equals(endpoint.getScheme()));
        if (plain.isPresent()) {
            throw new IllegalStateException("the deployed environment " + config.env() + " refuses the FLOTTILA_S3_ENDPOINT " + plain.get()
                    + ": a deployed node reaches S3 over https only (D-070)");
        }
    }

    /**
     * D-061: an ACK promises a commit that survives a crash, so the node's sessions must commit with {@code synchronous_commit}
     * {@code on}, {@code remote_write} or {@code remote_apply}. {@code off} does not wait for the WAL flush; {@code local} waits
     * for the local flush, and is refused because it does not wait for a standby.
     */
    private static void requireSynchronousCommit(Pool pool) {
        String value = pool.query("show synchronous_commit").execute().await().iterator().next().getString(0);
        if (!SYNCHRONOUS_COMMIT.contains(value)) {
            throw new IllegalStateException("Postgres synchronous_commit is " + value + " for the node's sessions; it must be on, remote_write or"
                    + " remote_apply (D-061): off acknowledges a commit before its WAL flush, local does not wait for a synchronous standby");
        }
    }

    /** The port the admin server bound: the actual one when {@code FLOTTILA_ADMIN_PORT} is 0. */
    public int adminPort() { return adminPort; }

    /** The node's message store over Postgres. */
    public MessageStore messages() { return messages; }

    /**
     * Closes the deployments (every flow stopped, its lease released, deploys refused), then stops the admin server, then closes the
     * retention job (waits for its batch in flight), then flushes and closes the message store, then closes the pool, then the S3
     * client, then the Valkey client — each step even when the one before failed.
     */
    @Override
    public void close() {
        try {
            deployments.close();
        } finally {
            try {
                admin.stop().await();
            } finally {
                try {
                    retention.close().await();
                } finally {
                    closeStores(messages, pool, closeS3, closeValkey);
                }
            }
        }
    }

    /**
     * Flushes and closes the store (none yet: nothing to flush), then closes the pool, then the S3 client (none: nothing), then the
     * Valkey client (none: nothing) — each even when the one before failed.
     */
    private static void closeStores(PgMessageStore messages, Pool pool, Runnable closeS3, Runnable closeValkey) {
        try {
            if (messages != null) messages.close();
        } finally {
            try {
                pool.close().await();
            } finally {
                try {
                    closeS3.run();
                } finally {
                    closeValkey.run();
                }
            }
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
