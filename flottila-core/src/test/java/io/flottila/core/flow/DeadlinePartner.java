package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.Deadline;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * P2-15b: a partner component at the SPI boundary whose producer overrides {@code send(Envelope, Deadline)} and records every
 * call with the {@link Deadline} it was given — the test reads {@code remaining()} on it whenever it likes (it is live) — and
 * every call of the plain {@code send(Envelope)} too, which a builder that bounds its sends must never make. Every call stays
 * unanswered until the test answers it ({@link Call#reply}, {@link Call#fail}). Its target is {@value #TARGET}, so a
 * {@code Targets} limit of that key applies.
 */
final class DeadlinePartner implements Component {

    static final String TARGET = "partner://p:1";

    /** One send: the envelope, the deadline given ({@code null} for a plain {@code send(e)}), its answer. */
    record Call(Envelope envelope, Deadline deadline, Promise<Envelope> answer) {
        Duration remaining() { return deadline.remaining(); }

        void reply() { answer.tryComplete(envelope.withHeader("replied-by", "partner")); }

        void fail(Throwable failure) { answer.tryFail(failure); }
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final List<Call> plainCalls = new CopyOnWriteArrayList<>();

    @Override public String scheme() { return "partner"; }
    @Override public String configSchema() { return "{}"; }
    @Override public Optional<String> target(EndpointConfig cfg) { return Optional.of(TARGET); }
    @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
    @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

    @Override public Producer producer(EndpointConfig cfg) {
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e, Deadline d) {
                Call call = new Call(e, d, Promise.promise());
                calls.add(call);   // published after the promise exists: a test that sees the call can answer it
                return call.answer().future();
            }

            @Override public Future<Envelope> send(Envelope e) {
                Call call = new Call(e, null, Promise.promise());
                plainCalls.add(call);
                return call.answer().future();
            }

            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    Call call(int i) { return calls.get(i); }

    /** Answers every call still open (a teardown: no worker stop waits on a held send). */
    void replyAll() {
        calls.forEach(Call::reply);
        plainCalls.forEach(Call::reply);
    }
}
