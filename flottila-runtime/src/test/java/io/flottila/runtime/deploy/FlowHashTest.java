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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09d2a (D-063): the effective hash's exact text, which P1-09c may persist — {@code sha256:} over the versioned lines, the
 * flow file first, then every path the build read once, sorted by its package-relative path ({@code file}, {@code dir} with its
 * sorted entry names, {@code absent}), then the {@code from} endpoint and the endpoints sent to, by id, each with its options as
 * JSON whose keys are sorted at every level while lists keep their order. {@code DeploymentsHashTest} shows what changes it
 * through a deploy; a line no deploy could tell apart (a probe of a missing file sits in a file that is hashed too) is pinned here.
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

        String hash = FlowHash.of(relativeDir, pkg.resolve("flows/orders.yaml"), reads, from, List.of(partner, archive));

        String text = "flottila-flow-hash 1\n"
                + "yaml flows/orders.yaml " + sha256("id: orders\n") + "\n"
                + "absent flows/data/missing.txt\n"
                + "dir flows/lookup [\"a.txt\",\"b.txt\",\"c.txt\",\"d\"]\n"
                + "file flows/main.xsl " + sha256("main") + "\n"
                + "file flows/xsl/lib.xsl " + sha256("lib") + "\n"
                + "endpoint orders-in doors {\"flow\":\"shop/orders\",\"list\":[{\"x\":2,\"y\":1},\"z\"],\"maxBodyBytes\":4096,\"path\":\"/orders\","
                + "\"tags\":{\"a\":\"1\",\"b\":\"2\"}}\n"
                + "endpoint archive rec {\"url\":\"rec://archive\"}\n"
                + "endpoint partner rec {\"url\":\"rec://partner\"}\n";
        assertEquals("sha256:" + sha256(text), hash,
                "the version line, the flow file, each read path once by path (a missing one absent, a directory with its sorted entries, the"
                        + " unread file nowhere), from, then the endpoints sent to by id, options with sorted keys at every level, lists in order");
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
                    () -> FlowHash.of(pkg, pkg.resolve("flows/orders.yaml"), List.of(pkg.resolve("flows/orders.yaml"), outside), from, List.of()),
                    "refused: " + outside);
            assertTrue(refused.getMessage().contains(outside.toAbsolutePath().normalize().toString()) && refused.getMessage().contains(root.toString()),
                    "the refusal names the path and the package directory: " + refused.getMessage());
        }
        assertTrue(FlowHash.of(pkg, pkg.resolve("flows/orders.yaml"), List.of(pkg.resolve("flows/x/../orders.yaml")), from, List.of()).startsWith("sha256:"),
                "witness: a path that normalises inside the package is hashed");
    }
}
