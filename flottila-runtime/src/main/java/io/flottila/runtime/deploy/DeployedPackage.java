package io.flottila.runtime.deploy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A package deployed on this node (D-059, D-060 b): what the deploy answers and {@code GET /admin/packages} lists.
 *
 * @param name the package's name
 * @param version its manifest's version; a {@link State#FAILED} package's is its record's
 * @param revision the deploy's revision, empty without one
 * @param archiveSha256 the uploaded archive's SHA-256, lower-case hex: the name of its directory under {@code packages/<name>/}
 * @param flows its flows by qualified id, sorted; none when it is {@link State#FAILED}
 * @param deployedAt the node's clock when the deploy unpacked it; a restored package's is its record's (D-070)
 * @param files every file of the archive (no directory), by path from the package root, sorted; none when it is {@link State#FAILED}
 * @param state whether the node runs it
 * @param problems why it is {@link State#FAILED}, one line each; none when it runs
 * @since 0.1
 */
public record DeployedPackage(String name, String version, Optional<String> revision, String archiveSha256, List<String> flows,
                              Instant deployedAt, List<PackageFile> files, State state, List<String> problems) {

    /**
     * {@link #RUNNING}: installed, its flows listed each with its own state; {@link #FAILED}: the restore at the node's start could
     * not install it (D-070) — nothing of it runs on this node until a deploy of it, which is a first deploy.
     */
    public enum State { RUNNING, FAILED }

    public DeployedPackage {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(state, "state");
        flows = List.copyOf(flows);
        files = List.copyOf(files);
        problems = List.copyOf(problems);
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
