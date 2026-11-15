package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;

/**
 * A body that exists, in another node's journal (D-105, CORE-3 item 7), and cannot be read here now: its owner is not live, its
 * peer link is refused, closed or lost, or it answers that it does not hold the record (CORE-5a). Retryable, never "not found"
 * and never other bytes: the read is tried again, on this node or on the owner, which claims the body's work.
 *
 * @since 0.1
 */
public final class RemoteBodyException extends IllegalStateException {

    /** The body lies in {@code owner}'s journal and this store reads no other node's ({@link RemoteBodies} not given). */
    public RemoteBodyException(BlobKey key, String owner, String here) {
        super("the body under " + key + " lies in the journal of node " + owner + ", not of this node " + here
                + ": it is read on its own node; retryable");
    }

    /** The body lies in {@code owner}'s journal and its read from there failed: {@code why}, with its {@code cause} (may be null). */
    public RemoteBodyException(BlobKey key, String owner, String here, String why, Throwable cause) {
        super("the body under " + key + " lies in the journal of node " + owner + ", not of this node " + here + ": " + why + "; retryable", cause);
    }
}
