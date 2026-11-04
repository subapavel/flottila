package io.flottila.runtime.journal;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The journal's rows in Postgres (V12, CORE-3 items 5 and 9): {@code journal_nodes} and {@code journal_segments}. Each call
 * is one autocommit statement on the pool: its future succeeds once the row is committed. A segment's {@code copies} is the
 * node alone until CORE-5 replicates.
 *
 * @since 0.1
 */
public final class PgSegmentRows implements SegmentRows {

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
    public Future<Void> drop(String node, Set<Long> segments) {
        return pool.preparedQuery("delete from journal_segments where node = $1 and segment = any($2)")
                .execute(Tuple.tuple().addString(node).addArrayOfLong(segments.toArray(Long[]::new))).mapEmpty();
    }
}
