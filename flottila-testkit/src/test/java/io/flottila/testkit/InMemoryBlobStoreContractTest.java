package io.flottila.testkit;

import io.flottila.api.BlobStore;

/** The fake passes the same suite as every real tier (CLAUDE.md). */
class InMemoryBlobStoreContractTest extends BlobStoreContract {
    @Override protected BlobStore store() { return new InMemoryBlobStore(hasher(), TREE_LIMIT); }
}
