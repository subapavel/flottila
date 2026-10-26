package io.flottila.runtime.node;

import io.flottila.runtime.admin.AdminServer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A node's whole configuration, read from its environment (D-057 b). Every key has a default except {@code FLOTTILA_ENV};
 * every refusal is an {@link IllegalArgumentException} whose message names the key. No {@code toString} here carries a secret:
 * {@link Pg} and {@link AdminServer.Config} redact the password and the token, and this record's prints theirs.
 *
 * @param nodeId {@code FLOTTILA_NODE_ID}, else {@code $HOSTNAME}: a DNS label (lower-case letters, digits and inner dashes, at most 63)
 * @param env {@code FLOTTILA_ENV}, required
 * @param admin {@code FLOTTILA_ADMIN_HOST} (127.0.0.1), {@code FLOTTILA_ADMIN_PORT} (8081, 0 binds any free port), {@code FLOTTILA_ADMIN_TOKEN}
 * @param dataDir {@code FLOTTILA_DATA_DIR} (/var/lib/flottila): the spool lives under it
 * @param stopDeadline {@code FLOTTILA_STOP_DEADLINE}, ISO-8601, not negative (PT10S)
 * @param maxPackageBytes {@code FLOTTILA_MAX_PACKAGE_BYTES}, 1 byte to 1 GiB (64 MiB)
 * @param pg the {@code FLOTTILA_PG_*} keys
 * @param blobs the {@code FLOTTILA_BLOB_*} keys
 * @since 0.1
 */
public record NodeConfig(String nodeId, String env, AdminServer.Config admin, Path dataDir, Duration stopDeadline,
                         long maxPackageBytes, Pg pg, Blobs blobs) {

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
     * @since 0.1
     */
    public record Blobs(long treeLimit, long memoryTier, long memoryBudget) {}

    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;
    private static final Pattern NODE_ID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    /** Reads {@code env} only (never {@link System#getenv()}): the node's configuration, or the refusal naming the key. */
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
        Blobs blobs = new Blobs(number(env, "FLOTTILA_BLOB_TREE_LIMIT", 8 * MIB, 1, Integer.MAX_VALUE), memoryTier, memoryBudget);
        return new NodeConfig(nodeId, name, admin,
                Path.of(env.getOrDefault("FLOTTILA_DATA_DIR", "/var/lib/flottila")), stopDeadline(env),
                number(env, "FLOTTILA_MAX_PACKAGE_BYTES", 64 * MIB, 1, GIB), pg, blobs);
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
