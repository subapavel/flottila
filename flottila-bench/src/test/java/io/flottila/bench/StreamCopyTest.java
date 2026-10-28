package io.flottila.bench;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyKind;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepResources;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bench's stream step (P1-10c, GATE 9 "a 50 MB body through a stream step"): {@link StreamCopy}, type {@code stream-copy},
 * declares {@link BodyKind#STREAM} — what lets a flow whose max body is above the store's tree limit deploy it — and copies the
 * body through {@link Body#open()} into a new body under {@link Envelope#nextKey()}, written as a stream bounded by the flow's
 * max body; it never materialises the body ({@link Body#whole()}, {@link BlobStore#read}) and never writes it from memory. The
 * store is observed at its boundary ({@link Counting}); the bodies here are above the tree limit (16 bytes), so a
 * {@code whole()} fails.
 */
class StreamCopyTest {

    static final long TREE_LIMIT = 16;
    static final int SIZE = 1000;

    final FakeRuntime rt = FakeRuntime.create().withTreeLimit(TREE_LIMIT);
    final Counting store = new Counting(rt.blobs());

    @TempDir Path dir;

    StepResources resources(long maxBodyBytes) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), store, rt.hasher(), maxBodyBytes, dir);
    }

    static StepConfig copy() {
        return new StepConfig(1, "stream-copy", "copy", false, Map.of());
    }

    /** {@code SIZE} bytes that differ from position to position, written through the counting store as message m-1's ingress body. */
    Envelope ingress() {
        Buffer bytes = Buffer.buffer();
        for (int i = 0; i < SIZE; i++) bytes.appendByte((byte) (i * 31 + 7));
        Body body = store.write(BlobKey.body("m-1", 0), bytes, SIZE).await();
        store.reset();
        return Envelope.ingress("m-1", "stream-heap", Headers.empty(), body);
    }

    @Test
    void declaresTheStreamCopyTypeAsAStreamStepWithAJsonObjectSchema() {
        StreamCopy factory = new StreamCopy();
        assertEquals("stream-copy", factory.type(), "the flow's step type (the flow schema's type pattern has no dot)");
        assertEquals(BodyKind.STREAM, factory.bodyKind(), "a stream step: the build would refuse a whole step on a 50 MB flow");
        JsonObject schema = new JsonObject(factory.configSchema());
        assertEquals("object", schema.getString("type"), "the step's config schema describes an object: " + schema.encode());
    }

    @Test
    void copiesTheBodyThroughOpenIntoANewBodyUnderTheNextKey() {
        Stage stage = new StreamCopy().create(copy(), resources(4096));
        Envelope in = ingress();
        List<Envelope> rest = new ArrayList<>();
        Envelope answered = Envelope.ingress("m-answer", "stream-heap", Headers.empty(), in.body());

        Envelope result = stage.apply(in, e -> {
            rest.add(e);
            return answered;
        });

        assertSame(answered, result, "the stage answers what the rest of the pipeline answered");
        assertEquals(1, rest.size(), "the rest of the pipeline runs once");
        Envelope out = rest.get(0);
        assertEquals(in.step() + 1, out.step(), "the copy is the next step's envelope");
        assertEquals(in.nextKey(), out.body().key(), "a new body under the next step's key (message id + step), not the ingress key");
        assertNotEquals(in.body().key(), out.body().key());
        assertEquals(SIZE, out.body().size(), "every byte copied");
        assertEquals(in.body().hash(), out.body().hash(), "the same content");
        assertArrayEquals(drain(rt.blobs().open(in.body().key())).getBytes(), drain(rt.blobs().open(out.body().key())).getBytes(),
                "the stored copy holds the ingress bytes");
        assertTrue(rt.blobs().contains(in.body().key()), "the ingress body is left as it was");

        // at the store boundary: read through open(), written as a stream, never materialised
        assertTrue(store.opened.contains(in.body().key()), "the step read the body through open(): " + store.opened);
        assertEquals(List.of(), store.read, "the step never read the whole body (Body.whole / BlobStore.read)");
        assertEquals(List.of(), store.wroteWhole, "the step never wrote the copy from a buffer in memory");
        assertEquals(List.of(in.nextKey()), store.wroteStream, "one stream write, under the next key");
        assertEquals(List.of(4096L), store.streamMaxSizes, "the write is bounded by the flow's max body, never unlimited");
    }

    @Test
    void aBodyAboveTheFlowsMaxBodyIsRefusedWhileItStreams() {
        Stage stage = new StreamCopy().create(copy(), resources(SIZE - 1));
        Envelope in = ingress();
        List<Envelope> rest = new ArrayList<>();
        assertThrows(BodyTooLargeException.class, () -> stage.apply(in, e -> {
            rest.add(e);
            return e;
        }), "a copy above the flow's max body fails the step");
        assertEquals(List.of(), rest, "nothing reaches the rest of the pipeline");
        assertEquals(List.of((long) SIZE - 1), store.streamMaxSizes, "the flow's max body bounded the write");
    }

    static Buffer drain(ReadStream<Buffer> stream) {
        Promise<Buffer> done = Promise.promise();
        Buffer all = Buffer.buffer();
        stream.exceptionHandler(done::tryFail);
        stream.endHandler(v -> done.tryComplete(all));
        stream.handler(all::appendBuffer);
        stream.resume();
        return done.future().await();
    }

    /** The in-memory store, every call recorded at the boundary. */
    static final class Counting implements BlobStore {
        final InMemoryBlobStore inner;
        final List<BlobKey> opened = new ArrayList<>();
        final List<BlobKey> read = new ArrayList<>();
        final List<BlobKey> wroteWhole = new ArrayList<>();
        final List<BlobKey> wroteStream = new ArrayList<>();
        final List<Long> streamMaxSizes = new ArrayList<>();

        Counting(InMemoryBlobStore inner) {
            this.inner = inner;
        }

        void reset() {
            opened.clear();
            read.clear();
            wroteWhole.clear();
            wroteStream.clear();
            streamMaxSizes.clear();
        }

        @Override public long treeLimit() { return inner.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            wroteWhole.add(key);
            return inner.write(key, whole, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            wroteStream.add(key);
            streamMaxSizes.add(maxSize);
            return inner.write(key, in, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
        }

        @Override public ReadStream<Buffer> open(BlobKey key) {
            opened.add(key);
            return inner.open(key);
        }

        @Override public Future<Buffer> read(BlobKey key) {
            read.add(key);
            return inner.read(key);
        }

        @Override public Future<Void> persist(BlobKey key) {
            return inner.persist(key);
        }
    }
}
