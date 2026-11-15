package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tier benchmark end to end with a small body; the numbers it prints are not recorded from here. */
class BlobBenchMainTest {

    @Test
    void writesAndReadsBothTiersAndReportsMbPerSecond(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("blob.json");
        int code = BlobBenchMain.run(new String[] {"--mb", "1", "--rounds", "3", "--chunk", "4096", "--spool-dir", dir.resolve("spool").toString(),
                "--out", out.toString(), "--commit", "test"});
        assertEquals(0, code);
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(true, json.getBoolean("hashesMatched"));
        for (String tier : new String[] {"memory", "spool"}) {
            JsonObject t = json.getJsonObject(tier);
            assertTrue(t.getDouble("writeMbPerSecond") > 0, tier);
            assertTrue(t.getDouble("readMbPerSecond") > 0, tier);
            assertEquals(3, t.getJsonArray("writeMsPerRound").size());
        }
        assertEquals(1, json.getInteger("bodyMb"));
        assertEquals("test", json.getString("commit"));
        assertEquals(1.5, BlobBenchMain.mbPerSecond(3, new long[] {2_000_000_000L, 1_000_000_000L, 5_000_000_000L}), "median round: 3 MB in the 2 s round");
    }
}
