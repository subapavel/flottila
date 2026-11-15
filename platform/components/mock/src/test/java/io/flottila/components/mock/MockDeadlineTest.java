package io.flottila.components.mock;

import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.flottila.api.Timer;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15b (D-113 draft), beside the contract: the mock's bounded send with no consumer running. One with no time left is neither
 * recorded nor counted; a delayed answer is cut at the deadline; a re-read that fires after its send settled (its cancel came
 * too late) arms no other.
 */
class MockDeadlineTest {

    final FakeRuntime rt = FakeRuntime.create();
    final EndpointConfig partner = new EndpointConfig("partner", URI.create("http://localhost:8081/echo"), Map.of());

    Envelope message() { return rt.ingress("f", Headers.empty(), Buffer.buffer("<a/>")); }

    @Test
    void aSendWithNoTimeLeftIsNotRecordedAndADelayedAnswerIsCut() {
        MockComponent mock = new MockComponent("http", rt.blobs(), rt.timer());
        mock.bind("partner", Behaviour.from(Map.of("delay", "10s"), java.nio.file.Path.of("")));
        Producer p = mock.producer(partner);
        Future<Envelope> late = p.send(message(), () -> Duration.ZERO);
        assertTrue(late.failed() && late.cause() instanceof NackException n && n.retryable(), "no time left: a retryable Nack: " + late.cause());
        assertEquals(List.of(), mock.sent(), "not recorded");

        AtomicReference<Instant> until = new AtomicReference<>(rt.clock().instant().plusSeconds(3));
        Envelope e = message();
        Future<Envelope> delayed = p.send(e, () -> Duration.between(rt.clock().instant(), until.get()));
        assertEquals(List.of(e), mock.sent(), "recorded as given: " + mock.sent());
        rt.clock().advance(Duration.ofSeconds(3));
        NackException cut = assertInstanceOf(NackException.class, delayed.cause(), "cut at 3 s, the answer due at 10 s: " + delayed);
        assertTrue(cut.retryable(), cut.toString());
    }

    @Test
    void aReReadThatFiresAfterItsSendSettledArmsNoOther() {
        AtomicInteger schedules = new AtomicInteger();
        Timer lateCancels = (delay, task) -> {   // every cancel comes too late: the task still fires
            schedules.incrementAndGet();
            rt.timer().schedule(delay, task);
            return () -> false;
        };
        Producer p = new MockComponent("http", rt.blobs(), lateCancels).producer(partner);
        AtomicReference<Instant> until = new AtomicReference<>(rt.clock().instant().plusSeconds(5));
        Future<Envelope> echoed = p.send(message(), () -> Duration.between(rt.clock().instant(), until.get()));
        assertTrue(echoed.succeeded(), "echoed at once: " + echoed);
        assertEquals(1, schedules.get(), "fixture: one cut armed");
        until.set(rt.clock().instant().plusSeconds(10));
        rt.clock().advance(Duration.ofSeconds(5));
        assertEquals(1, schedules.get(), "a re-read for a settled send stops there: it arms no other, though time is left");
    }
}
