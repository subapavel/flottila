package io.flottila.api;

/**
 * A body exceeded a finite limit: the flow's maximum body size on write, or the store's tree limit
 * on {@link Body#whole()}. Sizes are {@code long}; no limit is ever unlimited.
 *
 * @since 0.1
 */
public final class BodyTooLargeException extends RuntimeException {

    private final BlobKey key;
    private final long size;
    private final long limit;

    public BodyTooLargeException(BlobKey key, long size, long limit) {
        super("body " + key + " has " + size + " bytes, limit is " + limit);
        this.key = key;
        this.size = size;
        this.limit = limit;
    }

    public BlobKey key() { return key; }

    public long size() { return size; }

    public long limit() { return limit; }
}
