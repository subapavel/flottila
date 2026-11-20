package dev.rail8.runtime.node;

import dev.rail8.api.Component;
import dev.rail8.api.Expressions;
import dev.rail8.api.Hasher;
import dev.rail8.api.Health;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.Runtime;
import dev.rail8.api.StepFactory;
import dev.rail8.api.Timer;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.core.admission.Targets;
import dev.rail8.core.batch.BatchingWriter;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.node.NodeLease;
import dev.rail8.core.record.MessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.StepCpu;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.runtime.admin.AdminServer;
import dev.rail8.runtime.blob.TieredBlobStore;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.PgDeploymentStore;
import dev.rail8.runtime.journal.Journal;
import dev.rail8.runtime.journal.JournalFiles;
import dev.rail8.runtime.journal.JournalKeys;
import dev.rail8.runtime.journal.PgSegmentRows;
import dev.rail8.runtime.journal.SegmentSweeper;
import dev.rail8.runtime.journal.peer.PeerIdentity;
import dev.rail8.runtime.journal.peer.PeerReads;
import dev.rail8.runtime.journal.peer.PeerServer;
import dev.rail8.runtime.lease.InMemoryPollerLeases;
import dev.rail8.runtime.lease.PollerLeases;
import dev.rail8.runtime.lease.ValkeyPollerLeases;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.runtime.queue.PgFailedMessages;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.runtime.queue.PgWorkSignals;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.runtime.store.PgIntake;
import dev.rail8.runtime.store.PgMessageList;
import dev.rail8.runtime.store.PgMessageStore;
import dev.rail8.runtime.store.Retention;
import dev.rail8.runtime.time.VertxTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.redis.client.Redis;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A running node: the Postgres stores, the blob store, the components and the admin API on its caller's
 * Vert.x. {@link #start} and {@link #close} block on Vert.x futures: call them on a virtual thread.
 *
 * <p>{@code start} builds the two pools (the intake pool of {@code RAIL8_PG_POOL_INTAKE} connections for the
 * at-least-once saves and the journal's segment rows, the work pool of the rest of {@code RAIL8_PG_POOL_SIZE} for everything
 * else; no connection yet), the timer, ids and clock, builds the components from them and from the
 * node's blob store (bound once the journal is open, see below) and its HTTP port ({@code RAIL8_HTTP_HOST}:{@code RAIL8_HTTP_PORT},
 * not listening yet), refuses two components for one scheme and a component whose HTTP prefix is refused
 * ({@link SharedHttpPort#prefixes}) (in every environment) and, in a deployed environment, a mock binding — the scheme {@code mock}, or a component
 * class in {@code dev.rail8.components.mock..} or {@code dev.rail8.testkit..} — before it touches Postgres, and loads the body journal's master key
 * (from {@code RAIL8_JOURNAL_KEY_FILE}, {@code RAIL8_JOURNAL_KEY} or the opted-in generated one; none refuses the start, so a
 * keyless node migrates nothing). It then checks its durability profile ({@code RAIL8_PG_DURABILITY},
 * {@link PgDurability}) in one read on the intake pool before it migrates, so a refused node leaves its database untouched, migrates the
 * database, opens the body journal in {@code RAIL8_JOURNAL_DIR}
 * (every {@code journal_keys} row unwrapped under the master key, then its {@code LOCK}, its identity and its segment rows), opens
 * the peer link's listener on {@code RAIL8_PEER_BIND}:{@code RAIL8_PEER_PORT} (other nodes READ this journal's records
 * through it, under the master key's proof), joins the membership as a new incarnation of its node id with the link's address
 * ({@code RAIL8_PEER_HOST} and the port it bound: a
 * {@link NodeLease} over a {@link PgMembership} on a connection of its own, renewed every {@code RAIL8_NODE_LEASE_RENEW}, each renew
 * answering the live peers' addresses; its fence fences every at-least-once worker the deployments build; superseded, the node halts —
 * the process exits with {@value #SUPERSEDED}), builds the blob store on the journal (bodies in the journal under the
 * {@code default} tenant's key, their refs in the work pool, bounded by {@code RAIL8_BLOB_DURABLE_MAX}; no spool; a body in another
 * live node's journal read through the peer link) and states its notices once — the profile's, one journal copy and, when no
 * other member is live, that nobody takes its items over —, opens the stores (the work queue claiming only what this journal holds) and
 * the poller leases (in the Valkey of {@code RAIL8_VALKEY_URI} over one client the
 * node builds; without it in the node's memory, with one WARNING), builds the {@link Deployments} over them and the node's
 * one {@link Targets} ({@code RAIL8_TARGET_*}: every flow it builds sends under it, across packages and versions), starts the
 * {@link CpuSampler} when {@code RAIL8_CPU_SAMPLING} sets a period, binds the HTTP port — until the start returns it answers
 * 503 for every path no mount takes, after that 503 for the path of a flow deployed here whose consumer has not mounted it at the
 * moment and 404 for any other ({@link Deployments#serves}) — restores
 * what the pool's records name ({@link Deployments#restore}: rows that cannot be read or a data directory that cannot be
 * written fail the start; a package or a flow that cannot be restored is listed FAILED and the node starts), builds the
 * {@link Retention} job over the work pool, the clock and the timer ({@code RAIL8_RETENTION_*}) and only then starts the admin
 * server with the deploy, work and retry routes, the flows' CPU (the sampler's aggregate, read every 5 s on the node's timer and failed once a read failed, or off) and the health checks
 * {@code postgres}, {@code deployments} (DEGRADED while
 * anything is listed FAILED or RESTARTING, never DOWN, {@link Deployments#health}) and {@code journal} ({@link Journal#health}: DOWN once it failed and
 * stopped, DEGRADED while a segment row is retried), then the
 * retention job, whose first run begins at once and does not hold
 * the start, then the journal's {@link SegmentSweeper} ({@code RAIL8_JOURNAL_SWEEP_*}, its first pass an interval later); when any of this fails, what was opened closes again — the deployments first (the
 * restored flows stop while the pools are open; a stop that fails is added to the start's failure as suppressed), then the HTTP port, then the node lease
 * and its connection, then the sampler, then the work queue's completion batch, then the work events, then the peer link (its reads,
 * then its listener), then the journal, the store, the intake pool, the work pool, the Valkey client — so nothing is left running, listening
 * or pooled; {@code /ready}'s lifecycle is UP only once the start returns. {@code close} first marks the node not ready
 * ({@code /ready} 503), then closes the deployments — no deploy is taken any more and every deployed flow stops within {@code RAIL8_STOP_DEADLINE}, its lease
 * released, while the admin port still answers; a request a consumer took on the HTTP port and has not answered by that deadline is
 * reset — then closes the HTTP port, then stops renewing the node lease and closes its connection (the row expires;
 * a peer or the next start takes the items over), then closes the sampler (if one runs), then stops the admin server, then closes the retention job (no next run; the
 * batch in flight commits or rolls back first), then the sweeper (its pass in flight ends first), then the work queue's completion
 * batch (every completion handed over answered first), then the work events, then the peer link (its reads, then its
 * listener), then closes the journal (its spills and persists in flight first; its {@code LOCK}
 * given up), then flushes and closes the message store, then closes the intake pool, then the work pool, then the Valkey
 * client, each step even when the one before failed; Vert.x stays its caller's.
 *
 * @since 0.1
 */
public final class Node implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Node.class.getName());

    /** Where a mock lives, whatever scheme it is bound to (the testkit's RecordingComponent included); names, never classes. */
    private static final List<String> MOCK_PACKAGES = List.of("dev.rail8.components.mock", "dev.rail8.testkit");

    /** The tenant whose journal key seals the node's bodies until tenants are configured. */
    private static final String TENANT = "default";

    /** The exit status of a superseded node (fail-stop): a peer released its lease, or a newer incarnation joined. */
    public static final int SUPERSEDED = 3;

    /** The single-copy notice: a body lies in its node's journal only. */
    static final String ONE_COPY = "one journal copy: this node's at-least-once items wait for it while it is down (D-101 (5))";

    /** The notice of a node that found no other member live at its start. */
    static final String ALONE = "no other live member at this node's start: no other node takes its at-least-once items over while it is down (D-101)";

    private final Deployments deployments;
    /** The node's HTTP port, shared by the in endpoints of every adapter with an HTTP prefix. */
    private final SharedHttpPort http;
    /** The node's lease, renewed on its membership's own connection. */
    private final NodeLease lease;
    private final PgMembership membership;
    /** The CPU sampler, when {@code RAIL8_CPU_SAMPLING} sets a period. */
    private final Optional<CpuSampler> sampler;
    private final AdminServer admin;
    private final int adminPort;
    private final Retention retention;
    private final SegmentSweeper sweeper;
    private final Journal journal;
    private final PgMessageStore messages;
    /** The intake pool: the at-least-once saves and the journal's segment rows. */
    private final Pool intake;
    /** The work pool: every other statement of the node. */
    private final Pool pool;
    /** Closes the Valkey client of the poller leases; nothing when they are held in memory. */
    private final Runnable closeValkey;
    private final Runnable closeSignals;
    /** Closes the work queue's completion batch: what was handed over is answered through the open pool. */
    private final Runnable closeWork;
    /** The {@code lifecycle} input of {@code /ready}: set once the start finished, cleared as the close's first act. */
    private final AtomicBoolean ready;
    /** The peer link: this node's reads of other nodes' journals and its listener for theirs. */
    private final PeerReads peerReads;
    private final PeerServer peerServer;

    private Node(Deployments deployments, SharedHttpPort http, NodeLease lease, PgMembership membership, Optional<CpuSampler> sampler, AdminServer admin,
                 int adminPort, Retention retention, SegmentSweeper sweeper, Journal journal, PgMessageStore messages, Pool intake, Pool pool,
                 Runnable closeValkey, Runnable closeSignals, Runnable closeWork, AtomicBoolean ready, PeerReads peerReads,
                 PeerServer peerServer) {
        this.deployments = deployments;
        this.http = http;
        this.lease = lease;
        this.membership = membership;
        this.sampler = sampler;
        this.admin = admin;
        this.adminPort = adminPort;
        this.retention = retention;
        this.sweeper = sweeper;
        this.journal = journal;
        this.messages = messages;
        this.intake = intake;
        this.pool = pool;
        this.closeValkey = closeValkey;
        this.closeSignals = closeSignals;
        this.closeWork = closeWork;
        this.ready = ready;
        this.peerReads = peerReads;
        this.peerServer = peerServer;
    }

    /** {@link #start(NodeConfig, Vertx, Function, List, List, Map, Supplier)} with no modules to list and no admin services of modules. */
    public static Node start(NodeConfig config, Vertx vertx, Function<ComponentContext, List<Component>> components, List<StepFactory> steps,
                             List<Expressions> languages) throws IOException {
        return start(config, vertx, components, steps, languages, Map.of(), List::of);
    }

    /**
     * Starts a node on {@code vertx}; see the class comment for the order.
     *
     * @param components builds the node's components, once, from the node's own context
     * @param services   the admin services of the node's modules, by module name, served under {@code /admin/modules/<name>/}
     * @param listed     the node's modules and plugins, as {@code GET /admin/modules} lists them; asked once, after
     *                   {@code components} has built them
     * @throws IllegalStateException two components for one scheme, a component whose HTTP prefix is refused
     *                               ({@link SharedHttpPort#prefixes}), a deployed environment and a mock binding among the components,
     *                               or a Postgres the durability profile
     *                               refuses ({@link PgDurability}): under both profiles a session that does not commit with
     *                               {@code synchronous_commit=on} and a server in recovery; under {@code ha} also an empty
     *                               {@code synchronous_standby_names}, a role without the privileges of {@code pg_read_all_stats},
     *                               and no sync or quorum standby streaming
     * @throws IllegalArgumentException no journal master key, or one that is no base64 of 256 bits
     * @throws IOException the restore's data directory cannot be written, a
     *                     {@code journal_keys} row cannot be unwrapped (named by its key id), or the journal cannot be opened
     */
    public static Node start(NodeConfig config, Vertx vertx, Function<ComponentContext, List<Component>> components, List<StepFactory> steps,
                             List<Expressions> languages, Map<String, List<AdminService>> services, Supplier<List<Plugins.Listed>> listed)
            throws IOException {
        List<StepFactory> stepFactories = List.copyOf(steps);
        List<Expressions> expressions = List.copyOf(languages);
        Hasher hasher = Hasher.sha256();
        Timer timer = new VertxTimer(vertx);
        IdGenerator ids = () -> UUID.randomUUID().toString();
        Clock clock = Clock.systemUTC();
        NodeConfig.Blobs tiers = config.blobs();
        NodeConfig.Pg pg = config.pg();
        // building a pool connects nowhere (connections open on the first query), so the refusals below still come before
        // anything touches Postgres; from here on every failed start closes both again. One source of the options, each
        // session named by its role: the two pools, the membership and the LISTEN connection connect alike.
        // Both sizes and both options come before either pool is built; an intake share at or above the total never gets here,
        // because the NodeConfig constructor refuses it. Each pool waits at most RAIL8_PG_POOL_WAIT for a connection
        // and queues at most RAIL8_PG_POOL_QUEUE callers: never the client's unbounded queue and 30 s wait
        PoolOptions intakeSize = bounded(new PoolOptions().setMaxSize(pg.intakePoolSize()), pg);
        PoolOptions workSize = bounded(new PoolOptions().setMaxSize(pg.workPoolSize()), pg);
        PgConnectOptions intakeConnect = connectOptions(pg, "intake", config.nodeId());
        PgConnectOptions workConnect = connectOptions(pg, "work", config.nodeId());
        Pool intakePool = PgBuilder.pool().connectingTo(intakeConnect).with(intakeSize).using(vertx).build();   // the ACK path's only pool
        Pool pool = PgBuilder.pool().connectingTo(workConnect).with(workSize).using(vertx).build();
        PgMessageStore messages = null;
        Journal journal = null;   // once open: closed after the deployments, before the store and the pools its rows go through
        Runnable closeValkey = () -> {};   // the Valkey client once built: closed last
        Runnable closeSignals = () -> {};   // the work events once built: closed after the deployments, before the stores and the pools
        Runnable closeWork = () -> {};   // the work queue's completion batch once built: closed after the deployments, before the signals
        Deployments deployments = null;   // once built: closed first, its flows stopping while the pool is open
        // binds nothing until it listens: closed after the deployments, whose consumers' mounts drain within the stop deadline
        SharedHttpPort http = new SharedHttpPort(vertx, timer, config.http().host(), config.http().port(), config.stopDeadline());
        NodeLease lease = null;   // once joined: closed after the deployments, its connection with it
        PgMembership membership = null;
        PeerServer peerServer = null;   // once listening: closed with the reads, after the work events, before the journal it serves
        PeerReads peerReads = null;
        Optional<CpuSampler> sampler = Optional.empty();   // once started: closed after the deployments
        try {
            LateBlobStore late = new LateBlobStore(tiers.treeLimit());   // the node's store once the journal it writes to is open
            List<Component> built = List.copyOf(components.apply(new ComponentContext(vertx, late, ids, timer, clock, http)));
            Map<String, Component> byScheme = byScheme(built);
            Map<String, String> prefixes = SharedHttpPort.prefixes(built);
            if (config.deployed()) {
                refuseMocks(config.env(), built);
            }

            // the master key before any Postgres statement: a node without one migrates nothing
            SecretKey master = config.journalKey().load(config.journal().dir(), JournalFiles.real());
            // the durability profile before the migration: a refused node leaves its database untouched
            List<String> durability = PgDurability.check(intakePool, pg.durability());   // on the sessions that commit the saves
            Migrations.migrate(jdbcUrl(pg), pg.user(), pg.password());
            JournalKeys keys = JournalKeys.load(pool, master);
            Journal opened = Journal.open(config.journal(), clock, timer, keys, JournalFiles.real(), new PgSegmentRows(intakePool), UUID::randomUUID);
            journal = opened;   // its segment rows on the intake pool: a save's persist waits for them, never for the work pool
            // the peer link: listening before the join, which writes where (its actual port); its HELLO names the joined epoch
            List<SecretKey> masters = List.of(master);
            java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong();   // the join's, once it answered
            // a repl link's epoch is bounded by the renew's epochs once the membership exists; before, no owner has a bound
            var epochs = new java.util.concurrent.atomic.AtomicReference<java.util.function.ToLongFunction<String>>(owner -> 0);
            peerServer = PeerServer.listen(vertx, config.peer(), () -> new PeerIdentity(config.nodeId(), epoch.get(), opened.id(), masters), opened, timer,
                    owner -> epochs.get().applyAsLong(owner)).await();
            // the node lease: after the journal's LOCK (one process per journal joins), before any flow is built (each is fenced)
            membership = PgMembership.connecting(vertx, connectOptions(pg, "membership", config.nodeId()), config.peer().address(peerServer.port()));   // its own connection, never a pool's
            epochs.set(membership::epochOf);
            lease = new NodeLease(config.nodeId(), membership, config.nodeLease(), config.nodeLeaseRenew(), System::nanoTime, timer, Node::halt);
            lease.start().await();
            epoch.set(membership.epoch());
            // a body in another live node's journal is read through its link, at the address the last renew answered (S7)
            PgMembership members = membership;
            PeerReads reads = new PeerReads(vertx, config.peer(), new PeerIdentity(config.nodeId(), epoch.get(), opened.id(), masters), opened, timer,
                    members::peerAddress);
            peerReads = reads;
            TieredBlobStore blobs = new TieredBlobStore(new TieredBlobStore.Config(tiers.treeLimit(), tiers.memoryTier(), tiers.memoryBudget(),
                    tiers.durableMax()), hasher, opened, keys.keyOf(TENANT), pool, reads);
            late.bind(blobs);   // before any flow is built: every body a component writes is this store's
            List<String> notices = notices(config, durability, membership.others(config.nodeId()).await());
            Runtime runtime = new Runtime(clock, timer, ids, blobs, hasher, byScheme);
            messages = new PgMessageStore(pool, timer, BatchingWriter.Options.defaults());
            PgDlq dlq = new PgDlq(pool);
            // claims what this journal holds, its claims indexed; unkeyed completions in batches
            PgWorkQueue work = new PgWorkQueue(pool, opened.node(), blobs, timer);
            closeWork = () -> work.close().await();
            PollerLeases leases;
            if (config.leases().valkey().isPresent()) {
                Redis valkey = Redis.createClient(vertx, config.leases().valkey().get().toString());   // connects on its first command
                closeValkey = () -> valkey.close().await();
                leases = new ValkeyPollerLeases(valkey);
            } else {
                LOG.log(System.Logger.Level.WARNING, "RAIL8_VALKEY_URI is not set: this node holds its poller leases in its own memory. "
                        + "Alone that is enough; two such nodes on one polling endpoint both poll it (duplicates, never a loss): give a pool of "
                        + "nodes one Valkey (D-083)");
                leases = new InMemoryPollerLeases(clock);
            }
            // an at-least-once ingress saves its body, its record and its item in one transaction
            // the node's started workers: its intake hands each saved item off to the flow's worker, leased to this node
            Workers workers = new Workers();
            // the work events between the nodes: listening on a connection of their own before any worker starts
            PgWorkSignals signals = new PgWorkSignals(vertx, connectOptions(pg, "listen", config.nodeId()), pool, opened.node(), workers, timer);
            closeSignals = () -> signals.close().await();
            signals.start().await();
            // grouped saves on the intake pool; the wake and the announce after a commit are fire-and-forget on the work pool
            PgIntake intake = new PgIntake(intakePool, blobs, messages, work, workers, timer, config.intakeGroup(), signals);
            Deployments restored = new Deployments(config, runtime, byScheme, clock, timer, stepFactories, expressions, messages, dlq, work,
                    Meter.system(), new PgDeploymentStore(pool), intake, leases,
                    new Targets(config.targets().defaults(), config.targets().overrides(), timer), lease, workers, signals);   // the node's one Targets and fence
            deployments = restored;
            StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
            // before the restore: the restored flows' steps are sampled from their first message on
            sampler = config.cpuSampling().map(period -> CpuSampler.start(period, cpu, clock, timer, CpuSampler.READ_EVERY));
            AtomicBoolean ready = new AtomicBoolean();   // /ready's lifecycle: DOWN until the start finished (a failed start never sets it)
            // before the restore, whose consumers mount on it; a path no mount takes is 503 until the start finished
            http.listen(prefixes, path -> !ready.get() || restored.serves(path)).await();
            restored.restore();   // before the admin port opens: what the pool recorded runs before a deploy can be taken
            Retention retention = new Retention(pool, clock, timer, config.retention());
            // /ready's inputs are in-memory reads: the fence, the journal's and the deployments' health — both synchronous
            AdminServer.Readiness readiness = new AdminServer.Readiness(ready::get, lease,
                    () -> opened.health().status() == Health.Status.UP, () -> restored.health().status() != Health.Status.DOWN);
            AdminServer admin = new AdminServer(config.admin(), messages, dlq, blobs,
                    new AdminServer.HealthProbe(Map.of("postgres", () -> pool.query("select 1").execute().map(Health.up()),
                            "deployments", () -> Future.succeededFuture(restored.health()),
                            "journal", () -> Future.succeededFuture(opened.health())), messages::failed, notices, readiness),
                    Optional.of(restored), work, new PgFailedMessages(pool, work), sampler.map(s -> new AdminServer.Sampling(s.period(), s.since(), cpu, s::failed)),
                    new PgMessageList(pool), workers, signals, new AdminServer.Modules(listed.get(), services, clock));
            int port = admin.start(vertx).await();
            retention.start();   // its first run on a virtual thread of its own: the start does not wait for it
            // the segments no row pins go; its first pass an interval later, on a virtual thread of its own
            SegmentSweeper sweeper = new SegmentSweeper(opened, new PgSegmentRows(pool), intake::openSaves, config.journalSweep(), clock, timer);
            sweeper.start();
            Node node = new Node(restored, http, lease, membership, sampler, admin, port, retention, sweeper, opened, messages, intakePool, pool,
                    closeValkey, closeSignals, closeWork, ready, peerReads, peerServer);
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
                    try {
                        http.close().await();   // after the flows that mounted on it
                    } finally {
                        closeLease(lease, membership);
                    }
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
                                try {
                                    closePeers(peerReads, peerServer);   // before the journal it serves
                                } finally {
                                    closeStores(journal, messages, intakePool, pool, closeValkey);
                                }
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
     * The node's Postgres connect options: the server and credentials of {@code pg}, and the prepared-statement cache on
     * for every SQL text up to {@value #STATEMENT_CACHE_SQL_LIMIT} characters, so a connection parses a
     * statement once; pipelining stays at the client's default (a turn's statements are sent together). Every session runs
     * with {@code jit} off, a startup parameter: the node's statements touch a bounded number of rows, yet
     * an estimate that grows with a table's size crossed jit_above_cost and every claim compiled its plan. Every session also
     * commits with {@code synchronous_commit=on} and runs with {@code statement_timeout=0}, startup parameters above a database's or
     * a role's own setting under both durability profiles: a commit waits for its WAL flush and, when
     * the server names synchronous standbys, for them, and no timeout cancels that wait (the node's save timeout bounds the
     * caller's answer instead). The link is secured as
     * {@code pg}'s {@link NodeConfig.Pg#sslMode() sslMode} says: a verifying mode trusts the PEM CA of
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
     * {@code options} with {@code pg}'s bounds on the wait for a pooled connection: a caller waits at most
     * {@link NodeConfig.Pg#poolWait() poolWait} (in whole milliseconds), at most {@link NodeConfig.Pg#poolQueue() poolQueue} callers
     * wait, and the pool refuses the rest at once.
     */
    private static PoolOptions bounded(PoolOptions options, NodeConfig.Pg pg) {
        return options.setConnectionTimeout(Math.toIntExact(pg.poolWait().toMillis())).setConnectionTimeoutUnit(TimeUnit.MILLISECONDS)
                .setMaxWaitQueueSize(pg.poolQueue());
    }

    /**
     * {@link #connectOptions(NodeConfig.Pg)} — the one source of the server, the credentials, the session pins and TLS — with the
     * session named {@code rail8-<role>@<nodeId>} ({@code application_name}), so {@code pg_stat_activity} shows
     * which pool or connection of which node a session is: {@code intake}, {@code work}, {@code membership} or {@code listen}.
     */
    public static PgConnectOptions connectOptions(NodeConfig.Pg pg, String role, String nodeId) {
        return connectOptions(pg).addProperty("application_name", "rail8-" + role + "@" + nodeId);
    }

    /**
     * The JDBC URL Flyway migrates over, secured as {@link #connectOptions} is: {@code sslmode} always, {@code sslrootcert}
     * when a CA is given (URL-encoded: the driver decodes its parameters).
     */
    static String jdbcUrl(NodeConfig.Pg pg) {
        String url = "jdbc:postgresql://" + pg.host() + ":" + pg.port() + "/" + pg.database() + "?sslmode=" + pg.sslMode();
        return pg.sslRootCert().map(ca -> url + "&sslrootcert=" + URLEncoder.encode(ca.toString(), StandardCharsets.UTF_8)).orElse(url);
    }

    /** The port the admin server bound: the actual one when {@code RAIL8_ADMIN_PORT} is 0. */
    public int adminPort() { return adminPort; }

    /** The node's HTTP port as bound: the actual one when {@code RAIL8_HTTP_PORT} is 0. */
    public int httpPort() { return http.port(); }

    /** The node's message store over Postgres. */
    public MessageStore messages() { return messages; }

    /**
     * What the admin health names beside its checks: a generated master key, which lies beside the data it protects; the
     * {@code durability} notices of the start's Postgres check; one journal copy; and, with no
     * {@code others} live at the start, that no other node takes this one's items over. All but the first are logged here, once.
     */
    private static List<String> notices(NodeConfig config, List<String> durability, int others) {
        List<String> notices = new ArrayList<>();
        if (config.journalKey().generates()) {
            notices.add("the journal's master key is generated (RAIL8_JOURNAL_KEY_GENERATE=true) and lies beside the data it protects: "
                    + "for a single dev node only; give every other node RAIL8_JOURNAL_KEY_FILE (D-106)");
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

    /** Fail-stop: the process exits with {@link #SUPERSEDED}, off the calling thread (a renew answers on an event loop, which the shutdown hook needs). */
    private static void halt() {
        Thread.ofVirtual().name("rail8-superseded").start(() -> System.exit(SUPERSEDED));
    }

    /** Stops renewing the node lease (none yet: nothing) and closes its connection (none: nothing), the connection even when the stop threw. */
    private static void closeLease(NodeLease lease, PgMembership membership) {
        try {
            if (lease != null) lease.close();
        } finally {
            if (membership != null) membership.close().await();
        }
    }

    /** Closes the peer link's reads (none yet: nothing), then its listener (none: nothing), the listener even when the reads' close threw. */
    private static void closePeers(PeerReads reads, PeerServer server) {
        try {
            if (reads != null) reads.close().await();
        } finally {
            if (server != null) server.close().await();
        }
    }

    /** The node's body journal, which the blob store writes through. */
    public Journal journal() { return journal; }

    /**
     * Marks the node not ready ({@code /ready} 503 from here), then closes the deployments (every flow stopped, its lease released, deploys refused), then the HTTP port, then stops the node lease and closes its connection, then the CPU sampler (if one runs), then stops the admin server, then closes the
     * retention job (waits for its batch in flight), then the segment sweeper (waits for its pass in flight), then the work queue's
     * completion batch (waits until every completion handed over is answered), then the work events
     * (their listening connection; nothing sent from then on), then the peer link (its reads, then its listener), then closes the
     * journal, then flushes and closes the message store, then closes
     * the intake pool, then the work pool, then the Valkey client — each step even when the one before failed.
     */
    @Override
    public void close() {
        ready.set(false);   // first: /ready is 503 while the listeners still drain, the admin port still answering
        try {
            deployments.close();
        } finally {
            try {
                try {
                    http.close().await();   // after the flows that mounted on it
                } finally {
                    closeLease(lease, membership);   // after the deployments: no flow is left to fence
                }
            } finally {
                try {
                    sampler.ifPresent(CpuSampler::close);   // after the deployments; it feeds nothing more, their last one to two read cycles unread
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
                                    closeWork.run();   // after the flows that complete, before the pool it deletes through
                                } finally {
                                    try {
                                        closeSignals.run();   // after the flows that announce, before the pool it sends on
                                    } finally {
                                        try {
                                            closePeers(peerReads, peerServer);   // after the flows that read, before the journal it serves
                                        } finally {
                                            closeStores(journal, messages, intake, pool, closeValkey);
                                        }
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
     * flushes and closes the store (none yet: nothing to flush), then closes the intake pool, then the work pool, then the Valkey
     * client (none: nothing) — each even when the one before failed.
     */
    private static void closeStores(Journal journal, PgMessageStore messages, Pool intake, Pool pool, Runnable closeValkey) {
        try {
            if (journal != null) journal.close();
        } catch (IOException e) {
            throw new UncheckedIOException("the journal did not close", e);
        } finally {
            closeStores(messages, intake, pool, closeValkey);
        }
    }

    private static void closeStores(PgMessageStore messages, Pool intake, Pool pool, Runnable closeValkey) {
        try {
            if (messages != null) messages.close();
        } finally {
            try {
                intake.close().await();
            } finally {
                closePool(pool, closeValkey);
            }
        }
    }

    private static void closePool(Pool pool, Runnable closeValkey) {
        try {
            pool.close().await();
        } finally {
            closeValkey.run();
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
