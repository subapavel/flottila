package dev.rail8.runtime.journal;

import java.io.IOException;

/**
 * A replica this holder cannot take as asked (the holder answers REFUSE MISMATCH): the segment is held sealed already, a {@code .part} of
 * it is not exactly the header an OPEN carries (another header, or bytes past it), or a SEAL's CRC32C is not the one of the
 * bytes written. Nothing of the file is changed by the refusal.
 *
 * @since 0.1
 */
public final class ReplicaMismatch extends IOException {

    public ReplicaMismatch(String message) {
        super(message);
    }
}
