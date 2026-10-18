package io.flottila.api;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * What a flow is built from: clock, id generation, blob store, hasher and the components by
 * scheme. Used inside the flow builder and nowhere else — a stage holds only what it was given at
 * build time and never captures the record (CLAUDE.md). Draft — review at hand-over; stores,
 * caches, wallet and the services registry are added by the tasks that introduce them.
 *
 * @since 0.1
 */
public record Runtime(Clock clock, IdGenerator ids, BlobStore blobs, Hasher hasher, Map<String, Component> components) {

    public Runtime {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(hasher, "hasher");
        components = Map.copyOf(components);
    }

    /** The component for a scheme. */
    public Component component(String scheme) {
        Component c = components.get(scheme);
        if (c == null) throw new IllegalArgumentException("no component for scheme '" + scheme + "'");
        return c;
    }
}
