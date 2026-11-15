package io.flottila.runtime.blob;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBudgetTest {

    @Test
    void reservesAllOrNothingAndReleasesWhatWasReserved() {
        MemoryBudget b = new MemoryBudget(100);
        assertEquals(100, b.max());
        assertTrue(b.tryReserve(60));
        assertFalse(b.tryReserve(41), "60 + 41 > 100: refused, nothing taken");
        assertEquals(60, b.inUse());
        assertTrue(b.tryReserve(40));
        assertEquals(100, b.inUse());
        b.release(100);
        assertEquals(0, b.inUse());
        assertTrue(b.tryReserve(0));
    }

    @Test
    void misuseIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryBudget(0));
        assertThrows(IllegalArgumentException.class, () -> new MemoryBudget(-5));
        MemoryBudget b = new MemoryBudget(10);
        assertThrows(IllegalArgumentException.class, () -> b.tryReserve(-1));
        assertThrows(IllegalArgumentException.class, () -> b.release(-1));
        assertThrows(IllegalArgumentException.class, () -> b.release(1), "nothing reserved, nothing to release");
    }
}
