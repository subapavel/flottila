package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Segment rows and the journal id with the seam (CORE-4c1 packet Scope 4 and 5, CORE-3 items 1 and 9): every segment — the
 * one an open starts and the writer's pre-opened ones, not only a large body's — has its {@link SegmentRows} row inserted,
 * and the insert answered, before its file exists in {@code segments/}; a segment whose insert failed never gets a file.
 * A first start writes the journal id its injected id source gives (never one it makes up itself) into {@code NODE} and
 * every segment header; a later start reads it and asks the source for nothing.
 *
 * <p>Witnesses: {@link RecordingRows#onInsert} notes, inside each insert, whether the file is there yet; {@link Opens}, a
 * {@link JournalFiles} over the real one, notes at each create of a segment file whether its insert has answered success.
 *
 * <p>The API beyond CORE-4b these cases compile against (proposed by the test-author, CORE-4c1 handback):
 * {@code Journal.open(JournalConfig, Clock, Timer, IntFunction<SecretKey>, JournalFiles, SegmentRows, Supplier<UUID> journalIds)},
 * the journal id of a first start being {@code journalIds.get()}.
 */
class JournalSegmentRowsTest {

    /** {@code NODE}: magic FJN1 · version u8 · journal id; a segment header: magic FJS1 · version u8 · journal id (CORE-3 item 1). */
    static final int ID_AT = 5;
    static final Pattern SEGMENT = Pattern.compile("([1-9][0-9]*)\\.seg");

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    private Settings in(String name) throws IOException {
        return settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1)).segmentRecords(1);
    }

    private Journal open(Settings s, JournalFiles files, SegmentRows rows) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), files, rows);
        journals.add(j);
        return j;
    }

    /** The real files; at each open that creates {@code segments/<n>.seg} it notes whether {@code inserted(n)} held. */
    static final class Opens implements JournalFiles {
        final Map<Long, Boolean> rowAtCreate = new ConcurrentHashMap<>();
        private final LongPredicate inserted;

        Opens(LongPredicate inserted) { this.inserted = inserted; }

        @Override public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
            Matcher m = SEGMENT.matcher(file.getFileName().toString());
            boolean creates = (options.contains(StandardOpenOption.CREATE_NEW) || options.contains(StandardOpenOption.CREATE)) && !Files.exists(file);
            if (creates && file.getParent() != null && file.getParent().getFileName().toString().equals("segments") && m.matches()) {
                long n = Long.parseLong(m.group(1));
                rowAtCreate.merge(n, inserted.test(n), (was, now) -> was && now);
            }
            return FileChannel.open(file, options);
        }
    }

    @Test
    void everySegmentHasItsRowBeforeItsFileExistsThePreOpenedOnesToo() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("rows-first");
        Path segments = s.dir.resolve("segments");
        Map<Long, Boolean> fileAtInsert = new ConcurrentHashMap<>();
        for (long n = 1; n <= 4; n++) {
            long segment = n;
            rows.onInsert(segment, insert -> fileAtInsert.put(segment, Files.exists(segments.resolve(segment + ".seg"))));
        }
        Opens files = new Opens(rows::inserted);

        Journal j = open(s, files, rows);
        for (String id : List.of("rf-1", "rf-2", "rf-3")) await(j.append(record(id, 100), live()).forced());   // one record per segment
        until(() -> files.rowAtCreate.size() >= 4, () -> "segments 1..4 created (the open's, and three pre-opens): " + files.rowAtCreate);

        assertEquals(Map.of(1L, true, 2L, true, 3L, true, 4L, true), Map.copyOf(files.rowAtCreate),
                "each segment file was created only after its row insert answered");
        assertEquals(Map.of(1L, false, 2L, false, 3L, false, 4L, false), Map.copyOf(fileAtInsert), "no segment file existed at its insert");
        assertEquals(List.of("node-1"), rows.inserts().stream().map(RecordingRows.Insert::node).distinct().toList(), "every row is this node's");
    }

    @Test
    void aSegmentWhoseRowInsertFailedGetsNoFile() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("pre-open-fails");
        rows.failNext(2, new IllegalStateException("the journal_segments insert is refused (test)"));
        Opens files = new Opens(rows::inserted);

        Journal j = open(s, files, rows);
        until(() -> !rows.insertsOf(2).isEmpty(), () -> "the pre-open of segment 2 inserted its row: " + rows.inserts());
        await(j.append(record("pf-1", 100), live()).forced());   // segment 1, then the roll
        // the record after the roll goes to segment 2: the writer joins its pre-open first, so once this record is answered
        // the pre-open is over. CORE-4c1 r1 (architect, implementer's change of the expectation): a failed pre-open insert is
        // no fail-stop but tried again on the timer, so the case fires that retry (the one task due within 5 s)
        Future<Void> barrier = j.append(record("pf-2", 100), live()).forced();
        until(() -> timer.pending() > 0 && timer.nextDelay().compareTo(Duration.ofSeconds(5)) <= 0, () -> "the failed insert of segment 2 is retried");
        clock.advance(timer.nextDelay());
        until(barrier::isComplete, () -> "the record after the roll is answered");
        assertFalse(j.isFailed(), "a failed row insert fails no journal");

        Boolean created = files.rowAtCreate.get(2L);
        assertTrue(created == null || created, "segment 2 has no file unless an insert of its row answered success: " + files.rowAtCreate);
        assertTrue(!Files.exists(s.dir.resolve("segments").resolve("2.seg")) || rows.inserted(2), "no 2.seg without its row");
    }

    @Test
    void anOpenWhoseFirstRowInsertFailsLeavesNoSegmentFileAndGivesTheLockBack() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("open-fails");
        IllegalStateException refused = new IllegalStateException("the journal_segments insert is refused (test)");
        rows.failNext(1, refused);

        Exception e = assertThrows(Exception.class, () -> open(s, new Opens(rows::inserted), rows));

        assertTrue(causedBy(e, refused), "the open fails with the insert's failure: " + e);
        assertFalse(Files.exists(s.dir.resolve("segments").resolve("1.seg")), "no file for the segment whose row is not in");
        Journal j = open(s, new Opens(rows::inserted), rows);   // the LOCK was given back
        Journal.Appended a = j.append(record("of-1", 10), live());
        await(a.forced());
        assertArrayEquals(JournalFixture.body("of-1", 10), j.read(a.ref()), "the second open takes records");
    }

    private static boolean causedBy(Throwable t, Throwable cause) {
        for (Throwable c = t; c != null; c = c.getCause()) if (c == cause) return true;
        return false;
    }

    @Test
    void aFirstStartWritesTheJournalIdOfItsIdSourceAndALaterStartAsksForNone() throws Exception {
        Settings s = in("identity");
        UUID id = UUID.fromString("6f1c2a4e-0d3b-4c5a-9e8f-7a6b5c4d3e2f");
        AtomicInteger asked = new AtomicInteger();
        Supplier<UUID> source = () -> {
            asked.incrementAndGet();
            return id;
        };

        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real(), new RecordingRows(), source);
        JournalRef ref = j.append(record("id-1", 50), live()).ref();
        await(j.append(record("id-2", 50), live()).forced());
        j.close();

        assertEquals(1, asked.get(), "one journal id for a first start");
        byte[] node = Files.readAllBytes(s.dir.resolve("NODE"));
        assertEquals(id, uuidAt(node), "NODE names the injected journal id");
        assertEquals(id, uuidAt(Files.readAllBytes(s.dir.resolve("segments").resolve(ref.segment() + ".seg"))), "and so does every segment header");

        Supplier<UUID> never = () -> {
            throw new AssertionError("a start that finds NODE asks for no journal id");
        };
        Journal back = Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real(), new RecordingRows(), never);
        journals.add(back);
        assertArrayEquals(node, Files.readAllBytes(s.dir.resolve("NODE")), "NODE is read, never rewritten");
        assertArrayEquals(JournalFixture.body("id-1", 50), back.read(ref));
    }

    private static UUID uuidAt(byte[] b) {
        ByteBuffer buf = ByteBuffer.wrap(b);
        return new UUID(buf.getLong(ID_AT), buf.getLong(ID_AT + 8));
    }
}
