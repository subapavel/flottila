package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.api.StepFactory;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.steps.validate.XsdValidate;
import dev.rail8.steps.xslt.SaxonXml;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Package dependencies on the node. {@code invoice} declares {@code common 1.2.0} in package.yaml and pins it in package.lock with
 * its content digest, its repository and its commit; its flow maps with {@code common:xslt/to-ubl.xsl}, read from {@code common}'s
 * directory of that version, whose include stays in {@code common}. A deploy resolves each pin of the closure from its own
 * commit — a directory the node keeps with that content is used as it is, otherwise the commit is fetched and its content checked
 * against the pin — following each dependency's own lock and refusing a cycle of names: 422, no absolute path. A validate fetches
 * nothing and resolves against what the node has. The record pins the closure (name, version, content, repository, commit); prune
 * never removes a pinned directory; restore reads a pin from its commit when its directory is absent and lists the package FAILED
 * when it cannot.
 *
 * <p>Fixtures: nodes of one pool ({@link Node}), each with its own data directory, git cache and {@link Doors}, over one
 * {@link Ledger} (the pool's rows) and one {@link GitRepos} (the pool's repositories, one per package). A partner replies with what
 * was sent: a reply is the XSLT's output. Content digests are computed by {@link PackageDraft#content()} from the files, never by
 * the code under test.
 */
class DeploymentsDependenciesTest {

    static final String XSL = DeploymentsFoldersTest.XSL;
    static final String FLOW = "invoice/to-ubl";

    @TempDir Path tmp;
    GitRepos repos;
    final FakeRuntime rt = FakeRuntime.create();
    final Ledger records = new Ledger(trace -> {});
    final List<Node> nodes = new ArrayList<>();

    @BeforeEach
    void wire() {
        repos = new GitRepos(tmp.resolve("repos"));
    }

    @AfterEach
    void closeEveryNode() throws Exception {
        for (Node n : nodes) n.doors.releaseAll();
        records.releaseAll();
        for (Node n : nodes) n.close();
    }

    // --- fixture: nodes of one pool

    /** A package as committed once, to the repository of its name: its draft, the commit, its content digest. */
    record Committed(PackageDraft draft, String commit, String content) {
        String name() { return draft.name(); }

        String version() { return draft.version(); }

        /** The name of its directory under {@code packages/<name>/}: the hex of its content digest. */
        String hex() { return content.substring("sha256:".length()); }
    }

    /** Commits {@code draft} to the repository of its name. */
    Committed committed(PackageDraft draft) {
        return new Committed(draft, repos.commit(draft), draft.content());
    }

    final class Node {
        final String id;
        final Path data;
        final Doors doors = new Doors();
        final Deployments deployments;
        private boolean closed;

        Node(String id) {
            this(id, tmp.resolve(id));
        }

        /** A node whose data directory is {@code data}: another node's, once that one is closed, is a restart. */
        Node(String id, Path data) {
            this.id = id;
            this.data = data;
            Map<String, String> env = new HashMap<>();
            env.put("RAIL8_ENV", "dev");
            env.put("RAIL8_NODE_ID", id);
            env.put("RAIL8_DATA_DIR", data.toString());
            env.put("RAIL8_STOP_DEADLINE", "PT5S");
            env.put("RAIL8_BLOB_DURABLE_MAX", "4096");
            env.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            env.put("RAIL8_GIT_BASE", repos.base().toString());
            SaxonXml xml = new SaxonXml();
            List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), steps, xml.languages(),
                    new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L, records);
            nodes.add(this);
        }

        DeployResult deploy(Committed p) throws Exception {
            return OnVirtualThread.call(() -> deployments.deploy(p.name(), p.commit(), "test"));
        }

        /** Commits {@code p} to the repository of its name and deploys that commit. */
        DeployResult deploy(PackageDraft p) throws Exception {
            return deploy(committed(p));
        }

        DeployRefusedException refused(PackageDraft p) throws IOException {
            Committed c = committed(p);
            DeployRefusedException refused = assertThrows(DeployRefusedException.class,
                    () -> OnVirtualThread.call(() -> deployments.deploy(c.name(), c.commit(), "test")), "the deploy of " + p.name() + " is refused");
            assertEquals(Kind.INVALID, refused.kind(), refused.getMessage() + " " + refused.problems());
            assertNoAbsolutePath(refused.problems());
            assertFalse(refused.error().contains(tmp.toString()), "no absolute path in the error: " + refused.error());
            return refused;
        }

        void restore() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.restore();
                return null;
            });
        }

        void close() throws Exception {
            if (closed) return;
            closed = true;
            doors.releaseAll();
            OnVirtualThread.call(() -> {
                deployments.close();
                return null;
            });
        }

        /** The partner's reply to a message into the consumer built last for {@code flow}: the XSLT's output. */
        String reply(String flow) throws Exception {
            Doors.Door door = doors.door(flow);
            Ack a = within(door.deliver(rt.ingress(flow, Headers.empty(), Buffer.buffer("<invoice/>"))), door + "'s answer");
            return assertInstanceOf(Ack.Reply.class, a, door + " replies: " + a).reply().body().whole().toString();
        }

        Optional<DeployedPackage> listed(String name) {
            return deployments.packages().stream().filter(p -> p.name().equals(name)).findFirst();
        }

        DeployedPackage pkg(String name) {
            return listed(name).orElseGet(() -> fail(id + " lists no package " + name + ": " + deployments.packages()));
        }

        /** {@code packages/<name>/<hex>/} of this node's data directory. */
        Path dir(String name, String hex) {
            return data.resolve("packages").resolve(name).resolve(hex);
        }

        /** Deletes this node's git cache: a commit it reads next is fetched. */
        void forgetGit() throws IOException {
            deleteTree(data.resolve("git"));
        }
    }

    // --- fixture: packages

    /** {@code common} at {@code version}: {@code xslt/to-ubl.xsl} includes {@code lib.xsl} next to it, which writes {@code text}. No flows. */
    static PackageDraft common(String version, String text) {
        return PackageDraft.of("common", version)
                .file("xslt/to-ubl.xsl", XSL + "<xsl:include href=\"lib.xsl\"/><xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:call-template name=\"lib\"/>"
                        + "</xsl:template></xsl:stylesheet>")
                .file("xslt/lib.xsl", XSL + "<xsl:template name=\"lib\">" + text + "</xsl:template></xsl:stylesheet>");
    }

    /** A library {@code name}@{@code version} whose {@code xslt/to-ubl.xsl} writes {@code text}, depending on {@code deps}. */
    static PackageDraft library(String name, String version, String text, PackageDraft... deps) {
        PackageDraft p = PackageDraft.of(name, version).file("xslt/to-ubl.xsl", DeploymentsFoldersTest.standalone(text));
        return deps.length == 0 ? p : p.dependingOn(deps);
    }

    /**
     * {@code name}@{@code version}: flow {@code to-ubl} (sync, from {@code invoice-in}) maps with {@code stylesheet} and sends to
     * {@code partner}; a decoy {@code xslt/lib.xsl} of its own writes {@code invoice's lib}; it depends on {@code deps}.
     */
    static PackageDraft user(String name, String version, String stylesheet, PackageDraft... deps) {
        return PackageDraft.of(name, version)
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .endpoint("invoice-in", "in", new JsonObject().put("path", "/" + name))
                .flow("to-ubl", "1.0.0", "sync", "invoice-in", 4096, DeploymentsFoldersTest.mapWith(stylesheet))
                .file("xslt/lib.xsl", XSL + "<xsl:template name=\"lib\">invoice's lib</xsl:template></xsl:stylesheet>")
                .dependingOn(deps);
    }

    /** {@code invoice}@{@code version} mapping with {@code common:xslt/to-ubl.xsl}. */
    static PackageDraft invoice(String version, PackageDraft... deps) {
        return user("invoice", version, "common:xslt/to-ubl.xsl", deps);
    }

    static DeploymentRecord.Pin pin(Committed p) {
        return new DeploymentRecord.Pin(p.name(), p.version(), p.content(), p.name(), p.commit());
    }

    static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** The file key of {@code p} (Linux: device and inode): the same key, the same directory, not one written again. */
    static Object fileKey(Path p) throws IOException {
        return Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    DeploymentRecord row(String name) {
        return records.rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseGet(() -> fail("no row " + name + ": " + records.rows()));
    }

    void assertNoAbsolutePath(List<String> lines) throws IOException {
        for (String absolute : List.of(tmp.toString(), tmp.toRealPath().toString())) {
            for (String l : lines) assertFalse(l.contains(absolute), "no absolute path (" + absolute + ") in '" + l + "'");
        }
    }

    static void writeTo(Path dir, PackageDraft p) throws IOException {
        for (Map.Entry<String, byte[]> f : p.files().entrySet()) {
            Path file = dir.resolve(f.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, f.getValue());
        }
    }

    // --- cases

    /** invoice pins common 1.2.0 and maps with its stylesheet, whose include is common's lib.xsl, not invoice's; the record pins it. */
    @Test
    void invoicePinsCommon120AndUsesItsXslt() throws Exception {
        Node node = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common);

        DeployResult r = node.deploy(invoice("1.0.0", common.draft()));

        assertEquals(List.of(FLOW), r.added(), r.toString());
        assertEquals("UBL 1.2.0", node.reply(FLOW), "common's to-ubl.xsl, its include next to it in common");
        assertEquals(List.of(pin(common)), row("invoice").dependencies(), "the record pins common 1.2.0 by its commit");
        assertEquals(List.of(), row("common").dependencies(), "common pins nothing");
        assertEquals(common.content(), node.pkg("common").content(), "the node's content digest of common is the lock's");
    }

    /** common 1.3.0 deployed after invoice: invoice keeps 1.2.0 — the same output, no swap, its listing and record unchanged. */
    @Test
    void deployingCommon130LeavesInvoiceOn120() throws Exception {
        Node node = new Node("node-1");
        Committed common120 = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common120);
        node.deploy(invoice("1.0.0", common120.draft()));
        Doors.Door running = node.doors.door(FLOW);

        DeployResult r = node.deploy(common("1.3.0", "UBL 1.3.0"));

        assertEquals(List.of(), r.added(), r.toString());
        assertEquals("1.3.0", node.pkg("common").version(), "common's current version");
        assertSame(running, node.doors.door(FLOW), "no consumer was built for invoice");
        assertEquals(0, running.stops(), "invoice's consumer was not stopped");
        assertEquals("UBL 1.2.0", node.reply(FLOW), "invoice still maps with common 1.2.0");
        assertEquals(List.of(new DeployedPackage.Dependency("common", "1.2.0", common120.content(), common120.name(), common120.commit(), true)),
                node.pkg("invoice").dependencies(), "invoice lists the version it pins");
        assertEquals(List.of(pin(common120)), row("invoice").dependencies(), "its record pins 1.2.0 still");
    }

    /** invoice pins common 1.2.0 at a commit its repository does not have: 422 naming the pin's commit, with or without common deployed. */
    @Test
    void aPinWhoseCommitIsNotThereIs422() throws Exception {
        Node node = new Node("node-1");
        PackageDraft invoice = invoice("1.0.0", common("1.2.0", "UBL 1.2.0"));   // never committed: the lock pins a commit no repository has
        String missing = "dependency common@1.2.0: common at " + PackageDraft.COMMIT + ": not found";

        DeployRefusedException none = node.refused(invoice);

        assertTrue(none.problems().contains(missing), none.problems().toString());

        node.deploy(common("1.3.0", "UBL 1.3.0"));
        DeployRefusedException other = node.refused(invoice);

        assertTrue(other.problems().contains(missing), other.problems().toString());
        assertEquals(Optional.empty(), node.listed("invoice"), "nothing of invoice is listed");
        assertEquals(List.of("common"), records.rows().stream().map(DeploymentRecord::name).toList(), "no row for invoice");
    }

    /** common 1.2.0, committed but never deployed, while 1.3.0 runs: invoice's deploy fetches its pin from its commit and maps with it. */
    @Test
    void aDependencyIsFetchedFromItsPinnedCommitWhileAnotherVersionRuns() throws Exception {
        Node node = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common("1.3.0", "UBL 1.3.0"));

        DeployResult r = node.deploy(invoice("1.0.0", c120.draft()));

        assertEquals(List.of(FLOW), r.added(), r.toString());
        assertEquals("UBL 1.2.0", node.reply(FLOW), "the pin's files, not those of the version that runs");
        assertEquals("1.3.0", node.pkg("common").version(), "common runs on at 1.3.0");
        assertTrue(Files.isDirectory(node.dir("common", c120.hex())), "the pin is written under common, by its content");
        assertEquals(List.of(pin(c120)), row("invoice").dependencies());
    }

    /** The pin's commit holds other bytes than the lock pins: 422 naming both digests; the lock with the commit's digest deploys. */
    @Test
    void aContentMismatchIs422() throws Exception {
        Node node = new Node("node-1");
        PackageDraft onNode = common("1.2.0", "UBL 1.2.0");
        PackageDraft locked = common("1.2.0", "UBL 1.2.0 as built elsewhere");
        node.deploy(onNode);
        PackageDraft invoice = invoice("1.0.0").locking(List.of(new PackageDraft.LockEntry("common", "1.2.0", locked.content(), "common", onNode.commit())));

        DeployRefusedException refused = node.refused(invoice);

        assertTrue(refused.problems().contains("dependency common@1.2.0: common at " + onNode.commit() + " has content " + onNode.content()
                + ", package.lock pins " + locked.content()), refused.problems().toString());
        assertEquals(Optional.empty(), node.listed("invoice"));
        assertEquals(List.of(FLOW), node.deploy(invoice("1.0.0", onNode)).added(), "witness: the lock with the node's content deploys");
    }

    /** a@1; b@1 -> a@1; a@2 -> b@1 is a cycle through b's own lock: 422 naming it; so is one three long. a stays on 1.0.0. */
    @Test
    void aCycleIs422NamingIt() throws Exception {
        Node node = new Node("node-1");
        PackageDraft a1 = library("a", "1.0.0", "a1");
        node.deploy(a1);
        PackageDraft b1 = library("b", "1.0.0", "b1", a1);
        node.deploy(b1);

        DeployRefusedException two = node.refused(library("a", "2.0.0", "a2", b1));

        assertTrue(two.problems().contains("dependency cycle: a -> b -> a"), two.problems().toString());
        assertEquals("1.0.0", node.pkg("a").version(), "a stays on 1.0.0");

        PackageDraft c1 = library("c", "1.0.0", "c1", b1);
        node.deploy(c1);
        DeployRefusedException three = node.refused(library("a", "2.0.0", "a2", c1));

        assertTrue(three.problems().contains("dependency cycle: a -> c -> b -> a"), three.problems().toString());
        assertEquals("1.0.0", node.pkg("a").version());
    }

    /**
     * common 1.2.0 listed FAILED after a restart (its commit gone from its repository and the node's cache, its directory still on
     * disk): the directory holds the content invoice's lock pins, so invoice deploys from it.
     */
    @Test
    void aDirectoryWithThePinnedContentIsUsedWhateverItsPackagesState() throws Exception {
        Node first = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        first.close();
        repos.delete("common");
        first.forgetGit();
        Node restarted = new Node("node-1b", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.pkg("common").state(), "fixture: common is listed FAILED");
        assertTrue(Files.isDirectory(restarted.dir("common", common.hex())), "fixture: its directory is still on disk");

        assertEquals(List.of(FLOW), restarted.deploy(invoice("1.0.0", common.draft())).added());
        assertEquals("UBL 1.2.0", restarted.reply(FLOW));
    }

    /** common 1.2.0 pinned by invoice outlives three later versions on disk; 1.3.0, pinned by nobody, is pruned once neither current nor previous. */
    @Test
    void aPinnedVersionIsNeverPrunedAndAnUnpinnedOneIs() throws Exception {
        Node node = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        Committed c130 = committed(common("1.3.0", "UBL 1.3.0"));
        node.deploy(c130);
        node.deploy(common("1.4.0", "UBL 1.4.0"));

        assertTrue(Files.isDirectory(node.dir("common", c120.hex())), "1.2.0 is neither current nor previous, and pinned: kept");

        node.deploy(common("1.5.0", "UBL 1.5.0"));

        assertFalse(Files.exists(node.dir("common", c130.hex())), "1.3.0 is neither current, previous nor pinned: pruned");
        assertTrue(Files.isDirectory(node.dir("common", c120.hex())), "1.2.0 is still pinned: kept");
        assertEquals("UBL 1.2.0", node.reply(FLOW));
    }

    /** A stylesheet missing from common 1.2.0, and an include in common leaving it: 422 naming the flow, no absolute path nor archive SHA-256. */
    @Test
    void aMissingDependencyFileIs422WithoutAnAbsolutePath() throws Exception {
        Node node = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0")
                .file("xslt/escape.xsl", XSL + "<xsl:include href=\"../../escape.xsl\"/><xsl:output method=\"text\"/><xsl:template match=\"/\">x</xsl:template>"
                        + "</xsl:stylesheet>"));
        node.deploy(common);

        DeployRefusedException missing = node.refused(user("invoice", "1.0.0", "common:xslt/missing.xsl", common.draft()));

        assertTrue(missing.problems().stream().anyMatch(l -> l.contains("'to-ubl'") && l.endsWith("stylesheet 'common:xslt/missing.xsl' does not exist in common@1.2.0")),
                missing.problems().toString());
        for (String l : missing.problems()) assertFalse(l.contains(common.hex()), "no package directory in '" + l + "'");

        DeployRefusedException escape = node.refused(user("invoice", "1.0.0", "common:xslt/escape.xsl", common.draft()));

        assertTrue(escape.problems().stream().anyMatch(l -> l.contains("to-ubl") && l.contains("../../escape.xsl")), escape.problems().toString());
        for (String l : escape.problems()) assertFalse(l.contains(common.hex()), "no package directory in '" + l + "'");
        assertEquals(Optional.empty(), node.listed("invoice"));
    }

    /** A stylesheet missing from common 1.2.0, named by a step of a local process: 422 naming the process and its step. */
    @Test
    void aMissingDependencyFileNamedInsideAProcessIs422NamingTheProcessStep() throws Exception {
        Node node = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common);
        PackageDraft invoice = user("invoice", "1.0.0", "common:xslt/to-ubl.xsl", common.draft());
        JsonObject flow = invoice.flowDoc("to-ubl");
        flow.put("processes", new JsonObject().put("convert", new JsonObject().put("steps", flow.getJsonArray("steps").copy())));
        flow.getJsonObject("processes").getJsonObject("convert").getJsonArray("steps").getJsonObject(0).put("stylesheet", "common:xslt/missing.xsl");
        flow.put("steps", new JsonArray().add(new JsonObject().put("type", "process").put("name", "run").put("process", "convert")));

        DeployRefusedException missing = node.refused(invoice);

        assertTrue(missing.problems().stream().anyMatch(l -> l.contains("flow 'to-ubl' process 'convert' step 'map' stylesheet 'common:xslt/missing.xsl'")
                && l.endsWith("does not exist in common@1.2.0")), missing.problems().toString());
        assertEquals(Optional.empty(), node.listed("invoice"));
    }

    /** common 1.2.1 with 1.2.0's stylesheets byte for byte: invoice 1.0.1 locking it swaps nothing and pins 1.2.1; a bump to new bytes swaps. */
    @Test
    void aLockBumpWithIdenticalBytesSwapsNothing() throws Exception {
        Node node = new Node("node-1");
        PackageDraft c120 = common("1.2.0", "UBL 1.2.0");
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120));
        Doors.Door running = node.doors.door(FLOW);
        String hash = node.deployments.flows().stream().filter(f -> f.id().equals(FLOW)).findFirst().orElseThrow().hash();
        Committed c121 = committed(common("1.2.1", "UBL 1.2.0"));
        node.deploy(c121);

        DeployResult r = node.deploy(invoice("1.0.1", c121.draft()));

        assertEquals(List.of(), r.added(), r.toString());
        assertEquals(List.of(), r.swapped(), "the bytes the flow reads are the same: no change: " + r);
        assertEquals(List.of(), r.removed(), r.toString());
        assertSame(running, node.doors.door(FLOW), "no consumer was built");
        assertEquals(0, running.stops(), "the running consumer was not stopped");
        assertEquals(hash, node.deployments.flows().stream().filter(f -> f.id().equals(FLOW)).findFirst().orElseThrow().hash(), "the same effective hash");
        assertEquals(List.of(pin(c121)), row("invoice").dependencies(), "the record pins 1.2.1");

        PackageDraft c130 = common("1.3.0", "UBL 1.3.0");
        node.deploy(c130);
        DeployResult bump = node.deploy(invoice("1.0.2", c130));

        assertEquals(List.of(FLOW), bump.swapped(), "witness: other bytes swap the flow: " + bump);
        assertEquals("UBL 1.3.0", node.reply(FLOW));
    }

    /** A node with an empty data directory restores common 1.3.0 and invoice, whose pin 1.2.0 it reads from its own commit and maps with. */
    @Test
    void restoreOnAnEmptyDataDirInstallsThePin() throws Exception {
        Node first = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        first.deploy(invoice("1.0.0", c120.draft()));
        first.deploy(common("1.3.0", "UBL 1.3.0"));

        Node second = new Node("node-2");
        second.restore();

        assertEquals(DeployedPackage.State.RUNNING, second.pkg("invoice").state(), second.pkg("invoice").problems().toString());
        assertEquals("1.3.0", second.pkg("common").version());
        assertTrue(Files.isDirectory(second.dir("common", c120.hex())), "the pin is written under common, by its content");
        assertEquals("UBL 1.2.0", second.reply(FLOW), "invoice maps with its pin, not common's current version");
    }

    /**
     * invoice's pin is gone from its repository (common's history rewritten, 1.3.0 on a fresh one): at restore on an empty data
     * directory invoice is FAILED naming the pin's commit; common runs.
     */
    @Test
    void aPinWhoseCommitIsGoneIsFailedAtRestore() throws Exception {
        Node first = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        first.deploy(invoice("1.0.0", c120.draft()));
        repos.delete("common");
        first.deploy(common("1.3.0", "UBL 1.3.0"));

        Node second = new Node("node-2");
        second.restore();

        DeployedPackage invoice = second.pkg("invoice");
        assertEquals(DeployedPackage.State.FAILED, invoice.state(), invoice.toString());
        assertTrue(invoice.problems().contains("dependency common@1.2.0: common at " + c120.commit() + ": not found"), "a line names the pin: "
                + invoice.problems());
        assertEquals(List.of(), invoice.flows(), "a FAILED package has no flows");
        assertTrue(second.doors.doors().stream().noneMatch(d -> d.flow().equals(FLOW) && d.started()), "invoice's consumer never started: " + second.doors.events());
        assertEquals(DeployedPackage.State.RUNNING, second.pkg("common").state(), "common runs");
    }

    /** top -> mid -> base: the record pins mid and base; billing -> mid and base (a diamond) pins each once. */
    @Test
    void theRecordPinsTheClosureNotOnlyDirectDependencies() throws Exception {
        Node node = new Node("node-1");
        Committed base = committed(library("base", "1.0.0", "base"));
        node.deploy(base);
        Committed mid = committed(library("mid", "1.0.0", "mid", base.draft()));
        node.deploy(mid);

        node.deploy(user("top", "1.0.0", "mid:xslt/to-ubl.xsl", mid.draft()));

        assertEquals(Set.of(pin(mid), pin(base)), Set.copyOf(row("top").dependencies()), "the closure: " + row("top").dependencies());
        assertEquals(2, row("top").dependencies().size(), row("top").dependencies().toString());
        assertEquals(List.of(pin(base)), row("mid").dependencies());
        assertEquals(Set.of(new DeployedPackage.Dependency("mid", "1.0.0", mid.content(), mid.name(), mid.commit(), true),
                new DeployedPackage.Dependency("base", "1.0.0", base.content(), base.name(), base.commit(), false)), Set.copyOf(node.pkg("top").dependencies()));

        node.deploy(user("billing", "1.0.0", "base:xslt/to-ubl.xsl", mid.draft(), base.draft()));

        assertEquals(Set.of(pin(mid), pin(base)), Set.copyOf(row("billing").dependencies()));
        assertEquals(2, row("billing").dependencies().size(), "each pinned once: " + row("billing").dependencies());
        assertEquals("base", node.reply("billing/to-ubl"));
    }

    /** common 1.3.0 is current, 1.2.0 pinned by invoice's record: billing locking 1.2.0 deploys and maps with it. */
    @Test
    void aVersionARecordPinsIsAvailableToTheNextDeploy() throws Exception {
        Node node = new Node("node-1");
        PackageDraft c120 = common("1.2.0", "UBL 1.2.0");
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120));
        node.deploy(common("1.3.0", "UBL 1.3.0"));

        DeployResult r = node.deploy(user("billing", "1.0.0", "common:xslt/to-ubl.xsl", c120));

        assertEquals(List.of("billing/to-ubl"), r.added(), r.toString());
        assertEquals("UBL 1.2.0", node.reply("billing/to-ubl"));
    }

    /**
     * A restart over the same data directory: common (current 1.4.0) is restored before invoice, which pins 1.2.0 — common's prune
     * keeps 1.2.0 though invoice is not restored yet, so its directory is used as it is; alpha, restored before common, pins 1.2.0
     * too: kept.
     */
    @Test
    void aPinnedVersionSurvivesARestorePruneInEitherNameOrder() throws Exception {
        Node first = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        first.deploy(invoice("1.0.0", c120.draft()));
        first.deploy(common("1.3.0", "UBL 1.3.0"));
        first.deploy(common("1.4.0", "UBL 1.4.0"));
        Object kept = fileKey(first.dir("common", c120.hex()));
        first.close();

        Node restarted = new Node("node-1b", first.data);
        restarted.restore();

        assertEquals(kept, fileKey(restarted.dir("common", c120.hex())), "1.2.0's directory was kept on disk, not written again");
        assertEquals("UBL 1.2.0", restarted.reply(FLOW));

        restarted.deploy(user("alpha", "1.0.0", "common:xslt/to-ubl.xsl", c120.draft()));
        restarted.deploy(common("1.5.0", "UBL 1.5.0"));
        restarted.close();
        Node empty = new Node("node-2");
        empty.restore();

        assertTrue(Files.isDirectory(empty.dir("common", c120.hex())), "alpha's pin, written before common's restore, survives common's prune");
        assertEquals("UBL 1.2.0", empty.reply("alpha/to-ubl"));
        assertEquals("UBL 1.2.0", empty.reply(FLOW));
    }

    /** validate checks dependencies as a deploy does: against an empty node 'not deployed', with common 1.2.0 deployed no problem. */
    @Test
    void validateResolvesDependenciesAsADeployDoes() throws Exception {
        Node node = new Node("node-1");
        PackageDraft c120 = common("1.2.0", "UBL 1.2.0");
        Path dir = tmp.resolve("checkout");
        writeTo(dir, invoice("1.0.0", c120));

        List<String> empty = OnVirtualThread.call(() -> node.deployments.validate("invoice", dir));

        assertTrue(empty.contains("dependency common@1.2.0 is not deployed on this node (no package common)"), empty.toString());
        assertNoAbsolutePath(empty);

        node.deploy(c120);
        assertEquals(List.of(), OnVirtualThread.call(() -> node.deployments.validate("invoice", dir)), "common 1.2.0 deployed: no problem");
    }

    // --- guards

    /** loop depends on itself: package.yaml's check and the node's resolution both find the cycle; the 422 lists its line once. */
    @Test
    void aSelfDependencyIs422NamingTheCycleOnce() throws Exception {
        Node node = new Node("node-1");
        PackageDraft loop = library("loop", "1.0.0", "loop").locking(List.of(new PackageDraft.LockEntry("loop", "1.0.0", "sha256:" + "a".repeat(64))));

        DeployRefusedException refused = node.refused(loop);

        assertEquals(1, refused.problems().stream().filter("dependency cycle: loop -> loop"::equals).count(), "the cycle, once: " + refused.problems());
        assertEquals(Optional.empty(), node.listed("loop"));
    }

    /**
     * common 1.2.0, pinned by invoice and neither current nor previous, deployed again and refused (its record is not written):
     * the refusal deletes what it wrote, never the directory a record pins — invoice serves on and billing still resolves 1.2.0.
     */
    @Test
    void aRefusedRedeployOfAPinnedVersionKeepsItsDirectory() throws Exception {
        Node node = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        node.deploy(common("1.3.0", "UBL 1.3.0"));
        node.deploy(common("1.4.0", "UBL 1.4.0"));
        assertTrue(Files.isDirectory(node.dir("common", c120.hex())), "fixture: 1.2.0 is on disk, pinned, neither current nor previous");
        records.failNextSave("the pool is down");

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> node.deploy(c120), "the redeploy of 1.2.0 is refused");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertEquals("1.4.0", node.pkg("common").version(), "fixture: 1.4.0 stays current");
        assertTrue(Files.isRegularFile(node.dir("common", c120.hex()).resolve("xslt/to-ubl.xsl")), "the refusal leaves the directory invoice's record pins");
        assertEquals(List.of("billing/to-ubl"), node.deploy(user("billing", "1.0.0", "common:xslt/to-ubl.xsl", c120.draft())).added(), "1.2.0 still resolves");
        assertEquals("UBL 1.2.0", node.reply("billing/to-ubl"));
    }

    /** invoice FAILED at restore (its own commit gone) keeps its record's pin: a later deploy of common prunes 1.3.0's previous, not 1.2.0. */
    @Test
    void aPackageFailedAtRestoreKeepsItsPinsFromPrune() throws Exception {
        Node first = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        Committed invoice = committed(invoice("1.0.0", c120.draft()));
        first.deploy(invoice);
        Committed c130 = committed(common("1.3.0", "UBL 1.3.0"));
        first.deploy(c130);
        first.deploy(common("1.4.0", "UBL 1.4.0"));
        first.close();
        repos.delete("invoice");
        first.forgetGit();
        Node restarted = new Node("node-1b", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.pkg("invoice").state(), "fixture: invoice is FAILED");
        assertTrue(Files.isDirectory(restarted.dir("common", c120.hex())), "fixture: 1.2.0 is on disk after the restore");

        restarted.deploy(common("1.5.0", "UBL 1.5.0"));

        assertFalse(Files.exists(restarted.dir("common", c130.hex())), "witness: 1.3.0, pinned by nobody, is pruned");
        assertTrue(Files.isDirectory(restarted.dir("common", c120.hex())), "invoice's record, FAILED on this node, pins 1.2.0: kept");
    }

    /**
     * invoice 1.0.0 maps with common 1.2.0, whose stylesheet is then deleted from its directory; 1.0.1 (on common 1.3.0) does not
     * start: the rollback cannot build 1.0.0's flow again, and its line names the dependency file without an absolute path or the
     * package's directory.
     */
    @Test
    void aRollbackThatCannotRebuildFromADependencyNamesNoAbsolutePath() throws Exception {
        Node node = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        PackageDraft c130 = common("1.3.0", "UBL 1.3.0");
        node.deploy(c130);
        Files.delete(node.dir("common", c120.hex()).resolve("xslt/to-ubl.xsl"));
        node.doors.failStartOf(FLOW, "address in use");

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> node.deploy(invoice("1.0.1", c130)), "1.0.1 does not start");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage() + " " + refused.problems());
        List<String> again = refused.problems().stream().filter(l -> l.startsWith("flow 'to-ubl' does not build again: ")).toList();
        assertEquals(1, again.size(), "a line says to-ubl does not build again: " + refused.problems());
        assertTrue(again.getFirst().contains("to-ubl.xsl"), "it names the file: " + again);
        assertNoAbsolutePath(refused.problems());
        for (String l : refused.problems()) assertFalse(l.contains(c120.hex()), "no package directory in '" + l + "'");
    }

    // --- rollbacks over a dependency

    /**
     * invoice 1.0.0 on common 1.2.0; common 1.2.1 has the same bytes, so invoice 1.0.1 locking it swaps nothing and its flow runs on,
     * built over 1.2.0's directory; common 1.3.0 then makes 1.2.0 neither current, previous nor pinned — but in use. invoice 1.0.2 on
     * 1.3.0 does not start: the rollback rebuilds the running flow from the directory it reads, and it answers as before.
     */
    @Test
    void aRollbackAfterALockBumpRebuildsFromTheDirTheFlowReads() throws Exception {
        Node node = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        PackageDraft c121 = common("1.2.1", "UBL 1.2.0");
        node.deploy(c121);
        assertEquals(List.of(), node.deploy(invoice("1.0.1", c121)).swapped(), "fixture: the same bytes, nothing swaps");
        PackageDraft c130 = common("1.3.0", "UBL 1.3.0");
        node.deploy(c130);
        node.doors.failStartOf(FLOW, "address in use");

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> node.deploy(invoice("1.0.2", c130)), "1.0.2 does not start");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage() + " " + refused.problems());
        assertEquals("version 1.0.2 did not start; 1.0.1 runs again", refused.error(), "the rollback ran the flow again: " + refused.problems());
        assertEquals(DeployedFlow.State.RUNNING, node.deployments.flows().stream().filter(f -> f.id().equals(FLOW)).findFirst().orElseThrow().state());
        assertEquals("UBL 1.2.0", node.reply(FLOW), "rebuilt over 1.2.0's directory");
        assertTrue(Files.isDirectory(node.dir("common", c120.hex())), "1.2.0's directory, in use by the running flow, was kept");
    }

    /**
     * invoice's flow reads common 1.2.0's directory through its build only (the lock bumped to 1.2.1 with the same bytes, then common
     * 1.3.0): no record pins 1.2.0, it is neither current nor previous, but in use. A deploy of common at 1.2.0's commit, refused
     * (its record is not written), deletes what it wrote — never that directory; invoice answers as before.
     */
    @Test
    void aRefusedRedeployOfAVersionARunningFlowReadsKeepsItsDirectory() throws Exception {
        Node node = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        PackageDraft c121 = common("1.2.1", "UBL 1.2.0");
        node.deploy(c121);
        assertEquals(List.of(), node.deploy(invoice("1.0.1", c121)).swapped(), "fixture: the same bytes, nothing swaps");
        node.deploy(common("1.3.0", "UBL 1.3.0"));
        assertTrue(records.rows().stream().flatMap(r -> r.dependencies().stream()).noneMatch(p -> p.commit().equals(c120.commit())),
                "fixture: no record pins 1.2.0: " + records.rows());
        assertTrue(Files.isDirectory(node.dir("common", c120.hex())), "fixture: 1.2.0's directory is kept, in use");
        records.failNextSave("the pool is down");

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> node.deploy(c120), "the redeploy of 1.2.0 is refused");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertEquals("1.3.0", node.pkg("common").version(), "fixture: 1.3.0 stays current");
        assertTrue(Files.isRegularFile(node.dir("common", c120.hex()).resolve("xslt/to-ubl.xsl")), "the refusal leaves the directory the running flow reads");
        assertEquals("UBL 1.2.0", node.reply(FLOW));
    }

    /**
     * invoice is FAILED at restore (its own commit gone): its record pins common 1.2.0, and no listed flow reads it. A deploy of
     * common at 1.2.0's commit, refused (its record is not written), leaves that directory.
     */
    @Test
    void aRefusedRedeployOfAVersionOnlyAFailedPackagePinsKeepsItsDirectory() throws Exception {
        Node first = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        Committed invoice = committed(invoice("1.0.0", c120.draft()));
        first.deploy(invoice);
        first.deploy(common("1.3.0", "UBL 1.3.0"));
        first.deploy(common("1.4.0", "UBL 1.4.0"));
        first.close();
        repos.delete("invoice");
        first.forgetGit();
        Node restarted = new Node("node-1b", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.pkg("invoice").state(), "fixture: invoice is FAILED");
        assertEquals(List.of(), restarted.deployments.flows(), "fixture: no listed flow reads 1.2.0");
        assertTrue(Files.isDirectory(restarted.dir("common", c120.hex())), "fixture: 1.2.0's directory is on disk, pinned");
        records.failNextSave("the pool is down");

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> restarted.deploy(c120), "the redeploy of 1.2.0 is refused");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertEquals("1.4.0", restarted.pkg("common").version(), "fixture: 1.4.0 stays current");
        assertTrue(Files.isRegularFile(restarted.dir("common", c120.hex()).resolve("xslt/to-ubl.xsl")), "invoice's FAILED record pins 1.2.0: the refusal leaves it");
    }

    /** package.lock with a byte 0xFF: the deploy is a 422 naming package.lock, not a node failure; nothing is listed. */
    @Test
    void aLockThatIsNotUtf8Is422() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        PackageDraft invoice = invoice("1.0.0", common);
        byte[] text = invoice.files().get("package.lock");
        byte[] bytes = java.util.Arrays.copyOf(text, text.length + 1);
        bytes[text.length] = (byte) 0xFF;
        invoice.file("package.lock", bytes);

        DeployRefusedException refused = node.refused(invoice);   // asserts Kind.INVALID and no absolute path

        assertTrue((refused.error() + " " + refused.problems()).contains("package.lock"), "it names package.lock: " + refused.error() + " " + refused.problems());
        assertEquals(Optional.empty(), node.listed("invoice"));
    }

    /**
     * A lock written before its entries pinned a repository and a commit: the deploy is a 422 that names {@code rail8 lock}, and
     * nothing is listed. The node goes on resolving a dependency by its content: the same lock with both runs.
     */
    @Test
    void aLockWithoutRepositoryAndCommitIs422NamingRail8Lock() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        PackageDraft invoice = invoice("1.0.0", common);
        JsonObject lock = new JsonObject(new String(invoice.files().get("package.lock"), java.nio.charset.StandardCharsets.UTF_8));
        JsonObject entry = lock.getJsonArray("dependencies").getJsonObject(0);
        entry.remove("repository");
        entry.remove("commit");
        invoice.file("package.lock", lock.encodePrettily());

        DeployRefusedException refused = node.refused(invoice);   // asserts Kind.INVALID and no absolute path

        assertTrue(refused.problems().contains("package.lock does not pin each dependency's repository and commit: write it again with rail8 lock"),
                refused.getMessage() + " " + refused.problems());
        assertEquals(Optional.empty(), node.listed("invoice"));

        node.deploy(invoice("1.0.0", common));
        assertEquals(DeployedPackage.State.RUNNING, node.pkg("invoice").state(), "witness: with both, the lock is taken");
    }

    /**
     * A lock pins common 1.2.0's content at a commit that holds other content (1.3.0's): the node that deployed it had that content
     * on disk and fetched nothing, but at restore on an empty data directory invoice is FAILED naming the pin, and nothing of that
     * commit is written under the pin's content; common runs.
     */
    @Test
    void aPinWhoseCommitHoldsOtherContentIsFailedAtRestore() throws Exception {
        Node first = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        Committed c130 = committed(common("1.3.0", "UBL 1.3.0"));
        PackageDraft edited = invoice("1.0.0", c120.draft());
        JsonObject lock = new JsonObject(new String(edited.files().get("package.lock"), java.nio.charset.StandardCharsets.UTF_8));
        lock.getJsonArray("dependencies").getJsonObject(0).put("commit", c130.commit());
        edited.file("package.lock", lock.encodePrettily());
        first.deploy(edited);
        assertEquals(DeployedPackage.State.RUNNING, first.pkg("invoice").state(), "fixture: the node had the pinned content and fetched nothing");
        first.deploy(c130);
        assertEquals(List.of(new DeploymentRecord.Pin("common", "1.2.0", c120.content(), "common", c130.commit())), row("invoice").dependencies(),
                "fixture: the record pins the lock's commit");

        Node second = new Node("node-2");
        second.restore();

        DeployedPackage invoice = second.pkg("invoice");
        assertEquals(DeployedPackage.State.FAILED, invoice.state(), invoice.toString());
        assertTrue(invoice.problems().contains("dependency common@1.2.0: common at " + c130.commit() + " holds common@1.3.0"), "a line names the pin: "
                + invoice.problems());
        assertFalse(Files.exists(second.dir("common", c120.hex())), "nothing of that commit is written under the pin's content");
        assertTrue(second.doors.doors().stream().noneMatch(d -> d.flow().equals(FLOW) && d.started()), "invoice's consumer never started");
        assertEquals(DeployedPackage.State.RUNNING, second.pkg("common").state(), "common runs");
    }

    /** Fixture self-check: the lock helper writes the documented shape, so a red above is the node's, not the fixture's. */
    @Test
    void theFixtureLockIsTheDocumentedShape() {
        PackageDraft c = common("1.2.0", "x");
        PackageDraft inv = invoice("1.0.0", c);

        assertEquals(new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.2.0")), inv.manifest().getJsonArray("dependencies"));
        JsonObject lock = new JsonObject(new String(inv.files().get("package.lock"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(Set.of("schemaVersion", "kind", "dependencies"), lock.fieldNames());
        assertEquals(new JsonObject().put("name", "common").put("version", "1.2.0").put("content", c.content()).put("repository", "common")
                .put("commit", PackageDraft.COMMIT), lock.getJsonArray("dependencies").getJsonObject(0));
        assertTrue(c.content().matches("sha256:[0-9a-f]{64}"), c.content());
    }
}
