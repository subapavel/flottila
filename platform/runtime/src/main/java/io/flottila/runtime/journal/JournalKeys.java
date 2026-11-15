package io.flottila.runtime.journal;

import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * The tenant keys (D-106, CORE-3 item 2): {@code journal_keys(key_id, tenant, wrapped, created_at)}, a tenant's 256-bit key
 * made on its first use, wrapped under the master key ({@link MasterKeySource}) with AES-256-GCM — a random 96-bit nonce, the
 * AAD the key id (u32) and the tenant (UTF-8) — and stored as {@code nonce ‖ ciphertext ‖ tag}; the key id, the record
 * header's tenant key id, comes from {@code journal_key_ids} before the wrap. A start unwraps every row; a row that fails
 * refuses it, naming its key id (never a key). Every key is held in memory from then on: a lookup asks Postgres nothing. The
 * journal takes it as its {@link IntFunction}. Its calls block on Postgres: call them on a virtual thread.
 *
 * @since 0.1
 */
public final class JournalKeys implements IntFunction<SecretKey> {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Pool pool;
    private final SecretKey master;
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKey> byId = new ConcurrentHashMap<>();
    private final Map<String, Integer> byTenant = new ConcurrentHashMap<>();

    private JournalKeys(Pool pool, SecretKey master) {
        this.pool = pool;
        this.master = master;
    }

    /**
     * Every {@code journal_keys} row unwrapped under {@code master}; nothing is written.
     *
     * @throws JournalKeyException a row cannot be unwrapped (another master key, or the row was changed): the message names
     *                             its key id
     */
    public static JournalKeys load(Pool pool, SecretKey master) throws JournalKeyException {
        JournalKeys keys = new JournalKeys(Objects.requireNonNull(pool, "pool"), Objects.requireNonNull(master, "master"));
        for (Row r : pool.query("select key_id, tenant, wrapped from journal_keys order by key_id").execute().await()) {
            keys.hold(r.getInteger(0), r.getString(1), r.getBuffer(2).getBytes());
        }
        return keys;
    }

    /** The tenant key of {@code keyId}, or null when there is none. */
    @Override
    public SecretKey apply(int keyId) {
        return byId.get(keyId);
    }

    /**
     * The key id of {@code tenant}'s key: from memory once known; on a first use a new key is made, wrapped and stored. When
     * another node (or another first use here) stored {@code tenant}'s key first, that one is unwrapped and taken instead:
     * one key per tenant.
     *
     * @throws JournalKeyException the key stored first cannot be unwrapped under this node's master key
     */
    public int keyOf(String tenant) throws JournalKeyException {
        Integer known = byTenant.get(Objects.requireNonNull(tenant, "tenant"));
        if (known != null) return known;
        int id = pool.query("select nextval('journal_key_ids')::int").execute().await().iterator().next().getInteger(0);
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        try {
            RowSet<Row> stored = pool.preparedQuery("insert into journal_keys(key_id, tenant, wrapped) values ($1, $2, $3) on conflict (tenant) do nothing")
                    .execute(Tuple.of(id, tenant, Buffer.buffer(wrap(id, tenant, key)))).await();
            if (stored.rowCount() == 1) {
                take(id, tenant, new SecretKeySpec(key, "AES"));
                return id;
            }
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        Row first = pool.preparedQuery("select key_id, wrapped from journal_keys where tenant = $1").execute(Tuple.of(tenant)).await().iterator().next();
        return hold(first.getInteger(0), tenant, first.getBuffer(1).getBytes());
    }

    /** Unwraps the row of {@code id} and holds its key; answers {@code id}. */
    private int hold(int id, String tenant, byte[] wrapped) throws JournalKeyException {
        take(id, tenant, unwrap(id, tenant, wrapped));
        return id;
    }

    private void take(int id, String tenant, SecretKey key) {
        byId.put(id, key);
        byTenant.put(tenant, id);
    }

    private byte[] wrap(int id, String tenant, byte[] key) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(id, tenant));
            byte[] sealed = c.doFinal(key);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the journal master key cannot wrap a tenant key (it must be a 256-bit AES key)", e);
        }
    }

    private SecretKey unwrap(int id, String tenant, byte[] wrapped) throws JournalKeyException {
        byte[] key = null;
        try {
            if (wrapped.length != NONCE_BYTES + KEY_BYTES + TAG_BITS / 8) throw new GeneralSecurityException("a wrapped key of " + wrapped.length + " bytes");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_BYTES));
            c.updateAAD(aad(id, tenant));
            key = c.doFinal(wrapped, NONCE_BYTES, wrapped.length - NONCE_BYTES);
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException e) {
            throw new JournalKeyException("the journal key " + id + " (journal_keys) cannot be unwrapped under the master key: another master key, or "
                    + "its row was changed; the start is refused", e);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    /** The key id (u32) and the tenant (UTF-8): a wrapped key moved to another id or tenant fails its tag. */
    private static byte[] aad(int id, String tenant) {
        byte[] t = tenant.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + t.length).putInt(id).put(t).array();
    }
}
