package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HasherTest {

    // SHA-256("") = e3b0c442 98fc1c14 9afbf4c8 996fb924 ...; the first eight bytes, big-endian, are the 64-bit hash
    static final long SHA256_OF_EMPTY_PREFIX = 0xe3b0c44298fc1c14L;
    // SHA-256("abc") = ba7816bf 8f01cfea 414140de 5dae2223 ...
    static final long SHA256_OF_ABC_PREFIX = 0xba7816bf8f01cfeaL;

    @Test
    void sha256HasherTruncatesTheDigestToItsFirstEightBytes() {
        Hasher h = Hasher.sha256();
        assertEquals("sha256-64", h.algorithm());
        assertEquals(SHA256_OF_EMPTY_PREFIX, h.hash(new byte[0]));
        assertEquals(SHA256_OF_ABC_PREFIX, h.hash("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void hashCarriesTheAlgorithmAndPrintsAsHex() {
        Hash hash = Hasher.sha256().hashOf(Buffer.buffer("abc"));
        assertEquals(new Hash("sha256-64", SHA256_OF_ABC_PREFIX), hash);
        assertEquals("ba7816bf8f01cfea", hash.hex());
        assertEquals("0000000000000001", new Hash("x", 1L).hex());
    }

    @Test
    void streamingSessionEqualsOneShotHashRegardlessOfChunking() {
        Hasher h = Hasher.sha256();
        Hasher.Session s = h.begin();
        s.update(Buffer.buffer("a"));
        s.update(Buffer.buffer(""));
        s.update(Buffer.buffer("bc"));
        assertEquals(new Hash("sha256-64", SHA256_OF_ABC_PREFIX), s.finish());
        assertNotEquals(h.hash("abc".getBytes()), h.hash("abd".getBytes()));
    }

    @Test
    void hashesOfDifferentAlgorithmsAreNeverEqual() {
        assertNotEquals(new Hash("sha256-64", 1L), new Hash("xxh3-64", 1L));
    }
}
