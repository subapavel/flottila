package io.flottila.sim;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.core.flow.Delivery;
import io.vertx.core.Future;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Broken variant DOUBLE_ADMISSION (D-101): every flow is built twice — two deliveries, two admissions — and its sends go to
 * them alternately, so up to twice the flow's limits are admitted. Built over the public {@link Delivery} only.
 */
final class DoubleAdmission implements World {

    static final String NAME = "double-admission";

    @Override public String name() { return NAME; }

    @Override public Function<Envelope, Future<Ack>> entry(String flow, Supplier<Delivery> deliveries) {
        Delivery first = deliveries.get();
        Delivery second = deliveries.get();
        AtomicLong sends = new AtomicLong();
        return e -> (sends.getAndIncrement() % 2 == 0 ? first : second).apply(e);
    }
}
