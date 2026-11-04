package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgIntake;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One node of the CORE-4c2 ITs over a case database: its journal ({@link TestJournals}, through {@link #files} — real files
 * that a case can hold or fail, {@link HeldFiles}), the body store over it ({@code TieredBlobStore}, the journal its durable
 * tier), the message store, the work queue of this node, the intake and the DLQ — all on {@code pool}; the journal's own rows on
 * {@code rows}. Two worlds of two node ids over one database are two nodes.
 *
 * <p>The shapes these ITs assume (CORE-4c2, CORE-3 items 5–8): {@code new TieredBlobStore.Config(long treeLimit, long
 * memoryTierBytes, long memoryBudgetBytes, long durableMaxBytes)} (no spool directory, no spool key) and
 * {@code new TieredBlobStore(Config, Hasher, Journal journal, int tenantKeyId, Pool pool)}: its location index (key → ref) is the
 * store's own, so a second store over the same open journal ({@link #restartedStore()}) starts with an empty index — a store
 * restart with the journal open; {@code new PgWorkQueue(Pool pool, String node)}; core {@code Intake.save(Envelope, Snapshot,
 * String flow, String partitionKey, String dedupeKey, boolean keepInput, Future<?> abandoned)} (CORE-4d: the delivery's answer future;
 * complete = abandoned).
 */
final class JournalWorld implements AutoCloseable {

    static final String FLOW = "orders";
    /** Every limit finite (CLAUDE.md): a flow's max body, the memory tier and budget, the durable bound. */
    static final long MAX = 1L << 20;
    static final long MEMORY_TIER = 1L << 20;
    static final long MEMORY_BUDGET = 8L << 20;
    static final long DURABLE_MAX = 4L << 20;
    /** Every wait of these ITs is bounded by this: a failure detector, never a timing. */
    static final long BOUND_SECONDS = 20;

    final String node;
    final Pool pool;
    final HeldFiles files;
    final TestJournals.Opened opened;
    final Journal journal;
    final TieredBlobStore.Config config;
    final TieredBlobStore blobs;
    final PgMessageStore messages;
    final PgWorkQueue queue;
    final PgIntake intake;
    final PgDlq dlq;

    private JournalWorld(String node, Pool pool, HeldFiles files, TestJournals.Opened opened, TieredBlobStore.Config config, Vertx vertx) {
        this.node = node;
        this.pool = pool;
        this.files = files;
        this.opened = opened;
        this.journal = opened.journal();
        this.config = config;
        this.blobs = new TieredBlobStore(config, Hasher.sha256(), journal, opened.tenant(), pool);
        this.messages = new PgMessageStore(pool, new VertxTimer(vertx), BatchingWriter.Options.defaults());
        this.queue = new PgWorkQueue(pool, node);
        this.intake = new PgIntake(pool, blobs, messages, queue);
        this.dlq = new PgDlq(pool);
    }

    /** Node {@code node}: its journal in {@code dir} (the design's defaults), its stores on {@code pool}, its journal rows on {@code rows}. */
    static JournalWorld open(Vertx vertx, Pool pool, Pool rows, Path dir, String node) throws Exception {
        return open(vertx, pool, rows, dir, node, s -> { }, new TieredBlobStore.Config(MAX, MEMORY_TIER, MEMORY_BUDGET, DURABLE_MAX));
    }

    /** As {@link #open(Vertx, Pool, Pool, Path, String)} with the journal's settings changed by {@code tweak} and this store config. */
    static JournalWorld open(Vertx vertx, Pool pool, Pool rows, Path dir, String node, Consumer<JournalFixture.Settings> tweak,
                             TieredBlobStore.Config config) throws Exception {
        return open(vertx, pool, rows, dir, node, tweak, config, JournalFixture.START);
    }

    /** As above, the journal's fake clock at {@code at} (CORE-4d: a node restarted later than the first start). */
    static JournalWorld open(Vertx vertx, Pool pool, Pool rows, Path dir, String node, Consumer<JournalFixture.Settings> tweak,
                             TieredBlobStore.Config config, Instant at) throws Exception {
        HeldFiles files = new HeldFiles();
        TestJournals.Opened opened = TestJournals.open(rows, dir, node, files, tweak, at);
        return new JournalWorld(node, pool, files, opened, config, vertx);
    }

    /** An answer never decided: the save is never abandoned (core {@code Intake.save}'s {@code abandoned}, CORE-4d). */
    static Future<Void> undecided() {
        return Promise.<Void>promise().future();
    }

    /** Another store over this node's open journal and pool: its location index is empty (a store restart). */
    TieredBlobStore restartedStore() {
        return new TieredBlobStore(config, Hasher.sha256(), journal, opened.tenant(), pool);
    }

    // --- arrivals and saves

    /** The deterministic body of a case: {@code size} bytes, from {@code seed}. */
    static Buffer bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * 31 + seed + (i >>> 8));
        return Buffer.buffer(b);
    }

    /** An at-least-once arrival: its ingress body written to this node's store under {@code (id, 0, body)}. */
    Envelope arrive(String id, Buffer bytes) {
        Future<Body> written = settled(blobs.write(BlobKey.body(id, 0), bytes, MAX));
        assertTrue(written.succeeded(), "fixture: written " + written.cause());
        return Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), written.result());
    }

    static Snapshot ingress(Envelope e) {
        return new Recorder(new InMemoryMessageStore(), Clock.fixed(Instant.parse("2026-11-04T10:00:00.123456Z"), ZoneOffset.UTC), Meter.system(), Map.of())
                .startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT);
    }

    /** The save of {@code e} without a dedupe key, partition key {@code K-1}, settled. */
    Future<String> save(Envelope e, boolean keepInput, Future<?> abandoned) {
        return settled(start(e, keepInput, abandoned));
    }

    /** The save of {@code e}, not waited for. */
    Future<String> start(Envelope e, boolean keepInput, Future<?> abandoned) {
        return intake.save(e, ingress(e), FLOW, "K-1", null, keepInput, abandoned);
    }

    // --- reading Postgres (on the given pool: another session's view once committed)

    /** A ref as a row names it: {@code node} null for a pre-V12 work row. */
    record Ref(String node, Long segment, Long position, Long length) {
        JournalRef journalRef() { return new JournalRef(segment, position, length); }
    }

    /** The ref of message {@code id}'s work row, or null when it has none. */
    static Ref workRef(Pool on, String id) {
        for (Row r : on.preparedQuery("select body_node, body_segment, body_position, body_length from work where id = $1").execute(Tuple.of(id)).await()) {
            return new Ref(r.getString(0), r.getLong(1), r.getLong(2), r.getLong(3));
        }
        return null;
    }

    /** The {@code body_refs} row of {@code key}, or null. */
    static Ref bodyRef(Pool on, BlobKey key) {
        for (Row r : on.preparedQuery("select node, segment, position, length from body_refs where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(key.messageId(), key.step(), key.part())).await()) {
            return new Ref(r.getString(0), r.getLong(1), r.getLong(2), r.getLong(3));
        }
        return null;
    }

    static long count(Pool on, String sql, Object... params) {
        return on.preparedQuery(sql).execute(Tuple.wrap(params)).await().iterator().next().getLong(0);
    }

    /** The plaintext of the journal record at {@code ref}, read by its ref (CORE-4a's read: CRC, then AEAD). */
    Buffer record(Ref ref) throws Exception {
        return Buffer.buffer(io.flottila.runtime.node.OnVirtualThread.call(() -> journal.read(ref.journalRef())));
    }

    // --- futures and streams

    /** Waits (bounded) until {@code f} completes, either way. */
    static <T> Future<T> settled(Future<T> f) {
        try {
            f.otherwiseEmpty().await(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after " + BOUND_SECONDS + " s: " + f);
        }
        return f;
    }

    /** Every byte of the stream {@code store.open(key)} gives, or its failure — also when {@code open} throws at the call. */
    static Future<Buffer> opened(BlobStore store, BlobKey key) {
        ReadStream<Buffer> stream;
        try {
            stream = store.open(key);
        } catch (RuntimeException thrown) {
            return Future.failedFuture(thrown);
        }
        Promise<Buffer> p = Promise.promise();
        Buffer all = Buffer.buffer();
        stream.exceptionHandler(p::tryFail);
        stream.endHandler(v -> p.tryComplete(all));
        stream.handler(all::appendBuffer);
        return settled(p.future());
    }

    static List<String> causes(Throwable failure) {
        List<String> chain = new ArrayList<>();
        for (Throwable t = failure; t != null && chain.size() < 20; t = t.getCause()) chain.add(String.valueOf(t));
        return chain;
    }

    @Override public void close() throws Exception {
        files.releaseAll();
        messages.close();
        opened.close();
    }
}
