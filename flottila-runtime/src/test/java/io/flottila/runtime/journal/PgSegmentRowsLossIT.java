package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value {@code journal_losses} recorded accepts nothing again (CORE-4c3 item 3; the implementer's), even when the loss it
 * names is back — its marks cleared by hand, so the same items pin again and the refusal prints the same value: the start
 * is refused ({@link IllegalStateException}, the printed value) with a WARNING naming the stale value, and nothing changes.
 * Through {@link PgSegmentRows} on a real Postgres at V13 and a journal opened on it.
 */
class PgSegmentRowsLossIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Pattern PRINTED = Pattern.compile("FLOTTILA_JOURNAL_ACCEPT_LOSS=([0-9a-f-]{36}:[0-9a-f]+)");
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    final FakeClock clock = FakeClock.at(START);

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void caseDatabase() throws Exception {
        db = CaseDatabase.create(pg, vertx, "segment_rows_loss_").migrated();
    }

    @AfterEach
    void closeThePool() {
        db.close();
    }

    Journal open(JournalConfig config) throws Exception {
        return OnVirtualThread.call(() -> Journal.open(config, clock, new FakeTimer(clock), keys(KEY), JournalFiles.real(), new PgSegmentRows(db.pool())));
    }

    static JournalConfig accepting(JournalConfig c, String value) {
        return new JournalConfig(c.dir(), c.segmentBytes(), c.segmentRecords(), c.segmentAge(), c.pendingBytes(), c.appendWait(), c.recordMax(), c.node(),
                c.maxBytes(), c.readChannels(), Optional.of(value));
    }

    static String printed(Throwable e) {
        Matcher m = PRINTED.matcher(String.valueOf(e.getMessage()));
        assertTrue(m.find(), "the refusal prints the value: " + e.getMessage());
        return m.group(1);
    }

    String marks() {
        StringBuilder b = new StringBuilder();
        db.pool().query("select id, lost_at is not null from work order by id").execute().await().forEach(r -> b.append(r.getString(0)).append('=').append(r.getBoolean(1)).append(' '));
        return b.append(db.count("select count(*) from journal_losses")).toString();
    }

    @Test
    void aRecordedValueAcceptsNothingEvenWhenItsLossIsBack() throws Exception {
        Settings s = settings(Files.createDirectories(tmp.resolve("journal"))).segmentAge(Duration.ofHours(1)).segmentRecords(1);
        Journal first = open(s.config());
        Journal.Appended a = first.append(record("back-1", 100), live());
        await(a.forced());
        JournalRef ref = a.ref();
        first.close();
        db.pool().preparedQuery("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length) values ($1, 'orders', 1, $1, $2, $3, $4, $5)")
                .execute(Tuple.of("back-1", s.config().node(), ref.segment(), ref.position(), ref.length())).await();
        Files.delete(s.dir.resolve("segments").resolve(ref.segment() + ".seg"));
        String value = printed(assertThrows(IllegalStateException.class, () -> open(s.config()), "fixture: the loss refuses"));
        open(accepting(s.config(), value)).close();
        assertEquals("back-1=true 1", marks(), "fixture: accepted and recorded");
        db.pool().query("update work set lost_at = null").execute().await();   // the loss is back: the same item pins again

        try (LogCapture logs = new LogCapture()) {
            Throwable e = assertThrows(Throwable.class, () -> open(accepting(s.config(), value)));

            assertInstanceOf(IllegalStateException.class, e, "refused as a loss is, before any write: " + e);
            assertEquals(value, printed(e), "the same items: the same value");
            assertEquals("back-1=false 1", marks(), "nothing given up again, nothing recorded again");
            assertTrue(logs.records().stream().anyMatch(r -> r.getLevel().equals(Level.WARNING) && String.valueOf(r.getMessage()).contains(value)),
                    "a WARNING names the stale value: " + logs.lines());
        }
    }
}
