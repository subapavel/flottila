package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.deploy.DeploymentsDependenciesTest.Zipped;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.pg.PoolRefusedException;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.VertxException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static io.flottila.runtime.deploy.DeploymentsDependenciesTest.common;
import static io.flottila.runtime.deploy.DeploymentsDependenciesTest.invoice;
import static io.flottila.runtime.deploy.DeploymentsDependenciesTest.library;
import static io.flottila.runtime.deploy.DeploymentsDependenciesTest.user;
import static io.flottila.runtime.deploy.DeploymentsTest.until;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-02c (D-095, D-060 e, D-087): undeploying a package. {@code Deployments.undeploy(name)} checks, in order, the name's pattern
 * (BAD_REQUEST), the environment (a deployed one: FORBIDDEN "manual undeploy is non-production only (ARCHITECTURE §9)"), the node's
 * deploy lock (CONFLICT, or STOPPING while closing), closing (STOPPING), that the package is known (NOT_FOUND "no package '<name>'
 * is deployed") and that no other record pins it, a FAILED one included (CONFLICT "<name>@<version> is in use by <a>, <b>", the
 * dependents sorted, each once). Then, under the lock: the record is deleted — the commit point; a delete that fails is the node's
 * failure (IOException) and changes nothing else — then every flow of the package stops and closes (a stop that fails is a
 * WARNING), the listing drops it and its unpacked directories are pruned (a prune that fails is a WARNING). The archives stay in
 * the archive store: deploying it again is the rollback. A restart does not restore it.
 *
 * <p>Fixtures: nodes of one pool as in {@link DeploymentsDependenciesTest} — each with its own data directory and {@link Doors},
 * over one {@link Ledger} and one {@link Shelf} — and its packages ({@code common} with no flows, {@code invoice} and
 * {@code billing} mapping with {@code common:xslt/to-ubl.xsl}, so a reply is the version of common they read). One {@link #log}
 * receives the Ledger's {@code save}/{@code delete} and every node's Doors events, in the order made.
 *
 * <p>The shape assumed (P2-02c test-author): {@code UndeployResult Deployments.undeploy(String name) throws
 * DeployRefusedException, IOException} (blocking: on a virtual thread), {@code record UndeployResult(String name, String version,
 * List<String> flowsRemoved)}, {@code DeployRefusedException.Kind.NOT_FOUND}, {@code Future<Void> DeploymentStore.delete(String name)}.
 */
class DeploymentsUndeployTest {

    static final String FLOW = "invoice/to-ubl";
    static final String BILLING = "billing/to-ubl";
    static final String FORBIDDEN = "manual undeploy is non-production only (ARCHITECTURE §9)";

    @TempDir Path tmp;
    Path bucket;
    Path uploads;
    final FakeRuntime rt = FakeRuntime.create();
    /** The Ledger's saves and deletes and every node's Doors events, in the order made. */
    final List<String> log = new CopyOnWriteArrayList<>();
    final Ledger records = new Ledger(log::add);
    Shelf shelf;
    final List<Node> nodes = new ArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void wire() {
        bucket = tmp.resolve("bucket");
        uploads = tmp.resolve("uploads");
        shelf = new Shelf(bucket, tmp.resolve("shelf-reads"), trace -> {});
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void closeEveryNode() throws Exception {
        try {
            for (Node n : nodes) n.doors.releaseAll();
            records.releaseAll();
            for (Node n : nodes) n.close();
        } finally {
            flottila.removeHandler(capture);
        }
    }

    // --- fixture: nodes of one pool

    final class Node {
        final String id;
        final Path data;
        final Doors doors = new Doors();
        final DeploymentsRestoreTest.Fetches fetches = new DeploymentsRestoreTest.Fetches(shelf);
        /** Its work queue: records every {@code hasItems} call with the rows of that flow at the call (P2-02c r2). */
        final CountingQueue queue = new CountingQueue(rt.clock());
        final Deployments deployments;
        private boolean closed;

        Node(String id) {
            this(id, "dev", tmp.resolve(id));
        }

        /** A node in {@code env} whose data directory is {@code data}: another node's, once that one is closed, is a restart. */
        Node(String id, String env, Path data) {
            this.id = id;
            this.data = data;
            Map<String, String> e = new HashMap<>();
            e.put("FLOTTILA_ENV", env);
            e.put("FLOTTILA_NODE_ID", id);
            e.put("FLOTTILA_DATA_DIR", data.toString());
            e.put("FLOTTILA_STOP_DEADLINE", "PT5S");
            e.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
            e.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            doors.trace(log::add);
            SaxonXml xml = new SaxonXml();
            List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), steps, xml.languages(),
                    new InMemoryMessageStore(), new InMemoryDlq(), queue, () -> 0L, records, fetches);
            nodes.add(this);
        }

        DeployResult deploy(Zipped p) throws Exception {
            Path archive = PackageDraft.write(uploads, p.zip());
            return OnVirtualThread.call(() -> deployments.deploy(p.name(), Optional.empty(), archive));
        }

        DeployResult deploy(PackageDraft p) throws Exception {
            return deploy(Zipped.of(p));
        }

        OnVirtualThread.Call<DeployResult> deploying(PackageDraft p) throws IOException {
            Path archive = PackageDraft.write(uploads, p.zip());
            return OnVirtualThread.start(() -> deployments.deploy(p.name(), Optional.empty(), archive));
        }

        UndeployResult undeploy(String name) throws Exception {
            return OnVirtualThread.call(() -> deployments.undeploy(name));
        }

        /** The undeploy of {@code name}, refused as {@code kind}; its problems are none. */
        DeployRefusedException refused(String name, Kind kind) {
            DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> undeploy(name), "the undeploy of '" + name + "' is refused");
            assertEquals(kind, refused.kind(), "the undeploy of '" + name + "': " + refused.getMessage());
            assertEquals(List.of(), refused.problems(), "an undeploy refusal is an error line only: " + refused.getMessage());
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

        List<String> packageNames() {
            return deployments.packages().stream().map(DeployedPackage::name).toList();
        }

        List<String> flowIds() {
            return deployments.flows().stream().map(DeployedFlow::id).toList();
        }

        /** A message into {@code flow} through the consumer built last for it, accepted (at-least-once: saved and queued). */
        Envelope accept(String flow) throws Exception {
            Envelope e = rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
            Doors.Door door = doors.door(flow);
            assertInstanceOf(Ack.Accepted.class, within(door.deliver(e), door + "'s answer"), "fixture: " + door + " accepts");
            return e;
        }

        /** Every {@code hasItems(flow)} call so far, as {@code "<flow> rows=<n>"}. */
        List<String> hasItemsCalls(String flow) {
            return queue.hasItemsCalls().stream().filter(c -> c.startsWith(flow + " ")).toList();
        }

        /** {@code packages/<name>/} of this node's data directory. */
        Path packageDir(String name) {
            return data.resolve("packages").resolve(name);
        }

        /** {@code packages/<name>/<sha>/} of this node's data directory. */
        Path dir(String name, String sha) {
            return packageDir(name).resolve(sha);
        }
    }

    // --- fixture: packages and helpers

    /** {@code billing}@1.0.0 mapping with {@code common:xslt/to-ubl.xsl} of {@code common}: flow {@link #BILLING}. */
    static PackageDraft billing(PackageDraft common) {
        return user("billing", "1.0.0", "common:xslt/to-ubl.xsl", common);
    }

    /**
     * {@code shop}@1.0.0: flows {@code audit}, {@code ledger} and {@code relay} at-least-once and {@code orders} sync, each from
     * its own in endpoint to {@code partner}, each at-least-once one running one item at most.
     */
    static PackageDraft queued() {
        PackageDraft p = DeploymentsTest.flows("shop", new TreeMap<>(Map.of("audit", "at-least-once", "ledger", "at-least-once", "orders", "sync", "relay", "at-least-once")));
        for (String f : List.of("audit", "ledger", "relay")) p.flowDoc(f).getJsonObject("limits").put("maxInFlight", 1);
        return p;
    }

    static final List<String> SHOP_FLOWS = List.of("shop/audit", "shop/ledger", "shop/orders", "shop/relay");

    /** The 409 line of an at-least-once flow of the package whose items the work queue holds (P2-02c r2, the packet's text). */
    static String itemsLine(String localFlow) {
        return "flow '" + localFlow + "' runs at-least-once and the work queue holds items of it (queued, leased or held): undeploy it once they are done";
    }

    List<String> rowNames() {
        return records.rows().stream().map(DeploymentRecord::name).toList();
    }

    /** The names of the directories in {@code dir}; empty when it does not exist. */
    static List<String> children(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    List<LogRecord> warningsWith(String text) {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING && DeploymentsProducersTest.texts(r).stream().anyMatch(t -> t.contains(text))).toList();
    }

    static Throwable rootOf(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c;
    }

    static boolean carries(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) if (String.valueOf(c.getMessage()).contains(text)) return true;
        return false;
    }

    /** A message through {@code door} after its flow stopped: a retryable Nack, never a run. */
    void assertStoppedDelivery(Node node, Doors.Door door) throws Exception {
        int sent = node.doors.partner().sentTo("partner").size();
        Ack gone = within(door.deliver(rt.ingress(door.flow(), Headers.empty(), Buffer.buffer("<invoice/>"))), "an answer through the undeployed " + door);
        assertTrue(gone instanceof Ack.Nack n && n.retryable(), "the undeployed flow no longer runs a message: " + gone);
        assertEquals(sent, node.doors.partner().sentTo("partner").size(), "nothing reached the partner through the undeployed flow");
    }

    // --- cases

    /**
     * invoice (pinning common, pinned by nobody) undeploys: the result names it, its version and its flow; its record is deleted
     * before its flow stops; it is no longer listed, its consumer is stopped — a message through it no longer runs — and its
     * producer closed; its archive stays in the archive store, and deploying it again (the rollback) runs it again. common,
     * which it pinned, runs on.
     */
    @Test
    void anUnpinnedPackageIsUndeployedAndItsFlowsStop() throws Exception {
        Node node = new Node("node-1");
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common);
        Zipped invoice = Zipped.of(invoice("1.0.0", common.draft()));
        node.deploy(invoice);
        assertEquals("UBL 1.2.0", node.reply(FLOW), "fixture: invoice serves");
        Doors.Door door = node.doors.door(FLOW);
        int mark = log.size();

        UndeployResult r = node.undeploy("invoice");

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), r, "the package, its version and its flows by qualified id");
        assertEquals(List.of("common"), rowNames(), "invoice's record is deleted, common's stays");
        assertEquals(List.of("common"), node.packageNames(), "invoice is no longer listed");
        assertEquals(List.of(), node.flowIds(), "its flow is no longer listed");
        assertTrue(door.stopped(), "its consumer is stopped: " + door.history());
        assertStoppedDelivery(node, door);
        until(() -> node.doors.openProducers().isEmpty(), "its producer is closed: " + node.doors.producers());
        List<String> all = List.copyOf(log);
        List<String> since = all.subList(mark, all.size());
        assertEquals(1, since.stream().filter("delete invoice"::equals).count(), "one delete of invoice's record: " + since);
        assertTrue(since.indexOf("delete invoice") < since.indexOf("stop " + FLOW), "the record delete (the commit point) comes before the stop: " + since);
        assertTrue(shelf.holds(invoice.sha()), "invoice's archive stays in the archive store");
        assertTrue(shelf.holds(common.sha()), "and common's");

        assertEquals(List.of(FLOW), node.deploy(invoice).added(), "deployed again (the rollback), invoice is a first deploy");
        assertEquals("UBL 1.2.0", node.reply(FLOW), "and serves again");
    }

    /**
     * common pinned by invoice is 409 "common@1.2.0 is in use by invoice"; pinned also by billing, the dependents are named sorted;
     * base pinned by mid (direct) and top (through mid's lock) names each once. Nothing changes: rows, listing, the flows serve.
     */
    @Test
    void aPinnedPackageIsRefusedNamingItsDependents() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        node.deploy(invoice("1.0.0", common));
        List<DeploymentRecord> rows = records.rows();
        List<DeployedPackage> listed = node.deployments.packages();

        DeployRefusedException one = node.refused("common", Kind.CONFLICT);

        assertEquals("common@1.2.0 is in use by invoice", one.error());
        assertEquals(rows, records.rows(), "no record is deleted");
        assertEquals(listed, node.deployments.packages(), "the listing is unchanged");
        assertFalse(log.contains("delete common"), "no delete was called: " + log);
        assertFalse(node.doors.door(FLOW).stopped(), "invoice runs on");
        assertEquals("UBL 1.2.0", node.reply(FLOW), "and maps with common 1.2.0");

        node.deploy(billing(common));   // deployed after invoice: the error's order is the names', not the deploys'

        assertEquals("common@1.2.0 is in use by billing, invoice", node.refused("common", Kind.CONFLICT).error(), "dependents sorted");

        PackageDraft base = library("base", "1.0.0", "base");
        node.deploy(base);
        PackageDraft mid = library("mid", "1.0.0", "mid", base);
        node.deploy(mid);
        node.deploy(library("top", "1.0.0", "top", mid));

        assertEquals("base@1.0.0 is in use by mid, top", node.refused("base", Kind.CONFLICT).error(), "a dependent through a lock counts, each once");
        assertEquals("mid@1.0.0 is in use by top", node.refused("mid", Kind.CONFLICT).error());
        assertEquals(List.of("base", "billing", "common", "invoice", "mid", "top"), rowNames(), "every record stays");
        assertFalse(log.stream().anyMatch(e -> e.startsWith("delete ")), "no delete was called: " + log);
    }

    /** invoice listed FAILED after a restart (its archive gone) still pins common: 409; once invoice is undeployed, common undeploys. */
    @Test
    void aFailedDependentStillPins() throws Exception {
        Node first = new Node("node-1");
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        Zipped invoice = Zipped.of(invoice("1.0.0", common.draft()));
        first.deploy(invoice);
        first.close();
        Files.delete(bucket.resolve(invoice.sha() + ".zip"));
        Node restarted = new Node("node-1b", "dev", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.listed("invoice").orElseThrow().state(), "fixture: invoice is listed FAILED");

        DeployRefusedException refused = restarted.refused("common", Kind.CONFLICT);

        assertEquals("common@1.2.0 is in use by invoice", refused.error(), "a FAILED package's record pins as a running one's does");
        assertEquals(List.of("common", "invoice"), rowNames(), "no record is deleted");
        assertEquals(List.of("common", "invoice"), restarted.packageNames());

        restarted.undeploy("invoice");
        assertEquals(new UndeployResult("common", "1.2.0", List.of()), restarted.undeploy("common"), "witness: with invoice gone, common undeploys");
    }

    /** invoice and billing pin common: undeploying invoice leaves billing's pin (409 naming billing); undeploying billing frees common. */
    @Test
    void undeployingTheLastDependentThenTheDependencyWorks() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        node.deploy(invoice("1.0.0", common));
        node.deploy(billing(common));

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), node.undeploy("invoice"));
        assertEquals("common@1.2.0 is in use by billing", node.refused("common", Kind.CONFLICT).error(), "billing still pins common");
        assertEquals("UBL 1.2.0", node.reply(BILLING), "billing serves on, with common 1.2.0");
        assertEquals(new UndeployResult("billing", "1.0.0", List.of(BILLING)), node.undeploy("billing"));

        UndeployResult r = node.undeploy("common");

        assertEquals(new UndeployResult("common", "1.2.0", List.of()), r, "the last dependent gone, common undeploys; it had no flows");
        assertEquals(List.of(), records.rows(), "no record is left");
        assertEquals(List.of(), node.packageNames(), "nothing is listed");
    }

    /** A name the node has no package of — never deployed, or undeployed already — is NOT_FOUND; nothing is deleted. */
    @Test
    void anUnknownPackageIsNotFound() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        node.deploy(invoice("1.0.0", common));
        List<DeploymentRecord> rows = records.rows();

        DeployRefusedException none = node.refused("billing", Kind.NOT_FOUND);

        assertEquals("no package 'billing' is deployed", none.error());
        assertEquals(rows, records.rows(), "no record is deleted");
        assertEquals(List.of("common", "invoice"), node.packageNames());
        assertFalse(log.stream().anyMatch(e -> e.startsWith("delete ")), "no delete was called: " + log);

        node.undeploy("invoice");
        assertEquals("no package 'invoice' is deployed", node.refused("invoice", Kind.NOT_FOUND).error(), "undeployed already: not found");
        assertEquals(1, log.stream().filter("delete invoice"::equals).count(), "the second undeploy deleted nothing: " + log);
    }

    /**
     * A node in prod restores invoice and common from the pool's records; an undeploy there is 403 — of a package it runs, of a
     * name it does not have, and while it closes — and only a bad name comes before it (400). invoice serves on, every record stays.
     */
    @Test
    void aDeployedEnvironmentRefusesUndeploy() throws Exception {
        Node dev = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        dev.deploy(common);
        dev.deploy(invoice("1.0.0", common));
        dev.close();
        Node prod = new Node("node-2", "prod", tmp.resolve("node-2"));
        prod.restore();
        assertEquals("UBL 1.2.0", prod.reply(FLOW), "fixture: prod restored invoice and it serves");
        List<DeploymentRecord> rows = records.rows();

        DeployRefusedException refused = prod.refused("invoice", Kind.FORBIDDEN);

        assertEquals(FORBIDDEN, refused.error());
        assertEquals(rows, records.rows(), "no record is deleted");
        assertEquals(List.of("common", "invoice"), prod.packageNames(), "both stay listed");
        assertFalse(prod.doors.door(FLOW).stopped(), "invoice's consumer is not stopped");
        assertEquals("UBL 1.2.0", prod.reply(FLOW), "and it serves on");
        assertEquals(FORBIDDEN, prod.refused("billing", Kind.FORBIDDEN).error(), "a name prod does not have: 403 before 404");
        prod.refused("Invoice", Kind.BAD_REQUEST);   // the name's pattern first
        prod.close();
        assertEquals(FORBIDDEN, prod.refused("invoice", Kind.FORBIDDEN).error(), "a closing node in a deployed environment answers 403 too");
        assertFalse(log.stream().anyMatch(e -> e.startsWith("delete ")), "no delete was called: " + log);
        assertEquals(rows, records.rows());
    }

    /** A name off the package name pattern is 400 before anything is looked up: nothing is deleted, common stays. */
    @Test
    void aBadNameIsRefused() throws Exception {
        Node node = new Node("node-1");
        node.deploy(common("1.2.0", "UBL 1.2.0"));

        for (String bad : List.of("Common", "../common", "common/x", "", "-common", "a".repeat(65))) {
            DeployRefusedException refused = node.refused(bad, Kind.BAD_REQUEST);
            assertFalse(refused.error().isBlank(), "the 400 names the problem: '" + bad + "'");
        }

        assertEquals(List.of("common"), rowNames(), "no record is deleted");
        assertEquals(List.of("common"), node.packageNames());
        assertFalse(log.stream().anyMatch(e -> e.startsWith("delete ")), "no delete was called: " + log);
        assertTrue(Files.isDirectory(node.packageDir("common")), "common's directory stays");
    }

    /** A closed node answers STOPPING, for a package it ran and for a name it never had (closing before the lookup). */
    @Test
    void aClosingNodeIsStopping() throws Exception {
        Node node = new Node("node-1");
        node.deploy(common("1.2.0", "UBL 1.2.0"));
        node.close();

        node.refused("common", Kind.STOPPING);
        node.refused("billing", Kind.STOPPING);

        assertEquals(List.of("common"), rowNames(), "the record stays: a closed node deletes nothing");
        assertFalse(log.stream().anyMatch(e -> e.startsWith("delete ")), "no delete was called: " + log);
    }

    /**
     * While a deploy holds the node's lock (billing's consumer start is held), an undeploy is 409 at once — of invoice, and of a
     * name the node does not have (the lock before the lookup); invoice serves on. Once the deploy is done, invoice undeploys.
     */
    @Test
    void anUndeployWhileADeployRunsIsConflict() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        node.deploy(invoice("1.0.0", common));
        Doors.Gate gate = node.doors.holdStart(1);
        OnVirtualThread.Call<DeployResult> deploying = node.deploying(billing(common));
        if (!gate.reached().await(20, TimeUnit.SECONDS)) fail("fixture: billing's deploy did not reach its consumer start: " + deploying.outcome());

        node.refused("invoice", Kind.CONFLICT);
        node.refused("ghost", Kind.CONFLICT);

        assertFalse(log.stream().anyMatch(e -> e.startsWith("delete ")), "no delete was called: " + log);
        assertFalse(node.doors.door(FLOW).stopped(), "invoice runs on");
        assertFalse(deploying.isDone(), "the deploy still holds the lock");
        gate.release();
        assertEquals(List.of(BILLING), OnVirtualThread.join(deploying).added(), "fixture: billing deployed");
        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), node.undeploy("invoice"), "witness: with the lock free, invoice undeploys");
    }

    /**
     * An undeploy holds the node's lock from before its record delete: while the delete is held, invoice still runs (nothing is
     * stopped before the commit point) and a deploy is 409; released, the undeploy stops invoice and returns.
     */
    @Test
    void aDeployWhileAnUndeployRunsIsConflict() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        node.deploy(invoice("1.0.0", common));
        Doors.Door door = node.doors.door(FLOW);
        Ledger.Hold hold = records.holdNextDelete();
        OnVirtualThread.Call<UndeployResult> undeploying = OnVirtualThread.start(() -> node.deployments.undeploy("invoice"));
        if (!hold.reached().await(20, TimeUnit.SECONDS)) fail("fixture: the undeploy did not reach its record delete: " + undeploying.outcome());

        assertFalse(door.stopped(), "while the record delete waits, invoice's consumer is not stopped: " + door.history());
        assertEquals("UBL 1.2.0", node.reply(FLOW), "and it serves");
        DeployRefusedException conflict = assertThrows(DeployRefusedException.class, () -> node.deploy(billing(common)), "a deploy while the undeploy runs");
        assertEquals(Kind.CONFLICT, conflict.kind(), conflict.getMessage());
        assertFalse(undeploying.isDone(), "the undeploy still waits on its delete");

        hold.release();

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), OnVirtualThread.join(undeploying));
        assertTrue(door.stopped(), "once the record is deleted, the flow stops: " + door.history());
        assertEquals(List.of("common"), rowNames());
    }

    /**
     * invoice's consumer stop is held: during the hold its record is deleted (the commit point is passed), yet invoice is still
     * listed with its flow and the undeploy has not returned; released, invoice leaves the listing and the undeploy returns. The
     * package leaves the listing only once its flows stopped.
     */
    @Test
    void thePackageLeavesTheListingOnlyAfterItsFlowsStop() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        node.deploy(invoice("1.0.0", common));
        Doors.Gate stop = node.doors.holdStopOf(FLOW);
        OnVirtualThread.Call<UndeployResult> undeploying = OnVirtualThread.start(() -> node.deployments.undeploy("invoice"));
        try {
            if (!stop.reached().await(20, TimeUnit.SECONDS)) fail("fixture: the undeploy did not reach invoice's consumer stop: " + undeploying.outcome());

            assertEquals(List.of("common"), rowNames(), "while the stop is held, invoice's record is deleted already");
            assertEquals(List.of("common", "invoice"), node.packageNames(), "and invoice is still listed");
            assertTrue(node.flowIds().contains(FLOW), "with its flow: " + node.flowIds());
            assertFalse(undeploying.isDone(), "the undeploy has not returned");
        } finally {
            stop.release();
        }

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), OnVirtualThread.join(undeploying), "released, the undeploy returns");
        assertEquals(List.of("common"), node.packageNames(), "invoice is no longer listed");
        assertEquals(List.of(), node.flowIds());
    }

    /** The record delete fails: the undeploy is the node's failure (IOException naming the cause) and nothing else changes. */
    @Test
    void aRecordDeleteThatFailsChangesNothing() throws Exception {
        Node node = new Node("node-1");
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common);
        Zipped invoice = Zipped.of(invoice("1.0.0", common.draft()));
        node.deploy(invoice);
        Doors.Door door = node.doors.door(FLOW);
        List<DeploymentRecord> rows = records.rows();
        List<DeployedPackage> listed = node.deployments.packages();
        List<DeployedFlow> flows = node.deployments.flows();
        List<String> dirs = children(node.packageDir("invoice"));
        records.failNextDelete("connection reset by peer");

        IOException failed = assertThrows(IOException.class, () -> node.undeploy("invoice"), "a delete that fails is the node's failure");

        assertTrue(carries(failed, "connection reset by peer"), "the failure names the cause: " + failed + " / " + rootOf(failed));
        assertTrue(log.contains("delete invoice"), "fixture: the delete was called: " + log);
        assertEquals(rows, records.rows(), "the record stays");
        assertEquals(listed, node.deployments.packages(), "invoice is listed as before");
        assertEquals(flows, node.deployments.flows(), "its flow too");
        assertFalse(door.stopped(), "its consumer was not stopped: " + door.history());
        assertEquals("UBL 1.2.0", node.reply(FLOW), "it serves on");
        assertEquals(dirs, children(node.packageDir("invoice")), "its directory stays");
        assertEquals(List.of(invoice.sha()), dirs, "fixture: one directory");

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), node.undeploy("invoice"), "witness: the next undeploy, whose delete works, undeploys");
    }

    /**
     * invoice undeployed on node 1 (billing runs on), then the node restarts on its data directory: the restore starts billing and
     * common and not invoice, which is neither listed nor built, nor fetched.
     */
    @Test
    void aRestoreAfterUndeployDoesNotStartIt() throws Exception {
        Node first = new Node("node-1");
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        Zipped invoice = Zipped.of(invoice("1.0.0", common.draft()));
        first.deploy(invoice);
        first.deploy(billing(common.draft()));
        first.undeploy("invoice");
        first.close();
        Node restarted = new Node("node-1b", "dev", first.data);

        restarted.restore();

        assertEquals(List.of("billing", "common"), restarted.packageNames(), "invoice is not restored");
        assertEquals(List.of(BILLING), restarted.flowIds(), "only billing's flow runs");
        assertEquals("UBL 1.2.0", restarted.reply(BILLING), "witness: the restore ran billing");
        assertEquals(List.of(BILLING), restarted.doors.doors().stream().map(Doors.Door::flow).toList(), "no consumer was built for invoice");
        assertEquals(List.of(), children(restarted.packageDir("invoice")), "nothing of invoice is on disk");
        assertEquals(0, restarted.fetches.fetchesOf(invoice.sha()), "invoice's archive was not fetched");
        assertTrue(shelf.holds(invoice.sha()), "it stays in the archive store");
    }

    /** invoice listed FAILED (its archive gone) undeploys: its version from the record, no flows removed, its record gone; common then undeploys. */
    @Test
    void aFailedPackageUndeploys() throws Exception {
        Node first = new Node("node-1");
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        Zipped invoice = Zipped.of(invoice("1.0.0", common.draft()));
        first.deploy(invoice);
        first.close();
        Files.delete(bucket.resolve(invoice.sha() + ".zip"));
        Node restarted = new Node("node-1b", "dev", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.listed("invoice").orElseThrow().state(), "fixture: invoice is listed FAILED");

        UndeployResult r = restarted.undeploy("invoice");

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of()), r, "a FAILED package has no flows to stop");
        assertEquals(List.of("common"), rowNames(), "its record is deleted");
        assertEquals(List.of("common"), restarted.packageNames(), "it is no longer listed");
        assertEquals(List.of(), children(restarted.packageDir("invoice")), "its directory is pruned");
        assertEquals(List.of(), restarted.doors.doors(), "no consumer was ever built for it");
        assertEquals(new UndeployResult("common", "1.2.0", List.of()), restarted.undeploy("common"), "its pin went with its record");
    }

    /**
     * invoice ran 1.0.0 then 1.0.1 (two directories, current and previous); both are pruned on undeploy. common 1.2.0 — neither
     * current nor previous, pinned by invoice and by billing — keeps its directory: billing's record still pins it, billing maps with it.
     */
    @Test
    void itsDirectoriesArePrunedButAPinnedOnesKept() throws Exception {
        Node node = new Node("node-1");
        Zipped c120 = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        Zipped v1 = Zipped.of(invoice("1.0.0", c120.draft()));
        node.deploy(v1);
        node.deploy(billing(c120.draft()));
        node.deploy(common("1.3.0", "UBL 1.3.0"));
        node.deploy(common("1.4.0", "UBL 1.4.0"));
        Zipped v2 = Zipped.of(invoice("1.0.1", c120.draft()));
        node.deploy(v2);
        assertEquals(List.of(v1.sha(), v2.sha()).stream().sorted().toList(), children(node.packageDir("invoice")), "fixture: invoice's current and previous directories");
        assertTrue(Files.isDirectory(node.dir("common", c120.sha())), "fixture: common 1.2.0 is on disk, pinned");

        assertEquals(new UndeployResult("invoice", "1.0.1", List.of(FLOW)), node.undeploy("invoice"));

        assertEquals(List.of(), children(node.packageDir("invoice")), "both of invoice's directories are pruned");
        assertTrue(Files.isRegularFile(node.dir("common", c120.sha()).resolve("xslt/to-ubl.xsl")), "common 1.2.0, pinned by billing's record, is kept");
        assertEquals("UBL 1.2.0", node.reply(BILLING), "billing maps with it");
        assertTrue(shelf.holds(v1.sha()) && shelf.holds(v2.sha()), "both of invoice's archives stay in the archive store");
    }

    /**
     * invoice's directory cannot be deleted (a directory chain below it longer than PATH_MAX, root-proof): the undeploy stands —
     * its result, record deleted, not listed, flow stopped — and the prune's failure is a WARNING naming the package.
     */
    @Test
    void aPruneThatFailsWarnsAndTheUndeployStands() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        Zipped invoice = Zipped.of(invoice("1.0.0", common));
        node.deploy(invoice);
        Doors.Door door = node.doors.door(FLOW);
        Path work = Files.createDirectories(tmp.resolve("w"));
        Path chain = DeploymentsRedeployTest.deepChain(work);
        Path deep = node.dir("invoice", invoice.sha()).resolve("deep");
        Files.move(chain, deep);
        try {
            UndeployResult r = node.undeploy("invoice");

            assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), r, "the undeploy stands");
            assertEquals(List.of("common"), rowNames());
            assertEquals(List.of("common"), node.packageNames());
            assertTrue(door.stopped(), door.history().toString());
            assertTrue(Files.exists(deep, LinkOption.NOFOLLOW_LINKS), "fixture: the prune could not delete it");
            assertFalse(warningsWith("invoice").isEmpty(), "the prune's failure is a WARNING naming the package: "
                    + logged.stream().map(DeploymentsProducersTest::texts).toList());
        } finally {
            if (Files.exists(deep, LinkOption.NOFOLLOW_LINKS)) DeploymentsRedeployTest.dismantle(deep, work);
            if (Files.exists(chain, LinkOption.NOFOLLOW_LINKS)) DeploymentsRedeployTest.dismantle(chain, work);
        }
    }

    /** invoice's consumer stop fails: a WARNING carrying the cause, and the undeploy stands (the stop counts as settled). */
    @Test
    void aStopThatFailsWarnsAndTheUndeployStands() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        node.deploy(invoice("1.0.0", common));
        node.doors.failStop(FLOW, "the socket would not close");

        UndeployResult r = node.undeploy("invoice");

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), r, "the undeploy stands");
        assertEquals(List.of("common"), rowNames());
        assertEquals(List.of("common"), node.packageNames());
        assertEquals(List.of(), node.flowIds());
        assertFalse(warningsWith("the socket would not close").isEmpty(), "the failed stop is a WARNING with its cause: "
                + logged.stream().map(DeploymentsProducersTest::texts).toList());
    }

    /** invoice listed FAILED on a node whose data directory never held it (a fresh one): it undeploys, and no WARNING names it. */
    @Test
    void aFailedPackageNeverUnpackedUndeploysWithoutAPruneWarning() throws Exception {
        Node first = new Node("node-1");
        Zipped common = Zipped.of(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        Zipped invoice = Zipped.of(invoice("1.0.0", common.draft()));
        first.deploy(invoice);
        first.close();
        Files.delete(bucket.resolve(invoice.sha() + ".zip"));
        Node fresh = new Node("node-2", "dev", tmp.resolve("node-2"));
        fresh.restore();
        assertEquals(DeployedPackage.State.FAILED, fresh.listed("invoice").orElseThrow().state(), "fixture: invoice is listed FAILED");
        assertFalse(Files.exists(fresh.packageDir("invoice")), "fixture: the fresh node has no directory of invoice");
        int before = warningsWith("invoice").size();

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of()), fresh.undeploy("invoice"));

        assertEquals(before, warningsWith("invoice").size(), "nothing to prune is no WARNING: "
                + warningsWith("invoice").stream().map(DeploymentsProducersTest::texts).toList());
        assertEquals(List.of("common"), fresh.packageNames());
        assertEquals(List.of("common"), rowNames(), "its record is deleted");
    }

    // --- P2-02c r2: an at-least-once flow whose items the work queue holds

    /**
     * shop runs audit, ledger and relay at-least-once and orders sync; one message of audit and one of relay are leased, their
     * sends held at the partner, ledger has none. The undeploy is 409, one line per flow with items (audit, relay), none for
     * ledger; nothing changes — no delete, the record, the listing, the flows, the directory and the items stay, no consumer
     * stops. Released, the items finish, and shop undeploys.
     */
    @Test
    void anAtLeastOnceFlowWithQueueItemsRefusesTheUndeploy() throws Exception {
        Node node = new Node("node-1");
        node.deploy(queued());
        CountDownLatch release = node.doors.partner().holdNext(2);
        try {
            Envelope a = node.accept("shop/audit");
            Envelope r = node.accept("shop/relay");
            node.doors.partner().awaitSent(2);
            assertEquals(Set.of(a.id(), r.id()), Set.copyOf(node.queue.rows().stream().map(WorkQueue.Item::messageId).toList()),
                    "fixture: one item of audit and one of relay, leased while their sends are held: " + node.queue.rows());
            List<DeploymentRecord> rows = records.rows();
            List<DeployedPackage> listed = node.deployments.packages();
            List<DeployedFlow> flows = node.deployments.flows();
            List<String> dirs = children(node.packageDir("shop"));

            DeployRefusedException refused = node.refused("shop", Kind.CONFLICT);

            assertEquals(List.of(itemsLine("audit"), itemsLine("relay")), refused.error().lines().toList(),
                    "one line per at-least-once flow with items, by flow; ledger holds none: " + refused.error());
            assertFalse(log.stream().anyMatch(e -> e.startsWith("delete ")), "no delete was called: " + log);
            assertEquals(rows, records.rows(), "the record stays");
            assertEquals(listed, node.deployments.packages(), "shop is listed as before");
            assertEquals(flows, node.deployments.flows(), "its flows too");
            for (String f : SHOP_FLOWS) assertFalse(node.doors.door(f).stopped(), f + "'s consumer is not stopped: " + node.doors.door(f).history());
            assertEquals(dirs, children(node.packageDir("shop")), "its directory stays");
            assertEquals(2, node.queue.size(), "the items stay: " + node.queue.rows());
        } finally {
            release.countDown();
        }
        until(() -> node.queue.size() == 0, "released, the items finish: " + node.queue.rows());

        assertEquals(new UndeployResult("shop", "1.0.0", SHOP_FLOWS), node.undeploy("shop"), "witness: its items done, shop undeploys");
        assertEquals(List.of(), rowNames());
    }

    /**
     * The work queue cannot answer: invoice (sync flows only, nothing to ask) still undeploys; shop (at-least-once flows) is the
     * node's failure — an IOException naming the cause — and nothing changes. The queue answering again, shop undeploys.
     */
    @Test
    void aQueueThatCannotAnswerFailsTheUndeployAndChangesNothing() throws Exception {
        Node node = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        node.deploy(common);
        node.deploy(invoice("1.0.0", common));
        node.deploy(queued());
        List<String> dirs = children(node.packageDir("shop"));
        node.queue.failHasItems(new IllegalStateException("the queue's database is unreachable"));

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), node.undeploy("invoice"), "a package with no at-least-once flow asks the queue nothing");
        List<DeploymentRecord> rows = records.rows();
        List<DeployedPackage> listed = node.deployments.packages();
        List<DeployedFlow> flows = node.deployments.flows();
        int mark = log.size();

        IOException failed = assertThrows(IOException.class, () -> node.undeploy("shop"), "a question the queue cannot answer is the node's failure");

        assertTrue(carries(failed, "the queue's database is unreachable"), "the failure names the cause: " + failed + " / " + rootOf(failed));
        assertFalse(node.queue.hasItemsCalls().isEmpty(), "fixture: the queue was asked: " + node.queue.hasItemsCalls());
        List<String> sinceMark = List.copyOf(log);
        assertEquals(List.of(), sinceMark.subList(mark, sinceMark.size()).stream().filter(e -> e.startsWith("delete ")).toList(), "no delete was called: " + log);
        assertEquals(rows, records.rows(), "shop's record stays");
        assertEquals(listed, node.deployments.packages(), "shop is listed as before");
        assertEquals(flows, node.deployments.flows(), "its flows too");
        for (String f : SHOP_FLOWS) assertFalse(node.doors.door(f).stopped(), f + "'s consumer is not stopped: " + node.doors.door(f).history());
        assertEquals(dirs, children(node.packageDir("shop")), "its directory stays");
        assertInstanceOf(Ack.Accepted.class, within(node.doors.door("shop/audit").deliver(rt.ingress("shop/audit", Headers.empty(), Buffer.buffer("<order/>"))),
                "audit's answer"), "audit accepts on");

        node.queue.failHasItems(null);
        until(() -> node.queue.size() == 0, "audit's message finishes: " + node.queue.rows());
        assertEquals(new UndeployResult("shop", "1.0.0", SHOP_FLOWS), node.undeploy("shop"), "witness: the queue answering, shop undeploys");
    }

    /**
     * FIX-172b O3: the work queue's question refused by the node's Postgres pool — its wait elapsed (exactly VertxException), its
     * queue full, a transaction's acquire refused — is {@link Kind#BUSY}, retryable, naming the flow and the cause, and changes
     * nothing: no delete, the record, the listing and the flows as they were. A VertxException subclass is no pool refusal: the
     * node's IOException, as before. The queue answering again, shop undeploys.
     */
    @Test
    void aQueueQuestionThePoolRefusedIsBusyAndChangesNothing() throws Exception {
        Node node = new Node("node-1");
        node.deploy(queued());
        List<String> dirs = children(node.packageDir("shop"));
        List<DeploymentRecord> rows = records.rows();
        List<DeployedPackage> listed = node.deployments.packages();
        List<DeployedFlow> flows = node.deployments.flows();
        Map<String, Throwable> refusals = new LinkedHashMap<>();
        refusals.put("the wait elapsed", new VertxException("Timeout waiting for connection", true));
        refusals.put("the queue full", new ConnectionPoolTooBusyException("Connection pool reached max wait queue size of 1"));
        refusals.put("the acquire refused", new PoolRefusedException(new VertxException("Timeout", true)));
        int mark = log.size();
        for (Map.Entry<String, Throwable> r : refusals.entrySet()) {
            int asked = node.queue.hasItemsCalls().size();
            node.queue.failHasItems(r.getValue());
            DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> node.undeploy("shop"), r.getKey() + ": the undeploy is refused");
            assertEquals(Kind.BUSY, refused.kind(), r.getKey() + ": " + refused.getMessage());
            assertTrue(node.queue.hasItemsCalls().size() > asked, r.getKey() + ": witness, the queue was asked: " + node.queue.hasItemsCalls());
            assertTrue(refused.error().contains(r.getValue().getMessage()) && refused.error().contains("shop/"),
                    r.getKey() + ": the refusal names the flow and the cause: " + refused.error());
            assertEquals(List.of(), refused.problems(), r.getKey() + ": no problem lines");
        }
        VertxException closed = new VertxException("connection closed by the server") { };
        node.queue.failHasItems(closed);
        IOException failed = assertThrows(IOException.class, () -> node.undeploy("shop"), "a VertxException subclass is no pool refusal: the node's failure, as before");
        assertSame(closed, failed.getCause(), "the IOException carries the queue's failure: " + failed);

        List<String> sinceMark = List.copyOf(log);
        assertEquals(List.of(), sinceMark.subList(mark, sinceMark.size()).stream().filter(e -> e.startsWith("delete ")).toList(), "no delete was called: " + log);
        assertEquals(rows, records.rows(), "shop's record stays");
        assertEquals(listed, node.deployments.packages(), "shop is listed as before");
        assertEquals(flows, node.deployments.flows(), "its flows too");
        for (String f : SHOP_FLOWS) assertFalse(node.doors.door(f).stopped(), f + "'s consumer is not stopped: " + node.doors.door(f).history());
        assertEquals(dirs, children(node.packageDir("shop")), "its directory stays");

        node.queue.failHasItems(null);
        assertEquals(new UndeployResult("shop", "1.0.0", SHOP_FLOWS), node.undeploy("shop"), "witness: the queue answering, shop undeploys");
    }

    /**
     * Right after each one's check found nothing, messages arrive: audit's A runs held at the partner with B queued behind it
     * (audit runs one at most), relay's C runs held. The undeploy answers without waiting for them. Once audit's worker drained
     * (A finished) audit is asked again: B is still there, and a WARNING names shop/audit; relay's second check finds nothing and
     * no WARNING names it.
     */
    @Test
    void itemsArrivingAfterTheCheckAreRecheckedOnceTheWorkerDrainedAndWarned() throws Exception {
        Node node = new Node("node-1");
        node.deploy(queued());
        List<CountDownLatch> releases = new CopyOnWriteArrayList<>();
        Map<String, Envelope> arrived = new ConcurrentHashMap<>();
        node.queue.afterFirstHasItems("shop/audit", () -> {
            try {
                int sent = node.doors.partner().sent().size();
                releases.add(node.doors.partner().holdNext(1));
                arrived.put("A", node.accept("shop/audit"));
                node.doors.partner().awaitSent(sent + 1);
                arrived.put("B", node.accept("shop/audit"));
            } catch (Exception e) {
                throw new AssertionError("fixture: messages arriving after audit's check", e);
            }
        });
        node.queue.afterFirstHasItems("shop/relay", () -> {
            try {
                int sent = node.doors.partner().sent().size();
                releases.add(node.doors.partner().holdNext(1));
                arrived.put("C", node.accept("shop/relay"));
                node.doors.partner().awaitSent(sent + 1);
            } catch (Exception e) {
                throw new AssertionError("fixture: a message arriving after relay's check", e);
            }
        });
        try {
            UndeployResult r = node.undeploy("shop");

            assertEquals(List.of("shop/audit rows=0"), node.hasItemsCalls("shop/audit"), "audit is asked once before the stop, and not again before its worker drained: "
                    + node.queue.hasItemsCalls());
            assertEquals(List.of("shop/relay rows=0"), node.hasItemsCalls("shop/relay"), "relay too: " + node.queue.hasItemsCalls());
            assertEquals(new UndeployResult("shop", "1.0.0", SHOP_FLOWS), r, "the undeploy answers without waiting for the late items");
            assertEquals(Set.of("A", "B", "C"), arrived.keySet(), "fixture: A, B and C arrived after the checks");
            assertEquals(Set.of(arrived.get("A").id(), arrived.get("C").id()), Set.copyOf(node.queue.claimedIds()), "fixture: A and C run, B waits behind A");
            assertEquals(List.of(), warningsWith("shop/audit"), "no WARNING while A still runs: the recheck comes after the drain");
        } finally {
            releases.forEach(CountDownLatch::countDown);
        }

        until(() -> node.hasItemsCalls("shop/audit").size() == 2 && node.hasItemsCalls("shop/relay").size() == 2,
                "each at-least-once flow with a late item is asked again once its worker drained: " + node.queue.hasItemsCalls());
        assertEquals(List.of("shop/audit rows=0", "shop/audit rows=1"), node.hasItemsCalls("shop/audit"), "audit's second check sees B alone: A finished, the worker drained");
        assertEquals(List.of("shop/relay rows=0", "shop/relay rows=0"), node.hasItemsCalls("shop/relay"));
        until(() -> !warningsWith("shop/audit").isEmpty(), "a WARNING names the flow whose items are left: "
                + logged.stream().map(x -> x.getLevel() + " " + DeploymentsProducersTest.texts(x)).toList());
        assertEquals(List.of(arrived.get("B").id()), node.queue.rows().stream().map(WorkQueue.Item::messageId).toList(), "B is the item left");
        assertEquals(List.of(), warningsWith("shop/relay"), "relay left nothing: no WARNING names it");
    }
}
