package io.flottila.runtime.journal;

/**
 * Where a record lies: its segment, its offset in the segment file and its encoded length (CORE-3 items 1 and 2). Known
 * when {@link Journal#append} returns, before the record is written.
 *
 * @param segment  the segment number n ({@code segments/<n>.seg})
 * @param position the offset of the record's first byte in the segment file
 * @param length   the encoded record's length (u32 inside a segment)
 * @since 0.1
 */
public record JournalRef(long segment, long position, int length) { }
