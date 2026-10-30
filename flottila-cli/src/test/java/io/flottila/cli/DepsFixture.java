package io.flottila.cli;

import io.flottila.schemas.Documents;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * P2-02b2 test fixture (D-087, D-089): packages with dependencies, resolved off-node from a candidates directory. The committed
 * packages under {@code src/test/resources/deps} carry no {@code package.lock}: a case copies what it needs into a temp directory
 * and writes the locks there with digests this class computes from the files ({@link #content}, never the code under test), so
 * a digest never goes stale.
 *
 * <ul>
 *   <li>{@code common} — common 1.2.0, no flows: {@code xslt/to-partner.xsl} includes {@code xslt/lib.xsl} next to it, which
 *       writes the attribute {@code by="common 1.2.0"};</li>
 *   <li>{@code common-1.3.0} — common 1.3.0, the same files but {@code lib.xsl} writes {@code by="common 1.3.0"};</li>
 *   <li>{@code invoice} — invoice 1.0.0 declaring {@code common 1.2.0}; flow {@code to-partner} (sync, from {@code invoice-in})
 *       maps with {@code common:xslt/to-partner.xsl} and sends to {@code partner}; its test document's one case
 *       {@code maps with common} expects the partner to see {@code by = common 1.2.0}.</li>
 * </ul>
 */
final class DepsFixture {

    static final Path DEPS = Path.of("src/test/resources/deps");

    /** A pin as package.lock holds it. */
    record Pin(String name, String version, String content) {
        /** The line {@code flottila lock} prints for it. */
        String line() { return name + "@" + version + " " + content; }
    }

    private DepsFixture() {}

    /** A copy of the fixture package {@code name} at {@code to}. */
    static Path copy(String name, Path to) throws IOException {
        return CliRuns.copy(DEPS.resolve(name), to);
    }

    /** {@code dir/package.yaml}'s name and version, its {@code dependencies} replaced by {@code deps} ({@code name@version} each, in order). */
    static void manifest(Path dir, String name, String version, String... deps) throws IOException {
        String list = deps.length == 0 ? "dependencies: []\n" : "dependencies:\n" + Stream.of(deps)
                .map(d -> "  - { name: " + d.substring(0, d.indexOf('@')) + ", version: " + d.substring(d.indexOf('@') + 1) + " }\n")
                .collect(Collectors.joining());
        Files.writeString(dir.resolve("package.yaml"), "schemaVersion: 1\nkind: package\nname: " + name + "\nversion: " + version + "\n" + list);
    }

    /** The pin of the package in {@code dir} as its files are now: name and version from its package.yaml, {@link #content}. */
    static Pin pinOf(Path dir) throws IOException {
        JsonObject manifest = Documents.yaml(Files.readString(dir.resolve("package.yaml")));
        return new Pin(manifest.getString("name"), manifest.getString("version"), content(dir));
    }

    /** Writes {@code dir/package.lock} pinning the packages in {@code pinned}, as they are now, in that order. */
    static void lock(Path dir, Path... pinned) throws IOException {
        List<Pin> pins = new java.util.ArrayList<>();
        for (Path p : pinned) pins.add(pinOf(p));
        lock(dir, pins);
    }

    /** Writes {@code dir/package.lock} with {@code pins}, in that order. */
    static void lock(Path dir, List<Pin> pins) throws IOException {
        StringBuilder text = new StringBuilder("schemaVersion: 1\nkind: lock\n");
        if (pins.isEmpty()) {
            text.append("dependencies: []\n");
        } else {
            text.append("dependencies:\n");
            for (Pin p : pins) text.append("  - name: ").append(p.name()).append("\n    version: ").append(p.version())
                    .append("\n    content: ").append(p.content()).append('\n');
        }
        Files.writeString(dir.resolve("package.lock"), text);
    }

    /** {@code common}'s {@code lib.xsl} in {@code dir} rewritten to write {@code by} instead: other content, same name and version. */
    static void relabel(Path dir, String by) throws IOException {
        Path lib = dir.resolve("xslt/lib.xsl");
        String text = Files.readString(lib);
        String now = text.replaceAll("by\">[^<]+<", "by\">" + by + "<");   // + : the template tag name="by">< holds no text
        if (now.equals(text)) throw new AssertionError("fixture: " + lib + " was not relabelled: " + text);
        Files.writeString(lib, now);
    }

    /**
     * The content digest of the package in {@code dir} as D-087 defines it (computed here, independently of the code under test):
     * {@code sha256:} and the lower-case hex SHA-256 over the UTF-8 lines {@code flottila-package-content 1} and
     * {@code file <path> <sha256 hex>} for every regular file below {@code dir}, sorted by its {@code /}-separated path, each
     * ended by {@code \n}.
     */
    static String content(Path dir) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(f -> files.put(dir.relativize(f).toString().replace('\\', '/'), f));
        }
        MessageDigest all = sha256();
        all.update("flottila-package-content 1\n".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, Path> f : files.entrySet()) {
            String line = "file " + f.getKey() + " " + HexFormat.of().formatHex(sha256().digest(Files.readAllBytes(f.getValue()))) + "\n";
            all.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return "sha256:" + HexFormat.of().formatHex(all.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException mandatory) {
            throw new AssertionError(mandatory);
        }
    }

    /**
     * A candidates directory at {@code deps}: {@code common} and {@code common-1.3.0} copied in, and {@code invoice} with its lock
     * pinning {@code common} as copied. Returns invoice's directory.
     */
    static Path invoiceWithCandidates(Path deps) throws IOException {
        Path common = copy("common", deps.resolve("common"));
        copy("common-1.3.0", deps.resolve("common-1.3.0"));
        Path invoice = copy("invoice", deps.resolve("invoice"));
        lock(invoice, common);
        return invoice;
    }

    /**
     * A chain in {@code deps}: {@code common} (copied), {@code ubl} 1.0.0 (a copy of common whose {@code lib.xsl} writes
     * {@code ubl 1.0.0}) declaring {@code common 1.2.0}, locked; {@code order} 1.0.0 (a copy of invoice) declaring
     * {@code ubl 1.0.0}, mapping with {@code ubl:xslt/to-partner.xsl}, its case expecting {@code by = ubl 1.0.0}, locked. Returns
     * order's directory. order's resolution needs ubl's own lock; ubl deploys only after common.
     */
    static Path orderOverUblOverCommon(Path deps) throws IOException {
        Path common = copy("common", deps.resolve("common"));
        Path ubl = copy("common", deps.resolve("ubl"));
        manifest(ubl, "ubl", "1.0.0", "common@1.2.0");
        relabel(ubl, "ubl 1.0.0");
        lock(ubl, common);
        Path order = copy("invoice", deps.resolve("order"));
        manifest(order, "order", "1.0.0", "ubl@1.0.0");
        CliRuns.rewrite(order.resolve("flows/to-partner.yaml"), Map.of("stylesheet: common:xslt/to-partner.xsl", "stylesheet: ubl:xslt/to-partner.xsl"));
        CliRuns.rewrite(order.resolve("flows/to-partner.test.yaml"), Map.of("equals: common 1.2.0", "equals: ubl 1.0.0"));
        lock(order, ubl);
        return order;
    }

    /**
     * A name cycle in {@code deps}: {@code a} 1.0.0 declares {@code b} 1.0.0, which declares {@code a} 1.0.0 (both copies of common).
     * b's lock pins a as it was before a's lock was written; a's lock pins b as it is. Returns a's directory.
     */
    static Path cycle(Path deps) throws IOException {
        Path a = copy("common", deps.resolve("a"));
        manifest(a, "a", "1.0.0", "b@1.0.0");
        Path b = copy("common", deps.resolve("b"));
        manifest(b, "b", "1.0.0", "a@1.0.0");
        lock(b, a);
        lock(a, b);
        return a;
    }

    /** {@code ran}'s stdout and stderr name none of {@code paths} as an absolute path (as given, or real). */
    static void assertNoAbsolutePath(CliRuns.Ran ran, Path... paths) throws IOException {
        for (Path p : paths) {
            for (String abs : List.of(p.toAbsolutePath().toString(), p.toRealPath().toString())) {
                if (ran.out().contains(abs) || ran.err().contains(abs)) throw new AssertionError("the output names " + abs + ": " + ran);
            }
        }
    }
}
