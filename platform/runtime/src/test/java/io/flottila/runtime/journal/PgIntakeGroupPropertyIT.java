package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.store.PgIntake;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-6b (D-109): the intake's group commit under any mix of ≤ 16 saves — keyed from a pool of three keys (repeats), unkeyed,
 * over two partitions, some answers decided before the call or right after it, one poison member whose item Postgres refuses,
 * waits between calls that shape the groups — on a group max of 1..8 and 1..2 groups in flight (window zero: members go when a
 * slot frees). Invariants, read from Postgres after every save settled: a save that answers its own id has its rows committed,
 * and only such saves have rows (no ack without committed rows, no rows without an ack); a save that fails has nothing and its
 * body stays local; every undecided save but the poison succeeds — a poison or a duplicate never fails its neighbours; a key has
 * one row, naming a save that answered its own id, and every save under the key answers that id; a save decided before its call
 * never answers its own id; within one transaction the items follow the call order; no save is left open.
 *
 * <p>One database and one node for every try (jqwik runs no {@code @BeforeEach}); each try has its own intake, fake clock, flow
 * (the key's scope) and id prefix.
 */
class PgIntakeGroupPropertyIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final AtomicInteger TRIES = new AtomicInteger();
    static Vertx vertx;
    static CaseDatabase db;
    static Pool pool;
    static Path dir;
    static JournalWorld a;

    @BeforeContainer
    static void start() throws Exception {
        pg.start();
        vertx = Vertx.vertx();
        db = CaseDatabase.create(pg, vertx, "intake_group_prop_").migrated();
        pool = PgBuilder.pool()
                .connectingTo(Node.connectOptions(new NodeConfig.Pg(pg.getHost(), pg.getMappedPort(5432), db.name(), pg.getUsername(), pg.getPassword(), 3)))
                .with(new PoolOptions().setMaxSize(3)).using(vertx).build();
        dir = Files.createTempDirectory("intake-group-prop");
        a = JournalWorld.open(vertx, pool, db.pool(), Files.createDirectories(dir.resolve("journal")), "node-a");
        sql("create function refuse_poison() returns trigger language plpgsql as $$ begin "
                + "if new.id like '%-poison' then raise exception 'poison refused by the test'; end if; return new; end $$");
        sql("create trigger refuse_poison before insert on work for each row execute function refuse_poison()");
        sql("create table txlog(n bigserial primary key, id text not null, txid bigint not null, seq bigint not null)");
        sql("create function log_tx() returns trigger language plpgsql as $$ begin insert into txlog(id, txid, seq) values (new.id, txid_current(), new.seq); "
                + "return null; end $$");
        sql("create trigger log_tx after insert on work for each row execute function log_tx()");
    }

    @AfterContainer
    static void stop() throws Exception {
        if (a != null) a.close();
        if (pool != null) pool.close().await();
        if (db != null) db.close();
        if (vertx != null) vertx.close().await();
        pg.stop();
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    static void sql(String statement) {
        db.pool().query(statement).execute().await();
    }

    enum Answer { UNDECIDED, DECIDED_BEFORE, DECIDED_AFTER }

    /** One save: its key (0: none, else K-1..K-3), its partition (P-0, P-1), its answer, and whether the case waits for it before the next call. */
    record Call(int key, int partition, Answer answer, boolean waitAfter) {}

    @Provide
    Arbitrary<List<Call>> calls() {
        Arbitrary<Integer> key = Arbitraries.integers().between(0, 3);
        Arbitrary<Integer> partition = Arbitraries.integers().between(0, 1);
        Arbitrary<Answer> answer = Arbitraries.frequency(net.jqwik.api.Tuple.of(6, Answer.UNDECIDED), net.jqwik.api.Tuple.of(1, Answer.DECIDED_BEFORE),
                net.jqwik.api.Tuple.of(2, Answer.DECIDED_AFTER));
        Arbitrary<Boolean> waitAfter = Arbitraries.frequency(net.jqwik.api.Tuple.of(4, false), net.jqwik.api.Tuple.of(1, true));
        return Combinators.combine(key, partition, answer, waitAfter).as(Call::new).list().ofMinSize(2).ofMaxSize(16);
    }

    @Property(tries = 30)
    void noAckWithoutCommittedRowsNoneLostDuplicatesIsolatedOrderKept(@ForAll("calls") List<Call> calls,
                                                                      @ForAll @IntRange(min = 0, max = 15) int poisonAt,
                                                                      @ForAll @IntRange(min = 1, max = 8) int max,
                                                                      @ForAll @IntRange(min = 1, max = 2) int concurrency) throws Exception {
        int t = TRIES.incrementAndGet();
        String flow = "orders-t" + t;
        String prefix = "t" + t + "-";
        int poison = poisonAt % calls.size();
        FakeTimer timer = new FakeTimer(FakeClock.at(Instant.parse("2026-11-04T10:00:00Z")));
        PgIntake intake = new PgIntake(pool, a.blobs, a.messages, a.queue, timer, new NodeConfig.IntakeGroup(max, 1L << 20, Duration.ZERO, concurrency));

        List<String> ids = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<Future<String>> saves = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Call c = calls.get(i);
            boolean isPoison = i == poison;
            String id = prefix + "m%02d".formatted(i) + (isPoison ? "-poison" : "");
            String key = isPoison || c.key() == 0 ? null : "K-" + c.key();
            Answer answer = isPoison ? Answer.UNDECIDED : c.answer();
            Envelope e = a.arrive(id, bytes(500, t * 100 + i));
            Promise<Void> decided = Promise.promise();
            if (answer == Answer.DECIDED_BEFORE) decided.complete();
            Future<String> save = OnVirtualThread.join(OnVirtualThread.start(
                    () -> intake.save(e, JournalWorld.ingress(e), flow, "P-" + c.partition(), key, true, decided.future())));
            if (answer == Answer.DECIDED_AFTER) decided.complete();
            if (c.waitAfter()) settled(save);
            ids.add(id);
            keys.add(key);
            saves.add(save);
        }
        for (Future<String> save : saves) settled(save);

        String context = "calls " + calls + ", poison " + poison + ", max " + max + ", concurrency " + concurrency + ", outcomes " + saves;
        Map<String, Integer> callIndex = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) callIndex.put(ids.get(i), i);
        Set<String> ownIds = new TreeSet<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Future<String> save = saves.get(i);
            boolean isPoison = i == poison;
            Answer answer = isPoison ? Answer.UNDECIDED : calls.get(i).answer();
            BlobKey body = BlobKey.body(id, 0);
            if (save.succeeded() && id.equals(save.result())) {
                ownIds.add(id);
                assertEquals(1, rows("messages", "id", id), id + " answered its own id: its ingress row committed — " + context);
                assertEquals(1, rows("work", "id", id), id + " answered its own id: its item committed — " + context);
                assertEquals(1, rows("body_refs", "message_id", id), id + " answered its own id: its kept ref committed — " + context);
                assertTrue(a.blobs.location(body).isEmpty(), id + ": committed, its local copy gone — " + context);
                assertNotEquals(Answer.DECIDED_BEFORE, answer, id + ": decided before its call, it answered its own id — " + context);
            } else if (save.succeeded()) {
                String first = save.result();
                assertTrue(keys.get(i) != null, id + ": an unkeyed save answered another id " + first + " — " + context);
                assertTrue(callIndex.containsKey(first), id + " answered " + first + ", no save of this try — " + context);
                assertEquals(first, saves.get(callIndex.get(first)).result(), id + " answered " + first + ", which did not answer its own id — " + context);
                assertEquals(List.of(first), keyRows(flow, keys.get(i)), id + ": its key names the id it answered — " + context);
                nothingOf(id, context);
                assertTrue(a.blobs.location(body).isEmpty(), id + ": a duplicate, its local copy evicted — " + context);
            } else {
                nothingOf(id, context);
                assertTrue(a.blobs.location(body).isPresent(), id + ": failed, its body stays local — " + context);
                if (isPoison) {
                    assertTrue(causes(save.cause()).stream().anyMatch(c -> c.contains("poison refused by the test")),
                            id + ": the poison fails with its own refusal: " + causes(save.cause()) + " — " + context);
                } else if (answer == Answer.UNDECIDED) {
                    fail(id + ": undecided and no poison, yet failed — a neighbour failed it: " + causes(save.cause()) + " — " + context);
                }
            }
        }
        assertTrue(saves.get(poison).failed(), "the poison never saves — " + context);

        for (String key : new TreeSet<>(keys.stream().filter(java.util.Objects::nonNull).toList())) {
            List<String> named = keyRows(flow, key);
            List<String> answered = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) if (key.equals(keys.get(i)) && saves.get(i).succeeded()) answered.add(saves.get(i).result());
            assertTrue(named.size() <= 1, key + ": one row at most: " + named + " — " + context);
            if (!answered.isEmpty()) {
                assertEquals(1, named.size(), key + ": saves answered " + answered + ", so its row is there — " + context);
                assertTrue(ownIds.contains(named.getFirst()), key + ": its row names " + named + ", a save that answered its own id — " + context);
                for (String id : answered) assertEquals(named.getFirst(), id, key + ": every save under it answers its row's id — " + context);
            }
        }

        assertEquals(ownIds, committedItems(prefix), "items committed exactly for the saves that answered their own id — " + context);
        for (Map.Entry<Long, List<String>> tx : itemsByTransaction(prefix).entrySet()) {
            List<String> inTx = tx.getValue();
            List<String> byCall = new ArrayList<>(inTx);
            byCall.sort(java.util.Comparator.comparing(callIndex::get));
            assertEquals(byCall, inTx, "transaction " + tx.getKey() + ": its items in call order — " + context);
        }
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "no save left open — " + context);
    }

    static long rows(String table, String column, String id) {
        return count(db.pool(), "select count(*) from " + table + " where " + column + " = $1", id);
    }

    static void nothingOf(String id, String context) {
        assertEquals(0, rows("messages", "id", id), id + ": no ingress row — " + context);
        assertEquals(0, rows("work", "id", id), id + ": no item — " + context);
        assertEquals(0, rows("body_refs", "message_id", id), id + ": no kept ref — " + context);
    }

    static List<String> keyRows(String flow, String key) {
        List<String> ids = new ArrayList<>();
        db.pool().preparedQuery("select message_id from processed_ids where scope = $1 and key_hash = sha256(convert_to($2::text, 'UTF8'))")
                .execute(Tuple.of(flow, key)).await().forEach(r -> ids.add(r.getString(0)));
        return ids;
    }

    static Set<String> committedItems(String prefix) {
        Set<String> ids = new TreeSet<>();
        db.pool().preparedQuery("select id from work where id like $1").execute(Tuple.of(prefix + "%")).await().forEach(r -> ids.add(r.getString(0)));
        return ids;
    }

    /** The committed items of this try by the transaction that inserted them, each in seq order. */
    static Map<Long, List<String>> itemsByTransaction(String prefix) {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (Row r : db.pool().preparedQuery("select l.txid, l.id from txlog l join work w on w.seq = l.seq where l.id like $1 order by l.txid, l.seq")
                .execute(Tuple.of(prefix + "%")).await()) {
            out.computeIfAbsent(r.getLong(0), k -> new ArrayList<>()).add(r.getString(1));
        }
        return out;
    }
}
