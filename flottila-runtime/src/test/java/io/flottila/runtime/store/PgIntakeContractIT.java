package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.flow.Intake;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.PgBodies;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2-08a (D-082): runtime's {@code PgIntake} — body, record and item in one Postgres transaction — passes the intake contract
 * over the node's stores: the {@link TieredBlobStore} with its Postgres tier ({@link PgBodies}, bound {@link #BOUND}), the
 * {@link PgMessageStore} and the {@link PgWorkQueue}, one pool of ONE connection ({@link PgIntakeIT#pool}): a statement of a save
 * on the pool instead of its transaction fails the case. Every case runs on a database of its own (FIX-5).
 *
 * <p>The shape assumed: {@code io.flottila.runtime.store.PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue) implements Intake}.
 */
class PgIntakeContractIT extends IntakeContract {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path spool;
    Pool pool;
    PgMessageStore messages;

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

    @AfterEach
    void closeTheStores() {
        if (messages != null) messages.close();
        if (pool != null) pool.close().await();
    }

    @Override
    protected World emptyWorld() {
        String database = PgIntakeIT.caseDatabase(pg, "intake_contract_" + cases.incrementAndGet());
        pool = PgIntakeIT.pool(vertx, pg, database);
        TieredBlobStore blobs = PgIntakeIT.blobs(spool, pool);
        messages = new PgMessageStore(pool, new VertxTimer(vertx), BatchingWriter.Options.defaults());
        PgIntake intake = new PgIntake(pool, blobs, messages, new PgWorkQueue(pool));
        Pool reader = pool;
        return new World() {
            @Override public Intake intake() { return intake; }

            @Override public BlobStore bodies() { return blobs; }

            @Override public Optional<Buffer> durable(BlobKey key) {
                for (Row r : reader.preparedQuery("select bytes from bodies where message_id = $1 and step = $2 and part = $3")
                        .execute(Tuple.of(key.messageId(), key.step(), key.part())).await()) {
                    return Optional.of(r.getBuffer(0));
                }
                return Optional.empty();
            }

            @Override public List<Snapshot> recorded(String messageId) { return messages.timeline(messageId).await(); }

            @Override public List<Queued> queued() {
                List<Queued> out = new ArrayList<>();
                for (Row r : reader.query("select id, flow, step, partition_key from work order by seq").execute().await()) {
                    out.add(new Queued(r.getString("id"), r.getString("flow"), r.getInteger("step"), r.getString("partition_key")));
                }
                return out;
            }
        };
    }
}
