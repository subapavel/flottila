package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;

/**
 * A body that exists, in another node's journal (D-105, CORE-3 item 7): until CORE-5's peer reads a body is read on the node
 * whose journal holds it, so the read fails here — retryable, never "not found" (the work of that body is claimed there).
 *
 * @since 0.1
 */
public final class RemoteBodyException extends IllegalStateException {

    public RemoteBodyException(BlobKey key, String owner, String here) {
        super("the body under " + key + " lies in the journal of node " + owner + ", not of this node " + here
                + ": it is read on its own node (peer reads come with CORE-5); retryable");
    }
}
