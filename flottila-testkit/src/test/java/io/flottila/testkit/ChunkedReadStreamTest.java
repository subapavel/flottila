package io.flottila.testkit;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The testkit's cold stream: chunks on demand, honours pause/fetch, ends once, can fail on purpose. */
class ChunkedReadStreamTest {

    @Test
    void deliversChunksInOrderThenEnds() {
        ChunkedReadStream s = new ChunkedReadStream(List.of("a", "b", "c"));
        List<String> got = new ArrayList<>();
        boolean[] ended = {false};
        s.endHandler(v -> ended[0] = true);
        s.handler(b -> got.add(b.toString()));
        assertEquals(List.of("a", "b", "c"), got);
        assertTrue(ended[0]);
        assertEquals(3, s.delivered());
    }

    @Test
    void pauseStopsDeliveryAndFetchResumesOneAtATime() {
        ChunkedReadStream s = new ChunkedReadStream(List.of("a", "b", "c"));
        List<String> got = new ArrayList<>();
        s.pause();
        s.handler(b -> got.add(b.toString()));
        assertEquals(List.of(), got);
        s.fetch(1);
        assertEquals(List.of("a"), got);
        s.fetch(1);
        assertEquals(List.of("a", "b"), got);
        s.resume();
        assertEquals(List.of("a", "b", "c"), got);
    }

    @Test
    void fetchOfZeroDeliversNothingAndDemandAccumulates() {
        ChunkedReadStream s = new ChunkedReadStream(List.of("a", "b", "c"));
        List<String> got = new ArrayList<>();
        s.pause().handler(b -> got.add(b.toString()));
        s.fetch(0);
        assertEquals(List.of(), got);
        s.fetch(2);
        assertEquals(List.of("a", "b"), got);
        assertEquals(2, s.delivered());
        assertSame(s, s.exceptionHandler(t -> { }), "fluent setters return the stream");
        assertSame(s, s.endHandler(v -> { }));
    }

    @Test
    void failsAfterItsChunksWhenBuiltWithAnError() {
        ChunkedReadStream s = new ChunkedReadStream(List.of("a"), new IllegalStateException("boom"));
        List<String> got = new ArrayList<>();
        Throwable[] failure = {null};
        boolean[] ended = {false};
        s.exceptionHandler(t -> failure[0] = t);
        s.endHandler(v -> ended[0] = true);
        s.handler(b -> got.add(b.toString()));
        assertEquals(List.of("a"), got);
        assertEquals("boom", failure[0].getMessage());
        assertFalse(ended[0], "a failed stream does not also end");
    }

    @Test
    void bytesAreCopiedPerDelivery() {
        Buffer source = Buffer.buffer("x");
        ChunkedReadStream s = new ChunkedReadStream(List.of(source));
        s.handler(b -> b.appendString("y"));
        assertEquals("x", source.toString());
    }

    @Test
    void pauseAndResumeReturnTheStreamItself() {   // FIX-53
        ChunkedReadStream s = new ChunkedReadStream(List.of("a", "b"));
        List<String> got = new ArrayList<>();
        assertSame(s, s.pause());
        s.handler(b -> got.add(b.toString()));
        assertSame(s, s.resume());
        assertEquals(List.of("a", "b"), got);
    }
}
