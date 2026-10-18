package io.flottila.testkit;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behaviour the main stream tests leave open: late handlers, empty streams, bad input, exactly-once termination. */
class ChunkedReadStreamEdgeCasesTest {

    @Test
    void anEndHandlerSetAfterTheLastChunkStillFiresExactlyOnce() {
        ChunkedReadStream s = new ChunkedReadStream(List.of("a"));
        s.handler(b -> { });
        int[] ends = {0};
        s.endHandler(v -> ends[0]++);
        s.endHandler(v -> ends[0]++);
        assertEquals(1, ends[0]);
    }

    @Test
    void aFailureHandlerSetAfterTheLastChunkStillFires() {
        ChunkedReadStream s = new ChunkedReadStream(List.of("a"), new IllegalStateException("late"));
        s.handler(b -> { });
        List<String> failures = new ArrayList<>();
        s.exceptionHandler(t -> failures.add(t.getMessage()));
        s.exceptionHandler(t -> failures.add(t.getMessage()));
        assertEquals(List.of("late"), failures);
    }

    @Test
    void anEmptyStreamEndsAsSoonAsItHasAHandler() {
        ChunkedReadStream s = new ChunkedReadStream(List.of());
        boolean[] ended = {false};
        s.endHandler(v -> ended[0] = true);
        assertEquals(false, ended[0], "cold: nothing moves before the data handler is set");
        s.handler(b -> { });
        assertEquals(true, ended[0]);
        assertEquals(0, s.delivered());
    }

    @Test
    void aHandlerThatPausesStopsTheDrainAndFetchContinuesFromThere() {
        ChunkedReadStream s = new ChunkedReadStream(List.of("a", "b", "c"));
        List<String> got = new ArrayList<>();
        s.handler(b -> { got.add(b.toString()); s.pause(); });
        assertEquals(List.of("a"), got);
        s.fetch(Long.MAX_VALUE).fetch(Long.MAX_VALUE);
        assertEquals(List.of("a", "b", "c"), got);
    }

    @Test
    void fetchOnARunningStreamChangesNothing() {
        ChunkedReadStream s = new ChunkedReadStream(List.of(Buffer.buffer("a")));
        List<String> got = new ArrayList<>();
        s.handler(b -> got.add(b.toString())).fetch(5);
        assertEquals(List.of("a"), got);
    }

    @Test
    void rejectsNegativeDemandAndForeignChunkTypes() {
        ChunkedReadStream s = new ChunkedReadStream(List.of("a"));
        assertThrows(IllegalArgumentException.class, () -> s.fetch(-1));
        assertThrows(IllegalArgumentException.class, () -> new ChunkedReadStream(List.of(42)));
    }
}
