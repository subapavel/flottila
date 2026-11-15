package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.ReplWire;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1 T13 (packet "Holder disk", start, as amended by C3): a holder's {@code Journal.open} finds the replicas an earlier
 * run left under {@code replicas/<owner>/<journal id>/} and counts them in {@link Journal#bytes()} and in its start room check
 * (usable space + the journal's bytes ≥ FLOTTILA_JOURNAL_MAX_BYTES); each {@code .part} is checked against its header — one no
 * longer than its header with a bad header is unlinked with a WARNING, a longer one with a bad header is kept, an ERROR, and
 * never served (an OPEN of its n is refused MISMATCH); a {@code .part} with a valid trailer is renamed {@code .seg}; any other
 * is CRC-scanned and cut after its last whole record, staying {@code .part} (never sealed); a {@code .seg} is counted as found.
 * The replicas are node-b's real sealed segments 1..5 (two records each), cut, broken or copied whole.
 *
 * <p>The API assumed (CORE-5b1, test-author): {@code Journal.replicas()} → {@code Replicas}, {@code Replica open(String owner,
 * UUID journal, long n, long length, byte[] header)} throwing {@code ReplicaMismatch} (journal package) when n's file is not
 * exactly this header.
 */
@Timeout(120)
class ReplicaStartTest {

    @TempDir Path tmp;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    final List<Journal> journals = new ArrayList<>();
    /** node-b's sealed segments 1..5, as its journal wrote them, and the refs of their records ([n][i]). */
    final byte[][] sealed = new byte[6][];
    final JournalRef[][] refs = new JournalRef[6][2];
    UUID journalB;
    Path holderDir;
    Path replicas;
    JournalFixture.LogCapture logs;

    @BeforeEach
    void ownerSegments() throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-b"))).segmentRecords(2);
        s.node = "node-b";
        JournalConfig config = s.config();
        Journal b = OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
        OnVirtualThread.call(() -> {
            for (int i = 0; i < 10; i++) {
                Journal.Appended a = b.append(JournalFixture.record("t13-" + i, 1000 + i), JournalFixture.live());
                JournalFixture.await(a.forced());
                refs[(int) a.ref().segment()][i % 2] = a.ref();
            }
            return null;
        });
        journalB = b.id();
        b.close();
        for (int n = 1; n <= 5; n++) {
            assertEquals(n, refs[n][0].segment(), "fixture: node-b's records 2n-2, 2n-1 lie in segment n");
            sealed[n] = Files.readAllBytes(tmp.resolve("node-b").resolve("segments").resolve(n + ".seg"));
        }
        holderDir = Files.createDirectories(tmp.resolve("node-a"));
        replicas = Files.createDirectories(holderDir.resolve("replicas").resolve("node-b").resolve(journalB.toString()));
        logs = new JournalFixture.LogCapture();
    }

    @AfterEach
    void close() throws Exception {
        for (Journal j : journals) j.close();
        logs.close();
    }

    Journal holder(JournalFiles files, long maxBytes) throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(holderDir).maxBytes(maxBytes);
        s.node = "node-a";
        JournalConfig config = s.config();
        Journal j = OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), files));
        journals.add(j);
        return j;
    }

    static int headerLength() {
        return 43 + "node-b".length();
    }

    /** Segment 1 cut in the middle of its second record: its first record whole. */
    byte[] torn() {
        JournalRef second = refs[1][1];
        return Arrays.copyOf(sealed[1], (int) (second.position() + second.length() / 2));
    }

    @Test
    void aStartCountsTheReplicasAndChecksEachPartAgainstItsHeader() throws Exception {
        Files.write(replicas.resolve("1.part"), torn());
        Files.write(replicas.resolve("2.part"), ReplWire.flipped(Arrays.copyOf(sealed[2], headerLength()), headerLength() - 6));
        byte[] badLonger = ReplWire.flipped(sealed[3], headerLength() - 6);
        Files.write(replicas.resolve("3.part"), badLonger);
        Files.write(replicas.resolve("4.part"), sealed[4]);
        Files.write(replicas.resolve("5.seg"), sealed[5]);

        Journal a = holder(JournalFiles.real(), JournalConfig.MAX_BYTES);

        JournalRef first = refs[1][0];
        assertArrayEquals(Arrays.copyOf(sealed[1], (int) (first.position() + first.length())), Files.readAllBytes(replicas.resolve("1.part")),
                "a torn .part is cut after its last whole record");
        assertFalse(Files.exists(replicas.resolve("1.seg")), "and stays .part: never sealed");

        assertFalse(Files.exists(replicas.resolve("2.part")), "a .part no longer than its header with a bad header is unlinked");
        assertTrue(logged(Level.WARNING, "2.part"), "with a WARNING naming it: " + logs.lines());

        assertArrayEquals(badLonger, Files.readAllBytes(replicas.resolve("3.part")), "a longer .part with a bad header is kept as it is");
        assertTrue(logged(Level.SEVERE, "3.part"), "with an ERROR naming it: " + logs.lines());
        byte[] valid3 = Arrays.copyOf(sealed[3], headerLength());
        assertThrows(ReplicaMismatch.class, () -> OnVirtualThread.call(() -> a.replicas().open("node-b", journalB, 3, sealed[3].length, valid3)),
                "never served: an OPEN of its n is refused");
        assertArrayEquals(badLonger, Files.readAllBytes(replicas.resolve("3.part")), "the refused OPEN leaves it as it is");

        assertArrayEquals(sealed[4], Files.readAllBytes(replicas.resolve("4.seg")), "a .part with a valid trailer is renamed .seg, its bytes as they were");
        assertFalse(Files.exists(replicas.resolve("4.part")));
        assertArrayEquals(sealed[5], Files.readAllBytes(replicas.resolve("5.seg")), "a .seg is found as it is");

        assertEquals(ReplicaWorld.sizeOf(holderDir.resolve("segments")) + ReplicaWorld.sizeOf(holderDir.resolve("replicas")), a.bytes(),
                "the journal's bytes count its segments and every replica file on disk");
    }

    @Test
    void theStartRoomCheckCountsTheReplicas() throws Exception {
        Files.write(replicas.resolve("1.part"), torn());
        Files.write(replicas.resolve("5.seg"), sealed[5]);
        long held = ReplicaWorld.sizeOf(holderDir.resolve("replicas"));
        long max = 64L << 20;
        JournalFreeSpaceTest.SpaceFiles files = new JournalFreeSpaceTest.SpaceFiles();

        files.usable = max - held - 1;
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> holder(files, max),
                "usable space + the replicas' bytes = MAX_BYTES - 1: refused");
        assertTrue(String.valueOf(refused.getMessage()).contains("FLOTTILA_JOURNAL_MAX_BYTES"), refused.getMessage());

        files.usable = max - held;
        Journal a = holder(files, max);
        // the start cuts the torn 1.part after its last whole record, so the bytes after the start are those on disk then,
        // not the sizes the room check saw before the cut (pinned just above)
        long segments = ReplicaWorld.sizeOf(holderDir.resolve("segments"));
        assertEquals(segments + ReplicaWorld.sizeOf(holderDir.resolve("replicas")), a.bytes(),
                "usable space + the replicas' bytes = MAX_BYTES starts the journal; its bytes are its segments and replicas on disk");
        assertTrue(a.bytes() > segments, "the replicas are counted, not only the holder's own segments: " + a.bytes() + " vs " + segments);
    }

    boolean logged(Level level, String needle) {
        SimpleFormatter f = new SimpleFormatter();
        for (LogRecord r : logs.records()) {
            if (r.getLevel().equals(level) && f.formatMessage(r).contains(needle)) return true;
        }
        return false;
    }
}
