package dev.rail8.runtime.journal;

/**
 * One entry of a sealed segment's footer index: what the record is and where it lies.
 *
 * @param messageId the record's message id
 * @param step      the record's step
 * @param part      the record's part
 * @param ref       the record's ref
 * @since 0.1
 */
public record JournalIndexEntry(String messageId, int step, String part, JournalRef ref) { }
