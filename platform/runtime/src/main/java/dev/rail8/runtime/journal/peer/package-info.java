/**
 * The peer link between nodes: node-to-node journal traffic over TCP — the read of a record in another live node's journal
 * (READ → BYTES | MISS) and the copies of a node's segments its holders keep. Internal to the runtime, not a component: it carries journal bytes, never
 * Envelopes, and is the one package that opens a raw socket ({@code ONLY_THE_PEER_LINK_OPENS_RAW_TCP}).
 */
package dev.rail8.runtime.journal.peer;
