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
import io.flottila.core.flow.Workers;
import io.flottila.core.node.NodeLease;
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
import io.flottila.runtime.journal.SegmentSweeper;
import io.flottila.runtime.lease.InMemoryPollerLeases;
import io.flottila.runtime.lease.PollerLeases;
import io.flottila.runtime.lease.ValkeyPollerLeases;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.queue.PgWorkSignals;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgIntake;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.store.Retention;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.PemTrustOptions;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * keyless node migrates nothing). It then checks its durability profile ({@code FLOTTILA_PG_DURABILITY}, D-127 draft, D-061
 * amended: {@link PgDurability}) in one read before it migrates, so a refused node leaves its database untouched, migrates the
 * database, opens the body journal in {@code FLOTTILA_JOURNAL_DIR} (D-105:
 * every {@code journal_keys} row unwrapped under the master key, then its {@code LOCK}, its identity and its segment rows), builds
 * the blob store on it (CORE-4c2: bodies in the journal under the {@code default} tenant's key, their refs in the pool, bounded by
 * {@code FLOTTILA_BLOB_DURABLE_MAX}; no spool), joins the membership as a new incarnation of its node id (D-101 (1), F-28: a
 * {@link NodeLease} over a {@link PgMembership} on a connection of its own, renewed every {@code FLOTTILA_NODE_LEASE_RENEW}; its
 * fence fences every at-least-once worker the deployments build; superseded, the node halts — the process exits with
 * {@value #SUPERSEDED}) and states its notices once — the profile's, one journal copy (D-101 (5), until CORE-5) and, when no
 * other member is live, that nobody takes its items over —, opens the stores (the work queue claiming only what this journal holds) and the
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
 * the start, then the journal's {@link SegmentSweeper} ({@code FLOTTILA_JOURNAL_SWEEP_*}, its first pass an interval later); when any of this fails, what was opened closes again — the deployments first (the
 * restored flows stop while the pool is open; a stop that fails is added to the start's failure as suppressed), then the node lease
 * and its connection, then the sampler, then the work queue's completion batch, then the work events, then the journal, the store, the pool, the S3 client, the Valkey client — so nothing is left running, listening
 * or pooled; {@code /ready}'s lifecycle is UP only once the start returns (D-123). {@code close} first marks the node not ready
 * ({@code /ready} 503), then closes the deployments — no deploy is taken any more and every deployed flow stops within {@code FLOTTILA_STOP_DEADLINE}, its lease
 * released, while the admin port still answers — then stops renewing the node lease and closes its connection (the row expires;
 * a peer or the next start takes the items over), then closes the sampler (if one runs), then stops the admin server, then closes the retention job (no next run; the
 * batch in flight commits or rolls back first), then the sweeper (its pass in flight ends first), then the work queue's completion
 * batch (every completion handed over answered first, D-122), then the work events, then closes the journal (its spills and persists in flight first; its {@code LOCK}
 * given up), then flushes and closes the message store, then closes the pool, then the S3
 * client, then the Valkey client, each step even when the one before failed; Vert.x stays its caller's.
 *
 * @since 0.1
 */
public final class Node implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Node.class.getName());

    /** Where a mock lives, whatever scheme it is bound to (the testkit's RecordingComponent included); names, never classes (F-4). */
    private static final List<String> MOCK_PACKAGES = List.of("io.flottila.components.mock", "io.flottila.testkit");

    /** The tenant whose journal key seals the node's bodies until tenants are configured (D-105). */
    private static final String TENANT = "default";

    /** The exit status of a superseded node (D-101 (2), fail-stop): a peer released its lease, or a newer incarnation joined. */
    public static final int SUPERSEDED = 3;

    /** The single-copy notice (D-101 (5)): until CORE-5 a body lies in its node's journal only. */
    static final String ONE_COPY = "one journal copy: this node's at-least-once items wait for it while it is down (D-101 (5))";

    /** The notice of a node that found no other member live at its start. */
    static final String ALONE = "no other live member at this node's start: no other node takes its at-least-once items over while it is down (D-101)";

    private final Deployments deployments;
    /** The node's lease (D-101), renewed on its membership's own connection. */
    private final NodeLease lease;
    private final PgMembership membership;
    /** The CPU sampler (D-094), when {@code FLOTTILA_CPU_SAMPLING} sets a period. */
    private final Optional<CpuSampler> sampler;
    private final AdminServer admin;
    private final int adminPort;
    private final Retention retention;
    private final SegmentSweeper sweeper;
    private final Journal journal;
    private final PgMessageStore messages;
    private final Pool pool;
    /** Closes the S3 client of an s3 archive store; nothing for a dir store. */
    private final Runnable closeS3;
    /** Closes the Valkey client of the poller leases; nothing when they are held in memory. */
    private final Runnable closeValkey;
    private final Runnable closeSignals;
    /** Closes the work queue's completion batch (D-122): what was handed over is answered through the open pool. */
    private final Runnable closeWork;
    /** The {@code lifecycle} input of {@code /ready} (D-123): set once the start finished, cleared as the close's first act. */
    private final AtomicBoolean ready;

    private Node(Deployments deployments, NodeLease lease, PgMembership membership, Optional<CpuSampler> sampler, AdminServer admin, int adminPort,
                 Retention retention, SegmentSweeper sweeper, Journal journal, PgMessageStore messages, Pool pool, Runnable closeS3, Runnable closeValkey,
                 Runnable closeSignals, Runnable closeWork, AtomicBoolean ready) {
        this.deployments = deployments;
        this.lease = lease;
        this.membership = membership;
        this.sampler = sampler;
        this.admin = admin;
        this.adminPort = adminPort;
        this.retention = retention;
        this.sweeper = sweeper;
        this.journal = journal;
        this.messages = messages;
        this.pool = pool;
        this.closeS3 = closeS3;
        this.closeValkey = closeValkey;
        this.closeSignals = closeSignals;
        this.closeWork = closeWork;
        this.ready = ready;
    }

    /**
     * Starts a node on {@code vertx}; see the class comment for the order.
     *
     * @param components builds the node's components, once, from the node's own context
     * @throws IllegalStateException two components for one scheme, a deployed environment and a mock binding among the components,
     *                               the dir archive store or an S3 endpoint over plain http, or a Postgres the durability profile
     *                               refuses ({@link PgDurability}): under both profiles a session that does not commit with
     *                               {@code synchronous_commit=on} and a server in recovery; under {@code ha} also an empty
     *                               {@code synchronous_standby_names}, a role without the privileges of {@code pg_read_all_stats},
     *                               and no sync or quorum standby streaming
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
        PgConnectOptions connect = connectOptions(pg);   // one source (D-124): the pool, the membership and the LISTEN connect alike
        Pool pool = PgBuilder.pool()
                .connectingTo(connect)
                .with(new PoolOptions().setMaxSize(pg.poolSize())).using(vertx).build();
        PgMessageStore messages = null;
        Journal journal = null;   // once open: closed after the deployments, before the store and the pool its rows go through
        Runnable closeS3 = () -> {};   // the S3 client once built: closed after the pool
        Runnable closeValkey = () -> {};   // the Valkey client once built: closed last
        Runnable closeSignals = () -> {};   // the work events once built: closed after the deployments, before the stores and the pool
        Runnable closeWork = () -> {};   // the work queue's completion batch once built: closed after the deployments, before the signals
        Deployments deployments = null;   // once built: closed first, its flows stopping while the pool is open
        NodeLease lease = null;   // once joined: closed after the deployments, its connection with it
        PgMembership membership = null;
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
            // the durability profile before the migration: a refused node leaves its database untouched (D-127 draft)
            List<String> durability = PgDurability.check(pool, pg.durability());
            Migrations.migrate(jdbcUrl(pg), pg.user(), pg.password());
            JournalKeys keys = JournalKeys.load(pool, master);
            Journal opened = Journal.open(config.journal(), clock, timer, keys, JournalFiles.real(), new PgSegmentRows(pool), UUID::randomUUID);
            journal = opened;
            TieredBlobStore blobs = new TieredBlobStore(new TieredBlobStore.Config(tiers.treeLimit(), tiers.memoryTier(), tiers.memoryBudget(),
                    tiers.durableMax()), hasher, opened, keys.keyOf(TENANT), pool);
            late.bind(blobs);   // before any flow is built: every body a component writes is this store's
            // the node lease (D-101): after the journal's LOCK (one process per journal joins), before any flow is built (each is fenced)
            membership = PgMembership.connecting(vertx, connect);   // its own connection, never the pool's
            lease = new NodeLease(config.nodeId(), membership, config.nodeLease(), config.nodeLeaseRenew(), System::nanoTime, timer, Node::halt);
            lease.start().await();
            List<String> notices = notices(config, durability, membership.others(config.nodeId()).await());
            Runtime runtime = new Runtime(clock, timer, ids, blobs, hasher, byScheme);
            messages = new PgMessageStore(pool, timer, BatchingWriter.Options.defaults());
            PgDlq dlq = new PgDlq(pool);
            // claims what this journal holds, its claims indexed; unkeyed completions in batches (D-122)
            PgWorkQueue work = new PgWorkQueue(pool, opened.node(), blobs, timer);
            closeWork = () -> work.close().await();
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
            // the node's started workers: its intake hands each saved item off to the flow's worker, leased to this node (D-121 (a))
            Workers workers = new Workers();
            // the work events between the nodes (D-121 (b)): listening on a connection of their own before any worker starts
            PgWorkSignals signals = new PgWorkSignals(vertx, connect, pool, opened.node(), workers, timer);
            closeSignals = () -> signals.close().await();
            signals.start().await();
            PgIntake intake = new PgIntake(pool, blobs, messages, work, workers, timer, config.intakeGroup(), signals,
                    config.pg().durability());   // grouped saves (D-109); under ha a duplicate commits behind a barrier (D-128)
            Deployments restored = new Deployments(config, runtime, byScheme, clock, timer, stepFactories, expressions, messages, dlq, work,
                    Meter.system(), new PgDeploymentStore(pool), archives, intake, leases,
                    new Targets(config.targets().defaults(), config.targets().overrides(), timer), lease, workers, signals);   // the node's one Targets (D-099) and fence
            deployments = restored;
            StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
            // before the restore: the restored flows' steps are sampled from their first message on (D-094)
            sampler = config.cpuSampling().map(period -> CpuSampler.start(period, cpu, clock, timer, CpuSampler.READ_EVERY));
            restored.restore();   // before the admin port opens: what the pool recorded runs before a deploy can be taken (D-070)
            Retention retention = new Retention(pool, clock, timer, config.retention());
            AtomicBoolean ready = new AtomicBoolean();   // /ready's lifecycle: DOWN until the start finished (a failed start never sets it)
            // /ready's inputs are in-memory reads (D-123): the fence, the journal's and the deployments' health — both synchronous
            AdminServer.Readiness readiness = new AdminServer.Readiness(ready::get, lease,
                    () -> opened.health().status() == Health.Status.UP, () -> restored.health().status() == Health.Status.UP);
            AdminServer admin = new AdminServer(config.admin(), messages, dlq, blobs,
                    new AdminServer.HealthProbe(Map.of("postgres", () -> pool.query("select 1").execute().map(Health.up()),
                            "deployments", () -> Future.succeededFuture(restored.health()),
                            "journal", () -> Future.succeededFuture(opened.health())), messages::failed, notices, readiness),
                    Optional.of(restored), work, ids, sampler.map(s -> new AdminServer.Sampling(s.period(), s.since(), cpu, s::failed)), workers, signals);
            int port = admin.start(vertx).await();
            retention.start();   // its first run on a virtual thread of its own: the start does not wait for it
            // the segments no row pins go (CORE-3 item 10); its first pass an interval later, on a virtual thread of its own
            SegmentSweeper sweeper = new SegmentSweeper(opened, new PgSegmentRows(pool), intake::openSaves, config.journalSweep(), clock, timer);
            sweeper.start();
            Node node = new Node(restored, lease, membership, sampler, admin, port, retention, sweeper, opened, messages, pool, closeS3, closeValkey,
                    closeSignals, closeWork, ready);
            ready.set(true);   // the start's last act: from here /ready may answer UP
            return node;
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
                    closeLease(lease, membership);
                } finally {
                    try {
                        sampler.ifPresent(CpuSampler::close);
                    } finally {
                        try {
                            closeWork.run();   // after the flows that complete, before the pool it deletes through
                        } finally {
                            try {
                                closeSignals.run();   // before the pool it sends on
                            } finally {
                                closeStores(journal, messages, pool, closeS3, closeValkey);
                            }
                        }
                    }
                }
            }
            throw failure;
        }
    }

    /** The longest SQL text a connection caches prepared (characters): above the client's 2048, so the intake's KEEP (2514) is cached too. */
    static final int STATEMENT_CACHE_SQL_LIMIT = 8192;

    /**
     * The node's Postgres connect options (F-26): the server and credentials of {@code pg}, and the prepared-statement cache on
     * (ARCHITECTURE §4 Writer) for every SQL text up to {@value #STATEMENT_CACHE_SQL_LIMIT} characters, so a connection parses a
     * statement once; pipelining stays at the client's default (D-109: a turn's statements are sent together). Every session runs
     * with {@code jit} off, a startup parameter (FIX-130, F-27 addendum): the node's statements touch a bounded number of rows, yet
     * an estimate that grows with a table's size crossed jit_above_cost and every claim compiled its plan. Every session also
     * commits with {@code synchronous_commit=on} and runs with {@code statement_timeout=0}, startup parameters above a database's or
     * a role's own setting under both durability profiles (D-127 draft, D-061 amended): a commit waits for its WAL flush and, when
     * the server names synchronous standbys, for them, and no timeout cancels that wait (the node's save timeout bounds the
     * caller's answer instead). The link is secured as
     * {@code pg}'s {@link NodeConfig.Pg#sslMode() sslMode} says (D-124): a verifying mode trusts the PEM CA of
     * {@link NodeConfig.Pg#sslRootCert() sslRootCert} only, and {@code verify-full} checks the server's name against the host dialled.
     */
    public static PgConnectOptions connectOptions(NodeConfig.Pg pg) {
        PgConnectOptions options = new PgConnectOptions().setHost(pg.host()).setPort(pg.port()).setDatabase(pg.database()).setUser(pg.user())
                .setPassword(pg.password()).setCachePreparedStatements(true).setPreparedStatementCacheSqlLimit(STATEMENT_CACHE_SQL_LIMIT)
                .addProperty("jit", "off").addProperty("synchronous_commit", "on").addProperty("statement_timeout", "0").setSslMode(io.vertx.pgclient.SslMode.of(pg.sslMode().toString()));
        if (pg.sslMode() != NodeConfig.Pg.SslMode.DISABLE) {
            // the client refuses verify-full without an algorithm; an empty one checks no name (verify-ca)
            options.setSslOptions(new ClientSSLOptions().setTrustOptions(new PemTrustOptions().addCertPath(pg.sslRootCert().orElseThrow().toString()))
                    .setHostnameVerificationAlgorithm(pg.sslMode() == NodeConfig.Pg.SslMode.VERIFY_FULL ? "HTTPS" : ""));
        }
        return options;
    }

    /**
     * The JDBC URL Flyway migrates over (D-034), secured as {@link #connectOptions} is: {@code sslmode} always, {@code sslrootcert}
     * when a CA is given (URL-encoded: the driver decodes its parameters).
     */
    static String jdbcUrl(NodeConfig.Pg pg) {
        String url = "jdbc:postgresql://" + pg.host() + ":" + pg.port() + "/" + pg.database() + "?sslmode=" + pg.sslMode();
        return pg.sslRootCert().map(ca -> url + "&sslrootcert=" + URLEncoder.encode(ca.toString(), StandardCharsets.UTF_8)).orElse(url);
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

    /** The port the admin server bound: the actual one when {@code FLOTTILA_ADMIN_PORT} is 0. */
    public int adminPort() { return adminPort; }

    /** The node's message store over Postgres. */
    public MessageStore messages() { return messages; }

    /**
     * What the admin health names beside its checks: a generated master key, which lies beside the data it protects (D-106); the
     * {@code durability} notices of the start's Postgres check (D-127 draft); one journal copy (D-101 (5)); and, with no
     * {@code others} live at the start, that no other node takes this one's items over. All but the first are logged here, once.
     */
    private static List<String> notices(NodeConfig config, List<String> durability, int others) {
        List<String> notices = new ArrayList<>();
        if (config.journalKey().generates()) {
            notices.add("the journal's master key is generated (FLOTTILA_JOURNAL_KEY_GENERATE=true) and lies beside the data it protects: "
                    + "for a single dev node only; give every other node FLOTTILA_JOURNAL_KEY_FILE (D-106)");
        }
        for (String notice : durability) {
            notices.add(notice);
            LOG.log(System.Logger.Level.WARNING, notice);
        }
        notices.add(ONE_COPY);
        LOG.log(System.Logger.Level.WARNING, ONE_COPY);
        if (others == 0) {
            notices.add(ALONE);
            LOG.log(System.Logger.Level.WARNING, ALONE);
        }
        return notices;
    }

    /** Fail-stop (D-101 (2)): the process exits with {@link #SUPERSEDED}, off the calling thread (a renew answers on an event loop, which the shutdown hook needs). */
    private static void halt() {
        Thread.ofVirtual().name("flottila-superseded").start(() -> System.exit(SUPERSEDED));
    }

    /** Stops renewing the node lease (none yet: nothing) and closes its connection (none: nothing), the connection even when the stop threw. */
    private static void closeLease(NodeLease lease, PgMembership membership) {
        try {
            if (lease != null) lease.close();
        } finally {
            if (membership != null) membership.close().await();
        }
    }

    /** The node's body journal (CORE-4c2's store writes through it). */
    public Journal journal() { return journal; }

    /**
     * Marks the node not ready ({@code /ready} 503 from here, D-123), then closes the deployments (every flow stopped, its lease released, deploys refused), then stops the node lease and closes its connection, then the CPU sampler (if one runs), then stops the admin server, then closes the
     * retention job (waits for its batch in flight), then the segment sweeper (waits for its pass in flight), then the work queue's
     * completion batch (waits until every completion handed over is answered, D-122), then the work events
     * (their listening connection; nothing sent from then on), then closes the journal, then flushes and closes the message store, then closes
     * the pool, then the S3 client, then the Valkey client — each step even when the one before failed.
     */
    @Override
    public void close() {
        ready.set(false);   // first (D-123 R3): /ready is 503 while the listeners still drain, the admin port still answering
        try {
            deployments.close();
        } finally {
            try {
                closeLease(lease, membership);   // after the deployments: no flow is left to fence
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
                            try {
                                sweeper.close();   // waits for a pass in flight: before the journal and the pool it uses
                            } finally {
                                try {
                                    closeWork.run();   // after the flows that complete, before the pool it deletes through (D-122)
                                } finally {
                                    try {
                                        closeSignals.run();   // after the flows that announce, before the pool it sends on (D-121 (b) B2)
                                    } finally {
                                        closeStores(journal, messages, pool, closeS3, closeValkey);
                                    }
                                }
                            }
                        }
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
