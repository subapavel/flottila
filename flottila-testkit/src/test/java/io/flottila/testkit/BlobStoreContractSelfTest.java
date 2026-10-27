package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Hasher;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.flottila.testkit.ContractRun.caughtBy;
import static io.flottila.testkit.ContractRun.caughtExactlyBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-53: the blob store contract tells a correct store from one with one property wrong — one broken variant per property,
 * run through the JUnit engine, asserting the exact set of failing cases and, per case, the check that caught the defect
 * (as {@link ComponentContractSelfTest} and {@link DurableBlobStoreContractSelfTest} do). A variant keeps its bytes in an
 * {@link InMemoryBlobStore}; only the property its {@link Defect} names differs.
 */
class BlobStoreContractSelfTest {

    enum Defect {
        NONE,
        /** A whole write's body hash covers the key too: equal bytes under two keys hash apart. */
        WHOLE_HASH_SALTED_WITH_THE_KEY,
        /** A whole write's body hash covers the message id too: equal bytes of two messages hash apart. */
        WHOLE_HASH_SALTED_WITH_THE_MESSAGE_ID,
        /** A whole write's body reports one byte more than it holds. */
        WHOLE_SIZE_ONE_TOO_MANY,
        /** A whole write's body names the next step's key, not the key written. */
        WHOLE_BODY_UNDER_THE_NEXT_STEP,
        /** A whole write's body reads through a view that appends its key to the bytes; the store's own read is right. */
        WHOLE_BODY_READS_THROUGH_A_CORRUPT_VIEW,
        /** The store's read appends the key to the bytes; the body a whole write returns reads right. */
        READ_APPENDS_THE_KEY,
        /** Equal bytes are one object: a whole write returns the body of the first key that stored them. */
        CONTENT_ADDRESSED,
        /** A streamed write's body hash is the empty body's. */
        STREAMED_HASH_OF_NOTHING,
        /** A streamed write's body reports one byte more than it holds. */
        STREAMED_SIZE_ONE_TOO_MANY,
        /** Every open delivers one byte too many at the end. */
        OPEN_APPENDS_A_BYTE,
        /** Open is not cold: a second open of a key delivers nothing. */
        OPEN_ONCE_ONLY,
        /** A refused or failed write leaves bytes under its key. */
        REFUSED_WRITE_LEAVES_BYTES,
        /** No write is refused: one above the flow's max size is stored, a failing stream stores an empty body. */
        NEVER_REFUSES,
        /** A read of a key never written succeeds with an empty buffer. */
        UNKNOWN_KEY_READS_EMPTY
    }

    static final class Variant implements BlobStore {
        private final InMemoryBlobStore local;
        private final Defect defect;
        private final Map<BlobKey, Integer> opens = new ConcurrentHashMap<>();
        private final Map<io.flottila.api.Hash, BlobKey> firstKeyOf = new ConcurrentHashMap<>();

        Variant(Defect defect) { this(new InMemoryBlobStore(Hasher.sha256(), BlobStoreContract.TREE_LIMIT), defect); }

        private Variant(InMemoryBlobStore local, Defect defect) { this.local = local; this.defect = defect; }

        @Override public long treeLimit() { return local.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            Future<Body> written = local.write(key, whole, defect == Defect.NEVER_REFUSES ? Long.MAX_VALUE : maxSize);
            if (defect == Defect.REFUSED_WRITE_LEAVES_BYTES) written = written.recover(t -> local.write(key, whole, Long.MAX_VALUE).compose(b -> Future.failedFuture(t)));
            return written.map(b -> wholeBody(b, whole));
        }

        private Body wholeBody(Body b, Buffer whole) {
            Hasher h = Hasher.sha256();
            return switch (defect) {
                case WHOLE_HASH_SALTED_WITH_THE_KEY -> Body.of(this, b.key(), h.hashOf(Buffer.buffer(b.key().toString()).appendBuffer(whole)), b.size());
                case WHOLE_HASH_SALTED_WITH_THE_MESSAGE_ID -> Body.of(this, b.key(), h.hashOf(Buffer.buffer(b.key().messageId()).appendBuffer(whole)), b.size());
                case WHOLE_SIZE_ONE_TOO_MANY -> Body.of(this, b.key(), b.hash(), b.size() + 1);
                case WHOLE_BODY_UNDER_THE_NEXT_STEP -> Body.of(this, new BlobKey(b.key().messageId(), b.key().step() + 1, b.key().part()), b.hash(), b.size());
                case WHOLE_BODY_READS_THROUGH_A_CORRUPT_VIEW -> Body.of(new Variant(local, Defect.READ_APPENDS_THE_KEY), b.key(), b.hash(), b.size());
                case READ_APPENDS_THE_KEY -> Body.of(local, b.key(), b.hash(), b.size());
                case CONTENT_ADDRESSED -> Body.of(this, firstKeyOf.computeIfAbsent(b.hash(), k -> b.key()), b.hash(), b.size());
                default -> Body.of(this, b.key(), b.hash(), b.size());
            };
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            Future<Body> written = local.write(key, in, defect == Defect.NEVER_REFUSES ? Long.MAX_VALUE : maxSize);
            if (defect == Defect.NEVER_REFUSES) written = written.recover(t -> local.write(key, Buffer.buffer(), Long.MAX_VALUE));
            if (defect == Defect.REFUSED_WRITE_LEAVES_BYTES) {
                written = written.recover(t -> local.write(key, Buffer.buffer("left behind"), Long.MAX_VALUE).compose(b -> Future.failedFuture(t)));
            }
            return written.map(b -> switch (defect) {
                case STREAMED_HASH_OF_NOTHING -> Body.of(this, b.key(), Hasher.sha256().hashOf(Buffer.buffer()), b.size());
                case STREAMED_SIZE_ONE_TOO_MANY -> Body.of(this, b.key(), b.hash(), b.size() + 1);
                default -> Body.of(this, b.key(), b.hash(), b.size());
            });
        }

        @Override public ReadStream<Buffer> open(BlobKey key) {
            if (!local.contains(key)) return local.open(key);
            int n = opens.merge(key, 1, Integer::sum);
            if (defect == Defect.OPEN_ONCE_ONLY && n > 1) return new ChunkedReadStream(List.of());
            if (defect == Defect.OPEN_APPENDS_A_BYTE) return new ChunkedReadStream(List.of(local.read(key).result(), "!"));
            return local.open(key);
        }

        @Override public Future<Buffer> read(BlobKey key) {
            if (defect == Defect.UNKNOWN_KEY_READS_EMPTY && !local.contains(key)) return Future.succeededFuture(Buffer.buffer());
            if (defect == Defect.READ_APPENDS_THE_KEY) return local.read(key).map(b -> b.copy().appendString(" " + key));
            return local.read(key);
        }

        @Override public Future<Void> persist(BlobKey key) { return local.persist(key); }
    }

    /** The engine instantiates the suite itself; the defect under test is handed over through this slot. */
    static volatile Defect current = Defect.NONE;

    static class VariantContractTest extends BlobStoreContract {
        @Override protected BlobStore store() { return new Variant(current); }
    }

    static Map<String, Throwable> failures(Defect defect) {
        current = defect;
        try {
            return ContractRun.failures(VariantContractTest.class);
        } finally {
            current = Defect.NONE;
        }
    }

    static final String WHOLE = "aWholeBufferRoundTripsWithItsHashAndSize";
    static final String STREAMED = "aStreamedBodyIsHashedWhileItIsWrittenAndReadsBackIntact";
    static final String PAUSED = "aStreamHandedOverPausedIsResumedByTheStore";
    static final String COLD = "openIsAFreshColdStreamEveryTime";
    static final String ABOVE_TREE = "wholeAboveTheTreeLimitThrowsWhileOpenStillStreams";
    static final String BUFFER_ABOVE_MAX = "aBufferAboveTheFlowsMaxSizeIsRefusedAndNothingIsStored";
    static final String STREAM_ABOVE_MAX = "aStreamAboveTheFlowsMaxSizeIsRefusedAndNothingIsStored";
    static final String FAILING_STREAM = "aFailingStreamFailsTheWriteAndNothingIsStored";
    static final String KEYED = "objectsAreKeyedByMessageIdAndStepNotByContent";
    static final String EMPTY = "anEmptyBodyIsABodyToo";
    static final String UNKNOWN = "readingAnUnknownKeyFailsInsteadOfReturningNothing";
    static final String BIG = "aBigStreamPassesThroughIntact";

    /** An assertTrue / assertFalse without a message of its own, as the engine reports it. */
    static final String NOT_TRUE = "org.opentest4j.AssertionFailedError: expected: <true> but was: <false>";

    @Test
    void theInMemoryStoreBehindEveryVariantPassesEveryCase() {
        assertEquals(Map.of(), failures(Defect.NONE));
    }

    @Test
    void aHashThatCoversTheKeyFailsTheHashChecksOfTheWholeWriteTheKeyingAndTheEmptyBody() {
        Map<String, Throwable> f = failures(Defect.WHOLE_HASH_SALTED_WITH_THE_KEY);
        assertEquals(Set.of(WHOLE, KEYED, EMPTY), f.keySet());
        String hashOfTheBytes = "expected: <" + Hasher.sha256().hashOf(Buffer.buffer("<invoice id='4711'/>"));
        caughtBy(f, WHOLE, hashOfTheBytes);
        caughtBy(f, KEYED, "expected: <" + Hasher.sha256().hashOf(Buffer.buffer(BlobKey.body("m-8", 0) + "same bytes"))
                + "> but was: <" + Hasher.sha256().hashOf(Buffer.buffer(BlobKey.body("m-8", 1) + "same bytes")));
        caughtBy(f, EMPTY, "expected: <" + Hasher.sha256().hashOf(Buffer.buffer()));
    }

    @Test
    void aHashThatCoversTheMessageIdFailsTheKeyingAcrossMessages() {
        Map<String, Throwable> f = failures(Defect.WHOLE_HASH_SALTED_WITH_THE_MESSAGE_ID);
        assertEquals(Set.of(WHOLE, KEYED, EMPTY), f.keySet());
        caughtBy(f, KEYED, "but was: <" + Hasher.sha256().hashOf(Buffer.buffer("m-9same bytes")));
    }

    @Test
    void aWholeBodyReportingOneByteTooManyFailsTheSizeChecks() {
        Map<String, Throwable> f = failures(Defect.WHOLE_SIZE_ONE_TOO_MANY);
        assertEquals(Set.of(WHOLE, EMPTY), f.keySet());
        caughtBy(f, WHOLE, "expected: <20> but was: <21>");
        caughtBy(f, EMPTY, "expected: <0> but was: <1>");
    }

    @Test
    void aWholeBodyNamingAnotherKeyFailsTheKeyCheck() {
        Map<String, Throwable> f = failures(Defect.WHOLE_BODY_UNDER_THE_NEXT_STEP);
        assertEquals(Set.of(WHOLE, COLD, EMPTY), f.keySet());
        caughtBy(f, WHOLE, "expected: <" + BlobKey.body("m-1", 0) + "> but was: <" + BlobKey.body("m-1", 1) + ">");
    }

    @Test
    void aWholeBodyReadingOtherBytesThanTheStoreFailsTheWholeChecks() {
        Map<String, Throwable> f = failures(Defect.WHOLE_BODY_READS_THROUGH_A_CORRUPT_VIEW);
        assertEquals(Set.of(WHOLE, EMPTY), f.keySet());
        caughtBy(f, WHOLE, "but was: <<invoice id='4711'/> " + BlobKey.body("m-1", 0) + ">");
        caughtBy(f, EMPTY, "expected: <0> but was: <" + (" " + BlobKey.body("m-10", 0)).length() + ">");
    }

    @Test
    void aStoreReadReturningOtherBytesFailsEveryReadBack() {
        Map<String, Throwable> f = failures(Defect.READ_APPENDS_THE_KEY);
        assertEquals(Set.of(WHOLE, PAUSED, KEYED), f.keySet());
        caughtBy(f, WHOLE, "but was: <<invoice id='4711'/> " + BlobKey.body("m-1", 0) + ">");
        caughtBy(f, PAUSED, "but was: <<a>bbb</a> " + BlobKey.body("m-paused", 0) + ">");
        caughtBy(f, KEYED, "but was: <same bytes " + BlobKey.body("m-8", 0) + ">");
    }

    @Test
    void equalBytesStoredAsOneObjectFailTheKeyIdentityCheck() {
        Map<String, Throwable> f = failures(Defect.CONTENT_ADDRESSED);
        assertEquals(Set.of(KEYED), f.keySet());
        caughtBy(f, KEYED, "distinct objects: the key is part of the identity");
    }

    @Test
    void aStreamedBodyWithTheWrongHashFailsTheStreamedHashChecks() {
        Map<String, Throwable> f = failures(Defect.STREAMED_HASH_OF_NOTHING);
        assertEquals(Set.of(STREAMED, PAUSED, BIG), f.keySet());
        caughtBy(f, STREAMED, "the hash covers every chunk in order");
        caughtBy(f, PAUSED, "but was: <" + Hasher.sha256().hashOf(Buffer.buffer()));
        caughtBy(f, BIG, "hashed while writing");
    }

    @Test
    void aStreamedBodyReportingOneByteTooManyFailsTheStreamedSizeChecks() {
        Map<String, Throwable> f = failures(Defect.STREAMED_SIZE_ONE_TOO_MANY);
        assertEquals(Set.of(STREAMED, PAUSED, ABOVE_TREE, BIG), f.keySet());
        caughtBy(f, STREAMED, "expected: <10> but was: <11>");
        caughtBy(f, PAUSED, "every chunk arrived although the stream was paused when handed over");
        caughtBy(f, ABOVE_TREE, "expected: <" + (BlobStoreContract.TREE_LIMIT + 1) + "> but was: <" + (BlobStoreContract.TREE_LIMIT + 2) + ">");
        caughtBy(f, BIG, "expected: <" + 3L * 1024 * 1024 + "> but was: <" + (3L * 1024 * 1024 + 1) + ">");
    }

    @Test
    void anOpenDeliveringAByteTooManyFailsEveryStreamedReadBack() {
        Map<String, Throwable> f = failures(Defect.OPEN_APPENDS_A_BYTE);
        assertEquals(Set.of(STREAMED, COLD, ABOVE_TREE, EMPTY, BIG), f.keySet());
        caughtBy(f, STREAMED, "expected: <<a>bbb</a>> but was: <<a>bbb</a>!>");
        caughtBy(f, COLD, "expected: <twice> but was: <twice!>");
        caughtBy(f, ABOVE_TREE, "expected: <" + GeneratedReadStream.expectedHash(Hasher.sha256(), BlobStoreContract.TREE_LIMIT + 1));
        caughtBy(f, EMPTY, "expected: <0> but was: <1>");
        caughtBy(f, BIG, "read back byte for byte");
    }

    @Test
    void anOpenThatIsNotColdFailsTheSecondOpen() {
        Map<String, Throwable> f = failures(Defect.OPEN_ONCE_ONLY);
        assertEquals(Set.of(COLD), f.keySet());
        caughtBy(f, COLD, "a second open yields the same bytes from the start");
    }

    @Test
    void aRefusedWriteThatLeavesBytesFailsEveryNothingIsStoredCheck() {
        Map<String, Throwable> f = failures(Defect.REFUSED_WRITE_LEAVES_BYTES);
        assertEquals(Set.of(BUFFER_ABOVE_MAX, STREAM_ABOVE_MAX, FAILING_STREAM), f.keySet());
        caughtBy(f, BUFFER_ABOVE_MAX, "nothing stored under the refused key");
        caughtBy(f, STREAM_ABOVE_MAX, "nothing stored under the refused key");
        caughtExactlyBy(f, FAILING_STREAM, NOT_TRUE);
    }

    @Test
    void aStoreThatNeverRefusesFailsTheRefusalChecks() {
        Map<String, Throwable> f = failures(Defect.NEVER_REFUSES);
        assertEquals(Set.of(BUFFER_ABOVE_MAX, STREAM_ABOVE_MAX, FAILING_STREAM), f.keySet());
        caughtExactlyBy(f, BUFFER_ABOVE_MAX, NOT_TRUE);
        caughtExactlyBy(f, STREAM_ABOVE_MAX, NOT_TRUE);
        caughtExactlyBy(f, FAILING_STREAM, NOT_TRUE);
    }

    @Test
    void anUnknownKeyThatReadsEmptyFailsTheUnknownKeyAndNothingIsStoredChecks() {
        Map<String, Throwable> f = failures(Defect.UNKNOWN_KEY_READS_EMPTY);
        assertEquals(Set.of(UNKNOWN, BUFFER_ABOVE_MAX, STREAM_ABOVE_MAX, FAILING_STREAM), f.keySet());
        caughtExactlyBy(f, UNKNOWN, NOT_TRUE);
        caughtExactlyBy(f, FAILING_STREAM, NOT_TRUE);
    }
}
