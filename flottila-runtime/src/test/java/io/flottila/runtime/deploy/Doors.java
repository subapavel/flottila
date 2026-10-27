package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * The component of the scheme {@value #SCHEME} the deploy tests bind every endpoint to (P1-09d1b). Its producers are a
 * {@link RecordingComponent}'s ({@link #partner()}: records every send, replies with the envelope as sent, can hold or fail a
 * send). Its consumers are {@link Door}s that record what the deploy gave them — the endpoint's options, {@code flow} and
 * {@code maxBodyBytes} among them — and the handler, and let a test deliver an envelope through that handler on a virtual
 * thread of its own, counted until its answer completes: a door's {@code stop()} completes once every answer it handed out
 * has completed (D-056), like a real consumer's. Every consumer built, start and stop is an event in {@link #events()}
 * ({@code "consumer <flow>"}, {@code "start <flow>"}, {@code "stop <flow>"}), where {@code <flow>} is the door's {@code flow}
 * option. A start can be held or failed ({@link #holdStart}, {@link #failStart}), a stop failed ({@link #failStop}), and a
 * hook runs inside every stop before it returns ({@link #onStop}). An endpoint whose options carry {@code refuse} makes
 * {@link #consumer} throw with that text: a consumer the component refuses to build.
 */
public final class Doors implements Component {

    public static final String SCHEME = "rec";

    /** The options this component takes: a key outside them, or {@code level} outside low/high, fails its configSchema. */
    public static final String CONFIG_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
              "path":{"type":"string"},"url":{"type":"string"},"level":{"enum":["low","high"]},"tags":{"type":"object"},
              "refuse":{"type":"string"},"flow":{"type":"string"},"maxBodyBytes":{"type":"integer","minimum":1}}}""";

    private final RecordingComponent partner = new RecordingComponent(SCHEME);
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Door> doors = new CopyOnWriteArrayList<>();
    private final List<EndpointConfig> producerConfigs = new CopyOnWriteArrayList<>();
    private final Map<String, String> stopFailures = new ConcurrentHashMap<>();
    private final List<Gate> gates = new CopyOnWriteArrayList<>();
    private volatile java.util.function.Consumer<Door> onStop = d -> {};
    private int starts;   // guarded by this: start() calls so far

    /** The producers' side: what was sent to each out endpoint, holds and failures of sends. */
    public RecordingComponent partner() { return partner; }

    /** Every consumer built, start and stop, in order. */
    public List<String> events() { return List.copyOf(events); }

    /** Every door built, in order. */
    public List<Door> doors() { return List.copyOf(doors); }

    /** The door built last whose {@code flow} option is {@code flow}; fails the case when there is none. */
    public Door door(String flow) {
        Door found = null;
        for (Door d : doors) if (flow.equals(d.flow())) found = d;
        if (found == null) throw new AssertionError("no consumer was built with the flow option '" + flow + "'; built: " + events);
        return found;
    }

    /** The configurations the producers of endpoint {@code name} were built with. */
    public List<EndpointConfig> producerConfigs(String name) {
        List<EndpointConfig> out = new ArrayList<>();
        for (EndpointConfig c : producerConfigs) if (c.name().equals(name)) out.add(c);
        return out;
    }

    /** The {@code nth} start() call from now on (1 = the next one), whichever door makes it, waits until the gate is released or failed. */
    public synchronized Gate holdStart(int nth) {
        Gate g = new Gate(starts + nth, null);
        gates.add(g);
        return g;
    }

    /** The {@code nth} start() call from now on fails at once with an {@link IllegalStateException} carrying {@code message}. */
    public synchronized Gate failStart(int nth, String message) {
        Gate g = new Gate(starts + nth, message);
        gates.add(g);
        return g;
    }

    /** The stop() of the door with flow option {@code flow} fails with {@code message} once its answers have completed. */
    public void failStop(String flow, String message) { stopFailures.put(flow, message); }

    /** Runs inside every door's stop(), on the stopping thread, before the stop returns. */
    public void onStop(java.util.function.Consumer<Door> hook) { this.onStop = Objects.requireNonNull(hook, "hook"); }

    /** Releases every held start: what a case left held never outlives it. */
    public void releaseAll() {
        for (Gate g : gates) g.release();
    }

    private synchronized Gate gateFor() {
        int n = ++starts;
        for (Gate g : gates) if (g.ordinal == n) return g;
        return null;
    }

    @Override public String scheme() { return SCHEME; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(cfg, "cfg");
        Objects.requireNonNull(handler, "handler");
        if (cfg.options().get("refuse") instanceof String why) throw new IllegalArgumentException(why);
        Door d = new Door(cfg, handler);
        doors.add(d);
        events.add("consumer " + d.flow());
        return d;
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        producerConfigs.add(cfg);
        return partner.producer(cfg);
    }

    @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

    /** A start() held or failed by the test. */
    public static final class Gate {
        final int ordinal;
        final String failure;
        final Promise<Void> result = Promise.promise();
        final CountDownLatch reached = new CountDownLatch(1);
        volatile Door door;

        Gate(int ordinal, String failure) {
            this.ordinal = ordinal;
            this.failure = failure;
        }

        /** Counted down when the start() call this gate holds was made. */
        public CountDownLatch reached() { return reached; }

        /** The door whose start() this gate holds; null before {@link #reached()}. */
        public Door door() { return door; }

        /** Lets the held start succeed. */
        public void release() { result.tryComplete(); }

        /** Fails the held start with an {@link IllegalStateException} carrying {@code message}. */
        public void fail(String message) { result.tryFail(new IllegalStateException(message)); }
    }

    /** A consumer as the deploy built it: its configuration, its handler, and the answers it handed out. */
    public final class Door implements Consumer {
        private final EndpointConfig cfg;
        private final Function<Envelope, Future<Ack>> handler;
        private final Promise<Void> drained = Promise.promise();
        private final Promise<Void> stopping = Promise.promise();
        private int open = 1;                 // guarded by this: the door's own hold until stop, plus each outstanding answer
        private volatile boolean started;     // its start() succeeded
        private volatile boolean stopped;     // its stop() was called

        Door(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
            this.cfg = cfg;
            this.handler = handler;
            stopping.future().onComplete(v -> release());
        }

        /** The endpoint configuration the deploy built this consumer with. */
        public EndpointConfig cfg() { return cfg; }

        /** The handler the deploy gave this consumer, as given. */
        public Function<Envelope, Future<Ack>> handler() { return handler; }

        /** The {@code flow} option as text ({@code "null"} without one). */
        public String flow() { return String.valueOf(cfg.options().get("flow")); }

        public boolean started() { return started; }

        public boolean stopped() { return stopped; }

        /**
         * Delivers {@code e} as a consumer would: the handler runs on a virtual thread of its own, the answer is counted until it
         * completes (a stop meanwhile waits for it). A handler that throws or answers null fails the answer.
         */
        public Future<Ack> deliver(Envelope e) {
            Promise<Ack> answer = Promise.promise();
            synchronized (this) { open++; }
            answer.future().onComplete(done -> release());
            Thread.ofVirtual().name("doors-handler").start(() -> Future.<Ack>future(p -> handler.apply(e).onComplete(p)).onComplete(answer));
            return answer.future();
        }

        private void release() {
            boolean last;
            synchronized (this) { last = --open == 0; }
            if (last) drained.tryComplete();
        }

        @Override
        public Future<Void> start() {
            events.add("start " + flow());
            Gate g = gateFor();
            Future<Void> result;
            if (g == null) {
                result = Future.succeededFuture();
            } else {
                g.door = this;
                if (g.failure != null) g.fail(g.failure);
                result = g.result.future();
                g.reached.countDown();
            }
            return result.onSuccess(v -> started = true);
        }

        @Override
        public Future<Void> stop() {
            stopped = true;
            events.add("stop " + flow());
            onStop.accept(this);
            stopping.tryComplete();
            String failure = stopFailures.get(flow());
            return failure == null ? drained.future() : drained.future().compose(v -> Future.failedFuture(new IllegalStateException(failure)));
        }

        @Override
        public String toString() { return "door " + cfg.name() + " (flow " + flow() + ")"; }
    }
}
