package dev.rail8.cli;

import io.vertx.core.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code rail8 deploy <repository> <commit|ref> [--check] [--admin URL]}: {@code POST <admin>/admin/deploy}, with
 * {@code ?check=true} for {@code --check}, the body {@code {"repository": "<repository>", "commit": "<commit|ref>"}},
 * {@code Content-Type: application/json} and, when RAIL8_ADMIN_TOKEN is set and not blank, {@code Authorization: Bearer <token>} —
 * the token is taken from the environment only. The node judges the repository and the commit: a ref ({@code HEAD},
 * {@code main}) only in dev and ci. The admin URL is {@code --admin}, else RAIL8_ADMIN, else {@code http://localhost:8081}.
 * Prints the node's answer body; exit 0 on a 2xx, 1 on any other status (a redirect is not followed) or a node that cannot be
 * reached, 2 on a usage error (nothing sent).
 */
final class Deploy {

    static final String DEFAULT_ADMIN = "http://localhost:8081";
    /** The one modification time of every entry of {@link #zip}: a DOS date-time, no extended timestamp, the same in every time zone. */
    static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final Duration CONNECT = Duration.ofSeconds(10);
    /** A deploy answers once its flows started or it was refused; the node bounds that by its stop deadline, this bounds the wait. */
    private static final Duration ANSWER = Duration.ofMinutes(5);

    private Deploy() {}

    /**
     * The arguments of {@code rail8 deploy} after its name ({@code args[0]}).
     *
     * @param repository the package repository's name, as the node reads it under its base
     * @param commit a commit id, or a ref the node resolves in dev and ci
     * @param check {@code --check}: the node answers what the deploy would do and changes nothing
     * @param admin {@code --admin}, when given
     */
    record Arguments(String repository, String commit, boolean check, Optional<String> admin) {}

    /** The arguments {@code args} give; a {@link Cli.Usage} unless they are a repository, a commit, and the options at most once each. */
    static Arguments arguments(String[] args) {
        List<String> positional = new ArrayList<>();
        boolean check = false;
        String admin = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--check")) {
                if (check) throw new Cli.Usage("deploy: --check is given twice");
                check = true;
            } else if (arg.equals("--admin")) {
                if (i + 1 == args.length || args[i + 1].isBlank()) throw new Cli.Usage("deploy: --admin needs a value");
                if (admin != null) throw new Cli.Usage("deploy: --admin is given twice");
                admin = args[++i];
            } else if (arg.startsWith("--")) {
                throw new Cli.Usage("deploy: unknown option " + arg);
            } else if (arg.isBlank()) {
                throw new Cli.Usage("deploy: a blank argument");
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() < 2) throw new Cli.Usage("deploy: the repository and the commit are both needed");
        if (positional.size() > 2) throw new Cli.Usage("deploy: a repository and a commit, not also '" + positional.get(2) + "'");
        return new Arguments(positional.get(0), positional.get(1), check, Optional.ofNullable(admin));
    }

    static int run(Arguments arguments, Map<String, String> env, PrintStream out, PrintStream err) {
        String admin = arguments.admin().orElse(env.getOrDefault("RAIL8_ADMIN", DEFAULT_ADMIN));
        byte[] body = new JsonObject().put("repository", arguments.repository()).put("commit", arguments.commit()).encode().getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder request = request(admin, arguments.check());
        request.header("Content-Type", "application/json").timeout(ANSWER).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        Optional.ofNullable(env.get("RAIL8_ADMIN_TOKEN")).filter(token -> !token.isBlank()).ifPresent(token -> request.header("Authorization", "Bearer " + token));
        HttpResponse<String> answer;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT).build()) {
            answer = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).join();
        } catch (CompletionException unreachable) {
            err.println("rail8 deploy: no answer from " + admin + ": " + Cli.oneLine(String.valueOf(unreachable.getCause())));
            return 1;
        }
        out.println(answer.body());
        return answer.statusCode() / 100 == 2 ? 0 : 1;
    }

    /** The POST's builder for {@code <admin>/admin/deploy[?check=true]}; a {@link Cli.Usage} when {@code admin} is no http(s) URL. */
    private static HttpRequest.Builder request(String admin, boolean check) {
        String uri = admin.replaceAll("/+$", "") + "/admin/deploy" + (check ? "?check=true" : "");
        try {
            return HttpRequest.newBuilder(URI.create(uri));
        } catch (IllegalArgumentException wrong) {
            throw new Cli.Usage("deploy: the admin URL '" + admin + "' is not an http or https URL: " + wrong.getMessage());
        }
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
