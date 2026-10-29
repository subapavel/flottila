package io.flottila.runtime.deploy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The record of a package deployed (D-070): one per package, shared by every node of the pool, written once the deploy's flows
 * started — its commit point — and replaced whole by the next deploy of the package. It holds no bytes: the archive is in the
 * {@link ArchiveStore} under {@code archiveSha256}.
 *
 * @param name the package's name
 * @param version its manifest's version
 * @param revision the deploy's revision, empty without one
 * @param archiveSha256 the archive's SHA-256, lower-case hex: its key in the archive store
 * @param deployedAt the {@link DeployedPackage#deployedAt()} of the deploy, truncated to the microsecond (what Postgres keeps)
 * @param dependencies the closure of the package's dependencies as its deploy resolved them (D-087), in the order resolved: the
 *                     archives this deploy runs with, never pruned while the record pins them; empty without dependencies
 * @since 0.1
 */
public record DeploymentRecord(String name, String version, Optional<String> revision, String archiveSha256, Instant deployedAt,
                               List<Pin> dependencies) {
    public DeploymentRecord {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(archiveSha256, "archiveSha256");
        deployedAt = deployedAt.truncatedTo(ChronoUnit.MICROS);
        dependencies = List.copyOf(dependencies);
    }

    /**
     * One package version a deploy resolved a dependency to (D-087).
     *
     * @param name the dependency's package name
     * @param version its version
     * @param archiveSha256 its archive's SHA-256, lower-case hex: its key in the archive store and its directory under
     *                      {@code packages/<name>/}
     * @since 0.1
     */
    public record Pin(String name, String version, String archiveSha256) {
        public Pin {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(archiveSha256, "archiveSha256");
        }
    }
}
