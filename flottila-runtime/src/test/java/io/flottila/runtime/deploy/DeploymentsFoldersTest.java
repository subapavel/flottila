package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02a (D-078, D-060 d): a deploy finds a package's artifacts by their kind in any folder, refuses a folder too deep or not a slug
 * and an id declared in two folders (422, the running version untouched), builds every flow over the package directory — a step's
 * file reference is a path from the package root, an include inside a stylesheet relative to the including file, both confined to the
 * package — and lists the package's folders and artifacts. A flow file moved alone keeps its effective hash (FlowHash v2): nothing
 * swaps.
 *
 * <p>The shape assumed (P2-02a test-author): {@code DeployedPackage.folders()} and {@code DeployedPackage.artifacts()} hold the
 * schemas' {@code Package.Folder} ({@code path()}, {@code namespace()}, {@code description()} as {@code Optional<String>}) and
 * {@code Package.Artifact} ({@code path()}, {@code kind()}, {@code id()} as {@code Optional<String>}), each sorted by path.
 */
class DeploymentsFoldersTest {

    static final String XSL = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\">";

    @TempDir Path tmp;
    Path data;
    Path archives;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    Deployments deployments;

    final List<Deployments> opened = new ArrayList<>();

    @BeforeEach
    void wire() {
        data = tmp.resolve("data");
        archives = tmp.resolve("archives");
        deployments = deploymentsAt(data);
    }

    /** A node's deployments whose {@code FLOTTILA_DATA_DIR} is {@code dataDir}, verbatim; closed after the case. */
    Deployments deploymentsAt(Path dataDir) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", dataDir.toString());
        env.put("FLOTTILA_STOP_DEADLINE", "PT5S");
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
        SaxonXml xml = new SaxonXml();
        List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        Deployments d = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), steps, xml.languages(),
                new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L);
        opened.add(d);
        return d;
    }

    @AfterEach
    void close() throws Exception {
        doors.releaseAll();
        for (Deployments d : opened) OnVirtualThread.call(() -> { d.close(); return null; });
    }

    // --- fixture

    /** A stylesheet that writes {@code text}. */
    static String standalone(String text) {
        return XSL + "<xsl:output method=\"text\"/><xsl:template match=\"/\">" + text + "</xsl:template></xsl:stylesheet>";
    }

    /** Map with {@code stylesheet}, then send to {@code partner}. */
    static JsonArray mapWith(String stylesheet) {
        return new JsonArray().add(new JsonObject().put("type", "xslt").put("name", "map").put("stylesheet", stylesheet))
                .add(new JsonObject().put("type", "to").put("name", "send").put("endpoint", "partner"));
    }

    /**
     * {@code shop} 1.0.0 with two namespace folders: {@code urn-a/} ({@code folder.yaml}: namespace {@code urn:example:a}, a
     * description) holds {@code orders.yaml} — flow {@code orders-a} from {@code a-in} — and {@code order.xsl} writing {@code A};
     * {@code urn-b/} ({@code folder.yaml}: namespace {@code urn:example:b}) holds a file of each of these names — flow {@code orders-b}
     * from {@code b-in}, a stylesheet writing {@code B}. Each flow maps with its folder's stylesheet by its path from the root and
     * sends to {@code partner}, whose reply is what was sent. The endpoints stay in {@code endpoints/}.
     */
    static PackageDraft twoNamespaces() {
        return PackageDraft.of("shop", "1.0.0")
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .endpoint("a-in", "in", new JsonObject().put("path", "/a"))
                .endpoint("b-in", "in", new JsonObject().put("path", "/b"))
                .flow("orders-a", "1.0.0", "sync", "a-in", 4096, mapWith("urn-a/order.xsl")).move("flows/orders-a.yaml", "urn-a/orders.yaml")
                .flow("orders-b", "1.0.0", "sync", "b-in", 4096, mapWith("urn-b/order.xsl")).move("flows/orders-b.yaml", "urn-b/orders.yaml")
                .file("urn-a/order.xsl", standalone("A"))
                .file("urn-b/order.xsl", standalone("B"))
                .file("urn-a/folder.yaml", "schemaVersion: 1\nkind: folder\nnamespace: urn:example:a\ndescription: namespace a\n")
                .file("urn-b/folder.yaml", "schemaVersion: 1\nkind: folder\nnamespace: urn:example:b\n");
    }

    DeployResult deploy(PackageDraft p) throws Exception {
        Path archive = p.zipTo(archives);
        return OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), archive));
    }

    DeployRefusedException refused(PackageDraft p) {
        Path archive = p.zipTo(archives);
        return assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), archive)),
                "the deploy is refused");
    }

    /** What the consumer built last for {@code flow} answers a message: the partner's reply, which is what was sent to it. */
    String reply(String flow) throws Exception {
        Doors.Door door = doors.door(flow);
        Ack a = within(door.deliver(rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"))), door + "'s answer");
        return assertInstanceOf(Ack.Reply.class, a, door + " replies: " + a).reply().body().whole().toString();
    }

    DeployedFlow listedFlow(String id) {
        return deployments.flows().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no flow " + id + ": " + deployments.flows()));
    }

    DeployedPackage listedShop() {
        List<DeployedPackage> packages = deployments.packages();
        assertEquals(1, packages.size(), "one package listed: " + packages);
        return packages.get(0);
    }

    /** Each folder as {@code <path> ns=<namespace|-> desc=<description|->}, in the order listed. */
    static List<String> folders(DeployedPackage p) {
        return p.folders().stream().map(f -> f.path() + " ns=" + f.namespace().orElse("-") + " desc=" + f.description().orElse("-")).toList();
    }

    /** Each artifact as {@code <path> <kind> <id|->}, in the order listed. */
    static List<String> artifacts(DeployedPackage p) {
        return p.artifacts().stream().map(a -> a.path() + " " + a.kind() + " " + a.id().orElse("-")).toList();
    }

    void assertNoAbsolutePath(DeployRefusedException refused) throws IOException {
        for (String absolute : List.of(tmp.toString(), tmp.toRealPath().toString())) {
            assertFalse(refused.error().contains(absolute), "no absolute path in the error: " + refused.error());
            for (String l : refused.problems()) assertFalse(l.contains(absolute), "no absolute path (" + absolute + ") in '" + l + "'");
        }
    }

    // --- cases

    /** Two namespace folders, a flow and a same-named stylesheet in each: both deploy, each maps with its own folder's file, the tree is listed. */
    @Test
    void aPackageWithTwoNamespaceFoldersDeploysAndListsItsTree() throws Exception {
        DeployResult r = deploy(twoNamespaces());

        assertEquals(List.of("shop/orders-a", "shop/orders-b"), r.added(), "both flows, found in their folders: " + r);
        assertEquals("A", reply("shop/orders-a"), "orders-a maps with urn-a/order.xsl");
        assertEquals("B", reply("shop/orders-b"), "orders-b maps with urn-b/order.xsl");
        List<String> folders = List.of("endpoints ns=- desc=-", "urn-a ns=urn:example:a desc=namespace a", "urn-b ns=urn:example:b desc=-");
        List<String> artifacts = List.of("endpoints/a-in.yaml endpoint a-in", "endpoints/b-in.yaml endpoint b-in", "endpoints/partner.yaml endpoint partner",
                "urn-a/orders.yaml flow orders-a", "urn-b/orders.yaml flow orders-b");
        assertEquals(folders, folders(r.pkg()), "the deploy's answer lists every folder below the root, by path");
        assertEquals(artifacts, artifacts(r.pkg()), "and every artifact, by path");
        assertEquals(folders, folders(listedShop()), "packages() lists the same folders");
        assertEquals(artifacts, artifacts(listedShop()), "and the same artifacts");
        assertTrue(listedShop().files().stream().anyMatch(f -> f.path().equals("urn-a/folder.yaml")), "files lists every file as before: " + listedShop().files());
    }

    /** v2 moves orders-a's yaml, byte for byte, to another folder: its effective hash stays, nothing swaps, the running consumer serves on. */
    @Test
    void movingAFlowFileToAnotherFolderSwapsNothing() throws Exception {
        deploy(twoNamespaces());
        String hash = listedFlow("shop/orders-a").hash();
        Doors.Door running = doors.door("shop/orders-a");

        DeployResult r = deploy(twoNamespaces().move("urn-a/orders.yaml", "urn-b/orders-a.yaml"));

        assertEquals(List.of(), r.added(), r.toString());
        assertEquals(List.of(), r.swapped(), "the flow file moved alone is no change: " + r);
        assertEquals(List.of(), r.removed(), r.toString());
        assertEquals(hash, listedFlow("shop/orders-a").hash(), "the same effective hash");
        assertSame(running, doors.door("shop/orders-a"), "no consumer was built for v2's orders-a");
        assertEquals(0, running.stops(), "v1's consumer was not stopped");
        assertEquals("A", reply("shop/orders-a"));
        assertTrue(artifacts(r.pkg()).contains("urn-b/orders-a.yaml flow orders-a"), "the listing shows the file where it lies now: " + artifacts(r.pkg()));
    }

    /** v2 moves orders-a's stylesheet to another folder and edits the reference: orders-a swaps, orders-b does not, both map as before. */
    @Test
    void movingAStylesheetAndItsReferenceSwapsTheFlow() throws Exception {
        deploy(twoNamespaces());
        String hash = listedFlow("shop/orders-a").hash();
        PackageDraft v2 = twoNamespaces().move("urn-a/order.xsl", "shared/order-a.xsl");
        v2.doc("urn-a/orders.yaml").getJsonArray("steps").getJsonObject(0).put("stylesheet", "shared/order-a.xsl");

        DeployResult r = deploy(v2);

        assertEquals(List.of("shop/orders-a"), r.swapped(), "the reference and the file moved: a change: " + r);
        assertEquals(List.of(), r.added(), r.toString());
        assertEquals(List.of(), r.removed(), r.toString());
        assertNotEquals(hash, listedFlow("shop/orders-a").hash());
        assertEquals("A", reply("shop/orders-a"), "v2's orders-a maps with shared/order-a.xsl");
        assertEquals("B", reply("shop/orders-b"));
    }

    /** urn-b's flow declares urn-a's flow id and urn-b holds a second partner endpoint: 422, a line per id naming both files; nothing runs. */
    @Test
    void aDuplicateIdInTwoFoldersIs422NamingBothFiles() throws Exception {
        PackageDraft p = twoNamespaces();
        p.doc("urn-b/orders.yaml").put("id", "orders-a");
        p.file("urn-b/partner.yaml", p.endpointDoc("partner").copy().put("config", new JsonObject().put("url", "rec://partner-b")).encodePrettily());

        DeployRefusedException refused = refused(p);

        assertEquals(Kind.INVALID, refused.kind(), refused.getMessage());
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("'orders-a'") && l.contains("urn-a/orders.yaml") && l.contains("urn-b/orders.yaml")),
                "the flow id and both files: " + refused.problems());
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("'partner'") && l.contains("endpoints/partner.yaml") && l.contains("urn-b/partner.yaml")),
                "the endpoint id and both files: " + refused.problems());
        assertEquals(List.of(), deployments.packages(), "nothing is listed");
    }

    /** A redeploy with a folder five deep, or one whose name is no slug, is 422 with its line; v1 stays listed and serves. */
    @Test
    void aTooDeepOrNoSlugFolderIs422AndTheOldVersionRuns() throws Exception {
        DeployResult v1 = deploy(twoNamespaces());
        for (List<String> broken : List.of(List.of("a/b/c/d/e/readme.txt", "a/b/c/d/e: 5 folders deep, at most 4"),
                List.of("Urn-C/readme.txt", "Urn-C: folder name 'Urn-C' is not a slug"))) {
            PackageDraft v2 = twoNamespaces().file(broken.get(0), "x");
            v2.manifest().put("version", "1.0.1");

            DeployRefusedException refused = refused(v2);

            assertEquals(Kind.INVALID, refused.kind(), refused.getMessage());
            assertTrue(refused.problems().contains(broken.get(1)), "the line '" + broken.get(1) + "': " + refused.problems());
            DeployedPackage listed = listedShop();
            assertEquals("1.0.0", listed.version(), "v1 stays listed");
            assertEquals(v1.pkg().archiveSha256(), listed.archiveSha256(), "v1's archive");
            assertEquals(DeployedFlow.State.RUNNING, listedFlow("shop/orders-a").state());
            assertEquals("A", reply("shop/orders-a"), "v1 serves");
        }
    }

    /**
     * orders-a (in urn-a/) maps with {@code urn-b/xsl/map.xsl}, a path from the root, which includes {@code lib.xsl}: the one next to
     * it, not the root's or urn-a's.
     */
    @Test
    void aFlowReadsAnotherFoldersFileByReferenceAndByInclude() throws Exception {
        PackageDraft p = twoNamespaces();
        p.doc("urn-a/orders.yaml").getJsonArray("steps").getJsonObject(0).put("stylesheet", "urn-b/xsl/map.xsl");
        p.file("urn-b/xsl/map.xsl", XSL + "<xsl:include href=\"lib.xsl\"/><xsl:output method=\"text\"/><xsl:template match=\"/\">map:<xsl:call-template name=\"lib\"/>"
                + "</xsl:template></xsl:stylesheet>");
        p.file("urn-b/xsl/lib.xsl", XSL + "<xsl:template name=\"lib\">urn-b/xsl</xsl:template></xsl:stylesheet>");
        p.file("lib.xsl", XSL + "<xsl:template name=\"lib\">root</xsl:template></xsl:stylesheet>");
        p.file("urn-a/lib.xsl", XSL + "<xsl:template name=\"lib\">urn-a</xsl:template></xsl:stylesheet>");

        DeployResult r = deploy(p);

        assertEquals(List.of("shop/orders-a", "shop/orders-b"), r.added(), r.toString());
        assertEquals("map:urn-b/xsl", reply("shop/orders-a"), "the stylesheet by its path from the root, its include next to it");
    }

    /**
     * A flow whose stylesheet reference leaves the package, and one whose stylesheet includes a file outside it: 422 each, naming the
     * flow, never an absolute path; nothing is listed.
     */
    @Test
    void aReferenceLeavingThePackageIs422WithoutAnAbsolutePath() throws Exception {
        PackageDraft byReference = twoNamespaces();
        byReference.doc("urn-a/orders.yaml").getJsonArray("steps").getJsonObject(0).put("stylesheet", "../outside.xsl");

        DeployRefusedException reference = refused(byReference);

        assertEquals(Kind.INVALID, reference.kind(), reference.getMessage());
        assertTrue(reference.problems().stream().anyMatch(l -> l.contains("urn-a/orders.yaml") && l.contains("'../outside.xsl'") && l.contains("leaves the package")),
                "the reference leaves the package: " + reference.problems());
        assertNoAbsolutePath(reference);

        PackageDraft byInclude = twoNamespaces().file("urn-a/order.xsl", XSL + "<xsl:include href=\"../../escape.xsl\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\">A</xsl:template></xsl:stylesheet>");

        DeployRefusedException include = refused(byInclude);

        assertEquals(Kind.INVALID, include.kind(), include.getMessage());
        assertTrue(include.problems().stream().anyMatch(l -> l.contains("orders-a") && l.contains("../../escape.xsl")),
                "a line names the flow and the include: " + include.problems());
        assertNoAbsolutePath(include);
        assertEquals(List.of(), deployments.packages(), "nothing is listed");
    }

    /** The include escape below a {@code FLOTTILA_DATA_DIR} given relative, or absolute but not normalised: still no absolute path in any line. */
    @ParameterizedTest(name = "FLOTTILA_DATA_DIR {0}")
    @ValueSource(strings = {"not normalised", "relative"})
    void anIncludeEscapeNamesNoAbsolutePathWhateverTheFormOfTheDataDir(String form) throws Exception {
        Path dataDir = form.equals("relative")
                ? Path.of("").toAbsolutePath().relativize(tmp.resolve("odd-data"))
                : Files.createDirectories(tmp.resolve("x")).resolve("../odd-data");   // x exists: the kernel resolves x/.. only then
        assertTrue(form.equals("relative") ? !dataDir.isAbsolute() : !dataDir.equals(dataDir.normalize()), "fixture: the data dir is " + form + ": " + dataDir);
        Deployments odd = deploymentsAt(dataDir);
        Path archive = twoNamespaces().file("urn-a/order.xsl", XSL + "<xsl:include href=\"../../escape.xsl\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\">A</xsl:template></xsl:stylesheet>").zipTo(archives);

        DeployRefusedException include = assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> odd.deploy("shop", Optional.empty(), archive)), "the deploy is refused");

        assertEquals(Kind.INVALID, include.kind(), include.getMessage());
        assertTrue(include.problems().stream().anyMatch(l -> l.contains("orders-a") && l.contains("../../escape.xsl")),
                "a line names the flow and the include: " + include.problems());
        assertNoAbsolutePath(include);
    }
}
