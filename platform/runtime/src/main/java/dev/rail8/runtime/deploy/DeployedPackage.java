package dev.rail8.runtime.deploy;

import dev.rail8.schemas.Package;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A package deployed on this node: what the deploy answers and {@code GET /admin/packages} lists.
 *
 * @param name the package's name
 * @param version its manifest's version; a {@link State#FAILED} package's is its record's
 * @param repository the repository it was deployed from; a {@link State#FAILED} package's is its record's; empty for a deploy with
 *                   no origin ({@code rail8 test})
 * @param commit the commit it was deployed from, 40 lower-case hex digits; a {@link State#FAILED} package's is its record's; empty
 *               for a deploy with no origin
 * @param flows its flows by qualified id, sorted; none when it is {@link State#FAILED}
 * @param deployedAt the node's clock when the deploy wrote it; a restored package's is its record's
 * @param files every file of the package (no directory), by path from the package root, sorted; none when it is {@link State#FAILED}
 * @param folders every folder below the package root, with its {@code folder.yaml}'s namespace and description, sorted by path;
 *                none when it is {@link State#FAILED}
 * @param artifacts every endpoint, flow and test document, sorted by path; none when it is {@link State#FAILED}
 * @param state whether the node runs it
 * @param problems why it is {@link State#FAILED}, one line each; none when it runs
 * @param content the content digest of its files as {@code Package.content} takes it, what a {@code package.lock} pins; its
 *                directory under {@code packages/<name>/} is its hex; null when it is {@link State#FAILED}
 * @param dependencies the closure of its dependencies as its deploy resolved them, in the order resolved; none when it is
 *                     {@link State#FAILED}
 * @since 0.1
 */
public record DeployedPackage(String name, String version, Optional<String> repository, Optional<String> commit, List<String> flows,
                              Instant deployedAt, List<PackageFile> files, List<Package.Folder> folders, List<Package.Artifact> artifacts, State state,
                              List<String> problems, String content, List<Dependency> dependencies) {

    /**
     * {@link #RUNNING}: installed, its flows listed each with its own state; {@link #FAILED}: no install of its record succeeded on
     * this node, at the node's start or at a tick since — nothing of it runs on this node until a tick installs it or a deploy of
     * it, which is a first deploy.
     */
    public enum State { RUNNING, FAILED }

    public DeployedPackage {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(state, "state");
        flows = List.copyOf(flows);
        files = List.copyOf(files);
        folders = List.copyOf(folders);
        artifacts = List.copyOf(artifacts);
        problems = List.copyOf(problems);
        dependencies = List.copyOf(dependencies);
    }

    /**
     * One package version this package's deploy resolved a dependency to.
     *
     * @param name the dependency's package name
     * @param version its exact version
     * @param content its content digest, {@code sha256:<hex>}: what the lock that named it pins; the directory under
     *                {@code packages/<name>/} it is read from is its hex
     * @param repository the repository the lock pins it in
     * @param commit the commit the lock pins, whose files it is
     * @param direct whether this package's own {@code package.lock} names it; false for one only a dependency's lock names
     * @since 0.1
     */
    public record Dependency(String name, String version, String content, String repository, String commit, boolean direct) {}

    /**
     * One file of a deployed package: a changed XSD, XSLT or script shows here without a flow change.
     *
     * @param path relative to the package root, {@code /}-separated
     * @param sha256 its bytes' SHA-256, lower-case hex
     * @since 0.1
     */
    public record PackageFile(String path, String sha256) {}
}
