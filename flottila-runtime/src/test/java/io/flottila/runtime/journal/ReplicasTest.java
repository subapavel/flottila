package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.ReplWire;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1, the implementer's cases of {@link Replicas} beside T13: an OPEN through the API whose header is not the owner's
 * segment header (too short, another n) is refused before anything is reserved or created; a start leaves every entry under
 * {@code replicas/} that is not {@code <owner>/<journal id>/<n>.part|.seg} alone and out of the journal's bytes; a {@code .part}
 * shorter than its header is unlinked.
 */
@Timeout(60)
class ReplicasTest {

    static final UUID JOURNAL = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");

    @TempDir Path tmp;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (Journal j : journals) j.close();
    }

    Journal holder(Path dir) throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(dir);
        s.node = "node-a";
        JournalConfig config = s.config();
        Journal j = OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
        journals.add(j);
        return j;
    }

    @Test
    void anOpenWhoseHeaderIsNotTheSegmentsIsRefusedWithNothingReservedOrCreated() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("node-a"));
        Journal a = holder(dir);
        long before = a.bytes();
        byte[] header = ReplWire.header("node-b", JOURNAL, 1, JournalFixture.START.toEpochMilli());
        assertThrows(IllegalArgumentException.class, () -> a.replicas().open("node-b", JOURNAL, 1, 4096, Arrays.copyOf(header, 10)), "a short header");
        assertThrows(IllegalArgumentException.class, () -> a.replicas().open("node-b", JOURNAL, 2, 4096, header), "segment 1's header for segment 2");
        assertThrows(IllegalArgumentException.class, () -> a.replicas().open("node-c", JOURNAL, 1, 4096, header), "node-b's header for node-c");
        assertEquals(before, a.bytes(), "nothing reserved");
        assertFalse(Files.exists(dir.resolve("replicas")), "nothing created");
        Replica r = OnVirtualThread.call(() -> a.replicas().open("node-b", JOURNAL, 1, 4096, header));
        assertEquals(header.length, r.end(), "witness: the same OPEN with its own header opens");
        assertEquals(before + 4096, a.bytes(), "and reserves its length");
    }

    /**
     * CORE-5b1 r1: Replicas.open takes its owner as a path element, so an owner that is not a DNS label (NodeConfig's node id)
     * is refused there, its own header and all, before anything is reserved or created anywhere under the node's directory.
     */
    @ParameterizedTest(name = "owner {0}")
    @ValueSource(strings = {"..", "a/b"})
    void anOpenOfAnOwnerThatIsNoDnsLabelIsRefusedWithNothingReservedOrCreated(String owner) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("node-a"));
        Journal a = holder(dir);
        JournalFixture.until(() -> a.holds(2), () -> "the pre-open of segment 2 made its file");
        long before = a.bytes();
        List<Path> tree = tree(tmp);
        byte[] header = ReplWire.header(owner, JOURNAL, 1, JournalFixture.START.toEpochMilli());   // that owner's own header
        assertThrows(IllegalArgumentException.class, () -> OnVirtualThread.call(() -> a.replicas().open(owner, JOURNAL, 1, 4096, header)),
                "an owner " + owner + " is no DNS label");
        assertEquals(before, a.bytes(), "nothing reserved");
        assertEquals(tree, tree(tmp), "nothing created, under replicas/ or beside it");
        byte[] own = ReplWire.header("node-b", JOURNAL, 1, JournalFixture.START.toEpochMilli());
        Replica r = OnVirtualThread.call(() -> a.replicas().open("node-b", JOURNAL, 1, 4096, own));
        assertEquals(own.length, r.end(), "witness: the same OPEN of a DNS-label owner opens");
    }

    /** Every path under {@code root}, sorted. */
    static List<Path> tree(Path root) throws Exception {
        try (Stream<Path> all = Files.walk(root)) {
            return all.sorted().toList();
        }
    }

    @Test
    void aStartLeavesWhatIsNoReplicaAloneAndUncountedAndUnlinksAPartShorterThanItsHeader() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("node-a"));
        Path replicas = Files.createDirectories(dir.resolve("replicas"));
        Path journal = Files.createDirectories(replicas.resolve("node-b").resolve(JOURNAL.toString()));
        byte[] header = ReplWire.header("node-b", JOURNAL, 3, JournalFixture.START.toEpochMilli());
        Files.write(journal.resolve("3.part"), header);                                   // a replica: counted, kept
        Files.write(journal.resolve("4.part"), Arrays.copyOf(header, 10));                // shorter than its header: unlinked
        Files.write(journal.resolve("notes.txt"), new byte[100]);                         // no replica's name
        Files.createDirectories(journal.resolve("5.seg"));                                // a directory of a replica's name
        Files.write(replicas.resolve("stray"), new byte[200]);                            // a file where an owner's directory goes
        Files.write(replicas.resolve("node-b").resolve(UUID.randomUUID().toString()), new byte[300]);   // a file where a journal's goes
        Path other = Files.createDirectories(replicas.resolve("node-b").resolve("not-a-journal-id"));
        Files.write(other.resolve("1.part"), new byte[400]);

        Journal a = holder(dir);
        JournalFixture.until(() -> a.holds(2), () -> "the pre-open of segment 2 made its file");

        assertArrayEquals(header, Files.readAllBytes(journal.resolve("3.part")), "a header-only .part of a sound header stays");
        assertFalse(Files.exists(journal.resolve("4.part")), "a .part shorter than its header is unlinked");
        for (Path p : List.of(journal.resolve("notes.txt"), journal.resolve("5.seg"), replicas.resolve("stray"), other.resolve("1.part"))) {
            assertTrue(Files.exists(p), "left alone: " + p);
        }
        assertEquals(ReplicaWorld.sizeOf(dir.resolve("segments")) + header.length, a.bytes(), "only the replica is counted beside the segments");
    }
}
