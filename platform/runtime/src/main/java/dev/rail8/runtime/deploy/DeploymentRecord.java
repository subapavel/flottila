package dev.rail8.runtime.deploy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * The record of a package deployed: one per package, shared by every node of the pool, written once the deploy's flows
 * started — its commit point — and replaced whole by the next deploy of the package. Every node of the pool runs what the records
 * name. It holds no bytes: the package's files are
 * the commit's, read from the node's git cache or fetched, and checked against {@code content}.
 *
 * @param name the package's name
 * @param version its manifest's version
 * @param repository the name of the repository the package was deployed from, under the pool's {@code RAIL8_GIT_BASE}
 * @param commit the commit deployed, 40 lower-case hex digits
 * @param content the content digest of the package's files ({@code Package.content}), {@code sha256:<hex>}: its directory under
 *                {@code packages/<name>/} is that hex
 * @param deployedAt the {@link DeployedPackage#deployedAt()} of the deploy, truncated to the microsecond (what Postgres keeps)
 * @param dependencies the closure of the package's dependencies as its deploy resolved them, in the order resolved: the
 *                     directories this deploy runs with, never pruned while the record pins them; empty without dependencies
 * @param atLeastOnce the qualified ids ({@code <package>/<flow>}) of the flows this version runs at-least-once, sorted
 * @since 0.1
 */
public record DeploymentRecord(String name, String version, String repository, String commit, String content, Instant deployedAt,
                               List<Pin> dependencies, List<String> atLeastOnce) {
    public DeploymentRecord {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(content, "content");
        deployedAt = deployedAt.truncatedTo(ChronoUnit.MICROS);
        dependencies = List.copyOf(dependencies);
        atLeastOnce = atLeastOnce.stream().sorted().toList();
    }

    /**
     * One package version a deploy resolved a dependency to: the entry of a {@code package.lock}.
     *
     * @param name the dependency's package name
     * @param version its version
     * @param content its content digest, {@code sha256:<hex>}: its directory under {@code packages/<name>/} is that hex
     * @param repository the repository its files are read from
     * @param commit the commit they are read from, 40 lower-case hex digits
     * @since 0.1
     */
    public record Pin(String name, String version, String content, String repository, String commit) {
        public Pin {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(commit, "commit");
        }
    }
}
