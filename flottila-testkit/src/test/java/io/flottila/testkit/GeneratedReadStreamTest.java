package io.flottila.testkit;

import io.flottila.api.Hasher;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedReadStreamTest {

    @Test
    void deliversExactlySizeBytesInChunksAndEndsOnce() {
        GeneratedReadStream s = new GeneratedReadStream(10, 4);
        Buffer all = Buffer.buffer();
        List<Integer> sizes = new ArrayList<>();
        int[] ends = {0};
        s.endHandler(v -> ends[0]++);
        s.handler(c -> { sizes.add(c.length()); all.appendBuffer(c); });
        assertEquals(List.of(4, 4, 2), sizes);
        assertEquals(10, all.length());
        for (int i = 0; i < 10; i++) assertEquals(GeneratedReadStream.byteAt(i), all.getByte(i));
        assertEquals(1, ends[0]);
        assertEquals(10, s.delivered());
    }

    @Test
    void pauseStopsDeliveryFetchHandsOutThatManyChunksResumeFinishes() {
        GeneratedReadStream s = new GeneratedReadStream(10, 2);
        List<Integer> seen = new ArrayList<>();
        s.pause();
        s.handler(c -> seen.add(c.length()));
        assertTrue(seen.isEmpty(), "paused before the handler: nothing flows");
        s.fetch(2);
        assertEquals(2, seen.size());
        s.resume();
        assertEquals(5, seen.size());
        assertEquals(10, s.delivered());
    }

    @Test
    void expectedHashEqualsTheHashOfTheDeliveredBytes() {
        Hasher h = Hasher.sha256();
        GeneratedReadStream s = new GeneratedReadStream(200_000, 777);
        Hasher.Session session = h.begin();
        s.handler(session::update);
        assertEquals(GeneratedReadStream.expectedHash(h, 200_000), session.finish());
        assertEquals(h.hashOf(Buffer.buffer()), GeneratedReadStream.expectedHash(h, 0), "the empty body");
    }

    @Test
    void fetchCreditSaturatesInsteadOfOverflowing() {
        GeneratedReadStream s = new GeneratedReadStream(6, 2);
        List<Integer> seen = new ArrayList<>();
        s.pause();
        s.handler(c -> seen.add(c.length()));
        s.fetch(Long.MAX_VALUE);
        assertEquals(3, seen.size(), "unbounded credit drains everything");
        GeneratedReadStream t = new GeneratedReadStream(6, 2);
        t.pause();
        t.fetch(Long.MAX_VALUE - 1);
        t.fetch(5);                       // would overflow: saturates at Long.MAX_VALUE
        List<Integer> late = new ArrayList<>();
        t.handler(c -> late.add(c.length()));
        assertEquals(3, late.size());
    }

    @Test
    void aHandlerThatResumesFromInsideDoesNotReenterTheDrainLoop() {
        GeneratedReadStream s = new GeneratedReadStream(6, 2);
        List<Integer> seen = new ArrayList<>();
        int[] ends = {0};
        s.pause();
        s.endHandler(v -> ends[0]++);
        s.handler(c -> { seen.add(c.length()); s.resume(); });   // re-entrant resume: the guard defers, nothing is delivered twice
        s.fetch(1);
        assertEquals(List.of(2, 2, 2), seen);
        assertEquals(1, ends[0]);
    }

    @Test
    void endFiresOnceEvenWhenPausedAtTheEndOrWithoutAnEndHandler() {
        GeneratedReadStream s = new GeneratedReadStream(4, 2);
        int[] ends = {0};
        s.pause();
        s.handler(c -> {});
        s.fetch(2);                       // all data out, credit spent: end waits
        assertEquals(4, s.delivered());
        s.endHandler(v -> ends[0]++);
        assertEquals(0, ends[0], "still paused without credit");
        s.resume();
        s.resume();
        assertEquals(1, ends[0]);
        GeneratedReadStream noEnd = new GeneratedReadStream(2, 2);
        noEnd.handler(c -> {});           // no endHandler: nothing to call, no failure
        assertEquals(2, noEnd.delivered());
    }

    @Test
    void argumentsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReadStream(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReadStream(4, 0));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedReadStream(4, 1).fetch(-1));
    }
}
