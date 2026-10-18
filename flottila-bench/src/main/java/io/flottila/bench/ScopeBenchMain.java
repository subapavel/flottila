package io.flottila.bench;

import io.flottila.core.admission.Admission;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Entry point of the spike-2 measurement (P0-11): {@code --scopes 10000 --children 4 [--scope-concurrency 10000] --threads 10000
 * --requests 256 --bytes 67108864 --request-bytes 4096 --hold-ms 1 --out bench/out/scope.json --commit <sha>}.
 * Also runs the real message path (P1-02): {@code Admitted.run} per message with {@code --children} forks. Exit 0,
 * or 3 when any scope or acquire failed or the admitted path exceeded its budget.
 *
 * @since 0.1
 */
public final class ScopeBenchMain {

    private ScopeBenchMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        System.exit(run(args));
    }

    static int run(String[] args) throws IOException, InterruptedException {
        Map<String, String> a = BenchMain.parse(args);
        int scopes = Integer.parseInt(a.getOrDefault("scopes", "10000"));
        int children = Integer.parseInt(a.getOrDefault("children", "4"));
        int scopeConcurrency = Integer.parseInt(a.getOrDefault("scope-concurrency", String.valueOf(scopes)));   // = scopes: all at once
        int threads = Integer.parseInt(a.getOrDefault("threads", "10000"));
        long maxRequests = Long.parseLong(a.getOrDefault("requests", "256"));
        long maxBytes = Long.parseLong(a.getOrDefault("bytes", String.valueOf(64L * 1024 * 1024)));
        long requestBytes = Long.parseLong(a.getOrDefault("request-bytes", "4096"));
        Duration hold = Duration.ofMillis(Long.parseLong(a.getOrDefault("hold-ms", "1")));
        Path out = Path.of(a.getOrDefault("out", "bench/out/scope.json"));

        Vertx vertx = Vertx.vertx();
        try {
            VertxTimer timer = new VertxTimer(vertx);
            Clock clock = Clock.systemUTC();
            ScopeBench.scopes(Math.min(scopes, 1000), children, Math.min(scopeConcurrency, 1000), clock, timer);   // warm-up
            ScopeBench.ScopeReport scope = ScopeBench.scopes(scopes, children, scopeConcurrency, clock, timer);
            ScopeBench.admission(Math.min(threads, 1000), new Admission(maxRequests, maxBytes), requestBytes, Duration.ZERO);   // warm-up
            ScopeBench.AdmissionReport admission = ScopeBench.admission(threads, new Admission(maxRequests, maxBytes), requestBytes, hold);
            ScopeBench.admitted(Math.min(threads, 1000), children, new Admission(maxRequests, maxBytes), requestBytes, clock, timer);   // warm-up
            ScopeBench.AdmittedReport admitted = ScopeBench.admitted(threads, children, new Admission(maxRequests, maxBytes), requestBytes, clock, timer);
            JsonObject json = new JsonObject()
                    .put("name", "spike-2")
                    .put("scope", scope.toJson())
                    .put("admission", admission.toJson())
                    .put("admitted", admitted.toJson())
                    .put("machine", BenchMain.machine())
                    .put("date", Instant.now().toString())
                    .put("commit", a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")));
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, json.encodePrettily() + "\n");
            System.out.println(json.encodePrettily());
            int failed = scope.failed() + admission.failed() + (admitted.maxInFlightRequests() > maxRequests || admitted.maxInFlightBytes() > maxBytes ? 1 : 0)
                    + (threads - admitted.completed() - admitted.refused());   // a message that neither completed nor was refused died in its thread
            System.err.println(failed + " scopes or acquires failed (0 expected)");
            return Integer.signum(failed) * 3;   // 0, or 3 when anything failed
        } finally {
            vertx.close().await();
        }
    }
}
