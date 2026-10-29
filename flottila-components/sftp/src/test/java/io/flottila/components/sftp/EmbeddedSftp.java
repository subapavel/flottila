package io.flottila.components.sftp;

import io.flottila.api.EndpointConfig;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.Handle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * An Apache MINA SSHD server with the SFTP subsystem on 127.0.0.1, its file system a {@link VirtualFileSystemFactory} rooted
 * at a test's directory (remote {@code /inbox} is {@code root/inbox}), password login for {@link #USER}, an EC host key.
 * It counts sessions and password checks, records what the client opened, read, closed, removed and moved, and lets a test act
 * once inside a removal, an open or a read (drop the session, fail the read, grow the file).
 *
 * <p>Public, with what a test of another module needs (the sftp test-jar): runtime's two-node lease test (P2-02L).
 */
public final class EmbeddedSftp implements AutoCloseable {

    static final String USER = "flottila";
    static final String PASSWORD = "s3cret-for-tests";
    static final KeyPair HOST_KEY = ecKey();
    static final KeyPair OTHER_KEY = ecKey();
    /** Where dropped files' mtimes start: an hour before the fake clock's start, so every drop is old enough by default. */
    static final Instant FIRST_MTIME = FakeRuntime.T0.minusSeconds(3600);

    final Path root;
    private final SshServer server;
    private final AtomicInteger sessionsCreated = new AtomicInteger();
    private final AtomicInteger sessionsClosed = new AtomicInteger();
    private final AtomicInteger passwordChecks = new AtomicInteger();
    private final AtomicLong drops = new AtomicLong();
    /**
     * "open /inbox/a.xml", "read /inbox/a.xml", "close /inbox/a.xml", "remove /inbox/a.xml", "move /inbox/a.xml -> /done/a.xml",
     * in the order the server handled them (a close is recorded once the server closed the file handle, before it answers).
     */
    final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<Hook> onRemoving = new AtomicReference<>();
    private final AtomicReference<Hook> onOpening = new AtomicReference<>();
    private final AtomicReference<Hook> onReading = new AtomicReference<>();

    /** What a test does once inside the server's handling of a file, on the server's thread; a throw refuses the operation. */
    @FunctionalInterface
    interface Hook {
        void at(ServerSession session, String path) throws IOException;
    }

    private EmbeddedSftp(Path root, int port, KeyPair hostKey) throws IOException {
        this.root = root;
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(port);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setPasswordAuthenticator((user, password, session) -> {
            passwordChecks.incrementAndGet();
            return USER.equals(user) && PASSWORD.equals(password);
        });
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        SftpSubsystemFactory sftp = new SftpSubsystemFactory();
        sftp.addSftpEventListener(new Listener());
        server.setSubsystemFactories(List.of(sftp));
        server.addSessionListener(new SessionListener() {
            @Override public void sessionCreated(Session session) { sessionsCreated.incrementAndGet(); }
            @Override public void sessionClosed(Session session) { sessionsClosed.incrementAndGet(); }
        });
        server.start();
    }

    /** A server on a free port (the kernel's choice at bind time). */
    public static EmbeddedSftp start(Path root) throws IOException { return new EmbeddedSftp(root, 0, HOST_KEY); }

    /** A server on {@code port} with {@code hostKey}: the same partner again after a restart. */
    static EmbeddedSftp start(Path root, int port, KeyPair hostKey) throws IOException { return new EmbeddedSftp(root, port, hostKey); }

    public int port() { return server.getPort(); }

    /** The SHA256 fingerprint of {@code key} as OpenSSH prints it ({@code SHA256:<base64, unpadded>}): the endpoint's {@code hostKey}. */
    static String fingerprint(KeyPair key) { return KeyUtils.getFingerPrint(key.getPublic()); }

    public int sessionsCreated() { return sessionsCreated.get(); }

    int sessionsClosed() { return sessionsClosed.get(); }

    int passwordChecks() { return passwordChecks.get(); }

    /** Runs {@code hook} at the next removal the client asks for, once. */
    void onNextRemoving(Hook hook) { onRemoving.set(hook); }

    /** Runs {@code hook} when the client next opens a file for reading, once, before it reads a byte. */
    void onNextOpening(Hook hook) { onOpening.set(hook); }

    /** Runs {@code hook} at the client's next read request, once. */
    void onNextReading(Hook hook) { onReading.set(hook); }

    /** The local path behind the remote absolute path {@code remote}. */
    Path local(String remote) { return root.resolve(remote.replaceFirst("^/", "")); }

    /** Creates the remote directory {@code remote}. */
    public Path mkdir(String remote) {
        try {
            return Files.createDirectories(local(remote));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Drops {@code name} into the remote directory {@code dir}, one second younger than the drop before it. */
    public void drop(String dir, String name, Buffer bytes) {
        drop(dir, name, bytes, FIRST_MTIME.plusSeconds(drops.getAndIncrement()));
    }

    /**
     * Drops {@code name} with {@code bytes} and the modification time {@code mtime} into the remote directory {@code dir}: written
     * and dated aside, then moved in at once, so a listing never sees it half-written.
     */
    void drop(String dir, String name, Buffer bytes, Instant mtime) {
        try {
            Path staging = Files.createDirectories(root.resolve(".staging"));   // outside every polled directory
            Path aside = staging.resolve(name);
            Files.write(aside, bytes.getBytes());
            Files.setLastModifiedTime(aside, FileTime.from(mtime));
            Files.move(aside, Files.createDirectories(local(dir)).resolve(name), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The names of the regular files in the remote directory {@code dir}. */
    public Set<String> files(String dir) {
        Path local = local(dir);
        if (!Files.isDirectory(local)) return Set.of();
        try (Stream<Path> listed = Files.list(local)) {
            return listed.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The bytes of the remote file {@code path}. */
    String read(String path) {
        try {
            return Files.readString(local(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the client opened the remote file {@code path} to read it. */
    boolean opened(String path) { return events.contains("open " + path); }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }

    private final class Listener implements SftpEventListener {
        private final Map<String, Boolean> readSeen = new HashMap<>();

        @Override public void opening(ServerSession session, String remoteHandle, Handle localHandle) throws IOException {
            if (!(localHandle instanceof FileHandle)) return;
            String path = localHandle.getFile().toString();
            events.add("open " + path);
            fire(onOpening, session, path);
        }

        @Override public void reading(ServerSession session, String remoteHandle, FileHandle localHandle, long offset, byte[] data, int dataOffset,
                                      int dataLen) throws IOException {
            String path = localHandle.getFile().toString();
            synchronized (readSeen) {
                if (readSeen.put(remoteHandle, Boolean.TRUE) == null) events.add("read " + path);
            }
            fire(onReading, session, path);
        }

        @Override public void closed(ServerSession session, String remoteHandle, Handle localHandle, Throwable thrown) {
            if (localHandle instanceof FileHandle) events.add("close " + localHandle.getFile());
        }

        @Override public void removing(ServerSession session, Path path, boolean isDirectory) throws IOException {
            events.add("remove " + path);
            fire(onRemoving, session, path.toString());
        }

        @Override public void moving(ServerSession session, Path srcPath, Path dstPath, Collection<CopyOption> opts) {
            events.add("move " + srcPath + " -> " + dstPath);
        }

        private void fire(AtomicReference<Hook> slot, ServerSession session, String path) throws IOException {
            Hook hook = slot.getAndSet(null);   // once
            if (hook != null) hook.at(session, path);
        }
    }

    private static KeyPair ecKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no EC key generator on this JDK", e);
        }
    }

    /**
     * An endpoint polling the remote directory {@code dir} of this server: URI {@code sftp://127.0.0.1:<port><dir>}, the
     * required options (this server's user, password and host key, a body limit) plus {@code extra}, which may override them.
     */
    public EndpointConfig endpoint(String name, String dir, Map<String, Object> extra) {
        Map<String, Object> options = new HashMap<>(Map.of("user", USER, "password", PASSWORD, "hostKey", fingerprint(HOST_KEY),
                "maxBodyBytes", 1_000_000, "flow", "contract"));
        options.putAll(extra);
        return new EndpointConfig(name, URI.create("sftp://127.0.0.1:" + port() + dir), options);
    }
}
