package io.flottila.core.flow;

import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Key;
import io.flottila.api.Pipeline;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.pattern.Filter;
import io.flottila.core.pattern.Retry;
import io.flottila.core.pattern.Route;
import io.flottila.core.record.Meter;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builds a {@link Pipeline} from a {@link FlowDefinition} (ARCHITECTURE §6): one {@link Stage} per configured
 * step — a built-in ({@code to}, {@code route}, {@code filter}, {@code extractKey}, {@code validate}) or the
 * registered {@link StepFactory} of its type; every stage is wrapped so the envelope it hands on carries the
 * step's number (ingress is 0, the n-th step is n — a stage that only changed headers, props or keys is
 * {@linkplain Envelope#advance() advanced}, one that wrote a body is already there); a {@code retryable} step
 * is wrapped in the flow's {@link RetryPolicy} by {@link Retry}. With a {@link MessageStore} given through
 * {@link #recording(MessageStore, Meter)}, every step is also wrapped by a {@link Recorder} under the flow's
 * {@link RecordingPolicy} (a step's own {@code recording} overrides it) and the pipeline starts with the ingress snapshot.
 *
 * <p>The built-ins take their conditions and values from a registered {@link Expressions} language
 * ({@code language} per step, default {@code xpath}), so core never parses a body. The {@link Runtime} is
 * used here and nowhere else (CLAUDE.md): factories get a {@link StepResources}, the {@code to} step gets its
 * {@link Producer}s, resolved once at build time from the endpoint's URI scheme. An unknown step type, endpoint,
 * scheme, language or expression is a build error naming the step; nothing is deferred to the first message.
 *
 * @since 0.1
 */
public final class FlowBuilder {

    /** The expression language a step uses when it names none. */
    public static final String DEFAULT_LANGUAGE = "xpath";

    private final Runtime runtime;
    private final Map<String, StepFactory> factories = new LinkedHashMap<>();
    private final Map<String, Expressions> languages = new LinkedHashMap<>();
    private final Map<String, EndpointConfig> endpoints;
    private MessageStore store;
    private Meter meter;

    public FlowBuilder(Runtime runtime, Collection<StepFactory> factories, Collection<Expressions> languages, Map<String, EndpointConfig> endpoints) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        for (StepFactory f : factories) {
            if (this.factories.putIfAbsent(f.type(), f) != null) throw new IllegalArgumentException("two step factories for type '" + f.type() + "'");
        }
        for (Expressions l : languages) {
            if (this.languages.putIfAbsent(l.language(), l) != null) throw new IllegalArgumentException("two expression languages named '" + l.language() + "'");
        }
        this.endpoints = Map.copyOf(endpoints);
    }

    /** Records a snapshot per step into {@code store} (ARCHITECTURE §6); without this call nothing is recorded. */
    public FlowBuilder recording(MessageStore store, Meter meter) {
        this.store = Objects.requireNonNull(store, "store");
        this.meter = Objects.requireNonNull(meter, "meter");
        return this;
    }

    /** Builds the flow; {@code baseDir} is where the steps' relative file references resolve (the flow file's directory). */
    public Pipeline build(FlowDefinition flow, Path baseDir) {
        Objects.requireNonNull(flow, "flow");
        StepResources resources = new StepResources(runtime.clock(), runtime.timer(), runtime.ids(), runtime.blobs(), runtime.hasher(), flow.maxBodyBytes(), baseDir);
        Recorder recorder = store == null ? null : new Recorder(store, runtime.clock(), meter, Map.of("flow", flow.id() + "@" + flow.version()));
        List<Stage> stages = new ArrayList<>(flow.steps().size() + 1);
        if (recorder != null) stages.add(recorder.ingress(flow.recording()));
        int lastIndex = flow.steps().size();
        for (StepConfig step : flow.steps()) {
            Stage stage = switch (step.type()) {
                case "to" -> to(step);
                case "route" -> route(step, resources);
                case "filter" -> new Filter(predicate(step, step.requireString("when"), resources));
                case "extractKey" -> extractKey(step, resources);
                case "validate" -> validate(step, resources);
                default -> factoryFor(step).create(step, resources);
            };
            if (step.retryable()) stage = Retry.stage(stage, flow.retry(), runtime.timer());
            stage = numbered(stage, step);
            if (recorder != null) stage = recorder.wrap(stage, step, step.index() == lastIndex, RecordingPolicy.from(step.config().get("recording"), flow.recording()));
            stages.add(stage);
        }
        return Pipeline.of(stages);
    }

    private StepFactory factoryFor(StepConfig step) {
        StepFactory f = factories.get(step.type());
        if (f == null) throw new IllegalArgumentException(step + ": unknown step type '" + step.type() + "'");
        return f;
    }

    /** The envelope a stage hands down is at the step's number: advanced if the stage left it where it was, refused if it went past. */
    static Stage numbered(Stage stage, StepConfig step) {
        int index = step.index();
        return (e, rest) -> stage.apply(e, out -> {
            if (out.step() > index) throw new IllegalStateException(step + " handed on an envelope at step " + out.step());
            Envelope at = out;
            while (at.step() < index) at = at.advance();
            return rest.apply(at);
        });
    }

    /** {@code route}: {@code prop}, {@code routes[]} of {@code when} / {@code set}, optional {@code otherwise}. */
    private Stage route(StepConfig step, StepResources resources) {
        Key<String> prop = new Key<>(step.requireString("prop"));
        if (!(step.config().get("routes") instanceof List<?> routes) || routes.isEmpty()) throw new IllegalArgumentException(step + ": 'routes' must be a non-empty list");
        List<Route.Branch> branches = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            if (!(routes.get(i) instanceof Map<?, ?> r) || !(r.get("when") instanceof String when) || !(r.get("set") instanceof String set)) {
                throw new IllegalArgumentException(step + ": routes[" + i + "] needs 'when' and 'set'");
            }
            branches.add(new Route.Branch(set, predicate(step, when, resources)));
        }
        return new Route(prop, branches, step.string("otherwise"));
    }

    /** {@code extractKey}: {@code key} from the value of {@code xpath}; a message without the value keeps no key and goes on. */
    private Stage extractKey(StepConfig step, StepResources resources) {
        String key = step.requireString("key");
        Function<Envelope, Optional<String>> value = language(step).value(step.requireString("xpath"), resources);
        return Stage.of(e -> value.apply(e).map(v -> e.withKey(key, v)).orElse(e));
    }

    /** {@code validate}: the message goes on when {@code required} holds, else it fails with {@link ValidationFailedException}. */
    private Stage validate(StepConfig step, StepResources resources) {
        String required = step.requireString("required");
        Predicate<Envelope> holds = predicate(step, required, resources);
        return Stage.of(e -> {
            if (!holds.test(e)) throw new ValidationFailedException(step.name(), required);
            return e;
        });
    }

    private Predicate<Envelope> predicate(StepConfig step, String expression, StepResources resources) {
        return language(step).predicate(expression, resources);
    }

    private Expressions language(StepConfig step) {
        String name = step.string("language").orElse(DEFAULT_LANGUAGE);
        Expressions l = languages.get(name);
        if (l == null) throw new IllegalArgumentException(step + ": no expression language '" + name + "' is registered (" + languages.keySet() + ")");
        return l;
    }

    /** The built-in {@code to}: {@code endpoint}, or {@code byProp} + {@code endpoints} (the route's decision picks the producer). */
    private Stage to(StepConfig step) {
        if (step.config().get("endpoint") != null) {
            Producer producer = producer(step, step.requireString("endpoint"));
            return Stage.of(e -> producer.send(e).await());
        }
        Key<String> prop = new Key<>(step.requireString("byProp"));
        if (!(step.config().get("endpoints") instanceof Map<?, ?> choices) || choices.isEmpty()) {
            throw new IllegalArgumentException(step + ": 'endpoints' must map every value of " + prop.name() + " to an endpoint");
        }
        Map<String, Producer> producers = new LinkedHashMap<>();
        choices.forEach((choice, endpoint) -> producers.put(String.valueOf(choice), producer(step, String.valueOf(endpoint))));
        return Stage.of(e -> {
            String choice = e.get(prop).orElseThrow(() -> new IllegalStateException(step + ": prop " + prop.name() + " is not set; a route step before it decides"));
            Producer producer = producers.get(choice);
            if (producer == null) throw new IllegalStateException(step + ": no endpoint for " + prop.name() + " = '" + choice + "' (" + producers.keySet() + ")");
            return producer.send(e).await();
        });
    }

    private Producer producer(StepConfig step, String endpoint) {
        EndpointConfig cfg = endpoints.get(endpoint);
        if (cfg == null) throw new IllegalArgumentException(step + ": unknown endpoint '" + endpoint + "' (" + endpoints.keySet() + ")");
        Component component;
        try {
            component = runtime.component(cfg.scheme());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(step + ": endpoint '" + endpoint + "' " + cfg.uri() + ": " + e.getMessage(), e);
        }
        return component.producer(cfg);
    }
}
