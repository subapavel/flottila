package dev.rail8.examples.webhook;

import dev.rail8.api.Ack;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.NackException;
import dev.rail8.api.Producer;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the contract suite leaves to the component: the statuses it answers and how it reads a partner's refusal. */
class WebhookComponentTest {

    static Vertx vertx;
    final FakeRuntime rt = FakeRuntime.create();
    final WebhookComponent webhook = new WebhookComponent(vertx, rt.blobs(), rt.ids(), rt.timer());

    @BeforeAll
    static void start() { vertx = Vertx.vertx(); }

    @AfterAll
    static void stop() { vertx.close().await(); }

    @Test
    void theConsumerAnswersAFinalRefusal400AnotherPath404AnotherMethod405AndABodyAboveItsLimit413() {
        Consumer consumer = webhook.consumer(new EndpointConfig("in", URI.create("webhook://in"),
                Map.of("path", "/in", "maxBodyBytes", 8, "flow", "test")), e -> Future.succeededFuture(new Ack.Nack("no such order", false)));
        consumer.start().await();
        HttpClientAgent client = vertx.createHttpClient();
        try {
            int port = ((WebhookComponent.WebhookConsumer) consumer).port();
            assertEquals(400, status(client, HttpMethod.POST, port, "/in", "order"));
            assertEquals(404, status(client, HttpMethod.POST, port, "/other", "order"));
            assertEquals(405, status(client, HttpMethod.GET, port, "/in", ""));
            assertEquals(413, status(client, HttpMethod.POST, port, "/in", "nine bytes"));
        } finally {
            client.close().await();
            consumer.stop().await();
        }
    }

    @Test
    void theConsumerAnswersAnAcceptedMessage202WithItsId() {
        AtomicReference<String> accepted = new AtomicReference<>();
        Consumer consumer = webhook.consumer(new EndpointConfig("in", URI.create("webhook://in"),
                Map.of("path", "/in", "maxBodyBytes", 1024, "flow", "test")), e -> {
            accepted.set(e.id());
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        consumer.start().await();
        HttpClientAgent client = vertx.createHttpClient();
        try {
            int port = ((WebhookComponent.WebhookConsumer) consumer).port();
            HttpClientResponse res = client.request(HttpMethod.POST, port, "127.0.0.1", "/in").compose(req -> req.send("order")).await();
            assertEquals(202, res.statusCode());
            assertEquals(accepted.get(), res.getHeader("x-message-id"), "the id of the message the handler accepted");
        } finally {
            client.close().await();
            consumer.stop().await();
        }
    }

    @Test
    void aPartnersRefusalFailsTheSendRetryableFor429And5xxAndFinalOtherwise() {
        for (int status : new int[] {400, 404, 429, 500, 503}) {
            HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> req.response().setStatusCode(status).end("why: " + status)))
                    .listen(0, "127.0.0.1").await();
            Producer producer = webhook.producer(new EndpointConfig("partner", URI.create("webhook://partner"),
                    Map.of("url", "http://127.0.0.1:" + partner.actualPort() + "/", "maxBodyBytes", 1024)));
            try {
                Future<?> sent = producer.send(rt.ingress("test", dev.rail8.api.Headers.empty(), Buffer.buffer("order")));
                sent.otherwiseEmpty().await();
                NackException nack = assertInstanceOf(NackException.class, sent.cause(), status + ": " + sent.cause());
                assertEquals(status == 429 || status >= 500, nack.retryable(), status + ": " + nack);
                assertTrue(nack.reason().contains("why: " + status), "the partner's reason travels: " + nack.reason());
            } finally {
                producer.close().await();
                partner.close().await();
            }
        }
    }

    @Test
    void theTargetIsTheUrlsSchemeHostAndPort() {
        assertEquals(Optional.of("http://partner.example:80"), webhook.target(partner("http://Partner.example/orders?x=1")));
        assertEquals(Optional.of("https://partner.example:443"), webhook.target(partner("https://partner.example/orders")));
        assertEquals(Optional.of("http://partner.example:8443"), webhook.target(partner("http://partner.example:8443")));
        assertEquals(Optional.empty(), webhook.target(new EndpointConfig("in", URI.create("webhook://in"), Map.of())));
    }

    static EndpointConfig partner(String url) {
        return new EndpointConfig("partner", URI.create("webhook://partner"), Map.of("url", url, "maxBodyBytes", 1024));
    }

    static int status(HttpClientAgent client, HttpMethod method, int port, String path, String body) {
        return client.request(method, port, "127.0.0.1", path).compose(req -> req.send(body)).await().statusCode();
    }
}
