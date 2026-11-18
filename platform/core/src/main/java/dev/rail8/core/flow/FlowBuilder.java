package dev.rail8.core.flow;

import dev.rail8.api.BodyKind;
import dev.rail8.api.Component;
import dev.rail8.api.Deadline;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Expressions;
import dev.rail8.api.Headers;
import dev.rail8.api.Key;
import dev.rail8.api.NackException;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Producer;
import dev.rail8.api.Runtime;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.api.Timer;
import dev.rail8.core.admission.Admission;
import dev.rail8.core.admission.TargetLimit;
import dev.rail8.core.admission.Targets;
import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.dlq.Parking;
import dev.rail8.core.node.Fence;
import dev.rail8.core.node.FencedException;
import dev.rail8.core.pattern.Filter;
import dev.rail8.core.pattern.Retry;
import dev.rail8.core.pattern.Route;
import dev.rail8.core.pattern.Split;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.MessageStore;
import dev.rail8.core.record.Recorder;
import dev.rail8.core.record.RecordingPolicy;
import dev.rail8.core.record.StepEvent;
import dev.rail8.core.scope.MessageContext;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Builds a {@link Pipeline} from a {@link FlowDefinition}: one {@link Stage} per configured step, either a built-in
 * ({@code to}, {@code route}, {@code filter}, {@code extractKey}, {@code validate}, {@code split}) or the registered
 * {@link StepFactory} of its type.
 *
 * <p>Every stage is wrapped so that the envelope it hands on carries the step's number: ingress is 0 and the n-th step
 * is n. A stage that only changed headers, props or keys is {@linkplain Envelope#advance() advanced}; one that wrote a
 * body is already at its number. A {@code retryable} step is wrapped in the flow's {@link RetryPolicy} by
 * {@link Retry}. With a {@link MessageStore} given through {@link #recording(MessageStore, Meter)}, every step is also
 * wrapped by a {@link Recorder} under the flow's {@link RecordingPolicy} (a step's own {@code recording} overrides it),
 * and the pipeline starts with the ingress snapshot. With {@link #stepEvents stepEvents(true)} every step is marked
 * outermost by a {@link StepEvent}, whatever else wraps it; the start stage is not marked. An at-least-once flow parks
 * a failed message in the {@link Dlq} given through {@link #dlq(Dlq)} and does not build without one; a sync flow
 * answers its caller and an at-most-once flow drops the message.
 *
 * <p>{@link #delivery} gives the flow's consumer handler for its delivery mode, under the flow's {@link Admission}. An
 * at-least-once delivery saves each message durably through the {@link Intake} given through {@link #intake(Intake)},
 * by default into the store and the {@link WorkQueue} given through {@link #queue(WorkQueue)}; {@link #worker} gives
 * the {@link FlowWorker} that runs what such a delivery accepted.
 *
 * <p>The steps see every header. A producer receives only the headers named by the flow's {@code forwardHeaders}
 * (case-insensitive) plus {@code content-type}, whatever set them: the {@code to} step filters them at the producer
 * boundary, so nothing reaches a partner unless the flow says so.
 *
 * <p>Each {@code to} sends under the limit of the target its endpoint's component names ({@link Component#target}),
 * taken from the {@link Targets} given through {@link #targets(Targets)}; a component that names no target gets an
 * unshared limit with the defaults. The limit wraps only the producer's send, inside {@link Retry} (so no permit is
 * held across a backoff) and inside the {@link Recorder} (so the wait counts in the step's time). A permit not granted
 * within the earlier of the limit's maxWait and the time left until the message's deadline fails the send with a
 * retryable {@link dev.rail8.api.NackException}; a send with no {@link MessageContext} bound waits maxWait. Under the
 * permit the producer is handed the time the send has left as a live {@link Deadline}, until the message's deadline; when
 * no time is left the send fails with a retryable {@link dev.rail8.api.NackException} and the producer is not called. A
 * send with no {@link MessageContext} bound is the producer's plain {@code send(e)}.
 *
 * <p>A {@link #worker}'s {@code to} sends only while the node's fence given through {@link #fence} is open, checked
 * under the permit just before the producer is called; when it is closed the send fails with a {@link FencedException},
 * which no {@link Retry} retries and no {@link Parking} parks. This keeps a node that lost its lease from sending
 * messages another node now owns. A {@link Fence} also bounds the worker's sends in time: the deadline is the earlier of
 * the message's and the fence's, and a timer at the fence's end re-reads it and fails the run's wait with a
 * {@link FencedException} once it is closed. The item is then left unsettled, and the abandoned send keeps its permit
 * until its own future settles. A producer failure that lands while the fence is closed becomes a
 * {@link FencedException} carrying it. A worker's failed run is deferred or parked only while the same fence is open;
 * when it is closed, the item is left unsettled, with no defer and no DLQ row ({@link FlowWorker}). A sync or
 * at-most-once run is never fenced, because no other node takes it over.
 *
 * <p>Every pipeline built (by {@link #build}, a sync or at-most-once {@link #delivery}, or a {@link #worker}) starts with
 * the {@link Runs#head head} of the node's {@link #runs}: it binds the message's {@link MessageContext} for the rest of the
 * run, with a deadline of the run's start on the Runtime's clock, taken when the stage runs, plus the flow's
 * {@code delivery.timeout}, fresh for each run: each sync call and each at-least-once claim. At that deadline, or at an
 * operator's cancel, the run stops: every step is {@linkplain Runs#guard guarded}, so the step running is interrupted and
 * no later one starts, and no send starts after it.
 *
 * <p>The built-ins take their conditions and values from a registered {@link Expressions} language ({@code language}
 * per step, default {@code xpath}), so core never parses a body. This class is the only user of the {@link Runtime}:
 * factories get a {@link StepResources}, and the {@code to} step gets its {@link Producer}s, resolved once at build time
 * from the endpoint's URI scheme. An unknown step type, endpoint, scheme, language or expression is a build error naming
 * the step; nothing is left to fail on the first message. So is a step that needs the whole body
 * ({@link BodyKind#WHOLE}) on a flow whose max body exceeds the store's tree limit with no {@code split} before it. A build
 * that fails closes the producers it created before the exception leaves; the producers of a built {@link Delivery} or
 * {@link FlowWorker} belong to it and are closed by its {@code close()}.
 *
 * @since 0.1
 */
public final class FlowBuilder {

    /** The expression language a step uses when it names none. */
    public static final String DEFAULT_LANGUAGE = "xpath";

    /** The step types this builder builds itself; no {@link StepFactory} may register one of them. */
    public static final Set<String> BUILT_INS = Set.of("to", "route", "filter", "extractKey", "validate", "split");

    /** The fence of an unfenced run: always open. */
    private static final BooleanSupplier OPEN = () -> true;

    /** The node's default schedule of retries when none is configured: 3 retries 5 minutes apart, no growth. */
    public static final RetryPolicy DEFAULT_RETRIES = new RetryPolicy(3, Duration.ofMinutes(5), Duration.ofMinutes(5), 1.0);

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
    private boolean stepEvents;   // false: no step is marked
    private Targets targets;
    private BooleanSupplier fence = OPEN;
    private Workers workers = new Workers();
    private WorkSignals signals = WorkSignals.NONE;
    private RetryPolicy retries = DEFAULT_RETRIES;
    private Runs runs;

    public FlowBuilder(Runtime runtime, Collection<StepFactory> factories, Collection<Expressions> languages, Map<String, EndpointConfig> endpoints) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        for (StepFactory f : factories) {
            if (BUILT_INS.contains(f.type())) throw new IllegalArgumentException("a step factory for type '" + f.type() + "', which is built in");
            if (this.factories.putIfAbsent(f.type(), f) != null) throw new IllegalArgumentException("two step factories for type '" + f.type() + "'");
        }
        for (Expressions l : languages) {
            if (this.languages.putIfAbsent(l.language(), l) != null) throw new IllegalArgumentException("two expression languages named '" + l.language() + "'");
        }
        this.endpoints = Map.copyOf(endpoints);
        this.targets = new Targets(Targets.DEFAULTS, Map.of(), runtime.timer());
        this.runs = new Runs(runtime.clock(), runtime.timer(), WorkerPolicy.DEFAULT.lease());
    }

    /** Records a snapshot per step into {@code store}, metered by {@code meter}; without this call nothing is recorded. */
    public FlowBuilder recording(MessageStore store, Meter meter) {
        this.store = Objects.requireNonNull(store, "store");
        this.meter = Objects.requireNonNull(meter, "meter");
        return this;
    }

    /**
     * Limits the sends of every {@code to} built from now on by the target its component names, through {@code targets}: the
     * node's, shared by every flow it builds. Without this call the builder has a {@link Targets} of its own with the
     * {@linkplain Targets#DEFAULTS defaults} on the Runtime's timer.
     */
    public FlowBuilder targets(Targets targets) {
        this.targets = Objects.requireNonNull(targets, "targets");
        return this;
    }

    /** Parks the failed messages of at-least-once flows in {@code dlq}; such a flow does not build without it. */
    public FlowBuilder dlq(Dlq dlq) {
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        return this;
    }

    /** Queues the accepted messages of at-least-once flows in {@code queue}; their delivery and worker need it. */
    public FlowBuilder queue(WorkQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
        return this;
    }

    /**
     * Saves the messages of at-least-once flows through {@code intake}: their delivery's durable save of body, ingress
     * record and work item. Without this call it is a {@link SequentialIntake} over the {@link #recording} store and the
     * {@link #queue}; the node's is the Postgres intake, all three in one transaction.
     */
    public FlowBuilder intake(Intake intake) {
        this.intake = Objects.requireNonNull(intake, "intake");
        return this;
    }

    /**
     * Configuration versions every message of the flows built from now on carries beside the flow's own, on its snapshots and
     * DLQ rows, such as a deploy's {@code package=<name>@<version>} and {@code revision}; they replace those of an earlier
     * call.
     *
     * @throws IllegalArgumentException when {@code extra} sets the {@code flow} key, which the builder writes itself
     */
    public FlowBuilder versions(Map<String, String> extra) {
        Map<String, String> copy = Map.copyOf(extra);
        if (copy.containsKey("flow")) throw new IllegalArgumentException("the 'flow' version is the flow's own id@version, written by the builder; extra versions may not set it: " + copy);
        this.extraVersions = copy;
        return this;
    }

    /**
     * Tells {@code sink} every path the steps of the flows built from now on read through their {@link StepResources} ({@code file},
     * {@code resolve}) while they are built by {@link #delivery} (for at-least-once, only its partition key's expression),
     * {@link #worker} and {@link #build}, so that a flow's effective hash covers the files it reads; it replaces the sink
     * of an earlier call. Without a call nobody is told.
     */
    public FlowBuilder reads(Consumer<Path> sink) {
        this.reads = Objects.requireNonNull(sink, "sink");
        return this;
    }

    /**
     * Gives the steps of the flows built from now on the package's dependencies, each name to the directory its resolved version
     * is unpacked in, as {@link StepResources#dependencies()}; it replaces the map of an earlier call. Without a call a
     * flow has none.
     */
    public FlowBuilder dependencies(Map<String, Path> dependencies) {
        this.dependencies = Map.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        return this;
    }

    /**
     * Marks every step of the flows built from now on outermost with a {@link StepEvent} when {@code on}; the node asks for
     * this when it samples CPU time per step. It replaces the choice of an earlier call. Without a call, or with {@code false}, no step is
     * marked: the stages are the steps and their wrappers alone.
     */
    public FlowBuilder stepEvents(boolean on) {
        this.stepEvents = on;
        return this;
    }

    /**
     * Fences the workers built from now on by {@code fence}, the node's {@link dev.rail8.core.node.NodeLease}: they claim,
     * their {@code to} steps send, and they defer a failed run, park a message, complete, take the next item of a key and
     * hold, only while it answers true. A {@link Fence} also bounds their sends in time: the producer's deadline is the earlier of the message's and the fence's, and a run stops waiting for a send
     * still open when the fence closes. It replaces the fence of an earlier call. Without a call the fence is always open.
     */
    public FlowBuilder fence(BooleanSupplier fence) {
        this.fence = Objects.requireNonNull(fence, "fence");
        return this;
    }

    /**
     * Registers the workers built from now on, while started, in {@code workers}: the node's registry, in which its intake
     * reserves its hand-off slots. It replaces the registry of an earlier call. Without a call the builder has a registry of its
     * own, which no intake asks.
     */
    public FlowBuilder workers(Workers workers) {
        this.workers = Objects.requireNonNull(workers, "workers");
        return this;
    }

    /**
     * The node's work events for the workers built from now on: a keyed completion whose key's next row lies on another
     * node announces it to that node. It replaces the signals of an earlier call. Without a call nothing is announced.
     */
    public FlowBuilder signals(WorkSignals signals) {
        this.signals = Objects.requireNonNull(signals, "signals");
        return this;
    }

    /**
     * The node's default schedule of retries (set by {@code RAIL8_RETRIES_COUNT} and {@code RAIL8_RETRIES_INTERVAL}) for
     * the workers built from now on of the flows that name no {@code delivery.retries}; it replaces the default of an
     * earlier call. Without a call it is {@link #DEFAULT_RETRIES}.
     *
     * @throws IllegalArgumentException when the schedule is outside the bounds: at most {@link FlowDefinition#MAX_RETRIES}
     *     retries, an interval of 1s..24h, a cap of at most 24h
     */
    public FlowBuilder retries(RetryPolicy nodeDefault) {
        this.retries = FlowDefinition.withinRetryBounds(Objects.requireNonNull(nodeDefault, "nodeDefault"), "the node's retries");
        return this;
    }

    /**
     * The node's runs, which the flows built from now on run in and an operator's cancel stops; it replaces the runs of an
     * earlier call. Without a call the builder has runs of its own on the Runtime's clock and timer.
     */
    public FlowBuilder runs(Runs runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
        return this;
    }

    /** Builds the flow; {@code baseDir} is where the steps' relative file references resolve (the package directory). */
    public Pipeline build(FlowDefinition flow, Path baseDir) {
        return built(flow, baseDir, OPEN, true).pipeline(true);
    }

    /**
     * The flow's consumer handler for its delivery mode (see {@link Delivery}), with one {@link Admission} of its own built from
     * {@code limits.maxInFlight} and {@code limits.maxInFlightBytes}. Their defaults are twice the available processors and
     * that many bodies of {@code maxBodyBytes}; the admission is never unlimited. An at-least-once flow's handler only saves,
     * so only its recorder is built here, with no step and no producer; the {@link #worker} builds the pipeline. With
     * {@code partitionBy: <key>} it also compiles the expression of the one {@code extractKey} step that sets that key, in
     * the step's language, and enqueues each message under its value, so that the messages of one key run in order. On a
     * sync or at-most-once flow {@code partitionBy} has no effect.
     *
     * @throws IllegalStateException when an at-least-once flow lacks the store ({@link #recording}), the {@link #queue} or
     *     the {@link #dlq}; the message names the one missing
     * @throws IllegalArgumentException when the default byte budget overflows a {@code long}, or when {@code partitionBy}
     *     names a key that no {@code extractKey} step or two of them set, that a {@code split} or {@code to} step precedes
     *     (the key is read from the body as it arrived), or whose language needs the whole body while the flow's
     *     {@code maxBodyBytes} exceeds the store's tree limit
     */
    public Delivery delivery(FlowDefinition flow, Path baseDir) {
        Objects.requireNonNull(flow, "flow");
        Admission admission = admission(flow);
        return switch (flow.deliveryMode()) {
            case "sync" -> {
                Built built = built(flow, baseDir, OPEN, true);
                yield Delivery.sync(admission, built.pipeline(true), built.producers(), runtime.timer());
            }
            case "at-most-once" -> {
                Built built = built(flow, baseDir, OPEN, true);
                yield Delivery.atMostOnce(admission, built.pipeline(true), built.producers(), runtime.timer());
            }
            case "at-least-once" -> {
                requireStoreAndQueue(flow, "delivery");
                requireDlq(flow);
                Function<Envelope, Optional<String>> partitionKey = partitionKey(flow, baseDir);
                Recorder recorder = new Recorder(store, runtime.clock(), meter, versionsOf(flow));
                yield Delivery.atLeastOnce(admission, flow.id(), e -> recorder.startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT),
                        partitionKey, intake != null ? intake : new SequentialIntake(store, queue),
                        flow.recording().keepsBody(), runtime.timer(), flow.saveTimeout());
            }
            default -> throw new IllegalArgumentException("flow '" + flow.id() + "': unknown delivery.mode '" + flow.deliveryMode() + "'");
        };
    }

    /**
     * The value an at-least-once flow's work items are ordered by: none without {@code partitionBy}, else the expression
     * of the one extractKey step that sets its key, compiled in the step's language.
     *
     * @throws IllegalArgumentException when the key cannot be read so; {@link #delivery} lists the cases
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
     * in-flight request limit of items at once. It runs the pipeline without the ingress snapshot, and its steps are not
     * wrapped by {@link Parking}, because a failed run is the worker's to retry or park. It leases, polls and schedules its
     * retries on the runtime's clock and timer under the flow's {@code delivery.retries}, else the node's default
     * ({@link #retries}); it parks a message at the flow's first step once its retries are spent or its failure is final,
     * and while started it is registered in the {@link #workers} registry.
     *
     * @throws IllegalStateException when the store, the {@link #queue} or the {@link #dlq} is missing
     */
    public FlowWorker worker(FlowDefinition flow, Path baseDir, String workerId, WorkerPolicy policy) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(policy, "policy");
        requireStoreAndQueue(flow, "worker");
        requireDlq(flow);
        int max = (int) Math.min(inFlightRequests(flow), Integer.MAX_VALUE);
        Parking parking = new Parking(dlq, runtime.clock(), versionsOf(flow));
        Built built = built(flow, baseDir, fence, false);
        return new FlowWorker(flow.id(), workerId, max, policy, flow.retries().orElse(retries), queue, store, runtime.blobs(), built.pipeline(false),
                parking, flow.steps().get(0), runtime.clock(), runtime.timer(), built.producers(), fence, workers, signals, runs);
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

    /** {@code limits.maxInFlight}, or twice the available processors; never unlimited. */
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
     * The flow's steps as stages, wrapped (retry, numbering, recording, parking, then, with {@link #stepEvents}, the {@link StepEvent}
     * outermost), without the start stage; the stage that binds the message's context, the recorder (null without a store) and
     * the producers the steps send through.
     */
    private record Built(FlowDefinition flow, Stage context, List<Stage> steps, Recorder recorder, Producers producers) {
        /** The flow's pipeline: the context's binding, the ingress snapshot ({@code withIngress}, when recording), then the steps. */
        Pipeline pipeline(boolean withIngress) {
            List<Stage> stages = new ArrayList<>(steps.size() + 2);
            stages.add(context);
            if (withIngress && recorder != null) stages.add(recorder.start(flow.recording(), Recorder.INGRESS));
            stages.addAll(steps);
            return Pipeline.of(stages);
        }
    }

    /**
     * The flow built, its {@code to} steps sending under {@code fence}; an at-least-once flow's steps are wrapped by
     * {@link Parking} when {@code parking} is true (for {@link #build}, not for a worker). A build that throws, with any
     * exception, closes every producer it created before the exception leaves.
     */
    private Built built(FlowDefinition flow, Path baseDir, BooleanSupplier fence, boolean parking) {
        Objects.requireNonNull(flow, "flow");
        Map<String, String> versions = versionsOf(flow);
        boolean parks = parking && "at-least-once".equals(flow.deliveryMode());
        if (parks) requireDlq(flow);
        Parking parker = parks ? new Parking(dlq, runtime.clock(), versions) : null;
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
                    case "to" -> to(step, forward, opened, fence);
                    case "route" -> route(step, resources);
                    case "filter" -> new Filter(predicate(step, step.requireString("when"), resources));
                    case "extractKey" -> extractKey(step, resources);
                    case "validate" -> validate(step, resources);
                    case "split" -> new Split(language(step).parts(step.requireString("xpath"), resources));
                    default -> factoryFor(step).create(step, resources);
                };
                if (step.retryable()) stage = Retry.stage(stage, flow.retry(), runtime.timer());
                stage = Runs.guard(numbered(stage, step), step);
                if (recorder != null) stage = recorder.wrap(stage, step, step.index() == lastIndex, RecordingPolicy.from(step.config().get("recording"), flow.recording()));
                if (parker != null) stage = parker.wrap(stage, step);
                stages.add(stepEvents ? StepEvent.marked(stage, flow.id(), step) : stage);   // outermost: a step's CPU time includes its wrappers
            }
            done = true;
        } finally {
            if (!done) Producers.closeAll(flow.id(), opened);   // the flow does not build: nothing it created is left open
        }
        return new Built(flow, runs.head(flow.timeout()), List.copyOf(stages), recorder, new Producers(flow.id(), opened));
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
     * The producer gets the envelope through {@code forward} (the flow's {@code forwardHeaders}) under its target's limit;
     * what goes on is its reply. Every producer created is added to {@code opened} at once.
     */
    private Stage to(StepConfig step, UnaryOperator<Envelope> forward, List<Producer> opened, BooleanSupplier fence) {
        if (step.config().get("endpoint") != null) {
            Send send = send(step, step.requireString("endpoint"), opened, fence);
            return Stage.of(e -> send.apply(forward.apply(e)));
        }
        Key<String> prop = new Key<>(step.requireString("byProp"));
        if (!(step.config().get("endpoints") instanceof Map<?, ?> choices) || choices.isEmpty()) {
            throw new IllegalArgumentException(step + ": 'endpoints' must map every value of " + prop.name() + " to an endpoint");
        }
        Map<String, Send> sends = new LinkedHashMap<>();
        choices.forEach((choice, endpoint) -> sends.put(String.valueOf(choice), send(step, String.valueOf(endpoint), opened, fence)));
        return Stage.of(e -> {
            String choice = e.get(prop).orElseThrow(() -> new IllegalStateException(step + ": prop " + prop.name() + " is not set; a route step before it decides"));
            Send send = sends.get(choice);
            if (send == null) throw new IllegalStateException(step + ": no endpoint for " + prop.name() + " = '" + choice + "' (" + sends.keySet() + ")");
            return send.apply(forward.apply(e));
        });
    }

    /** The producer of {@code endpoint}, added to {@code opened} at once, the limit of the target its component names, and {@code fence}. */
    private Send send(StepConfig step, String endpoint, List<Producer> opened, BooleanSupplier fence) {
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
        return new Send(endpoint, producer, component.target(cfg).map(targets::of).orElseGet(targets::unshared), runtime.clock(), runtime.timer(), fence,
                fence instanceof Fence f ? f : null);   // only a Fence bounds a send in time and abandons it when the fence closes
    }

    /**
     * A {@code to}'s send: the producer's, under the limit of its target, waiting no longer than the time left on {@code clock}
     * until the deadline of the message bound, maxWait when none is; under the permit, a closed {@code fence} fails it with a
     * {@link FencedException} before the producer is called. With a message bound the producer gets
     * {@code send(e, d)}: {@code d} is the time left until the message's deadline — the earlier of that and what
     * the fence has left when {@code fence} is a {@link Fence} — re-read at each {@code remaining()}; no time left fails the send
     * with a retryable {@link NackException} and the producer is not called. Without a message bound it gets {@code send(e)}.
     * With a {@link Fence} the run stops waiting once the fence closes ({@link FencedWait}) and a producer failure landing while
     * it is closed is a {@link FencedException} with that failure as its cause; the permit is held until the producer's own
     * future settles. Under the permit a stopped run fails the send with a {@link RunStoppedException} before the producer is
     * called, and a send it starts is watched by the run, which returns only once that send has settled. Otherwise what the
     * send fails with comes out as is.
     */
    private record Send(String endpoint, Producer producer, TargetLimit limit, Clock clock, Timer timer, BooleanSupplier fence, Fence bound) {
        Envelope apply(Envelope e) {
            MessageContext context = MessageContext.isBound() ? MessageContext.current() : null;
            Runs.Run run = Runs.current();
            FencedWait wait = bound == null ? null : new FencedWait(bound, timer, e.id(), endpoint);
            Supplier<Future<Envelope>> send = () -> {
                if (!fence.getAsBoolean()) {
                    throw new FencedException("message " + e.id() + " not sent to endpoint '" + endpoint + "': the node's fence is closed (D-101 (2)); "
                            + "its item is left to whoever runs it next");
                }
                Deadline d = context == null ? null : deadline(context.deadline());
                if (d != null && !d.remaining().isPositive()) {
                    return Future.failedFuture(new NackException("message " + e.id() + " not sent to endpoint '" + endpoint + "': its deadline passed (D-113)", true));
                }
                Runs.Why stopped = run == null ? null : run.why();
                if (stopped != null) return Future.failedFuture(new RunStoppedException(stopped, null));
                if (wait != null) wait.arm();   // before the producer is called: nothing of the send is out before the fence is watched
                try {   // a null answer fails here, inside the catch, so its re-read is disarmed too
                    Future<Envelope> sent = Objects.requireNonNull(d == null ? producer.send(e) : producer.send(e, d),
                            () -> "endpoint '" + endpoint + "': the send answered no future");
                    if (run != null) run.watch(sent);   // the run returns only once it has settled
                    return sent;
                } catch (RuntimeException | Error failure) {
                    if (wait != null) wait.disarm();
                    throw failure;
                }
            };
            Duration within = context == null ? limit.gauge().maxWait() : context.remaining(clock);
            try {
                return wait == null ? limit.call(within, send) : limit.call(within, send, wait::watch);
            } catch (Exception failure) {   // a checked one too, as the producer's future failed with it, or the interrupt
                throw FlowBuilder.<RuntimeException>rethrown(failure);
            }
        }

        /** The time left until {@code until} on the clock, the earlier of that and the fence's when bounded by one; live. */
        private Deadline deadline(Instant until) {
            if (bound == null) return () -> Duration.between(clock.instant(), until);
            return () -> {
                Duration message = Duration.between(clock.instant(), until);
                Duration fenced = Duration.ofNanos(bound.openNanos());
                return message.compareTo(fenced) <= 0 ? message : fenced;
            };
        }
    }

    /**
     * The wait of one send bounded by the node's {@link Fence}. {@link #arm}, called under the permit just before the
     * producer is called, schedules a re-read on the timer when the fence's open time runs out. At the re-read a fence still
     * open (renewed meanwhile) is re-armed, and a closed one fails the wait with a {@link FencedException}: the run stops
     * waiting for a send that may outlive the fence, while the producer's own future goes on, keeping the target's permit,
     * until it settles. A producer answer that settles first ends the wait with it and cancels the re-read; a failure that
     * lands while the fence is closed becomes a {@link FencedException} carrying it.
     */
    private static final class FencedWait {
        private final Fence fence;
        private final Timer timer;
        private final String messageId;
        private final String endpoint;
        private final Promise<Envelope> out = Promise.promise();
        private Timer.Cancellable armed;   // guarded by this: the re-read pending, the latest one scheduled

        FencedWait(Fence fence, Timer timer, String messageId, String endpoint) {
            this.fence = fence;
            this.timer = timer;
            this.messageId = messageId;
            this.endpoint = endpoint;
        }

        /** Schedules the re-read at what the fence has left now (at least a nanosecond: one that closed meanwhile is re-read at once). */
        void arm() {
            Timer.Cancellable next = timer.schedule(Duration.ofNanos(Math.max(fence.openNanos(), 1)), this::reread);
            synchronized (this) {
                armed = next;
            }
        }

        /** Cancels the pending re-read, if one was armed: the send settled, or never started (no time left: never armed). */
        void disarm() {
            Timer.Cancellable pending;
            synchronized (this) {
                pending = armed;
            }
            if (pending != null) pending.cancel();
        }

        /** The timer's task: a wait still open is re-armed while the fence is open, failed once it is closed. */
        private void reread() {
            if (out.future().isComplete()) return;   // settled meanwhile: its cancel came too late for this one
            if (fence.getAsBoolean()) {
                arm();
                return;
            }
            out.tryFail(new FencedException("message " + messageId + ": its send to endpoint '" + endpoint + "' was abandoned, still open when the "
                    + "node's fence closed (D-101 (2), D-113); its item is left to whoever runs it next"));
        }

        /** What the run waits for: {@code sent}'s outcome, unless the fence closes first. */
        Future<Envelope> watch(Future<Envelope> sent) {
            sent.onComplete(settled -> {
                disarm();
                if (settled.succeeded()) {
                    out.tryComplete(settled.result());
                } else if (fence.getAsBoolean()) {
                    out.tryFail(settled.cause());
                } else {
                    out.tryFail(new FencedException("message " + messageId + ": its send to endpoint '" + endpoint + "' failed while the node's fence was "
                            + "closed (D-101 (2)); its item is left to whoever runs it next", settled.cause()));
                }
            });
            return out.future();
        }
    }

    /** {@code failure} thrown as it is, checked or not. */
    @SuppressWarnings("unchecked")
    private static <X extends Throwable> X rethrown(Throwable failure) throws X {
        throw (X) failure;
    }
}
