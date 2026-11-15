package io.flottila.runtime.deploy;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A package as the deploy tests write it (P1-09d1b): {@code package.yaml}, {@code endpoints/<id>.yaml} and
 * {@code flows/<id>.yaml} held as documents a case may change before it zips them, plus any other file as bytes. Documents are
 * written as JSON, which every YAML reader reads (the files keep their {@code .yaml} names). Endpoints default to the
 * {@link Doors#SCHEME} scheme. {@link #zip()} puts {@code package.yaml} at the archive root, files only, in path order.
 *
 * <p>P2-02b (D-087): {@link #content()} is the content digest a {@code package.lock} pins, computed here from the files (never by
 * the code under test); {@link #dependingOn} and {@link #locking} declare dependencies in {@code package.yaml} and write the lock.
 */
public final class PackageDraft {

    private static final AtomicInteger ARCHIVES = new AtomicInteger();

    private final Map<String, JsonObject> docs = new TreeMap<>();
    private final Map<String, byte[]> raw = new TreeMap<>();

    private PackageDraft() {}

    /** A package with only its manifest: {@code name}, {@code version}, no dependencies. */
    public static PackageDraft of(String name, String version) {
        PackageDraft p = new PackageDraft();
        p.docs.put("package.yaml", new JsonObject().put("schemaVersion", 1).put("kind", "package").put("name", name).put("version", version));
        return p;
    }

    public JsonObject manifest() { return docs.get("package.yaml"); }

    /** An endpoint of the {@link Doors#SCHEME} scheme. */
    public PackageDraft endpoint(String id, String direction, JsonObject config) {
        return endpoint(id, Doors.SCHEME, direction, config);
    }

    public PackageDraft endpoint(String id, String scheme, String direction, JsonObject config) {
        docs.put("endpoints/" + id + ".yaml", new JsonObject().put("schemaVersion", 1).put("kind", "endpoint").put("id", id).put("scheme", scheme)
                .put("direction", direction).put("config", config));
        return this;
    }

    public JsonObject endpointDoc(String id) {
        JsonObject d = docs.get("endpoints/" + id + ".yaml");
        if (d == null) throw new AssertionError("fixture: no endpoint " + id);
        return d;
    }

    /** One step: {@code to} the endpoint {@code partner}. */
    public static JsonArray toPartner() {
        return new JsonArray().add(new JsonObject().put("type", "to").put("name", "send").put("endpoint", "partner"));
    }

    /** A flow {@code id} @ {@code version} in {@code flows/<id>.yaml}: delivery {@code mode}, from {@code from}, {@code maxBodyBytes}, {@code steps}. */
    public PackageDraft flow(String id, String version, String mode, String from, long maxBodyBytes, JsonArray steps) {
        docs.put("flows/" + id + ".yaml", new JsonObject().put("schemaVersion", 1).put("kind", "flow").put("id", id).put("version", version)
                .put("from", new JsonObject().put("endpoint", from)).put("delivery", new JsonObject().put("mode", mode))
                .put("limits", new JsonObject().put("maxBodyBytes", maxBodyBytes)).put("steps", steps));
        return this;
    }

    public JsonObject flowDoc(String id) {
        JsonObject d = docs.get("flows/" + id + ".yaml");
        if (d == null) throw new AssertionError("fixture: no flow " + id);
        return d;
    }

    /** Any other file, by its path in the archive. */
    public PackageDraft file(String path, byte[] bytes) {
        raw.put(path, bytes.clone());
        return this;
    }

    public PackageDraft file(String path, String text) {
        return file(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Moves the document or file at {@code from} to {@code to}, its bytes unchanged (P2-02a, D-078: artifacts in any folder, a file
     * moved to another one); fails the case when there is none at {@code from} or one at {@code to}.
     */
    public PackageDraft move(String from, String to) {
        if (docs.containsKey(to) || raw.containsKey(to)) throw new AssertionError("fixture: " + to + " exists already");
        JsonObject doc = docs.remove(from);
        if (doc != null) {
            docs.put(to, doc);
            return this;
        }
        byte[] bytes = raw.remove(from);
        if (bytes == null) throw new AssertionError("fixture: no file " + from + " to move");
        raw.put(to, bytes);
        return this;
    }

    /** The document at {@code path}, wherever it lies (P2-02a: one {@link #move}d out of {@code flows/} or {@code endpoints/}). */
    public JsonObject doc(String path) {
        JsonObject d = docs.get(path);
        if (d == null) throw new AssertionError("fixture: no document " + path);
        return d;
    }

    /** Drops the document or file at {@code path} (P1-09d2b: a flow or an endpoint a redeploy removes); fails the case when there is none. */
    public PackageDraft without(String path) {
        if (docs.remove(path) == null && raw.remove(path) == null) throw new AssertionError("fixture: no file " + path + " to remove");
        return this;
    }

    /** Every file of the package by its path in the archive, in path order. */
    public Map<String, byte[]> files() {
        Map<String, byte[]> all = new TreeMap<>();
        docs.forEach((path, doc) -> all.put(path, doc.encodePrettily().getBytes(StandardCharsets.UTF_8)));
        all.putAll(raw);
        return all;
    }

    /** The package as a zip: every file at its path, no directory entries. */
    public byte[] zip() {
        return zip(new LinkedHashMap<>(files()), List.of());
    }

    /** The package as a zip with these directory entries first (each ending in {@code /}). */
    public byte[] zipWithDirectories(List<String> directories) {
        return zip(new LinkedHashMap<>(files()), directories);
    }

    /** Writes {@link #zip()} to a fresh file under {@code dir}; the file's bytes are what the deploy hashes. */
    public Path zipTo(Path dir) {
        return write(dir, zip());
    }

    /** {@code bytes} in a fresh file under {@code dir}. */
    public static Path write(Path dir, byte[] bytes) {
        try {
            Files.createDirectories(dir);
            return Files.write(dir.resolve("archive-" + ARCHIVES.incrementAndGet() + ".zip"), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] zip(Map<String, byte[]> files, List<String> directories) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String d : directories) {
                out.putNextEntry(new ZipEntry(d));
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public String name() { return manifest().getString("name"); }

    public String version() { return manifest().getString("version"); }

    /** One entry of a {@code package.lock}: a dependency's name, its exact version and its content digest. */
    public record LockEntry(String name, String version, String content) {}

    /**
     * The content digest of this package as unpacked (D-087): {@code sha256:} and the SHA-256 over the UTF-8 lines
     * {@code flottila-package-content 1} and {@code file <path> <sha256>} for every file, sorted by path, each ended by {@code \n} —
     * whatever archive the files come in.
     */
    public String content() {
        StringBuilder text = new StringBuilder("flottila-package-content 1\n");
        files().forEach((path, bytes) -> text.append("file ").append(path).append(' ').append(sha256(bytes)).append('\n'));
        return "sha256:" + sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** This package as a lock entry pins it: its name, version and {@link #content()} as they are now. */
    public LockEntry locked() {
        return new LockEntry(name(), version(), content());
    }

    /** Declares each of {@code deps} at its version and pins it in {@code package.lock} with its content as it is now. */
    public PackageDraft dependingOn(PackageDraft... deps) {
        return locking(Arrays.stream(deps).map(PackageDraft::locked).toList());
    }

    /**
     * {@code package.yaml} declares each entry's name and version, {@code package.lock} (JSON, which YAML reads) pins each with its
     * content: both sorted by name, replacing what they held.
     */
    public PackageDraft locking(List<LockEntry> entries) {
        JsonArray declared = new JsonArray();
        JsonArray pinned = new JsonArray();
        for (LockEntry e : entries.stream().sorted(Comparator.comparing(LockEntry::name)).toList()) {
            declared.add(new JsonObject().put("name", e.name()).put("version", e.version()));
            pinned.add(new JsonObject().put("name", e.name()).put("version", e.version()).put("content", e.content()));
        }
        manifest().put("dependencies", declared);
        return file("package.lock", new JsonObject().put("schemaVersion", 1).put("kind", "lock").put("dependencies", pinned).encodePrettily());
    }

    /** The SHA-256 of {@code bytes}, lower-case hex. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code path}'s bytes' SHA-256, lower-case hex. */
    public static String sha256(Path path) {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
