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
