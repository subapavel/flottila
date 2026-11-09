package io.flottila.runtime.journal;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The journal's rows in Postgres (V12, CORE-3 items 5 and 9): {@code journal_nodes} and {@code journal_segments}. Each call
 * is one autocommit statement on the pool: its future succeeds once the row is committed — but {@link #giveUp} and
 * {@link #sweep}, one transaction each (V13: {@code work.lost_at}, {@code body_refs.lost_at}, {@code journal_losses}). A segment's {@code copies} is
 * the node alone until CORE-5 replicates. The pins are CORE-3 item 10's: a work row's ref not lost, an unarchived kept ref not
 * lost (a DLQ row pins through its kept ref, which is one of those). At recovery ({@link #pinsBeyond}) a kept ref pins only while
 * its segment row is registered — the sweep let an expired one go with its segment (FIX-140); a work row always pins.
 *
 * @since 0.1
 */
public final class PgSegmentRows implements SegmentRows {

    /**
     * $1 the node; $2, $3 the segments held and the end of each one's records. A work row pins unconditionally; a kept ref only
     * while its segment row is registered (FIX-140): the sweep deleted a swept segment's row under its own pin check, so a ref
     * left on it expired, it is not lost. No foreign key keeps a ref off a swept segment; per branch of TieredBlobStore's writes:
     * {@code REFERENCE}'s {@code kept} and {@code src} CTEs and {@code KEEP}'s conflict branch (its {@code seg} CTE) hold the
     * segment row {@code for key share}, so no sweep deletes it under them. {@code KEEP}'s fresh insert and its {@code GONE}
     * replace take no lock: they point at the active segment, never a sweep candidate (SegmentSweeper's {@code sealedBy}), and
     * their save is in the open-save set the sweeper reads before it asks the pins; once committed, the fresh
     * {@code created_at} pins. Except one window, not guarded: a memory body's append returns before {@code opened(a.ref())}
     * puts its save in the open-save set (TieredBlobStore's {@code durably}); if the segment seals and the grace passes in that
     * gap, the sweep can delete the segment row and the ref then lands on a swept segment, which this query no longer reports
     * at restart — accepted silently, not refused (FIX-150 closes the gap).
     */
    private static final String PINS_BEYOND = """
            with e(segment, ends) as (select * from unnest($2::bigint[], $3::bigint[]))
            select w.seq, w.id, 0, 'body', w.body_segment, w.body_position, w.body_length from work w
             where w.body_node = $1 and w.body_segment is not null and w.lost_at is null
               and not exists (select 1 from e where e.segment = w.body_segment and w.body_position + w.body_length <= e.ends)
            union all
            select -1, r.message_id, r.step, r.part, r.segment, r.position, r.length from body_refs r
             where r.node = $1 and r.archived_at is null and r.lost_at is null
               and exists (select 1 from journal_segments s where s.node = $1 and s.segment = r.segment)
               and not exists (select 1 from e where e.segment = r.segment and r.position + r.length <= e.ends)""";

    /**
     * Node $1's pinned segments (CORE-3 items 10, 14), worked out once per statement (FIX-127): a materialized CTE reads
     * {@code work} and {@code body_refs} in one scan each, so the check costs |work| + |candidates|, never their product (a
     * correlated {@code exists} ran a scan of {@code work} per candidate). $2 the candidates, $3 the instant a kept ref must be
     * created after.
     */
    private static final String PINNED = """
            with pinned(segment) as materialized (
                select w.body_segment from work w where w.body_node = $1 and w.lost_at is null
                union
                select r.segment from body_refs r where r.node = $1 and r.segment = any($2) and r.archived_at is null and r.lost_at is null
                   and (r.created_at > $3 or exists (select 1 from dlq d where d.body_key = r.message_id || '/' || r.step || '/' || r.part)))
            """;
    /** Segment {@code s.segment} not in the pinned set. */
    private static final String UNPINNED = " and not exists (select 1 from pinned p where p.segment = s.segment)";
    /** Of node $1's candidates $2, the unpinned rows, locked; a row another transaction holds (a reference insert's key share) is passed. */
    private static final String LOCK_UNPINNED = PINNED + "select s.segment from journal_segments s where s.node = $1 and s.segment = any($2)"
            + UNPINNED + " for update of s skip locked";
    /** Of the rows locked ($2), those still unpinned under this statement's later snapshot, deleted. */
    private static final String DELETE_UNPINNED = PINNED + "delete from journal_segments s where s.node = $1 and s.segment = any($2)" + UNPINNED
            + " returning s.segment";
    /** $1 the node; $2, $3 each given-up segment and its first given-up position: the rows in those extents. */
    private static final String EXTENTS = " join unnest($2::bigint[], $3::bigint[]) as g(segment, since) on %1$s = g.segment and %2$s >= g.since";
    private static final String LOCK_REFS = "select 1 from body_refs r" + EXTENTS.formatted("r.segment", "r.position")
            + " where r.node = $1 and r.lost_at is null for update of r";
    private static final String LOCK_WORK = "select 1 from work w" + EXTENTS.formatted("w.body_segment", "w.body_position")
            + " where w.body_node = $1 and w.lost_at is null for update of w";
    private static final String LOSE_WORK = "update work w set lost_at = now() from unnest($2::bigint[], $3::bigint[]) as g(segment, since)"
            + " where w.body_node = $1 and w.body_segment = g.segment and w.body_position >= g.since and w.lost_at is null";
    private static final String LOSE_REFS = "update body_refs r set lost_at = now() from unnest($2::bigint[], $3::bigint[]) as g(segment, since)"
            + " where r.node = $1 and r.segment = g.segment and r.position >= g.since and r.lost_at is null";

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

    /** {@link #giveUp(String, UUID, String, List, Map, Replacement)} with no ends: each pin's segment given up from 0. */
    public Future<Void> giveUp(String node, UUID journal, String digest, List<Pin> pins, Replacement replacement) {
        return giveUp(node, journal, digest, pins, Map.of(), replacement);
    }

    /**
     * {@inheritDoc} One transaction; every mark and the record take its {@code now()}, the transaction's start: one instant. The
     * kept refs of the given-up extents are locked first — {@code for update} waits for a reference insert holding one
     * {@code for key share} —, then the work rows, each statement under a snapshot taken after the locks before it, so a copy
     * committed meanwhile is marked too.
     */
    @Override
    public Future<Void> giveUp(String node, UUID journal, String digest, List<Pin> pins, Map<Long, Long> ends, Replacement replacement) {
        Map<Long, Long> since = new TreeMap<>();   // each pin segment's first given-up position: its cut
        for (Pin p : pins) since.put(p.segment(), ends.getOrDefault(p.segment(), 0L));
        Tuple extents = Tuple.tuple().addString(node).addArrayOfLong(since.keySet().toArray(Long[]::new))
                .addArrayOfLong(since.values().toArray(Long[]::new));
        return pool.withTransaction(tx -> Future.succeededFuture()   // a chain from a completed future: a throw in it rolls back
                .compose(v -> tx.preparedQuery(LOCK_REFS).execute(extents))
                .compose(v -> tx.preparedQuery(LOCK_WORK).execute(extents))
                .compose(v -> tx.preparedQuery(LOSE_WORK).execute(extents))
                .compose(v -> tx.preparedQuery(LOSE_REFS).execute(extents))
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

    /**
     * One transaction: the unpinned candidates locked {@code for update skip locked} (a row a reference insert holds is passed),
     * then deleted where still unpinned under the delete's own, later snapshot (a pin committed since the lock is seen).
     */
    @Override
    public Future<Set<Long>> sweep(String node, Set<Long> candidates, Instant keptSince) {
        Object kept = keptSince.atOffset(ZoneOffset.UTC);
        return pool.withTransaction(tx -> Future.succeededFuture()   // a chain from a completed future: a throw in it rolls back
                .compose(v -> tx.preparedQuery(LOCK_UNPINNED).execute(Tuple.tuple().addString(node).addArrayOfLong(candidates.toArray(Long[]::new)).addValue(kept)))
                .compose(locked -> {
                    List<Long> held = new ArrayList<>();
                    for (Row r : locked) held.add(r.getLong(0));
                    return tx.preparedQuery(DELETE_UNPINNED).execute(Tuple.tuple().addString(node).addArrayOfLong(held.toArray(Long[]::new)).addValue(kept));
                })
                .map(deleted -> {
                    Set<Long> gone = new TreeSet<>();
                    for (Row r : deleted) gone.add(r.getLong(0));
                    return gone;
                }));
    }

    @Override
    public Future<Void> drop(String node, Set<Long> segments) {
        return pool.preparedQuery("delete from journal_segments where node = $1 and segment = any($2)")
                .execute(Tuple.tuple().addString(node).addArrayOfLong(segments.toArray(Long[]::new))).mapEmpty();
    }
}
