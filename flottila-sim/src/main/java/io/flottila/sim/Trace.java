package io.flottila.sim;

import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.vertx.core.buffer.Buffer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The run's events (D-101), each at a tick under a message id ({@code ""} for the driver's own) with its sequence number within
 * that id; the digest is sha256 (the platform's {@code sha256-64}) over them sorted by (tick, message id, seq), so how the
 * threads of one tick interleave across messages does not move it.
 */
final class Trace {

    private record Event(long tick, String id, int seq, String text) {}

    private final List<Event> events = new ArrayList<>();       // guarded by this
    private final Map<String, Integer> seqs = new HashMap<>();   // guarded by this

    synchronized void add(long tick, String id, String text) {
        events.add(new Event(tick, id, seqs.merge(id, 1, Integer::sum), text));
    }

    /** {@code sha256-64:<16 hex digits>} over the sorted events, one {@code tick⇥id⇥seq⇥text} line each. */
    synchronized String digest() {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(Event::tick).thenComparing(Event::id).thenComparingInt(Event::seq));
        Hasher.Session sha = Hasher.sha256().begin();
        for (Event e : sorted) sha.update(Buffer.buffer(e.tick() + "\t" + e.id() + "\t" + e.seq() + "\t" + e.text() + "\n"));
        Hash h = sha.finish();
        return h.algorithm() + ":" + HexFormat.of().toHexDigits(h.value());
    }
}
