package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Hasher;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static io.flottila.testkit.ContractRun.caughtBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * FIX-35 (D-061 amended): the durable contract tells a store whose durable bytes never change from one that bends the rule —
 * one broken variant per wrong rule, run through the JUnit engine, asserting the exact set of failing cases (as
 * {@link ComponentContractSelfTest} does for components). A variant keeps its bodies in an {@link InMemoryBlobStore} and its
 * durable rows in a map of its own; only its {@code persist} differs.
 */
class DurableBlobStoreContractSelfTest {

    /** How a variant persists {@code bytes}, the body stored under {@code key}, given its durable {@code rows}. */
    interface Rule {
        Future<Void> persist(Map<BlobKey, Buffer> rows, InMemoryBlobStore local, BlobKey key, Buffer bytes);
    }

    static Future<Void> refused(BlobKey key, Buffer bytes) {
        return Future.failedFuture(new IllegalStateException(DurableBlobStoreContract.otherBytesRefused(key, bytes.length())));
    }

    /** The rule: the first durable bytes stay; the same bytes again are quiet, other bytes are refused and stay local. */
    static final Rule CORRECT = (rows, local, key, bytes) -> {
        Buffer prior = rows.putIfAbsent(key, bytes.copy());
        return prior == null || prior.equals(bytes) ? Future.succeededFuture() : refused(key, bytes);
    };

    /** D-061 before FIX-35: a conflicting insert does nothing and the persist completes quietly. */
    static final Rule FIRST_BYTES_WIN = (rows, local, key, bytes) -> {
        rows.putIfAbsent(key, bytes.copy());
        return Future.succeededFuture();
    };

    /** The compare looks at the length only. */
    static final Rule LENGTH_ONLY = (rows, local, key, bytes) -> {
        Buffer prior = rows.putIfAbsent(key, bytes.copy());
        return prior == null || prior.length() == bytes.length() ? Future.succeededFuture() : refused(key, bytes);
    };

    /** The row is replaced (put, not putIfAbsent) and the bytes it held are compared. */
    static final Rule OVERWRITES = (rows, local, key, bytes) -> {
        Buffer prior = rows.put(key, bytes.copy());
        return prior == null || prior.equals(bytes) ? Future.succeededFuture() : refused(key, bytes);
    };

    /** Any persist of a key with a row is refused, the same bytes too. */
    static final Rule REFUSES_EVERY_REPERSIST = (rows, local, key, bytes) -> {
        Buffer prior = rows.putIfAbsent(key, bytes.copy());
        return prior == null ? Future.succeededFuture() : refused(key, bytes);
    };

    /** A refused body does not stay local: the key reads the row's bytes afterwards. */
    static final Rule MISMATCH_RELEASES = (rows, local, key, bytes) -> {
        Buffer prior = rows.putIfAbsent(key, bytes.copy());
        if (prior == null || prior.equals(bytes)) return Future.succeededFuture();
        local.write(key, prior, Long.MAX_VALUE);
        return refused(key, bytes);
    };

    /** The compare matches any row of the message and part, whatever its step. */
    static final Rule COMPARE_IGNORES_STEP = (rows, local, key, bytes) -> {
        Buffer prior = rows.putIfAbsent(key, bytes.copy());
        if (prior == null) return Future.succeededFuture();
        boolean match = rows.entrySet().stream().anyMatch(r -> r.getKey().messageId().equals(key.messageId())
                && r.getKey().part().equals(key.part()) && r.getValue().equals(bytes));
        return match ? Future.succeededFuture() : refused(key, bytes);
    };

    /** The compare matches any row of the message and step, whatever its part. */
    static final Rule COMPARE_IGNORES_PART = (rows, local, key, bytes) -> {
        Buffer prior = rows.putIfAbsent(key, bytes.copy());
        if (prior == null) return Future.succeededFuture();
        boolean match = rows.entrySet().stream().anyMatch(r -> r.getKey().messageId().equals(key.messageId())
                && r.getKey().step() == key.step() && r.getValue().equals(bytes));
        return match ? Future.succeededFuture() : refused(key, bytes);
    };

    /** One row per message: another step or part of the message conflicts with the first one persisted. */
    static final Rule ONE_ROW_PER_MESSAGE = (rows, local, key, bytes) -> {
        BlobKey row = BlobKey.body(key.messageId(), 0);
        Buffer prior = rows.putIfAbsent(row, bytes.copy());
        return prior == null || prior.equals(bytes) ? Future.succeededFuture() : refused(key, bytes);
    };

    /** How a variant's local tier serves reads (FIX-53): right, or with one defect. */
    enum Local {
        RIGHT,
        /** read serves a key's durable row when it has one, not the bytes written last. */
        READ_PREFERS_THE_ROW,
        /** open serves a key's durable row when it has one. */
        OPEN_PREFERS_THE_ROW,
        /** read caches its first answer per key; a later write does not invalidate it. */
        READ_CACHED,
        /** open caches its first answer per key; a later write does not invalidate it. */
        OPEN_CACHED,
        /** the local bytes are slotted by message id and step, the part dropped (rows keep the full key): an attachment's write
         *  overwrites the body of its step. */
        PART_BLIND
    }

    /** A heap store whose {@code persist} follows {@code rule}; everything else is the in-memory store's, served as {@link Local} says. */
    static final class Variant implements BlobStore {
        private final InMemoryBlobStore local = new InMemoryBlobStore(Hasher.sha256(), BlobStoreContract.TREE_LIMIT);
        private final Map<BlobKey, Buffer> rows = new ConcurrentHashMap<>();
        private final Map<BlobKey, Buffer> readCache = new ConcurrentHashMap<>();
        private final Map<BlobKey, Buffer> openCache = new ConcurrentHashMap<>();
        private final Rule rule;
        private final Local served;

        Variant(Rule rule) { this(rule, Local.RIGHT); }

        Variant(Rule rule, Local served) {
            this.rule = rule;
            this.served = served;
        }

        /** Where the local tier keeps {@code key}'s bytes. */
        private BlobKey slot(BlobKey key) { return served == Local.PART_BLIND ? BlobKey.body(key.messageId(), key.step()) : key; }

        @Override public long treeLimit() { return local.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            return local.write(slot(key), whole, maxSize).map(b -> Body.of(this, key, b.hash(), b.size()));
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            return local.write(slot(key), in, maxSize).map(b -> Body.of(this, key, b.hash(), b.size()));
        }

        @Override public ReadStream<Buffer> open(BlobKey key) {
            Buffer row = rows.get(key);
            if (served == Local.OPEN_PREFERS_THE_ROW && row != null) return new ChunkedReadStream(List.of(row));
            if (served == Local.OPEN_CACHED && local.contains(key)) {
                return new ChunkedReadStream(List.of(openCache.computeIfAbsent(key, k -> local.read(k).result())));
            }
            return local.open(slot(key));
        }

        @Override public Future<Buffer> read(BlobKey key) {
            Buffer row = rows.get(key);
            if (served == Local.READ_PREFERS_THE_ROW && row != null) return Future.succeededFuture(row.copy());
            if (served == Local.READ_CACHED && local.contains(key)) {
                return Future.succeededFuture(readCache.computeIfAbsent(key, k -> local.read(k).result()).copy());
            }
            return local.read(slot(key));
        }

        @Override public Future<Void> persist(BlobKey key) {
            if (!local.contains(slot(key))) return Future.failedFuture(new IllegalArgumentException("no object stored under " + key));
            return rule.persist(rows, local, key, local.read(slot(key)).result());
        }
    }

    /** The engine instantiates the suite itself; the rule under test is handed over through this slot. */
    static volatile Rule current = CORRECT;

    /** As {@link #current}, for the local tier's defects (FIX-53). */
    static volatile Local served = Local.RIGHT;

    static class VariantContractTest extends DurableBlobStoreContract {
        @Override protected BlobStore store() { return new Variant(current, served); }
    }

    /** FIX-53: the failing cases of {@code rule}'s variant with what each failed with — the check that caught the defect. */
    static Map<String, Throwable> failures(Rule rule, Local served) {
        current = rule;
        DurableBlobStoreContractSelfTest.served = served;
        try {
            return ContractRun.failures(VariantContractTest.class);
        } finally {
            current = CORRECT;
            DurableBlobStoreContractSelfTest.served = Local.RIGHT;
        }
    }

    static Map<String, Throwable> failures(Rule rule) { return failures(rule, Local.RIGHT); }

    static Set<String> failing(Rule rule) {
        current = rule;
        Events events = EngineTestKit.engine("junit-jupiter").selectors(selectClass(VariantContractTest.class)).execute().testEvents();
        assertEquals(0, events.skipped().count(), "nothing skipped");
        assertEquals(0, events.aborted().count(), "nothing aborted");
        Set<String> failed = new TreeSet<>();
        events.failed().stream().forEach(e -> failed.add(e.getTestDescriptor().getDisplayName().replace("()", "")));
        return failed;
    }

    static final String OTHER_BYTES = "persistingOtherBytesUnderADurableKeyFailsIllegalStateAndKeepsTheFirst";
    static final String SAME_BYTES = "persistingTheSameBytesWrittenAgainIsQuiet";
    static final String SIBLINGS = "otherBytesUnderAnotherStepOrPartAreNoConflict";

    @Test
    void aStoreWhoseDurableBytesNeverChangePassesEveryCase() {
        assertEquals(Set.of(), failing(CORRECT));
    }

    @Test
    void aQuietPersistOfOtherBytesFailsTheOtherBytesAndTheSiblingCases() {
        assertEquals(Set.of(OTHER_BYTES, SIBLINGS), failing(FIRST_BYTES_WIN), "first bytes win, quietly");
    }

    @Test
    void aLengthOnlyCompareFailsTheCasesWithOtherBytesOfTheSameSize() {
        assertEquals(Set.of(OTHER_BYTES, SIBLINGS), failing(LENGTH_ONLY));
    }

    @Test
    void anOverwrittenRowFailsTheKeepsTheFirstCase() {
        assertEquals(Set.of(OTHER_BYTES), failing(OVERWRITES));
    }

    @Test
    void refusingTheSameBytesAgainFailsTheQuietCases() {
        assertEquals(Set.of(OTHER_BYTES, SAME_BYTES, "persistingTwiceSucceeds"), failing(REFUSES_EVERY_REPERSIST));
    }

    @Test
    void aRefusedBodyThatDoesNotStayLocalFailsTheCasesThatReadItBack() {
        assertEquals(Set.of(OTHER_BYTES, SIBLINGS), failing(MISMATCH_RELEASES));
    }

    @Test
    void aCompareThatIgnoresTheStepOrThePartFailsTheSiblingCase() {
        assertEquals(Set.of(SIBLINGS), failing(COMPARE_IGNORES_STEP), "step dropped from the compare");
        assertEquals(Set.of(SIBLINGS), failing(COMPARE_IGNORES_PART), "part dropped from the compare");
    }

    @Test
    void oneRowPerMessageFailsTheSiblingCase() {
        assertEquals(Set.of(SIBLINGS), failing(ONE_ROW_PER_MESSAGE));
    }

    // ---- FIX-53: the check that catches each defect (the first failing assertion of the case), not only the cases ----------

    /** The first loop round of the other-bytes case: other bytes of the same size, the last byte changed. */
    static final String SAME_SIZE = "(same size, last byte changed)";

    @Test
    void aQuietPersistOfOtherBytesIsCaughtByTheRefusalChecks() {
        Map<String, Throwable> f = failures(FIRST_BYTES_WIN);
        assertEquals(Set.of(OTHER_BYTES, SIBLINGS), f.keySet());
        caughtBy(f, OTHER_BYTES, "other bytes under a durable key are not persisted " + SAME_SIZE);
        caughtBy(f, SIBLINGS, "a sibling's durable bytes are no match");
    }

    /** Other bytes refused, but with a text of the store's own instead of {@link DurableBlobStoreContract#otherBytesRefused}. */
    static final Rule REFUSED_IN_OTHER_WORDS = (rows, local, key, bytes) -> {
        Buffer prior = rows.putIfAbsent(key, bytes.copy());
        return prior == null || prior.equals(bytes) ? Future.succeededFuture() : Future.failedFuture(new IllegalStateException("conflict under " + key));
    };

    @Test
    void aRefusalInOtherWordsIsCaughtByTheRefusalTextChecks() {
        Map<String, Throwable> f = failures(REFUSED_IN_OTHER_WORDS);
        assertEquals(Set.of(OTHER_BYTES, SIBLINGS), f.keySet());
        caughtBy(f, OTHER_BYTES, "but was: <conflict under " + BlobKey.body("p-other-0", 1) + ">");
        caughtBy(f, SIBLINGS, "but was: <conflict under " + BlobKey.body("p-sib", 1) + ">");
    }

    @Test
    void aRefusedBodyThatDoesNotStayLocalIsCaughtByTheFirstReadBack() {
        Map<String, Throwable> f = failures(MISMATCH_RELEASES);
        caughtBy(f, OTHER_BYTES, "the body stays as written: the key reads the other bytes " + SAME_SIZE);
    }

    @Test
    void aReadServingTheDurableRowIsCaughtByTheFixtureReadBeforeThePersist() {
        Map<String, Throwable> f = failures(CORRECT, Local.READ_PREFERS_THE_ROW);
        assertEquals(Set.of(OTHER_BYTES, SIBLINGS), f.keySet());
        caughtBy(f, OTHER_BYTES, "fixture: the key reads the other bytes before the persist " + SAME_SIZE);
        caughtBy(f, SIBLINGS, "the body stays as written");
    }

    @Test
    void anOpenServingTheDurableRowIsCaughtByTheStreamedReadBack() {
        Map<String, Throwable> f = failures(CORRECT, Local.OPEN_PREFERS_THE_ROW);
        assertEquals(Set.of(OTHER_BYTES), f.keySet());
        caughtBy(f, OTHER_BYTES, "streamed too " + SAME_SIZE);
    }

    @Test
    void anOverwrittenRowIsCaughtByTheSecondRefusal() {
        Map<String, Throwable> f = failures(OVERWRITES);
        caughtBy(f, OTHER_BYTES, "the durable bytes are still the first: the other bytes are refused again " + SAME_SIZE);
    }

    @Test
    void refusingTheSameBytesAgainIsCaughtByTheQuietRepersist() {
        Map<String, Throwable> f = failures(REFUSES_EVERY_REPERSIST);
        caughtBy(f, SAME_BYTES, "the same bytes again: idempotent and quiet");
    }

    @Test
    void aReadCachedAcrossAWriteIsCaughtByTheReadAfterTheFirstBytesPersistAgain() {
        Map<String, Throwable> f = failures(CORRECT, Local.READ_CACHED);
        assertEquals(Set.of(OTHER_BYTES, SIBLINGS), f.keySet());
        caughtBy(f, OTHER_BYTES, "last byte changed ==>");
        caughtBy(f, SIBLINGS, "the body stays as written");
    }

    @Test
    void anOpenCachedAcrossAWriteIsCaughtByTheStreamedReadAfterTheFirstBytesPersistAgain() {
        Map<String, Throwable> f = failures(CORRECT, Local.OPEN_CACHED);
        assertEquals(Set.of(OTHER_BYTES), f.keySet());
        caughtBy(f, OTHER_BYTES, "last byte changed ==>");
    }

    @Test
    void aPartBlindLocalTierIsCaughtByTheDurableKeyReadingItsOwnBytesAfterTheSiblingsWrites() {
        Map<String, Throwable> f = failures(CORRECT, Local.PART_BLIND);
        assertEquals(Set.of(SIBLINGS), f.keySet());
        caughtBy(f, SIBLINGS, "the durable key reads its own bytes");
    }
}
