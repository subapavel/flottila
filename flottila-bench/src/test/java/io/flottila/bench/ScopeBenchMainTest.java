package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spike-2 entry point end to end with small sizes (numbers from here are never recorded). */
class ScopeBenchMainTest {

    @Test
    void runsBothMeasurementsAndWritesOneJsonWithMachineAndCommit() throws Exception {
        Path out = Files.createTempDirectory("scope-bench").resolve("scope.json");
        int code = ScopeBenchMain.run(new String[] {"--scopes", "200", "--children", "2", "--scope-concurrency", "8", "--threads", "300", "--requests", "16",
                "--bytes", "65536", "--request-bytes", "1024", "--hold-ms", "0", "--out", out.toString(), "--commit", "test"});
        assertEquals(0, code);
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals("spike-2", json.getString("name"));
        assertEquals("test", json.getString("commit"));
        assertEquals(200, json.getJsonObject("scope").getInteger("scopes"));
        assertEquals(8, json.getJsonObject("scope").getInteger("concurrency"));
        assertEquals(0, json.getJsonObject("scope").getInteger("failed"));
        assertEquals(300, json.getJsonObject("admission").getInteger("threads"));
        assertEquals(0, json.getJsonObject("admission").getInteger("failed"));
        assertTrue(json.getJsonObject("admission").getLong("maxInFlightRequests") <= 16);
        assertTrue(json.getJsonObject("machine").getInteger("cpus") > 0);
    }
}
