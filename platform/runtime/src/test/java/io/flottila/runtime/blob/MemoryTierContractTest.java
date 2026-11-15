package io.flottila.runtime.blob;

import io.flottila.api.BlobStore;
import io.flottila.testkit.BlobStoreContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The tiered store with everything in memory (tier and budget above every body in the suite). CORE-4c2: over a journal in the
 * case's directory ({@link LocalStores}; the spool and its key are gone), which no body here reaches.
 */
class MemoryTierContractTest extends BlobStoreContract {
    @TempDir Path dir;

    @Override protected BlobStore store() {
        return LocalStores.store(dir, new TieredBlobStore.Config(TREE_LIMIT, 8L << 20, 64L << 20, LocalStores.DURABLE));
    }

    @AfterEach
    void closeTheJournals() {
        LocalStores.closeAll();
    }
}
