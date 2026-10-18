package io.flottila.runtime.time;

import io.flottila.api.Timer;
import io.vertx.core.Vertx;

import java.time.Duration;
import java.util.Objects;

/**
 * The runtime's {@link Timer}: Vert.x one-shot timers. Tasks run on an event-loop thread and must
 * not block; the batcher's window flush only hands a batch to its sink.
 *
 * @since 0.1
 */
public final class VertxTimer implements Timer {

    private final Vertx vertx;

    public VertxTimer(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

    @Override public Cancellable schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (delay.isNegative()) throw new IllegalArgumentException("negative delay: " + delay);
        long millis = Math.max(1L, delay.toMillis());   // Vert.x timers start at 1 ms
        long id = vertx.setTimer(millis, ignored -> task.run());
        return () -> vertx.cancelTimer(id);
    }
}
