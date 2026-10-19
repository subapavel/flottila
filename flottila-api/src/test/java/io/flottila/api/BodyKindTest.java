package io.flottila.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The body-kind declaration's defaults: a step or language that says nothing is taken to need the whole body; a language that cannot split says so. */
class BodyKindTest {

    @Test
    void theDefaultsAreTheSafeReading() {
        StepFactory silent = new StepFactory() {
            @Override public String type() { return "t"; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources resources) { return Stage.of(e -> e); }
        };
        assertEquals(BodyKind.WHOLE, silent.bodyKind());
        Expressions plain = new Expressions() {
            @Override public String language() { return "plain"; }
            @Override public Predicate<Envelope> predicate(String expression, StepResources resources) { return e -> true; }
            @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) { return e -> Optional.empty(); }
        };
        assertEquals(BodyKind.WHOLE, plain.bodyKind());
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> plain.parts("x", null));
        assertTrue(e.getMessage().contains("plain"), e.getMessage());
        assertEquals(List.of(BodyKind.WHOLE, BodyKind.STREAM, BodyKind.SPLIT_ASSEMBLE), List.of(BodyKind.values()));
    }
}
