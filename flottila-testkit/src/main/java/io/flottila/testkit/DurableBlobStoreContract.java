package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of a {@link BlobStore} with a durable tier (D-061): everything {@link BlobStoreContract} asks, plus
 * {@link BlobStore#persist(BlobKey)} — it completes once the bytes outlive the process, a persisted body reads back intact
 * by every path, and a key with nothing stored is refused with an {@link IllegalArgumentException} as a failed future. A key's
 * durable bytes never change (D-061 amended by FIX-35): persisting the same bytes again is quiet; persisting other bytes under
 * a durable key fails with an {@link IllegalStateException}, makes nothing durable and leaves the body as written; another
 * step or part is another key, for a conflict and for a match alike. The in-memory testkit store and every runtime store with
 * a durable tier run these cases. The bodies here stay below {@link #TREE_LIMIT} and below any durable bound a subclass
 * configures (1 KiB is enough).
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

    /** The refusal's text (FIX-35): it names the key and the size of the bytes that were refused. */
    protected static String otherBytesRefused(BlobKey key, long size) {
        return "the durable body under " + key + " holds other bytes than these " + size
                + ": a key's durable bytes never change (D-061, FIX-35); nothing was persisted and the body stays local";
    }

    /**
     * A key written again with other bytes after its persist — a rerun of a step that is not deterministic — is not persisted:
     * the persist fails with an {@link IllegalStateException} naming the key, the key reads what was written last, and the
     * durable bytes are still the first — persisting the other bytes is refused again, the first bytes written again persist
     * quietly. Other bytes of the same size (the last byte changed) and of another size (one byte short) alike.
     */
    @Test
    public void persistingOtherBytesUnderADurableKeyFailsIllegalStateAndKeepsTheFirst() {
        BlobStore s = store();
        Buffer first = invoice();
        Buffer lastByteChanged = first.copy();
        lastByteChanged.setByte(first.length() - 1, (byte) (first.getByte(first.length() - 1) ^ 1));
        Buffer oneByteShort = first.getBuffer(0, first.length() - 1);
        int n = 0;
        for (Buffer other : List.of(lastByteChanged, oneByteShort)) {
            String variant = other.length() == first.length() ? "same size, last byte changed" : "one byte short";
            BlobKey key = key("p-other-" + n++, 1);
            await(s.write(key, first, TREE_LIMIT));
            await(s.persist(key));
            await(s.write(key, other, TREE_LIMIT));   // a rerun writes the key again, with other bytes
            assertEquals(other, await(s.read(key)), "fixture: the key reads the other bytes before the persist (" + variant + ")");

            Future<Void> refused = awaitOutcome(s.persist(key));
            assertTrue(refused.failed(), "other bytes under a durable key are not persisted (" + variant + ")");
            IllegalStateException e = assertInstanceOf(IllegalStateException.class, refused.cause(), variant + ": " + refused.cause());
            assertEquals(otherBytesRefused(key, other.length()), e.getMessage(), variant);
            assertEquals(other, await(s.read(key)), "the body stays as written: the key reads the other bytes (" + variant + ")");
            assertEquals(other, await(collect(s.open(key))), "streamed too (" + variant + ")");

            Future<Void> again = awaitOutcome(s.persist(key));
            assertTrue(again.failed(), "the durable bytes are still the first: the other bytes are refused again (" + variant + ")");
            assertInstanceOf(IllegalStateException.class, again.cause(), variant + ": " + again.cause());
            await(s.write(key, first, TREE_LIMIT));
            Future<Void> firstAgain = awaitOutcome(s.persist(key));
            assertTrue(firstAgain.succeeded(), "and the first bytes written again persist quietly (" + variant + "): " + firstAgain.cause());
            assertEquals(first, await(s.read(key)), variant);
            assertEquals(first, await(collect(s.open(key))), variant);
        }
    }

    /** A key written again with the same bytes after its persist — a rerun of a deterministic step — persists quietly. */
    @Test
    public void persistingTheSameBytesWrittenAgainIsQuiet() {
        BlobStore s = store();
        Buffer bytes = invoice();
        BlobKey key = key("p-same", 2);
        await(s.write(key, bytes, TREE_LIMIT));
        await(s.persist(key));
        await(s.write(key, bytes.copy(), TREE_LIMIT));   // a rerun writes the key again: equal bytes, another buffer
        Future<Void> again = awaitOutcome(s.persist(key));
        assertTrue(again.succeeded(), "the same bytes again: idempotent and quiet: " + again.cause());
        assertEquals(bytes, await(s.read(key)), "the key reads the bytes");
        assertEquals(bytes, await(collect(s.open(key))), "streamed too");
        assertTrue(awaitOutcome(s.persist(key)).succeeded(), "and once more");
    }

    /**
     * Another step, another part or another message is another key: other bytes there are no conflict with a durable key —
     * each persists and reads its own bytes — and no match for it either: the durable key written again with the bytes its
     * siblings hold durably is still refused, its own row is the one compared.
     */
    @Test
    public void otherBytesUnderAnotherStepOrPartAreNoConflict() {
        BlobStore s = store();
        Buffer first = invoice();
        Buffer other = first.copy();
        other.setByte(0, (byte) (first.getByte(0) ^ 1));   // same size: only the bytes differ
        BlobKey key = key("p-sib", 1);
        List<BlobKey> siblings = List.of(key("p-sib", 2), BlobKey.attachment("p-sib", 1, "invoice.pdf"), key("p-sib-other", 1));
        await(s.write(key, first, TREE_LIMIT));
        await(s.persist(key));
        for (BlobKey sibling : siblings) {
            await(s.write(sibling, other, TREE_LIMIT));
            Future<Void> persisted = awaitOutcome(s.persist(sibling));
            assertTrue(persisted.succeeded(), "other bytes under " + sibling + " are no conflict with " + key + ": " + persisted.cause());
        }
        for (BlobKey sibling : siblings) assertEquals(other, await(s.read(sibling)), "each sibling reads its own bytes: " + sibling);
        assertEquals(first, await(s.read(key)), "the durable key reads its own bytes");

        await(s.write(key, other, TREE_LIMIT));   // the bytes every sibling holds durably
        Future<Void> refused = awaitOutcome(s.persist(key));
        assertTrue(refused.failed(), "a sibling's durable bytes are no match: " + key + " holds the first bytes");
        IllegalStateException e = assertInstanceOf(IllegalStateException.class, refused.cause(), String.valueOf(refused.cause()));
        assertEquals(otherBytesRefused(key, other.length()), e.getMessage());
        assertEquals(other, await(s.read(key)), "the body stays as written");
        for (BlobKey sibling : siblings) assertEquals(other, await(s.read(sibling)), "the siblings unchanged: " + sibling);
    }
}
