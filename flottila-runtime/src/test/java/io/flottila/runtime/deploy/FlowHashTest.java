package io.flottila.runtime.deploy;

import io.flottila.api.EndpointConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09d2a (D-063): the effective hash's exact text, which P1-09c may persist — {@code sha256:} over the versioned lines, the
 * flow file's bytes first (P2-02a, D-078: version 2, without the flow file's path), then every path the build read once, sorted by its package-relative path ({@code file}, {@code dir} with its
 * sorted entry names, {@code absent}), then the {@code from} endpoint and the endpoints sent to, by id, each with its options as
 * JSON whose keys are sorted at every level while lists keep their order. {@code DeploymentsHashTest} shows what changes it
 * through a deploy; a line no deploy could tell apart (a probe of a missing file sits in a file that is hashed too) is pinned here.
 *
 * <p>P2-02b (D-087): a read in a dependency's directory hashes as {@code <dep>:<path from its root>} — {@code file common:xslt/x.xsl
 * <sha256>}, never the directory of the version it is unpacked in — sorted with the package's own paths; a flow that reads nothing
 * of a dependency hashes exactly as before. The shape assumed (P2-02b test-author): {@code FlowHash.of(Path dir, Map<String, Path>
 * dependencies, Path yaml, Collection<Path> reads, EndpointConfig from, Collection<EndpointConfig> to)}, {@code dependencies} as
 * the build's {@code StepResources} got them (name → directory).
 */
class FlowHashTest {

    @TempDir Path tmp;

    static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theLinesAreVersionedSortedAndPackageRelative() throws Exception {
        Path pkg = tmp.resolve("pkg");
        Files.createDirectories(pkg.resolve("flows/xsl"));
        Files.writeString(pkg.resolve("flows/orders.yaml"), "id: orders\n");
        Files.writeString(pkg.resolve("flows/main.xsl"), "main");
        Files.writeString(pkg.resolve("flows/xsl/lib.xsl"), "lib");
        Files.createDirectories(pkg.resolve("flows/lookup"));   // entries created neither sorted nor reverse-sorted: c, a, d (a directory), b
        Files.writeString(pkg.resolve("flows/lookup/c.txt"), "c");
        Files.writeString(pkg.resolve("flows/lookup/a.txt"), "a");
        Files.createDirectories(pkg.resolve("flows/lookup/d"));
        Files.writeString(pkg.resolve("flows/lookup/b.txt"), "b");
        Files.writeString(pkg.resolve("flows/unread.xsl"), "unread");
        Path flows = pkg.resolve("flows").toAbsolutePath();
        List<Path> reads = List.of(flows.resolve("xsl/lib.xsl"), flows.resolve("main.xsl"), flows.resolve("lookup"), flows.resolve("data/missing.txt"),
                flows.resolve("main.xsl"));
        Map<String, Object> tags = new LinkedHashMap<>();   // every map in reverse key order
        tags.put("b", "2");
        tags.put("a", "1");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("y", 1);
        item.put("x", 2);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("tags", tags);
        options.put("path", "/orders");
        options.put("list", List.of(item, "z"));
        options.put("maxBodyBytes", 4096L);
        options.put("flow", "shop/orders");
        EndpointConfig from = new EndpointConfig("orders-in", URI.create("doors://orders-in"), options);
        EndpointConfig partner = new EndpointConfig("partner", URI.create("rec://partner"), Map.of("url", "rec://partner"));
        EndpointConfig archive = new EndpointConfig("archive", URI.create("rec://archive"), Map.of("url", "rec://archive"));
        Path relativeDir = Path.of("").toAbsolutePath().relativize(pkg.toAbsolutePath());

        String hash = FlowHash.of(relativeDir, Map.of(), pkg.resolve("flows/orders.yaml"), reads, from, List.of(partner, archive));

        String text = "flottila-flow-hash 2\n"
                + "yaml " + sha256("id: orders\n") + "\n"
                + "absent flows/data/missing.txt\n"
                + "dir flows/lookup [\"a.txt\",\"b.txt\",\"c.txt\",\"d\"]\n"
                + "file flows/main.xsl " + sha256("main") + "\n"
                + "file flows/xsl/lib.xsl " + sha256("lib") + "\n"
                + "endpoint orders-in doors {\"flow\":\"shop/orders\",\"list\":[{\"x\":2,\"y\":1},\"z\"],\"maxBodyBytes\":4096,\"path\":\"/orders\","
                + "\"tags\":{\"a\":\"1\",\"b\":\"2\"}}\n"
                + "endpoint archive rec {\"url\":\"rec://archive\"}\n"
                + "endpoint partner rec {\"url\":\"rec://partner\"}\n";
        assertEquals("sha256:" + sha256(text), hash,
                "the version line, the flow file's bytes (not its path), each read path once by path (a missing one absent, a directory with its sorted entries, the"
                        + " unread file nowhere), from, then the endpoints sent to by id, options with sorted keys at every level, lists in order");
    }

    /** P2-02a (D-078): the text starts with the version line {@code flottila-flow-hash 2}; the yaml line is {@code yaml <sha256>}. */
    @Test
    void theFirstLineIsVersion2() throws Exception {
        Path pkg = tmp.resolve("pkg");
        Files.createDirectories(pkg.resolve("urn-a"));
        Files.writeString(pkg.resolve("urn-a/orders.yaml"), "id: orders\n");
        EndpointConfig from = new EndpointConfig("orders-in", URI.create("doors://orders-in"), Map.of("flow", "shop/orders"));

        String hash = FlowHash.of(pkg, Map.of(), pkg.resolve("urn-a/orders.yaml"), List.of(), from, List.of());

        assertEquals("sha256:" + sha256("flottila-flow-hash 2\nyaml " + sha256("id: orders\n") + "\nendpoint orders-in doors {\"flow\":\"shop/orders\"}\n"), hash,
                "version 2, the yaml's bytes without its path, the from endpoint");
    }

    /**
     * P2-02a (D-078): the same flow yaml at another path in the package hashes the same — a flow file moved alone is no change —
     * while a read file's path still counts, and the yaml's bytes do.
     */
    @Test
    void theFlowFilesPathIsNotHashed() throws Exception {
        Path pkg = tmp.resolve("pkg");
        for (String file : List.of("flows/orders.yaml", "urn-a/sub/orders.yaml", "urn-b/orders.yaml", "shared/main.xsl", "flows/main.xsl")) {
            Files.createDirectories(pkg.resolve(file).getParent());
        }
        Files.writeString(pkg.resolve("flows/orders.yaml"), "id: orders\n");
        Files.writeString(pkg.resolve("urn-a/sub/orders.yaml"), "id: orders\n");
        Files.writeString(pkg.resolve("urn-b/orders.yaml"), "id: orders # edited\n");
        Files.writeString(pkg.resolve("flows/main.xsl"), "main");
        Files.writeString(pkg.resolve("shared/main.xsl"), "main");
        EndpointConfig from = new EndpointConfig("orders-in", URI.create("doors://orders-in"), Map.of("flow", "shop/orders"));
        List<Path> reads = List.of(pkg.resolve("flows/main.xsl"));

        String atFlows = FlowHash.of(pkg, Map.of(), pkg.resolve("flows/orders.yaml"), reads, from, List.of());
        String moved = FlowHash.of(pkg, Map.of(), pkg.resolve("urn-a/sub/orders.yaml"), reads, from, List.of());

        assertEquals(atFlows, moved, "the same yaml bytes in another folder: the same hash");
        assertNotEquals(atFlows, FlowHash.of(pkg, Map.of(), pkg.resolve("urn-b/orders.yaml"), reads, from, List.of()), "witness: other yaml bytes, another hash");
        assertNotEquals(atFlows, FlowHash.of(pkg, Map.of(), pkg.resolve("flows/orders.yaml"), List.of(pkg.resolve("shared/main.xsl")), from, List.of()),
                "witness: the same bytes read from another path, another hash");
    }

    /** A path outside the package can never be part of a flow's hash: StepResources confines it already; the hash refuses it too. */
    @Test
    void aReadOutsideThePackageIsRefused() throws Exception {
        Path pkg = tmp.resolve("pkg");
        Files.createDirectories(pkg.resolve("flows"));
        Files.writeString(pkg.resolve("flows/orders.yaml"), "id: orders\n");
        Path beside = tmp.resolve("pkg-b/flows/x.xsl");   // a sibling whose name starts with the package's: outside all the same
        Files.createDirectories(beside.getParent());
        Files.writeString(beside, "x");
        EndpointConfig from = new EndpointConfig("orders-in", URI.create("doors://orders-in"), Map.of("flow", "shop/orders"));
        Path root = pkg.toAbsolutePath().normalize();

        for (Path outside : List.of(pkg.resolve("flows/../../pkg-b/flows/x.xsl"), beside, tmp.resolve("missing.txt"))) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> FlowHash.of(pkg, Map.of(), pkg.resolve("flows/orders.yaml"), List.of(pkg.resolve("flows/orders.yaml"), outside), from, List.of()),
                    "refused: " + outside);
            assertTrue(refused.getMessage().contains(outside.toAbsolutePath().normalize().toString()) && refused.getMessage().contains(root.toString()),
                    "the refusal names the path and the package directory: " + refused.getMessage());
        }
        assertTrue(FlowHash.of(pkg, Map.of(), pkg.resolve("flows/orders.yaml"), List.of(pkg.resolve("flows/x/../orders.yaml")), from, List.of()).startsWith("sha256:"),
                "witness: a path that normalises inside the package is hashed");
    }

    // --- P2-02b (D-087): dependency reads

    /**
     * Reads in common's and util's directories ({@code packages/<name>/<sha256>/}, as unpacked) hash as {@code <dep>:<path>} lines —
     * a file, a listed folder, a probe of a missing file — sorted by that text together with the package's own paths, the same
     * relative path in two dependencies two lines, a path read twice once; a path in a directory no root holds (another version of
     * common) is refused.
     */
    @Test
    void aDependencyReadHashesAsNameColonPath() throws Exception {
        Path data = tmp.resolve("data/packages");
        Path pkg = data.resolve("invoice/" + "1".repeat(64));
        Path common = data.resolve("common/" + "c".repeat(64));
        Path util = data.resolve("util/" + "a".repeat(64));
        Path otherCommon = data.resolve("common/" + "d".repeat(64));
        for (Path d : List.of(pkg.resolve("flows"), common.resolve("xslt"), common.resolve("lookup"), util.resolve("xslt"), otherCommon.resolve("xslt"))) {
            Files.createDirectories(d);
        }
        Files.writeString(pkg.resolve("flows/invoice.yaml"), "id: invoice\n");
        Files.writeString(pkg.resolve("flows/local.xsl"), "local");
        Files.writeString(common.resolve("xslt/to-ubl.xsl"), "to-ubl");
        Files.writeString(common.resolve("xslt/lib.xsl"), "lib");
        Files.writeString(common.resolve("lookup/b.txt"), "b");
        Files.writeString(common.resolve("lookup/a.txt"), "a");
        Files.writeString(util.resolve("xslt/to-ubl.xsl"), "util's to-ubl");
        Files.writeString(otherCommon.resolve("xslt/to-ubl.xsl"), "to-ubl");
        EndpointConfig from = new EndpointConfig("invoice-in", URI.create("doors://invoice-in"), Map.of("flow", "invoice/invoice"));
        Map<String, Path> deps = Map.of("common", common, "util", util);
        List<Path> reads = List.of(util.resolve("xslt/to-ubl.xsl"), common.resolve("xslt/lib.xsl"), pkg.resolve("flows/local.xsl"), common.resolve("missing.txt"),
                common.resolve("xslt/to-ubl.xsl"), common.resolve("lookup"), common.resolve("xslt/lib.xsl"));

        String hash = FlowHash.of(pkg, deps, pkg.resolve("flows/invoice.yaml"), reads, from, List.of());

        String text = "flottila-flow-hash 2\n"
                + "yaml " + sha256("id: invoice\n") + "\n"
                + "dir common:lookup [\"a.txt\",\"b.txt\"]\n"
                + "absent common:missing.txt\n"
                + "file common:xslt/lib.xsl " + sha256("lib") + "\n"
                + "file common:xslt/to-ubl.xsl " + sha256("to-ubl") + "\n"
                + "file flows/local.xsl " + sha256("local") + "\n"
                + "file util:xslt/to-ubl.xsl " + sha256("util's to-ubl") + "\n"
                + "endpoint invoice-in doors {\"flow\":\"invoice/invoice\"}\n";
        assertEquals("sha256:" + sha256(text), hash, "each dependency read as <dep>:<path from its root>, sorted with the package's paths, once each");

        Path sibling = otherCommon.resolve("xslt/to-ubl.xsl");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> FlowHash.of(pkg, deps, pkg.resolve("flows/invoice.yaml"), List.of(sibling), from, List.of()), "another version of common is no root");
        assertTrue(refused.getMessage().contains(sibling.toAbsolutePath().normalize().toString()), "the refusal names the path: " + refused.getMessage());
    }

    /**
     * A flow that reads only its package's files hashes exactly as before — version 2, no line for a dependency it was given and
     * did not read — and as the same flow built without dependencies.
     */
    @Test
    void aFlowWithoutDependencyReadsHashesAsBefore() throws Exception {
        Path pkg = tmp.resolve("packages/invoice/" + "1".repeat(64));
        Path common = tmp.resolve("packages/common/" + "c".repeat(64));
        Files.createDirectories(pkg.resolve("flows"));
        Files.createDirectories(common.resolve("xslt"));
        Files.writeString(pkg.resolve("flows/invoice.yaml"), "id: invoice\n");
        Files.writeString(pkg.resolve("flows/main.xsl"), "main");
        Files.writeString(common.resolve("xslt/to-ubl.xsl"), "to-ubl");
        EndpointConfig from = new EndpointConfig("invoice-in", URI.create("doors://invoice-in"), Map.of("flow", "invoice/invoice"));
        List<Path> reads = List.of(pkg.resolve("flows/main.xsl"));

        String withCommon = FlowHash.of(pkg, Map.of("common", common), pkg.resolve("flows/invoice.yaml"), reads, from, List.of());

        assertEquals("sha256:" + sha256("flottila-flow-hash 2\nyaml " + sha256("id: invoice\n") + "\nfile flows/main.xsl " + sha256("main")
                + "\nendpoint invoice-in doors {\"flow\":\"invoice/invoice\"}\n"), withCommon, "the text as before P2-02b: no dependency line");
        assertEquals(FlowHash.of(pkg, Map.of(), pkg.resolve("flows/invoice.yaml"), reads, from, List.of()), withCommon, "and as without the dependency");
    }
}
