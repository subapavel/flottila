package io.flottila.runtime.node;

import io.flottila.core.admission.Targets;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.RetryPolicy;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.deploy.Secrets;
import io.flottila.runtime.journal.JournalConfig;
import io.flottila.runtime.journal.MasterKeySource;
import io.flottila.runtime.journal.SweepConfig;
import io.flottila.runtime.journal.peer.PeerConfig;
import io.flottila.runtime.store.Retention;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A node's whole configuration, read from its environment (D-057 b). Every key has a default except {@code FLOTTILA_ENV};
 * every refusal is an {@link IllegalArgumentException} whose message names the key. No {@code toString} here carries a secret:
 * {@link Pg}, {@link AdminServer.Config} and {@link S3} redact the password, the token and the secret key, {@link Leases} prints of
 * the Valkey URI its scheme, host, port and database only, {@link Secrets} names its variables only, {@link MasterKeySource} names
 * its source only, and this record's prints theirs.
 *
 * @param nodeId {@code FLOTTILA_NODE_ID}, else {@code $HOSTNAME}: a DNS label (lower-case letters, digits and inner dashes, at most 63)
 * @param env {@code FLOTTILA_ENV}, required
 * @param admin {@code FLOTTILA_ADMIN_HOST} (127.0.0.1), {@code FLOTTILA_ADMIN_PORT} (8081, 0 binds any free port), {@code FLOTTILA_ADMIN_TOKEN}
 * @param dataDir {@code FLOTTILA_DATA_DIR} (/var/lib/flottila): the spool lives under it, and the dir archive store by default
 * @param stopDeadline {@code FLOTTILA_STOP_DEADLINE}, ISO-8601, not negative (PT10S)
 * @param maxPackageBytes {@code FLOTTILA_MAX_PACKAGE_BYTES}, 1 byte to 1 GiB (64 MiB)
 * @param pg the {@code FLOTTILA_PG_*} keys
 * @param blobs the {@code FLOTTILA_BLOB_*} keys
 * @param archives {@code FLOTTILA_ARCHIVE_STORE} and {@code FLOTTILA_ARCHIVE_DIR}: where package archives are stored (D-070)
 * @param s3 the {@code FLOTTILA_S3_*} keys: present exactly when the archive store is {@link Archives.Store#S3}
 * @param secrets what the endpoints' {@code secret://} references resolve to (D-081): the {@code FLOTTILA_SECRET_*} variables
 * @param leases the {@code FLOTTILA_VALKEY_URI} and {@code FLOTTILA_POLLER_LEASE_*} keys: where and how long a polling consumer's
 *               lease is held (D-083)
 * @param retention the retention job's keys (D-088): {@code FLOTTILA_RETENTION_MAX_AGE}, ISO-8601, PT1H to P366D (P7D): rows
 *                  older are deleted; {@code FLOTTILA_RETENTION_INTERVAL}, ISO-8601, PT1M to P1D (PT1H): from the end of a run to
 *                  the start of the next; {@code FLOTTILA_RETENTION_BATCH}, 1 to 10000 (1000): the rows one batch scans
 * @param cpuSampling {@code FLOTTILA_CPU_SAMPLING} (D-094): {@code off} (the default, empty) or the CPU-time sampling period, an
 *                    ISO-8601 duration in whole milliseconds from PT0.001S to PT0.1S; with a period the node runs a CPU sampler
 * @param targets the limits per target system (D-099): {@code FLOTTILA_TARGET_MAX_CALLS}, {@code FLOTTILA_TARGET_MAX_WAIT} and
 *                {@code FLOTTILA_TARGET_LIMITS} ({@link TargetLimits})
 * @param journal the body journal's keys (CORE-3, D-105): {@code FLOTTILA_JOURNAL_DIR} ({@code <dataDir>/journal}),
 *                {@code FLOTTILA_JOURNAL_SEGMENT_BYTES} (64 MiB), {@code FLOTTILA_JOURNAL_SEGMENT_RECORDS} (65 536, at most
 *                2^31 - 1), {@code FLOTTILA_JOURNAL_SEGMENT_AGE} (PT1S), {@code FLOTTILA_JOURNAL_PENDING_BYTES} (16 MiB),
 *                {@code FLOTTILA_JOURNAL_APPEND_WAIT} (PT5S), {@code FLOTTILA_JOURNAL_RECORD_MAX} (256 KiB or
 *                {@code FLOTTILA_BLOB_MEMORY_TIER} when that is smaller; at most the tier), {@code FLOTTILA_JOURNAL_MAX_BYTES} (16 GiB),
 *                {@code FLOTTILA_JOURNAL_READ_CHANNELS} (64, 1..1024), {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} (none, taken as given),
 *                each range-checked as {@link JournalConfig} says; the node id is the journal's
 * @param journalKey the journal's master key source (D-106): {@code FLOTTILA_JOURNAL_KEY_FILE}, {@code FLOTTILA_JOURNAL_KEY} or
 *                   {@code FLOTTILA_JOURNAL_KEY_GENERATE}; a node without one is refused at start, not here
 * @param journalSweep the segment sweeper's keys (CORE-3 items 10, 14): {@code FLOTTILA_JOURNAL_SWEEP_INTERVAL} (PT10S,
 *                     PT1S..PT1H), {@code FLOTTILA_JOURNAL_SWEEP_GRACE} (PT1M, PT1S..PT1H), {@code FLOTTILA_JOURNAL_KEEP_MAX_AGE}
 *                     (PT1H, PT1M..P1D), ISO-8601 each, range-checked as {@link SweepConfig} says
 * @param intakeGroup the intake's group commit (CORE-6b, D-109): {@code FLOTTILA_INTAKE_GROUP_MAX} (64, 1..1024; 1 = no grouping),
 *                    {@code FLOTTILA_INTAKE_GROUP_BYTES} (1 MiB, 64 KiB..64 MiB), {@code FLOTTILA_INTAKE_GROUP_WINDOW} (PT0.001S,
 *                    PT0S..PT0.05S in whole milliseconds), {@code FLOTTILA_INTAKE_GROUP_CONCURRENCY} (1..64, at most
 *                    max(1, {@code FLOTTILA_PG_POOL_INTAKE} − 1), the intake pool's bound (D-130 draft amends D-109's bound over
 *                    {@code FLOTTILA_PG_POOL_SIZE}); unset, min(4, that bound)); draft defaults until CORE-GATE
 * @param nodeLease {@code FLOTTILA_NODE_LEASE} (D-101 (1), F-28), ISO-8601, above zero and at most PT10M (PT30S): the node's
 *                  lease in the membership; its fence closes half a lease after its last renewed send
 * @param nodeLeaseRenew {@code FLOTTILA_NODE_LEASE_RENEW}, ISO-8601, above zero and at most a quarter of the lease (PT5S): how
 *                       often the node renews it
 * @param retries the node's default schedule of retries for an at-least-once flow that names no {@code delivery.retries}
 *                (CORE-14a P1, D-135 draft): {@code FLOTTILA_RETRIES_COUNT} (3, 0..100) retries {@code FLOTTILA_RETRIES_INTERVAL}
 *                (PT5M, ISO-8601, PT1S..PT24H) apart, without growth: multiplier 1, the interval its own cap
 * @param peer the peer link's keys (CORE-5a S11, draft): {@code FLOTTILA_PEER_BIND} (0.0.0.0), {@code FLOTTILA_PEER_PORT} (8083,
 *             0..65535, 0 any free port; 8082 is the compose async door), {@code FLOTTILA_PEER_HOST} (the node id),
 *             {@code FLOTTILA_PEER_TIMEOUT} (PT2S, PT0.1S..PT30S), {@code FLOTTILA_PEER_READS} (16, 1..64 per peer)
 * @param journalCopies {@code FLOTTILA_JOURNAL_COPIES} (CORE-5 S10, S11, draft): 1 or 2; unset 1 in dev and ci, 2 in every
 *                      deployed environment. A generated master key ({@code FLOTTILA_JOURNAL_KEY_GENERATE=true}) is one node's
 *                      alone, which no peer could read with (D-106): with more than one copy it is refused
 * @since 0.1
 */
public record NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline,
                         long maxPackageBytes, Pg pg, Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases,
                         Retention.Config retention, Optional<Duration> cpuSampling, TargetLimits targets, JournalConfig journal,
                         MasterKeySource journalKey, SweepConfig journalSweep, IntakeGroup intakeGroup, Duration nodeLease,
                         Duration nodeLeaseRenew, RetryPolicy retries, PeerConfig peer, int journalCopies) {

    /**
     * The S3 settings and the archive store say one fact: an s3 store has them, a dir store has none. A journal record is
     * held in memory whole, so the record max is at most the memory tier (CORE-3 item 5). The intake pool's share of the Postgres
     * connections is at least 1 and below their total, so each pool keeps a connection (D-130 draft). The intake's groups in flight leave the
     * intake pool a connection: at most max(1, intake pool size − 1) of them (CORE-6b, D-130 draft). The node lease is renewed at least four times a lease
     * (D-101 (1)): a renew or two may fail without closing the fence. Each pool's wait for a connection is PT0.001S to PT1M and its
     * wait queue 1 to 65536 callers (D-132): an in-process {@link Pg} is held to the ranges the env reader names.
     */
    public NodeConfig {
        Objects.requireNonNull(archives, "archives");
        Objects.requireNonNull(s3, "s3");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(cpuSampling, "cpuSampling");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(journalKey, "journalKey");
        Objects.requireNonNull(journalSweep, "journalSweep");
        Objects.requireNonNull(intakeGroup, "intakeGroup");
        Objects.requireNonNull(retries, "retries");
        Objects.requireNonNull(peer, "peer");
        if (journalCopies < 1 || journalCopies > COPIES_MAX) {
            throw new IllegalArgumentException("FLOTTILA_JOURNAL_COPIES must be between 1 and " + COPIES_MAX + ", was " + journalCopies);
        }
        if (journalKey.generates() && journalCopies > 1) {
            throw new IllegalArgumentException("FLOTTILA_JOURNAL_KEY_GENERATE=true with FLOTTILA_JOURNAL_COPIES " + journalCopies + ": a generated master key"
                    + " is this node's alone and no peer could read its bodies with it (D-106); give every node FLOTTILA_JOURNAL_KEY_FILE, or set"
                    + " FLOTTILA_JOURNAL_COPIES=1 for a single dev node");
        }
        if (retries.attempts() > RETRIES_COUNT_MAX) {
            throw new IllegalArgumentException("FLOTTILA_RETRIES_COUNT " + retries.attempts() + " must be from 0 to " + RETRIES_COUNT_MAX);
        }
        if (retries.initial().compareTo(RETRIES_INTERVAL_MIN) < 0 || retries.initial().compareTo(RETRIES_INTERVAL_MAX) > 0) {
            throw new IllegalArgumentException("FLOTTILA_RETRIES_INTERVAL " + retries.initial() + " must be from " + RETRIES_INTERVAL_MIN + " to "
                    + RETRIES_INTERVAL_MAX);
        }
        if (retries.max().compareTo(RETRIES_INTERVAL_MAX) > 0) {   // the env reader's is the interval itself; an in-process one may grow, never past the range
            throw new IllegalArgumentException("FLOTTILA_RETRIES_INTERVAL's cap " + retries.max() + " must be at most " + RETRIES_INTERVAL_MAX);
        }
        if (!nodeLease.isPositive() || nodeLease.compareTo(NODE_LEASE_MAX) > 0) {
            throw new IllegalArgumentException("FLOTTILA_NODE_LEASE " + nodeLease + " must be above zero and at most " + NODE_LEASE_MAX);
        }
        if (!nodeLeaseRenew.isPositive() || nodeLeaseRenew.multipliedBy(4).compareTo(nodeLease) > 0) {
            throw new IllegalArgumentException("FLOTTILA_NODE_LEASE_RENEW " + nodeLeaseRenew + " must be above zero and at most a quarter of FLOTTILA_NODE_LEASE "
                    + nodeLease + ": the node renews at least four times a lease");
        }
        if (journal.recordMax() > blobs.memoryTier()) {
            throw new IllegalArgumentException("FLOTTILA_JOURNAL_RECORD_MAX " + journal.recordMax() + " is above FLOTTILA_BLOB_MEMORY_TIER "
                    + blobs.memoryTier() + ": a journal record is held in memory whole");
        }
        if ((archives.store() == Archives.Store.S3) != s3.isPresent()) {
            throw new IllegalArgumentException("FLOTTILA_ARCHIVE_STORE " + archives.store() + " with the S3 settings " + s3
                    + ": an s3 archive store has them, a dir store none");
        }
        if (pg.intakePoolSize() < 1 || pg.intakePoolSize() >= pg.poolSize()) {
            throw new IllegalArgumentException("FLOTTILA_PG_POOL_INTAKE " + pg.intakePoolSize() + " must be at least 1 and below FLOTTILA_PG_POOL_SIZE "
                    + pg.poolSize() + ": the total of both pools, and each pool keeps at least one connection (D-130)");
        }
        if (intakeGroup.concurrency() > IntakeGroup.bound(pg.intakePoolSize())) {
            throw new IllegalArgumentException("FLOTTILA_INTAKE_GROUP_CONCURRENCY " + intakeGroup.concurrency() + " is above max(1, FLOTTILA_PG_POOL_INTAKE "
                    + pg.intakePoolSize() + " - 1): each group in flight holds a connection of the intake pool, and it keeps one for the"
                    + " intake's other statements");
        }
        if (pg.poolWait().compareTo(POOL_WAIT_MIN) < 0 || pg.poolWait().compareTo(POOL_WAIT_MAX) > 0) {   // Durations compared: no toMillis overflow
            throw new IllegalArgumentException("FLOTTILA_PG_POOL_WAIT " + pg.poolWait() + " must be from " + POOL_WAIT_MIN + " to " + POOL_WAIT_MAX
                    + ": the longest a caller waits for a pooled connection (D-132)");
        }
        if (pg.poolQueue() < 1 || pg.poolQueue() > POOL_QUEUE_MAX) {
            throw new IllegalArgumentException("FLOTTILA_PG_POOL_QUEUE " + pg.poolQueue() + " must be from 1 to " + POOL_QUEUE_MAX
                    + ": the most callers waiting per pool (D-132)");
        }
    }

    /**
     * A node whose peer link and copies have the default keys (port 8083 on 0.0.0.0, the node id as its host, PT2S, 16 reads;
     * one copy in dev and ci, two when deployed), as {@link #from} reads an env without them.
     */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases, Retention.Config retention,
                      Optional<Duration> cpuSampling, TargetLimits targets, JournalConfig journal, MasterKeySource journalKey, SweepConfig journalSweep,
                      IntakeGroup intakeGroup, Duration nodeLease, Duration nodeLeaseRenew, RetryPolicy retries) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention, cpuSampling, targets,
                journal, journalKey, journalSweep, intakeGroup, nodeLease, nodeLeaseRenew, retries,
                new PeerConfig(PEER_BIND, PeerConfig.PORT, nodeId, PeerConfig.TIMEOUT, PeerConfig.READS), deployed(env) ? COPIES_MAX : 1);
    }

    /** A node whose node lease has the default keys (PT30S, renewed every PT5S), as {@link #from} reads an env without them. */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases, Retention.Config retention,
                      Optional<Duration> cpuSampling, TargetLimits targets, JournalConfig journal, MasterKeySource journalKey, SweepConfig journalSweep,
                      IntakeGroup intakeGroup) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention, cpuSampling, targets,
                journal, journalKey, journalSweep, intakeGroup, NODE_LEASE_DEFAULT, NODE_LEASE_RENEW_DEFAULT);
    }

    /** A node whose retries have the default keys (3 × PT5M, {@link FlowBuilder#DEFAULT_RETRIES}), as {@link #from} reads an env without them. */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases, Retention.Config retention,
                      Optional<Duration> cpuSampling, TargetLimits targets, JournalConfig journal, MasterKeySource journalKey, SweepConfig journalSweep,
                      IntakeGroup intakeGroup, Duration nodeLease, Duration nodeLeaseRenew) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention, cpuSampling, targets,
                journal, journalKey, journalSweep, intakeGroup, nodeLease, nodeLeaseRenew, FlowBuilder.DEFAULT_RETRIES);
    }

    /** A node whose intake groups have the default keys for its intake pool ({@link IntakeGroup#defaults}), as {@link #from} reads an env without them. */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases, Retention.Config retention,
                      Optional<Duration> cpuSampling, TargetLimits targets, JournalConfig journal, MasterKeySource journalKey, SweepConfig journalSweep) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention, cpuSampling, targets,
                journal, journalKey, journalSweep, IntakeGroup.defaults(pg.intakePoolSize()));
    }

    /** A node whose sweeper has the default keys (PT10S, PT1M, PT1H), as {@link #from} reads an env without them. */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases, Retention.Config retention,
                      Optional<Duration> cpuSampling, TargetLimits targets, JournalConfig journal, MasterKeySource journalKey) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention, cpuSampling, targets,
                journal, journalKey, SweepConfig.DEFAULTS);
    }

    /**
     * A node whose journal has the default keys in {@code <dataDir>/journal} and no master key source (its start is refused
     * until one is given), as {@link #from} reads an env without the {@code FLOTTILA_JOURNAL_*} keys.
     */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases, Retention.Config retention,
                      Optional<Duration> cpuSampling, TargetLimits targets) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention, cpuSampling, targets,
                journal(Map.of(), dataDir, nodeId, blobs.memoryTier()), MasterKeySource.fromEnv(Map.of()));
    }

    /** A node whose targets have the default limits and no override, as {@link #from} reads them by default (D-099). */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases, Retention.Config retention,
                      Optional<Duration> cpuSampling) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention, cpuSampling,
                TargetLimits.DEFAULTS);
    }

    /** A node without CPU sampling, as {@link #from} reads it by default (D-094). */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases, Retention.Config retention) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention, Optional.empty());
    }

    /** A node with the retention job's defaults (P7D, PT1H, 1000 rows), as {@link #from} reads them by default. */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, RETENTION_DEFAULTS);
    }

    /** A node whose poller leases are held in its memory with the default ttl and renew period, as {@link #from} reads it by default. */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, Leases.DEFAULTS);
    }

    /** A node without secrets: every {@code secret://} reference of an endpoint is not set (D-081). */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs, Archives archives, Optional<S3> s3) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, Secrets.fromEnv(Map.of()));
    }

    /** A node whose archives are stored in {@code <dataDir>/archives} (the dir store), as {@link #from} reads it by default; no secrets. */
    public NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline, long maxPackageBytes, Pg pg,
                      Blobs blobs) {
        this(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, new Archives(Archives.Store.DIR, dataDir.resolve("archives")),
                Optional.empty());
    }

    /** This configuration with {@code secrets} instead of its own: the CLI's dry build resolves to {@link Secrets#placeholder()}. */
    public NodeConfig withSecrets(Secrets secrets) {
        return new NodeConfig(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention,
                cpuSampling, targets, journal, journalKey, journalSweep, intakeGroup, nodeLease, nodeLeaseRenew, retries, peer, journalCopies);
    }

    /** Every environment but {@code dev} and {@code ci} (ARCHITECTURE §11: {@code test} is deployed); a deployed node refuses a mock binding. */
    public boolean deployed() {
        return deployed(env);
    }

    private static boolean deployed(String env) {
        return !env.equals("dev") && !env.equals("ci");
    }

    /**
     * Where the node's Postgres is.
     *
     * @param host {@code FLOTTILA_PG_HOST} (localhost)
     * @param port {@code FLOTTILA_PG_PORT} (5432)
     * @param database {@code FLOTTILA_PG_DATABASE} (flottila)
     * @param user {@code FLOTTILA_PG_USER} (flottila)
     * @param password {@code FLOTTILA_PG_PASSWORD} (empty)
     * @param poolSize {@code FLOTTILA_PG_POOL_SIZE}, 2 to 1024 (21 = 5 intake + 16 work, F-33): the pooled connections of both pools together (D-130 draft)
     * @param sslMode {@code FLOTTILA_PG_SSL_MODE}: {@code disable}, {@code verify-ca} or {@code verify-full} (D-124); unset,
     *                {@code disable} on a loopback host ({@code localhost}, {@code 127.0.0.1}, {@code ::1}), else {@code verify-full}
     * @param sslRootCert {@code FLOTTILA_PG_SSL_ROOT_CERT}: the PEM CA bundle that signs the server's certificate, required by a
     *                    verifying mode (never a silent fallback to plaintext or to an unverified link), refused at start when given but
     *                    missing, unreadable or empty; not a secret
     * @param durability {@code FLOTTILA_PG_DURABILITY}: {@code local} or {@code ha} (D-127 draft); unset, {@code local} in a
     *                   non-deployed environment ({@code dev}, {@code ci}) and {@code ha} in every deployed one, whatever the host
     * @param intakePoolSize {@code FLOTTILA_PG_POOL_INTAKE}, 1 to 1023 and below {@code poolSize} (D-130 draft): the intake pool's
     *                       share — the at-least-once saves and the journal's segment rows; unset, {@link #intakeDefault}; the work
     *                       pool keeps the rest ({@link #workPoolSize}). The split is checked by the {@link NodeConfig} constructor, not here
     * @param poolWait {@code FLOTTILA_PG_POOL_WAIT}, PT0.001S to PT1M (PT5S, the save timeout; FIX-172, D-132): the longest a caller
     *                 waits for a connection of either pool before the pool refuses it; the range is checked by the {@link NodeConfig}
     *                 constructor, not here
     * @param poolQueue {@code FLOTTILA_PG_POOL_QUEUE}, 1 to 65536 (1024; FIX-172, D-132): the most callers waiting per pool; the next
     *                  one is refused at once; the range is checked by the {@link NodeConfig} constructor, not here
     * @since 0.1
     */
    public record Pg(String host, int port, String database, String user, String password, int poolSize, SslMode sslMode, Optional<Path> sslRootCert,
                     Durability durability, int intakePoolSize, Duration poolWait, int poolQueue) {

        /** {@code FLOTTILA_PG_POOL_WAIT} unset: the save timeout (ARCHITECTURE §4). */
        public static final Duration POOL_WAIT_DEFAULT = Duration.ofSeconds(5);

        /** {@code FLOTTILA_PG_POOL_QUEUE} unset. */
        public static final int POOL_QUEUE_DEFAULT = 1024;

        /**
         * What an accepted message survives on the Postgres side (D-127 draft, D-114, D-115), checked once at the node's start
         * ({@code PgDurability}). Both profiles pin the node's sessions to {@code synchronous_commit=on} and refuse a server in
         * recovery.
         *
         * @since 0.1
         */
        public enum Durability {
            /** A single server's commit: an accepted message survives a Postgres crash, not a promotion or the loss of its host. */
            LOCAL("local"),
            /** A commit that waited for a synchronous (or quorum) standby: it survives the promotion of such a standby too. */
            HA("ha");

            private final String spelling;

            Durability(String spelling) {
                this.spelling = spelling;
            }

            /** As written in {@code FLOTTILA_PG_DURABILITY}. */
            @Override
            public String toString() {
                return spelling;
            }
        }

        /**
         * How the node's Postgres links are secured (D-124). TLS without verification ({@code require}, {@code prefer}) is not
         * offered: it gives no protection against an active attacker.
         *
         * @since 0.1
         */
        public enum SslMode {
            /** Plaintext: a loopback server, or a stack that writes its plaintext link down. */
            DISABLE("disable"),
            /** TLS, the server's certificate signed by the CA; its name is not checked. */
            VERIFY_CA("verify-ca"),
            /** TLS, the server's certificate signed by the CA and naming the host dialled. */
            VERIFY_FULL("verify-full");

            private final String spelling;

            SslMode(String spelling) {
                this.spelling = spelling;
            }

            /** As written in {@code FLOTTILA_PG_SSL_MODE} (and in libpq's and the JDBC driver's {@code sslmode}). */
            @Override
            public String toString() {
                return spelling;
            }
        }

        /** A verifying mode carries its CA: a link is never verified against nothing. */
        public Pg {
            Objects.requireNonNull(sslMode, "sslMode");
            Objects.requireNonNull(sslRootCert, "sslRootCert");
            Objects.requireNonNull(durability, "durability");
            Objects.requireNonNull(poolWait, "poolWait");
            if (sslMode != SslMode.DISABLE && sslRootCert.isEmpty()) {
                throw new IllegalArgumentException("FLOTTILA_PG_SSL_MODE " + sslMode + " (FLOTTILA_PG_HOST " + host
                        + ") needs FLOTTILA_PG_SSL_ROOT_CERT, the PEM CA that signs the server's certificate: set it, or set FLOTTILA_PG_SSL_MODE=disable"
                        + " for a server reached in plaintext on purpose");
            }
        }

        /**
         * The pools' wait and queue at their defaults ({@link #POOL_WAIT_DEFAULT}, {@link #POOL_QUEUE_DEFAULT}): in-process callers;
         * env parsing never uses it.
         *
         * @param host the server's host
         * @param port its port
         * @param database the database
         * @param user the user
         * @param password the user's password
         * @param poolSize the connections of both pools at most
         * @param sslMode how the link is secured
         * @param sslRootCert the CA of a verifying mode
         * @param durability what an accepted message survives
         * @param intakePoolSize the intake pool's share
         */
        public Pg(String host, int port, String database, String user, String password, int poolSize, SslMode sslMode, Optional<Path> sslRootCert,
                  Durability durability, int intakePoolSize) {
            this(host, port, database, user, password, poolSize, sslMode, sslRootCert, durability, intakePoolSize, POOL_WAIT_DEFAULT, POOL_QUEUE_DEFAULT);
        }

        /**
         * The intake pool's share by {@link #intakeDefault} of {@code poolSize}: in-process callers; env parsing never uses it.
         *
         * @param host the server's host
         * @param port its port
         * @param database the database
         * @param user the user
         * @param password the user's password
         * @param poolSize the connections of both pools at most
         * @param sslMode how the link is secured
         * @param sslRootCert the CA of a verifying mode
         * @param durability what an accepted message survives
         */
        public Pg(String host, int port, String database, String user, String password, int poolSize, SslMode sslMode, Optional<Path> sslRootCert,
                  Durability durability) {
            this(host, port, database, user, password, poolSize, sslMode, sslRootCert, durability, intakeDefault(poolSize));
        }

        /**
         * The {@link Durability#LOCAL local} profile: in-process callers; env parsing never uses it.
         *
         * @param host the server's host
         * @param port its port
         * @param database the database
         * @param user the user
         * @param password the user's password
         * @param poolSize the connections of both pools at most
         * @param sslMode how the link is secured
         * @param sslRootCert the CA of a verifying mode
         */
        public Pg(String host, int port, String database, String user, String password, int poolSize, SslMode sslMode, Optional<Path> sslRootCert) {
            this(host, port, database, user, password, poolSize, sslMode, sslRootCert, Durability.LOCAL);
        }

        /**
         * A plaintext link ({@link SslMode#DISABLE}, no CA) under the {@link Durability#LOCAL local} profile: in-process callers;
         * env parsing never uses it.
         *
         * @param host the server's host
         * @param port its port
         * @param database the database
         * @param user the user
         * @param password the user's password
         * @param poolSize the connections of both pools at most
         */
        public Pg(String host, int port, String database, String user, String password, int poolSize) {
            this(host, port, database, user, password, poolSize, SslMode.DISABLE, Optional.empty());
        }

        /**
         * The intake pool's share when {@code FLOTTILA_PG_POOL_INTAKE} is unset: max(1, min(5, {@code poolSize} / 2)) — 5 of 21 (the
         * default), 4 of 8, 2 of 4, 1 of 2 —, so the work pool keeps at least as many for {@code poolSize} ≥ 2.
         *
         * @param poolSize the connections of both pools
         * @return the intake pool's connections at most
         */
        public static int intakeDefault(int poolSize) {
            return Math.max(1, Math.min(5, poolSize / 2));
        }

        /**
         * The work pool's share: everything but the intake pool.
         *
         * @return {@code poolSize - intakePoolSize}
         */
        public int workPoolSize() {
            return poolSize - intakePoolSize;
        }

        /** The password redacted: a log line never carries it; the mode and the CA's path are shown. */
        @Override
        public String toString() {
            return "Pg[host=" + host + ", port=" + port + ", database=" + database + ", user=" + user + ", password=***, poolSize=" + poolSize
                    + ", sslMode=" + sslMode + ", sslRootCert=" + sslRootCert + ", durability=" + durability + ", intakePoolSize=" + intakePoolSize
                    + ", poolWait=" + poolWait + ", poolQueue=" + poolQueue + "]";
        }
    }

    /**
     * The intake's group commit (CORE-6b, D-109): saves waiting while a group is in flight go together, in one transaction.
     *
     * @param max members of a group at most; 1 = no grouping
     * @param bytes the bytes a group binds at most (a member above it goes alone)
     * @param window how long a partial group waits for members while a group is in flight; zero = until a group completes
     * @param concurrency groups in flight at most, each on a connection of its own
     * @since 0.1
     */
    public record IntakeGroup(int max, long bytes, Duration window, int concurrency) {

        /**
         * A group of one per save, sent at once — as many in flight as the largest pool (1024); the pool's own wait bounds them: the
         * intake built without a group.
         */
        public static final IntakeGroup SINGLE = new IntakeGroup(1, 64 * MIB, Duration.ZERO, 1024);

        /**
         * Groups in flight at most over an intake pool of {@code intakePoolSize}: max(1, intakePoolSize − 1), one connection left for
         * the intake's other statements (D-130 draft).
         */
        static int bound(int intakePoolSize) {
            return Math.max(1, intakePoolSize - 1);
        }

        /** The draft defaults (64, 1 MiB, PT0.001S) with min(4, {@link #bound}) groups in flight over an intake pool of {@code intakePoolSize}. */
        public static IntakeGroup defaults(int intakePoolSize) {
            return new IntakeGroup(64, MIB, Duration.ofMillis(1), Math.min(4, bound(intakePoolSize)));
        }
    }

    /**
     * The blob store's tiers. A body below the tree limit is one buffer, and so is one in the memory tier: both are at most
     * {@link Integer#MAX_VALUE}; the memory tier is never above the budget.
     *
     * @param treeLimit {@code FLOTTILA_BLOB_TREE_LIMIT} (8 MiB)
     * @param memoryTier {@code FLOTTILA_BLOB_MEMORY_TIER} (1 MiB, at least 4096)
     * @param memoryBudget {@code FLOTTILA_BLOB_MEMORY_BUDGET} (256 MiB)
     * @param durableMax {@code FLOTTILA_BLOB_DURABLE_MAX} (1 MiB, at most 64 MiB): the largest max body an at-least-once flow
     *                   may declare (D-058, D-059)
     * @since 0.1
     */
    public record Blobs(long treeLimit, long memoryTier, long memoryBudget, long durableMax) {}

    /**
     * Where package archives are stored, each once under its SHA-256 (D-070); a deployed node refuses {@link Store#DIR}.
     *
     * @param store {@code FLOTTILA_ARCHIVE_STORE}, {@code dir} or {@code s3} (dir)
     * @param dir {@code FLOTTILA_ARCHIVE_DIR} ({@code <dataDir>/archives}): the dir store's directory
     * @since 0.1
     */
    public record Archives(Store store, Path dir) {
        /** The archive store a node uses. */
        public enum Store {
            /** Files in {@link Archives#dir()}: dev and ci only. */
            DIR,
            /** Objects in an S3 bucket ({@link S3}). */
            S3
        }
    }

    /**
     * The S3 archive store's bucket and how to reach it (D-070). Without keys the client takes the AWS SDK's default credentials
     * chain, which reads the process environment and the instance's role: the one place the node's credentials do not come from
     * this record.
     *
     * @param bucket {@code FLOTTILA_S3_BUCKET}, required
     * @param region {@code FLOTTILA_S3_REGION}, required
     * @param endpoint {@code FLOTTILA_S3_ENDPOINT}, an absolute http or https URI (none: the SDK's endpoint for the region); a
     *                 deployed node refuses http
     * @param pathStyle {@code FLOTTILA_S3_PATH_STYLE}, true or false (false)
     * @param prefix {@code FLOTTILA_S3_PREFIX} (flottila): archives are the objects {@code <prefix>/archives/<sha256>.zip}
     * @param accessKey {@code FLOTTILA_S3_ACCESS_KEY}, given together with the secret key or not at all
     * @param secretKey {@code FLOTTILA_S3_SECRET_KEY}, given together with the access key or not at all
     * @since 0.1
     */
    public record S3(String bucket, String region, Optional<URI> endpoint, boolean pathStyle, String prefix, Optional<String> accessKey,
                     Optional<String> secretKey) {
        /** The secret key redacted: a log line never carries it. */
        @Override
        public String toString() {
            return "S3[bucket=" + bucket + ", region=" + region + ", endpoint=" + endpoint + ", pathStyle=" + pathStyle + ", prefix=" + prefix
                    + ", accessKey=" + accessKey + ", secretKey=" + secretKey.map(redacted -> "***") + "]";
        }
    }

    /**
     * Where a polling consumer's lease is held and for how long (D-083): the node runs the consumer only while it holds the lease.
     *
     * @param valkey {@code FLOTTILA_VALKEY_URI}, a {@code redis://} or {@code rediss://} URI with a host (none: the leases are held
     *               in the node's memory — one node alone; two such nodes on one endpoint both poll)
     * @param ttl {@code FLOTTILA_POLLER_LEASE_TTL}, ISO-8601 in whole milliseconds, more than zero and at most PT10M (PT30S): how
     *            long a hold lasts
     * @param renew {@code FLOTTILA_POLLER_LEASE_RENEW}, ISO-8601 in whole milliseconds, more than zero and at most PT10M, at most
     *              half the ttl (PT10S): how often the holder holds again
     * @since 0.1
     */
    public record Leases(Optional<URI> valkey, Duration ttl, Duration renew) {
        /** No Valkey, ttl PT30S, renew PT10S. */
        static final Leases DEFAULTS = new Leases(Optional.empty(), Duration.ofSeconds(30), Duration.ofSeconds(10));

        /** The Valkey URI as {@link #shown}: a log line never carries its user info or its query. */
        @Override
        public String toString() {
            return "Leases[valkey=" + valkey.map(Leases::shown) + ", ttl=" + ttl + ", renew=" + renew + "]";
        }

        /**
         * {@code uri} (one {@link #from} accepted: it has a host) as {@code scheme://host[:port][/db]}, built from its parsed parts:
         * its user info, query and fragment, and a path that is no {@code /<digits>} database, are left out — each may carry a
         * password (a {@code user:password@} as well as a {@code ?password=}).
         */
        static String shown(URI uri) {
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String db = DB.matcher(uri.getPath()).matches() ? uri.getPath() : "";
            return uri.getScheme() + "://" + uri.getHost() + port + db;
        }
    }

    /**
     * How many sends each target system may have in flight and how long a send waits for a permit (D-099), the node's one
     * {@link Targets} is built from: the {@code defaults} of every target, and the {@code overrides} by target key, each with the
     * default maxWait. Never unlimited: maxCalls 1 to 10000, maxWait PT0.001S to PT5M.
     *
     * @param defaults {@code FLOTTILA_TARGET_MAX_CALLS} (16) and {@code FLOTTILA_TARGET_MAX_WAIT} (PT5S)
     * @param overrides {@code FLOTTILA_TARGET_LIMITS}, {@code <target>=<n>,...} (none): each key in the form
     *                  {@link io.flottila.api.Component#target} names a target — {@code scheme://host:port}, lower-cased, the default
     *                  port of http (80), https (443) and sftp (22) filled in, an IPv6 literal in its brackets; another scheme needs
     *                  its port — so it matches what a component answers
     * @since 0.1
     */
    public record TargetLimits(Targets.Limit defaults, Map<String, Targets.Limit> overrides) {
        /** 16 calls, PT5S ({@link Targets#DEFAULTS}), no override. */
        static final TargetLimits DEFAULTS = new TargetLimits(Targets.DEFAULTS, Map.of());

        public TargetLimits {
            Objects.requireNonNull(defaults, "defaults");
            overrides = Map.copyOf(overrides);
        }
    }

    private static final long MIB = 1024L * 1024;
    /** The least FLOTTILA_BLOB_MEMORY_TIER: the least FLOTTILA_JOURNAL_RECORD_MAX, which is at most the tier (CORE-4c1 r1). */
    private static final long MEMORY_TIER_MIN = 4096;
    /** FLOTTILA_JOURNAL_RECORD_MAX by default, or the memory tier when that is smaller. */
    private static final long RECORD_MAX_DEFAULT = 256 * 1024;
    private static final long GIB = 1024 * MIB;
    private static final Pattern NODE_ID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Set<String> HTTP = Set.of("http", "https");
    private static final Set<String> REDIS = Set.of("redis", "rediss");
    /** The {@code FLOTTILA_PG_HOST} values that default to {@code FLOTTILA_PG_SSL_MODE=disable} (D-124): written as-is, never resolved. */
    private static final Set<String> PG_LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");
    /** The one path of a Valkey URI that is printed: its database number. */
    private static final Pattern DB = Pattern.compile("/\\d+");
    /** The longest ttl and renew period of a poller lease. */
    private static final Duration LEASE_MAX = Duration.ofMinutes(10);
    /** The most calls in flight one target may be given (D-099). */
    private static final int TARGET_MAX_CALLS = 10_000;
    /** A target key: {@code scheme://host[:port]}, the host a name or a bracketed IPv6 literal; nothing after the authority. */
    private static final Pattern TARGET = Pattern.compile("([a-z][a-z0-9+.-]*)://(\\[[0-9a-f:.]+]|[^\\[\\]/:?#@=,\\s]+)(?::([0-9]{1,5}))?");
    /** The schemes whose target key may leave out the port, and the port it then names (Component.target, D-099). */
    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443, "sftp", 22);
    /** The retention job's defaults (D-088): a max age of P7D, an interval of PT1H, batches of 1000 rows. */
    private static final Retention.Config RETENTION_DEFAULTS = new Retention.Config(Duration.ofDays(7), Duration.ofHours(1), 1000);
    /** The shortest and the longest CPU-time sampling period (D-094). */
    private static final Duration CPU_SAMPLING_MIN = Duration.ofMillis(1);
    private static final Duration CPU_SAMPLING_MAX = Duration.ofMillis(100);
    private static final Duration GROUP_WINDOW_MAX = Duration.ofMillis(50);
    /** The node lease's defaults and bound (D-101 (1)). */
    private static final Duration NODE_LEASE_DEFAULT = Duration.ofSeconds(30);
    private static final Duration NODE_LEASE_RENEW_DEFAULT = Duration.ofSeconds(5);
    private static final Duration NODE_LEASE_MAX = Duration.ofMinutes(10);
    private static final int RETRIES_COUNT_MAX = 100;
    private static final Duration RETRIES_INTERVAL_MIN = Duration.ofSeconds(1);
    private static final Duration RETRIES_INTERVAL_MAX = Duration.ofHours(24);
    private static final Duration POOL_WAIT_MIN = Duration.ofMillis(1);
    private static final Duration POOL_WAIT_MAX = Duration.ofMinutes(1);
    private static final int POOL_QUEUE_MAX = 65536;
    private static final String PEER_BIND = "0.0.0.0";
    /** The most {@code FLOTTILA_JOURNAL_COPIES}: the owner's and one holder's (CORE-5 O2, draft). */
    private static final int COPIES_MAX = 2;

    /**
     * Reads {@code env} only (never {@link System#getenv()}): the node's configuration, or the refusal naming the key; its
     * {@code FLOTTILA_SECRET_*} variables are the node's secrets ({@link Secrets#fromEnv}), its {@code FLOTTILA_JOURNAL_KEY*}
     * variables its master key source ({@link MasterKeySource#fromEnv}).
     */
    public static NodeConfig from(Map<String, String> env) {
        String name = env.get("FLOTTILA_ENV");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("FLOTTILA_ENV must name the environment (dev, ci, test, prod, ...)");
        String nodeId = env.getOrDefault("FLOTTILA_NODE_ID", env.get("HOSTNAME"));
        if (nodeId == null || !NODE_ID.matcher(nodeId).matches()) {
            throw new IllegalArgumentException("FLOTTILA_NODE_ID (else $HOSTNAME) must be a DNS label of at most 63 lower-case letters, digits and inner dashes, was "
                    + (nodeId == null ? "absent" : "'" + nodeId + "'"));
        }
        AdminServer.Config admin = new AdminServer.Config(env.getOrDefault("FLOTTILA_ADMIN_HOST", "127.0.0.1"),
                (int) number(env, "FLOTTILA_ADMIN_PORT", 8081, 0, 65535), Optional.ofNullable(env.get("FLOTTILA_ADMIN_TOKEN")));
        String pgHost = env.getOrDefault("FLOTTILA_PG_HOST", "localhost");
        int poolSize = (int) number(env, "FLOTTILA_PG_POOL_SIZE", 21, 2, 1024);
        int intakePoolSize = (int) number(env, "FLOTTILA_PG_POOL_INTAKE", Pg.intakeDefault(poolSize), 1, 1023);
        Pg pg = new Pg(pgHost, (int) number(env, "FLOTTILA_PG_PORT", 5432, 1, 65535),
                env.getOrDefault("FLOTTILA_PG_DATABASE", "flottila"), env.getOrDefault("FLOTTILA_PG_USER", "flottila"),
                env.getOrDefault("FLOTTILA_PG_PASSWORD", ""), poolSize, sslMode(env, pgHost), sslRootCert(env),
                durability(env, deployed(name)), intakePoolSize, duration(env, "FLOTTILA_PG_POOL_WAIT", Pg.POOL_WAIT_DEFAULT),
                (int) number(env, "FLOTTILA_PG_POOL_QUEUE", Pg.POOL_QUEUE_DEFAULT, 1, POOL_QUEUE_MAX));   // the constructor checks the range too (an in-process Pg)
        long memoryTier = number(env, "FLOTTILA_BLOB_MEMORY_TIER", MIB, MEMORY_TIER_MIN, Integer.MAX_VALUE);
        long memoryBudget = number(env, "FLOTTILA_BLOB_MEMORY_BUDGET", 256 * MIB, 1, Long.MAX_VALUE);
        if (memoryTier > memoryBudget) {
            throw new IllegalArgumentException("FLOTTILA_BLOB_MEMORY_TIER " + memoryTier + " is above FLOTTILA_BLOB_MEMORY_BUDGET " + memoryBudget);
        }
        Blobs blobs = new Blobs(number(env, "FLOTTILA_BLOB_TREE_LIMIT", 8 * MIB, 1, Integer.MAX_VALUE), memoryTier, memoryBudget,
                number(env, "FLOTTILA_BLOB_DURABLE_MAX", MIB, 1, 64 * MIB));
        Path dataDir = Path.of(env.getOrDefault("FLOTTILA_DATA_DIR", "/var/lib/flottila"));
        Archives archives = new Archives(archiveStore(env), Path.of(env.getOrDefault("FLOTTILA_ARCHIVE_DIR", dataDir.resolve("archives").toString())));
        Optional<S3> s3 = archives.store() == Archives.Store.S3 ? Optional.of(s3(env)) : Optional.empty();
        return new NodeConfig(nodeId, name, admin, dataDir, stopDeadline(env), number(env, "FLOTTILA_MAX_PACKAGE_BYTES", 64 * MIB, 1, GIB), pg, blobs,
                archives, s3, Secrets.fromEnv(env), leases(env), retention(env), samplingPeriod(env), targets(env), journal(env, dataDir, nodeId, memoryTier),
                MasterKeySource.fromEnv(env), journalSweep(env), intakeGroup(env, pg.intakePoolSize()), duration(env, "FLOTTILA_NODE_LEASE", NODE_LEASE_DEFAULT),
                duration(env, "FLOTTILA_NODE_LEASE_RENEW", NODE_LEASE_RENEW_DEFAULT), retries(env), peer(env, nodeId),
                (int) number(env, "FLOTTILA_JOURNAL_COPIES", deployed(name) ? COPIES_MAX : 1, 1, COPIES_MAX));
    }

    /** The {@code FLOTTILA_PEER_*} keys (CORE-5a S11): each range-checked naming its key. */
    private static PeerConfig peer(Map<String, String> env, String nodeId) {
        return new PeerConfig(env.getOrDefault("FLOTTILA_PEER_BIND", PEER_BIND), (int) number(env, "FLOTTILA_PEER_PORT", PeerConfig.PORT, 0, 65535),
                env.getOrDefault("FLOTTILA_PEER_HOST", nodeId),
                span(env, "FLOTTILA_PEER_TIMEOUT", PeerConfig.TIMEOUT, PeerConfig.TIMEOUT_MIN.toString(), PeerConfig.TIMEOUT_MAX.toString()),
                (int) number(env, "FLOTTILA_PEER_READS", PeerConfig.READS, 1, PeerConfig.READS_MAX));
    }

    /** The {@code FLOTTILA_RETRIES_*} keys (CORE-14a P1): each range-checked naming its key; no growth, the interval its own cap. */
    private static RetryPolicy retries(Map<String, String> env) {
        int count = (int) number(env, "FLOTTILA_RETRIES_COUNT", FlowBuilder.DEFAULT_RETRIES.attempts(), 0, RETRIES_COUNT_MAX);
        Duration interval = span(env, "FLOTTILA_RETRIES_INTERVAL", FlowBuilder.DEFAULT_RETRIES.initial(), RETRIES_INTERVAL_MIN.toString(),
                RETRIES_INTERVAL_MAX.toString());
        return new RetryPolicy(count, interval, interval, 1.0);
    }

    /** {@code FLOTTILA_PG_DURABILITY} as written; unset, {@code ha} when {@code deployed}, else {@code local} (D-127 draft). */
    private static Pg.Durability durability(Map<String, String> env, boolean deployed) {
        String raw = env.get("FLOTTILA_PG_DURABILITY");
        if (raw == null) return deployed ? Pg.Durability.HA : Pg.Durability.LOCAL;
        for (Pg.Durability profile : Pg.Durability.values()) {
            if (profile.toString().equals(raw)) return profile;
        }
        throw new IllegalArgumentException("FLOTTILA_PG_DURABILITY must be local (a single server's commit) or ha (a commit that waits for a "
                + "synchronous standby), was '" + raw + "'");
    }

    /** {@code FLOTTILA_PG_SSL_MODE} as written; unset, {@code disable} on a loopback {@code host}, else {@code verify-full} (D-124). */
    private static Pg.SslMode sslMode(Map<String, String> env, String host) {
        String raw = env.get("FLOTTILA_PG_SSL_MODE");
        if (raw == null) return PG_LOOPBACK.contains(host) ? Pg.SslMode.DISABLE : Pg.SslMode.VERIFY_FULL;
        for (Pg.SslMode mode : Pg.SslMode.values()) {
            if (mode.toString().equals(raw)) return mode;
        }
        throw new IllegalArgumentException("FLOTTILA_PG_SSL_MODE must be disable, verify-ca or verify-full (TLS without verification is not offered), was '"
                + raw + "'");
    }

    /**
     * {@code FLOTTILA_PG_SSL_ROOT_CERT} as given, once its first byte was read, under any mode: a missing file, a directory, a file
     * the node cannot read and an empty file (a CA that trusts nothing) are refused at start naming both keys, never at the first
     * handshake; whether a mode needs a CA at all, {@link Pg}'s constructor checks.
     */
    private static Optional<Path> sslRootCert(Map<String, String> env) {
        Optional<Path> ca = Optional.ofNullable(env.get("FLOTTILA_PG_SSL_ROOT_CERT")).map(Path::of);
        if (ca.isPresent()) {
            int first;
            try (InputStream in = Files.newInputStream(ca.get())) {
                first = in.read();
            } catch (IOException e) {
                throw new IllegalArgumentException("FLOTTILA_PG_SSL_ROOT_CERT " + ca.get() + " (the CA of FLOTTILA_PG_SSL_MODE) cannot be read: " + e, e);
            }
            if (first < 0) {
                throw new IllegalArgumentException("FLOTTILA_PG_SSL_ROOT_CERT " + ca.get() + " (the CA of FLOTTILA_PG_SSL_MODE) is empty: a CA without a"
                        + " certificate trusts nothing");
            }
        }
        return ca;
    }

    /**
     * The {@code FLOTTILA_INTAKE_GROUP_*} keys over an intake pool of {@code intakePoolSize}: each range-checked naming its key; the
     * concurrency's bound by the intake pool is the record's ({@link NodeConfig#NodeConfig}).
     */
    private static IntakeGroup intakeGroup(Map<String, String> env, int intakePoolSize) {
        IntakeGroup defaults = IntakeGroup.defaults(intakePoolSize);
        return new IntakeGroup((int) number(env, "FLOTTILA_INTAKE_GROUP_MAX", defaults.max(), 1, 1024),
                number(env, "FLOTTILA_INTAKE_GROUP_BYTES", defaults.bytes(), 64 * 1024, 64 * MIB), groupWindow(env, defaults.window()),
                (int) number(env, "FLOTTILA_INTAKE_GROUP_CONCURRENCY", defaults.concurrency(), 1, 64));
    }

    /** {@code FLOTTILA_INTAKE_GROUP_WINDOW}: an ISO-8601 duration in whole milliseconds from PT0S to PT0.05S, else refused naming the key. */
    private static Duration groupWindow(Map<String, String> env, Duration fallback) {
        String raw = env.get("FLOTTILA_INTAKE_GROUP_WINDOW");
        if (raw == null) return fallback;
        Duration window;
        try {
            window = Duration.parse(raw);
        } catch (DateTimeParseException notIso) {
            window = Duration.ofDays(1);   // refused below, as any value out of range
        }
        if (window.isNegative() || window.compareTo(GROUP_WINDOW_MAX) > 0 || window.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("FLOTTILA_INTAKE_GROUP_WINDOW must be an ISO-8601 duration in whole milliseconds from PT0S to "
                    + GROUP_WINDOW_MAX + ", was '" + raw + "'");
        }
        return window;
    }

    /** The {@code FLOTTILA_JOURNAL_SWEEP_*} and {@code FLOTTILA_JOURNAL_KEEP_MAX_AGE} keys; no ISO-8601 duration is refused naming its key. */
    private static SweepConfig journalSweep(Map<String, String> env) {
        return new SweepConfig(duration(env, "FLOTTILA_JOURNAL_SWEEP_INTERVAL", SweepConfig.DEFAULTS.interval()),
                duration(env, "FLOTTILA_JOURNAL_SWEEP_GRACE", SweepConfig.DEFAULTS.grace()),
                duration(env, "FLOTTILA_JOURNAL_KEEP_MAX_AGE", SweepConfig.DEFAULTS.keepMaxAge()));
    }

    /**
     * The {@code FLOTTILA_JOURNAL_*} limits and directory, each with its design default (CORE-3); a value that is no number or
     * no ISO-8601 duration is refused here naming its key, a range by {@link JournalConfig} naming its key.
     */
    private static JournalConfig journal(Map<String, String> env, Path dataDir, String nodeId, long memoryTier) {
        Path dir = Path.of(env.getOrDefault("FLOTTILA_JOURNAL_DIR", dataDir.resolve("journal").toString()));
        return new JournalConfig(dir, number(env, "FLOTTILA_JOURNAL_SEGMENT_BYTES", 64 * MIB, Long.MIN_VALUE, Long.MAX_VALUE),
                (int) number(env, "FLOTTILA_JOURNAL_SEGMENT_RECORDS", 65_536, 1, Integer.MAX_VALUE),
                duration(env, "FLOTTILA_JOURNAL_SEGMENT_AGE", Duration.ofSeconds(1)),
                number(env, "FLOTTILA_JOURNAL_PENDING_BYTES", 16 * MIB, Long.MIN_VALUE, Long.MAX_VALUE),
                duration(env, "FLOTTILA_JOURNAL_APPEND_WAIT", Duration.ofSeconds(5)),
                number(env, "FLOTTILA_JOURNAL_RECORD_MAX", Math.min(RECORD_MAX_DEFAULT, memoryTier), Long.MIN_VALUE, Long.MAX_VALUE), nodeId,
                number(env, "FLOTTILA_JOURNAL_MAX_BYTES", JournalConfig.MAX_BYTES, Long.MIN_VALUE, Long.MAX_VALUE),
                (int) number(env, "FLOTTILA_JOURNAL_READ_CHANNELS", JournalConfig.READ_CHANNELS, Integer.MIN_VALUE, Integer.MAX_VALUE),
                Optional.ofNullable(env.get("FLOTTILA_JOURNAL_ACCEPT_LOSS")));
    }

    /** {@code key}'s ISO-8601 duration, {@code fallback} when the key is absent; its range is the caller's to check. */
    private static Duration duration(Map<String, String> env, String key, Duration fallback) {
        String raw = env.get(key);
        if (raw == null) return fallback;
        try {
            return Duration.parse(raw);
        } catch (DateTimeParseException notIso) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 duration such as " + fallback + ", was '" + raw + "'");
        }
    }

    /**
     * The {@code FLOTTILA_TARGET_*} keys (D-099); a refusal names the key, and for a {@code FLOTTILA_TARGET_LIMITS} entry the entry —
     * by its text, or by its position only when the value holds an {@code @} anywhere (D-099 amended): userinfo, a password among
     * it, may hold a comma the split cuts at, so no entry's text is shown then, and neither is its target.
     */
    private static TargetLimits targets(Map<String, String> env) {
        int maxCalls = (int) number(env, "FLOTTILA_TARGET_MAX_CALLS", Targets.DEFAULTS.maxCalls(), 1, TARGET_MAX_CALLS);
        Duration maxWait = span(env, "FLOTTILA_TARGET_MAX_WAIT", Targets.DEFAULTS.maxWait(), "PT0.001S", "PT5M");
        String raw = env.getOrDefault("FLOTTILA_TARGET_LIMITS", "");
        Map<String, Targets.Limit> overrides = new LinkedHashMap<>();
        boolean secret = raw.indexOf('@') >= 0;   // decided on the whole value, before the split
        if (!raw.isBlank()) {
            String[] entries = raw.split(",", -1);
            for (int i = 0; i < entries.length; i++) {
                String entry = entries[i];
                String named = secret ? "entry " + (i + 1) + " of " + entries.length : "entry '" + entry + "'";
                int eq = entry.indexOf('=');
                String key = eq < 0 ? "" : canonicalTarget(entry.substring(0, eq).strip());
                int calls = eq < 0 ? 0 : limitCalls(entry.substring(eq + 1).strip());
                if (key.isEmpty() || calls == 0) {
                    throw new IllegalArgumentException("FLOTTILA_TARGET_LIMITS " + named + " must be <target>=<n>: a target scheme://host[:port]"
                            + " (a port for a scheme other than http, https and sftp) and n from 1 to " + TARGET_MAX_CALLS);
                }
                if (overrides.putIfAbsent(key, new Targets.Limit(calls, maxWait)) != null) {
                    throw new IllegalArgumentException("FLOTTILA_TARGET_LIMITS " + named + " names " + (secret ? "the target of an earlier entry" : key) + " again");
                }
            }
        }
        return new TargetLimits(new Targets.Limit(maxCalls, maxWait), overrides);
    }

    /**
     * {@code raw} as {@link io.flottila.api.Component#target} names a target — {@code scheme://host:port}, lower-cased, the default
     * port of http, https and sftp filled in — or empty when it is none: no scheme, userinfo, a path, a query, no port where the
     * scheme has no default, a port out of 1..65535.
     */
    private static String canonicalTarget(String raw) {
        Matcher m = TARGET.matcher(raw.toLowerCase(Locale.ROOT));
        if (!m.matches()) return "";
        String scheme = m.group(1);
        Integer port = m.group(3) == null ? DEFAULT_PORTS.get(scheme) : Integer.valueOf(m.group(3));
        if (port == null || port < 1 || port > 65535) return "";
        return scheme + "://" + m.group(2) + ":" + port;
    }

    /** {@code raw} as a target's maxCalls, 1 to {@link #TARGET_MAX_CALLS}; 0 when it is none. */
    private static int limitCalls(String raw) {
        try {
            int calls = Integer.parseInt(raw);
            return calls < 1 || calls > TARGET_MAX_CALLS ? 0 : calls;
        } catch (NumberFormatException notAnInt) {
            return 0;
        }
    }

    /**
     * {@code FLOTTILA_CPU_SAMPLING}: {@code off} (also when absent) is empty; else an ISO-8601 duration in whole milliseconds from
     * {@link #CPU_SAMPLING_MIN} to {@link #CPU_SAMPLING_MAX}; anything else is refused naming the key.
     */
    private static Optional<Duration> samplingPeriod(Map<String, String> env) {
        String raw = env.getOrDefault("FLOTTILA_CPU_SAMPLING", "off");
        if (raw.equals("off")) return Optional.empty();
        Duration period;
        try {
            period = Duration.parse(raw);
        } catch (DateTimeParseException notIso) {
            period = Duration.ZERO;   // refused below, as any value out of range
        }
        if (period.compareTo(CPU_SAMPLING_MIN) < 0 || period.compareTo(CPU_SAMPLING_MAX) > 0 || period.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("FLOTTILA_CPU_SAMPLING must be off or an ISO-8601 duration in whole milliseconds from " + CPU_SAMPLING_MIN
                    + " to " + CPU_SAMPLING_MAX + " (the CPU-time sampling period, D-094), was '" + raw + "'");
        }
        return Optional.of(period);
    }

    /** The {@code FLOTTILA_RETENTION_*} keys; a refusal names the key and its bounds, never the value. */
    private static Retention.Config retention(Map<String, String> env) {
        Duration maxAge = span(env, "FLOTTILA_RETENTION_MAX_AGE", RETENTION_DEFAULTS.maxAge(), "PT1H", "P366D");
        Duration interval = span(env, "FLOTTILA_RETENTION_INTERVAL", RETENTION_DEFAULTS.interval(), "PT1M", "P1D");
        String raw = env.get("FLOTTILA_RETENTION_BATCH");
        int batch = RETENTION_DEFAULTS.batch();
        if (raw != null) {
            try {
                batch = Integer.parseInt(raw);
            } catch (NumberFormatException notAnInt) {
                batch = 0;   // refused below: a word, a fraction or beyond an int is no batch either
            }
            if (batch < 1 || batch > 10_000) {
                throw new IllegalArgumentException("FLOTTILA_RETENTION_BATCH must be a whole number of rows from 1 to 10000 (the value is not shown)");
            }
        }
        return new Retention.Config(maxAge, interval, batch);
    }

    /**
     * {@code key}'s ISO-8601 duration within [{@code min}, {@code max}] (ISO-8601 both), {@code fallback} when the key is absent; a
     * refusal names the key and the bounds, never the value.
     */
    private static Duration span(Map<String, String> env, String key, Duration fallback, String min, String max) {
        String raw = env.get(key);
        if (raw == null) return fallback;
        Duration value;
        try {
            value = Duration.parse(raw);
        } catch (DateTimeParseException notIso) {
            value = Duration.ZERO;   // refused below, as any value out of range
        }
        if (value.compareTo(Duration.parse(min)) < 0 || value.compareTo(Duration.parse(max)) > 0) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 duration from " + min + " to " + max + " (the value is not shown)");
        }
        return value;
    }

    /** The {@code FLOTTILA_VALKEY_URI} and {@code FLOTTILA_POLLER_LEASE_*} keys. */
    private static Leases leases(Map<String, String> env) {
        Optional<URI> valkey = Optional.ofNullable(env.get("FLOTTILA_VALKEY_URI")).map(NodeConfig::valkey);
        Duration ttl = leaseDuration(env, "FLOTTILA_POLLER_LEASE_TTL", Leases.DEFAULTS.ttl());
        Duration renew = leaseDuration(env, "FLOTTILA_POLLER_LEASE_RENEW", Leases.DEFAULTS.renew());
        if (renew.multipliedBy(2).compareTo(ttl) > 0) {
            throw new IllegalArgumentException("FLOTTILA_POLLER_LEASE_RENEW " + renew + " is more than half FLOTTILA_POLLER_LEASE_TTL " + ttl
                    + ": the holder must hold again at least twice within a ttl");
        }
        return new Leases(valkey, ttl, renew);
    }

    /**
     * {@code FLOTTILA_VALKEY_URI}: a redis:// or rediss:// URI with a host; a refusal never shows the value, which may carry a
     * password.
     */
    private static URI valkey(String raw) {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException notAUri) {
            throw notValkey("it is no URI");
        }
        // a URI without a scheme has none: never in the set, and Set.contains takes no null
        if (!REDIS.contains(Objects.toString(uri.getScheme(), ""))) throw notValkey("its scheme is neither");
        // an unencoded '/', '?' or '#' in a password ends the authority there: parsed as a registry name, it has no host
        if (uri.getHost() == null) throw notValkey("it has no host (a '/', '?' or '#' in a password must be percent-encoded)");
        return uri;
    }

    private static IllegalArgumentException notValkey(String why) {
        return new IllegalArgumentException("FLOTTILA_VALKEY_URI must be a redis:// or rediss:// URI with a host, such as redis://valkey:6379, but "
                + why + " (the value is not shown: it may carry a password)");
    }

    /**
     * {@code key}'s ISO-8601 duration, more than zero and at most {@link #LEASE_MAX}, in whole milliseconds (a lease counts in them:
     * Valkey's PX); {@code fallback} when the key is absent.
     */
    private static Duration leaseDuration(Map<String, String> env, String key, Duration fallback) {
        String raw = env.get(key);
        if (raw == null) return fallback;
        Duration value;
        try {
            value = Duration.parse(raw);
        } catch (DateTimeParseException notIso) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 duration such as " + fallback + ", was '" + raw + "'");
        }
        if (!value.isPositive() || value.compareTo(LEASE_MAX) > 0) {
            throw new IllegalArgumentException(key + " must be more than zero and at most " + LEASE_MAX + ", was " + raw);
        }
        if (value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(key + " must be whole milliseconds (a lease counts in them), was " + raw);
        }
        return value;
    }

    private static Archives.Store archiveStore(Map<String, String> env) {
        String raw = env.getOrDefault("FLOTTILA_ARCHIVE_STORE", "dir");
        return switch (raw) {
            case "dir" -> Archives.Store.DIR;
            case "s3" -> Archives.Store.S3;
            default -> throw new IllegalArgumentException("FLOTTILA_ARCHIVE_STORE must be dir or s3, was '" + raw + "'");
        };
    }

    /** The {@code FLOTTILA_S3_*} keys of an s3 archive store. */
    private static S3 s3(Map<String, String> env) {
        String bucket = required(env, "FLOTTILA_S3_BUCKET");
        String region = required(env, "FLOTTILA_S3_REGION");
        Optional<URI> endpoint = Optional.ofNullable(env.get("FLOTTILA_S3_ENDPOINT")).map(NodeConfig::endpoint);
        boolean pathStyle = pathStyle(env.getOrDefault("FLOTTILA_S3_PATH_STYLE", "false"));
        Optional<String> accessKey = Optional.ofNullable(env.get("FLOTTILA_S3_ACCESS_KEY"));
        Optional<String> secretKey = Optional.ofNullable(env.get("FLOTTILA_S3_SECRET_KEY"));
        if (accessKey.isPresent() != secretKey.isPresent()) {
            throw new IllegalArgumentException((accessKey.isPresent() ? "FLOTTILA_S3_SECRET_KEY" : "FLOTTILA_S3_ACCESS_KEY") + " is missing: the S3 keys "
                    + "are given both or neither (neither: the AWS SDK's default credentials chain)");
        }
        return new S3(bucket, region, endpoint, pathStyle, env.getOrDefault("FLOTTILA_S3_PREFIX", "flottila"), accessKey, secretKey);
    }

    /** {@code key}'s value, which must be there and not blank. */
    private static String required(Map<String, String> env, String key) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(key + " must be set for FLOTTILA_ARCHIVE_STORE s3, was '" + raw + "'");
        return raw;
    }

    /** {@code FLOTTILA_S3_ENDPOINT}: an absolute http or https URI with a host. */
    private static URI endpoint(String raw) {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException notAUri) {
            throw notAnEndpoint(raw);
        }
        // a URI without a scheme (a bare host, a path) has none: never in the set, and Set.contains takes no null
        if (!HTTP.contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null) throw notAnEndpoint(raw);
        return uri;
    }

    private static IllegalArgumentException notAnEndpoint(String raw) {
        return new IllegalArgumentException("FLOTTILA_S3_ENDPOINT must be an absolute http or https URI with a host, such as https://s3.example.net, was '"
                + raw + "'");
    }

    private static boolean pathStyle(String raw) {
        return switch (raw) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("FLOTTILA_S3_PATH_STYLE must be true or false, was '" + raw + "'");
        };
    }

    /** {@code key}'s whole number within [{@code min}, {@code max}], {@code fallback} when the key is absent. */
    private static long number(Map<String, String> env, String key, long fallback, long min, long max) {
        String raw = env.get(key);
        if (raw == null) return fallback;
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(key + " must be a whole number, was '" + raw + "'");
        }
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", was " + value);
        return value;
    }

    private static Duration stopDeadline(Map<String, String> env) {
        String raw = env.get("FLOTTILA_STOP_DEADLINE");
        if (raw == null) return Duration.ofSeconds(10);
        Duration deadline;
        try {
            deadline = Duration.parse(raw);
        } catch (DateTimeParseException notIso) {
            throw new IllegalArgumentException("FLOTTILA_STOP_DEADLINE must be an ISO-8601 duration such as PT10S, was '" + raw + "'");
        }
        if (deadline.isNegative()) throw new IllegalArgumentException("FLOTTILA_STOP_DEADLINE must not be negative, was " + raw);
        return deadline;
    }
}
