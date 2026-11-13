package io.flottila.runtime.journal.peer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CORE-5b1 T1 (packet "Wire" as amended: SEAL = A · end u64 · crc32c u32): each repl frame's payload is the golden bytes
 * computed outside the code — {@code agent/work/CORE-5b1-frames.py}, run with {@code python3 -I}, its output in
 * {@code agent/work/CORE-5b1-frames.log} — and decodes back to its fields; a payload one byte short or long of its layout is
 * refused. DATA has no fixed length (1..4 MiB of bytes): its refusals are one byte short of its fixed fields, no bytes at all,
 * and one byte past DATA_MAX. The address's bounds are refused too: an owner of 0 or 64 bytes, n = 0.
 *
 * <p>The API assumed (CORE-5b1, test-author; the draft names the records, not their methods): package-private
 * {@code final class ReplFrames} in {@code io.flottila.runtime.journal.peer} with {@code record Address(String owner, UUID
 * journal, long n)}, {@code record Open(Address address, long length, byte[] header)}, {@code record Data(Address address,
 * long pos, byte[] bytes)}, {@code record Seal(Address address, long end, int crc32c)}, {@code record Receipt(Address address,
 * long end, int state)}, {@code record Refuse(Address address, int reason, String detail)}; each record's {@code byte[]
 * encode()} answers its frame payload (no framing: no len, type, seq or tag) and its {@code static X decode(ByteBuffer
 * payload)} reads one, a payload not of its layout a {@link PeerRefusedException}.
 */
class ReplFramesTest {

    // agent/work/CORE-5b1-frames.log
    static final String OWNER = "node-b";
    static final UUID JOURNAL = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");
    static final long N = 7;
    static final long CREATED_MS = 1767225600850L;
    static final long LENGTH = 1048576;
    static final long POS = 49;
    static final long END = 57;
    static final int FILE_CRC = 0xad255015;
    static final String DETAIL = "journal full";
    static final byte[] DATA_BYTES = "ABCDEFGH".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final byte[] HEADER = hex("464a5331010b0b0b0b00004000800000000000000b00066e6f64652d6200000000000000070000019b76daab52f3e308dc");
    static final byte[] OPEN = hex("00066e6f64652d620b0b0b0b00004000800000000000000b00000000000000070000000000100000464a5331010b0b0b0b00004000800000000000000b00066e6f64652d6200000000000000070000019b76daab52f3e308dc");
    static final byte[] DATA = hex("00066e6f64652d620b0b0b0b00004000800000000000000b000000000000000700000000000000314142434445464748");
    static final byte[] SEAL = hex("00066e6f64652d620b0b0b0b00004000800000000000000b00000000000000070000000000000039ad255015");
    static final byte[] RECEIPT = hex("00066e6f64652d620b0b0b0b00004000800000000000000b0000000000000007000000000000003902");
    static final byte[] REFUSE = hex("00066e6f64652d620b0b0b0b00004000800000000000000b000000000000000701000c6a6f75726e616c2066756c6c");

    static final ReplFrames.Address A = new ReplFrames.Address(OWNER, JOURNAL, N);

    static byte[] hex(String h) {
        return HexFormat.of().parseHex(h);
    }

    static ByteBuffer in(byte[] payload) {
        return ByteBuffer.wrap(payload);
    }

    static byte[] shorter(byte[] b) {
        return Arrays.copyOf(b, b.length - 1);
    }

    static byte[] longer(byte[] b) {
        return Arrays.copyOf(b, b.length + 1);
    }

    static void assertAddress(ReplFrames.Address a) {
        assertEquals(OWNER, a.owner(), "owner");
        assertEquals(JOURNAL, a.journal(), "journal id");
        assertEquals(N, a.n(), "n");
    }

    /** Self-test of the wire fixture every socket case encodes with: the same golden bytes. */
    @Test
    void theWireFixtureWritesTheGoldenBytes() {
        assertArrayEquals(HEADER, ReplWire.header(OWNER, JOURNAL, N, CREATED_MS), "a segment header");
        assertArrayEquals(OPEN, ReplWire.open(OWNER, JOURNAL, N, LENGTH, HEADER), "OPEN");
        assertArrayEquals(DATA, ReplWire.data(OWNER, JOURNAL, N, POS, DATA_BYTES), "DATA");
        assertArrayEquals(SEAL, ReplWire.seal(OWNER, JOURNAL, N, END, FILE_CRC), "SEAL");
        assertEquals(FILE_CRC, ReplWire.crc32c(concat(HEADER, DATA_BYTES)), "SEAL's crc32c is of the file's bytes [0, end)");
        assertArrayEquals(RECEIPT, ReplWire.receipt(OWNER, JOURNAL, N, END, ReplWire.WRITTEN), "RECEIPT");
        assertArrayEquals(REFUSE, ReplWire.refuse(OWNER, JOURNAL, N, ReplWire.FULL, DETAIL), "REFUSE");
    }

    @Test
    void openIsItsGoldenBytesAndDecodesBack() throws Exception {
        assertArrayEquals(OPEN, new ReplFrames.Open(A, LENGTH, HEADER).encode(), "OPEN = A · length u64 · header");
        ReplFrames.Open o = ReplFrames.Open.decode(in(OPEN));
        assertAddress(o.address());
        assertEquals(LENGTH, o.length());
        assertArrayEquals(HEADER, o.header());
    }

    @Test
    void dataIsItsGoldenBytesAndDecodesBack() throws Exception {
        assertArrayEquals(DATA, new ReplFrames.Data(A, POS, DATA_BYTES).encode(), "DATA = A · pos u64 · bytes");
        ReplFrames.Data d = ReplFrames.Data.decode(in(DATA));
        assertAddress(d.address());
        assertEquals(POS, d.pos());
        assertArrayEquals(DATA_BYTES, d.bytes());
    }

    @Test
    void sealIsItsGoldenBytesWithTheFilesCrc32cAndDecodesBack() throws Exception {
        assertArrayEquals(SEAL, new ReplFrames.Seal(A, END, FILE_CRC).encode(), "SEAL = A · end u64 · crc32c u32");
        ReplFrames.Seal s = ReplFrames.Seal.decode(in(SEAL));
        assertAddress(s.address());
        assertEquals(END, s.end());
        assertEquals(FILE_CRC, s.crc32c());
    }

    @Test
    void receiptIsItsGoldenBytesAndDecodesBack() throws Exception {
        assertArrayEquals(RECEIPT, new ReplFrames.Receipt(A, END, 2).encode(), "RECEIPT = A · end u64 · state u8");
        ReplFrames.Receipt r = ReplFrames.Receipt.decode(in(RECEIPT));
        assertAddress(r.address());
        assertEquals(END, r.end());
        assertEquals(2, r.state(), "WRITTEN");
    }

    @Test
    void refuseIsItsGoldenBytesAndDecodesBack() throws Exception {
        assertArrayEquals(REFUSE, new ReplFrames.Refuse(A, 1, DETAIL).encode(), "REFUSE = A · reason u8 · u16 len · detail");
        ReplFrames.Refuse r = ReplFrames.Refuse.decode(in(REFUSE));
        assertAddress(r.address());
        assertEquals(1, r.reason(), "FULL");
        assertEquals(DETAIL, r.detail());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"OPEN", "SEAL", "RECEIPT", "REFUSE"})
    void aFixedLayoutOneByteShortOrLongIsRefused(String frame) throws Exception {
        byte[] golden = switch (frame) {
            case "OPEN" -> OPEN;
            case "SEAL" -> SEAL;
            case "RECEIPT" -> RECEIPT;
            default -> REFUSE;
        };
        assertRefused(frame, shorter(golden), "one byte short");
        assertRefused(frame, longer(golden), "one byte long");
        decode(frame, golden);   // witness: the golden payload itself is taken
    }

    @Test
    void dataShortOfItsFixedFieldsEmptyOrPastDataMaxIsRefused() throws Exception {
        byte[] fixed = Arrays.copyOf(DATA, DATA.length - DATA_BYTES.length);   // A · pos, no bytes
        assertRefused("DATA", shorter(fixed), "one byte short of its fixed fields");
        assertRefused("DATA", fixed, "no bytes (empty DATA)");
        byte[] max = ReplWire.data(OWNER, JOURNAL, N, POS, new byte[ReplWire.DATA_MAX]);
        assertEquals(ReplWire.DATA_MAX, ReplFrames.Data.decode(in(max)).bytes().length, "witness: exactly DATA_MAX (4 MiB) is taken");
        assertRefused("DATA", longer(max), "one byte past DATA_MAX");
        assertEquals(1, ReplFrames.Data.decode(in(ReplWire.data(OWNER, JOURNAL, N, POS, new byte[1]))).bytes().length, "witness: one byte is taken");
    }

    @Test
    void anAddressOutsideItsBoundsIsRefused() throws Exception {
        assertRefused("SEAL", ReplWire.seal("", JOURNAL, N, END, FILE_CRC), "an owner of 0 bytes");
        assertRefused("SEAL", ReplWire.seal("o".repeat(64), JOURNAL, N, END, FILE_CRC), "an owner of 64 bytes");
        assertEquals("o".repeat(63), ReplFrames.Seal.decode(in(ReplWire.seal("o".repeat(63), JOURNAL, N, END, FILE_CRC))).address().owner(),
                "witness: an owner of 63 bytes is taken");
        assertRefused("SEAL", ReplWire.seal(OWNER, JOURNAL, 0, END, FILE_CRC), "n = 0");
        assertEquals(1, ReplFrames.Seal.decode(in(ReplWire.seal(OWNER, JOURNAL, 1, END, FILE_CRC))).address().n(), "witness: n = 1 is taken");
    }

    @Test
    void aRefuseDetailAbove512BytesIsRefused() throws Exception {
        assertEquals(512, ReplFrames.Refuse.decode(in(ReplWire.refuse(OWNER, JOURNAL, N, 2, "d".repeat(512)))).detail().length(),
                "witness: a detail of 512 bytes is taken");
        assertRefused("REFUSE", ReplWire.refuse(OWNER, JOURNAL, N, 2, "d".repeat(513)), "a detail of 513 bytes");
    }

    static void assertRefused(String frame, byte[] payload, String what) {
        Executable decode = () -> decode(frame, payload);
        assertThrows(PeerRefusedException.class, decode, frame + ": " + what + " (" + payload.length + " bytes)");
    }

    static Object decode(String frame, byte[] payload) throws PeerRefusedException {
        return switch (frame) {
            case "OPEN" -> ReplFrames.Open.decode(in(payload));
            case "DATA" -> ReplFrames.Data.decode(in(payload));
            case "SEAL" -> ReplFrames.Seal.decode(in(payload));
            case "RECEIPT" -> ReplFrames.Receipt.decode(in(payload));
            default -> ReplFrames.Refuse.decode(in(payload));
        };
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
