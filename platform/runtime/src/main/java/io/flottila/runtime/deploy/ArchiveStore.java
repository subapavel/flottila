package io.flottila.runtime.deploy;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Where package archives are stored, each once under its SHA-256 (D-070): S3 on a deployed node ({@link S3ArchiveStore}), a
 * directory in dev ({@link DirArchiveStore}). Both calls block: call them on a virtual thread. A key that is not 64 lower-case hex
 * digits is an {@link IllegalArgumentException}. Nothing here deletes an archive (Phase 1).
 *
 * @since 0.1
 */
public interface ArchiveStore {

    /**
     * Stores the file {@code archive} under {@code sha256}, always — a second put of a key writes again — and leaves the file as
     * it was.
     */
    void put(String sha256, Path archive) throws IOException;

    /**
     * Writes the archive stored under {@code sha256} to {@code target}, replacing what it held.
     *
     * @throws NoSuchFileException nothing is stored under {@code sha256}: the exception names the store's key for it
     */
    void fetch(String sha256, Path target) throws IOException;
}
