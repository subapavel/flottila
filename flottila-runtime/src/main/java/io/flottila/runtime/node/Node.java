package io.flottila.runtime.node;

import io.flottila.api.Component;
import io.flottila.api.Expressions;
import io.flottila.api.Hasher;
import io.flottila.api.Health;
import io.flottila.api.IdGenerator;
import io.flottila.api.Runtime;
import io.flottila.api.StepFactory;
import io.flottila.api.Timer;
import io.flottila.core.admission.Targets;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.StepCpu;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.deploy.ArchiveStore;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.DirArchiveStore;
import io.flottila.runtime.deploy.PgDeploymentStore;
import io.flottila.runtime.deploy.S3ArchiveStore;
import io.flottila.runtime.journal.Journal;
import io.flottila.runtime.journal.JournalFiles;
import io.flottila.runtime.journal.JournalKeys;
import io.flottila.runtime.journal.PgSegmentRows;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * A running node (D-057 b amended): the Postgres stores, the blob store, the components and the admin API on its caller's
 * Vert.x. {@link #start} and {@link #close} block on Vert.x futures: call them on a virtual thread.
 *
 * <p>{@code start} builds the pool (no connection yet), the timer, ids and clock, builds the components from them and from the
 * node's blob store (bound once the journal is open, see below), refuses two components
 * for one scheme (in every environment) and, in a deployed environment, a mock binding — the scheme {@code mock}, or a component
 * class in {@code io.flottila.components.mock..} or {@code io.flottila.testkit..} — and, in a deployed environment, the dir archive
 * store and an S3 endpoint over plain http (D-070) before it touches Postgres, and loads the body journal's master key (D-106:
 * from {@code FLOTTILA_JOURNAL_KEY_FILE}, {@code FLOTTILA_JOURNAL_KEY} or the opted-in generated one; none refuses the start, so a
 * keyless node migrates nothing). It then refuses a
 * {@code synchronous_commit} other than {@code on}, {@code remote_write} or {@code remote_apply} (D-061) before it migrates, so a
 * refused node leaves its database untouched, migrates the database, opens the body journal in {@code FLOTTILA_JOURNAL_DIR} (D-105:
 * every {@code journal_keys} row unwrapped under the master key, then its {@code LOCK}, its identity and its segment rows), builds
 * the blob store on it (CORE-4c2: bodies in the journal under the {@code default} tenant's key, their refs in the pool, bounded by
 * {@code FLOTTILA_BLOB_DURABLE_MAX}; no spool), opens the stores (the work queue claiming only what this journal holds) and the
 * archive store (an s3 store over
 * one S3 client the node builds) and the poller leases (D-083: in the Valkey of {@code FLOTTILA_VALKEY_URI} over one client the
 * node builds; without it in the node's memory, with one WARNING), builds the {@link Deployments} (D-059) over them and the node's
 * one {@link Targets} ({@code FLOTTILA_TARGET_*}, D-099: every flow it builds sends under it, across packages and versions), starts the
 * {@link CpuSampler} when {@code FLOTTILA_CPU_SAMPLING} sets a period (D-094), restores
 * what the pool's records name ({@link Deployments#restore}, D-070: rows that cannot be read or a data directory that cannot be
 * written fail the start; a package or a flow that cannot be restored is listed FAILED and the node starts), builds the
 * {@link Retention} job over the pool, the clock and the timer ({@code FLOTTILA_RETENTION_*}, D-088) and only then starts the admin
 * server with the deploy, work, replay and retry routes, the flows' CPU (the sampler's aggregate, read every 5 s on the node's timer and failed once a read failed, or off) and the health checks
 * {@code postgres}, {@code deployments} (DOWN while
 * anything is listed FAILED, {@link Deployments#health}) and {@code journal} ({@link Journal#health}: DOWN once it failed and
 * stopped, DEGRADED while a segment row is retried), then the
 * retention job, whose first run begins at once and does not hold
 * the start; when any of this fails, what was opened closes again — the deployments first (the
 * restored flows stop while the pool is open; a stop that fails is added to the start's failure as suppressed), then the sampler, then the
 * journal, the store, the pool, the S3 client, the Valkey client — so nothing is left running, listening or pooled. {@code close} first
 * closes the deployments — no deploy is taken any more and every deployed flow stops within {@code FLOTTILA_STOP_DEADLINE}, its lease
 * released, while the admin port still answers — then closes the sampler (if one runs), then stops the admin server, then closes the retention job (no next run; the
 * batch in flight commits or rolls back first), then closes the journal (its spills and persists in flight first; its {@code LOCK}
 * given up), then flushes and closes the message store, then closes the pool, then the S3
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

    /** The tenant whose journal key seals the node's bodies until tenants are configured (D-105). */
    private static final String TENANT = "default";

    private final Deployments deployments;
    /** The CPU sampler (D-094), when {@code FLOTTILA_CPU_SAMPLING} sets a period. */
    private final Optional<CpuSampler> sampler;
    private final AdminServer admin;
    private final int adminPort;
    private final Retention retention;
    private final Journal journal;
    private final PgMessageStore messages;
    private final Pool pool;
    /** Closes the S3 client of an s3 archive store; nothing for a dir store. */
    private final Runnable closeS3;
    /** Closes the Valkey client of the poller leases; nothing when they are held in memory. */
    private final Runnable closeValkey;

    private Node(Deployments deployments, Optional<CpuSampler> sampler, AdminServer admin, int adminPort, Retention retention, Journal journal,
                 PgMessageStore messages, Pool pool, Runnable closeS3, Runnable closeValkey) {
        this.deployments = deployments;
        this.sampler = sampler;
        this.admin = admin;
        this.adminPort = adminPort;
        this.retention = retention;
        this.journal = journal;
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
     * @throws IllegalArgumentException no journal master key, or one that is no base64 of 256 bits
     * @throws IOException the restore's data directory cannot be written, a
     *                     {@code journal_keys} row cannot be unwrapped (named by its key id), or the journal cannot be opened
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
        NodeConfig.Pg pg = config.pg();
        // building the pool connects nowhere (connections open on the first query), so the refusals below still come before
        // anything touches Postgres; from here on every failed start closes it again
        Pool pool = PgBuilder.pool()
                .connectingTo(new PgConnectOptions().setHost(pg.host()).setPort(pg.port()).setDatabase(pg.database()).setUser(pg.user()).setPassword(pg.password()))
                .with(new PoolOptions().setMaxSize(pg.poolSize())).using(vertx).build();
        PgMessageStore messages = null;
        Journal journal = null;   // once open: closed after the deployments, before the store and the pool its rows go through
        Runnable closeS3 = () -> {};   // the S3 client once built: closed after the pool
        Runnable closeValkey = () -> {};   // the Valkey client once built: closed last
        Deployments deployments = null;   // once built: closed first, its flows stopping while the pool is open
        Optional<CpuSampler> sampler = Optional.empty();   // once started: closed after the deployments
        try {
            LateBlobStore late = new LateBlobStore(tiers.treeLimit());   // the node's store once the journal it writes to is open
            List<Component> built = List.copyOf(components.apply(new ComponentContext(vertx, late, ids, timer, clock)));
            Map<String, Component> byScheme = byScheme(built);
            if (config.deployed()) {
                refuseMocks(config.env(), built);
                refuseArchiveStore(config);
            }

            // the master key before any Postgres statement: a node without one migrates nothing (D-106)
            SecretKey master = config.journalKey().load(config.journal().dir(), JournalFiles.real());
            requireSynchronousCommit(pool);   // before the migration: a refused node leaves its database untouched
            Migrations.migrate("jdbc:postgresql://" + pg.host() + ":" + pg.port() + "/" + pg.database(), pg.user(), pg.password());
            JournalKeys keys = JournalKeys.load(pool, master);
            Journal opened = Journal.open(config.journal(), clock, timer, keys, JournalFiles.real(), new PgSegmentRows(pool), UUID::randomUUID);
            journal = opened;
            TieredBlobStore blobs = new TieredBlobStore(new TieredBlobStore.Config(tiers.treeLimit(), tiers.memoryTier(), tiers.memoryBudget(),
                    tiers.durableMax()), hasher, opened, keys.keyOf(TENANT), pool);
            late.bind(blobs);   // before any flow is built: every body a component writes is this store's
            Runtime runtime = new Runtime(clock, timer, ids, blobs, hasher, byScheme);
            messages = new PgMessageStore(pool, timer, BatchingWriter.Options.defaults());
            PgDlq dlq = new PgDlq(pool);
            PgWorkQueue work = new PgWorkQueue(pool, opened.node(), blobs);   // claims what this journal holds, its claims indexed
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
                    Meter.system(), new PgDeploymentStore(pool), archives, new PgIntake(pool, blobs, messages, work), leases,
                    new Targets(config.targets().defaults(), config.targets().overrides(), timer));   // the node's one, for every flow (D-099)
            deployments = restored;
            StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
            // before the restore: the restored flows' steps are sampled from their first message on (D-094)
            sampler = config.cpuSampling().map(period -> CpuSampler.start(period, cpu, clock, timer, CpuSampler.READ_EVERY));
            restored.restore();   // before the admin port opens: what the pool recorded runs before a deploy can be taken (D-070)
            Retention retention = new Retention(pool, clock, timer, config.retention());
            AdminServer admin = new AdminServer(config.admin(), messages, dlq, blobs,
                    new AdminServer.HealthProbe(Map.of("postgres", () -> pool.query("select 1").execute().map(Health.up()),
                            "deployments", () -> Future.succeededFuture(restored.health()),
                            "journal", () -> Future.succeededFuture(opened.health())), messages::failed),
                    Optional.of(restored), work, ids, sampler.map(s -> new AdminServer.Sampling(s.period(), s.since(), cpu, s::failed)));
            int port = admin.start(vertx).await();
            retention.start();   // its first run on a virtual thread of its own: the start does not wait for it
            return new Node(restored, sampler, admin, port, retention, opened, messages, pool, closeS3, closeValkey);
        } catch (Throwable failure) {
            // refused, the records unreadable, or nothing listens (a port in use, an address not bound; a checked failure arrives
            // sneakily): close what was opened for it — the deployments first, so the restored flows stop while the pool is open —
            // and throw the start's failure, a failed stop added to it
            try {
                if (deployments != null) deployments.close();
            } catch (Throwable unstopped) {
                failure.addSuppressed(unstopped);
            } finally {
                try {
                    sampler.ifPresent(CpuSampler::close);
                } finally {
                    closeStores(journal, messages, pool, closeS3, closeValkey);
                }
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

    /** The node's body journal (CORE-4c2's store writes through it). */
    public Journal journal() { return journal; }

    /**
     * Closes the deployments (every flow stopped, its lease released, deploys refused), then the CPU sampler (if one runs), then stops the admin server, then closes the
     * retention job (waits for its batch in flight), then closes the journal, then flushes and closes the message store, then closes
     * the pool, then the S3 client, then the Valkey client — each step even when the one before failed.
     */
    @Override
    public void close() {
        try {
            deployments.close();
        } finally {
            try {
                sampler.ifPresent(CpuSampler::close);   // after the deployments; it feeds nothing more, their last one to two read cycles unread (F-22)
            } finally {
                try {
                    admin.stop().await();
                } finally {
                    try {
                        retention.close().await();
                    } finally {
                        closeStores(journal, messages, pool, closeS3, closeValkey);
                    }
                }
            }
        }
    }

    /**
     * Closes the journal (none yet: nothing to close; a failed close is thrown unchecked once the rest is closed, unless a later
     * close throws too: that one is thrown, as between the others), then
     * flushes and closes the store (none yet: nothing to flush), then closes the pool, then the S3 client (none: nothing), then the
     * Valkey client (none: nothing) — each even when the one before failed.
     */
    private static void closeStores(Journal journal, PgMessageStore messages, Pool pool, Runnable closeS3, Runnable closeValkey) {
        try {
            if (journal != null) journal.close();
        } catch (IOException e) {
            throw new UncheckedIOException("the journal did not close", e);
        } finally {
            closeStores(messages, pool, closeS3, closeValkey);
        }
    }

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
}
