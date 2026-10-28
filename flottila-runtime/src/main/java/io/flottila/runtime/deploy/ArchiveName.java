package io.flottila.runtime.deploy;

import java.util.regex.Pattern;

/** The name every {@link ArchiveStore} gives an archive: {@code <sha256>.zip}, the key checked first. */
final class ArchiveName {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private ArchiveName() {}

    /** {@code <sha256>.zip}; anything but 64 lower-case hex digits is refused, a path or a line end included. */
    static String of(String sha256) {
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("an archive is stored under its SHA-256, 64 lower-case hex digits, was '" + sha256 + "'");
        }
        return sha256 + ".zip";
    }
}
