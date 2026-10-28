package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The node bench's command line (P1-10a): BenchMain's options plus --scenario and --recording; --max-in-flight (FIX-67). */
class NodeBenchMainTest {

    @Test
    void optionsAreParsedAndAnUnknownScenarioIsRefused() throws Exception {
        NodeBenchMain.Options o = NodeBenchMain.options(new String[] {"--scenario", "worker", "--recording", "metadata-only",
                "--messages", "10", "--warmup", "2", "--concurrency", "8", "--name", "w", "--out", "x/w.json", "--sync-commit", "on"});
        assertEquals(NodeBenchMain.Scenario.WORKER, o.scenario());
        assertEquals(NodeBenchMain.Recording.METADATA_ONLY, o.recording());
        assertEquals(10, o.messages());
        assertEquals(2, o.warmup());
        assertEquals(8, o.concurrency());
        assertEquals("w", o.name());
        assertEquals(Path.of("x/w.json"), o.out());
        assertTrue(o.syncCommit());

        // every scenario and recording by its CLI name
        Map<String, NodeBenchMain.Scenario> scenarios = Map.of("sync", NodeBenchMain.Scenario.SYNC, "ack", NodeBenchMain.Scenario.ACK,
                "worker", NodeBenchMain.Scenario.WORKER);
        scenarios.forEach((cli, scenario) -> assertEquals(scenario, NodeBenchMain.options(new String[] {"--scenario", cli}).scenario(), cli));
        assertEquals(NodeBenchMain.Recording.KEEP, NodeBenchMain.options(new String[] {"--scenario", "sync", "--recording", "keep"}).recording());

        // defaults: the flow's default recording (keep) and a durable commit; off only when asked for
        NodeBenchMain.Options defaults = NodeBenchMain.options(new String[] {"--scenario", "ack"});
        assertEquals(NodeBenchMain.Recording.KEEP, defaults.recording(), "recording defaults to keep");
        assertTrue(defaults.syncCommit(), "synchronous_commit defaults to on");
        assertFalse(NodeBenchMain.options(new String[] {"--scenario", "ack", "--sync-commit", "off"}).syncCommit());

        IllegalArgumentException scenario = assertThrows(IllegalArgumentException.class,
                () -> NodeBenchMain.options(new String[] {"--scenario", "batch"}));
        assertTrue(scenario.getMessage().contains("batch"), "names the refused value: " + scenario.getMessage());
        for (String known : new String[] {"sync", "ack", "worker"})
            assertTrue(scenario.getMessage().contains(known), "names the known scenarios: " + scenario.getMessage());

        IllegalArgumentException recording = assertThrows(IllegalArgumentException.class,
                () -> NodeBenchMain.options(new String[] {"--scenario", "sync", "--recording", "all"}));
        assertTrue(recording.getMessage().contains("all"), "names the refused value: " + recording.getMessage());

        // the run refuses an unknown scenario before it touches a database (nothing listens on port 1)
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> NodeBenchMain.run(new String[] {
                "--host", "127.0.0.1", "--port", "1", "--scenario", "batch", "--messages", "1", "--warmup", "0"}));
        assertTrue(refused.getMessage().contains("batch"), refused.getMessage());

        // --step passthrough|xslt, default passthrough
        assertEquals(NodeBenchMain.Step.XSLT, NodeBenchMain.options(new String[] {"--scenario", "worker", "--step", "xslt"}).step());
        assertEquals(NodeBenchMain.Step.PASSTHROUGH, NodeBenchMain.options(new String[] {"--scenario", "worker", "--step", "passthrough"}).step());
        assertEquals(NodeBenchMain.Step.PASSTHROUGH, defaults.step(), "the step defaults to passthrough");
        IllegalArgumentException step = assertThrows(IllegalArgumentException.class,
                () -> NodeBenchMain.options(new String[] {"--scenario", "worker", "--step", "xquery"}));
        assertTrue(step.getMessage().contains("xquery"), "names the refused value: " + step.getMessage());

        // --scenario is required
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> NodeBenchMain.options(new String[] {"--recording", "keep", "--messages", "10"}));
        assertTrue(missing.getMessage().contains("--scenario"), "names the missing option: " + missing.getMessage());

        // BenchMain's argument rules hold: a stray word is refused
        assertThrows(IllegalArgumentException.class, () -> NodeBenchMain.options(new String[] {"scenario", "sync"}));
    }

    @Test
    void maxInFlightDefaultsToSixtyFourTimesTheConcurrencyForSyncAndToTheConcurrencyOtherwise() throws IOException {
        NodeBenchMain.Options sync = NodeBenchMain.options(new String[] {"--scenario", "sync", "--concurrency", "8"});
        assertEquals(512, NodeBenchMain.inFlight(sync), "sync: 64 x 8");
        assertEquals(512, flowLimit(sync), "the bench flow's limit is the run's");
        assertEquals(4096, NodeBenchMain.inFlight(NodeBenchMain.options(new String[] {"--scenario", "sync"})), "64 x the default 64");
        NodeBenchMain.Options ack = NodeBenchMain.options(new String[] {"--scenario", "ack", "--concurrency", "8"});
        assertEquals(8, NodeBenchMain.inFlight(ack), "at-least-once: the concurrency");
        assertEquals(8, flowLimit(ack));
    }

    @Test
    void anExplicitMaxInFlightIsTheBenchFlowsLimit() throws IOException {
        NodeBenchMain.Options o = NodeBenchMain.options(new String[] {"--scenario", "sync", "--concurrency", "64", "--max-in-flight", "128"});
        assertEquals(128, NodeBenchMain.inFlight(o), "2c at c=64 (packet P1-10, Notes for d)");
        assertEquals(128, flowLimit(o), "the package PUT to the node carries it");
        assertEquals(1, NodeBenchMain.inFlight(NodeBenchMain.options(new String[] {"--scenario", "sync", "--max-in-flight", "1"})),
                "1 is the smallest limit taken");
    }

    @Test
    void aMaxInFlightThatIsNotAPositiveNumberIsRefusedNamingTheOptionAndTheValue() {
        for (String bad : new String[] {"0", "-5", "eight", "2.5"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> NodeBenchMain.options(new String[] {"--scenario", "sync", "--max-in-flight", bad}), bad);
            assertTrue(refused.getMessage() != null && refused.getMessage().contains("--max-in-flight"), "names the option: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(bad), "names the value '" + bad + "': " + refused.getMessage());
        }
    }

    /** The {@code limits.maxInFlight} of {@code flows/bench.yaml} in the package the run PUTs. */
    static int flowLimit(NodeBenchMain.Options o) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(NodeBenchMain.benchPackage(o, 1, 2)))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("flows/bench.yaml")) {
                    return new JsonObject(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getJsonObject("limits").getInteger("maxInFlight");
                }
            }
        }
        throw new AssertionError("no flows/bench.yaml in the bench package");
    }
}
