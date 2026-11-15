package io.flottila.testkit;

import io.flottila.api.BlobStore;

/** The fake passes the same suite as every real tier (CLAUDE.md), the durable cases included (D-061). */
class InMemoryBlobStoreContractTest extends DurableBlobStoreContract {
    @Override protected BlobStore store() { return new InMemoryBlobStore(hasher(), TREE_LIMIT); }
}
