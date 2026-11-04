package io.flottila.runtime.journal;

import io.flottila.runtime.node.OnVirtualThread;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static io.flottila.runtime.journal.MasterKeySourceTest.assertCarriesNoKey;
import static io.flottila.runtime.journal.MasterKeySourceTest.material;
import static io.flottila.runtime.journal.MasterKeySourceTest.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tenant keys shared by the nodes of one database (CORE-4c1, D-106: one master key per environment; the implementer's):
 * the first use of a tenant on a node that did not see another node make its key takes that key — one key per tenant —, and
 * under another master key it is refused naming the key id; a wrapped key of another length refuses the start naming its
 * id, and so does a row that unwraps under the master key to a key of another length (an AES-128 key): no tenant key is
 * weaker than 256 bits; a master key that is no AES key makes no tenant key and writes no row.
 */
class JournalKeysSharedIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    static final SecretKey MASTER = new SecretKeySpec(material(111, 32), "AES");
    static final SecretKey OTHER_MASTER = new SecretKeySpec(material(112, 32), "AES");

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
        db = CaseDatabase.create(pg, vertx, "journal_keys_shared_").migrated();
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

    private List<String> rows() {
        List<String> rows = new ArrayList<>();
        for (Row r : db.pool().query("select key_id, tenant, encode(wrapped, 'base64') from journal_keys order by key_id").execute().await()) {
            rows.add(r.getInteger(0) + " " + r.getString(1) + " " + r.getString(2));
        }
        return rows;
    }

    @Test
    void aFirstUseAfterAnotherNodeMadeTheTenantsKeyTakesThatKey() throws Exception {
        JournalKeys one = load(MASTER);
        JournalKeys two = load(MASTER);   // started before one made the key: two knows no tenant

        int made = keyOf(one, "acme");
        int taken = keyOf(two, "acme");

        assertEquals(made, taken, "one key per tenant: the second node takes the first one's key id");
        assertArrayEquals(one.apply(made).getEncoded(), two.apply(taken).getEncoded(), "and its key");
        assertEquals(1, rows().size(), "one row: " + rows());
        assertEquals(taken, keyOf(two, "acme"), "known from then on");
    }

    @Test
    void underAnotherMasterKeyTheKeyAnotherNodeMadeIsRefusedNamingItsId() throws Exception {
        JournalKeys other = load(OTHER_MASTER);
        JournalKeys one = load(MASTER);
        int made = keyOf(one, "acme");
        List<String> before = rows();

        Exception e = assertThrows(Exception.class, () -> keyOf(other, "acme"));

        JournalKeyException refused = assertInstanceOf(JournalKeyException.class, e);
        assertTrue(Pattern.compile("\\b" + made + "\\b").matcher(refused.getMessage()).find(), "names the key id " + made + ": " + refused.getMessage());
        assertCarriesNoKey(text(refused), List.of(), MASTER.getEncoded(), OTHER_MASTER.getEncoded(), one.apply(made).getEncoded());
        assertEquals(before, rows(), "nothing is written");
        assertNull(other.apply(made), "and nothing is held");
    }

    @Test
    void aWrappedKeyOfAnotherLengthRefusesTheStartNamingItsId() throws Exception {
        int id = keyOf(load(MASTER), "default");
        byte[] wrapped = db.pool().preparedQuery("select wrapped from journal_keys where key_id = $1").execute(Tuple.of(id)).await()
                .iterator().next().getBuffer(0).getBytes();
        db.pool().preparedQuery("update journal_keys set wrapped = $1 where key_id = $2")
                .execute(Tuple.of(Buffer.buffer(Arrays.copyOf(wrapped, wrapped.length - 1)), id)).await();

        Exception e = assertThrows(Exception.class, () -> load(MASTER));

        assertTrue(Pattern.compile("\\b" + id + "\\b").matcher(text(e)).find(), "names the key id " + id + ": " + text(e));
        assertCarriesNoKey(text(e), List.of(), MASTER.getEncoded(), wrapped);
    }

    /** {@code key} wrapped as D-106 stores it: nonce (12) ‖ AES-256-GCM of the key under {@code master}, the AAD u32 key id ‖ UTF-8 tenant. */
    private static byte[] wrappedAsStored(SecretKey master, int id, String tenant, byte[] key, int seed) throws Exception {
        byte[] nonce = material(seed, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(128, nonce));
        byte[] t = tenant.getBytes(StandardCharsets.UTF_8);
        c.updateAAD(ByteBuffer.allocate(4 + t.length).putInt(id).put(t).array());
        byte[] sealed = c.doFinal(key);
        return ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
    }

    private void storeWrapped(int id, byte[] wrapped) {
        db.pool().preparedQuery("update journal_keys set wrapped = $1 where key_id = $2").execute(Tuple.of(Buffer.buffer(wrapped), id)).await();
    }

    @Test
    void aRowThatUnwrapsToAKeyOfAnotherLengthRefusesTheStartNamingItsId() throws Exception {
        int id = keyOf(load(MASTER), "default");
        byte[] full = material(114, 32);
        storeWrapped(id, wrappedAsStored(MASTER, id, "default", full, 115));
        assertArrayEquals(full, load(MASTER).apply(id).getEncoded(), "fixture: a row wrapped this way unwraps under the master key");
        byte[] short128 = material(116, 16);
        storeWrapped(id, wrappedAsStored(MASTER, id, "default", short128, 117));

        Exception e = assertThrows(Exception.class, () -> load(MASTER));

        assertInstanceOf(JournalKeyException.class, e, "refused: " + e);
        assertTrue(Pattern.compile("\\b" + id + "\\b").matcher(text(e)).find(), "names the key id " + id + ": " + text(e));
        assertCarriesNoKey(text(e), List.of(), MASTER.getEncoded(), short128);
    }

    @Test
    void aMasterKeyThatIsNoAesKeyMakesNoTenantKey() throws Exception {
        JournalKeys keys = load(new SecretKeySpec(material(113, 32), "HmacSHA256"));

        Exception e = assertThrows(Exception.class, () -> keyOf(keys, "default"));

        assertInstanceOf(IllegalStateException.class, e, "refused: " + e);
        assertEquals(List.of(), rows(), "no row is written");
    }
}
