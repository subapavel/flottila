/**
 * The peer link between nodes (CORE-5, F-35): node-to-node journal traffic over TCP — in 5a the read of a record in another
 * live node's journal (READ → BYTES | MISS). Internal to the runtime, not a component: it carries journal bytes, never
 * Envelopes, and is the one package that opens a raw socket ({@code ONLY_THE_PEER_LINK_OPENS_RAW_TCP}).
 */
package io.flottila.runtime.journal.peer;
