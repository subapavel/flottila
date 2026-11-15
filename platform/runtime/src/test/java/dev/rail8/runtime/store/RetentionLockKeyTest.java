package dev.rail8.runtime.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-218: the retention advisory-lock key is a stored name on the database server, shared by every node and every build that
 * runs retention against one database: two builds with different keys would both run retention. The expectation is a literal
 * written here, never read from {@code Retention}.
 */
class RetentionLockKeyTest {

    @Test
    void theRetentionLockKeyIsTheAsciiBytesOfRail8InTheHighFiveBytesAndZeroBelow() {
        // ASCII r a i l 8 = 72 61 69 6C 38, then three zero bytes: 72 61 69 6C 38 00 00 00 as a big-endian long
        assertEquals(0x7261696C38000000L, Retention.LOCK_KEY,
                "Retention.LOCK_KEY is 0x7261696C38000000 (\"rail8\" then three zero bytes): another build's key would let two retention runs overlap");
    }
}
