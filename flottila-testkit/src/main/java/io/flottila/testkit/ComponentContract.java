package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Headers;
import io.flottila.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The cases every component passes, real or mock. A component's test extends this class and supplies
 * the component and an endpoint that selects it.
 *
 * @since 0.1
 */
public abstract class ComponentContract {

    /** A fresh component under test; called once per test case. */
    protected abstract Component component();

    /** An endpoint whose URI scheme selects {@link #component()}. */
    protected abstract EndpointConfig endpoint();

    @Test
    public void schemeMatchesTheEndpointUri() {
        String scheme = component().scheme();
        assertNotNull(scheme, "scheme");
        assertFalse(scheme.isBlank(), "scheme must not be blank");
        assertEquals(endpoint().scheme(), scheme);
    }

    @Test
    public void configSchemaIsAJsonObject() {
        new JsonObject(component().configSchema());
    }

    @Test
    public void consumerStartsAndStops() {
        Consumer consumer = component().consumer(endpoint(), e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        consumer.start().await();
        consumer.stop().await();
    }

    @Test
    public void healthIsUpWhileAConsumerRuns() {
        Component component = component();
        Consumer consumer = component.consumer(endpoint(), e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        consumer.start().await();
        try {
            assertEquals(Health.Status.UP, component.health().await().status());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void producerDeliversToTheRunningConsumersHandler() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(endpoint(), e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Reply(e.withHeader("x-seen", "1")));
        });
        consumer.start().await();
        Producer producer = component.producer(endpoint());
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("ping"));
            Envelope reply = producer.send(sent).await();
            assertEquals("1", reply.header("x-seen").orElse(null));
            assertEquals(1, seen.size());
            assertEquals(sent.id(), seen.get(0).id());
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }
}
