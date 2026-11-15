package io.flottila.runtime.journal;

import java.util.Objects;

/**
 * What {@link Journal#append} writes (CORE-3 item 2): the message id, step and part go into the record header in plain
 * (authenticated, not encrypted), the body is encrypted under the tenant key {@code tenantKeyId}. The body is read only
 * while {@code append} runs; it is not copied, so the caller does not change it before {@code append} returns.
 *
 * @param messageId   the message id, at most 65 535 UTF-8 bytes
 * @param step        the step, written as u32
 * @param part        the part, at most 65 535 UTF-8 bytes
 * @param tenantKeyId the id the journal's key lookup answers the tenant key for
 * @param body        the plaintext, at most {@code FLOTTILA_JOURNAL_RECORD_MAX} bytes
 * @since 0.1
 */
public record JournalRecord(String messageId, int step, String part, int tenantKeyId, byte[] body) {

    public JournalRecord {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(body, "body");
    }
}
