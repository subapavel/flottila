package io.flottila.runtime.journal;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Records round-trip (CORE-3 items 1–3, 8): any sequence of records of any size up to RECORD_MAX, under either of two
 * tenant keys, with any message id, step and part, reads back byte-equal by the ref its append returned — in the open
 * journal and after a restart — across rolls by records, bytes and age. The real {@link JournalFiles} is used.
 *
 * <p>jqwik runs no {@code @BeforeEach}/{@code @AfterEach} for a property: every try builds and removes its own state. The
 * class is {@code JournalRecordPropertiesTest}, not the spec's {@code JournalRecordProperties}: Surefire's default includes
 * ({@code *Test}, {@code Test*}, {@code *Tests}, {@code *TestCase}) would never run a class named {@code *Properties}.
 */
class JournalRecordPropertiesTest {

    /** FLOTTILA_JOURNAL_RECORD_MAX for this property: its lower edge is 4 KiB, 16 KiB keeps a try fast. */
    static final int RECORD_MAX = 16 * 1024;

    record Item(String messageId, int step, String part, int tenantKeyId, int size, int advanceMillis) {
        JournalRecord record() { return new JournalRecord(messageId, step, part, tenantKeyId, body()); }

        byte[] body() { return JournalFixture.body(messageId + "/" + step + "/" + part, size); }
    }

    @Provide
    Arbitrary<List<Item>> sequences() {
        Arbitrary<String> ids = Arbitraries.strings().withCharRange('a', 'z').withChars('-', '/', 'é', 'ж').ofMinLength(1).ofMaxLength(40);
        Arbitrary<Integer> steps = Arbitraries.integers().between(0, Integer.MAX_VALUE);
        Arbitrary<String> parts = Arbitraries.strings().withCharRange('a', 'z').withChars('.', '-').ofMinLength(1).ofMaxLength(16);
        Arbitrary<Integer> tenants = Arbitraries.of(1, 2);
        Arbitrary<Integer> sizes = Arbitraries.integers().between(0, RECORD_MAX);
        Arbitrary<Integer> still = Arbitraries.just(0);
        Arbitrary<Integer> moving = Arbitraries.integers().between(1, 1500);   // past FLOTTILA_JOURNAL_SEGMENT_AGE now and then
        Arbitrary<Integer> advances = Arbitraries.frequencyOf(Tuple.of(4, still), Tuple.of(1, moving));
        return Combinators.combine(ids, steps, parts, tenants, sizes, advances).as(Item::new).list().ofMinSize(1).ofMaxSize(30);
    }

    @Property(tries = 60)
    void anyRecordSequenceReadsBackByItsRef(@ForAll("sequences") List<Item> items) throws Exception {
        Path dir = Files.createTempDirectory("journal-property");
        try {
            FakeClock clock = FakeClock.at(START);
            FakeTimer timer = new FakeTimer(clock);
            JournalConfig config = settings(dir).recordMax(RECORD_MAX).segmentBytes(64 * 1024).segmentRecords(5)
                    .segmentAge(Duration.ofSeconds(1)).config();
            Journal journal = Journal.open(config, clock, timer, keys(1, 2), JournalFiles.real());
            List<JournalRef> refs = new ArrayList<>();
            try {
                List<Journal.Appended> appended = new ArrayList<>();
                for (Item item : items) {
                    if (item.advanceMillis() > 0) clock.advance(Duration.ofMillis(item.advanceMillis()));
                    appended.add(journal.append(item.record(), live()));
                }
                for (Journal.Appended a : appended) {
                    await(a.forced());
                    refs.add(a.ref());
                }
                for (int i = 1; i < refs.size(); i++) {
                    JournalRef before = refs.get(i - 1);
                    JournalRef after = refs.get(i);
                    assertTrue(after.segment() > before.segment()
                                    || (after.segment() == before.segment() && after.position() >= before.position() + before.length()),
                            "positions follow the append order without overlap: " + before + " then " + after);
                }
                for (int i = 0; i < items.size(); i++) {
                    assertArrayEquals(items.get(i).body(), journal.read(refs.get(i)), "record " + i + " " + items.get(i) + " at " + refs.get(i));
                }
            } finally {
                journal.close();
            }

            Journal reopened = Journal.open(config, clock, timer, keys(1, 2), JournalFiles.real());
            try {
                for (int i = 0; i < items.size(); i++) {
                    assertArrayEquals(items.get(i).body(), reopened.read(refs.get(i)), "after a restart, record " + i + " at " + refs.get(i));
                }
                assertEquals(items.size(), refs.stream().distinct().count(), "every ref is distinct");
            } finally {
                reopened.close();
            }
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
