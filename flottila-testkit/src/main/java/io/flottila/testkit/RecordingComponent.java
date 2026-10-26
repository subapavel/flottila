package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The mock binding of a scheme for flow tests (ARCHITECTURE §11: a flow file is byte-identical everywhere, the
 * mocks live next to it). A producer records every envelope it is sent, per endpoint, and answers: with the
 * reply of the consumer running on that same endpoint (the loopback of the contract suite), else
 * with {@link #replyWith(Function)} (identity by default), or with the next failure queued by
 * {@link #failNext(int, Supplier)} — the way a test plays "partner down" for a retry. Each delivery calls a consumer's handler
 * on a virtual thread of its own (D-057); a consumer's {@code stop()} completes once the answers its handler handed out have
 * completed (D-056). Health is up while a consumer runs. Passes {@link ComponentContract}.
 *
 * @since 0.1
 */
public final class RecordingComponent implements Component {

    private final String scheme;
    private final List<Envelope> sent = new CopyOnWriteArrayList<>();
    private final Map<String, List<Envelope>> sentTo = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Supplier<? extends Throwable>> failures = new ConcurrentLinkedDeque<>();
    private final AtomicInteger producersBuilt = new AtomicInteger();
    private volatile Function<Envelope, Envelope> reply = e -> e;
    private final Map<String, Function<Envelope, Future<Ack>>> handlers = new ConcurrentHashMap<>();   // by endpoint name
    private final ConcurrentLinkedDeque<CountDownLatch> holds = new ConcurrentLinkedDeque<>();
    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();

    public RecordingComponent(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
    }

    /** What the producers were sent, in order. */
    public List<Envelope> sent() { return List.copyOf(sent); }

    /** What the producer of the endpoint {@code name} was sent, in order. */
    public List<Envelope> sentTo(String name) { return List.copyOf(sentTo.getOrDefault(name, List.of())); }

    /** Producers built so far, across endpoints. */
    public int producersBuilt() { return producersBuilt.get(); }

    /** The reply a send gets while no consumer runs; the function sees the envelope as sent. */
    public RecordingComponent replyWith(Function<Envelope, Envelope> reply) {
        this.reply = Objects.requireNonNull(reply, "reply");
        return this;
    }

    /** The next {@code times} sends fail with a fresh exception from {@code failure}, before any reply. */
    public RecordingComponent failNext(int times, Supplier<? extends Throwable> failure) {
        Objects.requireNonNull(failure, "failure");
        if (times < 0) throw new IllegalArgumentException("times must not be negative, was " + times);
        for (int i = 0; i < times; i++) failures.addLast(failure);
        return this;
    }

    /** Failures queued and not yet used. */
    public int failuresLeft() { return failures.size(); }

    /**
     * The next {@code times} sends are recorded at once (they count for {@link #awaitSent(int)}) and answered only after
     * the returned latch is counted down — the way a test holds a message "at the partner". The send's future is pending
     * meanwhile; no thread of the caller waits. A queued {@link #failNext} failure applies after the release.
     */
    public CountDownLatch holdNext(int times) {
        if (times < 0) throw new IllegalArgumentException("times must not be negative, was " + times);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < times; i++) holds.addLast(release);
        return release;
    }

    /**
     * Waits until at least {@code n} envelopes have been sent, across endpoints, on a latch counted down by the sends
     * (never a sleep). Bounded by {@link #AWAIT_BOUND}: an {@link IllegalStateException} names how many arrived.
     */
    public void awaitSent(int n) {
        awaitSent(n, AWAIT_BOUND);
    }

    /** {@link #awaitSent(int)} bounded by {@code bound} instead of {@link #AWAIT_BOUND}. */
    public void awaitSent(int n, Duration bound) {
        Objects.requireNonNull(bound, "bound");
        Waiter w = new Waiter(n, new CountDownLatch(1));
        waiters.add(w);
        try {
            if (sent.size() >= n) w.latch().countDown();
            if (!w.latch().await(bound.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(sent.size() + " of " + n + " sends arrived within " + bound);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for " + n + " sends, " + sent.size() + " arrived", interrupted);
        } finally {
            waiters.remove(w);
        }
    }

    /** How long {@link #awaitSent(int)} waits at most. */
    public static final Duration AWAIT_BOUND = Duration.ofSeconds(10);

    private record Waiter(int count, CountDownLatch latch) {}

    @Override public String scheme() { return scheme; }

    @Override public String configSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> h) {
        Objects.requireNonNull(h, "handler");
        Objects.requireNonNull(cfg, "cfg");
        Drain drain = new Drain();
        Function<Envelope, Future<Ack>> tracked = e -> drain.deliver(h, e);
        return new Consumer() {
            @Override public Future<Void> start() { handlers.put(cfg.name(), tracked); return Future.succeededFuture(); }
            /** Unregisters the handler at once; completes once every answer it handed out has completed (D-056). */
            @Override public Future<Void> stop() { handlers.remove(cfg.name(), tracked); return drain.stop(); }
        };
    }

    /**
     * The answers a consumer's handler handed out and that are still outstanding (D-056): the consumer itself holds one more
     * until {@link #stop}, so the count reaches zero — and the drain completes — only after the stop and the last answer.
     */
    private static final class Drain {
        private final Promise<Void> drained = Promise.promise();
        private final Promise<Void> stopping = Promise.promise();   // completes once, however often stop() is called
        private int open = 1;                                       // guarded by this: the consumer's own hold plus each outstanding answer

        Drain() {
            stopping.future().onComplete(stopped -> release());
        }

        /**
         * Calls {@code handler} with {@code e} on a virtual thread of its own — one per delivery, never the sender's (D-057) —
         * and returns its answer, counted until it completes before the handler runs: a stop issued meanwhile waits for it.
         * A handler that throws or answers null fails the answer ({@link Future#future} catches what its handler throws).
         */
        Future<Ack> deliver(Function<Envelope, Future<Ack>> handler, Envelope e) {
            Promise<Ack> answer = Promise.promise();
            synchronized (this) { open++; }
            answer.future().onComplete(done -> release());
            Thread.ofVirtual().name("recording-handler").start(() -> Future.<Ack>future(p -> handler.apply(e).onComplete(p)).onComplete(answer));
            return answer.future();
        }

        private void release() {
            boolean last;
            synchronized (this) { last = --open == 0; }
            if (last) drained.tryComplete();
        }

        /** Completes once no answer is outstanding, at once when none is; the same future for every call. */
        Future<Void> stop() {
            stopping.tryComplete();
            return drained.future();
        }
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        producersBuilt.incrementAndGet();
        List<Envelope> mine = sentTo.computeIfAbsent(cfg.name(), k -> new CopyOnWriteArrayList<>());
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                sent.add(e);
                mine.add(e);
                for (Waiter w : waiters) if (sent.size() >= w.count()) w.latch().countDown();
                CountDownLatch hold = holds.pollFirst();
                if (hold == null) return answer(e);
                Promise<Envelope> held = Promise.promise();
                // the caller gets a pending future; the wait for the release happens on a virtual thread of its own
                Thread.ofVirtual().name("recording-hold-" + scheme).start(() -> {
                    try {
                        hold.await();
                    } catch (InterruptedException interrupted) {
                        held.fail(interrupted);
                        return;
                    }
                    answer(e).onComplete(held);
                });
                return held.future();
            }

            private Future<Envelope> answer(Envelope e) {
                Supplier<? extends Throwable> failure = failures.pollFirst();
                if (failure != null) return Future.failedFuture(failure.get());
                Function<Envelope, Future<Ack>> running = handlers.get(cfg.name());
                if (running != null) {
                    return running.apply(e).compose(ack -> switch (ack) {
                        case Ack.Reply r -> Future.succeededFuture(r.reply());
                        case Ack.Accepted a -> Future.succeededFuture(e);
                        case Ack.Nack n -> Future.failedFuture(new NackException(n.reason(), n.retryable()));
                    });
                }
                return Future.succeededFuture(reply.apply(e));
            }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    @Override
    public Future<Health> health() {
        return Future.succeededFuture(handlers.isEmpty() ? Health.down("no consumer running") : Health.up());
    }
}
