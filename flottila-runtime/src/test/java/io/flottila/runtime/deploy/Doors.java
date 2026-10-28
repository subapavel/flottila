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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *
 * <p>Every producer built is an {@link Out} (FIX-30) over the partner's producer: it counts its {@code close()} calls and its
 * sends still unanswered, and records at each close how many of its own sends were unanswered then. A hook runs inside every
 * producer close ({@link #onProducerClose}); the close of the {@code nth} producer built can fail ({@link #failProducerClose}).
 * After {@link #closeFailsSends()} a producer's close fails each of its sends still unanswered and every later send, as a
 * real client closed under its requests does.
 *
 * <p>P1-09d2b: {@link #trace} hands every event, in the order made, to one sink — the redeploy tests' single log at the SPI
 * boundary — together with two kinds {@link #events()} leaves out: {@code "stopped <flow>"} when a door's stop() future
 * completes (before whoever awaits it sees it), and {@code "close <endpoint> #<ordinal>"} at each producer close call.
 * {@link #onConsumer} runs a hook at the top of every consumer() call.
 *
 * <p>P1-09d2c: a start or a stop can be held or failed by the flow it belongs to ({@link #holdStartOf}, {@link #failStartOf},
 * {@link #holdStopOf}) — the gates of one flow apply to its next start() (stop()) calls in the order armed, whichever door makes
 * them, and take precedence over an ordinal gate of {@link #holdStart} for that call. Every door keeps its own history on one
 * sequence shared by every door and producer of this component ({@link Door#history()}, {@link Door#first}, {@link Door#last}):
 * {@code start} (called), {@code started} (its future succeeded), {@code start failed}, {@code stop} (called) and {@code stopped}
 * (its future completed) — and every producer the sequence number of its first close ({@link Out#closedAt()}), so a test orders
 * the events of one door against another's even when both serve the same flow.
 *
 * <p>P1-09g (D-072): {@link #listensOn} answers where a consumer of an endpoint would listen — from its options alone, nothing is
 * bound: absent unless the endpoint sets {@code port} (0..65535), then {@code host} (default {@code 127.0.0.1}) and that port,
 * unresolved and as configured. An endpoint whose options carry {@code listenRefuse} makes it throw with that text. Every call is
 * recorded by the endpoint's name ({@link #listensOnAsked()}). {@link #Doors(String)} serves another scheme than {@value #SCHEME}.
 */
public final class Doors implements Component {

    public static final String SCHEME = "rec";

    /**
     * The options this component takes: a key outside them, {@code level} outside low/high or {@code port} outside 0..65535 fails
     * its configSchema.
     */
    public static final String CONFIG_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
              "path":{"type":"string"},"url":{"type":"string"},"level":{"enum":["low","high"]},"tags":{"type":"object"},
              "refuse":{"type":"string"},"flow":{"type":"string"},"maxBodyBytes":{"type":"integer","minimum":1},
              "host":{"type":"string"},"port":{"type":"integer","minimum":0,"maximum":65535},"listenRefuse":{"type":"string"}}}""";

    private final String scheme;
    private final RecordingComponent partner;
    private final List<String> listensOnAsked = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Door> doors = new CopyOnWriteArrayList<>();
    private final List<EndpointConfig> producerConfigs = new CopyOnWriteArrayList<>();
    private final Map<String, String> stopFailures = new ConcurrentHashMap<>();
    private final List<Gate> gates = new CopyOnWriteArrayList<>();
    private volatile java.util.function.Consumer<Door> onStop = d -> {};
    private final List<Out> outs = new CopyOnWriteArrayList<>();
    private final Map<Integer, String> closeFailures = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Out> onProducerClose = o -> {};
    private volatile boolean closeFailsSends;
    private volatile java.util.function.Consumer<String> trace = e -> {};
    private volatile java.util.function.Consumer<EndpointConfig> onConsumer = c -> {};
    private int starts;   // guarded by this: start() calls so far
    private final Map<String, java.util.Queue<Gate>> startGates = new ConcurrentHashMap<>();
    private final Map<String, java.util.Queue<Gate>> stopGates = new ConcurrentHashMap<>();
    /** One sequence for every door's history and every producer's close: a later event has a higher number. */
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();

    /** The component of the scheme {@value #SCHEME}. */
    public Doors() {
        this(SCHEME);
    }

    /** The same component serving {@code scheme} (P1-09g: two schemes on one node). */
    public Doors(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.partner = new RecordingComponent(scheme);
    }

    /**
     * From now on every event of {@link #events()} also goes to {@code sink}, on the thread that made it, and so do
     * {@code "stopped <flow>"} (a door's stop() future completed; logged before that future completes for its caller) and
     * {@code "close <endpoint> #<ordinal>"} (a producer's close() was called).
     */
    public void trace(java.util.function.Consumer<String> sink) { this.trace = Objects.requireNonNull(sink, "sink"); }

    /** Runs at the top of every consumer() call, on the building thread, before an endpoint's {@code refuse} option is looked at. */
    public void onConsumer(java.util.function.Consumer<EndpointConfig> hook) { this.onConsumer = Objects.requireNonNull(hook, "hook"); }

    private void event(String e) {
        events.add(e);
        trace.accept(e);
    }

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

    /**
     * The next start() of a door whose {@code flow} option is {@code flow}, after those the earlier gates of that flow took, waits
     * until the gate is released or failed; a gate never released is a start that never completes.
     */
    public Gate holdStartOf(String flow) {
        return armed(startGates, flow, new Gate(0, null));
    }

    /** The next start() of a door whose {@code flow} option is {@code flow} (after the earlier gates of that flow) fails at once with {@code message}. */
    public Gate failStartOf(String flow, String message) {
        return armed(startGates, flow, new Gate(0, Objects.requireNonNull(message, "message")));
    }

    /**
     * The next stop() of a door whose {@code flow} option is {@code flow} (after the earlier gates of that flow) is called as
     * usual — the door refuses nothing new and drains — but its future completes only once its answers have completed and the
     * gate is released; a gate never released is a stop that never completes. {@link Gate#reached()} counts down at the call.
     */
    public Gate holdStopOf(String flow) {
        return armed(stopGates, flow, new Gate(0, null));
    }

    private Gate armed(Map<String, java.util.Queue<Gate>> byFlow, String flow, Gate g) {
        byFlow.computeIfAbsent(Objects.requireNonNull(flow, "flow"), f -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(g);
        gates.add(g);   // released by releaseAll; ordinal 0 never matches a start() call
        return g;
    }

    private static Gate next(Map<String, java.util.Queue<Gate>> byFlow, String flow) {
        java.util.Queue<Gate> q = byFlow.get(flow);
        return q == null ? null : q.poll();
    }

    private long tick() {
        return seq.incrementAndGet();
    }

    /** The stop() of the door with flow option {@code flow} fails with {@code message} once its answers have completed. */
    public void failStop(String flow, String message) { stopFailures.put(flow, message); }

    /** Runs inside every door's stop(), on the stopping thread, before the stop returns. */
    public void onStop(java.util.function.Consumer<Door> hook) { this.onStop = Objects.requireNonNull(hook, "hook"); }

    /** Releases every held start and stop: what a case left held never outlives it. */
    public void releaseAll() {
        for (Gate g : gates) g.release();
    }

    private synchronized Gate gateFor(String flow) {
        int n = ++starts;
        Gate byFlow = next(startGates, flow);
        if (byFlow != null) return byFlow;
        for (Gate g : gates) if (g.ordinal == n) return g;
        return null;
    }

    @Override public String scheme() { return scheme; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    /**
     * Where a consumer of {@code cfg} would listen (P1-09g, {@code Component.listensOn}): empty without a {@code port} option, else
     * {@code host} (default {@code 127.0.0.1}) and {@code port}, unresolved, the host as configured. {@code listenRefuse} makes it
     * throw an {@link IllegalStateException} with that text; a {@code port} that is no number (a config its schema refuses) an
     * {@link IllegalArgumentException}. Every call is recorded first ({@link #listensOnAsked()}).
     */
    public Optional<InetSocketAddress> listensOn(EndpointConfig cfg) {
        listensOnAsked.add(cfg.name());
        if (cfg.options().get("listenRefuse") instanceof String why) throw new IllegalStateException(why);
        Object port = cfg.options().get("port");
        if (port == null) return Optional.empty();
        if (!(port instanceof Number n)) throw new IllegalArgumentException("endpoint '" + cfg.name() + "': port " + port + " is no number");
        Object host = cfg.options().get("host");
        return Optional.of(InetSocketAddress.createUnresolved(host == null ? "127.0.0.1" : String.valueOf(host), n.intValue()));
    }

    /** The name of the endpoint of every {@link #listensOn} call, in order. */
    public List<String> listensOnAsked() { return List.copyOf(listensOnAsked); }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(cfg, "cfg");
        Objects.requireNonNull(handler, "handler");
        onConsumer.accept(cfg);
        if (cfg.options().get("refuse") instanceof String why) throw new IllegalArgumentException(why);
        Door d = new Door(cfg, handler);
        doors.add(d);
        event("consumer " + d.flow());
        return d;
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        producerConfigs.add(cfg);
        Out out;
        synchronized (outs) {
            out = new Out(outs.size() + 1, cfg.name(), partner.producer(cfg));
            outs.add(out);
        }
        return out;
    }

    /** Every producer built, in build order (ordinal 1 = the first). */
    public List<Out> producers() { return List.copyOf(outs); }

    /** The producers built and never closed. */
    public List<Out> openProducers() { return outs.stream().filter(o -> o.closes() == 0).toList(); }

    /** The producers closed more than once. */
    public List<Out> producersClosedTwice() { return outs.stream().filter(o -> o.closes() > 1).toList(); }

    /** Runs inside every producer's close, on the closing thread, before the close answers. */
    public void onProducerClose(java.util.function.Consumer<Out> hook) { this.onProducerClose = Objects.requireNonNull(hook, "hook"); }

    /** The close of the {@code nth} producer built (1 = the first ever) answers a failed future carrying {@code message}; it still counts. */
    public void failProducerClose(int nth, String message) { closeFailures.put(nth, message); }

    /** From now on a producer's close fails each of its sends still unanswered (they answer a failed future) and every later send. */
    public void closeFailsSends() { this.closeFailsSends = true; }

    /** A producer as built: its ordinal, its endpoint, its close calls, its sends not yet answered and what was unanswered at each close. */
    public final class Out implements Producer {
        private final int ordinal;
        private final String endpoint;
        private final Producer inner;
        private final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger unanswered = new java.util.concurrent.atomic.AtomicInteger();
        private final List<Integer> unansweredAtClose = new CopyOnWriteArrayList<>();
        private final List<String> sentIds = new CopyOnWriteArrayList<>();
        private final java.util.Set<Promise<Envelope>> pending = ConcurrentHashMap.newKeySet();
        private volatile long closedAt = -1;

        Out(int ordinal, String endpoint, Producer inner) {
            this.ordinal = ordinal;
            this.endpoint = endpoint;
            this.inner = inner;
        }

        public int ordinal() { return ordinal; }

        public String endpoint() { return endpoint; }

        public int closes() { return closes.get(); }

        /** The sequence number of its first close() call (P1-09d2c: comparable with {@link Door#first}); -1 before one. */
        public long closedAt() { return closedAt; }

        /** The ids of the envelopes sent through this producer. */
        public List<String> sentIds() { return List.copyOf(sentIds); }

        /** At each close, how many of this producer's sends were not answered yet. */
        public List<Integer> unansweredAtClose() { return List.copyOf(unansweredAtClose); }

        @Override
        public Future<Envelope> send(Envelope e) {
            sentIds.add(e.id());
            if (closeFailsSends && closes.get() > 0) return Future.failedFuture(new IllegalStateException(this + ": a send after the close"));
            unanswered.incrementAndGet();
            Promise<Envelope> answer = Promise.promise();
            pending.add(answer);
            inner.send(e).onComplete(r -> {
                if (r.succeeded()) answer.tryComplete(r.result());
                else answer.tryFail(r.cause());
            });
            // andThen: the future the caller awaits completes only after the count went down
            return answer.future().andThen(done -> {
                pending.remove(answer);
                unanswered.decrementAndGet();
            });
        }

        @Override
        public Future<Void> close() {
            trace.accept("close " + endpoint + " #" + ordinal);
            long at = tick();
            synchronized (this) {
                if (closedAt < 0) closedAt = at;
            }
            unansweredAtClose.add(unanswered.get());   // before the count: a test that waits for closes() > 0 then reads it
            closes.incrementAndGet();
            if (closeFailsSends) {
                for (Promise<Envelope> p : List.copyOf(pending)) p.tryFail(new IllegalStateException(this + ": closed under a send"));
            }
            onProducerClose.accept(this);
            String failure = closeFailures.get(ordinal);
            return failure == null ? inner.close() : Future.failedFuture(new IllegalStateException(failure));
        }

        @Override
        public String toString() { return "producer #" + ordinal + " of " + endpoint + " (closed " + closes() + "x, sent " + sentIds + ")"; }
    }

    @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

    /** One event of a door's history and its place in the component's sequence. */
    record Mark(long seq, String kind) {}

    /** A start() (or, from {@link #holdStopOf}, a stop()) held or failed by the test. */
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

        /** Counted down when the start() (stop()) call this gate holds was made. */
        public CountDownLatch reached() { return reached; }

        /** The door whose start() (stop()) this gate holds; null before {@link #reached()}. */
        public Door door() { return door; }

        /** Lets the held start (stop) succeed. */
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
        private final List<Mark> history = new CopyOnWriteArrayList<>();

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

        /** Its events in order: {@code start}, {@code started}, {@code start failed}, {@code stop}, {@code stopped}. */
        public List<String> history() { return history.stream().map(Mark::kind).toList(); }

        /** The sequence number of its first {@code kind} event (see {@link #history()}); -1 when there was none. */
        public long first(String kind) {
            for (Mark m : history) if (m.kind().equals(kind)) return m.seq();
            return -1;
        }

        /** The sequence number of its last {@code kind} event; -1 when there was none. */
        public long last(String kind) {
            long found = -1;
            for (Mark m : history) if (m.kind().equals(kind)) found = m.seq();
            return found;
        }

        /** Its stop() calls so far. */
        public int stops() { return (int) history.stream().filter(m -> m.kind().equals("stop")).count(); }

        private void mark(String kind) { history.add(new Mark(tick(), kind)); }

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
            mark("start");
            event("start " + flow());
            Gate g = gateFor(flow());
            Future<Void> result;
            if (g == null) {
                result = Future.succeededFuture();
            } else {
                g.door = this;
                if (g.failure != null) g.fail(g.failure);
                result = g.result.future();
                g.reached.countDown();
            }
            return result.onSuccess(v -> {
                started = true;
                mark("started");
            }).onFailure(failed -> mark("start failed"));
        }

        @Override
        public Future<Void> stop() {
            stopped = true;
            mark("stop");
            event("stop " + flow());
            onStop.accept(this);
            stopping.tryComplete();
            String failure = stopFailures.get(flow());
            Future<Void> stop = failure == null ? drained.future() : drained.future().compose(v -> Future.failedFuture(new IllegalStateException(failure)));
            Gate held = next(stopGates, flow());
            if (held != null) {
                held.door = this;
                Future<Void> drainedFirst = stop;
                stop = drainedFirst.compose(v -> held.result.future());
                held.reached.countDown();
            }
            return stop.andThen(done -> {
                mark("stopped");
                trace.accept("stopped " + flow());
            });
        }

        @Override
        public String toString() { return "door " + cfg.name() + " (flow " + flow() + ")"; }
    }
}
