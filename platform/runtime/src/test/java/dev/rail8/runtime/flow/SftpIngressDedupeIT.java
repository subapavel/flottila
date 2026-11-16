package dev.rail8.runtime.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Key;
import dev.rail8.api.Runtime;
import dev.rail8.components.sftp.EmbeddedSftp;
import dev.rail8.components.sftp.SftpComponent;
import dev.rail8.core.batch.BatchingWriter;
import dev.rail8.core.flow.Delivery;
import dev.rail8.core.flow.FlowBuilder;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.FlowWorker;
import dev.rail8.core.record.Meter;
import dev.rail8.runtime.blob.TieredBlobStore;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.journal.TestJournals;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.runtime.store.PgIntake;
import dev.rail8.runtime.store.PgMessageStore;
import dev.rail8.runtime.time.VertxTimer;
import dev.rail8.testkit.FakeIds;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.PostgresImage;
import dev.rail8.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02D done-when (D-084): a file the real sftp consumer delivers twice — its removal refused by the server after the durable
 * save answered — is one message and one work item. The node's own pieces: the runtime's tiered body store over the node's
 * journal (CORE-4c2: the durable body is a journal record named by a {@code body_refs} row, no {@code bodies} row), {@code PgIntake} over Postgres, an at-least-once flow built with the dedupe key the sftp component names, its worker
 * over the Postgres queue, a mock partner. Round 1 saves the file as {@code sd-1} and its removal is refused: the file stays.
 * Round 2 delivers it again as {@code sd-2}: the save finds its file key taken and answers {@code sd-1}; the removal goes
 * through. One ingress row, one item, one send.
 *
 * <p>The shape assumed: {@code Component.dedupeBy(EndpointConfig)}, {@code FlowBuilder.delivery(FlowDefinition, Path,
 * Optional<Key<String>>)}, {@code PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue)}; EmbeddedSftp's
 * {@code onNextRemoving} and {@code events()} (public for this case).
 */
@Timeout(120)
class SftpIngressDedupeIT {

    static final Path DEMO = Path.of("../../examples/demo");
    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool pool;

    @TempDir Path root;
    @TempDir Path data;
    TestJournals.Opened journal;

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final List<Ack> answers = new CopyOnWriteArrayList<>();
    EmbeddedSftp sftp;
    PgMessageStore messages;
    dev.rail8.api.Consumer consumer;
    FlowDefinition def;
    FlowBuilder builder;
    Delivery delivery;
    FlowWorker worker;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() throws IOException {
        sftp = EmbeddedSftp.start(root);
        sftp.mkdir("/inbox");
        journal = DeliveryModesIT.journal(data, pool);
        TieredBlobStore blobs = DeliveryModesIT.tiered(journal, pool);
        messages = new PgMessageStore(pool, new VertxTimer(vertx), BatchingWriter.Options.defaults());
        PgWorkQueue queue = new PgWorkQueue(pool, "node-a");
        rt.withComponent(partner);
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "sftp-in"));
        doc.put("delivery", Map.of("mode", "at-least-once"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536));
        doc.put("steps", List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        def = FlowDefinition.from(doc);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), blobs, rt.hasher(), Map.of(partner.scheme(), partner));
        builder = new FlowBuilder(runtime, List.of(), List.of(), Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of())))
                .recording(messages, Meter.system()).queue(queue).dlq(new PgDlq(pool)).intake(new PgIntake(pool, blobs, messages, queue));
        SftpComponent component = new SftpComponent(blobs, new FakeIds("sd"), rt.timer(), rt.clock());
        EndpointConfig in = sftp.endpoint("sftp-in", "/inbox", Map.of("intervalMs", 1000, "flow", def.id(), "maxBodyBytes", def.maxBodyBytes()));
        Optional<Key<String>> dedupe = component.dedupeBy(in);
        assertEquals(Optional.of(SftpComponent.FILE_KEY), dedupe, "fixture: the sftp component dedupes by its file key");
        delivery = builder.delivery(def, DEMO, dedupe);
        worker = builder.worker(def, DEMO, "w1");
        Function<Envelope, Future<Ack>> recorded = e -> delivery.apply(e).onSuccess(answers::add);
        consumer = component.consumer(in, recorded);
        consumer.start().await();
    }

    @AfterEach
    void close() throws IOException {
        try {
            if (consumer != null) consumer.stop().await();
            if (messages != null) messages.close();
            if (journal != null) journal.close();
            pool.query("truncate message_keys, messages, dlq, work, bodies, body_refs, journal_segments, journal_nodes, processed_ids").execute().await();
        } finally {
            sftp.close();
        }
    }

    static boolean soon(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    /**
     * Fires the consumer's next round and waits until it is over: its file answered — the save's timeout, on the same timer, is
     * cancelled by then — and the round after it scheduled.
     */
    void round() {
        int answered = answers.size();
        assertTrue(soon(() -> rt.timer().pending() > 0), "a round is scheduled");
        rt.clock().advance(rt.timer().nextDelay());
        assertTrue(soon(() -> answers.size() > answered && rt.timer().pending() > 0), "the round ended: its file answered, the next one scheduled");
    }

    static long count(String sql) {
        return pool.query(sql).execute().await().iterator().next().getLong(0);
    }

    static List<String> column(String sql) {
        List<String> out = new ArrayList<>();
        for (Row r : pool.query(sql).execute().await()) out.add(r.getString(0));
        return out;
    }

    long removes() {
        return sftp.events().stream().filter(e -> e.equals("remove /inbox/order.xml")).count();
    }

    @Test
    void aFileDeliveredTwiceIsOneMessageAndOneWorkItem() {
        sftp.drop("/inbox", "order.xml", Buffer.buffer("<order id=\"1\"/>"));
        sftp.onNextRemoving((session, path) -> {
            throw new IOException("the test refuses the first removal");
        });

        round();
        assertEquals(List.of(new Ack.Accepted("sd-1")), answers, "round 1: the file was saved as sd-1 and accepted");
        assertEquals(1, removes(), "fixture: its removal was asked, and refused");
        assertEquals(Set.of("order.xml"), sftp.files("/inbox"), "the refused removal left the file");

        round();
        assertEquals(List.of(new Ack.Accepted("sd-1"), new Ack.Accepted("sd-1")), answers,
                "round 2: the file delivered again (as sd-2) is accepted as the message it already is");
        assertEquals(2, removes(), "its removal asked again");
        assertTrue(soon(() -> sftp.files("/inbox").isEmpty()), "and the file is gone: " + sftp.files("/inbox"));

        assertEquals(List.of("sd-1"), column("select id from messages where step = 0 order by id"), "one ingress row: sd-2 was never recorded");
        assertEquals(List.of("sd-1"), column("select id from work order by seq"), "one work item");
        assertEquals(List.of("sd-1"), column("select message_id from processed_ids"), "one key row, naming the first message");
        assertEquals(List.of("sd-1"), column("select distinct message_id from body_refs order by message_id"), "only sd-1's body is durable");
        assertEquals(List.of(), column("select distinct message_id from bodies"), "as a kept ref: no bodies row (CORE-4c2)");

        assertEquals(1, worker.poll().await(), "the worker runs the one item");
        assertEquals(List.of("sd-1"), partner.sentTo("partner").stream().map(Envelope::id).toList(), "one send to the partner");
        assertEquals(0, worker.poll().await(), "nothing else is queued");
        assertEquals(1, partner.sentTo("partner").size(), "still one send");
        assertEquals(0, count("select count(*) from work"), "the item is completed");
    }
}
