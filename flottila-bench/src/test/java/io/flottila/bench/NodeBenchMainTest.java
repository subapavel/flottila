package io.flottila.bench;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The node bench's command line (P1-10a): BenchMain's options plus --scenario and --recording. */
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
}
