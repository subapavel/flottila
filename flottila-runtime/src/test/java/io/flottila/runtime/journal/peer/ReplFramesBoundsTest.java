package io.flottila.runtime.journal.peer;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CORE-5b1, the implementer's cases of {@link ReplFrames} beside T1: a payload too short for its address's length field or
 * cut inside its address is refused, and so is a REFUSE with fewer than its three bytes after the address; a REFUSE's detail
 * past 512 bytes of UTF-8 is encoded cut at a character's end (the wire's bound), never refused at the sender.
 */
class ReplFramesBoundsTest {

    static final UUID JOURNAL = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");

    @Test
    void aPayloadCutBeforeOrInsideItsAddressIsRefused() throws Exception {
        assertThrows(PeerRefusedException.class, () -> ReplFrames.Seal.decode(ByteBuffer.wrap(new byte[1])), "one byte: no owner length");
        byte[] a = ReplWire.address("node-b", JOURNAL, 7);
        assertThrows(PeerRefusedException.class, () -> ReplFrames.Seal.decode(ByteBuffer.wrap(Arrays.copyOf(a, a.length - 1))), "cut inside its n");
        assertEquals(7, ReplFrames.Seal.decode(ByteBuffer.wrap(ReplWire.seal("node-b", JOURNAL, 7, 49, 1))).address().n(), "witness: a whole SEAL");
    }

    @Test
    void aRefuseShorterThanItsReasonAndLengthIsRefused() throws Exception {
        byte[] a = ReplWire.address("node-b", JOURNAL, 7);
        for (int extra = 0; extra < 3; extra++) {
            byte[] p = Arrays.copyOf(a, a.length + extra);
            assertThrows(PeerRefusedException.class, () -> ReplFrames.Refuse.decode(ByteBuffer.wrap(p)), extra + " bytes after the address");
        }
        assertEquals("", ReplFrames.Refuse.decode(ByteBuffer.wrap(Arrays.copyOf(a, a.length + 3))).detail(), "witness: reason, length 0");
    }

    @Test
    void aDetailPast512BytesIsEncodedCutAtACharactersEnd() throws Exception {
        ReplFrames.Address at = new ReplFrames.Address("node-b", JOURNAL, 7);
        String detail = "x" + "é".repeat(300);   // 1 + 600 bytes: the 512th byte is the first half of an é
        ReplFrames.Refuse r = ReplFrames.Refuse.decode(ByteBuffer.wrap(new ReplFrames.Refuse(at, ReplFrames.FAILED, detail).encode()));
        assertEquals(detail.substring(0, 1 + 255), r.detail(), "1 + 255 whole characters: 511 bytes");
        assertEquals(511, r.detail().getBytes(StandardCharsets.UTF_8).length);
    }
}
