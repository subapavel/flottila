package io.flottila.runtime.journal;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What a journal's start reads and changes through {@link PgSegmentRows} (CORE-4c1, CORE-3 item 9; the implementer's),
 * against a real Postgres at V12: a node's segment rows are its own — another node's are neither answered nor dropped —, a
 * drop deletes the rows it names and no other, and a node without rows answers an empty set (null would mean no rows are
 * kept); a node's journal is none until registered, then the registered one, and a second register of the node fails and
 * keeps the first.
 */
class PgSegmentRowsReadIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    CaseDatabase db;

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
        db = CaseDatabase.create(pg, vertx, "segment_rows_read_").migrated();
    }

    @AfterEach
    void closeThePool() {
        db.close();
    }

    @Test
    void aDropDeletesOnlyTheRowsItNamesOfItsNode() {
        PgSegmentRows rows = new PgSegmentRows(db.pool());
        for (long n : List.of(1L, 2L, 3L)) rows.insert("node-a", n).await();
        rows.insert("node-b", 2).await();

        rows.drop("node-a", Set.of(2L, 9L)).await();

        assertEquals(Set.of(1L, 3L), rows.segments("node-a").await(), "the named row is gone, the others stay; a number without a row drops nothing");
        assertEquals(Set.of(2L), rows.segments("node-b").await(), "another node's row of the same number stays");
        assertEquals(Set.of(), rows.segments("node-c").await(), "a node without rows: an empty set");
    }

    @Test
    void aNodesJournalIsNoneUntilRegisteredAndASecondRegisterKeepsTheFirst() {
        PgSegmentRows rows = new PgSegmentRows(db.pool());
        UUID first = new UUID(0x4c31, 1);
        UUID second = new UUID(0x4c31, 2);

        assertNull(rows.journalOf("node-a").await(), "none before the first start registers one");
        rows.register("node-a", first).await();

        assertEquals(first, rows.journalOf("node-a").await());
        assertNull(rows.journalOf("node-b").await(), "another node's journal is not this one's");
        assertThrows(Exception.class, () -> rows.register("node-a", second).await(), "a node registers one journal");
        assertEquals(first, rows.journalOf("node-a").await(), "and keeps it");
    }
}
