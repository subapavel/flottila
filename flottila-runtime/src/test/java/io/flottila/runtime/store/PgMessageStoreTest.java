package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The store's column encodings: a blob key as text and back, a hash as its eight big-endian bytes. */
class PgMessageStoreTest {

    @Test
    void blobKeysRoundTripThroughTextAndABadTextIsRefused() {
        BlobKey key = BlobKey.attachment("m-1", 4, "split-0");
        assertEquals("m-1/4/split-0", PgMessageStore.text(key));
        assertEquals(key, PgMessageStore.blobKey("m-1/4/split-0"));
        assertEquals(BlobKey.body("m-1", 0), PgMessageStore.blobKey("m-1/0/body"));
        assertThrows(IllegalArgumentException.class, () -> PgMessageStore.blobKey("m-1"));
        assertThrows(IllegalArgumentException.class, () -> PgMessageStore.blobKey("/0/body"));
        assertThrows(IllegalArgumentException.class, () -> PgMessageStore.blobKey("m-1/0"));
        assertThrows(NumberFormatException.class, () -> PgMessageStore.blobKey("m-1/x/body"));
    }

    @Test
    void aHashIsItsEightBigEndianBytes() {
        assertArrayEquals(new byte[] {0, 0, 0, 0, 0, 0, 0, 1}, PgMessageStore.bytes(new Hash("sha256-64", 1L)).getBytes());
        assertArrayEquals(new byte[] {-1, -1, -1, -1, -1, -1, -1, -1}, PgMessageStore.bytes(new Hash("sha256-64", -1L)).getBytes());
        assertEquals(42L, PgMessageStore.bytes(new Hash("sha256-64", 42L)).getLong(0));
    }
}
