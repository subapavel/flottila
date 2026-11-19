package dev.rail8.runtime.git;

import org.eclipse.jgit.lib.Repository;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a node pool's package repositories are and how they are read: the {@code RAIL8_GIT_*} settings. Which schemes an environment
 * may use is the node's configuration to check; this record holds every setting to the rules that hold in any environment. No
 * refusal repeats the base, and {@link #toString()} never shows the token.
 *
 * @param base {@code RAIL8_GIT_BASE}: an absolute {@code https://} URI with a host, or a {@code file://} URI, with no user
 *             information, query or fragment; the repositories' URIs are the base followed by their names, so a base whose path does
 *             not end in {@code /} is given one
 * @param path {@code RAIL8_GIT_PATH}: the package's folder in each repository, relative, its segments neither empty nor {@code .}
 *             nor {@code ..}; empty for the root
 * @param ref {@code RAIL8_GIT_REF}: a full branch or tag name ({@code refs/heads/…}, {@code refs/tags/…}), the same in every
 *            repository; when it is set, every commit a deploy fetches must be reachable from it
 * @param credentials {@code RAIL8_GIT_USER} and {@code RAIL8_GIT_TOKEN}: sent only as the credentials of the base's host
 * @param timeout {@code RAIL8_GIT_TIMEOUT}: the longest the fetches of one deploy, or of one package's restore, may take together,
 *                PT1S to PT10M
 * @param maxBytes {@code RAIL8_GIT_MAX_BYTES}: the largest the node's git cache may grow, 1 MiB to 64 GiB
 * @since 0.1
 */
public record GitSettings(URI base, String path, Optional<String> ref, Optional<Credentials> credentials, Duration timeout, long maxBytes) {

    /** {@code RAIL8_GIT_TIMEOUT} unset. */
    public static final Duration TIMEOUT_DEFAULT = Duration.ofSeconds(60);
    public static final Duration TIMEOUT_MIN = Duration.ofSeconds(1);
    public static final Duration TIMEOUT_MAX = Duration.ofMinutes(10);
    /** {@code RAIL8_GIT_MAX_BYTES} unset: 1 GiB. */
    public static final long MAX_BYTES_DEFAULT = 1L << 30;
    public static final long MAX_BYTES_MIN = 1L << 20;
    public static final long MAX_BYTES_MAX = 64L << 30;

    public GitSettings {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(timeout, "timeout");
        base = checked(base);
        if (!path.isEmpty()) {
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains("\\")) {
                    throw new IllegalArgumentException("RAIL8_GIT_PATH must be a relative folder such as examples/demo, with no empty, '.' or '..'"
                            + " segment and no backslash, was '" + path + "'");
                }
            }
        }
        ref.ifPresent(name -> {
            if (!(name.startsWith("refs/heads/") || name.startsWith("refs/tags/")) || !Repository.isValidRefName(name)) {
                throw new IllegalArgumentException("RAIL8_GIT_REF must be a full branch or tag name such as refs/heads/release, was '" + name + "'");
            }
        });
        if (timeout.compareTo(TIMEOUT_MIN) < 0 || timeout.compareTo(TIMEOUT_MAX) > 0) {
            throw new IllegalArgumentException("RAIL8_GIT_TIMEOUT " + timeout + " must be from " + TIMEOUT_MIN + " to " + TIMEOUT_MAX);
        }
        if (maxBytes < MAX_BYTES_MIN || maxBytes > MAX_BYTES_MAX) {
            throw new IllegalArgumentException("RAIL8_GIT_MAX_BYTES " + maxBytes + " must be from " + MAX_BYTES_MIN + " to " + MAX_BYTES_MAX);
        }
    }

    /** {@code base} with the defaults of the other settings: the root of each repository, no ref, no credentials, PT60S, 1 GiB. */
    public GitSettings(URI base) {
        this(base, "", Optional.empty(), Optional.empty(), TIMEOUT_DEFAULT, MAX_BYTES_DEFAULT);
    }

    /** Whether the base is a {@code file://} URI: repositories in a folder, read with no network and no credentials. */
    public boolean local() {
        return base.getScheme().equals("file");
    }

    /** The URI of the repository {@code name}, one the name rule accepted: the base followed by the name. */
    URI repository(String name) {
        return base.resolve(name);
    }

    private static URI checked(URI base) {
        if (base.getRawUserInfo() != null) {
            throw refused("it carries user information: give the user and the token as RAIL8_GIT_USER and RAIL8_GIT_TOKEN");
        }
        String scheme = Objects.toString(base.getScheme(), "");
        if (!(scheme.equals("https") && base.getHost() != null) && !scheme.equals("file")) {
            throw refused("it is neither an https:// URI with a host nor a file:// URI");
        }
        if (base.isOpaque() || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw refused("it is not hierarchical, or it has a query or a fragment");
        }
        String path = base.getRawPath().isEmpty() ? "/" : base.getRawPath();
        if (!path.endsWith("/")) path = path + "/";
        return URI.create(scheme + "://" + Objects.toString(base.getRawAuthority(), "") + path).normalize();
    }

    private static IllegalArgumentException refused(String why) {
        return new IllegalArgumentException("RAIL8_GIT_BASE is refused: " + why + " (the value is not shown)");
    }

    /**
     * A user name and a read-only access token for every repository under the base.
     *
     * @param user {@code RAIL8_GIT_USER}, not blank
     * @param token {@code RAIL8_GIT_TOKEN}, not blank
     * @since 0.1
     */
    public record Credentials(String user, String token) {

        public Credentials {
            if (user == null || user.isBlank()) throw new IllegalArgumentException("RAIL8_GIT_USER must not be blank");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("RAIL8_GIT_TOKEN must not be blank");
        }

        /** The token redacted: a log line never carries it. */
        @Override
        public String toString() {
            return "Credentials[user=" + user + ", token=***]";
        }
    }
}
