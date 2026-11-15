package io.flottila.components.mock;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mock's polling edges: its body limit, unreadable fixtures, a file dropped again, its ids, round 0 at start, and a stop before the first round and mid-round. */
@Timeout(30)
class MockPollingEdgeTest {

    final FakeRuntime rt = FakeRuntime.create();
    final List<String> seen = new CopyOnWriteArrayList<>();

    @TempDir
    Path base;

    static EndpointConfig in(Map<String, Object> options) { return new EndpointConfig("sftp-in", URI.create("sftp://partner.example/inbox"), options); }

    /** Fires the next round and waits until it is over (the round after it is scheduled). */
    void round() {
        assertTrue(MockPollingTest.soon(() -> rt.timer().pending() > 0), "a round is scheduled");
        rt.clock().advance(rt.timer().nextDelay());
        assertTrue(MockPollingTest.soon(() -> rt.timer().pending() > 0), "the round ended");
    }

    @Test
    void aFileAboveTheEndpointsMaxBodyBytesIsNeverDelivered() {
        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());
        Consumer consumer = sftp.consumer(in(Map.of("maxBodyBytes", 3)), e -> {
            seen.add(e.body().whole().toString());
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        sftp.drop("sftp-in", "big.txt", Buffer.buffer("12345"));
        sftp.drop("sftp-in", "ok.txt", Buffer.buffer("ok"));
        consumer.start().await();
        try {
            round();
            round();
            assertEquals(List.of("ok"), seen, "the file above maxBodyBytes was refused, the next one delivered");
            assertEquals(List.of("big.txt"), List.copyOf(sftp.remaining("sftp-in")), "the refused file stays");
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    void aFinallyRefusedFileDroppedAgainIsANewFile() {
        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer(), rt.ids());
        List<String> ids = new CopyOnWriteArrayList<>();
        Consumer consumer = sftp.consumer(in(Map.of()), e -> {
            seen.add(e.body().whole().toString());
            ids.add(e.id());
            return Future.succeededFuture("first".equals(e.body().whole().toString()) ? new Ack.Nack("malformed", false) : new Ack.Accepted(e.id()));
        });
        sftp.drop("sftp-in", "a.xml", Buffer.buffer("first"));
        consumer.start().await();
        try {
            round();
            round();
            assertEquals(List.of("first"), seen, "a final Nack is not delivered again");
            sftp.drop("sftp-in", "a.xml", Buffer.buffer("second"));   // replaces the refused one
            round();
            assertEquals(List.of("first", "second"), seen, "the file dropped again under that name is new");
            assertEquals(List.of(), List.copyOf(sftp.remaining("sftp-in")));
            assertEquals(List.of("m-1", "m-2"), ids, "the ids come from the injected generator, one per delivery");
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    void unreadableFixturesFailTheStartAndSubdirectoriesAreNotFed() throws IOException {
        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());
        sftp.bindAll(Map.of("sftp-in", Map.of("files", "missing/*.xml")), base);
        Future<Void> start = sftp.consumer(in(Map.of()), e -> Future.succeededFuture(new Ack.Accepted(e.id()))).start();
        assertTrue(start.failed(), "the fixtures' directory does not exist: " + start);
        assertInstanceOf(IllegalStateException.class, start.cause());
        assertEquals(Health.Status.DOWN, sftp.health().await().status(), "a consumer that did not start polls nothing");

        Path inbox = Files.createDirectories(base.resolve("fixtures"));
        Files.writeString(inbox.resolve("a.xml"), "<a/>");
        Files.createDirectories(inbox.resolve("sub.xml"));   // a directory whose name matches
        sftp.bindAll(Map.of("sftp-in", Map.of("files", "fixtures/*.xml")), base);
        Consumer consumer = sftp.consumer(in(Map.of()), e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        consumer.start().await();
        try {
            assertEquals(List.of("a.xml"), List.copyOf(sftp.remaining("sftp-in")), "regular files only");
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    void anEmptyFilesEntryAndAProducerSideBehaviourFeedNothing() {
        assertEquals(Optional.empty(), Behaviour.from(Map.of("files", ""), base).files());
        assertEquals(Optional.empty(), new Behaviour(Optional.empty(), Duration.ZERO, Optional.empty(), 1, 1).files());
    }

    @Test
    void aConsumerStoppedBeforeItStartedStopsAtOnce() throws Exception {
        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());
        sftp.consumer(in(Map.of()), e -> Future.succeededFuture(new Ack.Accepted(e.id()))).stop().await(10, TimeUnit.SECONDS);
        assertEquals(0, rt.timer().pending(), "no round was ever scheduled");
    }

    /** A store whose in-memory writes wait for the test's go. */
    static final class GatedBlobStore implements BlobStore {
        final BlobStore store;
        final Promise<Void> go = Promise.promise();
        final CountDownLatch writing = new CountDownLatch(1);

        GatedBlobStore(BlobStore store) { this.store = store; }

        @Override public long treeLimit() { return store.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            writing.countDown();
            return go.future().compose(v -> store.write(key, whole, maxSize));
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) { return store.write(key, in, maxSize); }

        @Override public ReadStream<Buffer> open(BlobKey key) { return store.open(key); }

        @Override public Future<Buffer> read(BlobKey key) { return store.read(key); }

        @Override public Future<Void> persist(BlobKey key) { return store.persist(key); }
    }

    @Test
    void stopWaitsForTheRoundInFlightEvenBeforeItsDelivery() throws Exception {
        GatedBlobStore gated = new GatedBlobStore(rt.blobs());
        MockComponent sftp = new MockComponent("sftp", gated, rt.timer());
        Consumer consumer = sftp.consumer(in(Map.of()), e -> {
            seen.add(e.body().whole().toString());
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        sftp.drop("sftp-in", "a.txt", Buffer.buffer("a"));
        sftp.drop("sftp-in", "b.txt", Buffer.buffer("b"));
        consumer.start().await();
        rt.clock().advance(rt.timer().nextDelay());
        assertTrue(gated.writing.await(10, TimeUnit.SECONDS), "the round took a.txt and is writing its body");
        Future<Void> stopped = consumer.stop();
        assertFalse(stopped.isComplete(), "the round in flight holds the stop: " + stopped);
        gated.go.complete();
        stopped.await(10, TimeUnit.SECONDS);
        assertEquals(List.of("a"), seen, "the file taken before the stop was delivered, none after");
        assertEquals(List.of("b.txt"), List.copyOf(sftp.remaining("sftp-in")), "and removed before the stop completed");
    }

    /** A store that records the thread of every in-memory write: the mock writes a body on the round's own thread. */
    static final class ThreadRecordingStore implements BlobStore {
        final BlobStore store;
        final List<Thread> writers = new CopyOnWriteArrayList<>();

        ThreadRecordingStore(BlobStore store) { this.store = store; }

        @Override public long treeLimit() { return store.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            writers.add(Thread.currentThread());
            return store.write(key, whole, maxSize);
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) { return store.write(key, in, maxSize); }

        @Override public ReadStream<Buffer> open(BlobKey key) { return store.open(key); }

        @Override public Future<Buffer> read(BlobKey key) { return store.read(key); }

        @Override public Future<Void> persist(BlobKey key) { return store.persist(key); }
    }

    @Test
    void roundZeroIsScheduledAtOnceOnStart() throws Exception {
        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());
        Consumer consumer = sftp.consumer(in(Map.of()), e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        consumer.start().await();
        assertEquals(1, rt.timer().pending(), "start scheduled round 0");
        assertEquals(Duration.ZERO, rt.timer().nextDelay(), "round 0 at once on start, not a ROUND later");
        consumer.stop().await(10, TimeUnit.SECONDS);
    }

    @Test
    void aStopMidRoundTakesNoFurtherFileAndSchedulesNoRoundAfterIt() throws Exception {
        ThreadRecordingStore store = new ThreadRecordingStore(rt.blobs());
        MockComponent sftp = new MockComponent("sftp", store, rt.timer());
        List<String> ids = new CopyOnWriteArrayList<>();
        Promise<Ack> answer = Promise.promise();
        Consumer consumer = sftp.consumer(in(Map.of()), e -> {
            seen.add(e.body().whole().toString());
            ids.add(e.id());
            return seen.size() == 1 ? answer.future() : Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        sftp.drop("sftp-in", "a.txt", Buffer.buffer("a"));
        sftp.drop("sftp-in", "b.txt", Buffer.buffer("b"));
        consumer.start().await();
        rt.clock().advance(rt.timer().nextDelay());
        assertTrue(MockPollingTest.soon(() -> ids.size() == 1), "a.txt delivered, its answer pending");
        Future<Void> stopped = consumer.stop();
        assertFalse(stopped.isComplete(), "the round in flight holds the stop");
        answer.complete(new Ack.Accepted(ids.get(0)));
        stopped.await(10, TimeUnit.SECONDS);
        Thread round = store.writers.get(0);
        assertTrue(MockPollingTest.soon(() -> round.getState() == Thread.State.TERMINATED), "the round's thread ran to its end");
        assertEquals(0, rt.timer().pending(), "no round scheduled after the stop, not even by the round that ended after it");
        assertEquals(List.of("a"), seen, "no file taken once the stop began");
        assertEquals(List.of("b.txt"), List.copyOf(sftp.remaining("sftp-in")));
    }
}
