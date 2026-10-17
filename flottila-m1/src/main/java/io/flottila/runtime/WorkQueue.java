package io.flottila.runtime;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent work queue between durable ingress and the pipeline (at-least-once flows only).
 * Interface so NATS/other can replace Postgres without touching stages.
 */
public interface WorkQueue {

    record Item(long seq, String messageId, String flow, int step, String partitionKey) {}

    Uni<Void> enqueue(String messageId, String flow, int step, String partitionKey);
    /** Claim up to max items for lease. Per partition_key strictly serial; null key = no ordering. */
    Uni<List<Item>> claim(String workerId, String flow, int max, Duration lease);
    Uni<Void> complete(long seq);

    /**
     * SELECT ... FOR UPDATE SKIP LOCKED. A row is claimable when its lease is free AND it is the
     * oldest row of its partition_key AND that key holds no active lease — the next row of a key
     * becomes eligible only after the previous one is completed (deleted) or its lease expired.
     */
    final class Postgres implements WorkQueue {
        private static final String ENQUEUE =
                "insert into work(id, flow, step, partition_key) values ($1, $2, $3, $4)";
        private static final String CLAIM = """
                update work w set locked_by = $1, locked_until = now() + make_interval(secs => $4)
                where w.seq in (
                  select w1.seq from work w1
                  where w1.flow = $2
                    and w1.available_at <= now()
                    and (w1.locked_until is null or w1.locked_until < now())
                    and (w1.partition_key is null or (
                          not exists (select 1 from work w2 where w2.flow = $2
                                        and w2.partition_key = w1.partition_key and w2.locked_until >= now())
                      and not exists (select 1 from work w3 where w3.flow = $2
                                        and w3.partition_key = w1.partition_key and w3.seq < w1.seq)))
                  order by w1.seq
                  limit $3
                  for update skip locked)
                returning seq, id, flow, step, partition_key""";
        private static final String COMPLETE = "delete from work where seq = $1";

        private final PgPool pool;

        public Postgres(PgPool pool) { this.pool = pool; }

        public Uni<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
            return pool.preparedQuery(ENQUEUE).execute(Tuple.of(messageId, flow, step, partitionKey)).replaceWithVoid();
        }

        public Uni<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
            return pool.preparedQuery(CLAIM)
                    .execute(Tuple.of(workerId, flow, max, lease.toMillis() / 1000.0))
                    .onItem().transform(rs -> {
                        List<Item> items = new ArrayList<>();
                        for (Row r : rs) items.add(new Item(r.getLong("seq"), r.getString("id"),
                                r.getString("flow"), r.getInteger("step"), r.getString("partition_key")));
                        return items;
                    });
        }

        public Uni<Void> complete(long seq) {
            return pool.preparedQuery(COMPLETE).execute(Tuple.of(seq)).replaceWithVoid();
        }
    }
}
