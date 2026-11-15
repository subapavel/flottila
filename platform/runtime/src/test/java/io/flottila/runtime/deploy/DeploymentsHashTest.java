package io.flottila.runtime.deploy;

import io.flottila.api.Runtime;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.steps.validate.XsdStepFactory;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.steps.xslt.XsltStepFactory;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09d2a (D-063, D-060 c): every flow a deploy starts carries its effective hash, {@code sha256:} and 64 lower-case hex digits,
 * over its yaml's bytes, every package file its build read (through {@code StepResources.file} / {@code resolve}: the file a step
 * names and what that file includes or imports — an at-least-once flow's files are read by its worker's build) and its {@code from}
 * and {@code to} endpoints' config with this node's environment laid over it, key order aside. package.yaml, the package version,
 * the deploy's revision, the archive's bytes and directory, other environments' overlays and files no build read are not part of it.
 *
 * <p>A redeploy of the same name is still refused here (it comes with P1-09d2b), so each variant of the package is deployed on a
 * node of its own — its own data directory, its own {@link Doors}, its own xslt and xsd step factories (their compile caches are
 * per factory, keyed by the main file's content: FIX-17) — and the hashes are compared by flow id across nodes. Each case asserts
 * the exact set of flows whose hash changed, so "changes it" and "changes only its readers" are one assertion.
 *
 * <p>The shape assumed (P1-09d2a test-author): {@code DeployedFlow.hash()} a {@code String}, {@code DeployedFlow.state()} of the
 * nested enum {@code DeployedFlow.State {RUNNING, FAILED}}; {@code GET /admin/flows} items gain {@code "hash"} (the same string) and
 * {@code "state"} (the enum's name, as {@code /health} writes {@code UP}).
 */
class DeploymentsHashTest {

    static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
    static final String TOKEN = "s3cret-token";
    static final String XSL = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\">";
    static final String XS = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">";
    static final String ORDERS = "shop/orders";
    static final String AUDIT = "shop/audit";
    static final String PING = "shop/ping";

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final List<Deployments> nodes = new ArrayList<>();

    @AfterEach
    void closeNodes() throws Exception {
        for (Deployments d : nodes) OnVirtualThread.call(() -> { d.close(); return null; });
    }

    // --- fixture

    /** A node of its own: data directory, {@link Doors}, step factories (xslt + xsd) and in-memory stores; environment {@code dev}. */
    Deployments node() {
        SaxonXml xml = new SaxonXml();
        return node(Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml);
    }

    /** A node of its own over {@code steps} (xslt + xsd), which another node may share: one compile cache for both (FIX-17). */
    Deployments node(List<StepFactory> steps, SaxonXml xml) {
        int n = nodes.size() + 1;
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-" + n);
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("node-" + n).toString());
        env.put("FLOTTILA_STOP_DEADLINE", "PT5S");
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
        Doors doors = new Doors();
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        Deployments d = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), steps, xml.languages(),
                new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L);
        nodes.add(d);
        return d;
    }

    static String mainXsl(String extra) {
        return XSL + "<xsl:include href=\"xsl/lib.xsl\"/>" + extra
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:call-template name=\"lib\"/></xsl:template></xsl:stylesheet>";
    }

    static String template(String name, String text) {
        return XSL + "<xsl:template name=\"" + name + "\">" + text + "</xsl:template></xsl:stylesheet>";
    }

    static final String ORDER_XSD = XS + "<xs:include schemaLocation=\"xsd/types.xsd\"/><xs:element name=\"Order\" type=\"Code\"/></xs:schema>";

    static String typesXsd(int length) {
        return XS + "<xs:simpleType name=\"Code\"><xs:restriction base=\"xs:string\"><xs:length value=\"" + length + "\"/></xs:restriction></xs:simpleType></xs:schema>";
    }

    static JsonObject step(String type, String name, String key, String value) {
        return new JsonObject().put("type", type).put("name", name).put(key, value);
    }

    static JsonObject overlay(String env, String key, String value) {
        return new JsonObject().put(env, new JsonObject().put("config", new JsonObject().put(key, value)));
    }

    /**
     * {@code name} 1.0.0: {@code orders} (sync) maps with {@code flows/main.xsl}, which includes {@code flows/xsl/lib.xsl}, and sends
     * to {@code partner}; {@code audit} (at-least-once) checks against {@code flows/order.xsd}, which includes
     * {@code flows/xsd/types.xsd}, and sends to {@code archive}; {@code ping} (at-most-once) sends to {@code partner} and reads no
     * file. {@code spare} is an out endpoint no flow names; {@code flows/unused.xsl} and {@code notes/readme.txt} are files no step names.
     */
    static PackageDraft shop(String name) {
        return PackageDraft.of(name, "1.0.0")
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders").put("level", "low").put("tags", new JsonObject().put("a", "1").put("b", "2")))
                .endpoint("audit-in", "in", new JsonObject().put("path", "/audit"))
                .endpoint("ping-in", "in", new JsonObject().put("path", "/ping"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner").put("level", "low"))
                .endpoint("archive", "out", new JsonObject().put("url", "rec://archive"))
                .endpoint("spare", "out", new JsonObject().put("url", "rec://spare"))
                .flow("orders", "2.1.0", "sync", "orders-in", 4096,
                        new JsonArray().add(step("xslt", "map", "stylesheet", "flows/main.xsl")).add(step("to", "send", "endpoint", "partner")))
                .flow("audit", "1.0.3", "at-least-once", "audit-in", 1024,
                        new JsonArray().add(step("xsd", "check", "schema", "flows/order.xsd")).add(step("to", "keep", "endpoint", "archive")))
                .flow("ping", "0.0.1", "at-most-once", "ping-in", 2048, new JsonArray().add(step("to", "send", "endpoint", "partner")))
                .file("flows/main.xsl", mainXsl(""))
                .file("flows/xsl/lib.xsl", template("lib", "lib v1"))
                .file("flows/order.xsd", ORDER_XSD)
                .file("flows/xsd/types.xsd", typesXsd(3))
                .file("flows/unused.xsl", template("unused", "unused v1"))
                .file("notes/readme.txt", "notes v1");
    }

    static PackageDraft shop() {
        return shop("shop");
    }

    /** Deploys {@code zip} as {@code name} with {@code revision} on {@code node}; every flow's hash by its qualified id, each checked for its form. */
    Map<String, String> deploy(Deployments node, String name, byte[] zip, String revision) throws Exception {
        Path archive = PackageDraft.write(tmp.resolve("archives"), zip);
        OnVirtualThread.call(() -> node.deploy(name, Optional.of(revision), archive));
        Map<String, String> hashes = new TreeMap<>();
        for (DeployedFlow f : node.flows()) {
            if (!f.pkg().equals(name)) continue;
            assertTrue(HASH.matcher(f.hash()).matches(), "a flow's hash is sha256: and 64 lower-case hex digits: " + f.id() + " " + f.hash());
            hashes.put(f.id(), f.hash());
        }
        return hashes;
    }

    /** {@code p} deployed as {@code shop}, revision {@code r1}, on a node of its own. */
    Map<String, String> hashes(PackageDraft p) throws Exception {
        return deploy(node(), "shop", p.zip(), "r1");
    }

    /** The flows whose hash differs between {@code base} and {@code variant}. */
    static Set<String> changed(Map<String, String> base, Map<String, String> variant) {
        assertEquals(base.keySet(), variant.keySet(), "fixture: the same flows deployed");
        return base.keySet().stream().filter(id -> !base.get(id).equals(variant.get(id))).collect(Collectors.toCollection(TreeSet::new));
    }

    // --- cases

    @Test
    void sameContentSameHash() throws Exception {
        Deployments one = node();
        Deployments two = node();
        Map<String, String> first = deploy(one, "shop", shop().zip(), "r1");
        rt.clock().advance(Duration.ofHours(1));
        Map<String, String> second = deploy(two, "shop", shop().zipWithDirectories(List.of("flows/", "flows/xsl/", "flows/xsd/", "notes/")), "r2");

        assertEquals(Set.of(AUDIT, ORDERS, PING), first.keySet(), "fixture: every flow deployed");
        assertNotEquals(one.packages().get(0).archiveSha256(), two.packages().get(0).archiveSha256(),
                "fixture: the second archive's bytes differ (directory entries), so it unpacks to another <sha256> directory");
        assertEquals(3, new HashSet<>(first.values()).size(), "each flow has a hash of its own: " + first);
        assertEquals(first, second, "the same content has the same hash on another node, from other archive bytes in another directory,"
                + " under another revision and deploy time");
    }

    @Test
    void yamlOrReadFileOrIncludedFileChangesIt() throws Exception {
        Map<String, String> base = hashes(shop());

        PackageDraft yaml = shop();
        yaml.flowDoc("orders").put("description", "the yaml changed");
        assertEquals(Set.of(ORDERS), changed(base, hashes(yaml)), "the flow's yaml: that flow only");
        assertEquals(Set.of(ORDERS), changed(base, hashes(shop().file("flows/main.xsl", mainXsl("<!-- edited -->")))),
                "the stylesheet a sync flow names: its readers only");
        assertEquals(Set.of(ORDERS), changed(base, hashes(shop().file("flows/xsl/lib.xsl", template("lib", "lib v2")))),
                "the module that stylesheet includes: its readers only");
        assertEquals(Set.of(AUDIT), changed(base, hashes(shop().file("flows/order.xsd", ORDER_XSD + "<!-- edited -->"))),
                "the schema an at-least-once flow names, read by its worker's build");
        assertEquals(Set.of(AUDIT), changed(base, hashes(shop().file("flows/xsd/types.xsd", typesXsd(4)))),
                "the schema that schema includes");
    }

    @Test
    void anUnreadFileDoesNotChangeIt() throws Exception {
        Map<String, String> base = hashes(shop());

        PackageDraft unread = shop().file("flows/unused.xsl", template("unused", "unused v2")).file("notes/readme.txt", "notes v2")
                .file("flows/extra.xsd", XS + "</xs:schema>");
        assertEquals(Set.of(), changed(base, hashes(unread)),
                "a file no build read, changed (beside the stylesheet that is read, or elsewhere) or added, is in no flow's hash");
        PackageDraft bumped = shop();
        bumped.manifest().put("version", "1.0.1").put("description", "bumped");
        Deployments node = node();
        Map<String, String> bumpedHashes = deploy(node, "shop", bumped.zip(), "r9");
        assertEquals("1.0.1", node.flows().get(0).packageVersion(), "fixture: the bumped version is deployed");
        assertEquals(Set.of(), changed(base, bumpedHashes), "package.yaml, the package's version and the deploy's revision are in no flow's hash");

        // witness: the same edit to the same file counts once a build reads it
        String including = mainXsl("<xsl:include href=\"unused.xsl\"/>");
        Map<String, String> readV1 = hashes(shop().file("flows/main.xsl", including));
        Map<String, String> readV2 = hashes(shop().file("flows/main.xsl", including).file("flows/unused.xsl", template("unused", "unused v2")));
        assertEquals(Set.of(ORDERS), changed(readV1, readV2), "witness: once main.xsl includes unused.xsl, the same edit to it changes its reader's hash");
    }

    @Test
    void fromOrToEndpointConfigChangesIt() throws Exception {
        Map<String, String> base = hashes(shop());

        PackageDraft from = shop();
        from.endpointDoc("orders-in").getJsonObject("config").put("path", "/orders-v2");
        assertEquals(Set.of(ORDERS), changed(base, hashes(from)), "the from endpoint's config: the flow it feeds");
        PackageDraft nested = shop();
        nested.endpointDoc("orders-in").getJsonObject("config").getJsonObject("tags").put("a", "9");
        assertEquals(Set.of(ORDERS), changed(base, hashes(nested)), "a nested value of the from endpoint's config");
        PackageDraft devFrom = shop();
        devFrom.endpointDoc("orders-in").put("environments", overlay("dev", "level", "high"));
        assertEquals(Set.of(ORDERS), changed(base, hashes(devFrom)), "this environment's (dev) overlay on the from endpoint");

        PackageDraft to = shop();
        to.endpointDoc("partner").getJsonObject("config").put("url", "rec://partner-v2");
        assertEquals(Set.of(ORDERS, PING), changed(base, hashes(to)), "a to endpoint's config: every flow that sends to it, and only those");
        PackageDraft devTo = shop();
        devTo.endpointDoc("archive").put("environments", overlay("dev", "url", "rec://archive-dev"));
        assertEquals(Set.of(AUDIT), changed(base, hashes(devTo)), "this environment's overlay on the at-least-once flow's to endpoint");
        PackageDraft spare = shop();
        spare.endpointDoc("spare").getJsonObject("config").put("url", "rec://spare-v2");
        assertEquals(Set.of(), changed(base, hashes(spare)), "an endpoint no flow consumes from or sends to is in no flow's hash");

        // another node for the renamed package: on the same node its identical main.xsl would come from the compile cache, its
        // include unread (FIX-17), and the hashes would differ for that reason alone
        Map<String, String> renamed = deploy(node(), "shop-b", shop("shop-b").zip(), "r1");
        assertNotEquals(base.get(ORDERS), renamed.get("shop-b/orders"),
                "the from endpoint as the consumer gets it, the 'flow' option the deploy sets included: the same files under another package name");
        assertNotEquals(base.get(PING), renamed.get("shop-b/ping"), "the same for a flow that reads no file: only the 'flow' option differs");
    }

    @Test
    void anotherEnvsOverlayOrKeyOrderDoesNot() throws Exception {
        Map<String, String> base = hashes(shop());

        PackageDraft prod = shop();
        prod.endpointDoc("orders-in").put("environments", overlay("prod", "level", "high"));
        prod.endpointDoc("partner").put("environments", overlay("prod", "url", "rec://partner-prod"));
        assertEquals(Set.of(), changed(base, hashes(prod)), "another environment's overlay");
        PackageDraft reordered = shop();
        reordered.endpointDoc("orders-in").put("config", new JsonObject().put("tags", new JsonObject().put("b", "2").put("a", "1")).put("level", "low").put("path", "/orders"));
        reordered.endpointDoc("partner").put("config", new JsonObject().put("level", "low").put("url", "rec://partner"));
        assertEquals(Set.of(), changed(base, hashes(reordered)), "the same config with its keys, nested ones included, in another order");
        PackageDraft same = shop();
        same.endpointDoc("orders-in").getJsonObject("config").put("level", "high");
        same.endpointDoc("orders-in").put("environments", overlay("dev", "level", "low"));
        assertEquals(Set.of(), changed(base, hashes(same)),
                "a base value this environment's overlay sets back to the base's value: the effective config counts, not the file");

        PackageDraft dev = shop();   // witness: this environment's overlay with another value does count
        dev.endpointDoc("orders-in").put("environments", overlay("dev", "level", "high"));
        assertEquals(Set.of(ORDERS), changed(base, hashes(dev)), "witness: this environment's overlay with another value counts");
    }

    /**
     * {@code shop} whose {@code flows/main.xsl} also probes {@code flows/data/missing.txt} with {@code unparsed-text-available()}
     * and lists {@code flows/lookup/} with {@code uri-collection()}, both in static variables: the build reports a path that does
     * not exist and a directory. {@code lookup/} holds {@code a.txt}.
     */
    static PackageDraft probing() {
        return shop().file("flows/main.xsl", XSL + "<xsl:include href=\"xsl/lib.xsl\"/>"
                        + "<xsl:variable name=\"probed\" static=\"yes\" select=\"unparsed-text-available('data/missing.txt')\"/>"
                        + "<xsl:variable name=\"listed\" static=\"yes\" select=\"count(uri-collection('lookup'))\"/>"
                        + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:call-template name=\"lib\"/>|<xsl:value-of select=\"$probed\"/>|"
                        + "<xsl:value-of select=\"$listed\"/></xsl:template></xsl:stylesheet>")
                .file("flows/lookup/a.txt", "a");
    }

    /**
     * A reported path that does not exist (an {@code absent} line) or is a directory (a {@code dir} line with its sorted entry names)
     * does not fail the deploy; creating the probed file or adding an entry to the listed directory changes the reader's hash.
     */
    @Test
    void aProbedMissingFileOrListedDirectoryStillDeploys() throws Exception {
        Map<String, String> base = hashes(probing());
        assertEquals(Set.of(AUDIT, ORDERS, PING), base.keySet(), "the deploy succeeded with an absent file and a directory among the reads");
        assertEquals(Set.of(), changed(base, hashes(probing())), "the same probe and the same listing: the same hash on another node");

        assertEquals(Set.of(ORDERS), changed(base, hashes(probing().file("flows/data/missing.txt", "now here"))),
                "the probed file created: its reader's hash changes");
        assertEquals(Set.of(ORDERS), changed(base, hashes(probing().file("flows/lookup/b.txt", "b"))),
                "an entry added to the listed directory: its reader's hash changes");
    }

    // --- FIX-17: one node's step factories compile once for every flow with the same files; a hit hashes as a fresh compile

    /** The xslt and xsd step factories two nodes share, and what they compiled so far. */
    record Shared(SaxonXml xml, List<StepFactory> steps) {
        static Shared create() {
            SaxonXml xml = new SaxonXml();
            return new Shared(xml, Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList());
        }

        int stylesheets() { return ((XsltStepFactory) steps.get(0)).compiledStylesheets(); }

        int schemas() { return ((XsdStepFactory) steps.get(1)).compiledSchemas(); }
    }

    @Test
    void aCacheHitGivesTheSameHashAsAFreshCompile() throws Exception {
        Shared shared = Shared.create();
        Map<String, String> fresh = deploy(node(shared.steps(), shared.xml()), "shop", shop().zip(), "r1");
        assertEquals(1, shared.stylesheets(), "fixture: the first node compiled main.xsl");
        assertEquals(1, shared.schemas(), "fixture: the first node compiled order.xsd");

        Map<String, String> hit = deploy(node(shared.steps(), shared.xml()), "shop", shop().zip(), "r1");

        assertEquals(1, shared.stylesheets(), "fixture: the second node's build of the same files hit the shared cache");
        assertEquals(1, shared.schemas(), "fixture: the same for the schema");
        assertEquals(fresh, hit, "a flow built from the cache has the hash of the same flow compiled fresh: the hit reports the include it read");
    }

    @Test
    void aChangedIncludeOnASharedFactoryChangesOnlyItsReaders() throws Exception {
        Shared shared = Shared.create();
        Map<String, String> base = deploy(node(shared.steps(), shared.xml()), "shop", shop().zip(), "r1");

        Map<String, String> lib = deploy(node(shared.steps(), shared.xml()), "shop", shop().file("flows/xsl/lib.xsl", template("lib", "lib v2")).zip(), "r1");
        assertEquals(Set.of(ORDERS), changed(base, lib), "the stylesheet's include changed: its reader only, the schema's reader hit the cache unchanged");
        assertEquals(2, shared.stylesheets(), "the same main.xsl over another include compiled again, not served from the cache");
        assertEquals(1, shared.schemas(), "the unchanged schema was served from the cache");

        Map<String, String> types = deploy(node(shared.steps(), shared.xml()), "shop", shop().file("flows/xsd/types.xsd", typesXsd(4)).zip(), "r1");
        assertEquals(Set.of(AUDIT), changed(base, types), "the schema's include changed: its reader only, the stylesheet's reader hit the cache unchanged");
        assertEquals(2, shared.schemas(), "the same order.xsd over another include compiled again, not served from the cache");
    }

    @Test
    void getFlowsShowsHashAndState() throws Exception {
        Deployments node = node();
        Vertx vertx = Vertx.vertx();
        AdminServer admin = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), new InMemoryMessageStore(), new InMemoryDlq(),
                rt.blobs(), new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(node));
        try (HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()) {
            int port = admin.start(vertx).await(10, TimeUnit.SECONDS);
            deploy(node, "shop", shop().zip(), "r1");

            HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/flows"))
                    .header("Authorization", "Bearer " + TOKEN).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, answer.statusCode(), answer.body());
            JsonArray items = new JsonObject(answer.body()).getJsonArray("items");
            List<DeployedFlow> flows = node.flows();
            assertEquals(List.of(AUDIT, ORDERS, PING), flows.stream().map(DeployedFlow::id).toList(), "fixture: the three flows, by id");
            assertEquals(flows.size(), items.size(), answer.body());
            for (int i = 0; i < flows.size(); i++) {
                DeployedFlow f = flows.get(i);
                JsonObject item = items.getJsonObject(i);
                assertEquals(f.id(), item.getString("id"), answer.body());
                assertEquals(DeployedFlow.State.RUNNING, f.state(), "a flow the deploy started runs: " + f.id());
                assertTrue(HASH.matcher(f.hash()).matches(), f.id() + " " + f.hash());
                assertEquals(f.hash(), item.getValue("hash"), "the item shows the flow's effective hash: " + item);
                assertEquals("RUNNING", item.getValue("state"), "the item shows the flow's state: " + item);
            }
            assertEquals(3, flows.stream().map(DeployedFlow::hash).distinct().count(), "each flow's own hash: " + answer.body());
        } finally {
            admin.stop().await(10, TimeUnit.SECONDS);
            vertx.close().await(10, TimeUnit.SECONDS);
        }
    }
}
