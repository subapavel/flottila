package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.flow.Intake;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.PgBodies;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.PostgresImage;
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
 * <p>The shape assumed: {@code io.flottila.runtime.store.PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue) implements Intake};
 * P2-16a: {@code PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue, Workers)}, its hand-offs leased to the queue's node.
 *
 * <p>Expectation changed by CORE-4c2 (D-105): the body store's durable tier is the node's journal; "made durable" is the key's
 * kept ref ({@code body_refs}, the contract saving with the input kept) read back from the journal by that ref — never a
 * {@code bodies} row, which takes no inserts any more.
 */
class PgIntakeContractIT extends IntakeContract {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path spool;
    Pool pool;
    Pool rows;   // the journal's own rows: not the intake's one connection
    io.flottila.runtime.journal.TestJournals.Opened journal;
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
    void closeTheStores() throws Exception {
        if (messages != null) messages.close();
        if (journal != null) journal.close();
        if (rows != null) rows.close().await();
        if (pool != null) pool.close().await();
    }

    @Override
    protected World emptyWorld() {
        String database = PgIntakeIT.caseDatabase(pg, "intake_contract_" + cases.incrementAndGet());
        pool = PgIntakeIT.pool(vertx, pg, database);
        rows = PgIntakeIT.pool(vertx, pg, database, 2);
        try {
            journal = io.flottila.runtime.journal.TestJournals.open(rows, spool, "node-1");
        } catch (Exception e) {
            throw new IllegalStateException("fixture: the journal did not open", e);
        }
        TieredBlobStore blobs = PgIntakeIT.blobs(journal, pool);
        messages = new PgMessageStore(pool, new VertxTimer(vertx), BatchingWriter.Options.defaults());
        Workers workers = new Workers();
        PgWorkQueue queue = new PgWorkQueue(pool, "node-1");
        PgIntake intake = new PgIntake(pool, blobs, messages, queue, workers);   // P2-16a: the node's registry; hand-offs leased to the queue's node
        io.flottila.runtime.journal.Journal opened = journal.journal();
        Pool reader = pool;
        PgMessageStore store = messages;
        return new World() {
            @Override public Intake intake() { return intake; }

            @Override public BlobStore bodies() { return blobs; }

            @Override public Optional<Buffer> durable(BlobKey key) {
                for (Row r : reader.preparedQuery("select segment, position, length from body_refs where message_id = $1 and step = $2 and part = $3")
                        .execute(Tuple.of(key.messageId(), key.step(), key.part())).await()) {
                    io.flottila.runtime.journal.JournalRef ref = new io.flottila.runtime.journal.JournalRef(r.getLong(0), r.getLong(1), r.getLong(2));
                    try {
                        return Optional.of(Buffer.buffer(io.flottila.runtime.node.OnVirtualThread.call(() -> opened.read(ref))));
                    } catch (Exception e) {
                        throw new IllegalStateException("the kept ref of " + key + " does not read back", e);
                    }
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

            @Override public Workers workers() { return workers; }

            @Override public MessageStore messages() { return store; }

            @Override public WorkQueue queue() { return queue; }

            @Override public String node() { return "node-1"; }

            @Override public Optional<String> leaseHolder(String messageId) {
                for (Row r : reader.preparedQuery("select locked_by from work where id = $1 and locked_until > now()").execute(Tuple.of(messageId)).await()) {
                    return Optional.ofNullable(r.getString(0));
                }
                return Optional.empty();
            }

            @Override public int attempts(String messageId) {
                for (Row r : reader.preparedQuery("select attempts from work where id = $1").execute(Tuple.of(messageId)).await()) return r.getInteger(0);
                throw new AssertionError("no item of " + messageId);
            }
        };
    }
}
