package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.api.StepFactory;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.deploy.DeploymentsDependenciesTest.Committed;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.steps.validate.XsdValidate;
import dev.rail8.steps.xslt.SaxonXml;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.common;
import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.invoice;
import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.user;
import static dev.rail8.runtime.deploy.DeploymentsTest.until;
import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Undeploying a package, in any environment. {@code Deployments.undeploy(name)} checks, in order, the name's pattern
 * (BAD_REQUEST), the node's deploy lock (CONFLICT, or STOPPING while closing), closing (STOPPING) and that the package is known
 * (NOT_FOUND "no package '<name>' is deployed"). A package other records pin is undeployed as any other: its dependents keep the
 * directories they read and run on. Then, under the lock: the record is deleted — the commit point; a delete that fails is the
 * node's failure (IOException) and changes nothing else — then every flow of the package stops and closes (a stop that fails is a
 * WARNING), the listing drops it, a drain runs what the work queue holds of each at-least-once flow, and its directories are
 * pruned, but for those a remaining record pins or a remaining flow or drain reads (a prune that fails is a WARNING). Its commits
 * stay in their repository: deploying one again is the rollback. A restart does not restore it.
 *
 * <p>Fixtures: nodes of one pool as in {@link DeploymentsDependenciesTest} — each with its own data directory, git cache and
 * {@link Doors}, over one {@link Ledger} and one {@link GitRepos} — and its packages ({@code common} with no flows, {@code invoice}
 * and {@code billing} mapping with {@code common:xslt/to-ubl.xsl}, so a reply is the version of common they read). One {@link #log}
 * receives the Ledger's {@code save}/{@code delete} and every node's Doors events, in the order made.
 */
class DeploymentsUndeployTest {

    static final String FLOW = "invoice/to-ubl";
    static final String BILLING = "billing/to-ubl";

    @TempDir Path tmp;
    GitRepos repos;
    final FakeRuntime rt = FakeRuntime.create();
    /** The Ledger's saves and deletes and every node's Doors events, in the order made. */
    final List<String> log = new CopyOnWriteArrayList<>();
    final Ledger records = new Ledger(log::add);
    final List<Node> nodes = new ArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger rail8 = Logger.getLogger("dev.rail8");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void wire() {
        repos = new GitRepos(tmp.resolve("repos"));
        capture.setLevel(Level.ALL);
        rail8.addHandler(capture);
    }

    @AfterEach
    void closeEveryNode() throws Exception {
        try {
            for (Node n : nodes) n.doors.releaseAll();
            records.releaseAll();
            for (Node n : nodes) n.close();
        } finally {
            rail8.removeHandler(capture);
        }
    }

    // --- fixture: nodes of one pool

    final class Node {
        final String id;
        final Path data;
        final Doors doors = new Doors();
        /** Its work queue: records every {@code hasItems} call with the rows of that flow at the call. */
        final CountingQueue queue = new CountingQueue(rt.clock());
        final Deployments deployments;
        private boolean closed;

        Node(String id) {
            this(id, "dev", tmp.resolve(id));
        }

        /**
         * A node in {@code env} whose data directory is {@code data}: another node's, once that one is closed, is a restart. In
         * {@code prod} its git base is one no host serves, so only its git cache answers.
         */
        Node(String id, String env, Path data) {
            this.id = id;
            this.data = data;
            Map<String, String> e = new HashMap<>();
            e.put("RAIL8_ENV", env);
            e.put("RAIL8_NODE_ID", id);
            e.put("RAIL8_DATA_DIR", data.toString());
            e.put("RAIL8_STOP_DEADLINE", "PT5S");
            e.put("RAIL8_BLOB_DURABLE_MAX", "4096");
            e.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            e.put("RAIL8_GIT_BASE", env.equals("prod") ? GitRepos.UNSERVED : repos.base().toString());
            if (env.equals("prod")) e.put("RAIL8_GIT_REF", "refs/heads/main");
            doors.trace(log::add);
            SaxonXml xml = new SaxonXml();
            List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), steps, xml.languages(),
                    new InMemoryMessageStore(), new InMemoryDlq(), queue, () -> 0L, records);
            nodes.add(this);
        }

        DeployResult deploy(Committed p) throws Exception {
            return OnVirtualThread.call(() -> deployments.deploy(p.name(), p.commit(), "test"));
        }

        /** Commits {@code p} to the repository of its name and deploys that commit. */
        DeployResult deploy(PackageDraft p) throws Exception {
            return deploy(committed(p));
        }

        OnVirtualThread.Call<DeployResult> deploying(PackageDraft p) {
            Committed c = committed(p);
            return OnVirtualThread.start(() -> deployments.deploy(c.name(), c.commit(), "test"));
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

        /** {@code packages/<name>/<hex>/} of this node's data directory. */
        Path dir(String name, String hex) {
            return packageDir(name).resolve(hex);
        }

        /** Deletes this node's git cache: a commit it reads next is fetched. */
        void forgetGit() throws IOException {
            DeploymentsDependenciesTest.deleteTree(data.resolve("git"));
        }
    }

    // --- fixture: packages and helpers

    /** Commits {@code draft} to the repository of its name. */
    Committed committed(PackageDraft draft) {
        return new Committed(draft, repos.commit(draft), draft.content());
    }

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
     * producer closed; deploying its commit again (the rollback) runs it again. common, which it pinned, runs on.
     */
    @Test
    void anUnpinnedPackageIsUndeployedAndItsFlowsStop() throws Exception {
        Node node = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common);
        Committed invoice = committed(invoice("1.0.0", common.draft()));
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

        assertEquals(List.of(FLOW), node.deploy(invoice).added(), "deployed again (the rollback), invoice is a first deploy");
        assertEquals("UBL 1.2.0", node.reply(FLOW), "and serves again");
    }

    /**
     * common, pinned by invoice and by billing, undeploys: its record goes, it is no longer listed, and the directory its
     * dependents read stays; both serve on with common 1.2.0. Then invoice and billing undeploy, and no record is left.
     */
    @Test
    void aDependencyUndeploysWhileItsDependentsRunOn() throws Exception {
        Node node = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common);
        node.deploy(invoice("1.0.0", common.draft()));
        node.deploy(billing(common.draft()));

        UndeployResult r = node.undeploy("common");

        assertEquals(new UndeployResult("common", "1.2.0", List.of()), r, "the package and its version; it had no flows");
        assertEquals(List.of("billing", "invoice"), rowNames(), "common's record is deleted, its dependents' stay");
        assertEquals(List.of("billing", "invoice"), node.packageNames(), "common is no longer listed");
        assertTrue(Files.isRegularFile(node.dir("common", common.hex()).resolve("xslt/to-ubl.xsl")), "the directory its dependents pin stays");
        assertFalse(node.doors.door(FLOW).stopped(), "invoice runs on");
        assertEquals("UBL 1.2.0", node.reply(FLOW), "and maps with common 1.2.0");
        assertEquals("UBL 1.2.0", node.reply(BILLING), "billing too");

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), node.undeploy("invoice"));
        assertEquals(new UndeployResult("billing", "1.0.0", List.of(BILLING)), node.undeploy("billing"));
        assertEquals(List.of(), records.rows(), "no record is left");
        assertEquals(List.of(), node.packageNames(), "nothing is listed");
    }

    /**
     * common undeployed while invoice pins it: a restart on the node's data directory restores invoice from its own commit and
     * common's directory as it is, and a node with an empty data directory reads common 1.2.0 from the commit invoice's record
     * pins; neither lists common, and invoice serves on both.
     */
    @Test
    void aDependentIsRestoredAfterItsDependencyIsUndeployed() throws Exception {
        Node first = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        first.deploy(invoice("1.0.0", common.draft()));
        first.undeploy("common");
        first.close();

        Node restarted = new Node("node-1b", "dev", first.data);
        restarted.restore();

        assertEquals(List.of("invoice"), restarted.packageNames(), "common is not restored");
        assertEquals(DeployedPackage.State.RUNNING, restarted.listed("invoice").orElseThrow().state());
        assertEquals("UBL 1.2.0", restarted.reply(FLOW), "invoice maps with common 1.2.0");

        Node fresh = new Node("node-2", "dev", tmp.resolve("node-2"));
        fresh.restore();

        assertEquals(List.of("invoice"), fresh.packageNames(), "common is not restored");
        assertEquals("UBL 1.2.0", fresh.reply(FLOW), "invoice maps with common 1.2.0, read from its pinned commit");
        assertTrue(Files.isDirectory(fresh.dir("common", common.hex())), "written under common, by its content");
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
     * A node in prod, restarted on the data directory of the dev node that deployed them, restores invoice and common from the
     * pool's records and its git cache; a bad name is 400 there as anywhere, and an undeploy of invoice deletes its record and stops
     * it. common stays.
     */
    @Test
    void aDeployedEnvironmentUndeploys() throws Exception {
        Node dev = new Node("node-1");
        PackageDraft common = common("1.2.0", "UBL 1.2.0");
        dev.deploy(common);
        dev.deploy(invoice("1.0.0", common));
        dev.close();
        Node prod = new Node("node-2", "prod", dev.data);
        prod.restore();
        assertEquals("UBL 1.2.0", prod.reply(FLOW), "fixture: prod restored invoice and it serves");
        prod.refused("Invoice", Kind.BAD_REQUEST);

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), prod.undeploy("invoice"));

        assertEquals(List.of("common"), rowNames(), "invoice's record is deleted");
        assertEquals(List.of("common"), prod.packageNames(), "common stays listed");
        assertTrue(prod.doors.door(FLOW).stopped(), "invoice's consumer is stopped: " + prod.doors.door(FLOW).history());
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
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(common);
        Committed invoice = committed(invoice("1.0.0", common.draft()));
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
        assertEquals(List.of(invoice.hex()), dirs, "fixture: one directory");

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of(FLOW)), node.undeploy("invoice"), "witness: the next undeploy, whose delete works, undeploys");
    }

    /**
     * invoice undeployed on node 1 (billing runs on), then the node restarts on its data directory: the restore starts billing and
     * common and not invoice, which is neither listed nor built; its commit, deployed again, runs it.
     */
    @Test
    void aRestoreAfterUndeployDoesNotStartIt() throws Exception {
        Node first = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        Committed invoice = committed(invoice("1.0.0", common.draft()));
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
        assertEquals(List.of(FLOW), restarted.deploy(invoice).added(), "witness: its commit, deployed again, is a first deploy");
    }

    /** invoice listed FAILED (its commit gone) undeploys: its version from the record, no flows removed, its record gone; common then undeploys. */
    @Test
    void aFailedPackageUndeploys() throws Exception {
        Node first = new Node("node-1");
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        Committed invoice = committed(invoice("1.0.0", common.draft()));
        first.deploy(invoice);
        first.close();
        repos.delete("invoice");
        first.forgetGit();
        Node restarted = new Node("node-1b", "dev", first.data);
        restarted.restore();
        assertEquals(DeployedPackage.State.FAILED, restarted.listed("invoice").orElseThrow().state(), "fixture: invoice is listed FAILED");

        UndeployResult r = restarted.undeploy("invoice");

        assertEquals(new UndeployResult("invoice", "1.0.0", List.of()), r, "a FAILED package has no flows to stop");
        assertEquals(List.of("common"), rowNames(), "its record is deleted");
        assertEquals(List.of("common"), restarted.packageNames(), "it is no longer listed");
        assertEquals(List.of(), children(restarted.packageDir("invoice")), "its directory is pruned");
        assertEquals(List.of(), restarted.doors.doors(), "no consumer was ever built for it");
        assertEquals(new UndeployResult("common", "1.2.0", List.of()), restarted.undeploy("common"));
    }

    /**
     * invoice ran 1.0.0 then 1.0.1 (two directories, current and previous); both are pruned on undeploy. common 1.2.0 — neither
     * current nor previous, pinned by invoice and by billing — keeps its directory: billing's record still pins it, billing maps with it.
     */
    @Test
    void itsDirectoriesArePrunedButAPinnedOnesKept() throws Exception {
        Node node = new Node("node-1");
        Committed c120 = committed(common("1.2.0", "UBL 1.2.0"));
        node.deploy(c120);
        Committed v1 = committed(invoice("1.0.0", c120.draft()));
        node.deploy(v1);
        node.deploy(billing(c120.draft()));
        node.deploy(common("1.3.0", "UBL 1.3.0"));
        node.deploy(common("1.4.0", "UBL 1.4.0"));
        Committed v2 = committed(invoice("1.0.1", c120.draft()));
        node.deploy(v2);
        assertEquals(List.of(v1.hex(), v2.hex()).stream().sorted().toList(), children(node.packageDir("invoice")), "fixture: invoice's current and previous directories");
        assertTrue(Files.isDirectory(node.dir("common", c120.hex())), "fixture: common 1.2.0 is on disk, pinned");

        assertEquals(new UndeployResult("invoice", "1.0.1", List.of(FLOW)), node.undeploy("invoice"));

        assertEquals(List.of(), children(node.packageDir("invoice")), "both of invoice's directories are pruned");
        assertTrue(Files.isRegularFile(node.dir("common", c120.hex()).resolve("xslt/to-ubl.xsl")), "common 1.2.0, pinned by billing's record, is kept");
        assertEquals("UBL 1.2.0", node.reply(BILLING), "billing maps with it");
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
        Committed invoice = committed(invoice("1.0.0", common));
        node.deploy(invoice);
        Doors.Door door = node.doors.door(FLOW);
        Path work = Files.createDirectories(tmp.resolve("w"));
        Path chain = DeploymentsRedeployTest.deepChain(work);
        Path deep = node.dir("invoice", invoice.hex()).resolve("deep");
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
        Committed common = committed(common("1.2.0", "UBL 1.2.0"));
        first.deploy(common);
        Committed invoice = committed(invoice("1.0.0", common.draft()));
        first.deploy(invoice);
        first.close();
        repos.delete("invoice");
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

    // --- an at-least-once flow whose items the work queue holds

    /**
     * shop runs audit, ledger and relay at-least-once, each one message at a time, and orders sync. Audit's A runs held at the
     * partner and B waits behind it when shop is undeployed: the undeploy asks the work queue nothing — it fails every question —
     * answers at once and stops every consumer, and shop's directory stays while audit's drain runs from it. Released, A finishes and
     * B runs on the drain. A tick then finds nothing of audit left and ends the drain: shop's directory is pruned.
     */
    @Test
    void itemsLeftAtTheUndeployRunOnTheDrain() throws Exception {
        Node node = new Node("node-1");
        node.deploy(queued());
        List<String> dirs = children(node.packageDir("shop"));
        CountDownLatch release = node.doors.partner().holdNext(1);
        Envelope b;
        try {
            node.accept("shop/audit");
            node.doors.partner().awaitSent(1);
            b = node.accept("shop/audit");
            assertEquals(2, node.queue.size(), "fixture: A runs held, B waits behind it: " + node.queue.rows());
            node.queue.failHasItems(new IllegalStateException("the queue's database is unreachable"));

            assertEquals(new UndeployResult("shop", "1.0.0", SHOP_FLOWS), node.undeploy("shop"), "the undeploy answers with A and B in the work queue");

            assertEquals(List.of(), rowNames(), "shop's record is deleted");
            for (String f : SHOP_FLOWS) assertTrue(node.doors.door(f).stopped(), f + "'s consumer is stopped: " + node.doors.door(f).history());
            assertEquals(dirs, children(node.packageDir("shop")), "shop's directory stays: audit's drain runs from it");
            node.queue.failHasItems(null);
        } finally {
            release.countDown();
        }
        until(() -> node.queue.size() == 0, "A finishes and B runs on audit's drain: " + node.queue.rows());
        assertTrue(node.doors.partner().sent().stream().anyMatch(e -> e.id().equals(b.id())), "B reached the partner");
        assertEquals(dirs, children(node.packageDir("shop")), "the directory stays until a tick ends the drain");

        OnVirtualThread.call(() -> {
            node.deployments.follow();
            return null;
        });

        assertEquals(List.of(), children(node.packageDir("shop")), "the drain ended: nothing reads shop's directory, it is pruned");
    }
}
