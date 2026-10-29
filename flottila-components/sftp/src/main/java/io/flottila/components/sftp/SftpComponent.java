package io.flottila.components.sftp;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.IdGenerator;
import io.flottila.api.Key;
import io.flottila.api.Producer;
import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The {@code sftp} component (D-076, ARCHITECTURE §7): a polling consumer on the Apache MINA SSHD client; no producer before
 * Phase 4. URI {@code sftp://host[:port, default 22]/dir}; options (validated against {@link #configSchema()}): {@code user},
 * exactly one of {@code password} / {@code privateKey}, {@code hostKey} (the server key's {@code SHA256:} fingerprint — a
 * mismatch refuses the session, there is no accept-all), {@code maxBodyBytes} (set by the deploy, never unlimited), and
 * {@code glob} ({@code *}), {@code intervalMs} (10 000), {@code maxFilesPerPoll} (100), {@code minAgeMs} (0),
 * {@code afterSuccess} ({@code delete} | {@code move}, then {@code moveTo}), {@code connectTimeoutMs} (10 000),
 * {@code ioTimeoutMs} (30 000), optional {@code errorDir} and {@code flow}.
 *
 * <p>A round runs on a virtual thread the consumer starts (the timer only fires it; the next round is scheduled
 * {@code intervalMs} after it ended): it lists the directory, keeps the regular files whose name matches the glob and whose
 * server mtime is at least {@code minAgeMs} behind the injected clock, takes the oldest (mtime, name) first, at most
 * {@code maxFilesPerPoll}, one at a time. A file listed above {@code maxBodyBytes} is refused finally without being read;
 * otherwise it streams into the {@link BlobStore} bounded by {@code maxBodyBytes} and goes to the handler on a virtual thread
 * of its own (D-057), the round waiting for the answer. {@link Ack.Accepted} or {@link Ack.Reply} (the durable save is the
 * handler's) deletes the file or renames it into {@code moveTo} ({@code <name>.<id>} when the name is taken, the id the
 * Accepted names — an earlier message's for a file the save found delivered before, D-084); a file already
 * gone counts as done. A retryable Nack, a failed answer, a throw or null keep the file and end the round (the poller pauses,
 * §4). A final Nack, or a body above the limit, moves the file to {@code errorDir} if set, else leaves it and never takes it
 * again in this consumer's life. Any I/O failure ends the round and drops the session; the file stays.
 *
 * <p>One SSH session per consumer, reused across rounds; one MINA client per consumer, its NIO2 pool sized to one thread
 * (transport-internal, D-076). Health is up while at least one consumer runs and none's last round failed to connect or list.
 *
 * @since 0.1
 */
public final class SftpComponent implements Component {

    /** The file's name. */
    public static final Key<String> FILE = new Key<>("sftp.file");
    /** The file's full remote path. */
    public static final Key<String> PATH = new Key<>("sftp.path");
    /** The file's server mtime, ISO-8601. */
    public static final Key<String> MTIME = new Key<>("sftp.mtime");
    /** {@code sftp://host:port/path@mtime#bodyHash}: the same file on every node (the save's duplicate check, P2-02D). */
    public static final Key<String> FILE_KEY = new Key<>("sftp.fileKey");

    static final String CONFIG_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "properties":{"user":{"type":"string","minLength":1},"password":{"type":"string"},"privateKey":{"type":"string"},
              "hostKey":{"type":"string","pattern":"^SHA256:[A-Za-z0-9+/]+=*$"},"maxBodyBytes":{"type":"integer","minimum":1},
              "flow":{"type":"string"},"glob":{"type":"string","minLength":1},"intervalMs":{"type":"integer","minimum":1},
              "maxFilesPerPoll":{"type":"integer","minimum":1},"minAgeMs":{"type":"integer","minimum":0},
              "afterSuccess":{"enum":["delete","move"]},"moveTo":{"type":"string","minLength":1},"errorDir":{"type":"string","minLength":1},
              "connectTimeoutMs":{"type":"integer","minimum":1},"ioTimeoutMs":{"type":"integer","minimum":1}},
             "required":["user","hostKey"],
             "oneOf":[{"required":["password"]},{"required":["privateKey"]}],
             "if":{"properties":{"afterSuccess":{"const":"move"}},"required":["afterSuccess"]},"then":{"required":["moveTo"]}}""";

    /** The MINA client's NIO2 I/O threads: the minimum (D-076); a consumer's round blocks on its own virtual thread. */
    static final int NIO_WORKERS = 1;
    static final int DEFAULT_PORT = 22;

    private final BlobStore blobs;
    private final IdGenerator ids;
    private final Timer timer;
    private final Clock clock;
    private final Map<SftpConsumer, Boolean> running = new ConcurrentHashMap<>();   // value: its last round connected and listed
    private final AtomicLong handled = new AtomicLong();   // numbers the handler threads

    public SftpComponent(BlobStore blobs, IdGenerator ids, Timer timer, Clock clock) {
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String scheme() { return "sftp"; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        return new SftpConsumer(cfg, handler);
    }

    /** Every sftp endpoint polls a directory other nodes may poll too: the node runs its consumer only under the endpoint's lease (D-083). */
    @Override
    public boolean polls(EndpointConfig cfg) {
        return true;
    }

    /**
     * Every sftp endpoint names {@link #FILE_KEY} as its dedupe key (D-084): the same file delivered again — its removal refused
     * after the save, another node polling it after a takeover — is the one message it was saved as.
     */
    @Override
    public Optional<Key<String>> dedupeBy(EndpointConfig cfg) {
        return Optional.of(FILE_KEY);
    }

    /** Refused: the flow build reports it (D-076). */
    @Override
    public Producer producer(EndpointConfig cfg) {
        throw new IllegalArgumentException("sftp has no producer before Phase 4");
    }

    @Override
    public Future<Health> health() {
        if (running.isEmpty()) return Future.succeededFuture(Health.down("no consumer polling"));
        return Future.succeededFuture(running.containsValue(Boolean.FALSE) ? Health.down("a consumer's last round could not connect or list") : Health.up());
    }

    // --- options

    private static String text(Map<String, Object> options, String key, String fallback) {
        Object v = options.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static String required(EndpointConfig cfg, String key) {
        Object v = cfg.options().get(key);
        if (v == null) throw new IllegalArgumentException("endpoint '" + cfg.name() + "': option '" + key + "' is required");
        return String.valueOf(v);
    }

    private static long number(Map<String, Object> options, String key, long fallback) {
        return options.get(key) instanceof Number n ? n.longValue() : fallback;
    }

    /** {@code name} in the remote directory {@code dir}. */
    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static boolean noSuchFile(SftpException e) {
        return e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE;
    }

    /** A listed file. */
    private record Remote(String name, String path, long size, Instant mtime) {
        /** What a final refusal without an error directory skips: this path at this mtime and size. */
        String refusedAs() { return path + "@" + mtime + "/" + size; }
    }

    // --- consumer

    /** @since 0.1 */
    public final class SftpConsumer implements Consumer {
        private final Function<Envelope, Future<Ack>> handler;
        private final String name;
        private final String host;
        private final int port;
        private final String dir;
        private final String user;
        private final String password;     // null when the endpoint logs in with a private key
        private final String privateKey;   // null when it logs in with a password
        private final String hostKey;
        private final long maxBodyBytes;
        private final String flow;
        private final PathMatcher glob;
        private final Duration interval;
        private final long maxFilesPerPoll;
        private final Duration minAge;
        private final String moveTo;       // null: delete after success
        private final String errorDir;     // null: a final refusal leaves the file and skips it
        private final Duration connectTimeout;
        private final SshClient client;
        private final Set<String> refused = ConcurrentHashMap.newKeySet();
        private final Promise<Void> stopped = Promise.promise();
        // the round's thread only, and close() once the last round ended (ordered by the monitor)
        private ClientSession session;
        private SftpClient sftp;
        // guarded by this
        private Timer.Cancellable next;
        private boolean roundInFlight;
        private boolean stopping;

        SftpConsumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            Map<String, Object> o = cfg.options();
            this.name = cfg.name();
            URI uri = cfg.uri();
            this.host = uri.getHost();
            this.port = uri.getPort() < 0 ? DEFAULT_PORT : uri.getPort();
            this.dir = uri.getPath();
            this.user = required(cfg, "user");
            this.password = text(o, "password", null);
            this.privateKey = text(o, "privateKey", null);
            this.hostKey = required(cfg, "hostKey");
            if (!(o.get("maxBodyBytes") instanceof Number max) || max.longValue() < 1) {
                throw new IllegalArgumentException("endpoint '" + name + "': option 'maxBodyBytes' must be a positive integer (never unlimited), was " + o.get("maxBodyBytes"));
            }
            this.maxBodyBytes = max.longValue();
            this.flow = text(o, "flow", name);
            this.glob = FileSystems.getDefault().getPathMatcher("glob:" + text(o, "glob", "*"));
            this.interval = Duration.ofMillis(number(o, "intervalMs", 10_000));
            this.maxFilesPerPoll = number(o, "maxFilesPerPoll", 100);
            this.minAge = Duration.ofMillis(number(o, "minAgeMs", 0));
            this.moveTo = "move".equals(o.get("afterSuccess")) ? required(cfg, "moveTo") : null;
            this.errorDir = text(o, "errorDir", null);
            this.connectTimeout = Duration.ofMillis(number(o, "connectTimeoutMs", 10_000));
            this.client = SshClient.setUpDefaultClient();
            CoreModuleProperties.NIO_WORKERS.set(client, NIO_WORKERS);
            CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, connectTimeout);
            // bounds every SFTP answer; a session idle that long (a slow handler) is closed and reopened for the delete
            CoreModuleProperties.IDLE_TIMEOUT.set(client, Duration.ofMillis(number(o, "ioTimeoutMs", 30_000)));
            client.setServerKeyVerifier((s, address, key) -> hostKey.equals(KeyUtils.getFingerPrint(key)));
            client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);    // never ~/.ssh/config
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);   // never ~/.ssh/id_*
        }

        /** Schedules round 0 and completes at once: a partner down never fails a deploy. Called once. */
        @Override
        public Future<Void> start() {
            client.start();
            synchronized (this) {
                running.put(this, Boolean.TRUE);
                next = timer.schedule(Duration.ZERO, this::fire);
            }
            return Future.succeededFuture();
        }

        /**
         * Stops (D-056): no further round, no new file; the answer in flight is awaited and its delete or move done, then
         * the session and the client close. Health no longer counts this consumer from now on.
         */
        @Override
        public Future<Void> stop() {
            boolean idle;
            synchronized (this) {
                stopping = true;
                running.remove(this);
                if (next != null) next.cancel();
                idle = !roundInFlight;
            }
            if (idle) Thread.ofVirtual().name("flottila-sftp-stop").start(this::close);
            return stopped.future();
        }

        /** The timer's task: starts the round on a virtual thread of its own, never on the timer's thread. */
        private void fire() {
            synchronized (this) {
                if (stopping) return;   // fired as it was cancelled
                roundInFlight = true;
            }
            Thread.ofVirtual().name("flottila-sftp-round-" + name).start(this::round);
        }

        private void round() {
            try {
                for (Remote file : due()) {
                    synchronized (this) {
                        if (stopping) break;   // stop takes no new file
                    }
                    if (!take(file)) break;
                }
            } catch (IOException | RuntimeException failed) {
                drop();   // the file stays; the next round opens a new session
            } finally {
                ended();
            }
        }

        private void ended() {
            boolean close;
            synchronized (this) {
                roundInFlight = false;
                close = stopping;
                if (!stopping) next = timer.schedule(interval, this::fire);
            }
            if (close) close();
        }

        private void close() {
            try {
                drop();
                client.stop();
            } finally {
                stopped.tryComplete();
            }
        }

        /** The files this round takes, oldest first; a failure to connect or list turns health down until a round lists again. */
        private List<Remote> due() throws IOException {
            List<Remote> listed = new ArrayList<>();
            try {
                for (SftpClient.DirEntry entry : sftp().readDir(dir)) {
                    SftpClient.Attributes a = entry.getAttributes();
                    listed.add(new Remote(entry.getFilename(), join(dir, entry.getFilename()), a.getSize(),
                            a.isRegularFile() ? a.getModifyTime().toInstant() : null));
                }
                running.replace(this, Boolean.TRUE);
            } catch (IOException | RuntimeException failed) {
                running.replace(this, Boolean.FALSE);
                throw failed;
            }
            Instant oldEnough = clock.instant().minus(minAge);
            return listed.stream()
                    .filter(f -> f.mtime() != null && glob.matches(Path.of(f.name())) && !f.mtime().isAfter(oldEnough) && !refused.contains(f.refusedAs()))
                    .sorted(Comparator.comparing(Remote::mtime).thenComparing(Remote::name))
                    .limit(maxFilesPerPoll)
                    .toList();
        }

        /** Takes one file; whether the round goes on. */
        private boolean take(Remote file) throws IOException {
            if (file.size() > maxBodyBytes) return refuse(file, ids.next());   // never read
            String id = ids.next();
            SftpClient remote = sftp();
            Download download = new Download(() -> remote.read(file.path()));
            Future<Body> written = blobs.write(BlobKey.body(id, 0), download, maxBodyBytes);
            written.otherwiseEmpty().await();
            download.settle(written.failed());
            if (written.failed()) {
                if (written.cause() instanceof BodyTooLargeException) return refuse(file, id);   // grew past the limit while read
                throw new IOException("reading " + file.path() + " failed", written.cause());
            }
            Body body = written.result();
            Envelope in = Envelope.ingress(id, flow, Headers.empty(), body)
                    .with(FILE, file.name())
                    .with(PATH, file.path())
                    .with(MTIME, file.mtime().toString())
                    .with(FILE_KEY, "sftp://" + host + ":" + port + file.path() + "@" + file.mtime() + "#" + body.hash().hex());
            return switch (answer(in)) {
                case Ack.Accepted accepted -> done(file, accepted.messageId());   // the message the file is: maybe an earlier one (D-084)
                case Ack.Reply reply -> done(file, id);
                case Ack.Nack nack when nack.retryable() -> false;   // the flow is full: the poller pauses until the next round
                case Ack.Nack nack -> refuse(file, id);
                case null -> false;   // a failed answer, a throw or null: the file stays, the round ends
            };
        }

        /**
         * Calls the handler on a virtual thread of its own (D-057) and waits for its answer; null when the answer failed,
         * the handler threw or answered null ({@link Future#future} catches either).
         */
        private Ack answer(Envelope in) {
            Promise<Ack> answer = Promise.promise();
            Thread.ofVirtual().name("flottila-sftp-" + handled.incrementAndGet())
                    .start(() -> Future.<Ack>future(p -> handler.apply(in).onComplete(p)).onComplete(answer));
            return answer.future().otherwiseEmpty().await();
        }

        /** After the handler's Accepted or Reply: delete, or move into {@code moveTo}. */
        private boolean done(Remote file, String id) throws IOException {
            if (moveTo == null) {
                try {
                    sftp().remove(file.path());
                } catch (SftpException e) {
                    if (!noSuchFile(e)) throw e;   // gone already (another node's delete): done
                }
            } else {
                move(file, moveTo, id);
            }
            return true;
        }

        /** A final refusal: into {@code errorDir}, else left where it is and skipped from now on. */
        private boolean refuse(Remote file, String id) throws IOException {
            if (errorDir == null) refused.add(file.refusedAs());
            else move(file, errorDir, id);
            return true;
        }

        /** Renames {@code file} into the existing directory {@code to}, under {@code <name>.<id>} when its name is taken there. */
        private void move(Remote file, String to, String id) throws IOException {
            SftpClient remote = sftp();
            String target = join(to, file.name());
            try {
                remote.lstat(target);
                target = target + "." + id;   // taken
            } catch (SftpException absent) {
                // not there (or not to be known): the rename below never overwrites, so a name taken after all refuses it
            }
            try {
                remote.rename(file.path(), target);
            } catch (SftpException e) {
                if (!noSuchFile(e)) throw e;   // gone already: done
            }
        }

        /**
         * The session's SFTP channel, (re)connected and logged in when there is none or the session is closing or closed (the
         * partner went away, or it sat idle for {@code ioTimeoutMs} while a handler worked). Set and dropped together with it.
         */
        private SftpClient sftp() throws IOException {
            if (sftp != null && session.isOpen()) return sftp;
            drop();
            session = client.connect(user, host, port).verify(connectTimeout).getSession();
            if (password != null) session.addPasswordIdentity(password);
            if (privateKey != null) {
                for (KeyPair key : keys()) session.addPublicKeyIdentity(key);
            }
            session.auth().verify(connectTimeout);   // a host key other than hostKey fails here, before any credential is sent
            sftp = SftpClientFactory.instance().createSftpClient(session);
            return sftp;
        }

        private Iterable<KeyPair> keys() throws IOException {
            try {
                return SecurityUtils.loadKeyPairIdentities(session, NamedResource.ofName("privateKey of endpoint '" + name + "'"),
                        new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)), FilePasswordProvider.EMPTY);
            } catch (GeneralSecurityException e) {
                throw new IOException("endpoint '" + name + "': privateKey cannot be read", e);
            }
        }

        /** Closes the session at once, if any: after an I/O failure, and at the stop. */
        private void drop() {
            ClientSession s = session;
            session = null;
            sftp = null;
            if (s != null) s.close(true);
        }
    }
}
