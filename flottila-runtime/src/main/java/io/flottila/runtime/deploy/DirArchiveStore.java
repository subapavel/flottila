package io.flottila.runtime.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    private final long maxBytes;

    /** @param maxBytes the most bytes a fetch copies, at least 1: a larger archive is refused while it is copied */
    public DirArchiveStore(Path dir, long maxBytes) {
        this.dir = Objects.requireNonNull(dir, "dir");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be at least 1, was " + maxBytes);
        this.maxBytes = maxBytes;
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

    /**
     * @throws java.nio.file.NoSuchFileException naming {@code <dir>/<sha256>.zip} when nothing is stored under the key
     * @throws IOException the archive is larger than the cap: refused while it is copied, nothing of it stays at {@code target}
     */
    @Override
    public void fetch(String sha256, Path target) throws IOException {
        try (InputStream in = Files.newInputStream(dir.resolve(ArchiveName.of(sha256)))) {
            copyBounded(in, target, maxBytes);
        }
    }

    /**
     * Copies {@code in} to {@code target}, replacing it, and fails as soon as more than {@code maxBytes} have been read; on any
     * failure, unchecked ones included, no file stays at {@code target}.
     */
    static void copyBounded(InputStream in, Path target, long maxBytes) throws IOException {
        boolean done = false;
        try {
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                byte[] chunk = new byte[64 * 1024];
                long copied = 0;
                for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                    copied += n;
                    if (copied > maxBytes) {
                        throw new IOException("the archive is larger than the cap of " + maxBytes + " bytes (FLOTTILA_MAX_PACKAGE_BYTES): refused after "
                                + copied + " bytes");
                    }
                    out.write(chunk, 0, n);
                }
            }
            done = true;
        } finally {
            if (!done) Files.deleteIfExists(target);
        }
    }
}
