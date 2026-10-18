package io.flottila.runtime.blob;

import io.flottila.api.BlobStore;
import io.flottila.testkit.BlobStoreContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The tiered store with everything in memory (tier and budget above every body in the suite). */
class MemoryTierContractTest extends BlobStoreContract {
    @TempDir Path spool;

    @Override protected BlobStore store() {
        return new TieredBlobStore(new TieredBlobStore.Config(TREE_LIMIT, 8L << 20, 64L << 20, spool, TieredBlobStoreTest.key(), null, false), hasher());
    }
}
