package io.flottila.core.record;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** D-057 (a): a page of a bounded listing — never unlimited (D-045), the cursor opaque and owned by the store. */
class PageTest {

    @Test
    void theLargestPageIsFiveHundred() {
        assertEquals(500, Page.MAX_LIMIT);
    }

    @Test
    void requireLimitAnswersTheLimitItChecked() {
        assertEquals(7, Page.requireLimit(7));
        assertEquals(1, Page.requireLimit(1));
        assertEquals(500, Page.requireLimit(500));
        assertThrows(IllegalArgumentException.class, () -> Page.requireLimit(0));
        assertThrows(IllegalArgumentException.class, () -> Page.requireLimit(501));
    }

    @Test
    void aPageKeepsItsOwnCopyOfTheItemsAndRefusesNulls() {
        List<String> source = new ArrayList<>(List.of("m-1", "m-2"));
        Page<String> page = new Page<>(source, Optional.of("cursor"));
        source.add("m-3");
        assertEquals(List.of("m-1", "m-2"), page.items(), "a page is a value: the caller's list may change after");
        assertThrows(UnsupportedOperationException.class, () -> page.items().add("m-4"));
        assertEquals(Optional.of("cursor"), page.next());
        assertThrows(NullPointerException.class, () -> new Page<String>(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new Page<>(List.of("m-1"), null));
    }
}
