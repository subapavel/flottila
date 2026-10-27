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

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

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

    /** A heap store whose {@code persist} follows {@code rule}; everything else is the in-memory store's. */
    static final class Variant implements BlobStore {
        private final InMemoryBlobStore local = new InMemoryBlobStore(Hasher.sha256(), BlobStoreContract.TREE_LIMIT);
        private final Map<BlobKey, Buffer> rows = new ConcurrentHashMap<>();
        private final Rule rule;

        Variant(Rule rule) { this.rule = rule; }

        @Override public long treeLimit() { return local.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            return local.write(key, whole, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            return local.write(key, in, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
        }

        @Override public ReadStream<Buffer> open(BlobKey key) { return local.open(key); }

        @Override public Future<Buffer> read(BlobKey key) { return local.read(key); }

        @Override public Future<Void> persist(BlobKey key) {
            if (!local.contains(key)) return Future.failedFuture(new IllegalArgumentException("no object stored under " + key));
            return rule.persist(rows, local, key, local.read(key).result());
        }
    }

    /** The engine instantiates the suite itself; the rule under test is handed over through this slot. */
    static volatile Rule current = CORRECT;

    static class VariantContractTest extends DurableBlobStoreContract {
        @Override protected BlobStore store() { return new Variant(current); }
    }

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
}
