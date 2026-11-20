package dev.rail8.runtime.deploy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.rail8.runtime.deploy.DeploymentStore.PoolLock;
import io.vertx.core.Future;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link DeploymentStore} guarantees: one row per package, written by {@code save} (an upsert by name: a second save of
 * a package replaces its row whole), {@code list} answering every row sorted by name in the order {@code DeployedPackage}s are
 * listed ({@link String#compareTo}: {@code shop-eu} before {@code shopa}, whatever the database's collation), {@code deployedAt}
 * kept to the microsecond, the pins and the at-least-once flows of each row as saved, and {@code delete} removing one row; the
 * pool's lock held by one holder at a time, every write through it raising the generation by one, and a lock that was freed
 * writing nothing. Run by {@code InMemoryDeploymentStoreTest} and {@code PgDeploymentStoreIT}.
 */
public abstract class DeploymentStoreContract {

    static final String SHA_1 = "sha256:" + "1".repeat(64);
    static final String SHA_2 = "sha256:" + "2a".repeat(32);
    static final String SHA_3 = "sha256:" + "0123456789abcdef".repeat(4);
    static final String C_1 = "1".repeat(40);
    static final String C_2 = "2b".repeat(20);
    static final String C_3 = "0123456789abcdef0123456789abcdef01234567";
    static final Instant AT = Instant.parse("2026-10-27T09:30:00.123456Z");

    /** The case's store: empty. */
    protected DeploymentStore store;

    /** A fresh, empty store for one case. */
    protected abstract DeploymentStore emptyStore();

    @BeforeEach
    void openTheStore() {
        store = emptyStore();
    }

    static DeploymentRecord record(String name, String version, String commit, String content, Instant at) {
        // out of order: the record sorts them
        return new DeploymentRecord(name, version, name, commit, content, at, List.of(), List.of(name + "/orders", name + "/audit"));
    }

    static DeploymentRecord record(String name, String version, String content, List<DeploymentRecord.Pin> pins) {
        return new DeploymentRecord(name, version, name, C_1, content, AT, pins, List.of());
    }

    static DeploymentRecord.Pin pin(String name, String version, String content, String commit) {
        return new DeploymentRecord.Pin(name, version, content, name + "-repo", commit);
    }

    List<DeploymentRecord> list() {
        return store.read().await().records();
    }

    /** The pool's lock, taken: a case that finds it held fails. */
    PoolLock lock() {
        return store.lock().await().orElseThrow(() -> new AssertionError("the pool's lock is held"));
    }

    void save(DeploymentRecord r) {
        PoolLock lock = lock();
        try {
            lock.save(r).await();
        } finally {
            lock.close().await();
        }
    }

    void delete(String name) {
        PoolLock lock = lock();
        try {
            lock.delete(name).await();
        } finally {
            lock.close().await();
        }
    }

    @Test
    void aSaveIsListed() {
        assertEquals(List.of(), list(), "an empty store lists nothing");
        DeploymentRecord shop = record("shop", "1.0.0", C_1, SHA_1, AT);
        save(shop);
        assertEquals(List.of(shop), list(), "the row saved, every field as saved");
        assertEquals(List.of(shop), list(), "listing reads, it does not consume");
    }

    @Test
    void aSaveReplacesThePackagesRow() {
        DeploymentRecord first = record("shop", "1.0.0", C_1, SHA_1, AT);
        DeploymentRecord other = record("billing", "3.1.0", C_3, SHA_3, AT.plusSeconds(5));
        save(first);
        save(other);
        // every field differs: an upsert that keeps an old column (a coalesce, a partial update) shows here
        DeploymentRecord second = new DeploymentRecord("shop", "2.0.0", "shop-moved", C_2, SHA_2, AT.plusSeconds(3600),
                List.of(pin("common", "1.0.0", SHA_3, C_3)), List.of("shop/refunds"));
        save(second);
        assertEquals(List.of(other, second), list(), "one row per package: shop's second save replaced its first whole, billing's row stays");
    }

    @Test
    void listIsSortedByName() {
        // saved out of order; '-' (0x2d) and '2' (0x32) sort before 'a' as Java strings, but not under en_US collation
        // (which ignores the dash): the order is DeployedPackage's, String.compareTo, whatever the database's locale
        List<String> saved = List.of("zeta", "shopa", "shop-eu", "alpha", "shop2", "shop");
        for (String name : saved) save(record(name, "1.0.0", C_1, SHA_1, AT));
        assertEquals(List.of("alpha", "shop", "shop-eu", "shop2", "shopa", "zeta"), list().stream().map(DeploymentRecord::name).toList(),
                "listed by name in String order (code points), as Deployments lists its packages");
    }

    @Test
    void deployedAtRoundTripsToTheMicrosecond() {
        // .999999999: truncating keeps the second and .999999; rounding to the microsecond would carry into the next second
        Instant nanos = Instant.parse("2026-10-27T09:30:59.999999999Z");
        DeploymentRecord r = record("shop", "1.0.0", C_1, SHA_1, nanos);
        assertEquals(Instant.parse("2026-10-27T09:30:59.999999Z"), r.deployedAt(), "the record truncates deployedAt to the microsecond, never rounds");
        DeploymentRecord older = record("archive", "1.0.0", C_2, SHA_2, Instant.parse("1999-12-31T23:59:59.000001Z"));
        save(r);
        save(older);
        List<DeploymentRecord> back = list();
        assertEquals(List.of(older, r), back, "each deployedAt comes back to the microsecond");
        assertEquals(Instant.parse("2026-10-27T09:30:59.999999Z"), back.get(1).deployedAt());
        assertEquals(Instant.parse("1999-12-31T23:59:59.000001Z"), back.get(0).deployedAt(), "one microsecond is kept, not dropped");
    }

    /** Pins come back in the order saved, every field as saved; a second save replaces them whole; one without pins empties them. */
    @Test
    void pinsRoundTrip() {
        // out of name order, two versions of one name: a store that sorts, dedupes by name or keeps only the first shows here
        List<DeploymentRecord.Pin> pins = List.of(pin("util", "2.0.0", SHA_3, C_3), pin("common", "1.2.0", SHA_1, C_1),
                pin("common", "1.3.0-rc.1", SHA_2, C_2));
        DeploymentRecord invoice = record("invoice", "1.0.0", SHA_1, pins);
        DeploymentRecord common = record("common", "1.2.0", SHA_2, List.of());
        save(invoice);
        save(common);

        assertEquals(List.of(common, invoice), list(), "every field as saved, pins included");
        assertEquals(pins, list().get(1).dependencies(), "the pins in the order saved");
        assertEquals(List.of(), list().get(0).dependencies(), "no pins: an empty list, never null");

        List<DeploymentRecord.Pin> other = List.of(pin("common", "1.4.0", SHA_3, C_2));
        save(record("invoice", "1.0.1", SHA_2, other));
        assertEquals(other, list().get(1).dependencies(), "a second save replaces the pins whole: " + list());

        save(record("invoice", "1.0.2", SHA_3, List.of()));
        assertEquals(List.of(), list().get(1).dependencies(), "a save without pins empties them: " + list());
    }

    /**
     * {@code delete(name)} removes that package's row, pins included, and no other — not one whose name it
     * prefixes ({@code shop-eu}, {@code shopa}); a later save of the name is listed again, as saved.
     */
    @Test
    void aDeleteRemovesOnlyThatPackagesRow() {
        DeploymentRecord shop = record("shop", "1.0.0", SHA_1, List.of(pin("common", "1.2.0", SHA_2, C_2)));
        DeploymentRecord shopEu = record("shop-eu", "2.0.0", C_2, SHA_2, AT);
        DeploymentRecord shopa = record("shopa", "3.0.0", C_3, SHA_3, AT);
        DeploymentRecord common = record("common", "1.2.0", C_1, SHA_2, AT);
        for (DeploymentRecord r : List.of(shop, shopEu, shopa, common)) save(r);

        delete("shop");

        assertEquals(List.of(common, shopEu, shopa), list(), "shop's row is gone, every other row as saved");

        DeploymentRecord again = record("shop", "1.0.1", C_3, SHA_3, AT.plusSeconds(60));
        save(again);
        assertEquals(List.of(common, again, shopEu, shopa), list(), "a save after the delete is listed again, as saved, with no old pin");

        delete("common");
        delete("shop-eu");
        assertEquals(List.of(again, shopa), list(), "each delete removes its own row");
    }

    /** The lock is one holder's: a second one is refused, not waited for, until the first is freed; then it is taken again. */
    @Test
    void theLockIsOneHoldersAtATime() {
        PoolLock first = lock();
        Optional<PoolLock> second = store.lock().await();
        assertTrue(second.isEmpty(), "a second holder is refused while the first holds the lock");
        first.close().await();
        PoolLock again = lock();
        again.close().await();
    }

    /**
     * Each save and each delete raises the generation by one and answers it, a delete of a package with no row too; a lock reads
     * the pool as it was when taken, and a read answers the generation with the records.
     */
    @Test
    void eachWriteRaisesTheGeneration() {
        long start = store.read().await().generation();
        DeploymentRecord shop = record("shop", "1.0.0", C_1, SHA_1, AT);
        PoolLock lock = lock();
        try {
            assertEquals(new DeploymentStore.Snapshot(start, List.of()), lock.snapshot(), "the pool as the lock read it");
            assertEquals(start + 1, lock.save(shop).await(), "a save raises the generation");
            assertEquals(start + 2, lock.delete("billing").await(), "a delete raises it, a row there or not");
        } finally {
            lock.close().await();
        }
        assertEquals(new DeploymentStore.Snapshot(start + 2, List.of(shop)), store.read().await(), "the generation and the records, read together");
        PoolLock next = lock();
        try {
            assertEquals(new DeploymentStore.Snapshot(start + 2, List.of(shop)), next.snapshot(), "the next lock reads what the last one wrote");
        } finally {
            next.close().await();
        }
    }

    /** A lock that was freed writes nothing: its save and its delete fail, and the pool stays as it was. */
    @Test
    void aFreedLockWritesNothing() {
        DeploymentRecord shop = record("shop", "1.0.0", C_1, SHA_1, AT);
        save(shop);
        DeploymentStore.Snapshot before = store.read().await();
        PoolLock lock = lock();
        lock.close().await();

        assertTrue(failsLater(lock.save(record("billing", "1.0.0", C_2, SHA_2, AT))), "a save through a freed lock fails");
        assertTrue(failsLater(lock.delete("shop")), "a delete through a freed lock fails");
        assertEquals(before, store.read().await(), "nothing written");
    }

    /** Whether {@code write} fails, once it completed. */
    static boolean failsLater(Future<Long> write) {
        return write.transform(done -> Future.succeededFuture(done.failed())).await();
    }
}
