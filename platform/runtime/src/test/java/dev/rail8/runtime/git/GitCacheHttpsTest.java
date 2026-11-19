package dev.rail8.runtime.git;

import dev.rail8.runtime.git.GitFailure.Reason;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JGit cache over https: the token sent only to the base's host and kept out of every message, log line and file of the cache,
 * credentials refused, a redirect to another host refused before it is made, a silent server stopped by the timer on a fake clock,
 * and the cache's limit held while a fetch receives.
 */
@Timeout(120)
class GitCacheHttpsTest {

    private static final String USER = "rail8-reader";
    private static final String TOKEN = "glpat-Secret-Token-4711";

    @TempDir
    Path temp;

    private final FakeClock clock = FakeClock.at(Instant.parse("2026-11-19T08:00:00Z"));
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<String> logged = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            StringWriter text = new StringWriter();
            text.append(record.getLoggerName()).append(' ').append(String.valueOf(record.getMessage()));
            if (record.getParameters() != null) for (Object parameter : record.getParameters()) text.append(' ').append(String.valueOf(parameter));
            if (record.getThrown() != null) record.getThrown().printStackTrace(new PrintWriter(text));
            synchronized (logged) {
                logged.add(text.toString());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private Level rootLevel;

    private GitSource source;
    private GitHttpsServer server;
    private String orders;

    @BeforeEach
    void start() throws Exception {
        Logger root = Logger.getLogger("");
        rootLevel = root.getLevel();
        root.setLevel(Level.ALL);
        capture.setLevel(Level.ALL);
        root.addHandler(capture);

        source = new GitSource(temp.resolve("source"));
        source.commit("orders", "main", Map.of("package.yaml", PackageRepositoriesContract.file("name: orders\n")));
        orders = source.commit("orders", "main", Map.of("package.yaml", PackageRepositoriesContract.file("name: orders\nversion: 2\n")));
        server = GitHttpsServer.start(temp.resolve("source"), temp.resolve("keys"));
    }

    @AfterEach
    void stop() {
        server.close();
        Logger root = Logger.getLogger("");
        root.removeHandler(capture);
        root.setLevel(rootLevel);
    }

    private PackageRepositories open(URI base, Optional<GitSettings.Credentials> credentials, long maxBytes) {
        GitSettings settings = new GitSettings(base, "", Optional.of("refs/heads/main"), credentials, GitSettings.TIMEOUT_DEFAULT, maxBytes);
        return new GitCache(settings, temp.resolve("cache"), 4L << 20, true, timer, Set::of, server.clientTls().getSocketFactory()).open();
    }

    private PackageRepositories open(Optional<GitSettings.Credentials> credentials) {
        return open(server.base("localhost"), credentials, GitSettings.MAX_BYTES_DEFAULT);
    }

    private static Optional<GitSettings.Credentials> token(String token) {
        return Optional.of(new GitSettings.Credentials(USER, token));
    }

    /** The token and the forms a request would carry it in. */
    private static List<String> secrets() {
        return List.of(TOKEN, Base64.getEncoder().encodeToString((USER + ":" + TOKEN).getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(TOKEN.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertNoSecretIn(String what, String text) {
        for (String secret : secrets()) assertFalse(text.contains(secret), what + " carries the token: " + text);
    }

    private void assertNoSecretLoggedOrCached() throws Exception {
        synchronized (logged) {
            for (String line : logged) assertNoSecretIn("a log line", line);
        }
        Path cache = temp.resolve("cache");
        if (!Files.isDirectory(cache)) return;
        try (Stream<Path> files = Files.walk(cache)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertNoSecretIn(cache.relativize(file).toString(), new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
    }

    private static GitFailure failure(Reason reason, PackageRepositoriesContract.GitFailureCall call) {
        GitFailure failure = assertThrows(GitFailure.class, call::run);
        assertEquals(reason, failure.reason(), failure.getMessage());
        return failure;
    }

    @Test
    void fetchesWithTheTokenAndKeepsItOutOfTheCacheAndTheLogs() throws Exception {
        server.requireCredentials(USER, TOKEN);
        Commit commit = open(token(TOKEN)).forDeploy("orders", orders);
        Path out = Files.createDirectories(temp.resolve("out"));
        commit.write(out);
        assertEquals("name: orders\nversion: 2\n", Files.readString(out.resolve("package.yaml")));

        assertTrue(server.requests().stream().anyMatch(r -> r.authorization().isPresent()), "the token was sent");
        String config = Files.readString(temp.resolve("cache").resolve("orders.git").resolve("config"));
        assertFalse(config.contains("remote"), config);
        assertFalse(config.contains(USER), config);
        assertNoSecretLoggedOrCached();
    }

    @Test
    void refusesCredentialsTheServerRefusesWithTheFormattersTextOnly() throws Exception {
        server.requireCredentials(USER, TOKEN);
        GitFailure wrong = failure(Reason.CREDENTIALS_REFUSED, () -> open(token("glpat-wrong")).forDeploy("orders", orders));
        assertEquals("orders at " + orders + ": credentials refused", wrong.getMessage());
        GitFailure none = failure(Reason.CREDENTIALS_REFUSED, () -> open(Optional.empty()).forDeploy("orders", orders));
        assertEquals("orders at " + orders + ": credentials refused", none.getMessage());
        assertNoSecretLoggedOrCached();
    }

    @Test
    void refusesARedirectToAnotherHostBeforeAnyRequestReachesIt() throws Exception {
        server.requireCredentials(USER, TOKEN);
        try (GitHttpsServer other = GitHttpsServer.start(temp.resolve("source"), temp.resolve("other-keys"))) {
            server.redirect("/group/orders/", other.base("127.0.0.1") + "orders/");
            GitFailure failure = failure(Reason.TRANSPORT_FAILED, () -> open(token(TOKEN)).forDeploy("orders", orders));
            assertEquals("orders at " + orders + ": the transport failed", failure.getMessage());
            assertEquals(List.of(), other.requests(), "the other host saw no request, so no credentials");

            server.redirect("/group/orders/", other.base("localhost") + "orders/");
            failure(Reason.TRANSPORT_FAILED, () -> open(token(TOKEN)).forDeploy("orders", orders));
            assertEquals(List.of(), other.requests(), "another port is another origin");
        }
        assertNoSecretLoggedOrCached();
    }

    @Test
    void followsARedirectOnTheSameHost() throws Exception {
        server.requireCredentials(USER, TOKEN);
        server.redirect("/group/moved/", server.base("localhost") + "orders/");
        assertEquals(orders, open(token(TOKEN)).forDeploy("moved", orders).id());
        assertNoSecretLoggedOrCached();
    }

    @Test
    void answersNotFoundForARepositoryTheServerLacks() {
        failure(Reason.NOT_FOUND, () -> open(Optional.empty()).forDeploy("missing", orders));
    }

    @Test
    void stopsAFetchThatWaitsOnASilentServerWhenTheTimerFires() throws Exception {
        CountDownLatch arrived = server.hold();
        PackageRepositories repositories = open(Optional.empty());
        CompletableFuture<GitFailure> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                repositories.forDeploy("orders", orders);
                result.complete(null);
            } catch (GitFailure failure) {
                result.complete(failure);
            } catch (Throwable unexpected) {
                result.completeExceptionally(unexpected);
            }
        });
        arrived.await();
        clock.advance(GitSettings.TIMEOUT_DEFAULT);
        GitFailure failure = result.get();
        assertEquals(Reason.TIMED_OUT, failure == null ? null : failure.reason());
        assertEquals("orders at " + orders + ": timed out (RAIL8_GIT_TIMEOUT)", failure.getMessage());
    }

    @Test
    void holdsTheCacheLimitWhileTheFetchReceives() throws Exception {
        byte[] big = new byte[3 << 20];
        new Random(11).nextBytes(big);
        String large = source.commit("large", "main", Map.of("package.yaml", PackageRepositoriesContract.file("name: large\n"),
                "data.bin", new PackageRepositoriesContract.File(big)));
        GitFailure failure = failure(Reason.CACHE_FULL, () -> open(server.base("localhost"), Optional.empty(), 1L << 20).forDeploy("large", large));
        assertEquals("large at " + large + ": the git cache is full (RAIL8_GIT_MAX_BYTES)", failure.getMessage());
        long cached;
        try (Stream<Path> files = Files.walk(temp.resolve("cache"))) {
            cached = files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        }
        assertTrue(cached < 1L << 20, "the cache holds " + cached + " bytes");
    }
}
