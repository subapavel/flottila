package io.flottila.runtime.deploy;

import io.flottila.api.EndpointConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A flow's effective hash (D-063, D-060 c, D-078): {@code sha256:} and the lower-case hex SHA-256 of these UTF-8 lines, each
 * ended by {@code \n}, paths package-relative with {@code /} (a path outside the package is refused, never hashed):
 * <ol>
 *   <li>{@code flottila-flow-hash 2};</li>
 *   <li>{@code yaml <sha256>} over the flow file's bytes, without its path: a flow file moved alone to another folder is no
 *       change (its references are paths from the package root, D-078);</li>
 *   <li>for every distinct path its build read (the {@code StepResources} reads sink), sorted by path — a path in a
 *       dependency's directory spelled {@code <dep>:<path from its root>} (D-087), never the directory of the version it is
 *       unpacked in, so the line changes exactly when the bytes read change: {@code file <path> <sha256>} for a file, {@code dir <path> <entry names as a JSON array, sorted>} for a directory (a
 *       listed collection), {@code absent <path>} for anything else (a probe of a file that does not exist);</li>
 *   <li>{@code endpoint <id> <scheme> <options as JSON, keys sorted at every level>}: first the {@code from} endpoint as its
 *       consumer gets it ({@code flow} and {@code maxBodyBytes} included), then every endpoint the flow sends to, by id — each
 *       with this environment's overlay laid over its config, each {@code secret://} reference as written, never its value
 *       (D-081).</li>
 * </ol>
 * Nothing else: package.yaml, the package's version, the deploy's revision, the archive, its directory, other environments'
 * overlays and files no build read are not part of it, so the same content hashes the same on any node; a stylesheet or schema
 * served from its step factory's compile cache reports the files its compile read, as a compile would (FIX-17). Known gap: what a
 * stylesheet reads at run time ({@code doc()}, {@code unparsed-text()}: F-9, FIX-40).
 */
final class FlowHash {

    private FlowHash() {}

    /**
     * @param dir the package's directory, every path below lies inside it or inside one of {@code dependencies}
     * @param dependencies the build's dependencies, each name to the directory its version is unpacked in (D-087)
     * @param yaml the flow's file, wherever it lies in the package
     * @param reads every path the flow's build reported, in any order, a path reported twice counted once
     * @param from the {@code from} endpoint as its consumer gets it
     * @param to every endpoint the flow sends to, in any order
     * @throws IllegalArgumentException a path that normalises outside {@code dir} and every dependency's: the flow does not build
     * @throws IOException a file cannot be read: the node's failure, not the package's
     */
    static String of(Path dir, Map<String, Path> dependencies, Path yaml, Collection<Path> reads, EndpointConfig from, Collection<EndpointConfig> to)
            throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        Map<String, Path> roots = new TreeMap<>();
        dependencies.forEach((name, d) -> roots.put(name, d.toAbsolutePath().normalize()));
        MessageDigest digest = digest();
        line(digest, "flottila-flow-hash 2");
        line(digest, "yaml " + sha256(yaml));
        Map<String, Path> byPath = new TreeMap<>();
        for (Path read : reads) byPath.put(relative(root, roots, read), read);
        for (Map.Entry<String, Path> read : byPath.entrySet()) line(digest, read(read.getKey(), read.getValue()));
        line(digest, endpoint(from));
        for (EndpointConfig e : to.stream().sorted(Comparator.comparing(EndpointConfig::name)).toList()) line(digest, endpoint(e));
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /** The SHA-256 of the file's bytes, lower-case hex. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException mandatory) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", mandatory);
        }
    }

    private static void line(MessageDigest digest, String line) {
        digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code path} from {@code root}, {@code /}-separated, or {@code <dep>:<path>} from the dependency directory that holds it; one
     * that normalises outside the package and every dependency is refused, never hashed.
     */
    private static String relative(Path root, Map<String, Path> dependencies, Path path) {
        Path normalised = path.toAbsolutePath().normalize();
        if (normalised.startsWith(root)) return slashed(root.relativize(normalised));
        for (Map.Entry<String, Path> d : dependencies.entrySet()) {
            if (normalised.startsWith(d.getValue())) return d.getKey() + ":" + slashed(d.getValue().relativize(normalised));
        }
        throw new IllegalArgumentException(normalised + " is outside the package directory " + root + " and its dependencies': not hashed");
    }

    private static String slashed(Path relative) {
        return relative.toString().replace(File.separatorChar, '/');
    }

    /** The line of one path the build read: what is there now. */
    private static String read(String relative, Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                return "dir " + relative + " " + new JsonArray(entries.map(e -> e.getFileName().toString()).sorted().toList()).encode();
            }
        }
        if (Files.isRegularFile(path)) return "file " + relative + " " + sha256(path);
        return "absent " + relative;
    }

    private static String endpoint(EndpointConfig e) {
        return "endpoint " + e.name() + " " + e.scheme() + " " + ((JsonObject) canonical(e.options())).encode();
    }

    /** {@code value} with every map's keys sorted, at every level; lists keep their order. */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, v) -> sorted.put((String) key, canonical(v)));
            JsonObject json = new JsonObject();
            sorted.forEach(json::put);
            return json;
        }
        if (value instanceof List<?> list) return new JsonArray(list.stream().map(FlowHash::canonical).toList());
        return value;
    }
}
