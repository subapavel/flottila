package io.flottila.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream-heap run's child node without a database (P1-10c): a heap sample is taken after a forced GC, every line asked is
 * answered with one sample, and a configuration the node refuses reaches the caller of {@link StreamHeapNode#run} as the cause of its ExecutionException.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StreamHeapNodeTest {

    @Test
    void aSampleIsTakenAfterAForcedGc() {
        WeakReference<Object> garbage = new WeakReference<>(new byte[1024 * 1024]);
        StreamHeapNode.Heap heap = StreamHeapNode.sample();
        assertNull(garbage.get(), "the sample forced a GC first: an object only weakly reachable is gone");
        assertTrue(heap.used() > 0 && heap.used() <= heap.max(), "used and max of this JVM's heap: " + heap);
    }

    @Test
    void everyLineAskedIsAnsweredWithOneSampleUntilTheAsksEnd() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamHeapNode.answer(new BufferedReader(new StringReader("heap\nanything\n")), new PrintStream(out, false, StandardCharsets.UTF_8));
        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, lines.size(), "one answer per line asked: " + lines);
        for (String line : lines) {
            String[] fields = line.split(" ");
            assertEquals(2, fields.length, line);
            assertTrue(Long.parseLong(fields[0]) > 0 && Long.parseLong(fields[0]) <= Long.parseLong(fields[1]), "<used> <max>: " + line);
        }
    }

    @Test
    void aConfigurationTheNodeRefusesReachesTheCaller() {
        ExecutionException failed = assertThrows(ExecutionException.class, () -> StreamHeapNode.run(Map.of(),
                new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        IllegalArgumentException refused = assertInstanceOf(IllegalArgumentException.class, failed.getCause(), "the node's own refusal: " + failed);
        assertTrue(refused.getMessage().contains("FLOTTILA_ENV"), refused.getMessage());
    }
}
