package dev.rail8.runtime.deploy;

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
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
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
import java.util.stream.Stream;

import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.common;
import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.invoice;
import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.library;
import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.user;
import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.writeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-110 (D-089): off-node validation — {@code Deployments.validate(name, dir, held)} with the package's dependency closure handed
 * over as package folders. For that call only each held folder counts as a version the node has (its name and version from its
 * package.yaml, its content {@code Package.content} of the folder, a {@code <dep>:<path>} read from it) in the resolution and the
 * build; nothing of it is deployed, stored, recorded, fetched, started or listed, in any environment (no FORBIDDEN), and the node's
 * own checks — missing version, content, cycle, files — run as for a deployed version. Afterwards nothing of it is visible.
 *
 * <p>The shape assumed (FIX-110 test-author, the packet's suggestion taken as is):
 * {@code public List<String> validate(String name, Path dir, List<Path> held) throws IOException} on {@link Deployments}, blocking,
 * on a virtual thread, problems as {@code validate(name, dir)} returns them. Fixtures: the packages of
 * {@link DeploymentsDependenciesTest} written to folders; a held folder's directory name is never the package's name, so the name
 * can only come from its package.yaml. Each node has its own data directory, {@link Doors} (every consumer built is an event),
 * and {@link Ledger} (every record save); no node has a git base, so none can fetch.
 */
class DeploymentsHeldValidateTest {

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final List<Node> nodes = new ArrayList<>();

    @AfterEach
    void closeEveryNode() throws Exception {
        for (Node n : nodes) n.close();
    }

    final class Node {
        final Path data;
        final Doors doors = new Doors();
        final Ledger records = new Ledger(trace -> {});
        final Deployments deployments;

        Node(String id, String env) {
            this.data = tmp.resolve(id);
            Map<String, String> e = new HashMap<>();
            e.put("RAIL8_ENV", env);
            e.put("RAIL8_NODE_ID", id);
            e.put("RAIL8_DATA_DIR", data.toString());
            e.put("RAIL8_STOP_DEADLINE", "PT5S");
            e.put("RAIL8_BLOB_DURABLE_MAX", "4096");
            e.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            SaxonXml xml = new SaxonXml();
            List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), steps, xml.languages(),
                    new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L, records);
            nodes.add(this);
        }

        /** The off-node validate: {@code dir} as {@code name}, {@code held} the closure's folders. */
        List<String> validate(String name, Path dir, List<Path> held) throws Exception {
            return OnVirtualThread.call(() -> deployments.validate(name, dir, held));
        }

        /** The node's own validate, against what it has deployed. */
        List<String> validate(String name, Path dir) throws Exception {
            return OnVirtualThread.call(() -> deployments.validate(name, dir));
        }

        DeployResult deploy(PackageDraft p) throws Exception {
            Path folder = p.folderIn(tmp.resolve("folders"));
            return OnVirtualThread.call(() -> deployments.deploy(p.name(), folder));
        }

        void close() throws Exception {
            doors.releaseAll();
            records.releaseAll();
            OnVirtualThread.call(() -> {
                deployments.close();
                return null;
            });
        }
    }

    /** {@code p}'s files written to a fresh folder {@code <tmp>/<folder>}. */
    Path folder(String folder, PackageDraft p) throws IOException {
        Path dir = tmp.resolve(folder);
        writeTo(dir, p);
        return dir;
    }

    void assertNoAbsolutePath(List<String> lines) throws IOException {
        for (String absolute : List.of(tmp.toString(), tmp.toRealPath().toString())) {
            for (String l : lines) assertFalse(l.contains(absolute), "no absolute path (" + absolute + ") in '" + l + "'");
        }
    }

    static final String NOT_DEPLOYED = "dependency common@1.2.0 is not deployed on this node (no package common)";

    // --- cases

    /**
     * A held common 1.2.0 (folder {@code shared-xsl}) satisfies invoice's lock on an empty node: no problem, where the node's own
     * validate says 'not deployed'. Held common 1.3.0 alone is a version the node has, named as such.
     */
    @Test
    void aHeldFolderSatisfiesResolution() throws Exception {
        Node node = new Node("node-1", "dev");
        PackageDraft c120 = common("1.2.0", "UBL 1.2.0");
        Path checkout = folder("checkout", invoice("1.0.0", c120));
        Path held120 = folder("shared-xsl", c120);
        Path held130 = folder("newer-xsl", common("1.3.0", "UBL 1.3.0"));

        assertTrue(node.validate("invoice", checkout).contains(NOT_DEPLOYED), "witness: the node itself has no common");

        assertEquals(List.of(), node.validate("invoice", checkout, List.of(held120)), "common 1.2.0 held");
        List<String> other = node.validate("invoice", checkout, List.of(held130));
        assertTrue(other.contains("dependency common@1.2.0 is not deployed on this node (it has common 1.3.0)"), other.toString());
        assertNoAbsolutePath(other);
    }

    /** A held common 1.2.0 whose bytes are not those invoice's lock pins: the content line naming both digests, no absolute path. */
    @Test
    void aHeldFolderWithOtherContentIsTheContentProblem() throws Exception {
        Node node = new Node("node-1", "dev");
        PackageDraft held = common("1.2.0", "UBL 1.2.0");
        PackageDraft locked = common("1.2.0", "UBL 1.2.0 as built elsewhere");
        Path checkout = folder("checkout", invoice("1.0.0", locked));

        List<String> problems = node.validate("invoice", checkout, List.of(folder("shared-xsl", held)));

        assertTrue(problems.contains("dependency common@1.2.0 has content " + held.content() + " on this node, package.lock pins " + locked.content()),
                problems.toString());
        assertNoAbsolutePath(problems);
    }

    /**
     * A held common that runs a flow of its own (from a {@code rec} in endpoint): invoice validates with it, and nothing was
     * deployed, stored, recorded, fetched, built as a consumer, started or listed. Witness: the same common deployed on another
     * node builds and starts its consumer through the same component.
     */
    @Test
    void heldFoldersStartNothing() throws Exception {
        PackageDraft running = common("1.2.0", "UBL 1.2.0")
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .endpoint("common-in", "in", new JsonObject().put("path", "/common"))
                .flow("echo", "1.0.0", "sync", "common-in", 4096, DeploymentsFoldersTest.mapWith("xslt/to-ubl.xsl"));
        Node witness = new Node("witness", "dev");
        witness.deploy(running);
        assertTrue(witness.doors.events().containsAll(List.of("consumer common/echo", "start common/echo")),
                "witness: deployed, its consumer is built and started: " + witness.doors.events());

        Node node = new Node("node-1", "dev");
        Path checkout = folder("checkout", invoice("1.0.0", running));

        assertEquals(List.of(), node.validate("invoice", checkout, List.of(folder("shared-xsl", running))));

        assertEquals(List.of(), node.doors.events(), "no consumer built, started or stopped");
        assertEquals(List.of(), node.doors.doors(), "no consumer built");
        assertEquals(List.of(), node.records.saves(), "no record written");
        assertEquals(List.of(), node.deployments.packages(), "nothing listed");
        assertEquals(List.of(), node.deployments.flows(), "no flow listed");
        assertFalse(Files.exists(node.data.resolve("packages").resolve("common")), "nothing unpacked under the data directory");
    }

    /** prod validates with held folders: no problem. */
    @Test
    void aDeployedEnvValidatesWithHeldFolders() throws Exception {
        Node prod = new Node("node-1", "prod");
        PackageDraft c120 = common("1.2.0", "UBL 1.2.0");
        Path checkout = folder("checkout", invoice("1.0.0", c120));

        assertEquals(List.of(), prod.validate("invoice", checkout, List.of(folder("shared-xsl", c120))));
    }

    /**
     * After a held validate — one that passed, and one that found the held version's content wrong — the node's own validate, and
     * a held validate holding nothing, say 'not deployed' again; the listing stays empty.
     */
    @Test
    void nothingHeldStaysVisibleAfterTheCall() throws Exception {
        Node node = new Node("node-1", "dev");
        PackageDraft c120 = common("1.2.0", "UBL 1.2.0");
        Path checkout = folder("checkout", invoice("1.0.0", c120));
        Path drifted = folder("drifted-xsl", common("1.2.0", "UBL 1.2.0 drifted"));

        assertEquals(List.of(), node.validate("invoice", checkout, List.of(folder("shared-xsl", c120))));

        List<String> after = node.validate("invoice", checkout);
        assertTrue(after.contains(NOT_DEPLOYED), "the passing call left nothing: " + after);
        List<String> heldNothing = node.validate("invoice", checkout, List.of());
        assertTrue(heldNothing.contains(NOT_DEPLOYED), "the passing call left nothing: " + heldNothing);

        List<String> drift = node.validate("invoice", checkout, List.of(drifted));
        assertTrue(drift.stream().anyMatch(l -> l.startsWith("dependency common@1.2.0 has content ")), "fixture: the drifted folder is held: " + drift);
        List<String> afterDrift = node.validate("invoice", checkout);
        assertTrue(afterDrift.contains(NOT_DEPLOYED), "the failing call left nothing: " + afterDrift);
        assertEquals(List.of(), node.deployments.packages());
    }

    /**
     * invoice maps with {@code common:xslt/to-ubl.xsl}, read from the held folder with its include next to it: a held common
     * whose included lib.xsl does not compile gives exactly the problems the same common deployed gives (paths as
     * {@code common:<path>}, none absolute); a stylesheet the held folder lacks is named; the intact common passes.
     */
    @Test
    void aHeldDependencysFileIsReadByTheFlow() throws Exception {
        PackageDraft broken = common("1.2.0", "<xsl:value-of select=\"$undeclaredInLib\"/>");
        Path checkout = folder("checkout", invoice("1.0.0", broken));
        Node deployed = new Node("deployed", "dev");
        deployed.deploy(broken);
        List<String> asDeployed = deployed.validate("invoice", checkout);
        assertTrue(asDeployed.stream().anyMatch(l -> l.contains("'to-ubl'")), "fixture: lib.xsl does not compile: " + asDeployed);

        Node node = new Node("node-1", "dev");
        List<String> held = node.validate("invoice", checkout, List.of(folder("broken-xsl", broken)));

        assertEquals(asDeployed, held, "the held folder's files are built as a deployed version's");
        assertNoAbsolutePath(held);

        PackageDraft c120 = common("1.2.0", "UBL 1.2.0");
        Path heldIntact = folder("shared-xsl", c120);
        List<String> missing = node.validate("invoice", folder("checkout-missing", user("invoice", "1.0.0", "common:xslt/missing.xsl", c120)), List.of(heldIntact));
        assertTrue(missing.stream().anyMatch(l -> l.contains("'to-ubl'") && l.endsWith("stylesheet 'common:xslt/missing.xsl' does not exist in common@1.2.0")),
                missing.toString());
        assertNoAbsolutePath(missing);
        assertEquals(List.of(), node.validate("invoice", folder("checkout-intact", invoice("1.0.0", c120)), List.of(heldIntact)), "witness: intact common");
    }

    /** a@2 -> b@1 held, whose own lock pins a@1: the cycle is named through the held folder's lock. */
    @Test
    void aCycleThroughAHeldFolderIsNamed() throws Exception {
        Node node = new Node("node-1", "dev");
        PackageDraft a1 = library("a", "1.0.0", "a1");
        PackageDraft b1 = library("b", "1.0.0", "b1", a1);
        Path checkout = folder("checkout", library("a", "2.0.0", "a2", b1));

        List<String> problems = node.validate("a", checkout, List.of(folder("held-b", b1)));

        assertTrue(problems.contains("dependency cycle: a -> b -> a"), problems.toString());
    }

    /** top -> mid -> base: held mid's own lock is followed — with base held too no problem; with mid alone base is missing. */
    @Test
    void aHeldFoldersOwnLockIsFollowed() throws Exception {
        Node node = new Node("node-1", "dev");
        PackageDraft base = library("base", "1.0.0", "base");
        PackageDraft mid = library("mid", "1.0.0", "mid", base);
        Path checkout = folder("checkout", user("top", "1.0.0", "mid:xslt/to-ubl.xsl", mid));
        Path heldMid = folder("held-mid", mid);

        assertEquals(List.of(), node.validate("top", checkout, List.of(heldMid, folder("held-base", base))));
        List<String> midAlone = node.validate("top", checkout, List.of(heldMid));
        assertTrue(midAlone.contains("dependency base@1.0.0 is not deployed on this node (no package base)"), midAlone.toString());
    }

    /**
     * Two held folders of one name and version are two candidates, in either order: the one whose content invoice's lock pins
     * resolves, the other is passed over — no problem. Witness that they differ: the drifted folder alone is the content line.
     */
    @Test
    void twoHeldFoldersOfOneVersionAreTwoCandidates() throws Exception {
        Node node = new Node("node-1", "dev");
        PackageDraft intact = common("1.2.0", "UBL 1.2.0");
        PackageDraft drift = common("1.2.0", "UBL 1.2.0 drifted");
        Path checkout = folder("checkout", invoice("1.0.0", intact));
        Path heldIntact = folder("shared-xsl", intact);
        Path heldDrifted = folder("drifted-xsl", drift);

        List<String> driftedAlone = node.validate("invoice", checkout, List.of(heldDrifted));
        assertTrue(driftedAlone.contains("dependency common@1.2.0 has content " + drift.content() + " on this node, package.lock pins "
                + intact.content()), "witness: " + driftedAlone);

        assertEquals(List.of(), node.validate("invoice", checkout, List.of(heldIntact, heldDrifted)), "intact first");
        assertEquals(List.of(), node.validate("invoice", checkout, List.of(heldDrifted, heldIntact)), "drifted first");
    }
}
