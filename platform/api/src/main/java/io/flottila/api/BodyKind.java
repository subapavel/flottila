package io.flottila.api;

/**
 * What a step needs of the body (ARCHITECTURE §5): the whole thing as a tree or string (size-limited), a stream it
 * never materialises, or splits into child messages. The flow builder refuses a {@link #WHOLE} step on a flow whose max
 * body exceeds the store's tree limit unless a {@link #SPLIT_ASSEMBLE} step precedes it — at build time, never on the
 * first large message.
 *
 * @since 0.1
 */
public enum BodyKind {
    /** DOM-style or string access to the whole body; bounded by the store's tree limit. */
    WHOLE,
    /** The body passes as a stream (gzip, PGP, signing, copying, sending); never materialised. */
    STREAM,
    /** Cuts the body into child messages (or assembles them); what makes a large body tractable for the steps after it. */
    SPLIT_ASSEMBLE
}
