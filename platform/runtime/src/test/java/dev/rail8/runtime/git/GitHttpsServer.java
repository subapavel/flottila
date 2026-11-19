package dev.rail8.runtime.git;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * A git server over https for tests: the bare repositories of a {@link GitSource} under {@code /group/}, served by JGit's
 * {@code UploadPack} in git's smart HTTP protocol. It can ask for basic credentials, redirect a path, and hold a request until the
 * test releases it. Its certificate is self-signed for {@code localhost} and {@code 127.0.0.1}, made with the JDK's keytool.
 */
final class GitHttpsServer implements AutoCloseable {

    /** One request as the server saw it. */
    record Request(String method, String path, Optional<String> authorization) {}

    private static final String PASS = "git-https-test";

    private final Path repositories;
    private final HttpsServer server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final SSLContext clientTls;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> redirects = new ConcurrentHashMap<>();
    private volatile String basic;
    private volatile CountDownLatch holding;
    private final CountDownLatch release = new CountDownLatch(1);

    private GitHttpsServer(Path repositories, HttpsServer server, SSLContext clientTls) {
        this.repositories = repositories;
        this.server = server;
        this.clientTls = clientTls;
    }

    /** A server over the bare repositories in {@code repositories}, its key material made under {@code keys}. */
    static GitHttpsServer start(Path repositories, Path keys) throws Exception {
        Path store = Files.createDirectories(keys).resolve("server.p12");
        keytool(keys, "-genkeypair", "-alias", "git", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost", "-validity", "2",
                "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASS,
                "-keypass", PASS);
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            serverKeys.load(in, PASS.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, PASS.toCharArray());
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManagers.getKeyManagers(), null, null);

        Certificate certificate = serverKeys.getCertificate("git");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("git", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManagers.getTrustManagers(), null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        GitHttpsServer git = new GitHttpsServer(repositories, server, clientTls);
        server.setExecutor(git.threads);
        server.createContext("/", git::handle);
        server.start();
        return git;
    }

    /** The base as {@code host} names this server. */
    URI base(String host) {
        return URI.create("https://" + host + ":" + server.getAddress().getPort() + "/group/");
    }

    /** The TLS a client that trusts this server uses. */
    SSLContext clientTls() {
        return clientTls;
    }

    /** Asks every request for basic credentials of {@code user} and {@code token}. */
    void requireCredentials(String user, String token) {
        basic = "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    /** Redirects a request whose path starts with {@code prefix}: the rest of its path and its query follow {@code to}. */
    void redirect(String prefix, String to) {
        redirects.put(prefix, to);
    }

    /** Holds the next request until {@link #release()}; answers a latch that opens when the request arrives. */
    CountDownLatch hold() {
        CountDownLatch arrived = new CountDownLatch(1);
        holding = arrived;
        return arrived;
    }

    void release() {
        release.countDown();
    }

    List<Request> requests() {
        return new ArrayList<>(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            requests.add(new Request(exchange.getRequestMethod(), uri.getPath(),
                    Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization"))));
            CountDownLatch arrived = holding;
            if (arrived != null) {
                holding = null;
                arrived.countDown();
                if (!release.await(10, TimeUnit.MINUTES)) return;
            }
            for (Map.Entry<String, String> redirect : redirects.entrySet()) {
                if (uri.getPath().startsWith(redirect.getKey())) {
                    String rest = uri.getPath().substring(redirect.getKey().length());
                    exchange.getResponseHeaders().set("Location", redirect.getValue() + rest + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
            }
            String expected = basic;
            if (expected != null && !expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"git\"");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            String[] parts = uri.getPath().split("/", 4);
            if (parts.length < 4 || !parts[1].equals("group") || !Files.isDirectory(repositories.resolve(parts[2]))) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            try (Repository repository = new FileRepositoryBuilder().setGitDir(repositories.resolve(parts[2]).toFile()).setMustExist(true).build()) {
                if (parts[3].equals("info/refs") && "service=git-upload-pack".equals(uri.getQuery())) {
                    exchange.getResponseHeaders().set("Content-Type", "application/x-git-upload-pack-advertisement");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream out = exchange.getResponseBody()) {
                        PacketLineOut lines = new PacketLineOut(out);
                        lines.writeString("# service=git-upload-pack\n");
                        lines.end();
                        UploadPack upload = new UploadPack(repository);
                        upload.setBiDirectionalPipe(false);
                        upload.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(lines));
                    }
                } else if (parts[3].equals("git-upload-pack") && exchange.getRequestMethod().equals("POST")) {
                    InputStream in = exchange.getRequestBody();
                    if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) in = new GZIPInputStream(in);
                    exchange.getResponseHeaders().set("Content-Type", "application/x-git-upload-pack-result");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream out = exchange.getResponseBody()) {
                        UploadPack upload = new UploadPack(repository);
                        upload.setBiDirectionalPipe(false);
                        upload.upload(in, out, null);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        release();
        server.stop(0);
        threads.shutdownNow();
    }

    private static void keytool(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Path log = cwd.resolve("keytool.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("fixture: keytool did not end within 60 s");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("fixture: keytool exited " + process.exitValue() + ": " + Files.readString(log));
    }
}
