package io.flottila.runtime.deploy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * @since 0.1
 */
public record DeploymentRecord(String name, String version, Optional<String> revision, String archiveSha256, Instant deployedAt) {
    public DeploymentRecord {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(archiveSha256, "archiveSha256");
        deployedAt = deployedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
