package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15, the implementer's cases: {@code --pg-cpu-source} reaches the record of a real (short) echo self-test — a pid it can
 * read is measured over the window without a note, a cgroup it cannot read is null with the reason naming it.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NodeBenchOpenEchoCpuIT {

    @TempDir Path dir;

    @Test
    void aReadablePidIsMeasuredOverTheWindowWithoutANote() throws Exception {
        JsonObject open = run(String.valueOf(ProcessHandle.current().pid()));
        assertNotNull(open.getJsonObject("cpuMs").getValue("postgres"), "this JVM's /proc stat is readable: " + open.encode());
        assertTrue(open.getJsonObject("cpuMs").getNumber("postgres").doubleValue() >= 0, open.encode());
        assertTrue(!open.getJsonObject("cpuNotes").containsKey("postgres"), "a measured figure has no note: " + open.encode());
    }

    @Test
    void anUnreadableCgroupIsNullWithTheReasonNamingIt() throws Exception {
        Path missing = dir.resolve("no-such.service");
        JsonObject open = run(missing.toString());
        CpuSourceTest.assertUnmeasured(open, "postgres");
        assertTrue(open.getJsonObject("cpuNotes").getString("postgres").contains(missing.toString()), open.encode());
    }

    /** A one-second echo self-test at 20/s with {@code source} as the Postgres CPU source; its {@code open} object. */
    JsonObject run(String source) throws Exception {
        Path out = dir.resolve("echo-cpu.json");
        int code = NodeBenchMain.run(new String[] {"--scenario", "echo", "--rate", "20", "--ramp", "PT0S", "--window", "PT1S",
                "--warmup", "0", "--pg-cpu-source", source, "--out", out.toString(), "--commit", "test"});
        assertEquals(0, code);
        return new JsonObject(Files.readString(out)).getJsonObject("open");
    }
}
