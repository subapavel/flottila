package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.runtime.journal.JournalRef;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The store's in-memory location index (CORE-3 item 8): key → the ref of its record in this node's journal, at most
 * {@code max} entries; a put past the bound lets an entry of the oldest segment (the lowest number) go first. Thread-safe.
 */
final class LocationIndex {

    private final int max;
    private final Map<BlobKey, JournalRef> refs = new HashMap<>();
    private final TreeMap<Long, Set<BlobKey>> bySegment = new TreeMap<>();

    LocationIndex(int max) {
        this.max = max;
    }

    synchronized JournalRef get(BlobKey key) {
        return refs.get(key);
    }

    synchronized void put(BlobKey key, JournalRef ref) {
        remove(key);
        refs.put(key, ref);
        bySegment.computeIfAbsent(ref.segment(), n -> new LinkedHashSet<>()).add(key);
        if (refs.size() > max) remove(bySegment.firstEntry().getValue().iterator().next());
    }

    synchronized void remove(BlobKey key) {
        JournalRef gone = refs.remove(key);
        if (gone == null) return;
        Set<BlobKey> keys = bySegment.get(gone.segment());
        keys.remove(key);
        if (keys.isEmpty()) bySegment.remove(gone.segment());
    }
}
