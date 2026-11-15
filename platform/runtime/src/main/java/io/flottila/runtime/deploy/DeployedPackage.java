package io.flottila.runtime.deploy;

import io.flottila.schemas.Package;

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
 * @param folders every folder below the package root, with its {@code folder.yaml}'s namespace and description, sorted by path
 *                (D-078); none when it is {@link State#FAILED}
 * @param artifacts every endpoint, flow and test document, sorted by path (D-078); none when it is {@link State#FAILED}
 * @param state whether the node runs it
 * @param problems why it is {@link State#FAILED}, one line each; none when it runs
 * @param content the content digest of its files as {@code Package.content} takes it, what a {@code package.lock} pins
 *                (D-087); null when it is {@link State#FAILED}
 * @param dependencies the closure of its dependencies as its deploy resolved them (D-087), in the order resolved; none when it is
 *                     {@link State#FAILED}
 * @since 0.1
 */
public record DeployedPackage(String name, String version, Optional<String> revision, String archiveSha256, List<String> flows,
                              Instant deployedAt, List<PackageFile> files, List<Package.Folder> folders, List<Package.Artifact> artifacts, State state,
                              List<String> problems, String content, List<Dependency> dependencies) {

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
        folders = List.copyOf(folders);
        artifacts = List.copyOf(artifacts);
        problems = List.copyOf(problems);
        dependencies = List.copyOf(dependencies);
    }

    /**
     * One package version this package's deploy resolved a dependency to (D-087).
     *
     * @param name the dependency's package name
     * @param version its exact version
     * @param content its content digest, {@code sha256:<hex>}: what the lock that named it pins
     * @param archiveSha256 its archive's SHA-256, lower-case hex: the directory under {@code packages/<name>/} it is read from
     * @param direct whether this package's own {@code package.lock} names it; false for one only a dependency's lock names
     * @since 0.1
     */
    public record Dependency(String name, String version, String content, String archiveSha256, boolean direct) {}

    /**
     * One file of a deployed package: a changed XSD, XSLT or script shows here without a flow change (D-060 b).
     *
     * @param path relative to the package root, {@code /}-separated
     * @param sha256 its bytes' SHA-256, lower-case hex
     * @since 0.1
     */
    public record PackageFile(String path, String sha256) {}
}
