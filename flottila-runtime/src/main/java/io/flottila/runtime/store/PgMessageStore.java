package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.api.Timer;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.batch.PgBatchSink;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The MessageStore over Postgres (ARCHITECTURE §7, §8): every snapshot goes through the P0-06 {@link BatchingWriter} into
 * one transaction per batch — {@code payloads} (hash, size, algorithm; {@code ON CONFLICT DO NOTHING}, the bytes are the
 * BlobStore's), {@code messages} (one row per message and step, the first wins) and {@code message_keys} (one row per
 * (name, value, message), numbered by V5's {@code seq} in the order the message first carried the key — what
 * {@link #findByKey} pages through its {@code (name, value, seq)} index; the cursor is the page's last {@code seq}). Reads
 * are plain queries. A failed commit fails the snapshot's future and counts in {@link #failed()}; the message path does
 * not wait for it.
 *
 * @since 0.1
 */
public final class PgMessageStore implements MessageStore, AutoCloseable {

    private static final String INSERT_PAYLOADS = "insert into payloads(hash, size, algorithm) select * from unnest($1::bytea[], $2::bigint[], $3::text[]) on conflict (hash) do nothing";
    private static final String INSERT_MESSAGES = """
            insert into messages(id, step, flow, step_name, outcome, error, started_at, wall_ns, cpu_ns, headers, props, keys, body_hash, body_key, body_size, versions)
            select * from unnest($1::text[], $2::int[], $3::text[], $4::text[], $5::text[], $6::text[], $7::timestamptz[], $8::bigint[], $9::bigint[],
                                 $10::jsonb[], $11::jsonb[], $12::jsonb[], $13::bytea[], $14::text[], $15::bigint[], $16::jsonb[])
            on conflict (id, step) do nothing""";
    private static final String INSERT_KEYS = "insert into message_keys(name, value, message_id, step) select * from unnest($1::text[], $2::text[], $3::text[], $4::int[]) "
            + "on conflict (name, value, message_id) do nothing";
    private static final String TIMELINE = "select id, step, flow, step_name, outcome, error, started_at, wall_ns, cpu_ns, headers, props, keys, body_hash, body_key, body_size, versions, "
            + "(select algorithm from payloads p where p.hash = m.body_hash) as algorithm from messages m where id = $1 order by step";
    private static final String FIND_BY_KEY = "select message_id, seq from message_keys where name = $1 and value = $2 order by seq limit $3";
    private static final String FIND_BY_KEY_AFTER = "select message_id, seq from message_keys where name = $1 and value = $2 and seq > $3 order by seq limit $4";
    private static final Comparator<KeyRow> KEY_ORDER = Comparator.comparing(KeyRow::name).thenComparing(KeyRow::value).thenComparing(KeyRow::messageId);

    private record KeyRow(String name, String value, String messageId) {}

    private final Pool pool;
    private final BatchingWriter<Snapshot> writer;
    private final AtomicLong failed = new AtomicLong();

    public PgMessageStore(Pool pool, Timer timer, BatchingWriter.Options options) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.writer = new BatchingWriter<>(new PgBatchSink<>(pool, List.of(
                new PgBatchSink.Statement<>(INSERT_PAYLOADS, PgMessageStore::payloads),
                new PgBatchSink.Statement<>(INSERT_MESSAGES, PgMessageStore::messages),
                new PgBatchSink.Statement<>(INSERT_KEYS, PgMessageStore::keys))), timer, options);
    }

    @Override
    public Future<Void> record(Snapshot snapshot) {
        return writer.save(Objects.requireNonNull(snapshot, "snapshot")).onFailure(t -> failed.incrementAndGet());
    }

    @Override
    public Future<List<Snapshot>> timeline(String messageId) {
        return pool.preparedQuery(TIMELINE).execute(Tuple.of(messageId)).map(rows -> {
            List<Snapshot> out = new ArrayList<>();
            for (Row r : rows) out.add(snapshot(r));
            return List.copyOf(out);
        });
    }

    @Override
    public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
        Page.requireLimit(limit);
        Future<RowSet<Row>> rows = after.isPresent()
                ? pool.preparedQuery(FIND_BY_KEY_AFTER).execute(Tuple.of(name, value, seq(after.get()), limit + 1))
                : pool.preparedQuery(FIND_BY_KEY).execute(Tuple.of(name, value, limit + 1));
        return rows.map(found -> {
            List<String> ids = new ArrayList<>();
            long last = 0;
            for (Row r : found) {
                if (ids.size() == limit) return new Page<>(ids, Optional.of(Page.cursor(Long.toString(last))));   // the limit + 1st row: another page exists
                ids.add(r.getString(0));
                last = r.getLong(1);
            }
            return new Page<>(ids, Optional.<String>empty());
        });
    }

    /** The key sequence a cursor carries; a garbled cursor is a NumberFormatException (an IllegalArgumentException). */
    private static long seq(String cursor) {
        return Long.parseLong(Page.position(cursor));
    }

    /** Commits what is queued now; completes when it is committed. */
    public Future<Void> flush() { return writer.flush(); }

    /** Snapshots whose write failed (a failed commit or a full backlog) since the store was opened — what monitoring shows until P1-09 exposes it. */
    public long failed() { return failed.get(); }

    /** Refuses further snapshots and drains what is queued. */
    @Override
    public void close() {
        writer.close();
        writer.flush().await();
    }

    // --- rows → arrays (one transaction per batch, PgBatchSink)

    /** Rows go in one order in every transaction (payloads by hash, messages by id and step): concurrent batches sharing a hash never deadlock on it. */
    private static Tuple payloads(List<Snapshot> batch) {
        Map<Hash, Long> distinct = new TreeMap<>(Comparator.comparing(Hash::algorithm).thenComparingLong(Hash::value));
        for (Snapshot s : batch) distinct.putIfAbsent(s.bodyHash(), s.bodySize());
        Buffer[] hashes = new Buffer[distinct.size()];
        Long[] sizes = new Long[distinct.size()];
        String[] algorithms = new String[distinct.size()];
        int i = 0;
        for (Map.Entry<Hash, Long> e : distinct.entrySet()) {
            hashes[i] = bytes(e.getKey());
            sizes[i] = e.getValue();
            algorithms[i] = e.getKey().algorithm();
            i++;
        }
        return Tuple.wrap(new Object[] {hashes, sizes, algorithms});
    }

    private static Tuple messages(List<Snapshot> unordered) {
        List<Snapshot> batch = new ArrayList<>(unordered);
        batch.sort(Comparator.comparing(Snapshot::messageId).thenComparingInt(Snapshot::step));
        int n = batch.size();
        String[] ids = new String[n], flows = new String[n], names = new String[n], outcomes = new String[n], errors = new String[n], bodyKeys = new String[n];
        Integer[] steps = new Integer[n];
        OffsetDateTime[] started = new OffsetDateTime[n];
        Long[] walls = new Long[n], cpus = new Long[n], sizes = new Long[n];
        JsonObject[] headers = new JsonObject[n], props = new JsonObject[n], keys = new JsonObject[n], versions = new JsonObject[n];
        Buffer[] hashes = new Buffer[n];
        for (int i = 0; i < n; i++) {
            Snapshot s = batch.get(i);
            ids[i] = s.messageId();
            steps[i] = s.step();
            flows[i] = s.flow();
            names[i] = s.stepName();
            outcomes[i] = s.outcome().name().toLowerCase(Locale.ROOT);
            errors[i] = s.error().orElse(null);
            started[i] = s.startedAt().atOffset(ZoneOffset.UTC);
            walls[i] = s.wallNanos();
            cpus[i] = s.cpuNanos() == Snapshot.CPU_UNKNOWN ? null : s.cpuNanos();
            headers[i] = new JsonObject(new HashMap<>(s.headers().asMap()));
            props[i] = json(s.props().asMap());
            keys[i] = new JsonObject(new HashMap<>(s.keys().asMap()));
            hashes[i] = bytes(s.bodyHash());
            bodyKeys[i] = s.bodyKey().map(PgMessageStore::text).orElse(null);
            sizes[i] = s.bodySize();
            versions[i] = new JsonObject(new HashMap<>(s.versions()));
        }
        return Tuple.wrap(new Object[] {ids, steps, flows, names, outcomes, errors, started, walls, cpus, headers, props, keys, hashes, bodyKeys, sizes, versions});
    }

    /**
     * One row per (name, value, message) of the batch, at the lowest step of the batch that carried it, sorted (D-044's
     * deadlock rule: V5's unique key is locked in one order by every transaction); a row already in the table — an earlier
     * batch's — is a no-op, so the first row written keeps its step and its place.
     */
    private static Tuple keys(List<Snapshot> batch) {
        Map<KeyRow, Integer> distinct = new TreeMap<>(KEY_ORDER);
        for (Snapshot s : batch) {
            for (Map.Entry<String, String> k : s.keys().asMap().entrySet()) distinct.merge(new KeyRow(k.getKey(), k.getValue(), s.messageId()), s.step(), Math::min);
        }
        int n = distinct.size(), i = 0;
        String[] names = new String[n], values = new String[n], ids = new String[n];
        Integer[] steps = new Integer[n];
        for (Map.Entry<KeyRow, Integer> e : distinct.entrySet()) {
            names[i] = e.getKey().name();
            values[i] = e.getKey().value();
            ids[i] = e.getKey().messageId();
            steps[i] = e.getValue();
            i++;
        }
        return Tuple.wrap(new Object[] {names, values, ids, steps});
    }

    /** Props as JSON: JSON-native values as they are, anything else by its string form (props never leave the runtime, ARCHITECTURE §5). */
    private static JsonObject json(Map<String, Object> props) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Object v = e.getValue();
            o.put(e.getKey(), v instanceof Number || v instanceof Boolean || v instanceof String ? v : String.valueOf(v));
        }
        return o;
    }

    // --- row → Snapshot

    private static Snapshot snapshot(Row r) {
        Long cpu = r.getLong("cpu_ns");
        return new Snapshot(r.getString("id"), r.getString("flow"), r.getInteger("step"), r.getString("step_name"),
                Snapshot.Outcome.valueOf(r.getString("outcome").toUpperCase(Locale.ROOT)), Optional.ofNullable(r.getString("error")),
                r.getOffsetDateTime("started_at").toInstant(), r.getLong("wall_ns"), cpu == null ? Snapshot.CPU_UNKNOWN : cpu,
                new Headers(strings(r.getJsonObject("headers"))), new Props(r.getJsonObject("props").getMap()), new Keys(strings(r.getJsonObject("keys"))),
                new Hash(r.getString("algorithm"), r.getBuffer("body_hash").getLong(0)), r.getLong("body_size"),   // the FK guarantees the payloads row
                Optional.ofNullable(r.getString("body_key")).map(PgMessageStore::blobKey), strings(r.getJsonObject("versions")));
    }

    private static Map<String, String> strings(JsonObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        o.forEach(e -> m.put(e.getKey(), String.valueOf(e.getValue())));
        return m;
    }

    static Buffer bytes(Hash hash) {
        return Buffer.buffer(ByteBuffer.allocate(Long.BYTES).putLong(hash.value()).array());
    }

    /** {@code <message id>/<step>/<part>}; message ids are safe path segments (D-041), parts never contain a slash. */
    static String text(BlobKey key) {
        return key.messageId() + "/" + key.step() + "/" + key.part();
    }

    static BlobKey blobKey(String text) {
        int a = text.indexOf('/'), b = text.indexOf('/', a + 1);
        if (a < 1 || b < 0) throw new IllegalArgumentException("not a blob key: " + text);
        return new BlobKey(text.substring(0, a), Integer.parseInt(text.substring(a + 1, b)), text.substring(b + 1));
    }
}
