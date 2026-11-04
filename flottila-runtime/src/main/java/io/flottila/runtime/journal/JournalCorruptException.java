package io.flottila.runtime.journal;

import java.io.IOException;

/**
 * A record or a footer read back is not what the journal wrote: it fails its CRC32C, it is not a record of this format, or
 * it fails authentication under its tenant key (CORE-3 item 9). Never reported as "not found"; the journal logs it at
 * ERROR. Also the start's refusal of a segment whose header is unreadable over records, or that is another journal's.
 *
 * @since 0.1
 */
public final class JournalCorruptException extends IOException {

    public JournalCorruptException(String message) {
        super(message);
    }

    public JournalCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
