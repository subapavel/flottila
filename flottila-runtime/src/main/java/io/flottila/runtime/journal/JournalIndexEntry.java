package io.flottila.runtime.journal;

/**
 * One entry of a sealed segment's footer index (CORE-3 item 1): what the record is and where it lies.
 *
 * @param messageId the record's message id
 * @param step      the record's step
 * @param part      the record's part
 * @param ref       the record's ref
 * @since 0.1
 */
public record JournalIndexEntry(String messageId, int step, String part, JournalRef ref) { }
