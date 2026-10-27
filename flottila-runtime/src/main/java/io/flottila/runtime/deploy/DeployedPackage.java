package io.flottila.runtime.deploy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A package deployed on this node (D-059, D-060 b): what the deploy answers and {@code GET /admin/packages} lists.
 *
 * @param name the package's name
 * @param version its manifest's version
 * @param revision the deploy's revision, empty without one
 * @param archiveSha256 the uploaded archive's SHA-256, lower-case hex: the name of its directory under {@code packages/<name>/}
 * @param flows its flows by qualified id, sorted
 * @param deployedAt the node's clock when the deploy unpacked it
 * @param files every file of the archive (no directory), by path from the package root, sorted
 * @since 0.1
 */
public record DeployedPackage(String name, String version, Optional<String> revision, String archiveSha256, List<String> flows,
                              Instant deployedAt, List<PackageFile> files) {
    public DeployedPackage {
        Objects.requireNonNull(revision, "revision");
        flows = List.copyOf(flows);
        files = List.copyOf(files);
    }

    /**
     * One file of a deployed package: a changed XSD, XSLT or script shows here without a flow change (D-060 b).
     *
     * @param path relative to the package root, {@code /}-separated
     * @param sha256 its bytes' SHA-256, lower-case hex
     * @since 0.1
     */
    public record PackageFile(String path, String sha256) {}
}
