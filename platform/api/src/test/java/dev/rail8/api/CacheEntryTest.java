package dev.rail8.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Cache.Entry} holds a value of at most {@link Cache#MAX_VALUE_BYTES} in UTF-8, cuts a ttl above {@link Cache#MAX_TTL}
 * to it, keeps a ttl of zero or less as given (such an entry is not kept), and never shows its value.
 */
class CacheEntryTest {

    @Test
    void aValueUpToTheLimitInUtf8IsAnEntry() {
        String ascii = "a".repeat((int) Cache.MAX_VALUE_BYTES);
        assertEquals(ascii, new Cache.Entry(ascii, Duration.ofMinutes(1)).value());
        String twoByteChars = "é".repeat((int) Cache.MAX_VALUE_BYTES / 2);
        assertEquals(twoByteChars, new Cache.Entry(twoByteChars, Duration.ofMinutes(1)).value());
    }

    @Test
    void aLongerValueIsRefusedWithoutShowingIt() {
        String overByOne = "é".repeat((int) Cache.MAX_VALUE_BYTES / 2) + "x";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new Cache.Entry(overByOne, Duration.ofMinutes(1)));
        assertFalse(e.getMessage().contains("éé"), e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(Cache.MAX_VALUE_BYTES)), e.getMessage());
        String huge = "z".repeat(10 * (int) Cache.MAX_VALUE_BYTES);
        assertThrows(IllegalArgumentException.class, () -> new Cache.Entry(huge, Duration.ofMinutes(1)));
    }

    @Test
    void aTtlAboveTheLimitIsCutToItAndOneOfZeroOrLessIsKeptAsGiven() {
        assertEquals(Cache.MAX_TTL, new Cache.Entry("v", Duration.ofDays(3)).ttl());
        assertEquals(Cache.MAX_TTL, new Cache.Entry("v", Cache.MAX_TTL).ttl());
        assertEquals(Duration.ZERO, new Cache.Entry("v", Duration.ZERO).ttl());
        assertEquals(Duration.ofSeconds(-1), new Cache.Entry("v", Duration.ofSeconds(-1)).ttl());
    }

    @Test
    void itShowsTheLengthAndTheTtlNeverTheValue() {
        String shown = new Cache.Entry("token-abc", Duration.ofMinutes(5)).toString();
        assertFalse(shown.contains("token-abc"), shown);
        assertTrue(shown.contains("9 chars") && shown.contains("PT5M"), shown);
    }

    @Test
    void nullsAreRefused() {
        assertThrows(NullPointerException.class, () -> new Cache.Entry(null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new Cache.Entry("v", null));
    }
}
