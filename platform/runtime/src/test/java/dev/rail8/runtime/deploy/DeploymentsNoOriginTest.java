package dev.rail8.runtime.deploy;

import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.schemas.Package;
import dev.rail8.testkit.FakeRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deploy with no origin, {@code Deployments.deploy(name, folder)}: the deploy {@code rail8 test} makes. It is refused in a deployed
 * environment before anything is read or written; in dev it runs the package from its own copy of the folder, stores no archive and
 * writes no record, so a restore over the same records installs nothing. The flows run on {@link Doors}, on a fake clock.
 */
class DeploymentsNoOriginTest {

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryDeploymentStore records = new InMemoryDeploymentStore();

    @AfterEach
    void releaseWhatACaseHeld() {
        doors.releaseAll();
    }

    Deployments node(String env) {
        Map<String, String> vars = new HashMap<>();
        vars.put("RAIL8_ENV", env);
        vars.put("RAIL8_NODE_ID", "node-1");
        vars.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        vars.put("RAIL8_STOP_DEADLINE", "PT37S");
        vars.put("RAIL8_BLOB_DURABLE_MAX", "4096");
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        return new Deployments(NodeConfig.from(vars), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(),
                new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L, records);
    }

    static List<String> under(Path dir) throws Exception {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    @Test
    void aDeployWithNoOriginIsRefusedInADeployedEnvironmentBeforeAnythingIsWritten() throws Exception {
        Deployments node = node("prod");
        Path folder = DeploymentsTest.shop("shop").folderIn(tmp.resolve("work"));
        List<String> before = under(folder);

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> node.deploy("shop", folder)));

        assertEquals(Kind.FORBIDDEN, refused.kind(), refused.getMessage());
        assertEquals(List.of(), node.packages(), "nothing listed");
        assertEquals(List.of(), records.list().await(), "no record");
        assertEquals(List.of(), under(tmp.resolve("data")), "nothing written into the data directory");
        assertEquals(before, under(folder), "the folder as it was");
    }

    @Test
    void aDeployWithNoOriginInDevRunsItsOwnCopyAndWritesNoRecord() throws Exception {
        Deployments node = node("dev");
        Path folder = DeploymentsTest.shop("shop").folderIn(tmp.resolve("work"));
        String content = Package.content(folder);
        List<String> files = under(folder);

        DeployResult deployed = OnVirtualThread.call(() -> node.deploy("shop", folder));

        assertEquals(List.of("shop/audit", "shop/orders", "shop/ping"), deployed.added());
        assertEquals(content, deployed.pkg().content());
        String hex = content.substring("sha256:".length());
        assertTrue(node.flows().stream().allMatch(f -> f.state() == DeployedFlow.State.RUNNING), node.flows().toString());
        assertEquals(List.of(), records.list().await(), "no record");
        assertEquals(List.of(), under(tmp.resolve("archives")), "no archive stored");
        assertEquals(files, under(tmp.resolve("data").resolve("packages").resolve("shop").resolve(hex)), "the node's own copy");
        assertEquals(List.of(), under(node.incomingDir()), "nothing left in .incoming");
        assertEquals(files, under(folder), "the folder is read, never moved");

        Files.writeString(folder.resolve("flows/orders.yaml"), "changed after the deploy", StandardCharsets.UTF_8);
        assertEquals(content, Package.content(tmp.resolve("data").resolve("packages").resolve("shop").resolve(hex)), "an edit of the folder changes nothing deployed");

        Deployments restarted = node("dev");
        OnVirtualThread.call(() -> {
            restarted.restore();
            return null;
        });
        assertEquals(List.of(), restarted.packages(), "a restore installs nothing: there is no record");
    }

    @Test
    void aSymbolicLinkInTheFolderIsRefusedAndNothingStays() throws Exception {
        Deployments node = node("dev");
        Path folder = DeploymentsTest.shop("shop").folderIn(tmp.resolve("work"));
        Files.createSymbolicLink(folder.resolve("link.xsd"), folder.resolve("package.yaml"));

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> node.deploy("shop", folder)));

        assertEquals(Kind.BAD_REQUEST, refused.kind(), refused.getMessage());
        assertTrue(refused.error().contains("'link.xsd' is neither a regular file nor a directory"), refused.error());
        assertFalse(refused.error().contains(tmp.toString()), "no absolute path: " + refused.error());
        assertEquals(List.of(), node.packages());
        assertEquals(List.of(), under(tmp.resolve("data")), "nothing stays in the data directory");
    }

    @Test
    void aBadNameIsRefusedBeforeTheFolderIsRead() throws Exception {
        Deployments node = node("dev");
        DeployRefusedException refused = assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> node.deploy("Shop", tmp.resolve("absent"))));
        assertEquals(Kind.BAD_REQUEST, refused.kind(), refused.getMessage());
        assertEquals(Optional.empty(), node.packages().stream().findAny());
    }
}
