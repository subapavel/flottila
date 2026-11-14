package io.flottila.testkit;

import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15b (D-113 draft), beside the contract: the recording component's bounded send with no consumer running. One with no time
 * left is not recorded; one built without a timer is never cut; a held send is cut on the timer; a re-read that fires after its
 * send settled (its cancel came too late) arms no other.
 */
class RecordingComponentDeadlineTest {

    final FakeRuntime rt = FakeRuntime.create();
    final EndpointConfig partner = new EndpointConfig("partner", URI.create("http://localhost:8081/in"), Map.of());

    Envelope message() { return rt.ingress("f", Headers.empty(), Buffer.buffer("<a/>")); }

    @Test
    void noTimeLeftIsNotRecordedAndAHeldSendIsCutOnlyWithATimer() throws Exception {
        RecordingComponent untimed = new RecordingComponent("http");
        Future<Envelope> late = untimed.producer(partner).send(message(), () -> Duration.ofNanos(-1));
        assertTrue(late.failed() && late.cause() instanceof NackException n && n.retryable(), "no time left: a retryable Nack: " + late.cause());
        assertEquals(List.of(), untimed.sent(), "not recorded");
        CountDownLatch untimedHold = untimed.holdNext(1);
        AtomicReference<Instant> until = new AtomicReference<>(rt.clock().instant().plusSeconds(3));
        Future<Envelope> neverCut = untimed.producer(partner).send(message(), () -> Duration.between(rt.clock().instant(), until.get()));
        rt.clock().advance(Duration.ofSeconds(4));
        assertTrue(!neverCut.isComplete(), "no timer: a held send waits past its deadline: " + neverCut);
        untimedHold.countDown();
        assertTrue(neverCut.toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS) != null, "released: answered");

        RecordingComponent timed = new RecordingComponent("http", rt.timer());
        CountDownLatch hold = timed.holdNext(1);
        until.set(rt.clock().instant().plusSeconds(3));
        Future<Envelope> held = timed.producer(partner).send(message(), () -> Duration.between(rt.clock().instant(), until.get()));
        rt.clock().advance(Duration.ofSeconds(3));
        NackException cut = assertInstanceOf(NackException.class, held.cause(), "cut at its deadline: " + held);
        assertTrue(cut.retryable(), cut.toString());
        hold.countDown();
    }

    @Test
    void aReReadThatFiresAfterItsSendSettledArmsNoOther() {
        AtomicInteger schedules = new AtomicInteger();
        Timer lateCancels = (delay, task) -> {   // every cancel comes too late: the task still fires
            schedules.incrementAndGet();
            rt.timer().schedule(delay, task);
            return () -> false;
        };
        Producer p = new RecordingComponent("http", lateCancels).producer(partner);
        AtomicReference<Instant> until = new AtomicReference<>(rt.clock().instant().plusSeconds(5));
        Future<Envelope> answered = p.send(message(), () -> Duration.between(rt.clock().instant(), until.get()));
        assertTrue(answered.succeeded(), "answered at once: " + answered);
        assertEquals(1, schedules.get(), "fixture: one cut armed");
        until.set(rt.clock().instant().plusSeconds(10));
        rt.clock().advance(Duration.ofSeconds(5));
        assertEquals(1, schedules.get(), "a re-read for a settled send stops there: it arms no other, though time is left");
    }
}
