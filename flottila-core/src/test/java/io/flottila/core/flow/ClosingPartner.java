package io.flottila.core.flow;

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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A component of the scheme {@code mock} whose producers are a {@link RecordingComponent}'s ({@link #partner()}: records,
 * holds and fails sends) wrapped so that every producer built is an {@link Out} that counts its {@code close()} calls (FIX-30).
 * A hook runs inside every close, on the closing thread, before the close answers ({@link #onClose}); the close of the
 * {@code nth} producer built (1 = the first) can fail with a failed future or a thrown exception ({@link #failClose}), or stay
 * pending until the test completes it ({@link #holdClose}). Like a real producer, an {@link Out} refuses a send made after its
 * close (a failed future); a send already in flight at the close is answered as the partner answers it.
 */
final class ClosingPartner implements Component {

    /** How the close of a producer fails. */
    enum CloseFailure { FAILED_FUTURE, THROWN }

    private final RecordingComponent partner = new RecordingComponent("mock");
    private final List<Out> built = new CopyOnWriteArrayList<>();
    private final Map<Integer, CloseFailure> failures = new ConcurrentHashMap<>();
    private final Map<Integer, String> failureTexts = new ConcurrentHashMap<>();
    private final Map<Integer, Promise<Void>> heldCloses = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Out> onClose = o -> {};

    RecordingComponent partner() { return partner; }

    /** Every producer built so far, in build order. */
    List<Out> built() { return List.copyOf(built); }

    /** The producers built so far that were never closed. */
    List<Out> open() { return built.stream().filter(o -> o.closes() == 0).toList(); }

    /** The producers closed more than once. */
    List<Out> closedTwice() { return built.stream().filter(o -> o.closes() > 1).toList(); }

    /** Runs inside every producer's close, on the closing thread, before the close answers. */
    void onClose(java.util.function.Consumer<Out> hook) { this.onClose = Objects.requireNonNull(hook, "hook"); }

    /** The close of the {@code nth} producer built (1 = the first) fails as {@code how}, carrying {@code text}; it still counts. */
    void failClose(int nth, CloseFailure how, String text) {
        failures.put(nth, how);
        failureTexts.put(nth, text);
    }

    /**
     * The close of the {@code nth} producer built (1 = the first) counts and runs the hook, then answers the returned promise's
     * future: it stays pending until the test completes (or fails) that promise.
     */
    Promise<Void> holdClose(int nth) {
        Promise<Void> held = Promise.promise();
        heldCloses.put(nth, held);
        return held;
    }

    @Override public String scheme() { return "mock"; }

    @Override public String configSchema() { return partner.configSchema(); }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        return partner.consumer(cfg, handler);
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        Out out = new Out(built.size() + 1, cfg.name(), partner.producer(cfg));
        built.add(out);
        return out;
    }

    @Override public Future<Health> health() { return partner.health(); }

    /** One producer as built: its ordinal (1 = the first built), its endpoint, its close calls. */
    final class Out implements Producer {
        final int ordinal;
        final String endpoint;
        private final Producer inner;
        private final AtomicInteger closes = new AtomicInteger();

        Out(int ordinal, String endpoint, Producer inner) {
            this.ordinal = ordinal;
            this.endpoint = endpoint;
            this.inner = inner;
        }

        int closes() { return closes.get(); }

        @Override
        public Future<Envelope> send(Envelope e) {
            if (closes.get() > 0) return Future.failedFuture(new IllegalStateException(this + ": a send after the close"));
            return inner.send(e);
        }

        @Override
        public Future<Void> close() {
            closes.incrementAndGet();
            onClose.accept(this);
            Promise<Void> held = heldCloses.get(ordinal);
            if (held != null) return held.future();
            CloseFailure how = failures.get(ordinal);
            if (how == null) return inner.close();
            String text = failureTexts.get(ordinal);
            return switch (how) {
                case FAILED_FUTURE -> Future.failedFuture(new IllegalStateException(text));
                case THROWN -> throw new IllegalStateException(text);
            };
        }

        @Override
        public String toString() { return "producer #" + ordinal + " of " + endpoint + " (closed " + closes() + "x)"; }
    }
}
