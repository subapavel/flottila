package io.flottila.runtime.journal;

import io.flottila.runtime.journal.HeldFiles.Hold;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A start over segment rows that are kept (CORE-4c1, CORE-3 item 9; the implementer's), in the cases a node's own segments
 * cannot show (they hold no record yet, and a segment without one goes at recovery anyway): a segment file that holds a
 * forced record but has no row is unlinked and none of it is read, while a segment that holds records keeps its row; a row
 * whose {@code NODE} file is gone and a {@code NODE} of another journal are each refused naming what was found, and leave
 * the rows and the directory as they were; a first start whose {@code NODE} cannot be written leaves no file of it and
 * registers nothing, and the next start begins the journal; a {@code NODE} without its row is registered only while no
 * segment file is there, and refused with nothing changed otherwise (CORE-4c1 r1).
 */
class JournalKeptRowsTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();
    private final List<HeldFiles> seams = new ArrayList<>();

    /** The rows of one node kept in memory as Postgres keeps them: the double at the SPI boundary. */
    static final class KeptRows implements SegmentRows {
        final Map<String, UUID> nodes = new ConcurrentHashMap<>();
        final Set<Long> segments = ConcurrentHashMap.newKeySet();

        @Override public Future<Void> insert(String node, long segment) {
            segments.add(segment);
            return Future.succeededFuture();
        }

        @Override public Future<UUID> journalOf(String node) { return Future.succeededFuture(nodes.get(node)); }

        @Override public Future<Void> register(String node, UUID journal) {
            nodes.put(node, journal);
            return Future.succeededFuture();
        }

        @Override public Future<Set<Long>> segments(String node) { return Future.succeededFuture(Set.copyOf(segments)); }

        @Override public Future<Void> drop(String node, Set<Long> dropped) {
            segments.removeAll(dropped);
            return Future.succeededFuture();
        }
    }

    @AfterEach
    void closeEverything() throws Exception {
        seams.forEach(HeldFiles::releaseAll);
        for (Journal j : journals) j.close();
    }

    private Settings in(String name) throws IOException {
        return settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1)).segmentRecords(1);
    }

    private Journal open(Settings s, SegmentRows rows) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real(), rows);
        journals.add(j);
        return j;
    }

    private void close(Journal j) throws IOException {
        journals.remove(j);
        j.close();
    }

    private static JournalRef appendForced(Journal j, String id) throws Exception {
        Journal.Appended a = j.append(record(id, 100), live());
        await(a.forced());
        return a.ref();
    }

    @Test
    void aSegmentWithRecordsButNoRowIsUnlinkedUnreadAndOneWithItsRowKeepsIt() throws Exception {
        KeptRows rows = new KeptRows();
        Settings s = in("rows");
        Journal first = open(s, rows);
        JournalRef kept = appendForced(first, "kept");
        JournalRef lost = appendForced(first, "lost");
        close(first);
        assertNotEquals(kept.segment(), lost.segment(), "fixture: one record per segment");
        Path lostFile = s.dir.resolve("segments").resolve(lost.segment() + ".seg");
        assertTrue(Files.isRegularFile(lostFile) && rows.segments.containsAll(Set.of(kept.segment(), lost.segment())), "fixture: both are filed with their rows");
        rows.segments.remove(lost.segment());   // its row is gone (a database restored from before it)

        Journal back = open(s, rows);

        assertFalse(Files.exists(lostFile), "a segment file without its row is unlinked, though it holds a forced record");
        IOException e = assertThrows(IOException.class, () -> back.read(lost), "none of it is read");
        assertFalse(e instanceof JournalCorruptException, "it is no segment of the journal any more, not a damaged one: " + e);
        assertArrayEquals(body("kept", 100), back.read(kept), "the segment with its row is recovered");
        assertTrue(rows.segments.contains(kept.segment()), "and keeps its row: " + rows.segments);
    }

    @Test
    void aRowWhoseNodeFileIsGoneAndANodeOfAnotherJournalAreRefusedEachNamingWhatItFound() throws Exception {
        KeptRows rows = new KeptRows();
        Settings s = in("identity");
        close(open(s, rows));
        assertEquals(1, rows.nodes.size(), "fixture: the first start registered its journal");
        String node = rows.nodes.keySet().iterator().next();
        UUID mine = rows.nodes.get(node);
        Path nodeFile = s.dir.resolve("NODE");
        byte[] identity = Files.readAllBytes(nodeFile);
        Set<Long> filed = Set.copyOf(rows.segments);

        Files.delete(nodeFile);
        IllegalStateException gone = assertThrows(IllegalStateException.class, () -> open(s, rows));
        assertTrue(gone.getMessage().contains(mine.toString()) && gone.getMessage().contains(s.dir.toString()) && gone.getMessage().contains("NODE"),
                "names the registered journal, the directory and the missing NODE file: " + gone.getMessage());
        assertFalse(gone.getMessage().contains("null"), "no journal was found, and none is named: " + gone.getMessage());
        assertFalse(Files.exists(nodeFile), "no new journal is begun");

        UUID other = new UUID(mine.getMostSignificantBits() ^ 1, mine.getLeastSignificantBits());
        rows.nodes.put(node, other);
        Files.write(nodeFile, identity);
        IllegalStateException another = assertThrows(IllegalStateException.class, () -> open(s, rows));
        assertTrue(another.getMessage().contains(mine.toString()) && another.getMessage().contains(other.toString()),
                "names the journal of the NODE file and the registered one: " + another.getMessage());

        assertEquals(Map.of(node, other), Map.copyOf(rows.nodes), "the rows are kept");
        assertEquals(filed, Set.copyOf(rows.segments), "and so are the segment rows");
        assertArrayEquals(identity, Files.readAllBytes(nodeFile), "and the NODE file");
    }

    @Test
    void aFirstStartWhoseNodeFileCannotBeWrittenLeavesNoneOfItAndRegistersNothing() throws Exception {
        KeptRows rows = new KeptRows();
        Settings s = in("node-write");
        HeldFiles files = new HeldFiles();
        seams.add(files);
        Hold write = files.holdFirstWriteOn("a file under " + s.dir, p -> p.getParent().equals(s.dir.toAbsolutePath().normalize()));
        IOException enospc = new IOException("ENOSPC on the journal identity (test)");
        write.fail(enospc);

        IOException e = assertThrows(IOException.class, () -> Journal.open(s.config(), clock, timer, keys(KEY), files, rows));

        assertSame(enospc, e, "the write's own failure");
        assertTrue(write.isReached(), "fixture: the failed write is the identity's");
        List<String> left;
        try (Stream<Path> l = Files.list(s.dir)) {
            left = l.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("LOCK"), left, "only the LOCK: neither NODE nor a file of its write");
        assertEquals(Map.of(), Map.copyOf(rows.nodes), "nothing is registered");

        open(s, rows);
        assertEquals(1, rows.nodes.size(), "the next start begins the journal");
        assertTrue(Files.isRegularFile(s.dir.resolve("NODE")));
    }

    /** Every file under {@code dir}, by its path below it, with its bytes. */
    private static Map<String, String> filesUnder(Path dir) throws IOException {
        Map<String, String> files = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(dir.relativize(p).toString(), java.util.HexFormat.of().formatHex(Files.readAllBytes(p)));
            }
        }
        return files;
    }

    @Test
    void aNodeFileWithoutItsRowIsRegisteredOnlyWhileNoSegmentFileIsThere() throws Exception {
        KeptRows rows = new KeptRows();
        Settings s = in("unregistered");
        Journal first = open(s, rows);
        appendForced(first, "u-1");
        close(first);
        String node = rows.nodes.keySet().iterator().next();
        UUID mine = rows.nodes.get(node);
        Path segments = s.dir.resolve("segments");
        long filed;
        try (Stream<Path> l = Files.list(segments)) {
            filed = l.filter(p -> p.getFileName().toString().matches("[1-9][0-9]*\\.seg")).count();
        }
        assertTrue(filed > 0, "fixture: segment files are there");
        Map<String, String> before = filesUnder(s.dir);

        KeptRows another = new KeptRows();   // another database: it registers no journal for the node
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> open(s, another));

        assertTrue(e.getMessage().contains(mine.toString()) && e.getMessage().contains(filed + " segment file") && e.getMessage().contains("wrong or re-provisioned database"),
                "names the journal, the count of segment files and the hint: " + e.getMessage());
        assertEquals(before, filesUnder(s.dir), "nothing changed on disk");
        assertEquals(Map.of(), Map.copyOf(another.nodes), "nothing is registered");
        assertEquals(Set.of(), Set.copyOf(another.segments), "and no segment row inserted");

        List<Path> files;
        try (Stream<Path> l = Files.list(segments)) {
            files = l.sorted().toList();
        }
        for (Path p : files.subList(1, files.size())) Files.delete(p);
        IllegalStateException one = assertThrows(IllegalStateException.class, () -> open(s, another));
        assertTrue(one.getMessage().contains(" 1 segment file"), "one segment file is enough to refuse: " + one.getMessage());

        Files.delete(files.get(0));
        open(s, another);
        assertEquals(Map.of(node, mine), Map.copyOf(another.nodes), "without a segment file NODE's journal is registered");
    }
}
