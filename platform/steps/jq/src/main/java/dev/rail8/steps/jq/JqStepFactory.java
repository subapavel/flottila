package dev.rail8.steps.jq;

import dev.rail8.api.Body;
import dev.rail8.api.BodyTooLargeException;
import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.jq.Bindings;
import dev.rail8.jq.JqEngine;
import dev.rail8.jq.JqException;
import dev.rail8.jq.JqFilter;
import dev.rail8.jq.JqFilterException;
import dev.rail8.jq.JqSink;
import dev.rail8.jq.JqSyntaxException;
import dev.rail8.jq.JqTermination;
import dev.rail8.jq.JsonDoc;
import dev.rail8.jq.JsonWriter;
import dev.rail8.jq.Limits;
import dev.rail8.jq.OutputOptions;
import dev.rail8.jq.Workspace;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import io.vertx.core.buffer.Buffer;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The {@code jq} step: {@code filter}, a jq program written inline in the flow, compiled once when the flow is built and run
 * on each message's body, read whole as one JSON document. The filter gives exactly one result, written as compact UTF-8
 * JSON (jq's {@code -c}, no line feed) under the envelope's {@link Envelope#nextKey() next key} and the flow's body limit;
 * it is the next step's body, with {@code content-type: application/json}, and headers, props and keys carried over.
 *
 * <p>A run has the engine's embedded budgets ({@link Limits#embedded()}) but two: its recursion depth is the step's
 * {@code maxRecursionDepth}, else the installation's, given to the constructor; the bytes it writes are the flow's
 * {@code maxBodyBytes} and the one line feed, so the output held in memory never passes the body limit.
 *
 * <p>A filter that does not compile is a build error naming the step and {@code filter}. At run time: a body that is not
 * one JSON document is a {@link Fault.Kind#MALFORMED} {@link JqStepException}; an error the filter raises, no result, a
 * second result (the run stops there) or a budget run out is an {@link Fault.Kind#INTERNAL} one; an output above the body
 * limit is a {@link BodyTooLargeException}.
 *
 * @since 0.1
 */
public final class JqStepFactory implements StepFactory {

    /** The recursion depth of an installation that sets none: 10 000 live activations. */
    public static final int DEFAULT_MAX_RECURSION_DEPTH = 10_000;

    /** The deepest recursion an installation or a flow may set: jq's own limit, 4 194 304 live activations. */
    public static final int MAX_RECURSION_DEPTH = 4_194_304;

    static final String CONFIG_SCHEMA = """
            {"type":"object","required":["filter"],"properties":{"filter":{"type":"string","minLength":1},\
            "maxRecursionDepth":{"type":"integer","minimum":1,"maximum":4194304}}}""";

    /** The longest part of a filter's error value a failure's text quotes, in characters. */
    private static final int ERROR_TEXT_MAX = 1000;

    private final int maxRecursionDepth;

    /**
     * A factory whose steps recurse at most {@code maxRecursionDepth} live activations unless their flow sets its own.
     *
     * @throws IllegalArgumentException when {@code maxRecursionDepth} is not from 1 to {@link #MAX_RECURSION_DEPTH}
     */
    public JqStepFactory(int maxRecursionDepth) {
        if (maxRecursionDepth < 1 || maxRecursionDepth > MAX_RECURSION_DEPTH) {
            throw new IllegalArgumentException("the jq recursion depth must be from 1 to " + MAX_RECURSION_DEPTH + ", was " + maxRecursionDepth);
        }
        this.maxRecursionDepth = maxRecursionDepth;
    }

    @Override public String type() { return "jq"; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    @Override
    public Stage create(StepConfig step, StepResources resources) {
        String text = step.requireString("filter");
        long maxBodyBytes = resources.maxBodyBytes();
        Limits embedded = Limits.embedded();
        // the output's line feed is written too; a body limit of Long.MAX_VALUE has no room for it and needs none
        long written = maxBodyBytes == Long.MAX_VALUE ? maxBodyBytes : maxBodyBytes + 1;
        Limits limits = new Limits(embedded.steps(), embedded.deadline(), embedded.nestingDepth(), depth(step), embedded.bytesBuilt(),
                written, embedded.regexMatchTime(), embedded.stackBytes());
        JqEngine engine = JqEngine.builder().limits(limits).build();
        JqFilter filter;
        try {
            filter = engine.compile(text);
        } catch (JqException x) {
            throw new IllegalArgumentException(step + ": 'filter' does not compile: " + x.getMessage(), x);
        }
        return Stage.of(e -> {
            byte[] out = run(step, engine, filter, e, maxBodyBytes);
            Body body = resources.blobs().write(e.nextKey(), Buffer.buffer(out), maxBodyBytes).await();
            return e.next(body).withHeader(Envelope.H_CONTENT_TYPE, "application/json");
        });
    }

    /** The step's {@code maxRecursionDepth}, else the installation's. */
    private int depth(StepConfig step) {
        Object value = step.config().get("maxRecursionDepth");
        if (value == null) return maxRecursionDepth;
        if (value instanceof Integer || value instanceof Long) {
            long depth = ((Number) value).longValue();
            if (depth >= 1 && depth <= MAX_RECURSION_DEPTH) return (int) depth;
        }
        throw new IllegalArgumentException(step + ": 'maxRecursionDepth' must be an integer from 1 to " + MAX_RECURSION_DEPTH + ", was " + value);
    }

    /** The one result of {@code filter} on {@code e}'s body as compact JSON without its line feed. */
    private static byte[] run(StepConfig step, JqEngine engine, JqFilter filter, Envelope e, long maxBodyBytes) {
        byte[] in = e.body().whole().getBytes();
        Workspace workspace = engine.newWorkspace();   // one running evaluation at a time: a run of its own
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JqSink writer = JsonWriter.sink(workspace, out, OutputOptions.compact());
        int[] results = {0};
        JqSink first = v -> ++results[0] == 1 && writer.emit(v);   // false at the second result: the run stops
        try (JsonDoc doc = workspace.read(MemorySegment.ofArray(in))) {
            filter.run(doc.root(), Bindings.builder().build(), first, workspace);
        } catch (JqSyntaxException x) {
            throw new JqStepException(e.id(), Fault.Kind.MALFORMED, step + ": the body is not one JSON document: " + x.getMessage(), x);
        } catch (JqFilterException x) {
            throw new JqStepException(e.id(), Fault.Kind.INTERNAL, step + ": the filter raised an error: " + errorText(workspace, x.value()), x);
        } catch (JqTermination x) {
            // the run stopped before the rest was written: the output's size is only known to be past the limit
            if (x.budget() == JqTermination.Budget.BYTES_WRITTEN) throw new BodyTooLargeException(e.nextKey(), maxBodyBytes + 1, maxBodyBytes);
            throw new JqStepException(e.id(), Fault.Kind.INTERNAL, step + ": the filter was stopped: " + x.getMessage(), x);
        }
        if (results[0] != 1) {
            String what = results[0] == 0 ? "no result" : "more than one result";
            throw new JqStepException(e.id(), Fault.Kind.INTERNAL, step + ": the filter gave " + what + "; it must give exactly one", null);
        }
        byte[] line = out.toByteArray();
        return Arrays.copyOf(line, line.length - 1);   // without the line feed
    }

    /** jq's error value as a failure quotes it: a string as it is, any other value as compact JSON; at most {@link #ERROR_TEXT_MAX} characters. */
    private static String errorText(Workspace workspace, JqValue value) {
        String text;
        if (value instanceof JqString s) {
            text = s.value();
        } else {
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            JsonWriter.sink(workspace, json, OutputOptions.compact()).emit(value);   // after the run: no budget applies
            text = json.toString(StandardCharsets.UTF_8).strip();
        }
        return text.length() <= ERROR_TEXT_MAX ? text : text.substring(0, ERROR_TEXT_MAX) + "…";
    }
}
