package dev.rail8.steps.mapping;

import dev.rail8.api.Body;
import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.mapping.embed.BundleReference;
import dev.rail8.mapping.embed.Mapping;
import dev.rail8.mapping.embed.MappingRefusedException;
import dev.rail8.mapping.embed.RunFailure;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

/**
 * The {@code mapping} step: {@code mapping} names a mapping file of the package (a path from the package root, or
 * {@code <dependency>:<path>}), compiled once when the flow is built and run on each message's body, read whole. The output
 * is written under the envelope's {@link Envelope#nextKey() next key} and the flow's body limit; it is the next step's body,
 * with {@code content-type} from the engine ({@code application/xml}), and headers, props and keys carried over.
 *
 * <p>The mapping file is read with {@link StepResources#file}, and each schema file it lists that is relative to it with
 * {@link StepResources#resolve} against the mapping file, inside the package or dependency that holds the mapping; so the
 * flow's hash covers them all. A mapping the engine refuses (a stale fingerprint among its reasons), a schema file outside
 * that directory, and a package reference ({@code name@version:/path}) are build errors naming the step.
 *
 * <p>A run sees the injected clock's time, in the clock's zone. At run time: a body that is not well-formed is a
 * {@link Fault.Kind#MALFORMED} {@link MappingStepException}; an output above the body limit an {@link Fault.Kind#INVALID}
 * one; a data error an {@link Fault.Kind#INTERNAL} one, which the flow retries. An interrupt is rethrown as the
 * {@link InterruptedException} it is, with the thread's interrupt status set again.
 *
 * @since 0.1
 */
public final class MappingStepFactory implements StepFactory {

    static final String CONFIG_SCHEMA = """
            {"type":"object","required":["mapping"],"additionalProperties":false,\
            "properties":{"mapping":{"type":"string","minLength":1}}}""";

    @Override public String type() { return "mapping"; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    @Override
    public Stage create(StepConfig step, StepResources resources) {
        String name = step.requireString("mapping");
        Path file;
        try {
            file = resources.file(name);
        } catch (IllegalArgumentException x) {
            throw new IllegalArgumentException(step + ": 'mapping' " + x.getMessage(), x);
        }
        byte[] model;
        try {
            model = Files.readAllBytes(file);
        } catch (IOException x) {
            throw new IllegalArgumentException(step + ": cannot read the mapping " + name + ": " + x, x);
        }
        String base = file.toUri().toString();
        Mapping mapping;
        try {
            mapping = Mapping.compile(model, reference -> switch (reference) {
                case BundleReference.Relative(String path) -> Files.readAllBytes(schema(step, resources, name, base, path));
                case BundleReference.Packaged packaged -> throw new IllegalArgumentException(step + ": the mapping " + name
                        + " names the package reference " + packaged.spelling() + ", which a mapping step does not read yet");
            });
        } catch (MappingRefusedException x) {
            String problems = x.problems().stream().map(MappingRefusedException.Problem::toString).collect(Collectors.joining("; "));
            throw new IllegalArgumentException(step + ": the mapping " + name + " is refused: " + problems, x);
        }
        long maxBodyBytes = resources.maxBodyBytes();
        return Stage.of(e -> {
            byte[] out = run(step, mapping, e, maxBodyBytes, OffsetDateTime.now(resources.clock()));
            Body body = resources.blobs().write(e.nextKey(), Buffer.buffer(out), maxBodyBytes).await();
            return e.next(body).withHeader(Envelope.H_CONTENT_TYPE, mapping.mediaType());
        });
    }

    /** The schema file {@code path} of the mapping {@code name}, relative to the mapping file at {@code base}. */
    private static Path schema(StepConfig step, StepResources resources, String name, String base, String path) {
        try {
            return resources.resolve(base, path);
        } catch (IllegalArgumentException x) {
            throw new IllegalArgumentException(step + ": the mapping " + name + " names a schema file outside its package: "
                    + x.getMessage(), x);
        }
    }

    /** The output of {@code mapping} on {@code e}'s body at {@code now}. */
    private static byte[] run(StepConfig step, Mapping mapping, Envelope e, long maxBodyBytes, OffsetDateTime now) {
        byte[] in = e.body().whole().getBytes();
        try {
            return mapping.run(in, maxBodyBytes, now);
        } catch (RunFailure x) {
            Fault.Kind kind = switch (x.reason()) {
                case MALFORMED_INPUT -> Fault.Kind.MALFORMED;
                case OUTPUT_TOO_LARGE -> Fault.Kind.INVALID;
                case DATA_ERROR -> Fault.Kind.INTERNAL;
            };
            throw new MappingStepException(e.id(), kind, step + ": " + x.getMessage(), x);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw MappingStepFactory.<RuntimeException>sneaky(x);
        }
    }

    /** Throws {@code failure}, a checked one too, from a method that declares none. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
