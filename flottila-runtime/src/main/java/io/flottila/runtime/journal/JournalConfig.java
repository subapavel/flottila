package io.flottila.runtime.journal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * The journal's settings (CORE-3 items 1, 3 and 4); every limit is finite and range-checked, a refusal names its key.
 *
 * @param dir            {@code FLOTTILA_JOURNAL_DIR}: {@code LOCK}, {@code NODE}, {@code segments/} and {@code spill/} live here
 * @param segmentBytes   {@code FLOTTILA_JOURNAL_SEGMENT_BYTES} (64 MiB): a segment rolls before the record that would take
 *                       it past this size; an empty segment takes any record
 * @param segmentRecords {@code FLOTTILA_JOURNAL_SEGMENT_RECORDS} (65 536): a segment rolls once it holds this many records
 * @param segmentAge     {@code FLOTTILA_JOURNAL_SEGMENT_AGE} (PT1S, at most PT1H): a segment rolls this long after its
 *                       first record on the injected clock; an empty segment never rolls
 * @param pendingBytes   {@code FLOTTILA_JOURNAL_PENDING_BYTES} (16 MiB, at least {@code FLOTTILA_JOURNAL_RECORD_MAX}):
 *                       encoded bytes reserved and not yet forced, above which an append waits for room; an append
 *                       finding nothing pending is always taken
 * @param appendWait     {@code FLOTTILA_JOURNAL_APPEND_WAIT} (PT5S, PT0.1S..PT1M): how long an append waits for room
 * @param recordMax      {@code FLOTTILA_JOURNAL_RECORD_MAX} (256 KiB, 4 KiB..2 147 319 755, where the longest segment record
 *                       still fits a Java array): the largest body a segment record takes
 * @param node           {@code FLOTTILA_NODE_ID}: written into every segment header and the identity file
 * @since 0.1
 */
public record JournalConfig(Path dir, long segmentBytes, int segmentRecords, Duration segmentAge, long pendingBytes,
                            Duration appendWait, long recordMax, String node) {

    private static final Duration WAIT_MIN = Duration.ofMillis(100);
    private static final Duration WAIT_MAX = Duration.ofMinutes(1);
    private static final long RECORD_MAX_MIN = 4096;
    /** The largest record max whose longest record (both names at their bound, a tag per chunk) a Java array holds. */
    private static final long RECORD_MAX_MAX = recordMaxBound();
    private static final Duration AGE_MAX = Duration.ofHours(1);

    public JournalConfig {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(segmentAge, "segmentAge");
        Objects.requireNonNull(appendWait, "appendWait");
        Objects.requireNonNull(node, "node");
        positive("FLOTTILA_JOURNAL_SEGMENT_BYTES", segmentBytes);
        positive("FLOTTILA_JOURNAL_SEGMENT_RECORDS", segmentRecords);
        if (!segmentAge.isPositive() || segmentAge.compareTo(AGE_MAX) > 0) {
            throw refused("FLOTTILA_JOURNAL_SEGMENT_AGE", "must be positive and at most " + AGE_MAX, segmentAge);
        }
        positive("FLOTTILA_JOURNAL_PENDING_BYTES", pendingBytes);
        if (appendWait.compareTo(WAIT_MIN) < 0 || appendWait.compareTo(WAIT_MAX) > 0) {
            throw refused("FLOTTILA_JOURNAL_APPEND_WAIT", "must lie in " + WAIT_MIN + ".." + WAIT_MAX, appendWait);
        }
        if (recordMax < RECORD_MAX_MIN || recordMax > RECORD_MAX_MAX) {
            throw refused("FLOTTILA_JOURNAL_RECORD_MAX", "must lie in " + RECORD_MAX_MIN + ".." + RECORD_MAX_MAX, recordMax);
        }
        if (pendingBytes < recordMax) {
            throw refused("FLOTTILA_JOURNAL_PENDING_BYTES", "must be at least FLOTTILA_JOURNAL_RECORD_MAX " + recordMax, pendingBytes);
        }
        int nodeBytes = node.getBytes(StandardCharsets.UTF_8).length;
        if (nodeBytes == 0 || nodeBytes > 255) throw refused("FLOTTILA_NODE_ID", "must be 1..255 UTF-8 bytes", node);
    }

    /** The most plain bytes whose record, after the longest header, ends within {@code Integer.MAX_VALUE - 8} (a JVM's array bound). */
    private static long recordMaxBound() {
        long room = Integer.MAX_VALUE - 8L - RecordFormat.MAX_HEADER;
        return room - (long) RecordFormat.chunks(room) * ChunkedAead.TAG_BYTES;
    }

    private static void positive(String key, long value) {
        if (value <= 0) throw refused(key, "must be positive", value);
    }

    private static IllegalArgumentException refused(String key, String rule, Object value) {
        return new IllegalArgumentException(key + " " + rule + ", was " + value);
    }
}
