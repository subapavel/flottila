package io.flottila.runtime.journal;

import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import static io.flottila.runtime.journal.MasterKeySourceTest.assertCarriesNoKey;
import static io.flottila.runtime.journal.MasterKeySourceTest.base64;
import static io.flottila.runtime.journal.MasterKeySourceTest.material;
import static io.flottila.runtime.journal.MasterKeySourceTest.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tenant keys (CORE-4c1 packet Scope 3, D-106): {@code journal_keys(key_id, tenant, wrapped, created_at)}; a tenant's key
 * is created on first use — 256 random bits, wrapped under the master key (AES-256-GCM, the AAD the key id and the
 * tenant), stored, cached in memory — and at start every row is unwrapped; a row that fails refuses the start naming its
 * key id, never a key. The result is the {@code IntFunction<SecretKey>} the journal takes.
 *
 * <p>"Cached" and "unwrapped at start" are witnessed by a session holding {@code journal_keys} in ACCESS EXCLUSIVE mode:
 * a lookup that asked Postgres would wait behind it; the cases' lookups return within the bound.
 *
 * <p>The API these cases compile against (proposed by the test-author, CORE-4c1 handback):
 * {@code JournalKeys JournalKeys.load(Pool pool, SecretKey master)} (blocks: a virtual thread), {@code int keyOf(String tenant)}
 * (blocks on a first use), {@code SecretKey apply(int keyId)} (null for an id it does not hold); {@code JournalKeys implements
 * IntFunction<SecretKey>}.
 */
class JournalKeysIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;

    static final SecretKey MASTER = new SecretKeySpec(material(101, 32), "AES");
    static final SecretKey OTHER_MASTER = new SecretKeySpec(material(102, 32), "AES");

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
        db = CaseDatabase.create(pg, vertx, "journal_keys_").migrated();
    }

    @AfterEach
    void closeThePool() {
        db.close();
    }

    private JournalKeys load(SecretKey master) throws Exception {
        return OnVirtualThread.call(() -> JournalKeys.load(db.pool(), master));
    }

    private static int keyOf(JournalKeys keys, String tenant) throws Exception {
        return OnVirtualThread.call(() -> keys.keyOf(tenant));
    }

    private record KeyRow(int id, String tenant, byte[] wrapped) { }

    private List<KeyRow> rows() {
        List<KeyRow> rows = new ArrayList<>();
        for (Row r : db.pool().query("select key_id, tenant, wrapped from journal_keys order by key_id").execute().await()) {
            rows.add(new KeyRow(r.getInteger(0), r.getString(1), r.getBuffer(2).getBytes()));
        }
        return rows;
    }

    /** Runs {@code call} on a virtual thread while another session holds journal_keys in ACCESS EXCLUSIVE mode. */
    private <T> T whileTheTableIsLocked(Callable<T> call) throws Exception {
        PgConnection holder = PgConnection.connect(vertx, db.options()).await();
        try {
            Transaction tx = holder.begin().await();
            holder.query("lock table journal_keys in access exclusive mode").execute().await();
            try {
                return OnVirtualThread.call(call);
            } finally {
                tx.rollback().await();
            }
        } finally {
            holder.close().await();
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        return HeldFiles.indexOf(haystack, needle, 0) >= 0;
    }

    @Test
    void aTenantsKeyIsCreatedOnFirstUseWrappedStoredAndCached() throws Exception {
        JournalKeys keys = load(MASTER);
        IntFunction<SecretKey> lookup = keys;   // what the journal takes
        assertEquals(List.of(), rows(), "a start creates no key: a tenant's key is made on its first use");

        int id = keyOf(keys, "default");

        List<KeyRow> rows = rows();
        assertEquals(1, rows.size(), "one row for the first use: " + rows);
        assertEquals(id, rows.get(0).id());
        assertEquals("default", rows.get(0).tenant());
        SecretKey key = lookup.apply(id);
        assertEquals("AES", key.getAlgorithm());
        assertEquals(32, key.getEncoded().length, "a 256-bit tenant key");
        assertFalse(contains(rows.get(0).wrapped(), key.getEncoded()), "the row holds the key wrapped, never in plain");
        assertTrue(rows.get(0).wrapped().length >= 32 + 16, "wrapped with its GCM tag: " + rows.get(0).wrapped().length + " bytes");

        assertEquals(id, keyOf(keys, "default"), "the same tenant, the same key");
        int acme = keyOf(keys, "acme");
        assertNotEquals(id, acme, "another tenant, another key id");
        assertFalse(Arrays.equals(key.getEncoded(), lookup.apply(acme).getEncoded()), "and another key");
        assertEquals(2, rows().size(), "one row per tenant");
        assertNull(lookup.apply(Math.max(id, acme) + 1000), "an id it does not hold answers null");

        int again = whileTheTableIsLocked(() -> keys.keyOf("default"));
        byte[] cached = whileTheTableIsLocked(() -> keys.apply(acme).getEncoded());
        assertEquals(id, again, "a known tenant is answered from memory");
        assertArrayEquals(lookup.apply(acme).getEncoded(), cached, "a known id is answered from memory");
    }

    @Test
    void aStartUnwrapsEveryRowUnderTheMasterKey() throws Exception {
        JournalKeys first = load(MASTER);
        int def = keyOf(first, "default");
        int acme = keyOf(first, "acme");

        JournalKeys restarted = load(MASTER);

        byte[] acmeKey = whileTheTableIsLocked(() -> restarted.apply(acme).getEncoded());
        assertArrayEquals(first.apply(acme).getEncoded(), acmeKey, "unwrapped at start, before any lookup asked for it");
        assertArrayEquals(first.apply(def).getEncoded(), restarted.apply(def).getEncoded());
        assertEquals(def, whileTheTableIsLocked(() -> restarted.keyOf("default")), "a tenant with a row is known from the start on");
        assertEquals(2, rows().size(), "nothing was created again");
    }

    /** What a case does to the rows before the start that must refuse. */
    enum Tamper {
        /** One byte of the wrapped key flipped. */
        WRAPPED_BYTE,
        /** The row's tenant renamed: the tenant is in the AAD. */
        TENANT,
        /** Two rows' key ids exchanged, each tenant keeping its wrapped key: the key id is in the AAD. */
        KEY_ID,
        /** Another master key at the start. */
        MASTER
    }

    @ParameterizedTest
    @EnumSource(Tamper.class)
    void aRowThatCannotBeUnwrappedRefusesTheStartNamingItsKeyIdNotTheKey(Tamper tamper) throws Exception {
        JournalKeys first = load(MASTER);
        int def = keyOf(first, "default");
        int acme = tamper == Tamper.MASTER ? -1 : keyOf(first, "acme");
        List<KeyRow> before = rows();
        List<byte[]> secrets = new ArrayList<>(List.of(MASTER.getEncoded(), OTHER_MASTER.getEncoded(), first.apply(def).getEncoded()));
        for (KeyRow r : before) secrets.add(r.wrapped());
        if (acme >= 0) secrets.add(first.apply(acme).getEncoded());
        List<Integer> named = switch (tamper) {
            case WRAPPED_BYTE -> {
                byte[] w = before.stream().filter(r -> r.id() == acme).findFirst().orElseThrow().wrapped().clone();
                w[w.length / 2] ^= 0x01;
                update("update journal_keys set wrapped = $1 where key_id = $2", Tuple.of(Buffer.buffer(w), acme));
                yield List.of(acme);
            }
            case TENANT -> {
                update("update journal_keys set tenant = 'acme-renamed' where key_id = $1", Tuple.of(acme));
                yield List.of(acme);
            }
            case KEY_ID -> {
                KeyRow d = before.stream().filter(r -> r.id() == def).findFirst().orElseThrow();
                KeyRow a = before.stream().filter(r -> r.id() == acme).findFirst().orElseThrow();
                update("update journal_keys set tenant = 'swapping' where key_id = $1", Tuple.of(def));
                update("update journal_keys set tenant = $1, wrapped = $2 where key_id = $3", Tuple.of(d.tenant(), Buffer.buffer(d.wrapped()), acme));
                update("update journal_keys set tenant = $1, wrapped = $2 where key_id = $3", Tuple.of(a.tenant(), Buffer.buffer(a.wrapped()), def));
                yield List.of(def, acme);
            }
            case MASTER -> List.of(def);
        };
        List<KeyRow> tampered = rows();
        SecretKey startWith = tamper == Tamper.MASTER ? OTHER_MASTER : MASTER;

        Exception refused = assertThrows(Exception.class, () -> load(startWith), tamper + ": the start is refused");

        String text = text(refused);
        assertTrue(named.stream().anyMatch(id -> Pattern.compile("\\b" + id + "\\b").matcher(text).find()),
                tamper + ": the refusal names the key id " + named + ": " + text);
        assertCarriesNoKey(text, List.of(), secrets.toArray(byte[][]::new));
        assertEquals(tampered.stream().map(r -> r.id() + " " + r.tenant() + " " + base64(r.wrapped())).toList(),
                rows().stream().map(r -> r.id() + " " + r.tenant() + " " + base64(r.wrapped())).toList(), "the refused start changed no row");
        if (tamper == Tamper.MASTER) {
            assertEquals(def, keyOf(load(MASTER), "default"), "fixture: the right master still unwraps the row");
        }
    }

    private void update(String sql, Tuple args) {
        assertEquals(1, db.pool().preparedQuery(sql).execute(args).await().rowCount(), "fixture: " + sql);
    }
}
