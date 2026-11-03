package io.flottila.sim;

import io.flottila.api.Body;
import io.flottila.api.BodyKind;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Stage;
import io.flottila.api.Step;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.vertx.core.buffer.Buffer;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What the simulation's flows are built from besides the built-ins (D-101): the {@code sim-transform} step, which reverses the
 * body's bytes into the next step's body (through {@link World#transform}), and the {@code sim} expression language, whose
 * expression names the header a value is read from (the at-least-once flow's partition key).
 */
final class SimSteps {

    static final String TRANSFORM = "sim-transform";
    static final String LANGUAGE = "sim";

    private SimSteps() {}

    /** The body's bytes reversed: what the transform step writes, and what the oracle expects of it. */
    static Buffer reversed(Buffer in) {
        byte[] b = in.getBytes();
        for (int i = 0, j = b.length - 1; i < j; i++, j--) {
            byte t = b[i];
            b[i] = b[j];
            b[j] = t;
        }
        return Buffer.buffer(b);
    }

    /** The {@code sim-transform} step factory: each stage built is the world's view of the simulation's step. */
    static StepFactory transform(World world) {
        return new StepFactory() {
            @Override public String type() { return TRANSFORM; }

            @Override public String configSchema() { return "{}"; }

            @Override public Stage create(StepConfig step, StepResources resources) {
                Step sim = e -> {
                    Body body = resources.blobs().write(e.nextKey(), reversed(e.body().whole()), resources.maxBodyBytes()).await();
                    return e.next(body);
                };
                return Stage.of(world.transform(sim));
            }
        };
    }

    /** The {@code sim} language: an expression is a header name; a value is that header, a condition that it is set. */
    static Expressions language() {
        return new Expressions() {
            @Override public String language() { return LANGUAGE; }

            @Override public Predicate<Envelope> predicate(String expression, StepResources resources) {
                return e -> e.header(expression).isPresent();
            }

            @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) {
                return e -> e.header(expression);
            }

            @Override public BodyKind bodyKind() { return BodyKind.STREAM; }
        };
    }
}
