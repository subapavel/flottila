package io.flottila.cli;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.StepResources;
import io.flottila.api.Timer;
import io.flottila.components.mock.Behaviour;
import io.flottila.components.mock.MockComponent;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.runtime.Main;
import io.flottila.runtime.deploy.DeployRefusedException;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Secrets;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.schemas.Documents;
import io.flottila.schemas.Package;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@code flottila test <dir> [--deps <dir>]} (D-080). First what {@code flottila validate} checks, in the environment {@code ci}
 * — the package's problems, its dependencies' closure (D-089) and the dry build over what a node ships — and what only a test
 * run asks of its test documents: a {@code recorded}
 * or {@code script} behaviour (not implemented yet), {@code expect.status} on a flow whose from endpoint is not http, an
 * {@code xpath} that does not compile; any problem is printed as validate prints it and no case runs (exit 1).
 *
 * <p>Then every {@code kind: test} document in path order, its cases in order, one at a time, each on a world of its own: a
 * fresh fake runtime (clock, timer, ids, blobs), one {@link MockComponent} per shipped scheme bound to the document's
 * {@code mocks} — a case's entry for an endpoint replaces the document's whole — in-memory stores, and a node
 * ({@link Deployments}) of environment {@code ci} over a temporary data directory, deleted after the case, to which the
 * dependencies' closure validate resolved (D-089) is deployed leaves first, then the package, each as its deterministic zip
 * ({@link Deploy#zip}, built once). The case's {@code send} — the fixture's bytes and
 * its {@code headers} — is {@link MockComponent#ingest ingested} on the flow's from endpoint; a case without one is fed by that
 * endpoint's mock, whose {@code files} or {@code messages} its consumer polls from its start (the package's cross-check refuses
 * a case that neither sends nor is fed, D-080). The case settles on virtual time ({@link VirtualTime}: settled = the answer
 * complete — a fed case has none —, no file of the from endpoint {@linkplain MockComponent#undelivered undelivered} and the
 * work queue empty, on a quiet world) and its {@code expect} is evaluated ({@link Expectations}; {@code steps} and {@code keys}
 * read the first message the from endpoint delivered); then the node is closed, on virtual time too.
 *
 * <p>The report, on stdout: {@code PASS|FAIL <flow>: <case> (flow time <d>)} per case, after a FAIL what failed, each on an
 * indented line — the failed keys ({@link Expectations}), or {@code did not settle: <what>}, {@code send refused: <why>} (the
 * message never reached the flow) or {@code deploy refused: <why>} — and last {@code N cases: P passed, F failed}. Nothing on it
 * depends on wall time: the same package prints the same bytes on every run. Exit 0 when every case passed; 1 on a failed
 * case, a problem, or a package without a test document.
 */
final class FlowTests {

    /** How long one wait on virtual time — a case's, its node's close — may take in wall time. */
    static final Duration WALL_GUARD = Duration.ofSeconds(60);
    /**
     * How long a case's world stays quiet, in wall time, before its clock moves ({@link Quiescence}): a virtual thread blocked on a
     * monitor of the node's own (a worker's, a delivery's) is neither mounted nor queued until the JDK's unblocker thread queues
     * it again, a hand-over of microseconds this outlasts.
     */
    static final Duration STEADY = Duration.ofMillis(1);
    /** The environment a test runs in: a node that loads mocks (not deployed, ARCHITECTURE §11). */
    private static final String ENV = "ci";
    /** The node id of each case's node: a DNS label, named nowhere in the output. */
    private static final String NODE_ID = "flottila-test";
    /** Behaviours the schema takes and this runner does not run yet. */
    private static final List<String> NOT_YET = List.of("recorded", "script");
    /** The answer of a case without a send, fed by its from endpoint's mock: there is none to wait for, and no Ack. */
    private static final Future<Ack> FED = Future.succeededFuture();

    private final Package pkg;
    private final Path root;
    private final List<String> schemes;
    private final Main.Shipped shipped;
    private final Map<String, Function<Envelope, Optional<String>>> xpaths = new HashMap<>();
    private byte[] zip;
    private List<Archive> dependencies;

    private FlowTests(Package pkg, Path root, List<String> schemes, Main.Shipped shipped) {
        this.pkg = pkg;
        this.root = root;
        this.schemes = schemes;
        this.shipped = shipped;
    }

    /** One test document: its path from the package root, its flow, its {@code mocks} and its cases. */
    private record Doc(String path, String flow, JsonObject mocks, List<JsonObject> cases) {}

    /** A dependency's deterministic zip ({@link Deploy#zip}) and the name it is deployed as. */
    private record Archive(String name, byte[] zip) {}

    /** How a case ended: passed or not, at what flow time, and the indented lines that say what failed. */
    private record Verdict(boolean passed, Duration flowTime, List<String> why) {
        static Verdict failed(Duration flowTime, String why) {
            return new Verdict(false, flowTime, List.of(why));
        }
    }

    static int run(Cli.Options options, PrintStream out) throws IOException {
        Path dir = options.dir();
        Validate.Checked checked = Validate.check(dir, ENV, Dependencies.of(options));
        if (!checked.problems().isEmpty()) return problems(checked.problems(), out);
        Package pkg = checked.pkg().orElseThrow();
        FlowTests run = new FlowTests(pkg, dir.toAbsolutePath().normalize(), checked.schemes().stream().sorted().toList(), Main.shipped());
        List<Doc> docs = run.docs(dir);
        List<String> problems = run.problems(docs);
        if (!problems.isEmpty()) return problems(problems, out);
        if (docs.isEmpty()) {
            out.println("no test document in package " + pkg.name() + ": nothing to run");
            return 1;
        }
        run.zip = Deploy.zip(dir);
        List<Archive> dependencies = new ArrayList<>();
        for (Dependencies.Found dep : checked.dependencies()) dependencies.add(new Archive(dep.pin().name(), Deploy.zip(dep.dir())));
        run.dependencies = List.copyOf(dependencies);
        int passed = 0;
        int cases = 0;
        for (Doc doc : docs) {
            for (JsonObject c : doc.cases()) {
                Verdict verdict = run.play(doc, c);
                out.println((verdict.passed() ? "PASS " : "FAIL ") + doc.flow() + ": " + c.getString("name") + " (flow time " + duration(verdict.flowTime()) + ")");
                verdict.why().forEach(line -> out.println("  " + line));
                cases++;
                if (verdict.passed()) passed++;
            }
        }
        out.println(cases + " cases: " + passed + " passed, " + (cases - passed) + " failed");
        return passed == cases ? 0 : 1;
    }

    private static int problems(List<String> problems, PrintStream out) {
        problems.forEach(p -> out.println("problem: " + p));
        return 1;
    }

    /** Every test document of the package, in path order. */
    private List<Doc> docs(Path dir) {
        List<Doc> docs = new ArrayList<>();
        for (Package.Artifact a : pkg.artifacts()) {
            if (!a.kind().equals("test")) continue;
            JsonObject d = Documents.read(dir.resolve(a.path()));
            JsonArray listed = d.getJsonArray("cases");
            List<JsonObject> cases = new ArrayList<>();
            for (int i = 0; i < listed.size(); i++) cases.add(listed.getJsonObject(i));
            docs.add(new Doc(a.path(), d.getString("flow"), d.getJsonObject("mocks", new JsonObject()), List.copyOf(cases)));
        }
        return docs;
    }

    /**
     * What only a test run asks of the (schema-valid) test documents, in path and case order; each {@code xpath} of an
     * {@code expect.sent} is compiled on the way, once, for the cases.
     */
    private List<String> problems(List<Doc> docs) {
        List<String> problems = new ArrayList<>();
        Expressions xpath = shipped.languages().stream().filter(l -> l.language().equals("xpath")).findFirst().orElseThrow();
        FakeRuntime rt = FakeRuntime.create();
        StepResources resources = new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), rt.blobs().treeLimit(), root);
        for (Doc doc : docs) {
            notYet(problems, doc.path() + ": ", doc.mocks());
            String from = from(doc.flow());
            String scheme = scheme(from);
            for (JsonObject c : doc.cases()) {
                String where = doc.path() + ": case '" + c.getString("name") + "' ";
                notYet(problems, where, c.getJsonObject("mocks", new JsonObject()));
                JsonObject expect = c.getJsonObject("expect");
                if (expect.containsKey("status") && !scheme.equals("http")) {
                    problems.add(where + "expects status, the http door's answer, but flow '" + doc.flow() + "' comes from endpoint '" + from + "' of scheme " + scheme);
                }
                JsonObject sent = expect.getJsonObject("sent", new JsonObject());
                for (String endpoint : sent.fieldNames()) {
                    String expression = sent.getJsonObject(endpoint).getString("xpath");
                    if (expression == null || xpaths.containsKey(expression)) continue;
                    try {
                        xpaths.put(expression, xpath.value(expression, resources));
                    } catch (IllegalArgumentException wrong) {
                        problems.add(where + "sent." + endpoint + ".xpath does not compile: " + Cli.oneLine(wrong.getMessage()));
                    }
                }
            }
        }
        return problems;
    }

    private static void notYet(List<String> problems, String where, JsonObject mocks) {
        for (String endpoint : mocks.fieldNames()) {
            JsonObject behaviour = mocks.getJsonObject(endpoint);
            NOT_YET.stream().filter(behaviour::containsKey).forEach(b -> problems.add(where + "mock '" + endpoint + "': " + b + " is not implemented yet"));
        }
    }

    /** The from endpoint of the flow {@code flow}. */
    private String from(String flow) {
        return pkg.flows().get(flow).getJsonObject("from").getString("endpoint");
    }

    private String scheme(String endpoint) {
        return pkg.endpoints().get(endpoint).getString("scheme");
    }

    /**
     * Runs the case {@code c} of {@code doc} on a world of its own (see the class comment). The node is closed and the data
     * directory deleted whatever happens after they were made.
     *
     * @throws IOException the data directory cannot be made, written or deleted, or the send's fixture read: the tool's failure
     */
    private Verdict play(Doc doc, JsonObject c) throws IOException {
        FakeRuntime rt = FakeRuntime.create();
        Watched watched = new Watched();
        Timer timer = watched.timer(rt.timer());
        Map<String, MockComponent> mocks = new TreeMap<>();
        for (String s : schemes) mocks.put(s, new MockComponent(s, rt.blobs(), timer, rt.ids()));
        JsonObject behaviours = doc.mocks().copy();
        c.getJsonObject("mocks", new JsonObject()).forEach(entry -> behaviours.put(entry.getKey(), entry.getValue()));   // whole entries
        for (String endpoint : behaviours.fieldNames()) {
            mocks.get(scheme(endpoint)).bind(endpoint, Behaviour.from(behaviours.getJsonObject(endpoint).getMap(), root));
        }
        String from = from(doc.flow());
        MockComponent door = mocks.get(scheme(from));
        String sends = c.getString("send");
        byte[] send = sends == null ? null : Files.readAllBytes(root.resolve(sends));
        Map<String, String> headers = new LinkedHashMap<>();
        JsonObject given = c.getJsonObject("headers", new JsonObject());
        given.fieldNames().forEach(name -> headers.put(name, given.getString(name)));

        InMemoryMessageStore messages = new InMemoryMessageStore();
        InMemoryDlq dlq = new InMemoryDlq();
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        Map<String, Component> components = new LinkedHashMap<>(mocks);
        Path data = Files.createTempDirectory("flottila-test-");
        try {
            NodeConfig config = NodeConfig.from(Map.of("FLOTTILA_ENV", ENV, "FLOTTILA_NODE_ID", NODE_ID, "FLOTTILA_DATA_DIR", data.toString()))
                    .withSecrets(Secrets.placeholder());   // no secret off the node: every reference resolves to the placeholder (D-081)
            Deployments node = new Deployments(config, new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), components), components,
                    rt.clock(), timer, shipped.steps(), shipped.languages(), messages, dlq, queue, Meter.system());
            try {
                for (Archive dep : dependencies) node.deploy(dep.name(), Optional.empty(), Files.write(data.resolve("dependency-" + dep.name() + ".zip"), dep.zip()));   // leaves first
                node.deploy(pkg.name(), Optional.empty(), Files.write(data.resolve("package.zip"), zip));
                Future<Ack> answer = send == null ? FED : door.ingest(from, new Headers(headers), Buffer.buffer(send));
                BooleanSupplier answered = done(answer);   // the answer's monitor stays out of the sampling: read through this only
                VirtualTime.Outcome outcome = new VirtualTime(rt.clock(), rt.timer(),
                        Quiescence.ofJvm(watched::inside, STEADY, world(watched, rt.clock(), door, from, mocks.values(), answered)), WALL_GUARD)
                        .settle(() -> answered.getAsBoolean() && door.undelivered(from).isEmpty() && queue.size() == 0);
                if (!outcome.settled()) {
                    return Verdict.failed(outcome.flowTime(), "did not settle: " + outcome.reason() + " (answer complete: " + answered.getAsBoolean()
                            + ", undelivered files: " + door.undelivered(from).size() + ", work queue items: " + queue.size() + ")");
                }
                List<Envelope> taken = door.delivered(from);
                if (answer.failed() && taken.isEmpty()) return Verdict.failed(outcome.flowTime(), "send refused: " + Cli.oneLine(answer.cause().getMessage()));
                Optional<String> first = taken.stream().findFirst().map(Envelope::id);   // none: a feed whose glob matched no file
                Expectations.Observed observed = new Expectations.Observed(first, answer.result(), answer.cause(), messages, dlq.size(),
                        mocks.values().stream().flatMap(m -> m.sent().stream()).toList(), endpoint -> mocks.get(scheme(endpoint)).sentTo(endpoint), xpaths);
                List<String> failed = Expectations.failed(c.getJsonObject("expect"), observed);
                return new Verdict(failed.isEmpty(), outcome.flowTime(), failed);
            } catch (DeployRefusedException refused) {
                return Verdict.failed(Duration.ZERO, "deploy refused: " + Cli.oneLine(refused.getMessage()));
            } finally {
                close(node, rt, watched, door, from, mocks.values());
            }
        } finally {
            deleteTree(data);
        }
    }

    /**
     * The fingerprint of a case's world that {@link Quiescence} compares: the calls into {@code watched}'s timer, the moves of
     * {@code clock} from now on — every advance, a zero one too, starts the steady wait afresh — its instant, {@code door}'s
     * deliveries on {@code from} and the files remaining there, each mock's sends and {@code done} (the case's answer, the node's
     * close). It takes no monitor a virtual thread takes, as a store's size or a Vert.x future's {@code isComplete()} would: a
     * thread blocked on one would leave its carrier unseen. The remaining files are read without one: the inbox's current
     * immutable list through a volatile field, found by a {@code ConcurrentHashMap} get ({@link MockComponent#remaining}).
     */
    static Supplier<List<Object>> world(Watched watched, FakeClock clock, MockComponent door, String from, Collection<MockComponent> mocks,
                                        BooleanSupplier done) {
        AtomicLong moves = new AtomicLong();
        clock.onChange(moves::incrementAndGet);
        return () -> {
            List<Object> seen = new ArrayList<>(List.of(watched.calls(), moves.get(), clock.instant(), door.delivered(from).size(),
                    door.remaining(from).size()));
            mocks.forEach(m -> seen.add(m.sent().size()));
            seen.add(done.getAsBoolean());
            return seen;
        };
    }

    /**
     * Whether {@code future} has completed, succeeded or failed, read without its monitor (Vert.x 5.1.10 {@code isComplete()} is
     * {@code synchronized}): a flag the listener registered here sets.
     */
    static BooleanSupplier done(Future<?> future) {
        AtomicBoolean done = new AtomicBoolean();
        future.onComplete(completed -> done.set(true));
        return done::get;
    }

    /**
     * Closes {@code node} on a virtual thread of its own while {@code flottila-clock} drives the clock: its stops are bounded by
     * the node's stop deadline on the case's timer. The case is over and reported; how the close ends — a delivery or a consumer
     * that did not stop within the deadline when the case did not settle, a close still waiting at the wall guard — changes
     * nothing of it.
     */
    private static void close(Deployments node, FakeRuntime rt, Watched watched, MockComponent door, String from, Collection<MockComponent> mocks) {
        CompletableFuture<Void> closed = CompletableFuture.runAsync(node::close, task -> Thread.ofVirtual().name("flottila-test-close").start(task));
        new VirtualTime(rt.clock(), rt.timer(), Quiescence.ofJvm(watched::inside, STEADY, world(watched, rt.clock(), door, from, mocks, closed::isDone)),
                WALL_GUARD).settle(closed::isDone);
    }

    /**
     * The calls into a case's timer: each schedule and each cancel counted as it returns (a fingerprint of the case's world), and
     * the threads inside one. The timer's monitor is the clock thread's while it fires due tasks, so a virtual thread a task woke
     * may block on it and leave its carrier: inside a call, it is busy all the same.
     */
    static final class Watched {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicInteger inside = new AtomicInteger();

        Timer timer(Timer timer) {
            return (delay, task) -> {
                Timer.Cancellable scheduled = call(() -> timer.schedule(delay, task));
                return () -> call(scheduled::cancel);
            };
        }

        long calls() { return calls.get(); }

        int inside() { return inside.get(); }

        private <T> T call(Supplier<T> call) {
            inside.incrementAndGet();
            try {
                return call.get();
            } finally {
                calls.incrementAndGet();   // before the thread leaves: a sample that sees it out sees its call
                inside.decrementAndGet();
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : paths) Files.delete(p);
    }

    /**
     * {@code d} in the largest of {@code m}, {@code s}, {@code ms} that divides it exactly, {@code 0ms} for none; a flow time
     * never reaches an hour ({@link VirtualTime#HORIZON}).
     */
    static String duration(Duration d) {
        long ms = d.toMillis();
        if (ms != 0 && ms % 60_000 == 0) return ms / 60_000 + "m";
        if (ms != 0 && ms % 1_000 == 0) return ms / 1_000 + "s";
        return ms + "ms";
    }
}
