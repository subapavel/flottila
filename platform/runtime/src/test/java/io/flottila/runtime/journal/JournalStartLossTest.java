package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.runtime.journal.JournalFreeSpaceTest.SpaceFiles;
import io.flottila.runtime.journal.JournalKeptRowsTest.KeptRows;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start's checks where the packet's cases leave a branch open (CORE-4c3 items 2, 5, 8; the implementer's): the journal id
 * the NODE-lost guard prints is the one its segment files' headers name — the zero id when the first one is too short to name
 * one —, and a file system whose usable space is reported as {@code Long.MAX_VALUE} (unknown) does not overflow into a refusal.
 */
class JournalStartLossTest {

    static final java.util.regex.Pattern PRINTED = java.util.regex.Pattern.compile("FLOTTILA_JOURNAL_ACCEPT_LOSS=([0-9a-f-]{36}):([0-9a-f]+)");

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    private Journal open(Settings s, JournalFiles files, SegmentRows rows) throws Exception {
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), files, rows);
        journals.add(j);
        return j;
    }

    private static UUID printedJournal(IllegalStateException e) {
        java.util.regex.Matcher m = PRINTED.matcher(e.getMessage());
        assertTrue(m.find(), "the refusal prints <journal id>:<digest>: " + e.getMessage());
        return UUID.fromString(m.group(1));
    }

    @Test
    void aLostNodeFileOnADatabaseWithoutItsRowPrintsTheJournalItsSegmentsName() throws Exception {
        Settings s = settings(Files.createDirectories(tmp.resolve("lost-node"))).segmentAge(Duration.ofHours(1)).segmentRecords(1);
        KeptRows first = new KeptRows();
        Journal j = open(s, JournalFiles.real(), first);
        await(j.append(record("g-1", 100), live()).forced());
        journals.remove(j);
        j.close();
        UUID mine = first.nodes.values().iterator().next();
        Files.delete(s.dir.resolve("NODE"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> open(s, JournalFiles.real(), new KeptRows()));

        assertEquals(mine, printedJournal(e), "the journal its segment files name: " + e.getMessage());
        assertFalse(Files.exists(s.dir.resolve("NODE")), "no NODE written");
    }

    @Test
    void aFirstSegmentFileTooShortToNameItsJournalPrintsTheZeroId() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("torn"));
        Files.write(Files.createDirectories(dir.resolve("segments")).resolve("1.seg"), new byte[] {0x46, 0x4A, 0x53});   // a header torn after 3 bytes
        Settings s = settings(dir).segmentAge(Duration.ofHours(1));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> open(s, JournalFiles.real(), new KeptRows()));

        assertEquals(JournalStart.NO_ID, printedJournal(e), "no file names a journal: " + e.getMessage());
        assertTrue(e.getMessage().contains("1 segment file"), e.getMessage());
    }

    /**
     * The value the refusal printed, set as FLOTTILA_JOURNAL_ACCEPT_LOSS, starts a new journal on rows that record no loss (the
     * {@link SegmentRows} defaults: nothing accepted before, the give-up a no-op): NODE names a new id, the old file is gone.
     */
    @Test
    void thePrintedValueStartsANewJournalOnRowsThatRecordNoLoss() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("accepted"));
        Path torn = Files.write(Files.createDirectories(dir.resolve("segments")).resolve("1.seg"), new byte[] {0x46, 0x4A, 0x53});
        Settings s = settings(dir).segmentAge(Duration.ofHours(1));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> open(s, JournalFiles.real(), new KeptRows()));
        java.util.regex.Matcher printed = PRINTED.matcher(refused.getMessage());
        assertTrue(printed.find(), refused.getMessage());
        JournalConfig c = s.config();
        JournalConfig accepting = new JournalConfig(c.dir(), c.segmentBytes(), c.segmentRecords(), c.segmentAge(), c.pendingBytes(),
                c.appendWait(), c.recordMax(), c.node(), c.maxBytes(), c.readChannels(), java.util.Optional.of(printed.group(1) + ":" + printed.group(2)));

        Journal j = Journal.open(accepting, clock, timer, keys(KEY), JournalFiles.real(), new KeptRows());
        journals.add(j);

        UUID now = SegmentFormat.journalOf(Files.readAllBytes(dir.resolve("NODE")));
        assertTrue(now != null && !now.equals(JournalStart.NO_ID), "NODE names the new journal: " + now);
        assertFalse(Files.exists(torn), "the given-up journal's file is no longer kept");
        Journal.Appended a = j.append(record("n-1", 100), live());
        await(a.forced());
        assertArrayEquals(body("n-1", 100), j.read(a.ref()), "the new journal appends and reads");
    }

    @Test
    void aUsableSpaceOfLongMaxValueDoesNotOverflowIntoARefusal() throws Exception {
        Settings s = settings(Files.createDirectories(tmp.resolve("huge"))).segmentAge(Duration.ofHours(1)).maxBytes(Long.MAX_VALUE);
        SpaceFiles files = new SpaceFiles();
        files.usable = Long.MAX_VALUE;   // what a file store reports when its size is beyond a long
        Journal first = open(s, files, (node, segment) -> io.vertx.core.Future.succeededFuture());
        await(first.append(record("h-1", 1000), live()).forced());
        journals.remove(first);
        first.close();
        assertTrue(JournalFreeSpaceTest.segmentBytes(s.dir) > 1000, "fixture: the journal holds bytes, so usable + them overflows a long");

        Journal again = open(s, files, (node, segment) -> io.vertx.core.Future.succeededFuture());

        Journal.Appended a = again.append(record("h-2", 100), live());
        await(a.forced());
        assertArrayEquals(body("h-2", 100), again.read(a.ref()), "usable Long.MAX_VALUE + the journal's bytes is room enough, never negative");
    }

    /**
     * CORE-4c3 review r1 (3): the printed {@code <journal id>:<digest>} covers the pins as a set — {@code pinsBeyond} answers
     * them "in no order" — so the same pins answered in two orders print one value (a value the operator copies must not change
     * between two starts that lose the same items). Four kept refs and work items across two segments, reversed and interleaved.
     */
    @Test
    void theSamePinsInAnotherOrderPrintTheSameValue() throws Exception {
        Settings s = settings(Files.createDirectories(tmp.resolve("orders"))).segmentAge(Duration.ofHours(1));
        KeptRows registry = new KeptRows();
        Journal first = open(s, JournalFiles.real(), registry);
        journals.remove(first);
        first.close();
        List<SegmentRows.Pin> pins = List.of(
                new SegmentRows.Pin(SegmentRows.Pin.REF, "m-b", 0, "body", 7, 0, 120),
                new SegmentRows.Pin(3, "m-a", 0, "body", 7, 120, 80),
                new SegmentRows.Pin(SegmentRows.Pin.REF, "m-a", 2, "body", 9, 0, 64),
                new SegmentRows.Pin(11, "m-c", 0, "body", 9, 64, 300),
                new SegmentRows.Pin(SegmentRows.Pin.REF, "m-c", 1, "attachment-1", 9, 364, 16));
        List<SegmentRows.Pin> reversed = new ArrayList<>(pins);
        java.util.Collections.reverse(reversed);
        List<SegmentRows.Pin> interleaved = List.of(pins.get(2), pins.get(0), pins.get(4), pins.get(1), pins.get(3));

        String asListed = printedValue(assertThrows(IllegalStateException.class, () -> open(s, JournalFiles.real(), pinning(registry, pins))));
        String asReversed = printedValue(assertThrows(IllegalStateException.class, () -> open(s, JournalFiles.real(), pinning(registry, reversed))));
        String asInterleaved = printedValue(assertThrows(IllegalStateException.class, () -> open(s, JournalFiles.real(), pinning(registry, interleaved))));

        assertEquals(registry.nodes.values().iterator().next().toString(), asListed.substring(0, 36), "fixture: the registered journal is the one given up");
        assertEquals(asListed, asReversed, "reversed pins print the same value");
        assertEquals(asListed, asInterleaved, "interleaved pins print the same value");
    }

    private static String printedValue(IllegalStateException e) {
        java.util.regex.Matcher m = PRINTED.matcher(e.getMessage());
        assertTrue(m.find(), "the refusal prints <journal id>:<digest>: " + e.getMessage());
        assertTrue(e.getMessage().contains("5 committed item(s)"), "fixture: the five pins are what is given up: " + e.getMessage());
        return m.group(1) + ":" + m.group(2);
    }

    /** {@code registry}'s rows, whose committed pins beyond the journal's records are {@code pins}, answered in that order. */
    private static SegmentRows pinning(KeptRows registry, List<SegmentRows.Pin> pins) {
        return new SegmentRows() {
            @Override public io.vertx.core.Future<Void> insert(String node, long segment) { return registry.insert(node, segment); }
            @Override public io.vertx.core.Future<UUID> journalOf(String node) { return registry.journalOf(node); }
            @Override public io.vertx.core.Future<Void> register(String node, UUID journal) { return registry.register(node, journal); }
            @Override public io.vertx.core.Future<java.util.Set<Long>> segments(String node) { return registry.segments(node); }
            @Override public io.vertx.core.Future<Void> drop(String node, java.util.Set<Long> dropped) { return registry.drop(node, dropped); }
            @Override public io.vertx.core.Future<List<SegmentRows.Pin>> pinsBeyond(String node, java.util.Map<Long, Long> ends) {
                return io.vertx.core.Future.succeededFuture(pins);
            }
        };
    }
}
