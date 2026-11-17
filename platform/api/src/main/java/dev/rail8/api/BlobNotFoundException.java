package dev.rail8.api;

/**
 * Nothing is stored under a key: it was never written, or retention removed it. A {@link BlobStore}'s {@code read} and
 * {@code persist} fail with it, its {@code open} throws it or returns a stream that fails with it before its first chunk;
 * {@link Body#whole()} and {@link Body#open()} pass it through. It is an {@link IllegalArgumentException}, the failure the
 * store's contract names for a key with nothing stored. It is not a {@link Fault}: whose fault a missing body is depends
 * on who asked for it.
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
