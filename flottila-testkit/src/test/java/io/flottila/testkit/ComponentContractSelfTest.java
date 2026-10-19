package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * The contract suite is only worth its name if every case fails for the component that breaks exactly that property.
 * One broken variant per property, run through the JUnit engine, asserting the exact set of failing cases.
 */
class ComponentContractSelfTest {

    static final EndpointConfig LOOP = new EndpointConfig("loop", URI.create("loopback://x"), Map.of());

    /** A loopback component with one thing wrong, chosen by the variant. */
    static final class Variant implements Component {
        final String scheme;
        final String schema;
        final boolean handlerSurvivesStop;
        final boolean healthAlwaysUp;
        final boolean ackBeforeHandler;
        final boolean nackIsSuccess;
        final UnaryOperator<Envelope> deliveredAs;
        final UnaryOperator<Envelope> repliedAs;
        private Function<Envelope, Future<Ack>> handler;

        Variant(String scheme, String schema, boolean handlerSurvivesStop, boolean healthAlwaysUp, boolean ackBeforeHandler, boolean nackIsSuccess,
                UnaryOperator<Envelope> deliveredAs, UnaryOperator<Envelope> repliedAs) {
            this.scheme = scheme; this.schema = schema; this.handlerSurvivesStop = handlerSurvivesStop; this.healthAlwaysUp = healthAlwaysUp;
            this.ackBeforeHandler = ackBeforeHandler; this.nackIsSuccess = nackIsSuccess; this.deliveredAs = deliveredAs; this.repliedAs = repliedAs;
        }

        static Variant correct() { return new Variant("loopback", "{\"type\":\"object\"}", false, false, false, false, e -> e, e -> e); }

        @Override public String scheme() { return scheme; }
        @Override public String configSchema() { return schema; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> h) {
            return new Consumer() {
                @Override public Future<Void> start() { handler = h; return Future.succeededFuture(); }
                @Override public Future<Void> stop() { if (!handlerSurvivesStop) handler = null; return Future.succeededFuture(); }
            };
        }
        @Override public Producer producer(EndpointConfig cfg) {
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e) {
                    Function<Envelope, Future<Ack>> h = handler;
                    if (h == null) return Future.failedFuture(new IllegalStateException("no consumer running"));
                    Future<Ack> ack = h.apply(deliveredAs.apply(e));
                    if (ackBeforeHandler) return Future.succeededFuture(e);
                    return ack.compose(a -> switch (a) {
                        case Ack.Reply r -> Future.succeededFuture(repliedAs.apply(r.reply()));
                        case Ack.Accepted ok -> Future.succeededFuture(e);
                        case Ack.Nack n -> nackIsSuccess ? Future.succeededFuture(e) : Future.failedFuture(new IllegalStateException("nack: " + n.reason()));
                    });
                }
                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
        @Override public Future<Health> health() { return Future.succeededFuture(healthAlwaysUp || handler != null ? Health.up() : Health.down("no consumer")); }
    }

    /** The engine instantiates the suite itself; the variant under test is handed over through this slot. */
    static volatile Component current = Variant.correct();

    static class VariantContractTest extends ComponentContract {
        @Override protected Component component() { return current; }
        @Override protected EndpointConfig endpoint() { return LOOP; }
    }

    static final EndpointConfig EPHEMERAL = new EndpointConfig("in", URI.create("ephemeral://in"), Map.of());   // no address before start

    /** An address assigned at start, but the suite never asks the started consumer for it: every producer targets {@link #endpoint()}. */
    static class EphemeralWithoutOverrideContractTest extends ComponentContract {
        @Override protected Component component() { return new EphemeralAddressComponent(); }
        @Override protected EndpointConfig endpoint() { return EPHEMERAL; }
        @Override protected Duration patience() { return Duration.ofMillis(200); }   // a handler never invoked is waited out
    }

    /** The producer reaches the started consumer's address, but {@link Consumer#stop()} leaves the handler registered there. */
    static class EphemeralStopKeepsTheAddressContractTest extends ComponentContract {
        @Override protected Component component() { return new EphemeralAddressComponent(true); }
        @Override protected EndpointConfig endpoint() { return EPHEMERAL; }
        @Override protected EndpointConfig producerEndpoint(Consumer started) {
            return new EndpointConfig("in", URI.create("ephemeral://in"), Map.of("address", ((EphemeralAddressComponent.EphemeralConsumer) started).address()));
        }
    }

    static Set<String> failing(Component variant) {
        current = variant;
        return failing(VariantContractTest.class);
    }

    static Set<String> failing(Class<? extends ComponentContract> suite) {
        Events events = EngineTestKit.engine("junit-jupiter").selectors(selectClass(suite)).execute().testEvents();
        assertEquals(0, events.skipped().count(), "nothing skipped");
        Set<String> failed = new TreeSet<>();
        events.failed().stream().forEach(e -> failed.add(e.getTestDescriptor().getDisplayName().replace("()", "")));
        return failed;
    }

    @Test
    void theCorrectLoopbackPassesEveryCase() {
        assertEquals(Set.of(), failing(Variant.correct()));
    }

    @Test
    void eachBrokenPropertyFailsExactlyItsCases() {
        Variant ok = Variant.correct();
        assertEquals(Set.of("schemeMatchesTheEndpointUri"), failing(new Variant("other", ok.schema, false, false, false, false, e -> e, e -> e)), "wrong scheme");
        assertEquals(Set.of("schemeMatchesTheEndpointUri"), failing(new Variant(" ", ok.schema, false, false, false, false, e -> e, e -> e)), "blank scheme");
        assertEquals(Set.of("configSchemaIsAJsonObjectSchema"), failing(new Variant("loopback", "{\"type\":\"string\"}", false, false, false, false, e -> e, e -> e)), "schema not an object schema");
        assertEquals(Set.of("configSchemaIsAJsonObjectSchema"), failing(new Variant("loopback", "not json", false, false, false, false, e -> e, e -> e)), "schema not JSON");
        assertEquals(Set.of("aStoppedConsumerReceivesNothing", "healthIsUpWhileAConsumerRunsAndDownAfterItStopped"),
                failing(new Variant("loopback", ok.schema, true, false, false, false, e -> e, e -> e)), "the handler survives stop: delivers after stop and reports up");
        assertEquals(Set.of("healthIsUpWhileAConsumerRunsAndDownAfterItStopped"), failing(new Variant("loopback", ok.schema, false, true, false, false, e -> e, e -> e)), "health always up");
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged"),
                failing(new Variant("loopback", ok.schema, false, false, false, false, e -> e, e -> e.withHeader("x-seen", "dropped").withHeader("x-acked", "dropped"))), "reply dropped (headers replaced)");
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged", "aNackFromTheHandlerFailsTheSendWithItsReason"),
                failing(new Variant("loopback", ok.schema, false, false, true, false, e -> e, e -> e)), "acknowledged before the handler answered");
        assertEquals(Set.of(), failing(new Variant("loopback", ok.schema, false, false, false, false, e -> e.withId("receiver-minted"), e -> e)),
                "a receiver that mints its own id (every real transport does) passes: the id is not what crosses");
        assertEquals(Set.of("aNackFromTheHandlerFailsTheSendWithItsReason"), failing(new Variant("loopback", ok.schema, false, false, false, true, e -> e, e -> e)), "a NACK treated as success");
        Body truncated = Body.of(FakeRuntime.create().blobs(), io.flottila.api.BlobKey.body("t", 0), new Hash("sha256-64", 0), 0);
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "aBodyAboveTheTreeLimitStreamsThroughIntact", "aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain"),
                failing(new Variant("loopback", ok.schema, false, false, false, false, e -> e.next(truncated), e -> e)), "the body does not arrive intact");
        current = Variant.correct();
    }

    @Test
    void anAddressAssignedAtStartIsReachedOnlyThroughTheStartedConsumersProducerEndpoint() {
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged",
                        "aNackFromTheHandlerFailsTheSendWithItsReason", "aBodyAboveTheTreeLimitStreamsThroughIntact", "aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain"),
                failing(EphemeralWithoutOverrideContractTest.class),
                "without producerEndpoint the producer has no address: every case that needs a delivery fails, the component delivers only to the address named");
    }

    @Test
    void aStoppedConsumerWhoseAddressStaysRegisteredFailsTheStoppedAndHealthCases() {
        assertEquals(Set.of("aStoppedConsumerReceivesNothing", "healthIsUpWhileAConsumerRunsAndDownAfterItStopped"),
                failing(EphemeralStopKeepsTheAddressContractTest.class),
                "stop() leaves the address registered: the producer built from where the consumer was still delivers, health stays up");
    }
}
