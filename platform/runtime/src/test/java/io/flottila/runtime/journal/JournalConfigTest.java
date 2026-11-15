package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every journal limit is finite and range-checked, refused with an IllegalArgumentException naming its key (CORE-4a
 * packet "Types"; ranges from CORE-3 items 3 and 4: FLOTTILA_JOURNAL_APPEND_WAIT PT0.1S..PT1M, FLOTTILA_JOURNAL_RECORD_MAX
 * from 4 KiB, its u32 length inside a segment at most 2^31−1). The record count has no key in the design; its name here,
 * FLOTTILA_JOURNAL_SEGMENT_RECORDS, follows the others.
 */
class JournalConfigTest {

    @TempDir Path tmp;

    private void refused(String key, Consumer<Settings> change) {
        Settings s = settings(tmp);
        change.accept(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::config, key);
        assertTrue(String.valueOf(e.getMessage()).contains(key), "the refusal names " + key + ": " + e.getMessage());
    }

    private void accepted(Consumer<Settings> change) {
        Settings s = settings(tmp);
        change.accept(s);
        assertDoesNotThrow(s::config);
    }

    @Test
    void theDefaultsOfTheDesignAreAccepted() {
        accepted(s -> { });
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aLimitThatIsNotPositiveIsRefusedNamingItsKey(long value) {
        refused("FLOTTILA_JOURNAL_SEGMENT_BYTES", s -> s.segmentBytes(value));
        refused("FLOTTILA_JOURNAL_SEGMENT_RECORDS", s -> s.segmentRecords((int) value));
        refused("FLOTTILA_JOURNAL_SEGMENT_AGE", s -> s.segmentAge(Duration.ofMillis(value)));
        refused("FLOTTILA_JOURNAL_PENDING_BYTES", s -> s.pendingBytes(value));
        refused("FLOTTILA_JOURNAL_APPEND_WAIT", s -> s.appendWait(Duration.ofMillis(value)));
        refused("FLOTTILA_JOURNAL_RECORD_MAX", s -> s.recordMax(value));
    }

    @Test
    void appendWaitIsTakenFromPT0_1SToPT1M() {
        accepted(s -> s.appendWait(Duration.ofMillis(100)));
        accepted(s -> s.appendWait(Duration.ofMinutes(1)));
        refused("FLOTTILA_JOURNAL_APPEND_WAIT", s -> s.appendWait(Duration.ofMillis(100).minusNanos(1)));
        refused("FLOTTILA_JOURNAL_APPEND_WAIT", s -> s.appendWait(Duration.ofMinutes(1).plusNanos(1)));
    }

    @Test
    void recordMaxIsTakenFrom4KiBToItsU32Length() {
        accepted(s -> s.recordMax(4096));
        // CORE-4b review r1: the bound is where the longest record still fits a Java array (LargeBodyEdgeTest works it out)
        accepted(s -> s.recordMax(2_147_319_755L).segmentBytes(Integer.MAX_VALUE + 1L).pendingBytes(Integer.MAX_VALUE + 1L));
        refused("FLOTTILA_JOURNAL_RECORD_MAX", s -> s.recordMax(2_147_319_756L).segmentBytes(Integer.MAX_VALUE + 1L).pendingBytes(Integer.MAX_VALUE + 1L));
        refused("FLOTTILA_JOURNAL_RECORD_MAX", s -> s.recordMax(4095));
        refused("FLOTTILA_JOURNAL_RECORD_MAX", s -> s.recordMax(Integer.MAX_VALUE + 1L).segmentBytes(Integer.MAX_VALUE + 1L).pendingBytes(Integer.MAX_VALUE + 1L));
    }
}
