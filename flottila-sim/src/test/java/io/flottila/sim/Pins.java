package io.flottila.sim;

import static org.junit.jupiter.api.Assertions.fail;

/** Golden values and floors the implementer pins at landing (P2-10a): until then a case reaching one fails naming it. */
final class Pins {

    private Pins() {}

    /** {@code value}; a failed test naming {@code what} while it is still the null placeholder. */
    static <T> T pinned(T value, String what) {
        if (value == null) {
            fail("PLACEHOLDER " + what + ": not pinned yet; the implementer fills it in at landing (P2-10a, D-101, bench/RESULTS.md §P2-10)");
        }
        return value;
    }
}
