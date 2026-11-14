package io.flottila.runtime.blob;

import io.flottila.api.Hasher;
import io.flottila.runtime.journal.TestJournals;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * The store of the blob unit tests since CORE-4c2: a {@link TieredBlobStore} over a journal in the case's directory and no
 * Postgres — memory, then the journal's spill (D-105; the spool, its directory and its per-start key are gone). Assumed shapes:
 * {@code new TieredBlobStore(Config, Hasher, Journal journal, int tenantKeyId)} (no Postgres: {@code persist} refuses every key
 * with an IllegalStateException, a key not held locally is not found) and the package-private test seam
 * {@code new TieredBlobStore(Config, Hasher, Journal, int, long queueHighWater)}; {@code Location.SPILL} (was SPOOL). The
 * journal's spill files lie under {@code <dir>/spill}. One journal per directory (its LOCK refuses a second one). Every journal
 * a case opened is closed by {@link #closeAll()}.
 */
final class LocalStores {

    /** The durable bound of these stores: finite (CLAUDE.md); no case here makes a body durable. */
    static final long DURABLE = 1L << 20;

    private static final List<TestJournals.Opened> OPEN = new CopyOnWriteArrayList<>();

    private LocalStores() { }

    /** A journal in {@code dir} whose tenant key comes from {@code keys}, RECORD_MAX 4 KiB: every body above the memory tier is a spill. */
    static TestJournals.Opened journal(Path dir, IntFunction<SecretKey> keys) {
        return journal(dir, keys, 4096);
    }

    static TestJournals.Opened journal(Path dir, IntFunction<SecretKey> keys, long recordMax) {
        try {
            TestJournals.Opened j = TestJournals.local(dir, keys, recordMax);
            OPEN.add(j);
            return j;
        } catch (Exception e) {
            throw new IllegalStateException("fixture: the journal did not open in " + dir, e);
        }
    }

    static TieredBlobStore store(Path dir, TieredBlobStore.Config config) {
        return store(dir, config, 4096L);
    }

    /** As {@link #store(Path, TieredBlobStore.Config)} with RECORD_MAX {@code recordMax}: above a case's memory tier, so the budget, not the record bound, decides a spill. */
    static TieredBlobStore store(Path dir, TieredBlobStore.Config config, long recordMax) {
        TestJournals.Opened j = journal(dir, id -> id == TestJournals.LOCAL_TENANT ? TieredBlobStoreTest.FIXED : null, recordMax);
        return new TieredBlobStore(config, TieredBlobStoreTest.HASHER, j.journal(), j.tenant());
    }

    /** The test seam: a small high-water mark; the tenant key {@code key} (a held key holds the spill's cipher). */
    static TieredBlobStore store(Path dir, TieredBlobStore.Config config, long queueHighWater, SecretKey key) {
        TestJournals.Opened j = journal(dir, id -> id == TestJournals.LOCAL_TENANT ? key : null);
        return new TieredBlobStore(config, TieredBlobStoreTest.HASHER, j.journal(), j.tenant(), queueHighWater);
    }

    /** The journal's spill files under {@code dir}: a spilled body not yet persisted is one. */
    static List<Path> spillFiles(Path dir) {
        Path spill = dir.resolve("spill");
        if (!Files.exists(spill)) return List.of();
        try (Stream<Path> files = Files.walk(spill)) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void closeAll() {
        for (TestJournals.Opened j : OPEN) {
            try {
                j.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        OPEN.clear();
    }
}
