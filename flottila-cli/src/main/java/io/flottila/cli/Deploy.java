package io.flottila.cli;

import io.flottila.schemas.Documents;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code flottila deploy <dir> [--name N] [--revision R] [--admin URL]} (D-079): the package directory as a deterministic zip
 * ({@link #zip}), PUT to {@code <admin>/admin/packages/<name>[?revision=R]} with {@code Content-Type: application/zip} and,
 * when FLOTTILA_ADMIN_TOKEN is set and not blank, {@code Authorization: Bearer <token>} — the token is taken from the
 * environment only. The name is package.yaml's unless {@code --name}; the admin URL {@code --admin}, else FLOTTILA_ADMIN, else
 * {@code http://localhost:8081}. Prints the node's answer body; exit 0 on a 2xx, 1 on any other status (a redirect is not
 * followed) or a node that cannot be reached, 2 on a usage error (nothing sent).
 */
final class Deploy {

    static final String DEFAULT_ADMIN = "http://localhost:8081";
    /** The one modification time of every entry: a DOS date-time, no extended timestamp, the same in every time zone. */
    static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final Duration CONNECT = Duration.ofSeconds(10);
    /** A deploy answers once its flows started or it was refused; the node bounds that by its stop deadline, this bounds the wait. */
    private static final Duration ANSWER = Duration.ofMinutes(5);

    private Deploy() {}

    static int run(Cli.Options options, Map<String, String> env, PrintStream out, PrintStream err) throws IOException {
        String name = options.value("--name").orElseGet(() -> manifestName(options.dir()));
        String admin = options.value("--admin").orElse(env.getOrDefault("FLOTTILA_ADMIN", DEFAULT_ADMIN));
        HttpRequest.Builder request = request(admin, name, options.value("--revision"));
        request.header("Content-Type", "application/zip").timeout(ANSWER).PUT(HttpRequest.BodyPublishers.ofByteArray(zip(options.dir())));
        Optional.ofNullable(env.get("FLOTTILA_ADMIN_TOKEN")).filter(token -> !token.isBlank()).ifPresent(token -> request.header("Authorization", "Bearer " + token));
        HttpResponse<String> answer;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT).build()) {
            answer = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).join();
        } catch (CompletionException unreachable) {
            err.println("flottila deploy: no answer from " + admin + ": " + Cli.oneLine(String.valueOf(unreachable.getCause())));
            return 1;
        }
        out.println(answer.body());
        return answer.statusCode() / 100 == 2 ? 0 : 1;
    }

    /** package.yaml's {@code name}; a {@link Cli.Usage} when it cannot be read or names none. */
    private static String manifestName(Path dir) {
        Object name;
        try {
            name = Documents.read(dir.resolve("package.yaml")).getValue("name");
        } catch (IllegalArgumentException | UncheckedIOException unreadable) {
            throw new Cli.Usage("deploy: package.yaml cannot be read for the package's name (or give --name): " + Cli.oneLine(unreadable.getMessage()));
        }
        if (name instanceof String s) return s;
        throw new Cli.Usage("deploy: package.yaml names no package (give --name)");
    }

    /** The PUT's builder for {@code <admin>/admin/packages/<name>[?revision=R]}; a {@link Cli.Usage} when {@code admin} is no http(s) URL. */
    private static HttpRequest.Builder request(String admin, String name, Optional<String> revision) {
        String uri = admin.replaceAll("/+$", "") + "/admin/packages/" + encoded(name) + revision.map(r -> "?revision=" + encoded(r)).orElse("");
        try {
            return HttpRequest.newBuilder(URI.create(uri));
        } catch (IllegalArgumentException wrong) {
            throw new Cli.Usage("deploy: the admin URL '" + admin + "' is not an http or https URL: " + wrong.getMessage());
        }
    }

    /** {@code text} percent-encoded for a path segment or a query value (a space as {@code %20}): the node judges what it names. */
    private static String encoded(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * The package directory {@code dir} as a zip, the same bytes for the same files wherever they lie and whenever they were
     * written: one entry per regular file at its {@code /}-path from {@code dir} ({@code package.yaml} at the root), entries in
     * plain string order of that path, no directory entries, every entry's time {@link #ENTRY_TIME}.
     */
    static byte[] zip(Path dir) throws IOException {
        List<String> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace(File.separatorChar, '/')).sorted().toList();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String path : paths) {
                ZipEntry entry = new ZipEntry(path);
                entry.setTimeLocal(ENTRY_TIME);
                zip.putNextEntry(entry);
                Files.copy(dir.resolve(path), zip);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
