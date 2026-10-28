package io.flottila.components.mock;

import io.flottila.api.Ack;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mock as a polling consumer (D-076): {@code files:} fixtures of a {@code *.test.yaml} and its one-second rounds on the injected timer. */
class MockPollingTest {

    final FakeRuntime rt = FakeRuntime.create();
    final MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());
    final EndpointConfig in = new EndpointConfig("sftp-in", URI.create("sftp://partner.example/inbox"), Map.of());
    final List<String> seen = new CopyOnWriteArrayList<>();
    final Function<Envelope, Future<Ack>> accept = e -> {
        seen.add(e.body().whole().toString());
        return Future.succeededFuture(new Ack.Accepted(e.id()));
    };

    @TempDir
    Path base;

    static boolean soon(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    /** Waits until the consumer's next round is scheduled, then moves the fake clock to it. */
    void nextRound() {
        assertTrue(soon(() -> rt.timer().pending() > 0), "a round is scheduled");
        rt.clock().advance(rt.timer().nextDelay());
    }

    @Test
    void filesFixturesMatchingTheGlobAreFedAtTheConsumersStartAndDeliveredInNameOrder() throws IOException {
        Path inbox = Files.createDirectories(base.resolve("fixtures/inbox"));
        Files.writeString(inbox.resolve("b.xml"), "<b/>");
        Files.writeString(inbox.resolve("a.xml"), "<a/>");
        Files.writeString(inbox.resolve("c.txt"), "not matched by the glob");
        sftp.bindAll(Map.of("sftp-in", Map.of("files", "fixtures/inbox/*.xml")), base);
        Consumer consumer = sftp.consumer(in, accept);
        assertEquals(Set.of(), Set.copyOf(sftp.remaining("sftp-in")), "nothing is fed before a consumer starts");
        consumer.start().await();
        try {
            assertEquals(Set.of("a.xml", "b.xml"), Set.copyOf(sftp.remaining("sftp-in")), "the fixtures the glob matches, resolved against the test file's directory, fed at the start");
            nextRound();
            assertTrue(soon(() -> seen.size() == 2), "one round delivered both fixtures: " + seen);
            assertEquals(List.of("<a/>", "<b/>"), seen, "in name order");
            assertTrue(soon(() -> sftp.remaining("sftp-in").isEmpty()), "accepted: removed: " + sftp.remaining("sftp-in"));
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    void theMockConsumerPollsEverySecondOnTheInjectedTimer() {
        Consumer consumer = sftp.consumer(in, accept);
        consumer.start().await();
        try {
            nextRound();   // round 0: nothing to deliver
            assertTrue(soon(() -> rt.timer().pending() > 0), "the next round is scheduled once a round ended");
            assertEquals(Duration.ofSeconds(1), rt.timer().nextDelay(), "a round every second");
            sftp.drop("sftp-in", "x.xml", Buffer.buffer("<x/>"));
            rt.clock().advance(Duration.ofSeconds(1));
            assertTrue(soon(() -> seen.size() == 1), "the next round delivered the dropped file: " + seen);
            assertEquals(List.of("<x/>"), seen);
        } finally {
            consumer.stop().await();
        }
    }
}
