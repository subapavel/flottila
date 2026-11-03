package io.flottila.sim;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.StepConfig;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.RetryPolicy;
import io.flottila.core.flow.SequentialIntake;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.core.record.Snapshot;
import io.flottila.sim.World.Unit;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Function;

/**
 * One run of one seed (D-101), on the driver thread: the real core — {@link FlowBuilder}, {@link io.flottila.core.flow.Delivery},
 * {@link FlowWorker}, admission, Retry, the recorder and the DLQ — over in-memory stores, the fake clock and the run's doubles.
 * Two flows: {@code sync} (transform, then the partner) and {@code alo}, at-least-once by key (extractKey, transform, the
 * partner), run by workers A and B over one queue. Each tick draws its actions from the WORKLOAD stream, adds the RELEASE of
 * every held call due, orders them by the SCHEDULE stream, runs them one unit at a time — waiting after each until no run is
 * RUNNING — then ADVANCEs the clock by one tick (to each due timer in turn by {@link FakeTimer#nextDelay()}) and runs the WAKE of
 * every backoff that came due. After the last tick the faults are off and the run drains, at most drainTicks, polling until
 * nothing is left; then the oracle judges.
 */
final class SimRun {

    static final String SYNC = "sync";
    static final String ALO = "alo";
    static final String KEY = "k";
    static final String KEY_HEADER = "sim-key";
    static final List<String> WORKERS = List.of("A", "B");
    private static final String PARTNER = "partner";
    private static final Path BASE = Path.of("");

    /** One unit of the driver. */
    @FunctionalInterface
    private interface Work {
        void run() throws InterruptedException;
    }

    private final SimSeed seed;
    private final SimConfig config;
    private final int ticks;
    private final World world;
    private final Runs runs = new Runs();
    private final Trace trace = new Trace();
    private final List<SimTimer.Wake> wakes = new ArrayList<>();
    private final FakeClock clock = FakeClock.at(FakeRuntime.T0);
    private final FakeTimer fake = new FakeTimer(clock);
    private final InMemoryBlobStore blobs = new InMemoryBlobStore(Hasher.sha256(), FakeRuntime.DEFAULT_TREE_LIMIT);
    private final SimStore store = new SimStore(new InMemoryMessageStore(), runs);
    private final InMemoryDlq dlq = new InMemoryDlq();
    private final Faults faults;
    private final Oracle oracle;
    private volatile long tick;
    private String claimedInPoll;   // the driver's: the item the poll in progress claimed
    private WorkQueue queue;
    private Map<String, Function<Envelope, Future<Ack>>> entries;
    private Map<String, FlowWorker> workers;

    SimRun(SimSeed seed, SimConfig config, int ticks, World world) {
        this.seed = seed;
        this.config = config;
        this.ticks = ticks;
        this.world = world;
        this.faults = new Faults(seed, config, world, () -> tick);
        this.oracle = new Oracle(config, trace, () -> tick);
    }

    /** The whole run, on the calling thread, which becomes the driver. */
    Verdict drive() throws InterruptedException {
        runs.drive();
        build();
        for (long t = 0; t < ticks; t++) tick(t);
        faults.off();
        drain();
        oracle.judge(store, dlq, runs, wakes.size(), queue.hasItems(ALO).await());
        return new Verdict(seed, config, ticks, world.name(), oracle.failures(), oracle.compared(), trace.digest());
    }

    /** What a hang waits on. */
    String unclassified() {
        return runs.unclassified();
    }

    private void build() {
        SimPartner partner = new SimPartner(faults, oracle, runs, trace, () -> tick);
        Runtime runtime = new Runtime(clock, new SimTimer(fake, runs, wakes), new FakeIds("sim"), blobs, Hasher.sha256(), Map.of(SimPartner.SCHEME, partner));
        queue = new ClaimCap(world.queue(new InMemoryWorkQueue(clock), clock), runs, this::claimed, this::taken);
        FlowBuilder builder = new FlowBuilder(runtime, List.of(SimSteps.transform(world)), List.of(SimSteps.language()),
                Map.of(PARTNER, new EndpointConfig(PARTNER, URI.create(SimPartner.SCHEME + "://" + PARTNER), Map.of())))
                .recording(store, () -> Snapshot.CPU_UNKNOWN)
                .dlq(dlq)
                .queue(queue)
                .intake(world.intake(new SimIntake(new SequentialIntake(store, queue), faults)));
        RetryPolicy retry = new RetryPolicy(config.retries(), config.retryInitial(), config.retryMax(), config.retryMultiplier());
        StepConfig transform1 = new StepConfig(1, SimSteps.TRANSFORM, "transform", false, Map.of());
        StepConfig transform2 = new StepConfig(2, SimSteps.TRANSFORM, "transform", false, Map.of());
        FlowDefinition sync = new FlowDefinition(SYNC, "1", "in", "sync", FlowDefinition.DEFAULT_SAVE_TIMEOUT, config.maxBodyBytes(),
                Optional.of(config.syncMaxInFlight()), Optional.of(config.syncMaxInFlightBytes()), Optional.empty(), List.of(), retry,
                RecordingPolicy.DEFAULT, List.of(transform1, to(2)));
        FlowDefinition alo = new FlowDefinition(ALO, "1", "in", "at-least-once", FlowDefinition.DEFAULT_SAVE_TIMEOUT, config.maxBodyBytes(),
                Optional.of(config.aloMaxInFlight()), Optional.of(config.aloMaxInFlightBytes()), Optional.of(KEY), List.of(), retry,
                RecordingPolicy.DEFAULT, List.of(new StepConfig(1, "extractKey", "key", false,
                        Map.of("key", KEY, "xpath", KEY_HEADER, "language", SimSteps.LANGUAGE)), transform2, to(3)));
        entries = Map.of(SYNC, world.entry(SYNC, () -> builder.delivery(sync, BASE)), ALO, world.entry(ALO, () -> builder.delivery(alo, BASE)));
        WorkerPolicy policy = new WorkerPolicy(Duration.ofSeconds(30), 5, Duration.ofSeconds(1));
        workers = Map.of(WORKERS.get(0), builder.worker(alo, BASE, WORKERS.get(0), policy), WORKERS.get(1), builder.worker(alo, BASE, WORKERS.get(1), policy));
    }

    private static StepConfig to(int index) {
        return new StepConfig(index, "to", PARTNER, true, Map.of("endpoint", PARTNER));
    }

    private void tick(long t) throws InterruptedException {
        tick = t;
        SplittableRandom workload = seed.tick(Stream.WORKLOAD, t);
        List<Work> units = new ArrayList<>();
        for (int k = 0; k < config.actionsPerTick(); k++) {
            Action action = config.draw(workload);
            trace.add(t, "", "DRAW " + k + " " + action.code());
            units.add(action(action, workload, t, k));
        }
        for (Runs.Held h : runs.due(t)) units.add(() -> release(h));
        SplittableRandom schedule = seed.tick(Stream.SCHEDULE, t);
        for (int i = units.size() - 1; i > 0; i--) Collections.swap(units, i, schedule.nextInt(i + 1));
        for (Work unit : units) {
            unit.run();
            oracle.bounds(runs);
        }
        advance();
    }

    /** Faults off: held calls released at their ticks, backoffs woken, both workers polled until nothing is claimed. */
    private void drain() throws InterruptedException {
        for (int d = 0; d < config.drainTicks() && !idle(); d++) {
            tick = ticks + d;
            for (Runs.Held h : runs.due(tick)) {
                release(h);
                oracle.bounds(runs);
            }
            int nothing = 0;
            for (int i = 0; nothing < WORKERS.size(); i++) {
                nothing = poll(WORKERS.get(i % WORKERS.size())) ? 0 : nothing + 1;
                oracle.bounds(runs);
            }
            advance();
        }
    }

    private boolean idle() {
        return runs.active() == 0 && !queue.hasItems(ALO).await();
    }

    private Work action(Action action, SplittableRandom w, long t, int k) {
        if (action == Action.SEND_SYNC) {
            Buffer payload = payload(w);
            return () -> send(SYNC, SYNC + "-t" + t + "-" + k, null, payload);
        }
        if (action == Action.SEND_ALO) {
            String key = config.keys().get(w.nextInt(config.keys().size()));
            Buffer payload = payload(w);
            return () -> send(ALO, ALO + "-t" + t + "-" + k, key, payload);
        }
        if (action == Action.RESEND) return () -> resend(t, k);
        String worker = WORKERS.get(w.nextInt(WORKERS.size()));
        return () -> poll(worker);
    }

    private Buffer payload(SplittableRandom w) {
        int size = Math.toIntExact(config.minPayload() + w.nextInt(Math.toIntExact(config.maxPayload() - config.minPayload() + 1)));
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) ('a' + w.nextInt(26));
        return Buffer.buffer(bytes);
    }

    private void resend(long t, int k) throws InterruptedException {
        Oracle.Pending p = oracle.resend(t);
        if (p != null) send(p.flow(), p.flow() + "-t" + t + "-" + k, p.key(), p.payload());
    }

    /** START: the send on a virtual thread of its own, as a consumer would make it. */
    private void send(String flow, String id, String key, Buffer payload) throws InterruptedException {
        world.onUnit(new Unit(tick, Unit.Kind.START, Optional.of(id)));
        Body body = blobs.write(BlobKey.body(id, 0), payload, config.maxBodyBytes()).await();
        Headers headers = key == null ? Headers.empty() : new Headers(Map.of(KEY_HEADER, key));
        Envelope e = Envelope.ingress(id, flow, headers, body);
        boolean refused = ALO.equals(flow) && (faults.wouldHit(FaultKind.STORE_REFUSE, id, 1, 0) || faults.wouldHit(FaultKind.STORE_REFUSE, id, 1, 1));
        Oracle.Send s = oracle.started(id, flow, key, payload, refused);
        Function<Envelope, Future<Ack>> entry = entries.get(flow);
        Thread thread = Thread.ofVirtual().name("sim-" + id).unstarted(() -> entry.apply(e).onComplete(answer -> oracle.answered(s, answer)));
        Runs.Run run = runs.start(id, thread);
        thread.start();
        runs.joinEnd(run);
        runs.quiesce();
        oracle.startEnded(s);
    }

    /** POLL: one poll of the worker on the driver; true when it claimed an item. */
    private boolean poll(String worker) throws InterruptedException {
        claimedInPoll = null;
        workers.get(worker).poll();
        runs.open(Thread.currentThread());   // the poll is past the start of what it claimed
        boolean claimed = claimedInPoll != null;
        if (!claimed) {
            world.onUnit(new Unit(tick, Unit.Kind.POLL, Optional.empty()));
            trace.add(tick, "", "POLL " + worker + " nothing");
        }
        runs.quiesce();
        return claimed;
    }

    /** The driver's claim handed {@code item} to {@code worker}: its run is expected, and the POLL names it. */
    private void claimed(WorkQueue.Item item, String worker) {
        claimedInPoll = item.messageId();
        runs.expect(item.messageId(), worker);
        world.onUnit(new Unit(tick, Unit.Kind.POLL, Optional.of(item.messageId())));
        trace.add(tick, item.messageId(), "POLL " + worker + " attempt " + item.attempts());
    }

    /** A fast path on a finishing run handed {@code item} to {@code worker}: its run is expected before that one ends. */
    private void taken(WorkQueue.Item item, String worker) {
        runs.expect(item.messageId(), worker);
        trace.add(tick, item.messageId(), "TAKE " + worker + " attempt " + item.attempts());
    }

    /** RELEASE: the held call answers. */
    private void release(Runs.Held h) throws InterruptedException {
        world.onUnit(new Unit(tick, Unit.Kind.RELEASE, Optional.of(h.run().id)));
        trace.add(tick, h.run().id, "RELEASE");
        runs.resume(h.run());
        h.released().complete(null);
        runs.quiesce();
    }

    /** ADVANCE by one tick, to each due timer in turn; then a WAKE for every backoff that came due, in the order it fired. */
    private void advance() throws InterruptedException {
        world.onUnit(new Unit(tick, Unit.Kind.ADVANCE, Optional.empty()));
        trace.add(tick, "", "ADVANCE");
        Instant end = clock.instant().plus(config.tick());
        while (fake.pending() > 0) {
            Instant due = clock.instant().plus(fake.nextDelay());
            if (due.isAfter(end)) break;
            clock.set(due);
        }
        clock.set(end);
        List<SimTimer.Wake> due = List.copyOf(wakes);
        wakes.clear();
        for (SimTimer.Wake w : due) {
            world.onUnit(new Unit(tick, Unit.Kind.WAKE, Optional.of(w.run().id)));
            trace.add(tick, w.run().id, "WAKE");
            runs.resume(w.run());
            w.task().run();
            runs.quiesce();
            oracle.bounds(runs);
        }
    }
}
