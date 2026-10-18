package io.flottila.bench;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchMainArgsTest {

    @Test
    void parsesDoubleDashPairsAndRejectsStrays() {
        assertEquals(Map.of("messages", "10", "sync-commit", "off"), BenchMain.parse(new String[] {"--messages", "10", "--sync-commit", "off"}));
        assertThrows(IllegalArgumentException.class, () -> BenchMain.parse(new String[] {"messages", "10"}));
        assertThrows(IllegalArgumentException.class, () -> BenchMain.parse(new String[] {"--messages"}));
        assertThrows(IllegalArgumentException.class, () -> BenchMain.parse(new String[] {"--messages", "--out"}));
    }

    @Test
    void machineDescriptionNamesTheLabelCpusAndJava() {
        var m = BenchMain.machine();
        assertTrue(m.getInteger("cpus") > 0);
        assertTrue(m.getString("java").startsWith("25"));
        assertEquals(System.getenv().getOrDefault("BENCH_MACHINE", "host:" + System.getenv().getOrDefault("HOSTNAME", "unknown-host")), m.getString("label"),
                "unset BENCH_MACHINE labels the run with this host, never with a baseline's label (D-037)");
        assertFalse(m.getString("label").isBlank());
    }
}
