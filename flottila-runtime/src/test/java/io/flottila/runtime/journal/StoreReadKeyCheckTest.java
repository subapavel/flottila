package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.node.OnVirtualThread;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c3 packet item 7 (carried from CORE-4a): a store read checks the record header's message id, step and part against the
 * key it reads, so a ref that names another body's record — a misaddressed index entry or row — fails as corrupt
 * ({@link JournalCorruptException}, an ERROR), never with that body's bytes: read and open alike, for a segment record and for a
 * large one (a persisted spill, read chunk by chunk). The misaddressed ref is put into the store's location index through its
 * public seam {@link TieredBlobStore#located}; the witness is the same ref under its own key, which reads the bytes.
 */
class StoreReadKeyCheckTest {

    static final String NODE = "node-1";

    @TempDir Path tmp;
    TestJournals.Opened opened;
    TieredBlobStore store;
    final Severe severe = new Severe();

    /** Every SEVERE (System.Logger ERROR) record of the runtime while a case runs, whichever class logs it. */
    static final class Severe extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        Severe() { setLevel(Level.SEVERE); }

        @Override public void publish(LogRecord r) {
            if (r.getLevel().intValue() >= Level.SEVERE.intValue()) records.add(r);
        }

        @Override public void flush() { }

        @Override public void close() { }
    }

    @BeforeEach
    void open() throws Exception {
        opened = TestJournals.local(tmp.resolve("journal"));   // RECORD_MAX 4 KiB: a body above it is a large record
        store = new TieredBlobStore(new TieredBlobStore.Config(1L << 20, 4096, 1L << 20, 1L << 20), Hasher.sha256(), opened.journal(), opened.tenant());
        Logger.getLogger("").addHandler(severe);
    }

    @AfterEach
    void close() throws Exception {
        Logger.getLogger("").removeHandler(severe);
        opened.close();
    }

    /** A segment record of {@code (m-b, 0, body)}, forced: its ref. */
    JournalRef small(byte[] bytes) throws Exception {
        Journal.Appended a = opened.journal().append(new JournalRecord("m-b", 0, BlobKey.BODY, opened.tenant(), bytes), Promise.<Void>promise().future());
        JournalFixture.await(a.forced());
        return a.ref();
    }

    /** A large record of {@code (m-b, 0, body)}: spilled and persisted into a segment of its own, its ref. */
    JournalRef large(int size) throws Exception {
        Journal.Spill spill = opened.journal().spill(BlobKey.body("m-b", 0), opened.tenant(), new GeneratedInput(7, size), 1L << 20, Hasher.sha256());
        return OnVirtualThread.call(spill::persist);
    }

    /** What a cold stream of {@code key} delivered before it ended or failed: the bytes, and the failure (null: it ended). */
    record Streamed(byte[] bytes, Throwable failure) { }

    Streamed streamed(BlobKey key) throws Exception {
        Promise<Throwable> end = Promise.promise();
        List<byte[]> chunks = new ArrayList<>();
        ReadStream<Buffer> in;
        try {
            in = store.open(key);
        } catch (RuntimeException thrown) {
            return new Streamed(new byte[0], thrown);
        }
        in.exceptionHandler(end::tryComplete);
        in.endHandler(v -> end.tryComplete(null));
        in.handler(b -> chunks.add(b.getBytes()));
        Throwable failure = end.future().await(20, TimeUnit.SECONDS);
        Buffer all = Buffer.buffer();
        for (byte[] c : chunks) all.appendBytes(c);
        return new Streamed(all.getBytes(), failure);
    }

    static boolean corrupt(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) if (x instanceof JournalCorruptException) return true;
        return false;
    }

    static BlobKey misaddressed(String which) {
        return switch (which) {
            case "message id" -> BlobKey.body("m-a", 0);
            case "step" -> BlobKey.body("m-b", 1);
            case "part" -> BlobKey.attachment("m-b", 0, "invoice.pdf");
            default -> throw new AssertionError(which);
        };
    }

    @ParameterizedTest(name = "another {0}")
    @ValueSource(strings = {"message id", "step", "part"})
    void aMisaddressedRefFailsAsCorrupt(String which) throws Exception {
        byte[] bytes = JournalFixture.body("m-b", 3000);
        JournalRef ref = small(bytes);
        BlobKey own = BlobKey.body("m-b", 0);
        BlobKey wrong = misaddressed(which);
        store.located(wrong, NODE, ref);

        Future<Buffer> read = JournalWorld.settled(store.read(wrong));
        Streamed open = streamed(wrong);

        assertTrue(read.failed(), "the read of " + wrong + " at the record of " + own + " fails, it does not answer: " + (read.succeeded() ? read.result().length() + " bytes" : ""));
        assertTrue(corrupt(read.cause()), "as corrupt: " + JournalWorld.causes(read.cause()));
        assertTrue(corrupt(open.failure()), "the stream fails as corrupt too: " + open.failure());
        assertEquals(0, open.bytes().length, "and delivers no byte of " + own + "'s body first");
        assertFalse(severe.records.isEmpty(), "an ERROR is logged");

        store.located(own, NODE, ref);
        assertArrayEquals(bytes, JournalWorld.settled(store.read(own)).result().getBytes(), "witness: the same ref read under its own key answers its bytes");
    }

    @ParameterizedTest(name = "another {0}")
    @ValueSource(strings = {"message id", "step", "part"})
    void aMisaddressedLargeRefFailsAsCorrupt(String which) throws Exception {
        int size = 3 * 4096 + 17;
        JournalRef ref = large(size);
        assertTrue(ref.length() > opened.config().recordMax(), "fixture: a large record: " + ref);
        BlobKey own = BlobKey.body("m-b", 0);
        BlobKey wrong = misaddressed(which);
        store.located(wrong, NODE, ref);

        Future<Buffer> read = JournalWorld.settled(store.read(wrong));
        Streamed open = streamed(wrong);

        assertTrue(read.failed(), "the read of " + wrong + " at the large record of " + own + " fails: " + (read.succeeded() ? read.result().length() + " bytes" : ""));
        assertTrue(corrupt(read.cause()), "as corrupt: " + JournalWorld.causes(read.cause()));
        assertTrue(corrupt(open.failure()), "the stream fails as corrupt too: " + open.failure());
        assertEquals(0, open.bytes().length, "and delivers no chunk of " + own + "'s body first");
        assertFalse(severe.records.isEmpty(), "an ERROR is logged");

        store.located(own, NODE, ref);
        Streamed witness = streamed(own);
        assertEquals(null, witness.failure(), "witness: under its own key the large record streams");
        assertArrayEquals(GeneratedInput.bytes(7, size), witness.bytes());
    }
}
