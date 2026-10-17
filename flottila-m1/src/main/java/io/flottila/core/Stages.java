package io.flottila.core;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** EIP vocabulary as factory methods. Every stage is stateless as an object. */
public final class Stages {
    private Stages() {}

    /**
     * 1→1. Retries per policy; final failure is parked in the DLQ and the message is dropped
     * from the stream (the stream itself never dies). Step counter increments on success.
     */
    public static Stage step(String name, Step step, StageContext ctx) {
        return in -> in.onItem().transformToUni(e -> apply(name, step, e, ctx))
                .merge(ctx.concurrency())               // .concatenate() for strict ordering
                .select().where(e -> e != DROPPED);
    }

    private static Uni<Envelope> apply(String name, Step step, Envelope e, StageContext ctx) {
        long t0 = System.nanoTime();
        Uni<Envelope> u = Uni.createFrom().deferred(() -> step.apply(e));
        var r = ctx.retry();
        if (r.attempts() > 0) {
            u = u.onFailure().retry().withBackOff(r.initialBackoff(), r.maxBackoff()).atMost(r.attempts());
        }
        return u.onItem().transform(out -> out.withStep(e.step() + 1))
                .onItem().invoke(out -> ctx.monitor().snapshot(name, e, out, System.nanoTime() - t0, null))
                .onFailure().recoverWithItem(t -> {
                    ctx.monitor().snapshot(name, e, null, System.nanoTime() - t0, t);
                    ctx.dlq().park(name, e, t);
                    return DROPPED;
                });
    }

    private static final Envelope DROPPED = Envelope.ingress("<dropped>", Map.of(), Body.empty());

    /** 1→0/1 */
    public static Stage filter(Predicate<Envelope> p) {
        return in -> in.select().where(p);
    }

    /** 1→N, ordered. */
    public static Stage split(Function<Envelope, List<Envelope>> splitter) {
        return in -> in.onItem().transformToMultiAndConcatenate(e -> Multi.createFrom().iterable(splitter.apply(e)));
    }

    public record Route(Predicate<Envelope> when, Stage target) {}

    /** Content-based router: first matching predicate wins; the branch is itself a Stage. */
    public static Stage route(List<Route> routes, Stage otherwise) {
        return in -> in.onItem().transformToMultiAndConcatenate(e -> {
            for (Route r : routes) if (r.when().test(e)) return r.target().apply(Multi.createFrom().item(e));
            return otherwise.apply(Multi.createFrom().item(e));
        });
    }
}
