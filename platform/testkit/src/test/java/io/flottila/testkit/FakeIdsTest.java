package io.flottila.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FakeIdsTest {

    @Test
    void idsAreDeterministicAndSequential() {
        FakeIds ids = new FakeIds("m");
        assertEquals("m-1", ids.next());
        assertEquals("m-2", ids.next());
        assertEquals("m-3", ids.next());
        assertEquals(3, ids.issued());
    }

    @Test
    void twoGeneratorsDoNotShareState() {
        FakeIds a = new FakeIds("a");
        FakeIds b = new FakeIds("b");
        a.next();
        assertEquals("b-1", b.next());
        assertEquals("a-2", a.next());
    }
}
