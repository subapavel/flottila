package io.flottila.sim;

import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.NackException;
import io.flottila.api.Ack;
import io.flottila.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The partner every {@code to} sends to (scheme {@code sim}, D-101): it answers {@code ack:} and the body it got. Per call, in
 * this order: REFUSE fails the call before any effect (a retryable {@link NackException}); DELAY holds it — the run is HELD
 * until the driver's RELEASE at its tick, parked in {@code send} — and then answers. The oracle sees each call start and end.
 * It names no target: each {@code to} gets an unshared limit above every flow's maxInFlight.
 */
final class SimPartner implements Component, Producer {

    static final String SCHEME = "sim";
    static final String REPLY_PREFIX = "ack:";

    private final Faults faults;
    private final Oracle oracle;
    private final Runs runs;
    private final Trace trace;
    private final LongSupplier tick;
    private final Map<String, Integer> ordinals = new ConcurrentHashMap<>();   // per message id and attempt: the next call's

    SimPartner(Faults faults, Oracle oracle, Runs runs, Trace trace, LongSupplier tick) {
        this.faults = faults;
        this.oracle = oracle;
        this.runs = runs;
        this.trace = trace;
        this.tick = tick;
    }

    @Override public String scheme() { return SCHEME; }

    @Override public String configSchema() { return "{}"; }

    @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        throw new UnsupportedOperationException("the simulation's driver sends; the partner consumes nothing");
    }

    @Override public Producer producer(EndpointConfig cfg) { return this; }

    @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

    @Override
    public Future<Envelope> send(Envelope e) {
        String id = e.id();
        int attempt = e.get(Envelope.ATTEMPT).orElse(1);
        int ordinal = ordinals.merge(id + "#" + attempt, 1, Integer::sum) - 1;
        String key = e.key(SimRun.KEY).orElse(null);
        oracle.call(id, key, ordinal);
        if (faults.hit(FaultKind.REFUSE, id, attempt, ordinal)) {
            trace.add(tick.getAsLong(), id, "CALL " + attempt + "/" + ordinal + " refused");
            oracle.called(id, key, ordinal, false);
            return Future.failedFuture(new NackException("REFUSE: the partner refused call " + ordinal + " of " + id, true));
        }
        if (faults.hit(FaultKind.DELAY, id, attempt, ordinal)) {
            int ticks = faults.heldTicks(id, attempt, ordinal);
            trace.add(tick.getAsLong(), id, "CALL " + attempt + "/" + ordinal + " held " + ticks);
            CompletableFuture<Void> released = new CompletableFuture<>();
            runs.hold(tick.getAsLong() + ticks, released);
            released.join();   // parked until the driver's RELEASE; never interrupted, never fails
        }
        Buffer reply = Buffer.buffer(REPLY_PREFIX).appendBuffer(e.body().whole());
        Body body = e.body().store().write(e.nextKey(), reply, e.body().store().treeLimit()).await();
        trace.add(tick.getAsLong(), id, "CALL " + attempt + "/" + ordinal + " delivered " + body.hash().value());
        oracle.called(id, key, ordinal, true);
        return Future.succeededFuture(e.next(body));
    }

    @Override public Future<Void> close() { return Future.succeededFuture(); }
}
