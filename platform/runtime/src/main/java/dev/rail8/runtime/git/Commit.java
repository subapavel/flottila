package dev.rail8.runtime.git;

import java.nio.file.Path;

/**
 * The package's folder in one commit of one repository, as {@link PackageRepositories} answered it.
 *
 * @since 0.1
 */
public interface Commit {

    /** The repository's name under the base. */
    String repository();

    /** The commit's 40-hex id. */
    String id();

    /**
     * Writes the package folder's files, as committed, into an empty directory: the bytes git stores, with no line ending or filter
     * changed. The whole tree is checked before anything is written; a refusal is an {@link IllegalArgumentException}: a commit with
     * no package folder or no {@code package.yaml} in it, a symbolic link, a submodule, a name that is not safe on disk ({@code .git}
     * among them), more than 10 000 files, or more than {@code RAIL8_MAX_PACKAGE_BYTES} in all. A failure of the node's own disk is an
     * {@link java.io.UncheckedIOException} and leaves what was written: the directory is the caller's to delete.
     *
     * @param dir an existing, empty directory
     * @throws GitFailure when the cache lacks an object of the folder
     */
    void write(Path dir) throws GitFailure;
}
