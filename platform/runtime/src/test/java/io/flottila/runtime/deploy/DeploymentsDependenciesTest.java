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

import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-02b (D-087, D-060 e): package dependencies on the node. {@code invoice} declares {@code common 1.2.0} in package.yaml and pins it
 * in package.lock with its content digest; its flow maps with {@code common:xslt/to-ubl.xsl}, read from {@code common}'s directory of
 * that version, whose include stays in {@code common}. The check (deploy and validate) resolves against what this node has: each
 * RUNNING package's current version and every version a record pins (a FAILED package's is not available), matching name, version
 * and content, following each dependency's own lock, refusing a cycle of names — 422, no absolute path. The record pins the closure
 * (name, version, archive SHA-256); prune never removes a pinned directory, restore fetches a pin by its SHA-256 when absent and
 * lists the package FAILED when it cannot.
 *
 * <p>Fixtures: nodes of one pool ({@link Node}), each with its own data directory and {@link Doors}, over one {@link Ledger} (the
 * pool's rows) and one {@link Shelf} over {@code <tmp>/bucket} (the pool's archive store), read through
 * {@link DeploymentsRestoreTest.Fetches} so every fetch is counted. A partner replies with what was sent: a reply is the XSLT's output.
 * Content digests are computed by {@link PackageDraft#content()} from the files, never by the code under test. Every zip is taken
 * once: its SHA-256 is that of the bytes deployed.
 *
 * <p>The shape assumed (P2-02b test-author): {@code DeploymentRecord(name, version, revision, archiveSha256, deployedAt,
 * List<DeploymentRecord.Pin> dependencies)} with {@code record Pin(String name, String version, String archiveSha256)};
 * {@code DeployedPackage.content()} ({@code sha256:<hex>}) and {@code DeployedPackage.dependencies()}, a list of
 * {@code DeployedPackage.Dependency(String name, String version, String content, String archiveSha256, boolean direct)}.
 */
class DeploymentsDependenciesTest {

    static final String XSL = DeploymentsFoldersTest.XSL;
    static final String FLOW = "invoice/to-ubl";

    @TempDir Path tmp;
    Path bucket;
    Path uploads;
    final FakeRuntime rt = FakeRuntime.create();
    final Ledger records = new Ledger(trace -> {});
    Shelf shelf;
    final List<Node> nodes = new ArrayList<>();

    @BeforeEach
    void wire() {
        bucket = tmp.resolve("bucket");
        uploads = tmp.resolve("uploads");
        shelf = new Shelf(bucket, tmp.resolve("shelf-reads"), trace -> {});
    }

    @AfterEach
    void closeEveryNode() throws Exception {
        for (Node n : nodes) n.doors.releaseAll();
        records.releaseAll();
        for (Node n : nodes) n.close();
    }

    // --- fixture: nodes of one pool

    /** A package as zipped once: its draft, its bytes, their SHA-256. */
    record Zipped(PackageDraft draft, byte[] zip, String sha) {
        static Zipped of(PackageDraft draft) {
            byte[] zip = draft.zip();
            return new Zipped(draft, zip, PackageDraft.sha256(zip));
        }

        String name() { return draft.name(); }

        String version() { return draft.version(); }

        String content() { return draft.content(); }
    }

    final class Node {
        final String id;
        final Path data;
        final Doors doors = new Doors();
        final DeploymentsRestoreTest.Fetches fetches = new DeploymentsRestoreTest.Fetches(shelf);
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
            env.put("FLOTTILA_ENV", "dev");
            env.put("FLOTTILA_NODE_ID", id);
            env.put("FLOTTILA_DATA_DIR", data.toString());
            env.put("FLOTTILA_STOP_DEADLINE", "PT5S");
            env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
            env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            SaxonXml xml = new SaxonXml();
            List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), steps, xml.languages(),
                    new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L, records, fetches);
            nodes.add(this);
        }

        DeployResult deploy(Zipped p) throws Exception {
            Path archive = PackageDraft.write(uploads, p.zip());
            return OnVirtualThread.call(() -> deployments.deploy(p.name(), Optional.empty(), archive));
        }

        DeployResult deploy(PackageDraft p) throws Exception {
            return deploy(Zipped.of(p));
        }

        DeployRefusedException refused(PackageDraft p) throws IOException {
            Path archive = PackageDraft.write(uploads, p.zip());
            DeployRefusedException refused = assertThrows(DeployRefusedException.class,
                    () -> OnVirtualThread.call(() -> deployments.deploy(p.name(), Optional.empty(), archive)), "the deploy of " + p.name() + " is refused");
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

        /** {@code packages/<name>/<sha>/} of this node's data directory. */
        Path dir(String name, String sha) {
            return data.resolve("packages").resolve(name).resolve(sha);
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

    static DeploymentRecord.Pin pin(Zipped p) {
        return new DeploymentRecord.Pin(p.name(), p.version(), p.sha());
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
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common);

        DeployResult r = node.deploy(invoice("1.0.0", common.draft()));

        assertEquals(List.of(FLOW), r.added(), r.toString());
        assertEquals("UBL 1.2.0", node.reply(FLOW), "common's to-ubl.xsl, its include next to it in common");
        assertEquals(List.of(pin(common)), row("invoice").dependencies(), "the record pins common 1.2.0 by its archive");
        assertEquals(List.of(), row("common").dependencies(), "common pins nothing");
        assertEquals(common.content(), node.pkg("common").content(), "the node's content digest of common is the lock's");
    }

    /** common 1.3.0 deployed after invoice: invoice keeps 1.2.0 — the same output, no swap, its listing and record unchanged. */
    @Test
    void deployingCommon130LeavesInvoiceOn120() throws Exception {
        Node node = new Node("node-1");
        Zipped common120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common120);
        node.deploy(invoice("1.0.0", common120.draft()));
        Doors.Door running = node.doors.door(FLOW);

        DeployResult r = node.deploy(common("1.3.0", "UBL 1.3.0"));

        assertEquals(List.of(), r.added(), r.toString());
        assertEquals("1.3.0", node.pkg("common").version(), "common's current version");
        assertSame(running, node.doors.door(FLOW), "no consumer was built for invoice");
        assertEquals(0, running.stops(), "invoice's consumer was not stopped");
        assertEquals("UBL 1.2.0", node.reply(FLOW), "invoice still maps with common 1.2.0");
        assertEquals(List.of(new DeployedPackage.Dependency("common", "1.2.0", common120.content(), common120.sha(), true)),
                node.pkg("invoice").dependencies(), "invoice lists the version it pins");
        assertEquals(List.of(pin(common120)), row("invoice").dependencies(), "its record pins 1.2.0 still");
    }

    /** invoice pins common 1.2.0: on a node with common 1.3.0, or with no common, 422 naming what the node has; invoice is not listed. */
    @Test
    void aMissingVersionIs422NamingWhatTheNodeHas() throws Exception {
        Node node = new Node("node-1");
        PackageDraft invoice = invoice("1.0.0", common("1.2.0", "UBL 1.2.0"));

        DeployRefusedException none = node.refused(invoice);

        assertTrue(none.problems().contains("dependency common@1.2.0 is not deployed on this node (no package common)"), none.problems().toString());

        node.deploy(common("1.3.0", "UBL 1.3.0"));
        DeployRefusedException other = node.refused(invoice);

        assertTrue(other.problems().contains("dependency common@1.2.0 is not deployed on this node (it has common 1.3.0)"), other.problems().toString());
        assertEquals(Optional.empty(), node.listed("invoice"), "nothing of invoice is listed");
        assertEquals(List.of("common"), records.rows().stream().map(DeploymentRecord::name).toList(), "no row for invoice");
    }

    /** common 1.2.0 on the node has other bytes than the lock pins: 422 naming both digests; the lock with the node's digest deploys. */
    @Test
    void aContentMismatchIs422() throws Exception {
        Node node = new Node("node-1");
        PackageDraft onNode = common("1.2.0", "UBL 1.2.0");
        PackageDraft locked = common("1.2.0", "UBL 1.2.0 as built elsewhere");
        node.deploy(onNode);

        DeployRefusedException refused = node.refused(invoice("1.0.0", locked));

        assertTrue(refused.problems().contains("dependency common@1.2.0 has content " + onNode.content() + " on this node, package.lock pins " + locked.content()),
                refused.problems().toString());
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
     * common 1.2.0 listed FAILED after a restart (its archive gone from the store, its directory still on disk) is not available: 422.
     * Deployed again, it is.
     */
    @Test
    void aFailedVersionIsNotAvailable() throws Exception {
        Node first = new Node("node-1");
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        first.close();
        Files.delete(bucket.resolve(common.sha() + ".zip"));
        Node restarted = new Node("node-1b", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.pkg("common").state(), "fixture: common is listed FAILED");
        assertTrue(Files.isDirectory(restarted.dir("common", common.sha())), "fixture: its directory is still on disk");

        DeployRefusedException refused = restarted.refused(invoice("1.0.0", common.draft()));

        assertTrue(refused.problems().stream().anyMatch(l -> l.startsWith("dependency common@1.2.0 is not deployed on this node")), refused.problems().toString());

        restarted.deploy(common);
        assertEquals(List.of(FLOW), restarted.deploy(invoice("1.0.0", common.draft())).added(), "witness: common deployed again is available");
        assertEquals("UBL 1.2.0", restarted.reply(FLOW));
    }

    /** common 1.2.0 pinned by invoice outlives three later versions on disk; 1.3.0, pinned by nobody, is pruned once neither current nor previous. */
    @Test
    void aPinnedVersionIsNeverPrunedAndAnUnpinnedOneIs() throws Exception {
        Node node = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        Zipped c130 = Zipped.of(common("1.3.0", "UBL 1.3.0"));
        node.deploy(c130);
        node.deploy(common("1.4.0", "UBL 1.4.0"));

        assertTrue(Files.isDirectory(node.dir("common", c120.sha())), "1.2.0 is neither current nor previous, and pinned: kept");

        node.deploy(common("1.5.0", "UBL 1.5.0"));

        assertFalse(Files.exists(node.dir("common", c130.sha())), "1.3.0 is neither current, previous nor pinned: pruned");
        assertTrue(Files.isDirectory(node.dir("common", c120.sha())), "1.2.0 is still pinned: kept");
        assertEquals("UBL 1.2.0", node.reply(FLOW));
    }

    /** A stylesheet missing from common 1.2.0, and an include in common leaving it: 422 naming the flow, no absolute path nor archive SHA-256. */
    @Test
    void aMissingDependencyFileIs422WithoutAnAbsolutePath() throws Exception {
        Node node = new Node("node-1");
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0")
                .file("xslt/escape.xsl", XSL + "<xsl:include href=\"../../escape.xsl\"/><xsl:output method=\"text\"/><xsl:template match=\"/\">x</xsl:template>"
                        + "</xsl:stylesheet>"));
        node.deploy(common);

        DeployRefusedException missing = node.refused(user("invoice", "1.0.0", "common:xslt/missing.xsl", common.draft()));

        assertTrue(missing.problems().stream().anyMatch(l -> l.contains("'to-ubl'") && l.endsWith("stylesheet 'common:xslt/missing.xsl' does not exist in common@1.2.0")),
                missing.problems().toString());
        for (String l : missing.problems()) assertFalse(l.contains(common.sha()), "no archive directory in '" + l + "'");

        DeployRefusedException escape = node.refused(user("invoice", "1.0.0", "common:xslt/escape.xsl", common.draft()));

        assertTrue(escape.problems().stream().anyMatch(l -> l.contains("to-ubl") && l.contains("../../escape.xsl")), escape.problems().toString());
        for (String l : escape.problems()) assertFalse(l.contains(common.sha()), "no archive directory in '" + l + "'");
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
        Zipped c121 = Zipped.of(common("1.2.1", "UBL 1.2.0"));
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

    /** A node with an empty data directory restores common 1.3.0 and invoice, whose pin 1.2.0 it fetches by its SHA-256 and maps with. */
    @Test
    void restoreOnAnEmptyDataDirInstallsThePin() throws Exception {
        Node first = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        first.deploy(invoice("1.0.0", c120.draft()));
        first.deploy(common("1.3.0", "UBL 1.3.0"));

        Node second = new Node("node-2");
        second.restore();

        assertEquals(DeployedPackage.State.RUNNING, second.pkg("invoice").state(), second.pkg("invoice").problems().toString());
        assertEquals("1.3.0", second.pkg("common").version());
        assertEquals(1, second.fetches.fetchesOf(c120.sha()), "the pin is fetched by its SHA-256: " + second.fetches.keys());
        assertTrue(Files.isDirectory(second.dir("common", c120.sha())), "and unpacked under common");
        assertEquals("UBL 1.2.0", second.reply(FLOW), "invoice maps with its pin, not common's current version");
    }

    /** invoice's pin is gone from the archive store: at restore on an empty data directory invoice is FAILED naming the pin; common runs. */
    @Test
    void aPinNotInTheArchiveStoreIsFailedAtRestore() throws Exception {
        Node first = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        first.deploy(invoice("1.0.0", c120.draft()));
        first.deploy(common("1.3.0", "UBL 1.3.0"));
        Files.delete(bucket.resolve(c120.sha() + ".zip"));

        Node second = new Node("node-2");
        second.restore();

        DeployedPackage invoice = second.pkg("invoice");
        assertEquals(DeployedPackage.State.FAILED, invoice.state(), invoice.toString());
        assertTrue(invoice.problems().stream().anyMatch(l -> l.contains("common@1.2.0") && l.contains(c120.sha())), "a line names the pin: " + invoice.problems());
        assertEquals(List.of(), invoice.flows(), "a FAILED package has no flows");
        assertTrue(second.doors.doors().stream().noneMatch(d -> d.flow().equals(FLOW) && d.started()), "invoice's consumer never started: " + second.doors.events());
        assertEquals(DeployedPackage.State.RUNNING, second.pkg("common").state(), "common runs");
    }

    /** top -> mid -> base: the record pins mid and base; billing -> mid and base (a diamond) pins each once. */
    @Test
    void theRecordPinsTheClosureNotOnlyDirectDependencies() throws Exception {
        Node node = new Node("node-1");
        Zipped base = Zipped.of(library("base", "1.0.0", "base"));
        node.deploy(base);
        Zipped mid = Zipped.of(library("mid", "1.0.0", "mid", base.draft()));
        node.deploy(mid);

        node.deploy(user("top", "1.0.0", "mid:xslt/to-ubl.xsl", mid.draft()));

        assertEquals(Set.of(pin(mid), pin(base)), Set.copyOf(row("top").dependencies()), "the closure: " + row("top").dependencies());
        assertEquals(2, row("top").dependencies().size(), row("top").dependencies().toString());
        assertEquals(List.of(pin(base)), row("mid").dependencies());
        assertEquals(Set.of(new DeployedPackage.Dependency("mid", "1.0.0", mid.content(), mid.sha(), true),
                new DeployedPackage.Dependency("base", "1.0.0", base.content(), base.sha(), false)), Set.copyOf(node.pkg("top").dependencies()));

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
     * A restart over the same data directory: common (current 1.3.0) is restored before invoice, which pins 1.2.0 — common's prune
     * keeps 1.2.0 though invoice is not restored yet, so it is not fetched; alpha, restored before common, pins 1.2.0 too: kept.
     */
    @Test
    void aPinnedVersionSurvivesARestorePruneInEitherNameOrder() throws Exception {
        Node first = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        first.deploy(invoice("1.0.0", c120.draft()));
        first.deploy(common("1.3.0", "UBL 1.3.0"));
        first.deploy(common("1.4.0", "UBL 1.4.0"));
        first.close();

        Node restarted = new Node("node-1b", first.data);
        restarted.restore();

        assertEquals(0, restarted.fetches.fetchesOf(c120.sha()), "1.2.0 was kept on disk, not fetched: " + restarted.fetches.keys());
        assertTrue(Files.isDirectory(restarted.dir("common", c120.sha())));
        assertEquals("UBL 1.2.0", restarted.reply(FLOW));

        restarted.deploy(user("alpha", "1.0.0", "common:xslt/to-ubl.xsl", c120.draft()));
        restarted.deploy(common("1.5.0", "UBL 1.5.0"));
        restarted.close();
        Node empty = new Node("node-2");
        empty.restore();

        assertEquals(1, empty.fetches.fetchesOf(c120.sha()), "alpha's pin is fetched once: common's prune kept it for invoice: " + empty.fetches.keys());
        assertTrue(Files.isDirectory(empty.dir("common", c120.sha())), "alpha's pin, fetched before common's restore, survives common's prune");
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

    // --- guards (P2-02b review round: M28, M29, M30)

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
     * common 1.2.0, pinned by invoice and neither current nor previous, deployed again and refused (the archive store's put fails):
     * the refusal deletes what it wrote, never the directory a record pins — invoice serves on and billing still resolves 1.2.0.
     */
    @Test
    void aRefusedRedeployOfAPinnedVersionKeepsItsDirectory() throws Exception {
        Node node = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        node.deploy(common("1.3.0", "UBL 1.3.0"));
        node.deploy(common("1.4.0", "UBL 1.4.0"));
        assertTrue(Files.isDirectory(node.dir("common", c120.sha())), "fixture: 1.2.0 is on disk, pinned, neither current nor previous");
        shelf.failNextPut(new IOException("the bucket is down"));

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> node.deploy(c120), "the redeploy of 1.2.0 is refused");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertEquals("1.4.0", node.pkg("common").version(), "fixture: 1.4.0 stays current");
        assertTrue(Files.isRegularFile(node.dir("common", c120.sha()).resolve("xslt/to-ubl.xsl")), "the refusal leaves the directory invoice's record pins");
        assertEquals(List.of("billing/to-ubl"), node.deploy(user("billing", "1.0.0", "common:xslt/to-ubl.xsl", c120.draft())).added(), "1.2.0 still resolves");
        assertEquals("UBL 1.2.0", node.reply("billing/to-ubl"));
    }

    /** invoice FAILED at restore (its own archive gone) keeps its record's pin: a later deploy of common prunes 1.3.0's previous, not 1.2.0. */
    @Test
    void aPackageFailedAtRestoreKeepsItsPinsFromPrune() throws Exception {
        Node first = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        Zipped invoice = Zipped.of(invoice("1.0.0", c120.draft()));
        first.deploy(invoice);
        Zipped c130 = Zipped.of(common("1.3.0", "UBL 1.3.0"));
        first.deploy(c130);
        first.deploy(common("1.4.0", "UBL 1.4.0"));
        first.close();
        Files.delete(bucket.resolve(invoice.sha() + ".zip"));
        Node restarted = new Node("node-1b", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.pkg("invoice").state(), "fixture: invoice is FAILED");
        assertTrue(Files.isDirectory(restarted.dir("common", c120.sha())), "fixture: 1.2.0 is on disk after the restore");

        restarted.deploy(common("1.5.0", "UBL 1.5.0"));

        assertFalse(Files.exists(restarted.dir("common", c130.sha())), "witness: 1.3.0, pinned by nobody, is pruned");
        assertTrue(Files.isDirectory(restarted.dir("common", c120.sha())), "invoice's record, FAILED on this node, pins 1.2.0: kept");
    }

    /**
     * invoice 1.0.0 maps with common 1.2.0, whose stylesheet is then deleted from its directory; 1.0.1 (on common 1.3.0) does not
     * start: the rollback cannot build 1.0.0's flow again, and its line names the dependency file without an absolute path or the
     * archive directory.
     */
    @Test
    void aRollbackThatCannotRebuildFromADependencyNamesNoAbsolutePath() throws Exception {
        Node node = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        PackageDraft c130 = common("1.3.0", "UBL 1.3.0");
        node.deploy(c130);
        Files.delete(node.dir("common", c120.sha()).resolve("xslt/to-ubl.xsl"));
        node.doors.failStartOf(FLOW, "address in use");

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> node.deploy(invoice("1.0.1", c130)), "1.0.1 does not start");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage() + " " + refused.problems());
        List<String> again = refused.problems().stream().filter(l -> l.startsWith("flow 'to-ubl' does not build again: ")).toList();
        assertEquals(1, again.size(), "a line says to-ubl does not build again: " + refused.problems());
        assertTrue(again.getFirst().contains("to-ubl.xsl"), "it names the file: " + again);
        assertNoAbsolutePath(refused.problems());
        for (String l : refused.problems()) assertFalse(l.contains(c120.sha()), "no archive directory in '" + l + "'");
    }

    // --- review r1 (P2-02b)

    /**
     * invoice 1.0.0 on common 1.2.0; common 1.2.1 has the same bytes, so invoice 1.0.1 locking it swaps nothing and its flow runs on,
     * built over 1.2.0's directory; common 1.3.0 then makes 1.2.0 neither current, previous nor pinned — but in use. invoice 1.0.2 on
     * 1.3.0 does not start: the rollback rebuilds the running flow from the directory it reads, and it answers as before.
     */
    @Test
    void aRollbackAfterALockBumpRebuildsFromTheDirTheFlowReads() throws Exception {
        Node node = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
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
        assertTrue(Files.isDirectory(node.dir("common", c120.sha())), "1.2.0's directory, in use by the running flow, was kept");
    }

    /**
     * invoice's flow reads common 1.2.0's directory through its build only (the lock bumped to 1.2.1 with the same bytes, then common
     * 1.3.0): no record pins 1.2.0, it is neither current nor previous, but in use. A deploy of common at 1.2.0's archive, refused
     * (the archive store's put fails), deletes what it wrote — never that directory; invoice answers as before.
     */
    @Test
    void aRefusedRedeployOfAVersionARunningFlowReadsKeepsItsDirectory() throws Exception {
        Node node = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        node.deploy(invoice("1.0.0", c120.draft()));
        PackageDraft c121 = common("1.2.1", "UBL 1.2.0");
        node.deploy(c121);
        assertEquals(List.of(), node.deploy(invoice("1.0.1", c121)).swapped(), "fixture: the same bytes, nothing swaps");
        node.deploy(common("1.3.0", "UBL 1.3.0"));
        assertTrue(records.rows().stream().flatMap(r -> r.dependencies().stream()).noneMatch(p -> p.archiveSha256().equals(c120.sha())),
                "fixture: no record pins 1.2.0: " + records.rows());
        assertTrue(Files.isDirectory(node.dir("common", c120.sha())), "fixture: 1.2.0's directory is kept, in use");
        shelf.failNextPut(new IOException("the bucket is down"));

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> node.deploy(c120), "the redeploy of 1.2.0 is refused");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertEquals("1.3.0", node.pkg("common").version(), "fixture: 1.3.0 stays current");
        assertTrue(Files.isRegularFile(node.dir("common", c120.sha()).resolve("xslt/to-ubl.xsl")), "the refusal leaves the directory the running flow reads");
        assertEquals("UBL 1.2.0", node.reply(FLOW));
    }

    /**
     * invoice is FAILED at restore (its own archive gone): its record pins common 1.2.0, and no listed flow reads it. A deploy of
     * common at 1.2.0's archive, refused (the archive store's put fails), leaves that directory.
     */
    @Test
    void aRefusedRedeployOfAVersionOnlyAFailedPackagePinsKeepsItsDirectory() throws Exception {
        Node first = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        Zipped invoice = Zipped.of(invoice("1.0.0", c120.draft()));
        first.deploy(invoice);
        first.deploy(common("1.3.0", "UBL 1.3.0"));
        first.deploy(common("1.4.0", "UBL 1.4.0"));
        first.close();
        Files.delete(bucket.resolve(invoice.sha() + ".zip"));
        Node restarted = new Node("node-1b", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.pkg("invoice").state(), "fixture: invoice is FAILED");
        assertEquals(List.of(), restarted.deployments.flows(), "fixture: no listed flow reads 1.2.0");
        assertTrue(Files.isDirectory(restarted.dir("common", c120.sha())), "fixture: 1.2.0's directory is on disk, pinned");
        shelf.failNextPut(new IOException("the bucket is down"));

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> restarted.deploy(c120), "the redeploy of 1.2.0 is refused");

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertEquals("1.4.0", restarted.pkg("common").version(), "fixture: 1.4.0 stays current");
        assertTrue(Files.isRegularFile(restarted.dir("common", c120.sha()).resolve("xslt/to-ubl.xsl")), "invoice's FAILED record pins 1.2.0: the refusal leaves it");
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
     * The archive store holds other bytes under the pin's SHA-256 — the same files zipped again, so the same content: at restore on
     * an empty data directory invoice is FAILED naming the pin, and nothing of those bytes is unpacked; common runs.
     */
    @Test
    void aPinWithOtherBytesInTheArchiveStoreIsFailedAtRestore() throws Exception {
        Node first = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(c120);
        first.deploy(invoice("1.0.0", c120.draft()));
        first.deploy(common("1.3.0", "UBL 1.3.0"));
        byte[] other = c120.draft().zipWithDirectories(List.of("xslt/"));
        assertFalse(PackageDraft.sha256(other).equals(c120.sha()), "fixture: other bytes, the same files");
        Files.write(bucket.resolve(c120.sha() + ".zip"), other);

        Node second = new Node("node-2");
        second.restore();

        DeployedPackage invoice = second.pkg("invoice");
        assertEquals(DeployedPackage.State.FAILED, invoice.state(), invoice.toString());
        assertTrue(invoice.problems().stream().anyMatch(l -> l.contains("common@1.2.0") && l.contains(c120.sha())), "a line names the pin: " + invoice.problems());
        assertEquals(1, second.fetches.fetchesOf(c120.sha()), "fixture: the pin was fetched");
        assertFalse(Files.exists(second.dir("common", c120.sha())), "nothing of the other bytes is unpacked under the pin's key");
        assertTrue(second.doors.doors().stream().noneMatch(d -> d.flow().equals(FLOW) && d.started()), "invoice's consumer never started");
        assertEquals(DeployedPackage.State.RUNNING, second.pkg("common").state(), "common runs");
    }

    /** Fixture self-check: the lock helper writes what D-087 names, so a red above is the node's, not the fixture's. */
    @Test
    void theFixtureLockIsTheDocumentedShape() {
        PackageDraft c = common("1.2.0", "x");
        PackageDraft inv = invoice("1.0.0", c);

        assertEquals(new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.2.0")), inv.manifest().getJsonArray("dependencies"));
        JsonObject lock = new JsonObject(new String(inv.files().get("package.lock"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(Set.of("schemaVersion", "kind", "dependencies"), lock.fieldNames());
        assertEquals(new JsonObject().put("name", "common").put("version", "1.2.0").put("content", c.content()), lock.getJsonArray("dependencies").getJsonObject(0));
        assertTrue(c.content().matches("sha256:[0-9a-f]{64}"), c.content());
    }
}
