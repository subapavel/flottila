package io.flottila.runtime.deploy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-09c3a (D-070): what every {@link DeploymentStore} guarantees — one row per package, written by {@code save} (an upsert by
 * name: a second save of a package replaces its row whole), {@code list} answering every row sorted by name in the order
 * {@code DeployedPackage}s are listed ({@link String#compareTo}: {@code shop-eu} before {@code shopa}, whatever the database's
 * collation), {@code deployedAt} kept to the microsecond and an absent revision answered empty. Run by
 * {@code InMemoryDeploymentStoreTest} and {@code PgDeploymentStoreIT}.
 *
 * <p>The shape these cases assume (P1-09c3a test-author): in {@code io.flottila.runtime.deploy},
 * {@code record DeploymentRecord(String name, String version, Optional<String> revision, String archiveSha256, Instant deployedAt)}
 * whose constructor truncates {@code deployedAt} to microseconds, and {@code interface DeploymentStore} with
 * {@code Future<Void> save(DeploymentRecord)} and {@code Future<List<DeploymentRecord>> list()}.
 */
public abstract class DeploymentStoreContract {

    static final String SHA_1 = "1".repeat(64);
    static final String SHA_2 = "2a".repeat(32);
    static final String SHA_3 = "0123456789abcdef".repeat(4);
    static final Instant AT = Instant.parse("2026-10-27T09:30:00.123456Z");

    /** The case's store: empty. */
    protected DeploymentStore store;

    /** A fresh, empty store for one case. */
    protected abstract DeploymentStore emptyStore();

    @BeforeEach
    void openTheStore() {
        store = emptyStore();
    }

    static DeploymentRecord record(String name, String version, Optional<String> revision, String sha256, Instant at) {
        return new DeploymentRecord(name, version, revision, sha256, at);
    }

    List<DeploymentRecord> list() {
        return store.list().await();
    }

    void save(DeploymentRecord r) {
        store.save(r).await();
    }

    @Test
    void aSaveIsListed() {
        assertEquals(List.of(), list(), "an empty store lists nothing");
        DeploymentRecord shop = record("shop", "1.0.0", Optional.of("r1"), SHA_1, AT);
        save(shop);
        assertEquals(List.of(shop), list(), "the row saved, every field as saved");
        assertEquals(List.of(shop), list(), "listing reads, it does not consume");
    }

    @Test
    void aSaveReplacesThePackagesRow() {
        DeploymentRecord first = record("shop", "1.0.0", Optional.of("r1"), SHA_1, AT);
        DeploymentRecord other = record("billing", "3.1.0", Optional.of("b7"), SHA_3, AT.plusSeconds(5));
        save(first);
        save(other);
        // every field differs, the revision goes away: an upsert that keeps an old column (a coalesce, a partial update) shows here
        DeploymentRecord second = record("shop", "2.0.0", Optional.empty(), SHA_2, AT.plusSeconds(3600));
        save(second);
        assertEquals(List.of(other, second), list(), "one row per package: shop's second save replaced its first whole, billing's row stays");
    }

    @Test
    void listIsSortedByName() {
        // saved out of order; '-' (0x2d) and '2' (0x32) sort before 'a' as Java strings, but not under en_US collation
        // (which ignores the dash): the order is DeployedPackage's, String.compareTo, whatever the database's locale
        List<String> saved = List.of("zeta", "shopa", "shop-eu", "alpha", "shop2", "shop");
        for (String name : saved) save(record(name, "1.0.0", Optional.empty(), SHA_1, AT));
        assertEquals(List.of("alpha", "shop", "shop-eu", "shop2", "shopa", "zeta"), list().stream().map(DeploymentRecord::name).toList(),
                "listed by name in String order (code points), as Deployments lists its packages");
    }

    @Test
    void deployedAtRoundTripsToTheMicrosecond() {
        // .999999999: truncating keeps the second and .999999; rounding to the microsecond would carry into the next second
        Instant nanos = Instant.parse("2026-10-27T09:30:59.999999999Z");
        DeploymentRecord r = record("shop", "1.0.0", Optional.of("r1"), SHA_1, nanos);
        assertEquals(Instant.parse("2026-10-27T09:30:59.999999Z"), r.deployedAt(), "the record truncates deployedAt to the microsecond, never rounds");
        DeploymentRecord older = record("archive", "1.0.0", Optional.empty(), SHA_2, Instant.parse("1999-12-31T23:59:59.000001Z"));
        save(r);
        save(older);
        List<DeploymentRecord> back = list();
        assertEquals(List.of(older, r), back, "each deployedAt comes back to the microsecond");
        assertEquals(Instant.parse("2026-10-27T09:30:59.999999Z"), back.get(1).deployedAt());
        assertEquals(Instant.parse("1999-12-31T23:59:59.000001Z"), back.get(0).deployedAt(), "one microsecond is kept, not dropped");
    }

    @Test
    void anAbsentRevisionIsEmpty() {
        save(record("shop", "1.0.0", Optional.empty(), SHA_1, AT));
        save(record("billing", "1.0.0", Optional.of("0"), SHA_2, AT));
        List<DeploymentRecord> back = list();
        assertEquals(Optional.of("0"), back.get(0).revision(), "a revision is kept as given: " + back);
        assertEquals(Optional.empty(), back.get(1).revision(), "no revision: empty, never null and never an empty string: " + back);
    }
}
