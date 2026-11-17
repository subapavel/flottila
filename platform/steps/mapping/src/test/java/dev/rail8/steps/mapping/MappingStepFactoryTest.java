package dev.rail8.steps.mapping;

import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.Headers;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepResources;
import dev.rail8.mapping.embed.MappingRefusedException;
import dev.rail8.mapping.embed.RunFailure;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mapping step: a mapping file of the package compiled per step, its output the next body, its faults by whose they are. */
class MappingStepFactoryTest {

    /** A package with two mappings in {@code mappings/} and their schemas in {@code structures/}. */
    static final Path PACKAGE = Path.of("src/test/resources/package").toAbsolutePath();

    /** {@code formatNum} with pattern {@code 0} over every {@code Qty} into {@code /Out/All}. */
    static final String FORMAT_NUM = "mappings/format-num-zero.mapping.json";

    /** {@code currentDate} with the default pattern {@code yyyy/MM/dd} into {@code /Out/All}. */
    static final String CURRENT_DATE = "mappings/current-date.mapping.json";

    static final String ORDERS = "<Orders><Order><Line><Qty>7</Qty></Line></Order></Orders>";

    static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    final FakeRuntime rt = FakeRuntime.create();
    final MappingStepFactory factory = new MappingStepFactory();
    final List<Path> reads = new ArrayList<>();

    @TempDir Path tmp;

    StepResources resources(Path dir, long maxBodyBytes, Map<String, Path> dependencies) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), maxBodyBytes, dir, reads::add, dependencies);
    }

    StepResources resources(long maxBodyBytes) { return resources(PACKAGE, maxBodyBytes, Map.of()); }

    static StepConfig step(String mapping) {
        return new StepConfig(2, "mapping", "toInvoice", false, Map.of("mapping", mapping));
    }

    Envelope message(String body) {
        return rt.ingress("orders", Headers.empty().with("x-request-id", "r1"), Buffer.buffer(body)).advance();
    }

    Envelope apply(String mapping, long maxBodyBytes, String body) {
        Stage stage = factory.create(step(mapping), resources(maxBodyBytes));
        return stage.apply(message(body), e -> e);
    }

    @Test
    void mapsTheBodyIntoTheNextBodyWithTheEnginesMediaType() {
        assertEquals("mapping", factory.type());
        Envelope in = message(ORDERS);
        Envelope out = factory.create(step(FORMAT_NUM), resources(65536)).apply(in, e -> e);
        assertEquals(DECLARATION + "<Out><All>7</All></Out>", out.body().whole().toString());
        assertEquals("application/xml", out.header(Envelope.H_CONTENT_TYPE).orElseThrow());
        assertEquals("r1", out.header("x-request-id").orElseThrow(), "headers carried over");
        assertEquals(in.id(), out.id());
        assertEquals(2, out.body().key().step(), "written under the envelope's next key");
    }

    @Test
    void oneStageRunsManyMessages() {
        Stage stage = factory.create(step(FORMAT_NUM), resources(65536));
        for (int n = 1; n <= 3; n++) {
            String body = "<Orders><Order><Line><Qty>" + n + "</Qty></Line></Order></Orders>";
            assertEquals(DECLARATION + "<Out><All>" + n + "</All></Out>", stage.apply(message(body), e -> e).body().whole().toString());
        }
    }

    @Test
    void theRunSeesTheInjectedClocksTime() {
        rt.withClockAt(Instant.parse("2026-03-04T10:00:00Z"));
        assertEquals(DECLARATION + "<Out><All>2026/03/04</All></Out>", apply(CURRENT_DATE, 65536, ORDERS).body().whole().toString());
    }

    @Test
    void theMappingFileAndEverySchemaFileItReadAreReported() {
        factory.create(step(FORMAT_NUM), resources(65536));
        assertEquals(List.of(PACKAGE.resolve(FORMAT_NUM), PACKAGE.resolve("structures/src-orders.xsd"),
                PACKAGE.resolve("structures/tgt-list.xsd")), reads);
    }

    @Test
    void aMappingInADependencyReadsItsSchemasFromThatDependency() throws IOException {
        Path dependency = copy(PACKAGE, tmp.resolve("common"));
        Path pkg = Files.createDirectories(tmp.resolve("pkg"));
        Stage stage = factory.create(step("common:" + FORMAT_NUM), resources(pkg, 65536, Map.of("common", dependency)));
        assertEquals(DECLARATION + "<Out><All>7</All></Out>", stage.apply(message(ORDERS), e -> e).body().whole().toString());
        assertEquals(List.of(dependency.resolve(FORMAT_NUM), dependency.resolve("structures/src-orders.xsd"),
                dependency.resolve("structures/tgt-list.xsd")), reads);
    }

    @Test
    void aRefusedMappingIsABuildErrorNamingTheStepAndTheProblem() throws IOException {
        Path pkg = copy(PACKAGE, tmp.resolve("pkg"));
        Path model = pkg.resolve(FORMAT_NUM);
        Files.writeString(model, Files.readString(model).replace("sha256:5e9b", "sha256:0e9b"));
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                () -> factory.create(step(FORMAT_NUM), resources(pkg, 65536, Map.of())));
        assertTrue(x.getMessage().startsWith("step 'toInvoice'"), x.getMessage());
        assertTrue(x.getMessage().contains("fingerprint-mismatch at '/source/fingerprint'"), x.getMessage());
        assertInstanceOf(MappingRefusedException.class, x.getCause());
    }

    @Test
    void aMissingSchemaFileIsARefusal() throws IOException {
        Path pkg = copy(PACKAGE, tmp.resolve("pkg"));
        Files.delete(pkg.resolve("structures/tgt-list.xsd"));
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                () -> factory.create(step(FORMAT_NUM), resources(pkg, 65536, Map.of())));
        assertTrue(x.getMessage().contains("schema-unreadable at '../structures/tgt-list.xsd:1:1'"), x.getMessage());
    }

    @Test
    void aSchemaFileOutsideThePackageIsABuildErrorNamingTheStep() throws IOException {
        Path pkg = copy(PACKAGE, tmp.resolve("pkg"));
        Files.copy(PACKAGE.resolve("structures/tgt-list.xsd"), tmp.resolve("tgt-list.xsd"));
        rewrite(pkg.resolve(FORMAT_NUM), "../structures/tgt-list.xsd", "../../tgt-list.xsd");
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                () -> factory.create(step(FORMAT_NUM), resources(pkg, 65536, Map.of())));
        assertTrue(x.getMessage().startsWith("step 'toInvoice'") && x.getMessage().contains("outside its package")
                && x.getMessage().contains("../../tgt-list.xsd"), x.getMessage());
        assertEquals(List.of(pkg.resolve(FORMAT_NUM), pkg.resolve("structures/src-orders.xsd")), reads, "nothing outside is read");
    }

    @Test
    void aPackageReferenceIsABuildErrorNamingTheStepAndTheReference() throws IOException {
        Path pkg = copy(PACKAGE, tmp.resolve("pkg"));
        rewrite(pkg.resolve(FORMAT_NUM), "../structures/src-orders.xsd", "common@1.4.0:/structures/src-orders.xsd");
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                () -> factory.create(step(FORMAT_NUM), resources(pkg, 65536, Map.of())));
        assertTrue(x.getMessage().startsWith("step 'toInvoice'") && x.getMessage().contains("common@1.4.0:/structures/src-orders.xsd")
                && x.getMessage().contains("does not read yet"), x.getMessage());
    }

    @Test
    void aMappingFileOutsideThePackageOrMissingIsABuildErrorNamingTheStep() {
        IllegalArgumentException outside = assertThrows(IllegalArgumentException.class,
                () -> factory.create(step("../" + FORMAT_NUM), resources(65536)));
        assertTrue(outside.getMessage().startsWith("step 'toInvoice'") && outside.getMessage().contains("'mapping'"), outside.getMessage());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> factory.create(step("mappings/none.mapping.json"), resources(65536)));
        assertTrue(missing.getMessage().startsWith("step 'toInvoice'") && missing.getMessage().contains("mappings/none.mapping.json"),
                missing.getMessage());
    }

    @Test
    void aBodyThatIsNotWellFormedIsTheSendersFault() {
        MappingStepException x = assertThrows(MappingStepException.class, () -> apply(FORMAT_NUM, 65536, "<Orders>"));
        assertEquals(Fault.Kind.MALFORMED, x.kind());
        assertEquals("m-1", x.messageId());
        assertTrue(x.getMessage().startsWith("step 'toInvoice'"), x.getMessage());
        assertInstanceOf(RunFailure.class, x.getCause());
    }

    @Test
    void aDataErrorIsTheFlowsFault() {
        MappingStepException x = assertThrows(MappingStepException.class,
                () -> apply(FORMAT_NUM, 65536, "<Orders><Order><Line><Qty>1e3</Qty></Line></Order></Orders>"));
        assertEquals(Fault.Kind.INTERNAL, x.kind());
        assertTrue(x.getMessage().contains("N1"), x.getMessage());
    }

    @Test
    void anOutputAboveTheBodyLimitIsInvalidAndOneAtTheLimitPasses() {
        int size = (DECLARATION + "<Out><All>7</All></Out>").getBytes(StandardCharsets.UTF_8).length;
        assertEquals(size, apply(FORMAT_NUM, size, ORDERS).body().size());
        MappingStepException x = assertThrows(MappingStepException.class, () -> apply(FORMAT_NUM, size - 1, ORDERS));
        assertEquals(Fault.Kind.INVALID, x.kind());
    }

    @Test
    void anInterruptIsRethrownWithTheInterruptStatusSet() {
        Stage stage = factory.create(step(FORMAT_NUM), resources(65536));
        Envelope in = message(ORDERS);
        Thread.currentThread().interrupt();
        try {
            Throwable x = assertThrows(Throwable.class, () -> stage.apply(in, e -> e));
            assertInstanceOf(InterruptedException.class, x);
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status is set again");
        } finally {
            Thread.interrupted();
        }
    }

    /** {@code from} copied to {@code to}, file by file. */
    static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(target);
                else Files.copy(f, target);
            }
        }
        return to;
    }

    static void rewrite(Path file, String text, String replacement) throws IOException {
        String before = Files.readString(file);
        assertTrue(before.contains(text), file + " holds " + text);
        Files.writeString(file, before.replace(text, replacement));
    }
}
