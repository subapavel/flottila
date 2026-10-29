package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Key;
import io.flottila.api.Runtime;
import io.flottila.components.sftp.EmbeddedSftp;
import io.flottila.components.sftp.SftpComponent;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.flow.Delivery;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.Replay;
import io.flottila.core.record.Meter;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgIntake;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02D done-when (D-084): a file the real sftp consumer delivers twice — its removal refused by the server after the durable
 * save answered — is one message and one work item. The node's own pieces: the runtime's tiered body store with its Postgres
 * tier, {@code PgIntake} over Postgres, an at-least-once flow built with the dedupe key the sftp component names, its worker
 * over the Postgres queue, a mock partner. Round 1 saves the file as {@code sd-1} and its removal is refused: the file stays.
 * Round 2 delivers it again as {@code sd-2}: the save finds its file key taken and answers {@code sd-1}; the removal goes
 * through. One ingress row, one item, one send. A replay of the message is a new message: it never passes the intake, so is
 * never deduped.
 *
 * <p>The shape assumed: {@code Component.dedupeBy(EndpointConfig)}, {@code FlowBuilder.delivery(FlowDefinition, Path,
 * Optional<Key<String>>)}, {@code PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue)}; EmbeddedSftp's
 * {@code onNextRemoving} and {@code events()} (public for this case).
 */
@Timeout(120)
class SftpIngressDedupeIT {

    static final Path DEMO = Path.of("../packages/demo");
    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;

    @TempDir Path root;
    @TempDir Path spool;

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final List<Ack> answers = new CopyOnWriteArrayList<>();
    EmbeddedSftp sftp;
    PgMessageStore messages;
    io.flottila.api.Consumer consumer;
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
        TieredBlobStore blobs = DeliveryModesIT.tiered(spool, pool);
        messages = new PgMessageStore(pool, new VertxTimer(vertx), BatchingWriter.Options.defaults());
        PgWorkQueue queue = new PgWorkQueue(pool);
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
            pool.query("truncate message_keys, messages, payloads, dlq, work, bodies, processed_ids").execute().await();
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
        assertEquals(List.of("sd-1"), column("select distinct message_id from bodies order by message_id"), "only sd-1's body is durable");

        assertEquals(1, worker.poll().await(), "the worker runs the one item");
        assertEquals(List.of("sd-1"), partner.sentTo("partner").stream().map(Envelope::id).toList(), "one send to the partner");
        assertEquals(0, worker.poll().await(), "nothing else is queued");
        assertEquals(1, partner.sentTo("partner").size(), "still one send");
        assertEquals(0, count("select count(*) from work"), "the item is completed");
    }

    @Test
    void replayOfADedupedMessageRunsAsANewMessage() throws Exception {
        sftp.drop("/inbox", "order.xml", Buffer.buffer("<order id=\"2\"/>"));
        sftp.onNextRemoving((session, path) -> {
            throw new IOException("the test refuses the first removal");
        });
        round();
        round();
        assertEquals(List.of(new Ack.Accepted("sd-1"), new Ack.Accepted("sd-1")), answers, "fixture: delivered twice, one message");
        assertEquals(1, worker.poll().await(), "fixture: the message ran");
        messages.flush().await();

        Replay replay = builder.replay(def, DEMO, delivery);
        try {
            OnVirtualThread.call(() -> replay.from("sd-1", 1));
        } finally {
            replay.close().await();
        }

        List<Envelope> sent = partner.sentTo("partner");
        assertEquals(2, sent.size(), "the replay ran and sent again, though the file key it carries is taken: never deduped — " + sent);
        Envelope replayed = sent.get(1);
        assertNotEquals("sd-1", replayed.id(), "a replay is a message of its own");
        assertEquals(Optional.of("sd-1"), replayed.get(Envelope.REPLAY_OF), "marked as the replay of sd-1");
        assertEquals(sent.get(0).get(SftpComponent.FILE_KEY), replayed.get(SftpComponent.FILE_KEY), "fixture: with the file key it was recorded with");
        assertEquals(List.of("sd-1"), column("select message_id from processed_ids"), "the replay never passed the intake: no key row of its own");
        assertEquals(0, count("select count(*) from work"), "nor an item");
    }
}
