package io.flottila.runtime.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Unpacks a package archive, a zip with {@code package.yaml} at its root, into a directory (D-059). The whole archive is checked
 * before anything is written; a refusal — the deploy's 400 — is an {@link IllegalArgumentException}: a file that is no zip (a
 * {@link ZipException} at open), more than {@value #MAX_ENTRIES} entries, an entry that is absolute, resolves outside the target
 * after normalisation, is a file that resolves to the target itself ({@code .}, {@code a/..}), resolves where an earlier entry
 * does, or is a file where another entry needs a directory; then, inflating
 * every entry without writing it, an entry that cannot be read (any {@link IOException}, e.g. data cut short) and more than
 * 4 × max bytes in total as the entries actually inflate (never as their headers claim). An {@link IOException} while writing
 * leaves what was written: the target is the caller's to delete.
 */
final class PackageArchive {

    static final int MAX_ENTRIES = 10_000;

    private PackageArchive() {}

    /** Unpacks {@code zip} into {@code into}, an existing directory, when its entries inflate to at most 4 × {@code max} bytes in total. */
    static void unpack(Path zip, Path into, long max) throws IOException {
        long budget = Math.multiplyExact(4, max);
        Path root = into.normalize();
        try (ZipFile archive = open(zip)) {
            Map<Path, ZipEntry> entries = entries(archive, root);
            long inflated = 0;
            for (ZipEntry e : entries.values()) inflated += inflate(archive, e, budget - inflated, budget);
            for (Map.Entry<Path, ZipEntry> e : entries.entrySet()) write(archive, e.getValue(), e.getKey());
        }
    }

    /** The archive, or the refusal of a file that is no zip; any other IOException (the file is gone, unreadable) is the node's. */
    private static ZipFile open(Path zip) throws IOException {
        try {
            return new ZipFile(zip.toFile());
        } catch (ZipException notAZip) {
            throw new IllegalArgumentException("not a zip archive: " + notAZip.getMessage(), notAZip);
        }
    }

    /**
     * Every entry by the path it unpacks to, in archive order; refuses too many entries, every name that does not stay below
     * {@code root}, and a file entry where another entry needs a directory (its own, or a parent of its path).
     */
    private static Map<Path, ZipEntry> entries(ZipFile archive, Path root) {
        if (archive.size() > MAX_ENTRIES) throw new IllegalArgumentException("the archive has " + archive.size() + " entries, at most " + MAX_ENTRIES + " are allowed");
        Map<Path, ZipEntry> entries = new LinkedHashMap<>();
        Set<Path> directories = new HashSet<>();
        for (ZipEntry e : archive.stream().toList()) {
            Path name = Path.of(e.getName());
            if (name.isAbsolute()) throw new IllegalArgumentException("entry '" + e.getName() + "' is an absolute path");
            Path target = root.resolve(name).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("entry '" + e.getName() + "' resolves outside the package directory");
            if (!e.isDirectory() && target.equals(root)) throw new IllegalArgumentException("entry '" + e.getName() + "' is a file at the package directory itself");
            ZipEntry earlier = entries.putIfAbsent(target, e);
            if (earlier != null) throw new IllegalArgumentException("entry '" + e.getName() + "' is a duplicate: entry '" + earlier.getName() + "' unpacks to the same path");
            Path below = root.relativize(target);
            for (int i = 1; i < below.getNameCount(); i++) directories.add(root.resolve(below.subpath(0, i)));
            if (e.isDirectory()) directories.add(target);
        }
        for (Map.Entry<Path, ZipEntry> e : entries.entrySet()) {
            if (!e.getValue().isDirectory() && directories.contains(e.getKey())) {
                throw new IllegalArgumentException("entry '" + e.getValue().getName() + "' is a file where another entry needs a directory");
            }
        }
        return entries;
    }

    /**
     * The bytes {@code entry} actually inflates to; more than {@code room} is a refusal, whatever its header says, and so is an entry
     * that cannot be read: this pass only reads the archive, so its IOException is the archive's.
     */
    private static long inflate(ZipFile archive, ZipEntry entry, long room, long budget) {
        byte[] buffer = new byte[8192];
        long inflated = 0;
        try (InputStream in = archive.getInputStream(entry)) {
            for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
                inflated += n;
                if (inflated > room) {
                    throw new IllegalArgumentException("the archive inflates to more than " + budget + " bytes (4 × the package limit) at entry '" + entry.getName() + "'");
                }
            }
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("entry '" + entry.getName() + "' cannot be read: " + unreadable, unreadable);
        }
        return inflated;
    }

    /** Writes one checked entry: a directory, or a file (with its parents) that must not exist yet. */
    private static void write(ZipFile archive, ZipEntry entry, Path target) throws IOException {
        if (entry.isDirectory()) {
            Files.createDirectories(target);
            return;
        }
        Files.createDirectories(target.getParent());
        try (InputStream in = archive.getInputStream(entry)) {
            Files.copy(in, target);
        }
    }
}
