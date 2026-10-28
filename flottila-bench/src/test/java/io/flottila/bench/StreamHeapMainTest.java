package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream-heap run's command line and verdict (P1-10c, GATE 9 "a 50 MB body through a stream step without heap growth"):
 * {@code --mb} (50; the body is {@code mb} MiB, at least 1), {@code --xmx} (64m, the child node's -Xmx), the node bench's
 * database options ({@code --db} must name a bench database: the run truncates it), {@code --out}, {@code --commit}. The exit
 * code is 0 only when the partner received the bytes sent ({@code partnerSha256 == sentSha256}), the node did not run out of
 * memory ({@code oom} false) and the heap after a forced GC grew less than 8 MiB ({@code heapGrowthBytes < 8 MiB}); one
 * non-zero case per term.
 */
class StreamHeapMainTest {

    static final long MIB = 1024L * 1024;
    static final String SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Test
    void optionsAreParsedWithTheGateDefaults() {
        StreamHeapMain.Options defaults = StreamHeapMain.options(new String[] {});
        assertEquals(50, defaults.mb(), "GATE 9: a 50 MB body");
        assertEquals("64m", defaults.xmx(), "the child node's heap: below what two copies of the body need");
        assertTrue(defaults.db().contains("bench"), "the default --db names a bench database: " + defaults.db());

        StreamHeapMain.Options o = StreamHeapMain.options(new String[] {"--mb", "12", "--xmx", "96m", "--host", "db.local", "--port", "6543",
                "--db", "heap_bench", "--user", "u", "--password", "p", "--out", "x/stream-heap.json", "--commit", "abc"});
        assertEquals(12, o.mb());
        assertEquals("96m", o.xmx());
        assertEquals("db.local", o.host());
        assertEquals(6543, o.port());
        assertEquals("heap_bench", o.db());
        assertEquals("u", o.user());
        assertEquals("p", o.password());
        assertEquals(Path.of("x/stream-heap.json"), o.out());
        assertEquals("abc", o.commit());
        assertEquals(1, StreamHeapMain.options(new String[] {"--mb", "1"}).mb(), "the smallest body is taken");
    }

    @Test
    void aDatabaseThatIsNotABenchDatabaseIsRefusedBeforeAnythingConnects() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> StreamHeapMain.options(new String[] {"--db", "flottila"}));
        assertTrue(refused.getMessage().contains("flottila") && refused.getMessage().contains("bench"),
                "names the database and the rule: " + refused.getMessage());
        // the run refuses before it touches a database or starts a child JVM (nothing listens on port 1)
        IllegalArgumentException run = assertThrows(IllegalArgumentException.class,
                () -> StreamHeapMain.run(new String[] {"--host", "127.0.0.1", "--port", "1", "--db", "production"}));
        assertTrue(run.getMessage().contains("production"), run.getMessage());
    }

    @Test
    void aBodyBelowOneMegabyteIsRefused() {
        for (String mb : new String[] {"0", "-1"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> StreamHeapMain.options(new String[] {"--mb", mb}), "--mb " + mb);
            assertTrue(refused.getMessage().contains("--mb") && refused.getMessage().contains(mb), "names the option and the value: " + refused.getMessage());
        }
        IllegalArgumentException run = assertThrows(IllegalArgumentException.class,
                () -> StreamHeapMain.run(new String[] {"--host", "127.0.0.1", "--port", "1", "--db", "x_bench", "--mb", "0"}));
        assertTrue(run.getMessage().contains("--mb"), run.getMessage());
    }

    @Test
    void theExitCodeIsZeroWhenThePartnerGotTheBytesWithoutOomAndTheHeapGrewLessThanEightMegabytes() {
        assertEquals(0, StreamHeapMain.exitCode(clean()), "a clean run: " + clean().encode());
        assertEquals(0, StreamHeapMain.exitCode(grown(8 * MIB - 1)), "growth just below 8 MiB passes");
        assertEquals(0, StreamHeapMain.exitCode(grown(-3 * MIB)), "a heap that shrank passes");
    }

    @Test
    void theExitCodeIsNonZeroWhenThePartnerGotOtherBytes() {
        JsonObject other = clean().put("partnerSha256", SHA.substring(0, 63) + "9");
        assertNotEquals(0, StreamHeapMain.exitCode(other), "the partner's sha256 differs from the sent one: " + other.encode());
        JsonObject none = clean().putNull("partnerSha256");
        assertNotEquals(0, StreamHeapMain.exitCode(none), "the partner received nothing: " + none.encode());
    }

    @Test
    void theExitCodeIsNonZeroWhenTheNodeRanOutOfMemory() {
        JsonObject oom = clean().put("oom", true);
        assertNotEquals(0, StreamHeapMain.exitCode(oom), "an OutOfMemoryError in the node fails the run whatever the hashes: " + oom.encode());
    }

    @Test
    void theExitCodeIsNonZeroWhenTheHeapGrewEightMegabytesOrMore() {
        assertNotEquals(0, StreamHeapMain.exitCode(grown(8 * MIB)), "growth of exactly 8 MiB is not below 8 MiB");
        assertNotEquals(0, StreamHeapMain.exitCode(grown(50 * MIB)), "a retained body fails the run");
    }

    /** A run that holds: the body arrived intact, no OOM, the heap after a forced GC 1 MiB above where it started. */
    static JsonObject clean() {
        return grown(MIB);
    }

    /** The clean run with the heap after a forced GC {@code growth} bytes above the 20 MiB it started at. */
    static JsonObject grown(long growth) {
        long before = 20 * MIB;
        return new JsonObject().put("mb", 50).put("xmx", "64m").put("maxHeapBytes", 64 * MIB).put("heapBeforeBytes", before)
                .put("heapAfterBytes", before + growth).put("heapGrowthBytes", growth).put("sentSha256", SHA).put("partnerSha256", SHA)
                .put("oom", false).put("elapsedMs", 1234.5).put("commit", "test").put("date", "2026-10-28T00:00:00Z")
                .put("machine", BenchMain.machine());
    }
}
