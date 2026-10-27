package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of a {@link BlobStore} with a durable tier (D-061): everything {@link BlobStoreContract} asks, plus
 * {@link BlobStore#persist(BlobKey)} — it completes once the bytes outlive the process, a persisted body reads back intact
 * by every path, persisting again is quiet, and a key with nothing stored is refused with an {@link IllegalArgumentException}
 * as a failed future. The in-memory testkit store and every runtime store with a durable tier run these cases. The bodies
 * here stay below {@link #TREE_LIMIT} and below any durable bound a subclass configures (1 KiB is enough).
 *
 * @since 0.1
 */
public abstract class DurableBlobStoreContract extends BlobStoreContract {

    /** 1000 bytes, not one repeated byte: a reordered or truncated read back shows. */
    protected static Buffer invoice() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; s.length() < 1000; i++) s.append("<line n='").append(i).append("'/>");
        return Buffer.buffer(s.substring(0, 1000));
    }

    @Test
    public void aPersistedBodyReadsBackIntactByOpenReadAndWhole() {
        BlobStore s = store();
        Buffer bytes = invoice();
        BlobKey key = key("p-1", 0);
        Body body = await(s.write(key, bytes, TREE_LIMIT));
        await(s.persist(key));
        assertEquals(bytes, await(s.read(key)), "read after persist: the same bytes");
        assertEquals(bytes, await(collect(s.open(key))), "open after persist: the same bytes, from the start");
        assertEquals(bytes, await(collect(s.open(key))), "and again: a fresh cold stream every time");
        assertEquals(bytes, body.whole(), "the body the write returned reads whole through its store");
        assertEquals(bytes, Body.of(s, key, body.hash(), body.size()).whole(), "a body rebuilt from its key and hash (the worker's view) reads the same");
        assertEquals(body.hash(), await(hashOf(s.open(key))), "byte for byte: the hash the write computed");
    }

    @Test
    public void persistingTwiceSucceeds() {
        BlobStore s = store();
        Buffer bytes = invoice();
        BlobKey key = key("p-2", 3);
        await(s.write(key, bytes, TREE_LIMIT));
        await(s.persist(key));
        await(s.persist(key));
        assertEquals(bytes, await(s.read(key)), "a second persist changes nothing");
    }

    @Test
    public void persistingAnUnknownKeyFailsIllegalArgument() {
        BlobStore s = store();
        await(s.write(key("p-3", 0), Buffer.buffer("stored"), TREE_LIMIT));
        Future<Void> unknown = awaitOutcome(s.persist(key("p-3", 1)));
        assertTrue(unknown.failed(), "nothing is stored under p-3 step 1: nothing can be made durable");
        assertInstanceOf(IllegalArgumentException.class, unknown.cause(), "an unknown key is the caller's error: " + unknown.cause());
        Future<Void> never = awaitOutcome(s.persist(key("never-written", 0)));
        assertTrue(never.failed());
        assertInstanceOf(IllegalArgumentException.class, never.cause(), String.valueOf(never.cause()));
    }
}
