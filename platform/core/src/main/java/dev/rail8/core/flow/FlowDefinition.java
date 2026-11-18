package dev.rail8.core.flow;

import dev.rail8.api.StepConfig;
import dev.rail8.core.record.RecordingPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A flow file as core sees it: a plain map in (the YAML as {@code rail8-schemas} parsed and validated it), a typed definition
 * out. Core does not depend on the schema module, so the parts it needs are checked again here with the same paths in the
 * messages ({@code steps[2]}); a file that passed the schema never fails here, except for a zero {@code saveTimeout} or
 * {@code timeout}, or a {@code timeout} over {@link #MAX_TIMEOUT}, which the schema admits. {@code recording} is the flow's policy (default:
 * all, keep); a step's own {@code recording} stays in its config and overrides it in the builder. {@code saveTimeout} bounds
 * an at-least-once flow's durable save ({@code delivery.saveTimeout}, default {@link #DEFAULT_SAVE_TIMEOUT}); {@code timeout} is
 * the time each run of a message has, in any delivery mode: its deadline is the run's start plus it ({@code delivery.timeout},
 * default {@link #DEFAULT_TIMEOUT}). A body limit above the in-flight byte limit is refused, since such a body could never be
 * admitted. A {@code partitionBy} flow keeps its order through a failure: an item that failed for good is parked and stops its
 * key until an operator retries or cancels it; there is no choice to state, and a document that still sets {@code onFailure}
 * is refused with {@link #ON_FAILURE_GONE}. {@code retries} is an at-least-once flow's own schedule
 * of retries ({@code delivery.retries}, refused for any other mode); empty, its worker runs under the node's default
 * ({@link FlowBuilder#retries}). {@code retry} is the in-thread retry of the steps marked {@code retryable}, a key of its own.
 * {@code processes} names blocks of steps that a {@value #PROCESS} step runs where it stands: {@code steps} is the run's steps,
 * each call replaced by the steps of its process, numbered in run order and named {@code <call>/<step>}; a problem inside a
 * process names its step as {@code processes.<process>.steps[i]}. Processes run inside each other at most {@link #MAX_NESTING}
 * deep, and a flow runs at most {@link #MAX_STEPS} steps.
 *
 * @since 0.1
 */
public record FlowDefinition(String id, String version, String fromEndpoint, String deliveryMode, Duration saveTimeout, Duration timeout,
                             long maxBodyBytes, Optional<Long> maxInFlight, Optional<Long> maxInFlightBytes, Optional<String> partitionBy,
                             List<String> forwardHeaders, RetryPolicy retry, Optional<RetryPolicy> retries, RecordingPolicy recording,
                             List<StepConfig> steps) {

    /** Keys of a step entry that are the step's shape, not its configuration. */
    private static final Set<String> STRUCTURAL = Set.of("type", "name", "retryable");

    /** The step type that runs a process of the flow file where it stands. */
    public static final String PROCESS = "process";

    /** The keys of a {@value #PROCESS} step: it stands for the steps of its process and has no options of its own. */
    private static final Set<String> CALL = Set.of("type", "name", PROCESS);

    /** The most steps a flow runs once its processes are expanded. */
    public static final int MAX_STEPS = 1024;

    /** The most processes that run inside each other: a step calls a process, a step of that one calls another, this many in all. */
    public static final int MAX_NESTING = 8;

    /** How long an at-least-once save may take before the sender is told to retry, when the flow names nothing. */
    public static final Duration DEFAULT_SAVE_TIMEOUT = Duration.ofSeconds(5);

    /** The time each run of a message has when the flow names none. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /** The longest {@code delivery.timeout}: a day. */
    public static final Duration MAX_TIMEOUT = Duration.ofHours(24);

    /** Why a flow document that sets {@code onFailure} is refused. */
    public static final String ON_FAILURE_GONE = "onFailure is gone: an ordered flow (partitionBy) always stops behind a message that"
            + " failed for good, until an operator retries or cancels it; remove onFailure";

    /** The most retries {@code delivery.retries.count} may schedule. */
    public static final int MAX_RETRIES = 100;

    /** The shortest {@code delivery.retries.interval}. */
    public static final Duration MIN_RETRY_INTERVAL = Duration.ofSeconds(1);

    /** The longest {@code delivery.retries.interval} and {@code maxInterval}. */
    public static final Duration MAX_RETRY_INTERVAL = Duration.ofHours(24);

    public FlowDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(fromEndpoint, "from.endpoint");
        Objects.requireNonNull(deliveryMode, "delivery.mode");
        Objects.requireNonNull(saveTimeout, "delivery.saveTimeout");
        if (saveTimeout.compareTo(Duration.ZERO) <= 0) throw new IllegalArgumentException("delivery.saveTimeout must be positive, was " + saveTimeout);
        Objects.requireNonNull(timeout, "delivery.timeout");
        if (!timeout.isPositive() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("delivery.timeout must be positive and at most " + MAX_TIMEOUT + ", was " + timeout);
        }
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(retries, "delivery.retries");
        retries.ifPresent(r -> withinRetryBounds(r, "delivery.retries"));
        if (retries.isPresent() && !"at-least-once".equals(deliveryMode)) {
            throw new IllegalArgumentException("delivery.retries schedules the retries of an at-least-once flow; a " + deliveryMode
                    + " flow has no durable item to retry: remove it");
        }
        Objects.requireNonNull(recording, "recording");
        if (maxBodyBytes <= 0) throw new IllegalArgumentException("limits.maxBodyBytes must be positive, was " + maxBodyBytes);
        if (maxInFlightBytes.isPresent() && maxBodyBytes > maxInFlightBytes.get()) {
            throw new IllegalArgumentException("limits.maxBodyBytes " + maxBodyBytes + " exceeds limits.maxInFlightBytes " + maxInFlightBytes.get()
                    + ": such a body could never be admitted");
        }
        forwardHeaders = List.copyOf(forwardHeaders);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("steps must name at least one step");
    }

    /** A definition whose messages have the {@link #DEFAULT_TIMEOUT} per run and that schedules no retries of its own. */
    public FlowDefinition(String id, String version, String fromEndpoint, String deliveryMode, Duration saveTimeout, long maxBodyBytes,
                          Optional<Long> maxInFlight, Optional<Long> maxInFlightBytes, Optional<String> partitionBy,
                          List<String> forwardHeaders, RetryPolicy retry, RecordingPolicy recording, List<StepConfig> steps) {
        this(id, version, fromEndpoint, deliveryMode, saveTimeout, DEFAULT_TIMEOUT, maxBodyBytes, maxInFlight, maxInFlightBytes, partitionBy,
                forwardHeaders, retry, Optional.empty(), recording, steps);
    }

    /** Builds the definition from the parsed flow document. */
    public static FlowDefinition from(Map<String, Object> doc) {
        Objects.requireNonNull(doc, "flow document");
        if (doc.containsKey("onFailure")) throw new IllegalArgumentException(ON_FAILURE_GONE);
        Map<String, Object> from = section(doc, "from");
        Map<String, Object> delivery = section(doc, "delivery");
        Map<String, Object> limits = section(doc, "limits");
        return new FlowDefinition(
                string(doc, "id"), string(doc, "version"), string(from, "from.endpoint", "endpoint"), string(delivery, "delivery.mode", "mode"),
                delivery.get("saveTimeout") == null ? DEFAULT_SAVE_TIMEOUT : duration(delivery, "delivery.saveTimeout", "saveTimeout"),
                delivery.get("timeout") == null ? DEFAULT_TIMEOUT : duration(delivery, "delivery.timeout", "timeout"),
                number(limits, "limits.maxBodyBytes", "maxBodyBytes").orElseThrow(() -> new IllegalArgumentException("limits.maxBodyBytes is required")),
                number(limits, "limits.maxInFlight", "maxInFlight"), number(limits, "limits.maxInFlightBytes", "maxInFlightBytes"),
                Optional.ofNullable(doc.get("partitionBy")).map(Object::toString),
                strings(doc.get("forwardHeaders")), retry(doc.get("retry")), retries(delivery.get("retries")), RecordingPolicy.from(doc.get("recording"), RecordingPolicy.DEFAULT), steps(doc.get("steps"), doc.get("processes")));
    }

    /** Endpoints the steps send to, in order, each once: {@code to.endpoint} and the values of {@code to.endpoints}. The {@code from} endpoint is a consumer and not listed. */
    public List<String> referencedEndpoints() {
        List<String> names = new ArrayList<>();
        for (StepConfig step : steps) {
            if (step.config().get("endpoint") instanceof String s && !names.contains(s)) names.add(s);
            if (step.config().get("endpoints") instanceof Map<?, ?> m) {
                for (Object v : m.values()) if (v instanceof String s && !names.contains(s)) names.add(s);
            }
        }
        return List.copyOf(names);
    }

    private static RetryPolicy retry(Object node) {
        if (node == null) return RetryPolicy.NONE;
        if (!(node instanceof Map<?, ?> m)) throw new IllegalArgumentException("retry must be an object");
        Object multiplier = m.get("multiplier");
        return new RetryPolicy(
                Math.toIntExact(number(m, "retry.attempts", "attempts").orElseThrow(() -> new IllegalArgumentException("retry.attempts is required"))),
                RetryPolicy.parseDuration(string(m, "retry.initial", "initial")),
                RetryPolicy.parseDuration(string(m, "retry.max", "max")),
                multiplier instanceof Number n ? n.doubleValue() : 2.0);
    }

    /**
     * {@code delivery.retries}: empty when absent (the node's default applies); else {@code count}
     * 0..{@value #MAX_RETRIES} and {@code interval} 1s..24h, both required, {@code multiplier} a number of at least 1 (default 1),
     * {@code maxInterval} from the interval to 24h (default the interval) — as a {@link RetryPolicy} whose attempts are the count,
     * initial the interval and max the maxInterval. A value out of its bounds is refused naming its key.
     */
    private static Optional<RetryPolicy> retries(Object node) {
        if (node == null) return Optional.empty();
        if (!(node instanceof Map<?, ?> m)) throw new IllegalArgumentException("delivery.retries must be an object with count and interval");
        long count = number(m, "delivery.retries.count", "count").orElseThrow(() -> new IllegalArgumentException("delivery.retries.count is required"));
        if (count > MAX_RETRIES) throw new IllegalArgumentException("delivery.retries.count must be at most " + MAX_RETRIES + ", was " + count);
        Duration interval = retryInterval(m, "interval", MIN_RETRY_INTERVAL);
        Duration maxInterval = m.get("maxInterval") == null ? interval : retryInterval(m, "maxInterval", interval);
        Object multiplier = m.get("multiplier");
        if (multiplier != null && !(multiplier instanceof Number n && n.doubleValue() >= 1.0)) {
            throw new IllegalArgumentException("delivery.retries.multiplier must be a number of at least 1, was " + multiplier);
        }
        return Optional.of(new RetryPolicy((int) count, interval, maxInterval, multiplier == null ? 1.0 : ((Number) multiplier).doubleValue()));
    }

    /**
     * {@code policy} when it is within the bounds of a schedule of retries: at most {@value #MAX_RETRIES} retries (so a
     * worker's bound, count + 1, never overflows), an interval from {@link #MIN_RETRY_INTERVAL} to {@link #MAX_RETRY_INTERVAL}
     * and a cap no longer than that; else refused naming {@code what}. A schedule read from a flow file is held to them key by key before it gets here.
     */
    static RetryPolicy withinRetryBounds(RetryPolicy policy, String what) {
        if (policy.attempts() > MAX_RETRIES) {
            throw new IllegalArgumentException(what + ": count must be at most " + MAX_RETRIES + ", was " + policy.attempts());
        }
        if (policy.initial().compareTo(MIN_RETRY_INTERVAL) < 0 || policy.initial().compareTo(MAX_RETRY_INTERVAL) > 0) {
            throw new IllegalArgumentException(what + ": interval must be from " + MIN_RETRY_INTERVAL + " to " + MAX_RETRY_INTERVAL + ", was " + policy.initial());
        }
        if (policy.max().compareTo(MAX_RETRY_INTERVAL) > 0) {
            throw new IllegalArgumentException(what + ": maxInterval must be at most " + MAX_RETRY_INTERVAL + ", was " + policy.max());
        }
        return policy;
    }

    /** {@code delivery.retries.<key>}: a duration from {@code min} to {@link #MAX_RETRY_INTERVAL}, else refused naming the key. */
    private static Duration retryInterval(Map<?, ?> m, String key, Duration min) {
        String path = "delivery.retries." + key;
        Duration d = duration(m, path, key);
        if (d.compareTo(min) < 0 || d.compareTo(MAX_RETRY_INTERVAL) > 0) {
            throw new IllegalArgumentException(path + " must be from " + min + " to " + MAX_RETRY_INTERVAL + ", was " + d);
        }
        return d;
    }

    /**
     * The run's steps: those of {@code stepsNode} (the flow's {@code steps}) with each {@code process} step replaced by the steps
     * of the process it names in {@code processesNode} (the flow's {@code processes}, may be null), numbered 1.. in run order and
     * named {@code <call>/<step>}. Every process is checked, called or not; a process that calls itself, directly or through
     * others, is refused, and so are processes nested more than {@link #MAX_NESTING} deep and a run of more than
     * {@link #MAX_STEPS} steps, at the call or step that would pass the cap.
     */
    private static List<StepConfig> steps(Object stepsNode, Object processesNode) {
        Map<String, Object> declared = processesNode == null ? Map.of() : declared(processesNode);
        Map<String, List<Written>> processes = new LinkedHashMap<>();
        declared.forEach((name, steps) -> processes.put(name, written(steps, "processes." + name + ".steps", declared.keySet())));
        List<Written> flow = written(stepsNode, "steps", declared.keySet());
        Map<String, Integer> heights = new HashMap<>();
        nesting(flow, processes, new ArrayList<>(), heights);
        processes.forEach((name, steps) -> {
            if (!heights.containsKey(name)) heights.put(name, 1 + nesting(steps, processes, new ArrayList<>(List.of(name)), heights));
        });
        List<StepConfig> run = new ArrayList<>();
        expand(flow, "", processes, run);
        return run;
    }

    /** A step entry as written at {@code path}: a step, or a call of the process {@code process} (null for a step). */
    private record Written(String path, String type, String name, boolean retryable, Map<String, Object> config, String process) { }

    /** {@code processes}: each process's name to its {@code steps} as written (null when it has none). */
    private static Map<String, Object> declared(Object node) {
        if (!(node instanceof Map<?, ?> m)) throw new IllegalArgumentException("processes must be an object that names each process");
        Map<String, Object> declared = new LinkedHashMap<>();
        m.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!(value instanceof Map<?, ?> process)) throw new IllegalArgumentException("processes." + name + " must be an object with steps");
            declared.put(name, process.get("steps"));
        });
        return declared;
    }

    /**
     * The entries of the step list {@code node} at {@code path}, names unique among them; a {@code process} step takes only
     * {@code type}, {@code name} and {@code process}, which must be one of {@code processes}.
     */
    private static List<Written> written(Object node, String path, Set<String> processes) {
        if (!(node instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException(path + " must be a non-empty list");
        List<Written> entries = new ArrayList<>(list.size());
        Set<String> names = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = path + "[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) throw new IllegalArgumentException(at + " must be an object");
            String type = string(m, at + ".type", "type");
            String name = string(m, at + ".name", "name");
            if (!names.add(name)) throw new IllegalArgumentException(at + ".name '" + name + "' is used twice; step names are unique in a flow and in each process");
            if (PROCESS.equals(type)) {
                for (Object key : m.keySet()) {
                    if (!CALL.contains(String.valueOf(key))) {
                        throw new IllegalArgumentException(at + " runs a process and takes only type, name and process, not " + key);
                    }
                }
                String process = string(m, at + ".process", "process");
                if (!processes.contains(process)) throw new IllegalArgumentException(at + ".process '" + process + "' names no process of this flow");
                entries.add(new Written(at, type, name, false, Map.of(), process));
                continue;
            }
            Map<String, Object> config = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!STRUCTURAL.contains(key) && e.getValue() != null) config.put(key, e.getValue());
            }
            entries.add(new Written(at, type, name, Boolean.TRUE.equals(m.get("retryable")), config, null));
        }
        return entries;
    }

    /**
     * The most processes that a call among {@code entries} runs inside each other, the called one counted; 0 when none calls one.
     * {@code calling} is the chain of processes that led to {@code entries}; {@code heights} holds that number for each process
     * already followed to its end, so each is followed once. Refuses a call of a process on {@code calling}, naming the cycle, and
     * a call that nests processes more than {@link #MAX_NESTING} deep, naming the chain.
     */
    private static int nesting(List<Written> entries, Map<String, List<Written>> processes, List<String> calling, Map<String, Integer> heights) {
        int most = 0;
        for (Written w : entries) {
            if (w.process() == null) continue;
            Integer height = heights.get(w.process());
            if (height == null) {
                int at = calling.indexOf(w.process());
                if (at >= 0) {
                    throw new IllegalArgumentException(w.path() + ".process: process '" + w.process() + "' calls itself: "
                            + String.join(" -> ", calling.subList(at, calling.size())) + " -> " + w.process());
                }
                if (calling.size() == MAX_NESTING) throw tooDeep(w, calling);
                calling.add(w.process());
                height = 1 + nesting(processes.get(w.process()), processes, calling, heights);
                calling.removeLast();
                heights.put(w.process(), height);
            }
            if (calling.size() + height > MAX_NESTING) throw tooDeep(w, calling);
            most = Math.max(most, height);
        }
        return most;
    }

    /** The refusal of the call {@code w} at the end of {@code calling}, which nests processes more than {@link #MAX_NESTING} deep. */
    private static IllegalArgumentException tooDeep(Written w, List<String> calling) {
        List<String> chain = new ArrayList<>(calling);
        chain.add(w.process());
        return new IllegalArgumentException(w.path() + ".process: processes run inside each other at most " + MAX_NESTING
                + " deep, and this call goes deeper: " + String.join(" -> ", chain));
    }

    /** Adds {@code entries} to {@code run}, each call as its process's steps under the call's name, each step named after {@code prefix}. */
    private static void expand(List<Written> entries, String prefix, Map<String, List<Written>> processes, List<StepConfig> run) {
        for (Written w : entries) {
            if (w.process() != null) {
                expand(processes.get(w.process()), prefix + w.name() + "/", processes, run);
                continue;
            }
            if (run.size() == MAX_STEPS) {
                throw new IllegalArgumentException("a flow runs at most " + MAX_STEPS + " steps once its processes are expanded; " + w.path()
                        + " ('" + prefix + w.name() + "') would be one more");
            }
            run.add(new StepConfig(run.size() + 1, w.type(), prefix + w.name(), w.retryable(), w.config()));
        }
    }

    private static Map<String, Object> section(Map<String, Object> doc, String key) {
        if (!(doc.get(key) instanceof Map<?, ?> m)) throw new IllegalArgumentException(key + " is required and must be an object");
        Map<String, Object> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }

    private static String string(Map<?, ?> m, String key) { return string(m, key, key); }

    private static String string(Map<?, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isEmpty()) throw new IllegalArgumentException(path + " is required and must be a non-empty string");
        return s;
    }

    /** A duration string ({@link RetryPolicy#parseDuration}); one that does not parse is refused naming its key. */
    private static Duration duration(Map<?, ?> m, String path, String key) {
        String text = string(m, path, key);
        try {
            return RetryPolicy.parseDuration(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    private static Optional<Long> number(Map<?, ?> m, String path, String key) {
        Object v = m.get(key);
        if (v == null) return Optional.empty();
        if (!(v instanceof Number n) || n.longValue() < 0 || (n instanceof Double || n instanceof Float)) {
            throw new IllegalArgumentException(path + " must be a non-negative integer, was " + v);
        }
        return Optional.of(n.longValue());
    }

    private static List<String> strings(Object node) {
        if (node == null) return List.of();
        if (!(node instanceof List<?> list)) throw new IllegalArgumentException("forwardHeaders must be a list of strings");
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) out.add(String.valueOf(o));
        return out;
    }
}
