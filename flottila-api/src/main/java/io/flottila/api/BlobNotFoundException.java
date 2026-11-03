package io.flottila.api;

/**
 * Nothing is stored under a key — it was never written, or retention removed it (D-096). A {@link BlobStore}'s
 * {@code read} and {@code persist} fail with it, its {@code open} throws it or returns a stream that fails with it before
 * its first chunk; {@link Body#whole()} and {@link Body#open()} pass it through. An {@link IllegalArgumentException}, so
 * D-061's contract for a key with nothing stored holds; no {@link Fault}: whose fault a missing body is depends on who asked.
 *
 * @since 0.1
 */
public final class BlobNotFoundException extends IllegalArgumentException {

    private final BlobKey key;

    public BlobNotFoundException(BlobKey key) {
        super("no object stored under " + key);
        this.key = key;
    }

    /** The key with nothing stored under it. */
    public BlobKey key() { return key; }
}
