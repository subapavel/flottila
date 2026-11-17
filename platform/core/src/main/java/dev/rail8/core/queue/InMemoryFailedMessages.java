package dev.rail8.core.queue;

import dev.rail8.core.dlq.FailedMessages;
import io.vertx.core.Future;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link FailedMessages} over an {@link InMemoryWorkQueue} and the DLQ it parks in: the same rules as the Postgres one, each act
 * one step under the queue's monitor. Its events are counted from 1 and kept nowhere; a {@link FailedMessages.Done} names no node,
 * as this queue holds no bodies. Tests and dry runs only.
 *
 * @since 0.1
 */
public final class InMemoryFailedMessages implements FailedMessages {

    private final InMemoryWorkQueue queue;

    public InMemoryFailedMessages(InMemoryWorkQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    @Override
    public Future<Act> retry(String messageId, String flow, String partitionKey, int attempt, Actor by) {
        return Future.succeededFuture(queue.retry(messageId, flow, partitionKey, attempt, by));
    }

    @Override
    public Future<Act> cancel(String messageId, Actor by, Optional<String> reason) {
        return Future.succeededFuture(queue.cancel(messageId, by, reason));
    }
}
