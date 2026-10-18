package io.flottila.runtime.blob;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The memory tier's cold stream: chunked, pausable, ends once. */
class BufferReadStreamTest {

    static Buffer bytes(int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) i; return Buffer.buffer(b); }

    @Test
    void deliversTheBufferInChunksAndEndsOnce() {
        Buffer src = bytes(BufferReadStream.CHUNK * 2 + 10);
        BufferReadStream s = new BufferReadStream(src);
        Buffer all = Buffer.buffer();
        List<Integer> sizes = new ArrayList<>();
        int[] ends = {0};
        s.exceptionHandler(t -> { throw new AssertionError(t); });
        s.endHandler(v -> ends[0]++);
        s.handler(c -> { sizes.add(c.length()); all.appendBuffer(c); });
        assertEquals(List.of(BufferReadStream.CHUNK, BufferReadStream.CHUNK, 10), sizes);
        assertEquals(src, all);
        assertEquals(1, ends[0]);
    }

    @Test
    void pauseFetchAndResumeControlTheFlow() {
        BufferReadStream s = new BufferReadStream(bytes(BufferReadStream.CHUNK * 3));
        List<Integer> seen = new ArrayList<>();
        int[] ends = {0};
        s.pause();
        s.endHandler(v -> ends[0]++);
        s.handler(c -> seen.add(c.length()));
        assertTrue(seen.isEmpty());
        s.fetch(1);
        assertEquals(1, seen.size());
        s.fetch(Long.MAX_VALUE - 1);
        s.fetch(5);                       // saturates instead of overflowing
        assertEquals(3, seen.size());
        assertEquals(1, ends[0]);
        BufferReadStream t = new BufferReadStream(bytes(4));
        t.pause();
        t.handler(c -> {});
        t.fetch(1);                       // data out, credit spent: the end waits for demand
        int[] tEnds = {0};
        t.endHandler(v -> tEnds[0]++);
        assertEquals(0, tEnds[0]);
        t.resume();
        t.resume();
        assertEquals(1, tEnds[0]);
        assertThrows(IllegalArgumentException.class, () -> t.fetch(-1));
        BufferReadStream flowing = new BufferReadStream(bytes(3));
        flowing.fetch(1);                 // fetch while not paused changes nothing
        List<Integer> got = new ArrayList<>();
        flowing.handler(c -> got.add(c.length()));
        assertEquals(List.of(3), got);
    }

    @Test
    void aHandlerThatResumesFromInsideDoesNotReenterAndNoEndHandlerIsFine() {
        BufferReadStream s = new BufferReadStream(bytes(BufferReadStream.CHUNK * 2));
        List<Integer> seen = new ArrayList<>();
        s.pause();
        s.handler(c -> { seen.add(c.length()); s.resume(); });
        s.fetch(1);
        assertEquals(2, seen.size());
        BufferReadStream empty = new BufferReadStream(Buffer.buffer());
        int[] ends = {0};
        empty.endHandler(v -> ends[0]++);
        empty.handler(c -> { throw new AssertionError("no data in an empty body"); });
        assertEquals(1, ends[0]);
    }
}
