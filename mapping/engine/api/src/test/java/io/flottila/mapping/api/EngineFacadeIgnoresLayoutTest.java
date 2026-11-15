package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The engine ignores a mapping's description and layout (ADR-0029, #153): the corpus mapping {@code
 * gate-po-invoice} with both added gives the same diagnostics, test output, trace, deployed output
 * and portable export as the file without them. One layout holds the position of a box the mapping
 * does not have, which stages 2 and 3 do not read either.
 */
class EngineFacadeIgnoresLayoutTest {

  /** The trace limit of ADR-0015 (#53). */
  private static final int TRACE_LIMIT = 10_000;

  /** {@code corpus/mappings}, which the gate mapping's bundle references are relative to. */
  private static Path mappings() {
    return CorpusStructures.directory("gate").getParent().getParent().resolve("mappings");
  }

  private static ReferenceResolver resolver() {
    return DirectoryResolver.over(mappings());
  }

  /** The input of the corpus case {@code gate/xsdToXsd/001}, whose mapping this is. */
  private static byte[] input() throws IOException {
    return Files.readAllBytes(mappings().resolveSibling("cases/gate/xsdToXsd/001/input.xml"));
  }

  private static byte[] plain() throws IOException {
    return Files.readAllBytes(mappings().resolve("gate-po-invoice.mapping.json"));
  }

  /**
   * {@link #plain} with a description and the layouts of two target field mappings: T1's with its
   * target, its box and the stale box {@code Z9}, T6's with its box alone.
   */
  private static byte[] laidOut() throws IOException {
    String plain = new String(plain(), UTF_8);
    String laidOut =
        plain
            .replaceFirst("\\{", "{\"description\": \"Purchase orders to invoices (gate)\", ")
            .replace(
                "\"id\": \"T1\",",
                "\"id\": \"T1\", \"layout\": {\"target\": {\"x\": 600, \"y\": 40},"
                    + " \"boxes\": {\"S1\": {\"x\": 0, \"y\": 40}, \"Z9\": {\"x\": -50, \"y\": -50}}},")
            .replace(
                "\"id\": \"T6\",",
                "\"id\": \"T6\", \"layout\": {\"boxes\": {\"S6\": {\"x\": 10, \"y\": -200}}},");
    assertThat(laidOut)
        .as("the description and both layouts are in the file")
        .startsWith("{\"description\": ")
        .contains("\"id\": \"T1\", \"layout\"", "\"id\": \"T6\", \"layout\"");
    return laidOut.getBytes(UTF_8);
  }

  /** Severity, code and location; never the message (ADR-0015). */
  private static List<String> described(List<Diagnostic> diagnostics) {
    return diagnostics.stream()
        .map(d -> d.severity() + " " + d.code() + " " + d.location())
        .toList();
  }

  private static CompiledMapping compiled(byte[] model, CompileOptions options) throws Exception {
    CompileResult result = new EngineFacade().compile(model, resolver(), options);
    if (result instanceof CompileResult.Compiled(CompiledMapping mapping)) {
      return mapping;
    }
    throw new AssertionError("expected Compiled, got " + result);
  }

  private static TestRunResult.Output testRun(byte[] model) throws Exception {
    TestRunResult result =
        new EngineFacade()
            .testRun(
                compiled(model, CompileOptions.TEST_RUN),
                input(),
                Duration.ofSeconds(30),
                TRACE_LIMIT);
    if (result instanceof TestRunResult.Output output) {
      return output;
    }
    throw new AssertionError("expected Output, got " + result);
  }

  @Test
  void theDiagnosticsAreTheSameWithTheDescriptionAndLayout() throws Exception {
    EngineFacade facade = new EngineFacade();

    assertThat(described(facade.diagnostics(laidOut(), resolver())))
        .isEqualTo(described(facade.diagnostics(plain(), resolver())));
  }

  @Test
  void aTestRunGivesTheSameOutputAndTraceWithTheDescriptionAndLayout() throws Exception {
    TestRunResult.Output plain = testRun(plain());

    TestRunResult.Output laidOut = testRun(laidOut());

    // witness: the plain file runs the corpus case, whose expected.xml holds these values
    assertThat(new String(plain.output(), UTF_8))
        .contains(">PO-100<", ">Ada Lovelace<", ">London<");
    assertThat(plain.trace().entries()).isNotEmpty();
    assertThat(new String(laidOut.output(), UTF_8)).isEqualTo(new String(plain.output(), UTF_8));
    assertThat(laidOut.output()).isEqualTo(plain.output());
    assertThat(List.copyOf(laidOut.trace().entries().entrySet()))
        .isEqualTo(List.copyOf(plain.trace().entries().entrySet()));
  }

  @Test
  void aDeployedRunGivesTheSameOutputWithTheDescriptionAndLayout() throws Exception {
    EngineFacade facade = new EngineFacade();
    byte[] plain = facade.run(compiled(plain(), CompileOptions.DEPLOY), input());

    byte[] laidOut = facade.run(compiled(laidOut(), CompileOptions.DEPLOY), input());

    assertThat(new String(plain, UTF_8)).contains(">PO-100<");
    assertThat(laidOut).isEqualTo(plain);
  }

  @Test
  void thePortableExportIsTheSameWithTheDescriptionAndLayout() throws Exception {
    EngineFacade facade = new EngineFacade();
    ExportResult plain = facade.export(plain(), resolver());

    ExportResult laidOut = facade.export(laidOut(), resolver());

    assertThat(plain).isInstanceOf(ExportResult.Exported.class);
    assertThat(laidOut).isEqualTo(plain);
  }
}
