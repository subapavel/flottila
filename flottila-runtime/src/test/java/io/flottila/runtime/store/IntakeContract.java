package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.flow.Intake;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-08a (D-082): what every {@link Intake} guarantees for an at-least-once ingress — a save that succeeds has the body durable,
 * the ingress snapshot recorded and the item queued for step 1 under the partition key it was given; a save that fails queues
 * nothing: a body above its store's durable bound, a key whose durable bytes are other bytes (FIX-35), a save found abandoned
 * ({@code abandoned} is asked once). A save that was abandoned leaves nothing that stops the sender's retry. Run by
 * {@code SequentialIntakeContractTest} (core's SequentialIntake over the in-memory store and queue) and
 * {@code PgIntakeContractIT} (runtime's PgIntake over Postgres).
 *
 * <p>The shape assumed: core {@code io.flottila.core.flow.Intake} with {@code Future<String> save(Envelope arrived, Snapshot ingress,
 * String flow, String partitionKey, String dedupeKey, BooleanSupplier abandoned)} (P2-02D, D-084: the id the answer names; every
 * case here passes no dedupe key).
 */
@Timeout(60)
public abstract class IntakeContract {

    static final String FLOW = "orders";
    /** The durable bound of every case's body store: a body of {@code BOUND + 1} bytes cannot be made durable. */
    protected static final long BOUND = 1000;
    /** The flow's max body size: a write above the durable bound is accepted. */
    protected static final long FLOW_MAX = 4 * BOUND;
    static final Instant AT = Instant.parse("2026-10-29T10:00:00.123456Z");

    /** An item as the case's queue holds it. */
    protected record Queued(String messageId, String flow, int step, String partitionKey) {}

    /** One case's intake and what a case reads back through it. */
    protected interface World {
        /** The intake under test. */
        Intake intake();

        /** The store {@link #arrive} writes ingress bodies to: the intake's body store. */
        BlobStore bodies();

        /** The bytes made durable under {@code key}, as the durable tier holds them; empty when none are. */
        Optional<Buffer> durable(BlobKey key);

        /** The snapshots recorded for {@code messageId}, in step order. */
        List<Snapshot> recorded(String messageId);

        /** Every queued item, in queue order. */
        List<Queued> queued();
    }

    /** The case's world: an empty store, an empty queue, an empty body store with the durable bound {@link #BOUND}. */
    protected abstract World emptyWorld();

    protected World world;

    @BeforeEach
    void openTheWorld() {
        world = emptyWorld();
    }

    // --- fixture

    /** {@code size} bytes of a position-dependent pattern: other seeds are other bytes. */
    protected static Buffer bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * 31 + seed);
        return Buffer.buffer(b);
    }

    /** A stage-0 envelope {@code id} of {@link #FLOW}, {@code bytes} written under {@code body(id, 0)} in the world's body store. */
    protected Envelope arrive(String id, Buffer bytes) {
        Future<Body> written = settled(world.bodies().write(BlobKey.body(id, 0), bytes, FLOW_MAX));
        assertTrue(written.succeeded(), "fixture: the body is written: " + written.cause());
        return Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), written.result());
    }

    /** The ingress snapshot an at-least-once delivery hands its intake: the payload kept. */
    protected static Snapshot ingress(Envelope e) {
        return new Recorder(new InMemoryMessageStore(), Clock.fixed(AT, ZoneOffset.UTC), Meter.system(), Map.of())
                .startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT);
    }

    /** {@code abandoned}, answering {@code answer}, counting its asks. */
    protected record Asked(AtomicInteger times, BooleanSupplier supplier) {
        static Asked answering(boolean answer) {
            AtomicInteger times = new AtomicInteger();
            return new Asked(times, () -> {
                times.incrementAndGet();
                return answer;
            });
        }
    }

    /** A save without a dedupe key (P2-02D: null — the keyed saves are PgIntake's, {@code PgIntakeIT}). */
    protected Future<String> save(Envelope e, String partitionKey, BooleanSupplier abandoned) {
        return settled(world.intake().save(e, ingress(e), FLOW, partitionKey, null, abandoned));
    }

    /** {@code f} once complete, either way; still pending after 20 s fails the case. */
    protected static <T> Future<T> settled(Future<T> f) {
        try {
            f.otherwiseEmpty().await(20, TimeUnit.SECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after 20 s: " + f);
        }
        return f;
    }

    /** The cause chain of {@code failure}, outermost first. */
    protected static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = failure; t != null && !chain.contains(t); t = t.getCause()) chain.add(t);
        return chain;
    }

    static List<String> stepNames(List<Snapshot> rows) {
        return rows.stream().map(Snapshot::stepName).toList();
    }

    // --- cases

    @Test
    void savedIsRecordedQueuedAndDurable() {
        Buffer keyedBytes = bytes(300, 1);
        Envelope keyed = arrive("m-keyed", keyedBytes);
        Asked asked = Asked.answering(false);
        Future<String> saved = save(keyed, "K-1", asked.supplier());
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        assertEquals("m-keyed", saved.result(), "a save without a dedupe key answers the arrival's own id");
        assertEquals(1, asked.times().get(), "abandoned is asked once");

        List<Snapshot> rows = world.recorded("m-keyed");
        assertEquals(List.of(Recorder.INGRESS), stepNames(rows), "the ingress snapshot is recorded");
        assertEquals(FLOW, rows.get(0).flow());
        assertEquals(0, rows.get(0).step());
        assertEquals(Optional.of(keyed.body().key()), rows.get(0).bodyKey(), "with its payload's key");
        assertEquals(List.of(new Queued("m-keyed", FLOW, 1, "K-1")), world.queued(), "the item is queued for step 1 under its key");
        assertEquals(Optional.of(keyedBytes), world.durable(keyed.body().key()), "the body is durable");
        assertEquals(keyedBytes, settled(world.bodies().read(keyed.body().key())).result(), "and reads back as it arrived");

        Buffer unkeyedBytes = bytes(200, 2);
        Envelope unkeyed = arrive("m-unkeyed", unkeyedBytes);
        Future<String> unkeyedSave = save(unkeyed, null, () -> false);
        assertTrue(unkeyedSave.succeeded(), "a save without a partition key");
        assertEquals("m-unkeyed", unkeyedSave.result());
        assertEquals(List.of(new Queued("m-keyed", FLOW, 1, "K-1"), new Queued("m-unkeyed", FLOW, 1, null)), world.queued(),
                "no key: an unordered item, queued after the first");
        assertEquals(List.of(Recorder.INGRESS), stepNames(world.recorded("m-unkeyed")));
        assertEquals(Optional.of(unkeyedBytes), world.durable(unkeyed.body().key()));
    }

    @Test
    void aBodyAboveTheBoundQueuesNothing() {
        Buffer above = bytes((int) BOUND + 1, 3);
        Envelope big = arrive("m-big", above);
        Future<String> saved = save(big, "K-1", () -> false);
        assertTrue(saved.failed(), "a body that cannot be made durable is no save");
        assertTrue(causes(saved.cause()).stream().anyMatch(BodyTooLargeException.class::isInstance), "it is too large: " + causes(saved.cause()));
        assertEquals(List.of(), world.queued(), "nothing queued");
        assertEquals(Optional.empty(), world.durable(big.body().key()), "nothing durable");
        assertEquals(above, settled(world.bodies().read(big.body().key())).result(), "the body stays readable where it was");
    }

    @Test
    void aKeyWithOtherDurableBytesQueuesNothing() {
        Buffer first = bytes(300, 4);
        Buffer other = bytes(300, 5);
        Envelope earlier = arrive("m-twice", first);
        assertTrue(settled(earlier.body().store().persist(earlier.body().key())).succeeded(), "fixture: the key's first bytes are durable");
        Envelope again = arrive("m-twice", other);   // the key written again, with other bytes of the same size

        Future<String> saved = save(again, "K-1", () -> false);
        assertTrue(saved.failed(), "a key's durable bytes never change (FIX-35): no save over other bytes");
        assertTrue(causes(saved.cause()).stream().anyMatch(t -> t instanceof IllegalStateException && String.valueOf(t.getMessage()).contains("other bytes")),
                "refused as other bytes under a durable key: " + causes(saved.cause()));
        assertEquals(List.of(), world.queued(), "nothing queued");
        assertEquals(Optional.of(first), world.durable(again.body().key()), "the durable bytes stay the first");
        assertEquals(other, settled(world.bodies().read(again.body().key())).result(), "the body stays as written");
    }

    @Test
    void anAbandonedSaveQueuesNothing() {
        Envelope in = arrive("m-abandoned", bytes(300, 6));
        Asked asked = Asked.answering(true);
        Future<String> saved = save(in, "K-1", asked.supplier());
        assertTrue(saved.failed(), "an abandoned save fails");
        assertEquals(1, asked.times().get(), "abandoned is asked once");
        assertEquals(List.of(), world.queued(), "nothing queued");

        Future<String> retry = save(in, "K-1", () -> false);
        assertTrue(retry.succeeded(), "the sender's retry saves: the abandoned save left nothing in its way: " + retry.cause());
        assertEquals(List.of(new Queued("m-abandoned", FLOW, 1, "K-1")), world.queued(), "one item: the retry's");
        assertEquals(List.of(Recorder.INGRESS), stepNames(world.recorded("m-abandoned")));
    }
}
