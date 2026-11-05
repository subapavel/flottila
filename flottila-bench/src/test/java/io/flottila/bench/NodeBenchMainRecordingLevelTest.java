package io.flottila.bench;

import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.schemas.Schemas;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-124: {@code --recording-level none|all} (default all, the flow as before) sets the bench flow's recording level; the
 * CORE-GATE cell runs with none. {@code --recording keep|metadata-only} stays the payload setting. The level is read where the
 * node reads it: the package the run PUTs, its {@code flows/bench.yaml} checked against the flow schema and parsed by
 * {@link FlowDefinition#from} as the node's deploy does — so a level the node does not know fails here, not on the node.
 */
class NodeBenchMainRecordingLevelTest {

    @Test
    void recordingLevelNoneDeploysAFlowThatRecordsNoStep() throws IOException {
        RecordingPolicy policy = deployed(new String[] {"--scenario", "ack", "--recording-level", "none"});

        assertEquals(RecordingPolicy.Level.OFF, policy.level(), "none reaches the flow as the level that records no step");
        assertEquals(RecordingPolicy.Payload.KEEP, policy.payload(), "the payload setting is --recording's, unchanged");
    }

    @Test
    void recordingLevelAllAndItsAbsenceDeployTheFlowAsBefore() throws IOException {
        assertEquals(RecordingPolicy.Level.ALL, deployed(new String[] {"--scenario", "ack"}).level(), "the default is all, as before");
        assertEquals(RecordingPolicy.Level.ALL, deployed(new String[] {"--scenario", "sync", "--recording-level", "all"}).level());
    }

    @Test
    void recordingLevelAndPayloadAreSetApart() throws IOException {
        RecordingPolicy policy = deployed(new String[] {"--scenario", "worker", "--recording-level", "none", "--recording", "metadata-only"});

        assertEquals(RecordingPolicy.Level.OFF, policy.level());
        assertEquals(RecordingPolicy.Payload.METADATA_ONLY, policy.payload());
        assertEquals(RecordingPolicy.Payload.METADATA_ONLY,
                deployed(new String[] {"--scenario", "worker", "--recording", "metadata-only"}).payload(), "--recording alone as before");
    }

    @Test
    void anOpenRunTakesRecordingLevelNone() throws IOException {
        // the gate cell: the open loop at ack with no step recorded
        assertEquals(RecordingPolicy.Level.OFF,
                deployed(new String[] {"--scenario", "ack", "--rate", "5000", "--recording-level", "none"}).level());
    }

    @Test
    void anUnknownRecordingLevelIsRefusedNamingTheValueAndTheKnownOnes() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodeBenchMain.options(new String[] {"--scenario", "ack", "--recording-level", "sometimes"}));

        String m = refused.getMessage();
        assertTrue(m != null && m.contains("sometimes"), "names the refused value: " + m);
        assertTrue(m.contains("none") && m.contains("all"), "names the known levels: " + m);
        assertTrue(m.contains("--recording-level"), "names the option: " + m);
    }

    /** The recording policy of the bench flow the run deploys for {@code args}, read as the node reads it. */
    static RecordingPolicy deployed(String[] args) throws IOException {
        JsonObject flow = flowDocument(NodeBenchMain.options(args));
        Schemas.Report report = Schemas.FLOW.validate(flow);
        assertTrue(report.valid(), "the flow schema takes the bench flow: " + report);
        return FlowDefinition.from(plain(flow)).recording();
    }

    /** {@code flows/bench.yaml} of the package the run PUTs (the bench writes it as JSON, a YAML subset). */
    static JsonObject flowDocument(NodeBenchMain.Options o) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(NodeBenchMain.benchPackage(o, 1, 2)))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("flows/bench.yaml")) return new JsonObject(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("no flows/bench.yaml in the bench package");
    }

    /** The document as plain maps and lists, the shape the node's deploy hands {@link FlowDefinition#from}. */
    static Map<String, Object> plain(JsonObject document) {
        Map<String, Object> map = new LinkedHashMap<>();
        document.forEach(e -> map.put(e.getKey(), plainValue(e.getValue())));
        return map;
    }

    private static Object plainValue(Object value) {
        if (value instanceof JsonObject o) return plain(o);
        if (value instanceof JsonArray a) return a.stream().map(NodeBenchMainRecordingLevelTest::plainValue).toList();
        return value;
    }
}
