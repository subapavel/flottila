package io.flottila.components.mock;

import io.flottila.api.Ack;
import io.flottila.api.Consumer;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.flottila.components.mock.MockPollingEdgeTest.in;
import static io.flottila.components.mock.MockPollingTest.soon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03b2 (D-080): what {@code flottila test}'s feed cases read of the mock — {@code messages} is a consumer's feed as
 * {@code files} is (both on one endpoint refused when the behaviour is built), and {@link MockComponent#undelivered} names the
 * files still pending on an endpoint: those remaining minus those refused finally (a final Nack, a body above
 * {@code maxBodyBytes}), which are settled.
 */
@Timeout(30)
class MockFeedTest {

    final FakeRuntime rt = FakeRuntime.create();

    @TempDir
    Path base;

    /** Fires the next round and waits until it is over (the round after it is scheduled). */
    void round() {
        assertTrue(soon(() -> rt.timer().pending() > 0), "a round is scheduled");
        rt.clock().advance(rt.timer().nextDelay());
        assertTrue(soon(() -> rt.timer().pending() > 0), "the round ended");
    }

    @Test
    void messagesIsReadAsFiles() throws IOException {
        assertEquals(Optional.of(base.resolve("fixtures/*.xml").normalize()), Behaviour.from(Map.of("messages", "fixtures/*.xml"), base).files(),
                "messages is the feed's glob, from the package root");

        Path fixtures = Files.createDirectories(base.resolve("fixtures"));
        Files.writeString(fixtures.resolve("b.xml"), "<b/>");
        Files.writeString(fixtures.resolve("a.xml"), "<a/>");
        Files.writeString(fixtures.resolve("c.txt"), "not matched");
        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer(), rt.ids());
        sftp.bindAll(Map.of("sftp-in", Map.of("messages", "fixtures/*.xml")), base);
        List<String> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = sftp.consumer(in(Map.of()), e -> {
            seen.add(e.body().whole().toString());
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        consumer.start().await();
        try {
            assertEquals(List.of("a.xml", "b.xml"), List.copyOf(sftp.remaining("sftp-in")), "fed at the start in name order, as files is");
            round();
            assertTrue(soon(() -> sftp.remaining("sftp-in").isEmpty()), "delivered and removed: " + sftp.remaining("sftp-in"));
            assertEquals(List.of("<a/>", "<b/>"), seen);
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    void filesAndMessagesBothIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Behaviour.from(Map.of("files", "fixtures/*.xml", "messages", "fixtures/*.xml"), base));
        assertTrue(refused.getMessage().contains("files") && refused.getMessage().contains("messages"), "names both: " + refused.getMessage());

        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());
        assertThrows(IllegalArgumentException.class,
                () -> sftp.bindAll(Map.of("sftp-in", Map.of("files", "a/*.xml", "messages", "b/*.xml")), base), "bindAll refuses it too");
    }

    /**
     * Four files, one round: {@code a} is Nacked finally and {@code b} is above the endpoint's {@code maxBodyBytes} (both refused
     * finally, kept), {@code c} is accepted (removed), {@code d} is Nacked retryably (kept, pending; the round ends). Before the
     * round all four are undelivered; after it only {@code d}; once {@code d} is accepted in the next round, none — while the two
     * refused files still remain.
     */
    @Test
    void undeliveredExcludesFinallyRefused() {
        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer(), rt.ids());
        AtomicBoolean partnerBack = new AtomicBoolean();
        Consumer consumer = sftp.consumer(in(Map.of("maxBodyBytes", 6)), e -> Future.succeededFuture(switch (e.body().whole().toString()) {
            case "final" -> new Ack.Nack("malformed", false);
            case "retry" -> partnerBack.get() ? new Ack.Accepted(e.id()) : new Ack.Nack("partner down", true);
            default -> new Ack.Accepted(e.id());
        }));
        sftp.drop("sftp-in", "a.txt", Buffer.buffer("final"));
        sftp.drop("sftp-in", "b.txt", Buffer.buffer("toolarge"));
        sftp.drop("sftp-in", "c.txt", Buffer.buffer("ok"));
        sftp.drop("sftp-in", "d.txt", Buffer.buffer("retry"));
        assertEquals(List.of(), List.copyOf(sftp.undelivered("elsewhere")), "an endpoint without files has none undelivered");
        consumer.start().await();
        try {
            assertEquals(List.of("a.txt", "b.txt", "c.txt", "d.txt"), List.copyOf(sftp.undelivered("sftp-in")), "before the round: all pending");

            round();
            assertEquals(List.of("a.txt", "b.txt", "d.txt"), List.copyOf(sftp.remaining("sftp-in")), "the refused files and the retryable one stay");
            assertEquals(List.of("d.txt"), List.copyOf(sftp.undelivered("sftp-in")), "refused finally is settled, not pending");

            partnerBack.set(true);
            round();
            assertEquals(List.of("a.txt", "b.txt"), List.copyOf(sftp.remaining("sftp-in")));
            assertEquals(List.of(), List.copyOf(sftp.undelivered("sftp-in")), "nothing pending once d is accepted");
        } finally {
            consumer.stop().await();
        }
    }
}
