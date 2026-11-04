package io.flottila.runtime.journal;

import io.flottila.api.Envelope;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c2 (D-105: "bytes live in the node's journal segments, never here"): no message body reaches Postgres. The schema has
 * no {@code bytea} column outside an allow-list — {@code bodies.bytes} (pre-V12 rows, read only until CORE-11), the tenant key
 * wrapped under the master key, and the existing hash columns; and a payload marker sent through the intake, with its input
 * kept (a {@code body_refs} row), is in no form in a {@code pg_dump} of the database — plain, hex (how a dump writes bytea) or
 * base64 — while the message id saved beside it is (the dump is no empty witness).
 */
@Timeout(120)
class SchemaIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    @TempDir Path tmp;
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
    void open() throws Exception {
        db = CaseDatabase.create(pg, vertx, "schema_").migrated();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void noByteaOutsideTheAllowList() {
        Set<String> bytea = new TreeSet<>();
        for (Row r : db.pool().query("select table_name, column_name, data_type, udt_name from information_schema.columns where table_schema = 'public'"
                + " and (data_type = 'bytea' or udt_name = '_bytea' or udt_name = 'bytea')").execute().await()) {
            bytea.add(r.getString(0) + "." + r.getString(1));
        }
        assertEquals(new TreeSet<>(Set.of(
                "bodies.bytes",            // pre-V12 rows, read only: no insert since CORE-4c2, the table goes with CORE-11
                "journal_keys.wrapped",    // a tenant key wrapped under the master key (D-106), never a body
                "messages.body_hash",      // 8 bytes of hash (V1)
                "dlq.body_hash",           // 8 bytes of hash (V1)
                "processed_ids.key_hash")), bytea, "no bytea column may hold a body (D-105)");
        assertEquals(1, db.count("select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'body_refs' and column_name = 'length'"),
                "fixture: the query sees V12's tables");
    }

    @Test
    void aPayloadMarkerSentThroughTheIntakeIsAbsentFromPgDump() throws Exception {
        String marker = "FLOTTILA-PAYLOAD-MARKER-4c2-7d1e";
        try (JournalWorld a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a")) {
            Buffer body = Buffer.buffer("<Invoice><Note>" + marker + "</Note></Invoice>");
            Envelope in = a.arrive("m-dumped", body);
            Future<String> saved = a.save(in, true, JournalWorld.undecided());
            assertTrue(saved.succeeded(), "fixture: saved " + saved.cause());
            assertEquals(1, db.count("select count(*) from body_refs where message_id = 'm-dumped'"), "fixture: its input is kept");
        }

        Container.ExecResult dump = pg.execInContainer("pg_dump", "-U", pg.getUsername(), "-d", db.name(), "--no-owner");
        assertEquals(0, dump.getExitCode(), "pg_dump ran: " + dump.getStderr());
        String text = dump.getStdout();
        assertTrue(text.contains("m-dumped"), "fixture: the dump holds the saved message's rows");
        byte[] bytes = marker.getBytes(StandardCharsets.UTF_8);
        assertFalse(text.contains(marker), "the marker is in no row, as text");
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains(HexFormat.of().formatHex(bytes)), "nor as bytea hex (\\x…)");
        assertFalse(text.contains(Base64.getEncoder().withoutPadding().encodeToString(bytes).substring(0, 24)), "nor base64");
    }
}
