package io.flottila.runtime.node;

import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.deploy.Secrets;
import io.flottila.runtime.store.Retention;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A node's whole configuration, read from its environment (D-057 b). Every key has a default except {@code FLOTTILA_ENV};
 * every refusal is an {@link IllegalArgumentException} whose message names the key. No {@code toString} here carries a secret:
 * {@link Pg}, {@link AdminServer.Config} and {@link S3} redact the password, the token and the secret key, {@link Leases} prints of
 * the Valkey URI its scheme, host, port and database only, {@link Secrets} names its variables only, and this record's prints theirs.
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
 * @since 0.1
 */
public record NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline,
                         long maxPackageBytes, Pg pg, Blobs blobs, Archives archives, Optional<S3> s3, Secrets secrets, Leases leases,
                         Retention.Config retention) {

    /** The S3 settings and the archive store say one fact: an s3 store has them, a dir store has none. */
    public NodeConfig {
        Objects.requireNonNull(archives, "archives");
        Objects.requireNonNull(s3, "s3");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(retention, "retention");
        if ((archives.store() == Archives.Store.S3) != s3.isPresent()) {
            throw new IllegalArgumentException("FLOTTILA_ARCHIVE_STORE " + archives.store() + " with the S3 settings " + s3
                    + ": an s3 archive store has them, a dir store none");
        }
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
        return new NodeConfig(nodeId, env, admin, dataDir, stopDeadline, maxPackageBytes, pg, blobs, archives, s3, secrets, leases, retention);
    }

    /** Every environment but {@code dev} and {@code ci} (ARCHITECTURE §11: {@code test} is deployed); a deployed node refuses a mock binding. */
    public boolean deployed() {
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
     * @param poolSize {@code FLOTTILA_PG_POOL_SIZE}, 1 to 1024 (16)
     * @since 0.1
     */
    public record Pg(String host, int port, String database, String user, String password, int poolSize) {
        /** The password redacted: a log line never carries it. */
        @Override
        public String toString() {
            return "Pg[host=" + host + ", port=" + port + ", database=" + database + ", user=" + user + ", password=***, poolSize=" + poolSize + "]";
        }
    }

    /**
     * The blob store's tiers. A body below the tree limit is one buffer, and so is one in the memory tier: both are at most
     * {@link Integer#MAX_VALUE}; the memory tier is never above the budget.
     *
     * @param treeLimit {@code FLOTTILA_BLOB_TREE_LIMIT} (8 MiB)
     * @param memoryTier {@code FLOTTILA_BLOB_MEMORY_TIER} (1 MiB)
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

    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;
    private static final Pattern NODE_ID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Set<String> HTTP = Set.of("http", "https");
    private static final Set<String> REDIS = Set.of("redis", "rediss");
    /** The one path of a Valkey URI that is printed: its database number. */
    private static final Pattern DB = Pattern.compile("/\\d+");
    /** The longest ttl and renew period of a poller lease. */
    private static final Duration LEASE_MAX = Duration.ofMinutes(10);
    /** The retention job's defaults (D-088): a max age of P7D, an interval of PT1H, batches of 1000 rows. */
    private static final Retention.Config RETENTION_DEFAULTS = new Retention.Config(Duration.ofDays(7), Duration.ofHours(1), 1000);

    /**
     * Reads {@code env} only (never {@link System#getenv()}): the node's configuration, or the refusal naming the key; its
     * {@code FLOTTILA_SECRET_*} variables are the node's secrets ({@link Secrets#fromEnv}).
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
        Pg pg = new Pg(env.getOrDefault("FLOTTILA_PG_HOST", "localhost"), (int) number(env, "FLOTTILA_PG_PORT", 5432, 1, 65535),
                env.getOrDefault("FLOTTILA_PG_DATABASE", "flottila"), env.getOrDefault("FLOTTILA_PG_USER", "flottila"),
                env.getOrDefault("FLOTTILA_PG_PASSWORD", ""), (int) number(env, "FLOTTILA_PG_POOL_SIZE", 16, 1, 1024));
        long memoryTier = number(env, "FLOTTILA_BLOB_MEMORY_TIER", MIB, 0, Integer.MAX_VALUE);
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
                archives, s3, Secrets.fromEnv(env), leases(env), retention(env));
    }

    /** The {@code FLOTTILA_RETENTION_*} keys; a refusal names the key and its bounds, never the value. */
    private static Retention.Config retention(Map<String, String> env) {
        Duration maxAge = retentionSpan(env, "FLOTTILA_RETENTION_MAX_AGE", RETENTION_DEFAULTS.maxAge(), "PT1H", "P366D");
        Duration interval = retentionSpan(env, "FLOTTILA_RETENTION_INTERVAL", RETENTION_DEFAULTS.interval(), "PT1M", "P1D");
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
    private static Duration retentionSpan(Map<String, String> env, String key, Duration fallback, String min, String max) {
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
