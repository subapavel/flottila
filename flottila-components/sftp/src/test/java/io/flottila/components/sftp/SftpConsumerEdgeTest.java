package io.flottila.components.sftp;

import io.flottila.api.Ack;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sftp consumer's edges the shared suites leave out: options a consumer refuses itself, a refused delete or move, a
 * trailing slash, the private-key login, a session closed idle, round 0 at start, a stop before the start and one mid-round.
 */
@Timeout(60)
class SftpConsumerEdgeTest {

    @TempDir
    Path root;

    EmbeddedSftp sftp;
    final FakeRuntime rt = FakeRuntime.create();
    private final List<Consumer> consumers = new CopyOnWriteArrayList<>();
    private final List<TcpRelay> relays = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws IOException {
        sftp = EmbeddedSftp.start(root);
        sftp.mkdir("/inbox");
    }

    @AfterEach
    void down() throws Exception {
        for (Consumer c : consumers) c.stop().otherwiseEmpty().await(10, TimeUnit.SECONDS);
        for (TcpRelay r : relays) r.close();
        sftp.close();
    }

    SftpComponent component() { return new SftpComponent(rt.blobs(), rt.ids(), rt.timer(), rt.clock()); }

    Consumer started(SftpComponent component, EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        Consumer c = component.consumer(cfg, handler);
        consumers.add(c);
        c.start().await();
        return c;
    }

    static Function<Envelope, Future<Ack>> accepting(List<Envelope> seen) {
        return e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        };
    }

    static List<String> files(List<Envelope> seen) { return seen.stream().map(e -> e.get(SftpComponent.FILE).orElse("?")).toList(); }

    /** {@code cfg} reaching the server through a new {@link TcpRelay}, which tells when the client closed its session. */
    EndpointConfig via(TcpRelay relay, EndpointConfig cfg) {
        return new EndpointConfig(cfg.name(), URI.create("sftp://127.0.0.1:" + relay.port() + cfg.uri().getPath()), cfg.options());
    }

    TcpRelay relay() throws IOException {
        TcpRelay relay = new TcpRelay(sftp.port());
        relays.add(relay);
        return relay;
    }

    void round(FakeRuntime on) {
        SftpComponentTest.round(on);
    }

    // ---- options the consumer refuses itself ---------------------------------------------------------------------------

    @Test
    void aConsumerWithoutUserHostKeyMoveToOrAPositiveLimitIsRefused() {
        SftpComponent component = component();
        Function<Envelope, Future<Ack>> h = e -> Future.succeededFuture(new Ack.Accepted(e.id()));
        Map<String, Object> valid = sftp.endpoint("in", "/inbox", Map.of()).options();
        for (var refusal : List.of(
                Map.entry(without(valid, "hostKey"), "'hostKey' is required"),
                Map.entry(without(valid, "user"), "'user' is required"),
                Map.entry(with(valid, "maxBodyBytes", 0), "'maxBodyBytes' must be a positive integer"),
                Map.entry(with(valid, "afterSuccess", "move"), "'moveTo' is required"))) {
            EndpointConfig cfg = new EndpointConfig("in", URI.create("sftp://127.0.0.1:" + sftp.port() + "/inbox"), refusal.getKey());
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> component.consumer(cfg, h));
            assertTrue(refused.getMessage().contains(refusal.getValue()), refusal.getValue() + ": " + refused.getMessage());
        }
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

    // ---- paths, refusals by the server -------------------------------------------------------------------------------

    @Test
    void aDirectoryWithATrailingSlashJoinsItsFilesOnce() {
        sftp.drop("/inbox", "a.xml", Buffer.buffer("<a/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(), sftp.endpoint("in", "/inbox/", Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of("/inbox/a.xml"), seen.stream().map(e -> e.get(SftpComponent.PATH).orElse("?")).toList());
        assertEquals(Set.of(), sftp.files("/inbox"), "deleted under the joined path");
    }

    @Test
    void aDeleteTheServerRefusesKeepsTheFileAndEndsTheRound() {
        sftp.drop("/inbox", "f1.xml", Buffer.buffer("<one/>"));
        sftp.drop("/inbox", "f2.xml", Buffer.buffer("<two/>"));
        sftp.onNextRemoving((session, path) -> { throw new IOException("permission denied (the test)"); });   // the session stays up
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(), sftp.endpoint("in", "/inbox", Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of("f1.xml"), files(seen), "the refused delete ended the round: f2 was not taken");
        assertEquals(Set.of("f1.xml", "f2.xml"), sftp.files("/inbox"), "f1 stays for the next round");
        round(rt);
        assertEquals(List.of("f1.xml", "f1.xml", "f2.xml"), files(seen), "f1 delivered again, then f2");
        assertEquals(Set.of(), sftp.files("/inbox"));
    }

    @Test
    void aFileAlreadyInErrorDirKeepsBothUnderTheMessageId() {
        sftp.mkdir("/error");
        Path inError = sftp.local("/error").resolve("bad.xml");
        sftp.drop("/inbox", "bad.xml", Buffer.buffer("<bad/>"));
        try {
            Files.writeString(inError, "<older/>");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(), sftp.endpoint("in", "/inbox", Map.of("errorDir", "/error")), e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Nack("no", false));
        });
        round(rt);
        assertEquals(Set.of("bad.xml", "bad.xml." + seen.get(0).id()), sftp.files("/error"));
        assertEquals("<older/>", sftp.read("/error/bad.xml"), "never overwritten");
    }

    @Test
    void ioTimeoutMsClosesASessionIdleWhileTheHandlerWorksAndTheAcceptedFileIsStillRemoved() throws IOException {
        sftp.drop("/inbox", "slow.xml", Buffer.buffer("<slow/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        io.vertx.core.Promise<Ack> answer = io.vertx.core.Promise.promise();
        TcpRelay relay = relay();
        started(component(), via(relay, sftp.endpoint("in", "/inbox", Map.of("ioTimeoutMs", 300))), e -> {
            seen.add(e);
            return answer.future();
        });
        SftpComponentTest.fire(rt);
        assertTrue(SftpComponentTest.soon(() -> seen.size() == 1), "delivered");
        assertTrue(SftpComponentTest.soon(() -> relay.clientsClosed() == 1), "the client closed its session idle for ioTimeoutMs while the answer was pending");
        answer.complete(new Ack.Accepted(seen.get(0).id()));
        assertTrue(SftpComponentTest.soon(() -> rt.timer().pending() > 0), "the round ended");
        assertEquals(Set.of(), sftp.files("/inbox"), "the accepted file was removed in the same round, on a new session");
        assertEquals(2, sftp.sessionsCreated(), "the delete reopened a session instead of failing on the closed one");
        assertEquals(1, seen.size(), "delivered once");
    }

    @Test
    void aSessionClosedIdleBetweenRoundsIsReopenedByTheNextRoundWhichDelivers() throws IOException {
        SftpComponent component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        sftp.drop("/inbox", "a.xml", Buffer.buffer("<a/>"));
        TcpRelay relay = relay();
        started(component, via(relay, sftp.endpoint("in", "/inbox", Map.of("ioTimeoutMs", 300))), accepting(seen));
        round(rt);
        assertEquals(List.of("a.xml"), files(seen));
        assertTrue(SftpComponentTest.soon(() -> relay.clientsClosed() == 1), "the client closed its session idle for ioTimeoutMs between rounds");
        assertTrue(SftpComponentTest.soon(() -> sftp.sessionsClosed() == 1), "and the server saw it close");
        sftp.drop("/inbox", "b.xml", Buffer.buffer("<b/>"));
        round(rt);
        assertEquals(List.of("a.xml", "b.xml"), files(seen), "the one next round delivered: it did not list on the closed session");
        assertEquals(Set.of(), sftp.files("/inbox"));
        assertEquals(Health.Status.UP, SftpComponentTest.health(component), "that round connected and listed");
        assertEquals(2, sftp.sessionsCreated(), "a new session for the round after the idle close");
    }

    @Test
    void anOldDirectoryWhoseNameMatchesIsNeverTaken() throws IOException {
        Path dir = sftp.mkdir("/inbox/old.xml");
        Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.from(EmbeddedSftp.FIRST_MTIME.minusSeconds(60)));   // older than every file
        sftp.drop("/inbox", "b.xml", Buffer.buffer("<b/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = component();
        started(component, sftp.endpoint("in", "/inbox", Map.of("glob", "*.xml")), accepting(seen));
        round(rt);
        assertEquals(List.of("b.xml"), files(seen), "the directory was not taken, the file was");
        assertEquals(1, sftp.sessionsCreated(), "no round failed on it");
        assertEquals(Health.Status.UP, component.health().await().status());
    }

    @Test
    void aFileGoneBeforeItsMoveCountsAsDoneAndTheRoundGoesOn() {
        sftp.mkdir("/done");
        sftp.drop("/inbox", "f1.xml", Buffer.buffer("<one/>"));
        sftp.drop("/inbox", "f2.xml", Buffer.buffer("<two/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(), sftp.endpoint("in", "/inbox", Map.of("afterSuccess", "move", "moveTo", "/done")), e -> {
            seen.add(e);
            if (seen.size() == 1) {
                try {
                    Files.delete(sftp.local("/inbox/f1.xml"));   // another node took it meanwhile
                } catch (IOException x) {
                    throw new AssertionError(x);
                }
            }
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        round(rt);
        assertEquals(List.of("f1.xml", "f2.xml"), files(seen), "the vanished file's move counted as done: the round went on");
        assertEquals(Set.of("f2.xml"), sftp.files("/done"));
    }

    @Test
    void anErrorDirThatIsNotADirectoryKeepsTheFile() throws IOException {
        Files.writeString(sftp.local("/error"), "a file, not a directory");
        sftp.drop("/inbox", "bad.xml", Buffer.buffer("<bad/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(), sftp.endpoint("in", "/inbox", Map.of("errorDir", "/error")), e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Nack("no", false));
        });
        round(rt);
        assertEquals(List.of("bad.xml"), files(seen));
        assertEquals(Set.of("bad.xml"), sftp.files("/inbox"), "the move failed: the file stays");
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------------

    @Test
    void aConsumerStoppedBeforeItStartedStopsAtOnce() throws Exception {
        SftpComponent component = component();
        Consumer consumer = component.consumer(sftp.endpoint("in", "/inbox", Map.of()), accepting(new ArrayList<>()));
        consumer.stop().await(10, TimeUnit.SECONDS);
        assertEquals(0, rt.timer().pending(), "no round was ever scheduled");
        assertEquals(Health.Status.DOWN, component.health().await().status());
        assertEquals(0, sftp.sessionsCreated());
    }

    @Test
    void roundZeroIsScheduledAtOnceOnStart() {
        started(component(), sftp.endpoint("in", "/inbox", Map.of("intervalMs", 60_000)), accepting(new CopyOnWriteArrayList<>()));
        assertEquals(1, rt.timer().pending(), "start scheduled round 0");
        assertEquals(Duration.ZERO, rt.timer().nextDelay(), "round 0 at once on start, not an intervalMs later");
    }

    @Test
    void aStopMidRoundTakesNoFurtherFileAndSchedulesNoRoundAfterIt() throws Exception {
        sftp.drop("/inbox", "a.xml", Buffer.buffer("<a/>"));
        sftp.drop("/inbox", "b.xml", Buffer.buffer("<b/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        io.vertx.core.Promise<Ack> answer = io.vertx.core.Promise.promise();
        Consumer consumer = started(component(), sftp.endpoint("in", "/inbox", Map.of()), e -> {
            seen.add(e);
            return seen.size() == 1 ? answer.future() : Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        SftpComponentTest.fire(rt);
        assertTrue(SftpComponentTest.soon(() -> seen.size() == 1), "a.xml delivered, its answer pending");
        Future<Void> stopped = consumer.stop();
        assertFalse(stopped.isComplete(), "the round in flight holds the stop");
        answer.complete(new Ack.Accepted(seen.get(0).id()));
        stopped.await(10, TimeUnit.SECONDS);   // completes only after the round ended: its end decided on the next round first
        assertEquals(0, rt.timer().pending(), "no round scheduled after the stop, not even by the round that ended after it");
        assertEquals(List.of("a.xml"), files(seen), "no file taken once the stop began");
        assertEquals(Set.of("b.xml"), sftp.files("/inbox"), "a.xml removed before the stop completed, b.xml left");
    }

    // ---- the private-key login and a refused move, on a server of this test's own ----------------------------------------

    static KeyPair ecKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static String openSsh(KeyPair key) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "flottila-test", (OpenSSHKeyEncryptionContext) null, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** A server that admits one public key and can refuse the next rename. */
    static final class KeyServer implements AutoCloseable {
        final SshServer server = SshServer.setUpDefaultServer();
        final AtomicBoolean refuseNextMove = new AtomicBoolean();

        KeyServer(Path root, KeyPair client) throws IOException {
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(KeyPairProvider.wrap(EmbeddedSftp.HOST_KEY));
            server.setPublickeyAuthenticator((user, key, session) -> EmbeddedSftp.USER.equals(user) && KeyUtils.compareKeys(key, client.getPublic()));
            server.setFileSystemFactory(new VirtualFileSystemFactory(root));
            SftpSubsystemFactory factory = new SftpSubsystemFactory();
            factory.addSftpEventListener(new SftpEventListener() {
                @Override public void moving(ServerSession session, Path src, Path dst, Collection<CopyOption> opts) throws IOException {
                    if (refuseNextMove.getAndSet(false)) throw new IOException("the test refused the rename");
                }
            });
            server.setSubsystemFactories(List.of(factory));
            server.start();
        }

        EndpointConfig endpoint(String privateKey, Map<String, Object> extra) {
            Map<String, Object> options = new HashMap<>(Map.of("user", EmbeddedSftp.USER, "privateKey", privateKey,
                    "hostKey", EmbeddedSftp.fingerprint(EmbeddedSftp.HOST_KEY), "maxBodyBytes", 1_000_000));
            options.putAll(extra);
            return new EndpointConfig("keyed", URI.create("sftp://127.0.0.1:" + server.getPort() + "/inbox"), options);
        }

        @Override public void close() throws IOException { server.stop(true); }
    }

    @Test
    void aPrivateKeyLogsInAndAMoveTheServerRefusesKeepsTheFile() throws Exception {
        KeyPair client = ecKey();
        sftp.mkdir("/done");
        try (KeyServer server = new KeyServer(root, client)) {
            sftp.drop("/inbox", "k.xml", Buffer.buffer("<k/>"));
            server.refuseNextMove.set(true);
            List<Envelope> seen = new CopyOnWriteArrayList<>();
            SftpComponent component = component();
            started(component, server.endpoint(openSsh(client), Map.of("afterSuccess", "move", "moveTo", "/done")), accepting(seen));
            round(rt);
            assertEquals(List.of("k.xml"), files(seen), "logged in with the private key and delivered");
            assertEquals(Set.of("k.xml"), sftp.files("/inbox"), "the refused rename kept the file");
            assertEquals(Set.of(), sftp.files("/done"));
            round(rt);
            assertEquals(List.of("k.xml", "k.xml"), files(seen), "delivered again");
            assertEquals(Set.of("k.xml"), sftp.files("/done"), "moved this time");
            assertEquals(Health.Status.UP, component.health().await().status());
        }
    }

    @Test
    void aPrivateKeyTheServerDoesNotKnowIsRefusedAndAMalformedOneTurnsHealthDown() throws Exception {
        KeyPair client = ecKey();
        try (KeyServer server = new KeyServer(root, client)) {
            sftp.drop("/inbox", "k.xml", Buffer.buffer("<k/>"));
            List<Envelope> seen = new CopyOnWriteArrayList<>();
            SftpComponent stranger = component();
            started(stranger, server.endpoint(openSsh(ecKey()), Map.of()), accepting(seen));
            round(rt);
            assertEquals(Health.Status.DOWN, stranger.health().await().status(), "an unknown key does not log in");

            for (String unreadable : List.of(
                    "-----BEGIN OPENSSH PRIVATE KEY-----\nnot a key\n-----END OPENSSH PRIVATE KEY-----\n",   // an IOException
                    "-----BEGIN PRIVATE KEY-----\nMBQCAQAwBwYFK4EEACIEBjAEAgEBBAA=\n-----END PRIVATE KEY-----\n",   // an unknown algorithm
                    "not a key at all")) {   // no key
                FakeRuntime other = FakeRuntime.create();
                SftpComponent malformed = new SftpComponent(other.blobs(), other.ids(), other.timer(), other.clock());
                started(malformed, server.endpoint(unreadable, Map.of()), accepting(seen));
                round(other);
                assertEquals(Health.Status.DOWN, malformed.health().await().status(), "a key that cannot be read does not log in: " + unreadable);
            }
            assertEquals(List.of(), seen);
            assertFalse(sftp.files("/inbox").isEmpty());
        }
    }
}
