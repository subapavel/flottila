package dev.rail8.runtime.git;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.ObjectChecker;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The rules a package's folder in a commit is held to, checked entry by entry from its tree before anything is written: regular
 * files only, each under a name that is safe on Linux, Windows and macOS ({@code .git} is not), at most {@value #MAX_FILES} of them
 * and at most the package limit in bytes, {@code package.yaml} among them. A refusal is an {@link IllegalArgumentException} naming the
 * repository, the commit and the path in the package.
 */
final class PackageTree {

    /** The most files one package holds. */
    static final int MAX_FILES = 10_000;

    /** What a tree entry is. */
    enum Kind { FILE, LINK, SUBMODULE, OTHER }

    private final String commit;
    private final long maxBytes;
    private final ObjectChecker names = new ObjectChecker().setSafeForWindows(true).setSafeForMacOS(true);
    private final Set<String> paths = new HashSet<>();
    private long bytes;
    private boolean manifest;

    /**
     * @param repository the repository's name
     * @param commit the commit's id
     * @param maxBytes {@code RAIL8_MAX_PACKAGE_BYTES}
     */
    PackageTree(String repository, String commit, long maxBytes) {
        this.commit = repository + " at " + commit;
        this.maxBytes = maxBytes;
    }

    /** The refusal of a commit whose tree has nothing at the package's folder, or a file there. */
    static IllegalArgumentException noFolder(String repository, String commit, String path) {
        return new IllegalArgumentException(repository + " at " + commit + ": the commit has no package folder "
                + (path.isEmpty() ? "at its root" : "at " + path));
    }

    /** An entry's path as git stores it, which must be UTF-8. */
    String name(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw refused("a file name is not UTF-8");
        }
    }

    /**
     * Checks one entry below the package's folder.
     *
     * @param path its path relative to the folder, {@code /} between segments
     * @param kind what it is
     * @param size a file's bytes; ignored for the other kinds
     */
    void add(String path, Kind kind, long size) {
        try {
            names.checkPath(path);
        } catch (CorruptObjectException unsafe) {
            throw refused("'" + path + "' is not a safe file name");
        }
        switch (kind) {
            case LINK -> throw refused("'" + path + "' is a symbolic link; a package holds regular files only");
            case SUBMODULE -> throw refused("'" + path + "' is a submodule; a package holds regular files only");
            case OTHER -> throw refused("'" + path + "' is not a regular file; a package holds regular files only");
            case FILE -> {
                if (!paths.add(path)) throw refused("'" + path + "' is in the tree twice");
                if (paths.size() > MAX_FILES) throw refused("the package holds more than " + MAX_FILES + " files");
                bytes += size;
                if (bytes > maxBytes) throw refused("the package holds more than RAIL8_MAX_PACKAGE_BYTES " + maxBytes + " bytes");
                manifest |= path.equals("package.yaml");
            }
        }
    }

    /** After the last entry: the folder holds {@code package.yaml}. */
    void finish() {
        if (!manifest) throw refused("the package folder holds no package.yaml");
    }

    private IllegalArgumentException refused(String why) {
        return new IllegalArgumentException(commit + ": " + why);
    }
}
