package io.flottila.runtime.journal;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The journal's rows in Postgres (V12, CORE-3 items 5 and 9): {@code journal_nodes} and {@code journal_segments}. Each call
 * is one autocommit statement on the pool: its future succeeds once the row is committed — but {@link #giveUp}, one
 * transaction (V13: {@code work.lost_at}, {@code body_refs.lost_at}, {@code journal_losses}). A segment's {@code copies} is
 * the node alone until CORE-5 replicates. The pins are CORE-3 item 10's: a work row's ref not lost, an unarchived kept ref not
 * lost (a DLQ row pins through its kept ref, which is one of those).
 *
 * @since 0.1
 */
public final class PgSegmentRows implements SegmentRows {

    /** $1 the node; $2, $3 the segments held and the end of each one's records. */
    private static final String PINS_BEYOND = """
            with e(segment, ends) as (select * from unnest($2::bigint[], $3::bigint[]))
            select w.seq, w.id, 0, 'body', w.body_segment, w.body_position, w.body_length from work w
             where w.body_node = $1 and w.body_segment is not null and w.lost_at is null
               and not exists (select 1 from e where e.segment = w.body_segment and w.body_position + w.body_length <= e.ends)
            union all
            select -1, r.message_id, r.step, r.part, r.segment, r.position, r.length from body_refs r
             where r.node = $1 and r.archived_at is null and r.lost_at is null
               and not exists (select 1 from e where e.segment = r.segment and r.position + r.length <= e.ends)""";

    private final Pool pool;

    public PgSegmentRows(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<Void> insert(String node, long segment) {
        return pool.preparedQuery("insert into journal_segments(node, segment, copies) values ($1, $2, array[$1::text]) on conflict (node, segment) do nothing")
                .execute(Tuple.of(node, segment)).mapEmpty();
    }

    @Override
    public Future<UUID> journalOf(String node) {
        return pool.preparedQuery("select journal_id from journal_nodes where node = $1").execute(Tuple.of(node))
                .map(rows -> rows.size() == 0 ? null : rows.iterator().next().getUUID(0));
    }

    /** A plain insert: a row registered meanwhile (another start of this node id elsewhere) fails it, never overwritten. */
    @Override
    public Future<Void> register(String node, UUID journal) {
        return pool.preparedQuery("insert into journal_nodes(node, journal_id) values ($1, $2)").execute(Tuple.of(node, journal)).mapEmpty();
    }

    @Override
    public Future<Set<Long>> segments(String node) {
        return pool.preparedQuery("select segment from journal_segments where node = $1").execute(Tuple.of(node)).map(rows -> {
            Set<Long> numbers = new HashSet<>();
            for (Row r : rows) numbers.add(r.getLong(0));
            return numbers;
        });
    }

    @Override
    public Future<List<Pin>> pinsBeyond(String node, Map<Long, Long> ends) {
        Long[] segments = ends.keySet().toArray(Long[]::new);
        Long[] limits = new Long[segments.length];
        for (int i = 0; i < segments.length; i++) limits[i] = ends.get(segments[i]);
        return pool.preparedQuery(PINS_BEYOND).execute(Tuple.tuple().addString(node).addArrayOfLong(segments).addArrayOfLong(limits)).map(rows -> {
            List<Pin> pins = new ArrayList<>();
            for (Row r : rows) pins.add(new Pin(r.getLong(0), r.getString(1), r.getInteger(2), r.getString(3), r.getLong(4), r.getLong(5), r.getLong(6)));
            return pins;
        });
    }

    @Override
    public Future<Boolean> accepted(String node, String value) {
        return pool.preparedQuery("select exists(select 1 from journal_losses where node = $1 and journal_id::text || ':' || digest = $2)")
                .execute(Tuple.of(node, value)).map(rows -> rows.iterator().next().getBoolean(0));
    }

    /** One transaction; every mark and the record take its {@code now()}, the transaction's start: one instant. */
    @Override
    public Future<Void> giveUp(String node, UUID journal, String digest, List<Pin> pins, Replacement replacement) {
        List<Long> seqs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<Integer> steps = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (Pin p : pins) {
            if (p.kept()) {
                ids.add(p.messageId());
                steps.add(p.step());
                parts.add(p.part());
            } else {
                seqs.add(p.seq());
            }
        }
        return pool.withTransaction(tx -> Future.succeededFuture()   // a chain from a completed future: a throw in it rolls back
                .compose(v -> tx.preparedQuery("update work set lost_at = now() where seq = any($1) and lost_at is null")
                        .execute(Tuple.tuple().addArrayOfLong(seqs.toArray(Long[]::new))))
                .compose(v -> tx.preparedQuery("update body_refs set lost_at = now() where lost_at is null and (message_id, step, part) in "
                                + "(select * from unnest($1::text[], $2::int[], $3::text[]))")
                        .execute(Tuple.tuple().addArrayOfString(ids.toArray(String[]::new)).addArrayOfInteger(steps.toArray(Integer[]::new))
                                .addArrayOfString(parts.toArray(String[]::new))))
                .compose(v -> tx.preparedQuery("insert into journal_losses(node, journal_id, digest, accepted_at) values ($1, $2, $3, now())")
                        .execute(Tuple.of(node, journal, digest)))
                .compose(v -> replacement == null ? Future.succeededFuture() : replace(tx, node, replacement))
                .mapEmpty());
    }

    /** The node's journal id replaced (a plain insert when none was registered) and the old journal's segment rows deleted. */
    private static Future<?> replace(SqlConnection tx, String node, Replacement r) {
        Future<?> row = r.registered() == null
                ? tx.preparedQuery("insert into journal_nodes(node, journal_id) values ($1, $2)").execute(Tuple.of(node, r.now()))
                : tx.preparedQuery("update journal_nodes set journal_id = $3 where node = $1 and journal_id = $2").execute(Tuple.of(node, r.registered(), r.now()));
        return row.compose(v -> tx.preparedQuery("delete from journal_segments where node = $1").execute(Tuple.of(node)));
    }

    @Override
    public Future<Void> drop(String node, Set<Long> segments) {
        return pool.preparedQuery("delete from journal_segments where node = $1 and segment = any($2)")
                .execute(Tuple.tuple().addString(node).addArrayOfLong(segments.toArray(Long[]::new))).mapEmpty();
    }
}
