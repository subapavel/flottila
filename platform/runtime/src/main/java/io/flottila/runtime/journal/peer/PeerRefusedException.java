package io.flottila.runtime.journal.peer;

import java.io.IOException;

/**
 * A peer link refused (CORE-5 S2, W7): a HELLO or a proof this side cannot trust, or a frame that breaks the protocol — a tag
 * that does not hold, a length above any frame's, a sequence out of order, a type or payload it does not know. The link closes.
 *
 * @since 0.1
 */
public final class PeerRefusedException extends IOException {

    public PeerRefusedException(String message) {
        super(message);
    }
}
