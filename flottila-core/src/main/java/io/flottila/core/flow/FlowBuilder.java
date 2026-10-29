package io.flottila.core.flow;

import io.flottila.api.BodyKind;
import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Pipeline;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.admission.Admission;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.Parking;
import io.flottila.core.pattern.Filter;
import io.flottila.core.pattern.Retry;
import io.flottila.core.pattern.Route;
import io.flottila.core.pattern.Split;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Builds a {@link Pipeline} from a {@link FlowDefinition} (ARCHITECTURE §6): one {@link Stage} per configured
 * step — a built-in ({@code to}, {@code route}, {@code filter}, {@code extractKey}, {@code validate}, {@code split}) or the
 * registered {@link StepFactory} of its type; every stage is wrapped so the envelope it hands on carries the
 * step's number (ingress is 0, the n-th step is n — a stage that only changed headers, props or keys is
 * {@linkplain Envelope#advance() advanced}, one that wrote a body is already there); a {@code retryable} step
 * is wrapped in the flow's {@link RetryPolicy} by {@link Retry}. With a {@link MessageStore} given through
 * {@link #recording(MessageStore, Meter)}, every step is also wrapped by a {@link Recorder} under the flow's
 * {@link RecordingPolicy} (a step's own {@code recording} overrides it) and the pipeline starts with the ingress snapshot.
 * An at-least-once flow parks a failed message in the {@link Dlq} given through {@link #dlq(Dlq)} (ARCHITECTURE §4
 * lifecycle table: sync answers the caller, at-most-once drops) — without one it does not build. {@link #replay} gives
 * the flow's replay: steps {@code n..end} as a new message.
 *
 * <p>{@link #delivery} gives the flow's consumer handler per delivery mode, under the flow's {@link Admission} — at-least-once
 * saves durably through the {@link Intake} given through {@link #intake(Intake)}, by default into the store and the
 * {@link WorkQueue} given through {@link #queue(WorkQueue)} — and {@link #worker} the
 * {@link FlowWorker} that runs what an at-least-once delivery accepted (ARCHITECTURE §4 lifecycle table, D-049, D-055). The steps
 * see every header; a producer receives only the headers the flow's {@code forwardHeaders} name (case-insensitive) plus
 * {@code content-type}, the body's media type, whatever set them — the {@code to} step filters at the producer boundary
 * (ARCHITECTURE Envelope table "nothing propagates implicitly", D-049 e).
 *
 * <p>The built-ins take their conditions and values from a registered {@link Expressions} language
 * ({@code language} per step, default {@code xpath}), so core never parses a body. The {@link Runtime} is
 * used here and nowhere else (CLAUDE.md): factories get a {@link StepResources}, the {@code to} step gets its
 * {@link Producer}s, resolved once at build time from the endpoint's URI scheme. An unknown step type, endpoint,
 * scheme, language or expression is a build error naming the step; nothing is deferred to the first message. So is a
 * step that needs the whole body ({@link BodyKind#WHOLE}) on a flow whose max body exceeds the store's tree limit
 * without a {@code split} before it (ARCHITECTURE §5). A build that fails closes the producers it created before the failure
 * leaves; those of a {@link Delivery}, {@link FlowWorker} or {@link Replay} built are theirs to close ({@code close()}, FIX-30).
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
    private Dlq dlq;
    private WorkQueue queue;
    private Intake intake;   // null: a SequentialIntake over the store and the queue
    private Map<String, String> extraVersions = Map.of();
    private Consumer<Path> reads = path -> { };
    private Map<String, Path> dependencies = Map.of();

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

    /** Parks the failed messages of at-least-once flows in {@code dlq} (ARCHITECTURE §6); such a flow does not build without it. */
    public FlowBuilder dlq(Dlq dlq) {
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        return this;
    }

    /** Queues the accepted messages of at-least-once flows in {@code queue} (ARCHITECTURE §4, §7); their delivery and worker need it. */
    public FlowBuilder queue(WorkQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
        return this;
    }

    /**
     * Saves the messages of at-least-once flows through {@code intake} (D-082): their delivery's durable save — body, ingress
     * record and work item. Without this call it is a {@link SequentialIntake} over the {@link #recording} store and the
     * {@link #queue}; the node's is the Postgres intake, all three in one transaction.
     */
    public FlowBuilder intake(Intake intake) {
        this.intake = Objects.requireNonNull(intake, "intake");
        return this;
    }

    /**
     * Configuration versions every message of the flows built from now on carries beside the flow's own, on its snapshots and
     * DLQ rows — a deploy's {@code package=<name>@<version>} and {@code revision} (D-059); they replace those of an earlier call.
     * The {@code flow} key is the builder's to write: an {@link IllegalArgumentException}.
     */
    public FlowBuilder versions(Map<String, String> extra) {
        Map<String, String> copy = Map.copyOf(extra);
        if (copy.containsKey("flow")) throw new IllegalArgumentException("the 'flow' version is the flow's own id@version, written by the builder; extra versions may not set it: " + copy);
        this.extraVersions = copy;
        return this;
    }

    /**
     * Tells {@code sink} every path the steps of the flows built from now on read through their {@link StepResources} ({@code file},
     * {@code resolve}) while they are built — by {@link #delivery} (sync, at-most-once; at-least-once: its partition key's
     * expression), {@link #worker}, {@link #build} and {@link #replay} — for a flow's effective hash (D-063); it replaces the sink
     * of an earlier call. Without a call nobody is told.
     */
    public FlowBuilder reads(Consumer<Path> sink) {
        this.reads = Objects.requireNonNull(sink, "sink");
        return this;
    }

    /**
     * Gives the steps of the flows built from now on the package's dependencies, each name to the directory its resolved version
     * is unpacked in, as {@link StepResources#dependencies()} (D-087); it replaces the map of an earlier call. Without a call a
     * flow has none.
     */
    public FlowBuilder dependencies(Map<String, Path> dependencies) {
        this.dependencies = Map.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        return this;
    }

    /** Builds the flow; {@code baseDir} is where the steps' relative file references resolve (the package directory, D-078). */
    public Pipeline build(FlowDefinition flow, Path baseDir) {
        return built(flow, baseDir).pipeline(true);
    }

    /**
     * The flow's consumer handler for its delivery mode (see {@link Delivery}), with one {@link Admission} of its own built from
     * {@code limits.maxInFlight} / {@code limits.maxInFlightBytes} (defaults: twice the available processors, and that many
     * bodies of {@code maxBodyBytes} — a product that overflows a {@code long} is an {@link IllegalArgumentException}; never
     * unlimited). An at-least-once flow needs the store ({@link #recording}), the {@link #queue} and the {@link #dlq}: without
     * one of them this is an {@link IllegalStateException} naming it. Its handler only saves, so only its recorder is built
     * here — no step, no producer; the {@link #worker} builds the pipeline. With {@code partitionBy: <key>} it also compiles the
     * expression of the one {@code extractKey} step that sets that key, in the step's language, and enqueues each message under
     * its value (D-075); the flow does not build — an {@link IllegalArgumentException} naming the key — when no such step or two
     * of them exist, when a {@code split} or {@code to} step comes before it (the key is read from the body as it arrived), or
     * when its language needs the whole body and the flow's {@code maxBodyBytes} exceeds the store's tree limit. On a sync or
     * at-most-once flow {@code partitionBy} has no effect. No dedupe key: {@link #delivery(FlowDefinition, Path, Optional)} with
     * none.
     */
    public Delivery delivery(FlowDefinition flow, Path baseDir) {
        return delivery(flow, baseDir, Optional.empty());
    }

    /**
     * {@link #delivery(FlowDefinition, Path)} whose at-least-once save dedupes by the prop {@code dedupeBy} names (D-084), the one
     * the flow's in endpoint's component names ({@link Component#dedupeBy}): its value on each envelope as it
     * arrived is the intake's dedupe key, scoped by the flow's id — no value or {@code ""} is none. Empty: no key. A sync or
     * at-most-once flow saves nothing, so ignores it.
     */
    public Delivery delivery(FlowDefinition flow, Path baseDir, Optional<Key<String>> dedupeBy) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(dedupeBy, "dedupeBy");
        Admission admission = admission(flow);
        return switch (flow.deliveryMode()) {
            case "sync" -> {
                Built built = built(flow, baseDir);
                yield Delivery.sync(admission, built.pipeline(true), built.producers(), runtime.timer());
            }
            case "at-most-once" -> {
                Built built = built(flow, baseDir);
                yield Delivery.atMostOnce(admission, built.pipeline(true), built.producers(), runtime.timer());
            }
            case "at-least-once" -> {
                requireStoreAndQueue(flow, "delivery");
                requireDlq(flow);
                Function<Envelope, Optional<String>> partitionKey = partitionKey(flow, baseDir);
                Recorder recorder = new Recorder(store, runtime.clock(), meter, versionsOf(flow));
                yield Delivery.atLeastOnce(admission, flow.id(), e -> recorder.startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT),
                        partitionKey, e -> dedupeBy.flatMap(e::get), intake != null ? intake : new SequentialIntake(store, queue), runtime.timer(),
                        flow.saveTimeout());
            }
            default -> throw new IllegalArgumentException("flow '" + flow.id() + "': unknown delivery.mode '" + flow.deliveryMode() + "'");
        };
    }

    /**
     * The value an at-least-once flow's work items are ordered by (D-075): none without {@code partitionBy}; else the expression
     * of the one extractKey step that sets its key, compiled in the step's language — an {@link IllegalArgumentException} when
     * the flow does not build so (see {@link #delivery}).
     */
    private Function<Envelope, Optional<String>> partitionKey(FlowDefinition flow, Path baseDir) {
        if (flow.partitionBy().isEmpty()) return e -> Optional.empty();   // unordered
        String key = flow.partitionBy().get();
        String refused = "flow '" + flow.id() + "' has partitionBy '" + key + "'";
        List<StepConfig> setters = flow.steps().stream().filter(s -> "extractKey".equals(s.type()) && key.equals(s.config().get("key"))).toList();
        if (setters.isEmpty()) throw new IllegalArgumentException(refused + " but no extractKey step sets the key '" + key + "'");
        if (setters.size() > 1) throw new IllegalArgumentException(refused + " but " + setters.size() + " extractKey steps set it, " + setters + ": one step sets the key");
        StepConfig source = setters.get(0);
        for (StepConfig before : flow.steps().subList(0, source.index() - 1)) {
            if ("split".equals(before.type()) || "to".equals(before.type())) {
                throw new IllegalArgumentException(refused + ": " + before + " comes before " + source + ", but the key is read from the body as it arrived");
            }
        }
        Expressions language = language(source);
        long treeLimit = runtime.blobs().treeLimit();
        if (language.bodyKind() == BodyKind.WHOLE && flow.maxBodyBytes() > treeLimit) {
            throw new IllegalArgumentException(refused + ": " + source + " needs the whole body (" + BodyKind.WHOLE + ") but the flow's maxBodyBytes "
                    + flow.maxBodyBytes() + " exceeds the store's tree limit " + treeLimit + " (ARCHITECTURE §5)");
        }
        return language.value(source.requireString("xpath"), resources(flow, baseDir));
    }

    /** {@link #worker(FlowDefinition, Path, String, WorkerPolicy)} under {@link WorkerPolicy#DEFAULT}. */
    public FlowWorker worker(FlowDefinition flow, Path baseDir, String workerId) {
        return worker(flow, baseDir, workerId, WorkerPolicy.DEFAULT);
    }

    /**
     * The worker {@code workerId} of an at-least-once flow (see {@link FlowWorker}) under {@code policy}: runs at most the flow's
     * in-flight request limit of items at once and the pipeline without stage 0, leases and polls on the runtime's timer and
     * parks an item past {@link WorkerPolicy#maxAttempts()} at the flow's first step. Needs the store, the {@link #queue} and
     * the {@link #dlq}.
     */
    public FlowWorker worker(FlowDefinition flow, Path baseDir, String workerId, WorkerPolicy policy) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(policy, "policy");
        requireStoreAndQueue(flow, "worker");
        requireDlq(flow);
        int max = (int) Math.min(inFlightRequests(flow), Integer.MAX_VALUE);
        Parking parking = new Parking(dlq, runtime.clock(), versionsOf(flow));
        Built built = built(flow, baseDir);
        return new FlowWorker(flow.id(), workerId, max, policy, queue, store, runtime.blobs(), built.pipeline(false),
                parking, flow.steps().get(0), runtime.timer(), built.producers());
    }

    private void requireStoreAndQueue(FlowDefinition flow, String what) {
        if (store == null) throw new IllegalStateException("flow '" + flow.id() + "': the at-least-once " + what + " needs the message store for its durable save: call recording(store, meter) first");
        if (queue == null) throw new IllegalStateException("flow '" + flow.id() + "': the at-least-once " + what + " needs a work queue: call queue(...) first");
    }

    private void requireDlq(FlowDefinition flow) {
        if (dlq == null) throw new IllegalStateException("flow '" + flow.id() + "' is at-least-once and needs a DLQ to park failed messages in: call dlq(...) first");
    }

    /** The configuration versions a message runs with, on its snapshots and DLQ rows: the flow's own and those of {@link #versions(Map)}. */
    private Map<String, String> versionsOf(FlowDefinition flow) {
        Map<String, String> all = new HashMap<>(extraVersions);
        all.put("flow", flow.id() + "@" + flow.version());
        return Map.copyOf(all);
    }

    /** A new {@link Admission} from the flow's limits (see {@link #delivery}); never unlimited. */
    private static Admission admission(FlowDefinition flow) {
        long requests = inFlightRequests(flow);
        return new Admission(requests, flow.maxInFlightBytes().orElseGet(() -> defaultInFlightBytes(flow, requests)));
    }

    /** The default byte budget: {@code requests} bodies of {@code maxBodyBytes}; one that does not fit a {@code long} is a configuration error. */
    private static long defaultInFlightBytes(FlowDefinition flow, long requests) {
        try {
            return Math.multiplyExact(requests, flow.maxBodyBytes());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("flow '" + flow.id() + "': limits.maxInFlight " + requests + " × limits.maxBodyBytes " + flow.maxBodyBytes()
                    + " does not fit a long; set limits.maxInFlightBytes or lower one of them", overflow);
        }
    }

    /** {@code limits.maxInFlight}, or twice the available processors (ARCHITECTURE §4 threading); never unlimited. */
    private static long inFlightRequests(FlowDefinition flow) {
        return flow.maxInFlight().orElseGet(() -> 2L * java.lang.Runtime.getRuntime().availableProcessors());
    }

    /** The envelope as a producer gets it: only the {@code allowed} headers (case-insensitive) and {@code content-type}, whatever set them. */
    static UnaryOperator<Envelope> forwardHeaders(List<String> allowed) {
        Set<String> names = new HashSet<>();
        for (String name : allowed) names.add(name.toLowerCase(Locale.ROOT));
        names.add(Envelope.H_CONTENT_TYPE);
        return e -> {
            Map<String, String> kept = new HashMap<>();
            e.headers().asMap().forEach((name, value) -> { if (names.contains(name.toLowerCase(Locale.ROOT))) kept.put(name, value); });
            return new Envelope(e.id(), e.flow(), e.step(), new Headers(kept), e.props(), e.keys(), e.body(), e.attachments(), e.views());
        };
    }

    /**
     * The flow's replay with an {@link Admission} of its own, built from the flow's limits as a {@link #delivery}'s is — no
     * unbounded path; see {@link #replay(FlowDefinition, Path, Delivery)}.
     */
    public Replay replay(FlowDefinition flow, Path baseDir) {
        Objects.requireNonNull(flow, "flow");
        return replay(flow, baseDir, admission(flow));
    }

    /**
     * The flow's replay, its runs admitted by {@code delivery}'s {@link Admission}: a replay in flight holds one of the permits
     * the consumer's deliveries take, and the other way round (D-067). Needs the recorded snapshots ({@link #recording}) and, for
     * DLQ retries, the {@link #dlq}. Built once, here: a replay slices the stages built now and holds nothing of this builder or
     * the {@link Runtime}; the producers its steps send through are its own, closed by {@link Replay#close()}.
     */
    public Replay replay(FlowDefinition flow, Path baseDir, Delivery delivery) {
        Objects.requireNonNull(flow, "flow");
        return replay(flow, baseDir, delivery.admission());
    }

    private Replay replay(FlowDefinition flow, Path baseDir, Admission admission) {
        if (store == null) throw new IllegalStateException("replay needs the recorded snapshots: call recording(store, meter) first");
        Built built = built(flow, baseDir);
        List<Stage> steps = built.steps();
        Recorder recorder = built.recorder();
        RecordingPolicy policy = flow.recording();
        return new Replay(flow, from -> {
            List<Stage> tail = new ArrayList<>(steps.size() - from + 2);
            tail.add(recorder.start(policy, Replay.REPLAY_INPUT));
            tail.addAll(steps.subList(from - 1, steps.size()));
            return Pipeline.of(tail);
        }, store, dlq, runtime.blobs(), runtime.ids(), admission, built.producers());
    }

    /**
     * The flow's steps as stages, wrapped (retry, numbering, recording, parking), without the start stage; the recorder (null
     * without a store) and the producers the steps send through.
     */
    private record Built(FlowDefinition flow, List<Stage> steps, Recorder recorder, Producers producers) {
        /** The flow's pipeline: the ingress snapshot ({@code withIngress}, when recording), then the steps. */
        Pipeline pipeline(boolean withIngress) {
            List<Stage> stages = new ArrayList<>(steps.size() + 1);
            if (withIngress && recorder != null) stages.add(recorder.start(flow.recording(), Recorder.INGRESS));
            stages.addAll(steps);
            return Pipeline.of(stages);
        }
    }

    /** The flow built; a build that throws closes every producer it created before the failure leaves (FIX-30), unchecked ones included. */
    private Built built(FlowDefinition flow, Path baseDir) {
        Objects.requireNonNull(flow, "flow");
        Map<String, String> versions = versionsOf(flow);
        boolean parks = "at-least-once".equals(flow.deliveryMode());
        if (parks) requireDlq(flow);
        Parking parking = parks ? new Parking(dlq, runtime.clock(), versions) : null;
        StepResources resources = resources(flow, baseDir);
        Recorder recorder = store == null ? null : new Recorder(store, runtime.clock(), meter, versions);
        UnaryOperator<Envelope> forward = forwardHeaders(flow.forwardHeaders());
        List<Stage> stages = new ArrayList<>(flow.steps().size());
        int lastIndex = flow.steps().size();
        long treeLimit = runtime.blobs().treeLimit();
        boolean splitBefore = false;
        List<Producer> opened = new ArrayList<>();
        boolean done = false;
        try {
            for (StepConfig step : flow.steps()) {
                BodyKind kind = switch (step.type()) {
                    case "to" -> BodyKind.STREAM;
                    case "split" -> language(step).bodyKind() == BodyKind.WHOLE ? BodyKind.WHOLE : BodyKind.SPLIT_ASSEMBLE;   // a splitter over the tree is itself a whole-body step
                    case "route", "filter", "extractKey", "validate" -> language(step).bodyKind();
                    default -> factoryFor(step).bodyKind();
                };
                if (kind == BodyKind.WHOLE && flow.maxBodyBytes() > treeLimit && !splitBefore) {
                    throw new IllegalStateException(step + " needs the whole body (" + kind + ") but the flow's maxBodyBytes " + flow.maxBodyBytes()
                            + " exceeds the store's tree limit " + treeLimit + " and no split precedes it (ARCHITECTURE §5)");
                }
                if (kind == BodyKind.SPLIT_ASSEMBLE) splitBefore = true;
                Stage stage = switch (step.type()) {
                    case "to" -> to(step, forward, opened);
                    case "route" -> route(step, resources);
                    case "filter" -> new Filter(predicate(step, step.requireString("when"), resources));
                    case "extractKey" -> extractKey(step, resources);
                    case "validate" -> validate(step, resources);
                    case "split" -> new Split(language(step).parts(step.requireString("xpath"), resources), runtime.ids());
                    default -> factoryFor(step).create(step, resources);
                };
                if (step.retryable()) stage = Retry.stage(stage, flow.retry(), runtime.timer());
                stage = numbered(stage, step);
                if (recorder != null) stage = recorder.wrap(stage, step, step.index() == lastIndex, RecordingPolicy.from(step.config().get("recording"), flow.recording()));
                if (parking != null) stage = parking.wrap(stage, step);
                stages.add(stage);
            }
            done = true;
        } finally {
            if (!done) Producers.closeAll(flow.id(), opened);   // the flow does not build: nothing it created is left open
        }
        return new Built(flow, List.copyOf(stages), recorder, new Producers(flow.id(), opened));
    }

    /** What a step of {@code flow} is built with; the paths it reads go to the {@link #reads} sink. */
    private StepResources resources(FlowDefinition flow, Path baseDir) {
        return new StepResources(runtime.clock(), runtime.timer(), runtime.ids(), runtime.blobs(), runtime.hasher(), flow.maxBodyBytes(), baseDir, reads, dependencies);
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

    /**
     * The built-in {@code to}: {@code endpoint}, or {@code byProp} + {@code endpoints} (the route's decision picks the producer).
     * The producer gets the envelope through {@code forward} (the flow's {@code forwardHeaders}); what goes on is its reply.
     * Every producer created is added to {@code opened} at once.
     */
    private Stage to(StepConfig step, UnaryOperator<Envelope> forward, List<Producer> opened) {
        if (step.config().get("endpoint") != null) {
            Producer producer = producer(step, step.requireString("endpoint"), opened);
            return Stage.of(e -> producer.send(forward.apply(e)).await());
        }
        Key<String> prop = new Key<>(step.requireString("byProp"));
        if (!(step.config().get("endpoints") instanceof Map<?, ?> choices) || choices.isEmpty()) {
            throw new IllegalArgumentException(step + ": 'endpoints' must map every value of " + prop.name() + " to an endpoint");
        }
        Map<String, Producer> producers = new LinkedHashMap<>();
        choices.forEach((choice, endpoint) -> producers.put(String.valueOf(choice), producer(step, String.valueOf(endpoint), opened)));
        return Stage.of(e -> {
            String choice = e.get(prop).orElseThrow(() -> new IllegalStateException(step + ": prop " + prop.name() + " is not set; a route step before it decides"));
            Producer producer = producers.get(choice);
            if (producer == null) throw new IllegalStateException(step + ": no endpoint for " + prop.name() + " = '" + choice + "' (" + producers.keySet() + ")");
            return producer.send(forward.apply(e)).await();
        });
    }

    private Producer producer(StepConfig step, String endpoint, List<Producer> opened) {
        EndpointConfig cfg = endpoints.get(endpoint);
        if (cfg == null) throw new IllegalArgumentException(step + ": unknown endpoint '" + endpoint + "' (" + endpoints.keySet() + ")");
        Component component;
        try {
            component = runtime.component(cfg.scheme());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(step + ": endpoint '" + endpoint + "' " + cfg.uri() + ": " + e.getMessage(), e);
        }
        Producer producer = component.producer(cfg);
        opened.add(producer);
        return producer;
    }
}
