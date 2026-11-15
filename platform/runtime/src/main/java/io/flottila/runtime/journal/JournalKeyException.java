package io.flottila.runtime.journal;

import java.io.IOException;

/**
 * A record cannot be read because its tenant key is not there (deleted: the record is shredded) or cannot be used as an
 * AES key. Not corruption: the bytes are intact, only the key is missing.
 *
 * @since 0.1
 */
public final class JournalKeyException extends IOException {

    public JournalKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
