package io.flottila.runtime.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * The {@link ArchiveStore} of a node in dev or ci (D-070; a deployed node refuses it): the archive of a key is the file
 * {@code <dir>/<sha256>.zip}. A put copies into a temporary file in {@code dir}, created with it when absent, and moves that over
 * the archive's file atomically: a reader never sees half an archive, and a failed put leaves no temporary file.
 *
 * @since 0.1
 */
public final class DirArchiveStore implements ArchiveStore {

    private final Path dir;

    public DirArchiveStore(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    @Override
    public void put(String sha256, Path archive) throws IOException {
        Path file = dir.resolve(ArchiveName.of(sha256));
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".put-", ".tmp");
        try {
            Files.copy(archive, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);   // moved: gone already; failed, unchecked failures included: nothing of the put stays
        }
    }

    /** @throws java.nio.file.NoSuchFileException naming {@code <dir>/<sha256>.zip} when nothing is stored under the key */
    @Override
    public void fetch(String sha256, Path target) throws IOException {
        Files.copy(dir.resolve(ArchiveName.of(sha256)), target, StandardCopyOption.REPLACE_EXISTING);
    }
}
