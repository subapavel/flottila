package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PgSegmentRows} (CORE-4c1 packet Scope 5, CORE-3 item 5) against a real Postgres at V12: an insert writes the
 * {@code journal_segments} row of its node and segment, committed when its future succeeds; a repeated insert is
 * idempotent (a persist retried after a failed move inserts again, {@link SegmentRows}); and a journal over it creates
 * every segment file — the open's and each pre-open's — only once the row is committed, seen from another session at the
 * moment the file is created.
 *
 * <p>The API these cases compile against (proposed by the test-author, CORE-4c1 handback):
 * {@code new PgSegmentRows(Pool pool)} implementing {@link SegmentRows}.
 */
class PgSegmentRowsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    final List<Journal> journals = new ArrayList<>();

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
        db = CaseDatabase.create(pg, vertx, "segment_rows_").migrated();
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
        db.close();
    }

    private static List<String> rowsOf(Pool pool) {
        List<String> rows = new ArrayList<>();
        for (Row r : pool.query("select node, segment, created_at is not null from journal_segments order by node, segment").execute().await()) {
            rows.add(r.getString(0) + " " + r.getLong(1) + (r.getBoolean(2) ? "" : " (no created_at)"));
        }
        return rows;
    }

    @Test
    void anInsertCommitsTheRowOfItsNodeAndSegmentAndARepeatedInsertIsIdempotent() throws Exception {
        PgSegmentRows rows = new PgSegmentRows(db.pool());

        OnVirtualThread.call(() -> {
            rows.insert("node-a", 1).await();
            rows.insert("node-a", 2).await();
            rows.insert("node-b", 1).await();
            rows.insert("node-a", 2).await();   // a persist retried after a failed move
            return null;
        });

        // seen from a session of its own, not the pool that wrote them: committed
        Pool observer = io.vertx.pgclient.PgBuilder.pool().connectingTo(db.options()).using(vertx).build();
        try {
            assertEquals(List.of("node-a 1", "node-a 2", "node-b 1"), rowsOf(observer), "one row per node and segment, each with its created_at");
        } finally {
            observer.close().await();
        }
    }

    @Test
    void aJournalOverItCreatesEverySegmentFileOnlyOnceItsRowIsCommitted() throws Exception {
        Pool observer = io.vertx.pgclient.PgBuilder.pool().connectingTo(db.options()).using(vertx).build();
        try {
            FakeClock clock = FakeClock.at(START);
            Settings s = settings(Files.createDirectories(tmp.resolve("journal"))).segmentAge(Duration.ofHours(1)).segmentRecords(1);
            JournalSegmentRowsTest.Opens files = new JournalSegmentRowsTest.Opens(n -> observer.preparedQuery(
                    "select count(*) from journal_segments where node = $1 and segment = $2").execute(Tuple.of(s.node, n)).await()
                    .iterator().next().getLong(0) == 1);

            Journal j = OnVirtualThread.call(() -> Journal.open(s.config(), clock, new FakeTimer(clock), keys(KEY), files, new PgSegmentRows(db.pool())));
            journals.add(j);
            for (String id : List.of("pg-1", "pg-2", "pg-3")) await(j.append(record(id, 100), live()).forced());
            until(() -> files.rowAtCreate.size() >= 4, () -> "segments 1..4 created: " + files.rowAtCreate);

            assertEquals(Map.of(1L, true, 2L, true, 3L, true, 4L, true), Map.copyOf(files.rowAtCreate),
                    "another session saw each segment's row committed when its file was created");
            TreeSet<Long> onDisk = new TreeSet<>();
            try (Stream<Path> l = Files.list(s.dir.resolve("segments"))) {
                l.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".seg")).forEach(f -> onDisk.add(Long.parseLong(f.substring(0, f.length() - 4))));
            }
            TreeSet<Long> rows = new TreeSet<>();
            for (Row r : observer.query("select segment from journal_segments where node = 'node-1'").execute().await()) rows.add(r.getLong(0));
            assertTrue(rows.containsAll(onDisk), "no segment file without its row: files " + onDisk + ", rows " + rows);
        } finally {
            observer.close().await();
        }
    }
}
