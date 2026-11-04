package io.flottila.runtime.node;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Hasher;
import io.flottila.testkit.BlobStoreContract;
import io.flottila.testkit.ChunkedReadStream;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c2: the node's components are built before its journal opens, so they get a blob store that is bound to the node's
 * store later. Before the bind it answers its tree limit and refuses every body call; after it, every call is the node's store's.
 */
class LateBlobStoreTest {

    private static final BlobKey KEY = BlobKey.body("m-late", 0);

    @Test
    void beforeTheBindOnlyTheTreeLimitIsAnswered() {
        LateBlobStore late = new LateBlobStore(4096);
        assertEquals(4096, late.treeLimit(), "the limit a component may read while it is built");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> late.write(KEY, Buffer.buffer("x"), 10));
        assertTrue(refused.getMessage().contains("journal"), "the refusal says why: " + refused.getMessage());
        assertThrows(IllegalStateException.class, () -> late.write(KEY, new ChunkedReadStream(List.of("x")), 10));
        assertThrows(IllegalStateException.class, () -> late.open(KEY));
        assertThrows(IllegalStateException.class, () -> late.read(KEY));
        assertThrows(IllegalStateException.class, () -> late.exists(KEY));
        assertThrows(IllegalStateException.class, () -> late.persist(KEY));
    }

    @Test
    void afterTheBindEveryCallIsTheNodeStores() {
        InMemoryBlobStore node = new InMemoryBlobStore(Hasher.sha256(), 1 << 20);
        LateBlobStore late = new LateBlobStore(4096);
        late.bind(node);

        Body whole = BlobStoreContract.await(late.write(KEY, Buffer.buffer("whole"), 100));
        assertSame(node, whole.store(), "the body names the node's store, not the late one");
        assertTrue(node.contains(KEY), "written there");
        assertEquals("whole", BlobStoreContract.await(late.read(KEY)).toString());
        assertEquals(Boolean.TRUE, BlobStoreContract.await(late.exists(KEY)));
        assertEquals(BlobStoreContract.awaitOutcome(node.persist(KEY)).succeeded(), BlobStoreContract.awaitOutcome(late.persist(KEY)).succeeded(),
                "persist answers as the node's store does");
        BlobKey streamed = BlobKey.body("m-late-streamed", 0);
        Body body = BlobStoreContract.await(late.write(streamed, new ChunkedReadStream(List.of("a", "b")), 100));
        assertSame(node, body.store());
        assertEquals("ab", BlobStoreContract.await(node.read(streamed)).toString(), "streamed into the node's store");
        assertEquals("ab", BlobStoreContract.await(BlobStoreContract.collect(late.open(streamed))).toString(), "and opened from there");
        assertEquals(4096, late.treeLimit(), "the limit stays the node's configured one");
    }
}
