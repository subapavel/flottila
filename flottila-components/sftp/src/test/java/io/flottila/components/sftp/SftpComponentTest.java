package io.flottila.components.sftp;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.schemas.Schemas;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What only the real sftp consumer does (D-076), against an embedded MINA SSHD server: two nodes on one endpoint lose
 * nothing, a dropped session or a failed read keeps the file, the size and age rules, the error and move directories,
 * the host key, a partner restart, the props, the options and the transport's own resources.
 */
@Timeout(60)
class SftpComponentTest {

    @TempDir
    Path root;

    EmbeddedSftp sftp;
    final FakeRuntime rt = FakeRuntime.create();
    private final List<Consumer> consumers = new CopyOnWriteArrayList<>();
    private final List<Promise<Ack>> held = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws IOException {
        sftp = EmbeddedSftp.start(root);
        sftp.mkdir("/inbox");
    }

    @AfterEach
    void down() throws Exception {
        held.forEach(p -> p.tryComplete(new Ack.Nack("test cleanup", true)));
        for (Consumer c : consumers) c.stop().otherwiseEmpty().await(10, TimeUnit.SECONDS);
        sftp.close();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    static SftpComponent component(FakeRuntime on) { return new SftpComponent(on.blobs(), on.ids(), on.timer(), on.clock()); }

    EndpointConfig inbox(Map<String, Object> extra) { return sftp.endpoint("sftp-in", "/inbox", extra); }

    Consumer started(SftpComponent component, EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        Consumer c = component.consumer(cfg, handler);
        consumers.add(c);
        c.start().await();
        return c;
    }

    /** A handler recording into {@code seen} and answering Accepted. */
    static Function<Envelope, Future<Ack>> accepting(List<Envelope> seen) {
        return e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        };
    }

    Promise<Ack> hold() {
        Promise<Ack> p = Promise.promise();
        held.add(p);
        return p;
    }

    static String file(Envelope e) { return e.get(SftpComponent.FILE).orElse("<no sftp.file>"); }

    static List<String> files(List<Envelope> seen) { return seen.stream().map(SftpComponentTest::file).toList(); }

    static boolean soon(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    /** Waits until {@code on}'s consumer has its next round scheduled, then moves the fake clock to it. */
    static void fire(FakeRuntime on) {
        assertTrue(soon(() -> on.timer().pending() > 0), "a round is scheduled");
        on.clock().advance(on.timer().nextDelay());
    }

    /** Fires the next round and waits until it is over: the round after it is scheduled (fixed delay after the round ends). */
    static void round(FakeRuntime on) {
        fire(on);
        assertTrue(soon(() -> on.timer().pending() > 0), "the round ended: the next one is scheduled");
    }

    /** The server recorded {@code first}, and before the first event that starts with {@code then}. */
    static void assertBefore(List<String> events, String first, String then, String why) {
        List<String> seen = List.copyOf(events);
        int a = seen.indexOf(first);
        int b = java.util.stream.IntStream.range(0, seen.size()).filter(i -> seen.get(i).startsWith(then)).findFirst().orElse(-1);
        assertTrue(a >= 0 && b >= 0 && a < b, why + ": '" + first + "' at " + a + ", '" + then + "...' at " + b + " in " + seen);
    }

    static Health.Status health(SftpComponent component) {
        try {
            return component.health().await(10, TimeUnit.SECONDS).status();
        } catch (java.util.concurrent.TimeoutException pending) {
            throw new AssertionError("health() did not answer", pending);
        }
    }

    /** Every dropped file is in the ledger of durable saves or still on the server: nothing lost, at any step. */
    void assertNothingLost(Set<String> dropped, Set<String> ledger, String step) {
        Set<String> kept = new TreeSet<>(ledger);
        kept.addAll(sftp.files("/inbox"));
        assertTrue(kept.containsAll(dropped), step + ": every dropped file is saved or still on the server: " + kept);
    }

    /** A BlobStore that records every write before the store underneath does it. */
    static final class SpyBlobStore implements BlobStore {
        record Write(BlobKey key, long maxSize) {}

        private final BlobStore store;
        final List<Write> writes = new CopyOnWriteArrayList<>();

        SpyBlobStore(BlobStore store) { this.store = store; }

        List<BlobKey> keys() { return writes.stream().map(Write::key).toList(); }

        @Override public long treeLimit() { return store.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            writes.add(new Write(key, maxSize));
            return store.write(key, whole, maxSize);
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            writes.add(new Write(key, maxSize));
            return store.write(key, in, maxSize);
        }

        @Override public ReadStream<Buffer> open(BlobKey key) { return store.open(key); }

        @Override public Future<Buffer> read(BlobKey key) { return store.read(key); }

        @Override public Future<Void> persist(BlobKey key) { return store.persist(key); }
    }

    // ---- two nodes, one endpoint ----------------------------------------------------------------------------------------

    @Test
    void aConsumerKilledAfterTheSaveLeavesTheFileForTheNextNode() {
        Set<String> dropped = Set.of("f1.xml", "f2.xml");
        sftp.drop("/inbox", "f1.xml", Buffer.buffer("<one/>"));
        sftp.drop("/inbox", "f2.xml", Buffer.buffer("<two/>"));
        Set<String> ledger = ConcurrentHashMap.newKeySet();   // the durable saves: what a handler took over before it answered
        List<Envelope> onA = new CopyOnWriteArrayList<>();
        List<Envelope> onB = new CopyOnWriteArrayList<>();
        Promise<Ack> aNeverAnswers = hold();
        EndpointConfig in = inbox(Map.of());
        started(component(rt), in, e -> {
            onA.add(e);
            ledger.add(file(e));
            return "f1.xml".equals(file(e)) ? aNeverAnswers.future() : Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        fire(rt);
        assertTrue(soon(() -> onA.size() == 1), "node A delivered the oldest file: " + files(onA));
        assertNothingLost(dropped, ledger, "A saved f1 and holds its answer");

        // A is killed after the save: its answer never comes. Node B, a fresh component, polls the same endpoint.
        FakeRuntime nodeB = FakeRuntime.create();
        started(component(nodeB), in, e -> {
            onB.add(e);
            ledger.add(file(e));
            return "f1.xml".equals(file(e)) ? Future.succeededFuture(new Ack.Accepted(e.id())) : Future.succeededFuture(new Ack.Nack("node B is full", true));
        });
        fire(nodeB);
        assertTrue(soon(() -> onB.size() == 2), "node B delivered the file A holds, then the next one: " + files(onB));
        assertEquals(List.of("f1.xml", "f2.xml"), files(onB));
        assertEquals(onA.get(0).get(SftpComponent.FILE_KEY), onB.get(0).get(SftpComponent.FILE_KEY),
                "both nodes deliver the same file key: the save drops the second as a duplicate (P2-02D)");
        assertTrue(soon(() -> !sftp.files("/inbox").contains("f1.xml")), "B's Accepted removed f1: " + sftp.files("/inbox"));
        assertNothingLost(dropped, ledger, "B accepted f1 and refused f2 for now");

        aNeverAnswers.complete(new Ack.Accepted(onA.get(0).id()));   // A was not dead after all: its answer comes late
        assertTrue(soon(() -> onA.size() == 2),
                "A's delete found no file and counted it done: its round went on to the next file without another tick: " + files(onA));
        assertEquals("f2.xml", file(onA.get(1)));
        assertTrue(soon(() -> sftp.files("/inbox").isEmpty()), "A accepted f2: removed: " + sftp.files("/inbox"));
        assertNothingLost(dropped, ledger, "both answered");
        assertEquals(dropped, ledger, "every dropped file was saved");
    }

    // ---- failures that keep the file ----------------------------------------------------------------------------------

    @Test
    void aSessionDroppedOnTheDeleteKeepsTheFile() {
        sftp.drop("/inbox", "order.xml", Buffer.buffer("<order/>"));
        sftp.onNextRemoving((session, path) -> {
            session.close(true);
            throw new IOException("the test dropped the session on the delete");
        });
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of("order.xml"), files(seen), "delivered and accepted");
        assertTrue(sftp.events.contains("remove /inbox/order.xml"), "the accept asked for the delete: " + sftp.events);
        assertEquals(Set.of("order.xml"), sftp.files("/inbox"), "the delete never happened: the file stays for the next round");
        round(rt);
        assertEquals(List.of("order.xml", "order.xml"), files(seen), "the next round delivers it again");
        assertEquals(seen.get(0).get(SftpComponent.FILE_KEY), seen.get(1).get(SftpComponent.FILE_KEY), "the same file");
        assertEquals(Set.of(), sftp.files("/inbox"), "accepted again, deleted this time");
        assertEquals(2, sftp.sessionsCreated(), "the dropped session was replaced by a new one");
    }

    @Test
    void anIoExceptionWhileReadingEndsTheRoundAndDropsTheSession() {
        sftp.drop("/inbox", "f1.xml", Buffer.buffer("<one/>"));
        sftp.drop("/inbox", "f2.xml", Buffer.buffer("<two/>"));
        sftp.onNextReading((session, path) -> { throw new IOException("the test failed the read of " + path); });
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of(), files(seen), "the failed read ended the round: nothing delivered, f2 not taken");
        assertEquals(Set.of("f1.xml", "f2.xml"), sftp.files("/inbox"), "both files stay");
        assertTrue(soon(() -> sftp.sessionsClosed() == 1), "the session was dropped after the IOException (the server kept it open)");
        round(rt);
        assertEquals(List.of("f1.xml", "f2.xml"), files(seen), "the next round, on a new session, delivers both");
        assertEquals("<one/>", seen.get(0).body().whole().toString(), "intact");
        assertEquals(2, sftp.sessionsCreated(), "one new session");
    }

    // ---- size, age, error and move directories -----------------------------------------------------------------------

    @Test
    void aFileAboveMaxBodyBytesGoesToErrorDirUnread() {
        sftp.mkdir("/error");
        sftp.drop("/inbox", "big.bin", Buffer.buffer("x".repeat(101)));
        sftp.drop("/inbox", "exact.bin", Buffer.buffer("y".repeat(100)));
        SpyBlobStore spy = new SpyBlobStore(rt.blobs());
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(new SftpComponent(spy, rt.ids(), rt.timer(), rt.clock()), inbox(Map.of("maxBodyBytes", 100, "errorDir", "/error")), accepting(seen));
        round(rt);
        round(rt);   // whether a final refusal ends its round or not, both files have been taken by now
        assertEquals(List.of("exact.bin"), files(seen), "a file of exactly maxBodyBytes is delivered, the one above it is not");
        assertEquals(Set.of("big.bin"), sftp.files("/error"), "refused finally: moved to the error directory");
        assertEquals("x".repeat(101), sftp.read("/error/big.bin"), "intact");
        assertEquals(Set.of(), sftp.files("/inbox"));
        assertFalse(sftp.opened("/inbox/big.bin"), "the listed size refused it: never opened, never read: " + sftp.events);
        assertEquals(List.of(BlobKey.body(seen.get(0).id(), 0)), spy.keys(), "the BlobStore saw only the delivered file's body");
    }

    @Test
    void aFileThatGrowsPastMaxBodyBytesWhileItIsReadIsRefusedFinally() {
        sftp.mkdir("/error");
        sftp.drop("/inbox", "grows.bin", Buffer.buffer("a".repeat(60)));
        sftp.onNextOpening((session, path) -> Files.writeString(sftp.local(path), "b".repeat(60), StandardOpenOption.APPEND));   // listed at 60
        SpyBlobStore spy = new SpyBlobStore(rt.blobs());
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(new SftpComponent(spy, rt.ids(), rt.timer(), rt.clock()), inbox(Map.of("maxBodyBytes", 100, "errorDir", "/error")), accepting(seen));
        round(rt);
        assertEquals(List.of(), files(seen), "never delivered: the bytes read passed maxBodyBytes");
        assertEquals(1, spy.writes.size(), "the download streamed into the BlobStore: " + spy.writes);
        assertEquals(100L, spy.writes.get(0).maxSize(), "bounded by maxBodyBytes, never by more");
        assertEquals(Set.of("grows.bin"), sftp.files("/error"), "BodyTooLarge is a final refusal: moved to the error directory");
        assertEquals(120, sftp.read("/error/grows.bin").length(), "intact");
        assertBefore(sftp.events, "close /inbox/grows.bin", "move /inbox/grows.bin ",
                "the abandoned download closed its handle before the file was moved, never moved while held open");
    }

    @Test
    void aFinalNackMovesToErrorDir() {
        sftp.mkdir("/error");
        sftp.drop("/inbox", "bad.xml", Buffer.buffer("<bad/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("errorDir", "/error")), e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Nack("the schema says no", false));
        });
        round(rt);
        assertEquals(List.of("bad.xml"), files(seen));
        assertEquals(Set.of(), sftp.files("/inbox"), "a final Nack moves the file out of the inbox");
        assertEquals(Set.of("bad.xml"), sftp.files("/error"), "into the error directory");
        assertEquals("<bad/>", sftp.read("/error/bad.xml"), "intact");
        round(rt);
        assertEquals(1, seen.size(), "never delivered again");
    }

    @Test
    void afterSuccessMoveRenamesIntoMoveTo() {
        sftp.mkdir("/done");
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("afterSuccess", "move", "moveTo", "/done")), accepting(seen));
        sftp.drop("/inbox", "x.xml", Buffer.buffer("<first/>"));
        round(rt);
        assertEquals(Set.of("x.xml"), sftp.files("/done"), "accepted: renamed into moveTo under its name");
        assertEquals("<first/>", sftp.read("/done/x.xml"));
        assertEquals(Set.of(), sftp.files("/inbox"));
        sftp.drop("/inbox", "x.xml", Buffer.buffer("<second/>"));
        round(rt);
        assertEquals(2, seen.size(), "the second file of that name was delivered: " + files(seen));
        String taken = "x.xml." + seen.get(1).id();
        assertEquals(Set.of("x.xml", taken), sftp.files("/done"), "moveTo/x.xml was taken: the second goes to x.xml.<its message id>");
        assertEquals("<first/>", sftp.read("/done/x.xml"), "the first is not overwritten");
        assertEquals("<second/>", sftp.read("/done/" + taken));
        assertEquals(Set.of(), sftp.files("/inbox"));
        assertTrue(sftp.events.stream().noneMatch(ev -> ev.startsWith("remove ")), "moved, never deleted: " + sftp.events);
    }

    @Test
    void aYoungFileWaitsForMinAge() {
        Instant mtime = FakeRuntime.T0.minusSeconds(10);   // the fake clock stands at T0: ten seconds old
        sftp.drop("/inbox", "young.xml", Buffer.buffer("<young/>"), mtime);
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("minAgeMs", 60_000, "intervalMs", 20_000)), accepting(seen));
        int youngRounds = 0;
        for (int i = 0; i < 10 && seen.isEmpty(); i++) {
            round(rt);
            Duration age = Duration.between(mtime, rt.clock().instant());
            if (age.compareTo(Duration.ofMinutes(1)) < 0) {
                assertEquals(List.of(), files(seen), "a round at age " + age + " leaves a file younger than minAgeMs alone");
                youngRounds++;
            } else {
                assertEquals(List.of("young.xml"), files(seen), "the first round at age " + age + " (minAgeMs reached) delivers it");
            }
        }
        assertTrue(youngRounds >= 2, "rounds ran while the file was young: " + youngRounds);
        assertEquals(List.of("young.xml"), files(seen), "delivered once old enough");
    }

    // ---- what a round takes ------------------------------------------------------------------------------------------

    @Test
    void filesWithTheSameMtimeAreDeliveredByName() {
        Instant same = EmbeddedSftp.FIRST_MTIME;
        for (String name : List.of("b.xml", "c.xml", "a.xml")) sftp.drop("/inbox", name, Buffer.buffer(name), same);
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of("a.xml", "b.xml", "c.xml"), files(seen), "(mtime, name): one mtime, so by name");
    }

    @Test
    void aRoundTakesAtMostMaxFilesPerPoll() {
        for (String name : List.of("f1.xml", "f2.xml", "f3.xml")) sftp.drop("/inbox", name, Buffer.buffer(name));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("maxFilesPerPoll", 2)), accepting(seen));
        round(rt);
        assertEquals(List.of("f1.xml", "f2.xml"), files(seen), "two files per round, the oldest");
        assertEquals(Set.of("f3.xml"), sftp.files("/inbox"));
        round(rt);
        assertEquals(List.of("f1.xml", "f2.xml", "f3.xml"), files(seen), "the rest in the next round");
    }

    @Test
    void onlyRegularFilesMatchingTheGlobAreTaken() throws IOException {
        sftp.drop("/inbox", "a.xml", Buffer.buffer("<a/>"));
        sftp.drop("/inbox", "b.txt", Buffer.buffer("not matched"));
        Path sub = sftp.mkdir("/inbox/sub.xml");   // a directory whose name matches
        Files.writeString(sub.resolve("c.xml"), "<c/>");   // not recursive
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("glob", "*.xml")), accepting(seen));
        round(rt);
        round(rt);
        assertEquals(List.of("a.xml"), files(seen), "the glob matches the file name, regular files only, not recursive");
        assertEquals(Set.of("b.txt"), sftp.files("/inbox"), "the file the glob does not match stays");
        assertTrue(Files.isRegularFile(sub.resolve("c.xml")), "the subdirectory is left alone");
    }

    // ---- the partner, the session, the host key -----------------------------------------------------------------------

    @Test
    void aWrongHostKeyIsRefused() {
        sftp.drop("/inbox", "secret.xml", Buffer.buffer("<secret/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = component(rt);
        started(component, inbox(Map.of("hostKey", EmbeddedSftp.fingerprint(EmbeddedSftp.OTHER_KEY))), accepting(seen));
        assertEquals(Health.Status.UP, health(component), "running, and no round has failed yet");
        round(rt);
        assertEquals(Health.Status.DOWN, health(component), "the round could not connect: down");
        assertTrue(sftp.sessionsCreated() >= 1, "the consumer did connect: the refusal is the host key's");
        assertEquals(0, sftp.passwordChecks(), "the server was refused before the login: the password never left the node");
        assertEquals(List.of(), files(seen));
        assertEquals(Set.of("secret.xml"), sftp.files("/inbox"));

        FakeRuntime other = FakeRuntime.create();   // the same endpoint with the server's own key is served
        SftpComponent good = component(other);
        started(good, inbox(Map.of()), accepting(seen));
        round(other);
        assertEquals(List.of("secret.xml"), files(seen), "the right fingerprint logs in and delivers");
        assertTrue(sftp.passwordChecks() >= 1, "and logs in with the password");
        assertEquals(Health.Status.UP, health(good));
    }

    @Test
    void aServerRestartIsSurvived() throws IOException {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = component(rt);
        started(component, inbox(Map.of()), accepting(seen));
        sftp.drop("/inbox", "before.xml", Buffer.buffer("<before/>"));
        round(rt);
        assertEquals(List.of("before.xml"), files(seen));
        assertEquals(Health.Status.UP, health(component));
        int port = sftp.port();
        sftp.close();   // the partner goes down
        round(rt);
        assertEquals(Health.Status.DOWN, health(component), "the last round could not connect");
        sftp = EmbeddedSftp.start(root, port, EmbeddedSftp.HOST_KEY);   // back: same address, same key, same files
        sftp.drop("/inbox", "after.xml", Buffer.buffer("<after/>"));
        round(rt);
        assertEquals(List.of("before.xml", "after.xml"), files(seen), "the consumer reconnected by itself");
        assertEquals(Health.Status.UP, health(component), "up again once a round succeeded");
        assertEquals(Set.of(), sftp.files("/inbox"));
    }

    @Test
    void startCompletesAtOnceWhileThePartnerIsDown() throws IOException {
        EndpointConfig in = inbox(Map.of());
        sftp.close();   // nothing listens there any more
        SftpComponent component = component(rt);
        Consumer consumer = component.consumer(in, accepting(new CopyOnWriteArrayList<>()));
        consumers.add(consumer);
        Future<Void> start = consumer.start();
        assertTrue(start.succeeded(), "start schedules round 0 and completes at once: a partner down never fails a deploy: " + start);
        round(rt);
        assertEquals(Health.Status.DOWN, health(component), "the round could not connect");
    }

    @Test
    void aDirectoryThatCannotBeListedTurnsHealthDown() {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = component(rt);
        started(component, sftp.endpoint("sftp-in", "/missing", Map.of()), accepting(seen));
        round(rt);
        assertEquals(Health.Status.DOWN, health(component), "the last round could not list its directory");
        sftp.mkdir("/missing");
        sftp.drop("/missing", "late.xml", Buffer.buffer("<late/>"));
        round(rt);
        assertEquals(List.of("late.xml"), files(seen));
        assertEquals(Health.Status.UP, health(component), "listed and delivered: up again");
    }

    @Test
    void oneSessionIsReusedAcrossRoundsAndStopClosesIt() throws Exception {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = started(component(rt), inbox(Map.of()), accepting(seen));
        assertInstanceOf(SftpComponent.SftpConsumer.class, consumer);
        sftp.drop("/inbox", "one.xml", Buffer.buffer("<one/>"));
        round(rt);
        assertEquals(Duration.ofSeconds(10), rt.timer().nextDelay(), "the next round intervalMs (default 10 s) after the round ended");
        sftp.drop("/inbox", "two.xml", Buffer.buffer("<two/>"));
        round(rt);
        round(rt);
        assertEquals(List.of("one.xml", "two.xml"), files(seen));
        assertBefore(sftp.events, "close /inbox/one.xml", "remove /inbox/one.xml", "an accepted file's handle is closed before its delete");
        assertEquals(1, sftp.sessionsCreated(), "one SSH session for every round of the consumer");
        consumers.remove(consumer);
        consumer.stop().await(10, TimeUnit.SECONDS);
        assertEquals(0, rt.timer().pending(), "stop cancelled the next round");
        assertTrue(soon(() -> sftp.sessionsClosed() == 1), "stop closed the session");
    }

    @Test
    void theMinaIoPoolHasAtMostTwoThreads() {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        sftp.drop("/inbox", "one.xml", Buffer.buffer("<one/>"));
        round(rt);
        assertEquals(List.of("one.xml"), files(seen), "a session is open");
        Map<String, Long> perClient = Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
                .filter(n -> n.startsWith("sshd-SshClient[") && n.contains("-nio2-thread-"))
                .collect(Collectors.groupingBy(n -> n.substring(0, n.indexOf(']') + 1), Collectors.counting()));
        assertFalse(perClient.isEmpty(), "a MINA client names its NIO2 threads sshd-SshClient[..]-nio2-thread-N: "
                + Thread.getAllStackTraces().keySet().stream().map(Thread::getName).filter(n -> n.startsWith("sshd-")).toList());
        perClient.forEach((client, threads) -> assertTrue(threads <= 2,
                client + " runs " + threads + " NIO2 threads: the transport's I/O pool is sized to the minimum (D-076), not to the cores"));
    }

    // ---- what the handler sees ----------------------------------------------------------------------------------------

    @Test
    void propsCarryNamePathMtimeAndFileKey() {
        sftp.drop("/inbox", "order-1.xml", Buffer.buffer("<order id=\"1\"/>"), Instant.parse("2025-12-31T23:00:00Z"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("flow", "orders/sftp-intake")), accepting(seen));
        round(rt);
        assertEquals(1, seen.size());
        Envelope e = seen.get(0);
        assertEquals("order-1.xml", e.get(SftpComponent.FILE).orElse(null), "sftp.file: the name");
        assertEquals("/inbox/order-1.xml", e.get(SftpComponent.PATH).orElse(null), "sftp.path: the full remote path");
        assertEquals("2025-12-31T23:00:00Z", e.get(SftpComponent.MTIME).orElse(null), "sftp.mtime: the server's mtime, ISO-8601");
        assertEquals("sftp://127.0.0.1:" + sftp.port() + "/inbox/order-1.xml@2025-12-31T23:00:00Z#" + e.body().hash().hex(),
                e.get(SftpComponent.FILE_KEY).orElse(null), "sftp.fileKey: sftp://host:port/path@mtime#bodyHash");
        assertEquals(Set.of("sftp.file", "sftp.path", "sftp.mtime", "sftp.fileKey"), e.props().asMap().keySet(), "these four, no size prop (body().size())");
        assertEquals(Map.of(), e.headers().asMap(), "no headers");
        assertEquals("<order id=\"1\"/>", e.body().whole().toString());
        assertEquals("orders/sftp-intake", e.flow(), "the flow option names the envelope's flow");
        assertEquals("m-1", e.id(), "the id from the injected IdGenerator");
        assertEquals(BlobKey.body(e.id(), 0), e.body().key(), "the body stored under (id, 0)");
    }

    @Test
    void theProducerIsRefusedAtBuild() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> component(rt).producer(inbox(Map.of())));
        assertEquals("sftp has no producer before Phase 4", refused.getMessage());
    }

    @Test
    void optionsOutsideTheSchemaAreRefused() {
        assertEquals("sftp", component(rt).scheme());
        String schema = component(rt).configSchema();
        // what the deploy checks: the endpoint's own config, before it sets flow and maxBodyBytes on an in endpoint
        Map<String, Object> base = Map.of("user", "flottila", "password", "s3cret", "hostKey", "SHA256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU");
        assertValid(schema, base, "user, password and hostKey");
        assertValid(schema, with(without(base, "password"), "privateKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n"),
                "a private key instead of the password");
        Map<String, Object> every = new HashMap<>(base);
        every.putAll(Map.of("glob", "*.xml", "intervalMs", 5_000, "maxFilesPerPoll", 10, "minAgeMs", 30_000, "afterSuccess", "move", "moveTo", "/done",
                "connectTimeoutMs", 5_000, "ioTimeoutMs", 10_000, "errorDir", "/error"));
        every.putAll(Map.of("flow", "orders/sftp-intake", "maxBodyBytes", 1_000_000));
        assertValid(schema, every, "every option the component knows, the two the deploy sets included");
        assertValid(schema, with(base, "afterSuccess", "delete"), "afterSuccess delete");
        assertRefused(schema, without(base, "hostKey"), "no hostKey: never accept-all");
        assertRefused(schema, with(base, "privateKey", "-----BEGIN OPENSSH PRIVATE KEY-----"), "both password and privateKey");
        assertRefused(schema, without(base, "password"), "neither password nor privateKey");
        assertRefused(schema, without(base, "user"), "no user");
        assertRefused(schema, with(base, "afterSuccess", "move"), "move without moveTo");
        assertRefused(schema, with(base, "afterSuccess", "copy"), "afterSuccess is delete or move");
        assertRefused(schema, with(base, "maxBodyBytes", 0), "maxBodyBytes below 1");
        assertRefused(schema, with(base, "pasword", "typo"), "strict: an option the component does not know");
        EndpointConfig noLimit = new EndpointConfig("sftp-in", inbox(Map.of()).uri(), without(inbox(Map.of()).options(), "maxBodyBytes"));
        IllegalArgumentException unlimited = assertThrows(IllegalArgumentException.class,
                () -> component(rt).consumer(noLimit, e -> Future.succeededFuture(new Ack.Accepted(e.id()))));
        assertTrue(String.valueOf(unlimited.getMessage()).contains("maxBodyBytes"), "a consumer without maxBodyBytes is refused, never unlimited: " + unlimited.getMessage());
    }

    // ---- P2-06 (D-086): host, port and dir as options, for a deployed endpoint whose URI is only <scheme>://<id> ------------

    /** The real server's options under {@code uri}: the URI's own host, port and directory are what the options must override. */
    private EndpointConfig under(String uri, Map<String, Object> extra) {
        return new EndpointConfig("sftp-in", URI.create(uri), sftp.endpoint("sftp-in", "/inbox", extra).options());
    }

    private List<String> deliveredBy(FakeRuntime on, EndpointConfig cfg, SftpComponent component) {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component, cfg, accepting(seen));
        round(on);
        return files(seen);
    }

    @Test
    void hostOptionWinsOverTheUriHost() {
        sftp.drop("/inbox", "real.xml", Buffer.buffer("<real/>"));
        SftpComponent component = component(rt);
        assertEquals(List.of("real.xml"),
                deliveredBy(rt, under("sftp://no-such-host.invalid:" + sftp.port() + "/inbox", Map.of("host", "127.0.0.1")), component),
                "the host option names the server; the URI's host does not resolve");
        assertEquals(Health.Status.UP, health(component));
        assertEquals(Set.of(), sftp.files("/inbox"), "and the delivered file was deleted");
    }

    @Test
    void portOptionWinsOverTheUriPort() {
        sftp.drop("/inbox", "real.xml", Buffer.buffer("<real/>"));
        assertEquals(List.of("real.xml"),
                deliveredBy(rt, under("sftp://127.0.0.1:1/inbox", Map.of("port", sftp.port())), component(rt)),
                "the port option names the server's port; the URI's port 1 refuses");
    }

    @Test
    void dirOptionWinsOverTheUriPath() {
        sftp.mkdir("/other");
        sftp.drop("/other", "decoy.xml", Buffer.buffer("<decoy/>"));
        sftp.drop("/inbox", "real.xml", Buffer.buffer("<real/>"));
        assertEquals(List.of("real.xml"),
                deliveredBy(rt, under("sftp://127.0.0.1:" + sftp.port() + "/other", Map.of("dir", "/inbox")), component(rt)),
                "the dir option names the polled directory, not the URI's path");
        assertEquals(Set.of("decoy.xml"), sftp.files("/other"), "the URI's directory is never touched");
    }

    @Test
    void hostPortAndDirOptionsWinOverTheUri() {
        sftp.mkdir("/other");
        sftp.drop("/other", "decoy.xml", Buffer.buffer("<decoy/>"));
        sftp.drop("/inbox", "real.xml", Buffer.buffer("<real/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        // what a deployed endpoint looks like: the URI is <scheme>://<id>, host, port and directory come from the options
        started(component(rt), under("sftp://sftp-in", Map.of("host", "127.0.0.1", "port", sftp.port(), "dir", "/inbox")), accepting(seen));
        round(rt);
        assertEquals(List.of("real.xml"), files(seen));
        assertEquals("/inbox/real.xml", seen.get(0).get(SftpComponent.PATH).orElse(null));
        assertTrue(seen.get(0).get(SftpComponent.FILE_KEY).orElse("").startsWith("sftp://127.0.0.1:" + sftp.port() + "/inbox/real.xml@"),
                "the file key names the server the options chose: " + seen.get(0).get(SftpComponent.FILE_KEY).orElse(null));
        assertEquals(Set.of("decoy.xml"), sftp.files("/other"));
    }

    @Test
    void withoutOptionsTheUriDecides() {
        sftp.mkdir("/other");
        sftp.drop("/other", "decoy.xml", Buffer.buffer("<decoy/>"));
        sftp.drop("/inbox", "real.xml", Buffer.buffer("<real/>"));
        assertEquals(List.of("real.xml"), deliveredBy(rt, under("sftp://127.0.0.1:" + sftp.port() + "/inbox", Map.of()), component(rt)),
                "the URI's host, port and path are used when no option is given");
        assertEquals(Set.of("decoy.xml"), sftp.files("/other"));

        FakeRuntime other = FakeRuntime.create();   // and a URI naming a host that is not there reaches nothing: the URI's host is the one dialled
        sftp.drop("/inbox", "kept.xml", Buffer.buffer("<kept/>"));
        SftpComponent unreachable = component(other);
        assertEquals(List.of(), deliveredBy(other, under("sftp://no-such-host.invalid:" + sftp.port() + "/inbox", Map.of()), unreachable));
        assertEquals(Health.Status.DOWN, health(unreachable), "the round could not connect");
        assertEquals(Set.of("kept.xml"), sftp.files("/inbox"));
    }

    @Test
    void theConfigSchemaAcceptsHostPortAndDirAndRefusesBadPorts() {
        String schema = component(rt).configSchema();
        Map<String, Object> base = Map.of("user", "flottila", "password", "s3cret", "hostKey", "SHA256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU");
        assertValid(schema, with(with(with(base, "host", "sftp"), "port", 22), "dir", "inbox"), "host, port and dir together");
        assertValid(schema, with(base, "host", "sftp"), "host alone");
        assertValid(schema, with(base, "port", 1), "port 1, the lowest");
        assertValid(schema, with(base, "port", 65535), "port 65535, the highest");
        assertValid(schema, with(base, "dir", "/inbox"), "dir alone");
        assertRefused(schema, with(base, "port", 0), "port 0");
        assertRefused(schema, with(base, "port", 70000), "port 70000");
        assertRefused(schema, with(base, "port", 65536), "port 65536");
        assertRefused(schema, with(base, "port", "22"), "port as text");
        assertRefused(schema, with(base, "host", 7), "host as a number");
        assertRefused(schema, with(base, "dir", 7), "dir as a number");
    }

    @Test
    void anEmptyHostOptionIsRefusedByTheConfigSchema() {
        Map<String, Object> base = Map.of("user", "flottila", "password", "s3cret", "hostKey", "SHA256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU");
        assertRefused(component(rt).configSchema(), with(base, "host", ""), "an empty host names no server (the URI's would silently not be used)");
    }

    /**
     * The deploy checks the config as written, secret references unresolved (D-081): the demo's {@code hostKey: secret://sftp-host-key}
     * (the key is generated at the stack's first start) and {@code password: secret://sftp-password} must pass this schema, and a
     * hostKey that is neither a fingerprint nor a well-formed reference must still not.
     */
    @Test
    void theHostKeyMayBeASecretReferenceButNothingElseThatIsNotAFingerprint() {
        String schema = component(rt).configSchema();
        Map<String, Object> base = Map.of("user", "demo", "password", "secret://sftp-password", "hostKey", "secret://sftp-host-key");
        assertValid(schema, base, "password and hostKey as secret references, as the demo's endpoint is written");
        assertValid(schema, with(base, "hostKey", "secret://sftp.host-key_2"), "a reference name with . and -");
        assertValid(schema, with(base, "hostKey", "SHA256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU"), "a fingerprint still validates");
        assertRefused(schema, with(base, "hostKey", "secret://"), "a reference without a name");
        assertRefused(schema, with(base, "hostKey", "secret://UPPER"), "a reference name outside [a-z0-9][a-z0-9._-]*");
        assertRefused(schema, with(base, "hostKey", "x secret://sftp-host-key"), "text around a reference");
        assertRefused(schema, with(base, "hostKey", "sha256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU"), "a fingerprint with the wrong prefix case");
        assertRefused(schema, with(base, "hostKey", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA"), "a public key line is no fingerprint");
    }

    static Map<String, Object> with(Map<String, Object> options, String key, Object value) {
        Map<String, Object> m = new HashMap<>(options);
        m.put(key, value);
        return m;
    }

    static Map<String, Object> without(Map<String, Object> options, String key) {
        Map<String, Object> m = new HashMap<>(options);
        m.remove(key);
        return m;
    }

    static void assertValid(String schema, Map<String, Object> options, String what) {
        Schemas.Report report = Schemas.check(schema, new JsonObject(options));
        assertTrue(report.valid(), what + ": valid, but refused with " + report.problems());
    }

    static void assertRefused(String schema, Map<String, Object> options, String what) {
        assertFalse(Schemas.check(schema, new JsonObject(options)).valid(), what + ": refused by configSchema(), but valid: " + options);
    }

    // ---- P2-02L (D-083): the node holds the endpoint's lease ----------------------------------------------------------

    /**
     * Every sftp endpoint polls: the node runs its consumer only while it holds the endpoint's lease. Answered from the config
     * alone — here one for a port nothing listens on, and one for this server: no session is opened.
     */
    @Test
    void everySftpEndpointPolls() {
        SftpComponent component = component(rt);
        EndpointConfig nowhere = new EndpointConfig("sftp-in", URI.create("sftp://127.0.0.1:1/inbox"),
                Map.of("user", "flottila", "password", "x", "hostKey", EmbeddedSftp.fingerprint(EmbeddedSftp.HOST_KEY), "maxBodyBytes", 1024));

        assertTrue(component.polls(inbox(Map.of())), "the default options");
        assertTrue(component.polls(inbox(Map.of("afterSuccess", "move", "moveTo", "/done", "glob", "*.xml"))), "moving, filtered");
        assertTrue(component.polls(nowhere), "a partner nobody reaches");
        assertEquals(0, sftp.sessionsCreated(), "answered from the config alone: nothing connected");
    }

    // ---- P2-02D (D-084): the save dedupes by the file key ------------------------------------------------------------

    /**
     * Every sftp endpoint names {@code sftp.fileKey} as its dedupe key: the same file delivered again — its removal refused after
     * the save, another node polling it after a takeover — is one message. Answered from the config alone.
     */
    @Test
    void dedupesByTheFileKey() {
        SftpComponent component = component(rt);
        EndpointConfig nowhere = new EndpointConfig("sftp-in", URI.create("sftp://127.0.0.1:1/inbox"),
                Map.of("user", "flottila", "password", "x", "hostKey", EmbeddedSftp.fingerprint(EmbeddedSftp.HOST_KEY), "maxBodyBytes", 1024));

        assertEquals(Optional.of(SftpComponent.FILE_KEY), component.dedupeBy(inbox(Map.of())), "the default options");
        assertEquals(Optional.of(SftpComponent.FILE_KEY), component.dedupeBy(inbox(Map.of("afterSuccess", "move", "moveTo", "/done"))), "moving");
        assertEquals(Optional.of(SftpComponent.FILE_KEY), component.dedupeBy(nowhere), "a partner nobody reaches");
        assertEquals("sftp.fileKey", component.dedupeBy(nowhere).orElseThrow().name(), "the prop every delivered file carries (propsCarryNamePathMtimeAndFileKey)");
        assertEquals(0, sftp.sessionsCreated(), "answered from the config alone: nothing connected");
    }

    /**
     * A duplicate is accepted under the first message's id: a move whose name is taken in {@code moveTo} is suffixed with the id
     * the Accepted names — the message the file is — not the arrival's own id.
     */
    @Test
    void aTakenMoveNameIsSuffixedWithTheAcceptedId() {
        sftp.mkdir("/done");
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("afterSuccess", "move", "moveTo", "/done")), e -> {
            seen.add(e);
            // the first arrival is saved as itself; the second is a duplicate of a message saved earlier
            return Future.succeededFuture(new Ack.Accepted(seen.size() == 1 ? e.id() : "m-first-saved"));
        });
        sftp.drop("/inbox", "x.xml", Buffer.buffer("<first/>"));
        round(rt);
        assertEquals(Set.of("x.xml"), sftp.files("/done"), "fixture: moveTo/x.xml is taken now");
        sftp.drop("/inbox", "x.xml", Buffer.buffer("<second/>"));
        round(rt);
        assertEquals(2, seen.size(), "fixture: the second file was delivered: " + files(seen));
        assertNotEquals("m-first-saved", seen.get(1).id(), "fixture: the accepted id is not the arrival's");
        assertEquals(Set.of("x.xml", "x.xml.m-first-saved"), sftp.files("/done"), "the taken name is suffixed with the accepted id, not " + seen.get(1).id());
        assertEquals("<second/>", sftp.read("/done/x.xml.m-first-saved"));
        assertEquals(Set.of(), sftp.files("/inbox"));
    }
}
