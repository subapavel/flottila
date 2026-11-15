package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.AtAuthoringId;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.runtime.MappingRunException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code fixValues} at the engine facade (#83; ADR-0026 "Table params"): a single-value function of
 * family {@code constant} that maps its input {@code key} through the table param {@code table}
 * (default {@code {}}). A key the table lacks gives, by the param {@code missing}, the key itself
 * ({@code key}, the default), the param {@code defaultValue} ({@code default}, itself defaulting to
 * {@code ""}) or the runtime error {@code po:FIXVALUES} at the box ({@code error}). The key is
 * matched exactly, and SUPPRESS passes through as in every Lift.
 *
 * <p>That FixValues maps a known key through its table is documented ("Executes a value mapping
 * using a fixed value table that you complete using the function parameters", SAP PI 7.11 Standard
 * Functions). The missing-key options, their names and default are assumed (obs-constant); the
 * error code and message are decided. Every model maps {@code Orders/Order -> Out/Item} and {@code
 * F1 -> Out/Item/V} over the observation kit, so Item i holds the values of order i.
 */
class EngineFacadeFixValuesTest {

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final String FINGERPRINT = "sha256:" + "0".repeat(64);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int LIMIT = 10_000;

  private final EngineFacade facade = new EngineFacade();

  private static Path observations() {
    return CorpusStructures.directory("observations");
  }

  /** Item per Order, and V from the box F1 with {@code params}, its key from S1. */
  private static byte[] fixValues(String params, String key) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%1$s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["tgt-list.xsd"], "fingerprint": "%1$s", "root": {"element": "Out"}},
         "targetFieldMappings": {
           "/Out/Item": {"id": "T0", "result": "S0", "boxes": {
             "S0": {"kind": "source", "field": "/Orders/Order", "context": "/Orders"}}},
           "/Out/Item/V": {"id": "T1", "result": "F1", "boxes": {
             "F1": {"kind": "function", "fn": "fixValues", "inputs": ["K1"], "params": {%2$s}},
             %3$s}}}}
        """
        .formatted(FINGERPRINT, params, key)
        .getBytes(UTF_8);
  }

  /** The key box K1: Sku in context Order. */
  private static final String SKU =
      """
      "K1": {"kind": "source", "field": "/Orders/Order/Line/Sku", "context": "/Orders/Order"}""";

  private static String orders(String... orders) {
    return "<Orders>" + String.join("", orders) + "</Orders>";
  }

  /** An Order with a Line per Sku. */
  private static String skus(String... skus) {
    StringBuilder order = new StringBuilder("<Order>");
    for (String sku : skus) {
      order.append("<Line><Sku>").append(sku).append("</Sku></Line>");
    }
    return order.append("</Order>").toString();
  }

  private CompiledMapping compiled(byte[] model, CompileOptions options) throws Exception {
    CompileResult result =
        facade.compile(
            Fingerprints.refreshed(model, observations()),
            DirectoryResolver.over(observations()),
            options);
    if (result instanceof CompileResult.Compiled(CompiledMapping mapping)) {
      return mapping;
    }
    throw new AssertionError("expected Compiled, got " + result);
  }

  /** The output of {@code input}, the same as deployed and as test-run compiled. */
  private String run(byte[] model, String input) throws Exception {
    String deployed =
        new String(
            facade.run(compiled(model, CompileOptions.DEPLOY), input.getBytes(UTF_8)), UTF_8);
    String tested =
        new String(
            facade.run(compiled(model, CompileOptions.TEST_RUN), input.getBytes(UTF_8)), UTF_8);
    assertThat(tested).as("test-run compiled against deployed").isEqualTo(deployed);
    return deployed;
  }

  /** Out with one Item per element of {@code items}, each its V values; null for no V. */
  private static String out(List<List<String>> items) {
    StringBuilder out = new StringBuilder(DECLARATION + "<Out>");
    for (List<String> values : items) {
      if (values.isEmpty()) {
        out.append("<Item/>");
        continue;
      }
      out.append("<Item>");
      for (String value : values) {
        out.append(value.isEmpty() ? "<V/>" : "<V>" + value + "</V>");
      }
      out.append("</Item>");
    }
    return out.append("</Out>").toString();
  }

  /** Skus a, b | (no line) | c. */
  private static final String ABC = orders(skus("a", "b"), "<Order/>", skus("c"));

  // --- documented

  @Test
  void aKnownKeyGivesItsValue() throws Exception {
    byte[] model = fixValues("\"table\": {\"a\": \"A\", \"b\": \"B\", \"c\": \"C\"}", SKU);

    assertThat(run(model, ABC)).isEqualTo(out(List.of(List.of("A", "B"), List.of(), List.of("C"))));
  }

  // --- assumed: a key the table lacks (obs-constant)

  /** {@code missing} left out is {@code key}: b and c are not in the table and pass as they are. */
  @Test
  void aMissingKeyGivesTheKeyItselfByDefault() throws Exception {
    List<List<String>> expected = List.of(List.of("A", "b"), List.of(), List.of("c"));

    assertThat(run(fixValues("\"table\": {\"a\": \"A\"}", SKU), ABC)).isEqualTo(out(expected));
    assertThat(run(fixValues("\"table\": {\"a\": \"A\"}, \"missing\": \"key\"", SKU), ABC))
        .isEqualTo(out(expected));
  }

  /** {@code missing: default}: b and c give {@code defaultValue}, or {@code ""} without one. */
  @Test
  void aMissingKeyGivesTheDefaultValueWhenMissingIsDefault() throws Exception {
    assertThat(
            run(
                fixValues(
                    "\"table\": {\"a\": \"A\"}, \"missing\": \"default\", \"defaultValue\": \"?\"",
                    SKU),
                ABC))
        .isEqualTo(out(List.of(List.of("A", "?"), List.of(), List.of("?"))));
    assertThat(run(fixValues("\"table\": {\"a\": \"A\"}, \"missing\": \"default\"", SKU), ABC))
        .isEqualTo(out(List.of(List.of("A", ""), List.of(), List.of(""))));
  }

  /**
   * {@code missing: error}: b stops the run with {@code po:FIXVALUES} at the box F1, in the decided
   * words; a run whose keys are all in the table writes its output.
   */
  @Test
  void aMissingKeyIsTheRuntimeErrorPoFixValuesWhenMissingIsError() throws Exception {
    byte[] model =
        fixValues("\"table\": {\"a\": \"A\", \"c\": \"C\"}, \"missing\": \"error\"", SKU);
    String message = "the function box F1: fixValues: the key \"b\" is not in the table";

    TestRunResult result =
        facade.testRun(
            compiled(model, CompileOptions.TEST_RUN), ABC.getBytes(UTF_8), TIMEOUT, LIMIT);

    assertThat(result).isInstanceOf(TestRunResult.RuntimeError.class);
    TestRunResult.RuntimeError error = (TestRunResult.RuntimeError) result;
    assertThat(error.code()).isEqualTo("po:FIXVALUES");
    assertThat(error.authoringId()).isEqualTo(Optional.of(new AuthoringId("F1")));
    assertThat(error.message()).isEqualTo(message);
    assertThatThrownBy(
            () -> facade.run(compiled(model, CompileOptions.DEPLOY), ABC.getBytes(UTF_8)))
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo("po:FIXVALUES");
              assertThat(e.authoringId()).isEqualTo(new AuthoringId("F1"));
              assertThat(e.getMessage()).isEqualTo(message);
            });
    // witness: every key in the table, no error
    assertThat(run(model, orders(skus("a", "c"), skus("c"))))
        .isEqualTo(out(List.of(List.of("A", "C"), List.of("C"))));
  }

  /**
   * An empty table holds no key: with {@code missing: default} every key gives the default value; a
   * box that leaves {@code table} out gets the default {@code {}}, so every key passes as it is.
   */
  @Test
  void anEmptyTableMissesEveryKey() throws Exception {
    assertThat(
            run(
                fixValues("\"table\": {}, \"missing\": \"default\", \"defaultValue\": \"-\"", SKU),
                ABC))
        .isEqualTo(out(List.of(List.of("-", "-"), List.of(), List.of("-"))));
    assertThat(run(fixValues("\"missing\": \"key\"", SKU), ABC))
        .isEqualTo(out(List.of(List.of("a", "b"), List.of(), List.of("c"))));
  }

  /**
   * The key comes from createIf over Tag: 1, 0 | 0 | 1 give "", SUPPRESS | SUPPRESS | "". The table
   * maps "" to E and {@code missing} is {@code error}: "" gives E, and SUPPRESS stays SUPPRESS,
   * creating no V and raising nothing. Read as "" it would give a second V in Item 1; read as a key
   * it would raise po:FIXVALUES.
   */
  @Test
  void aSuppressKeyStaysSuppress() throws Exception {
    String createIf =
        """
        "K1": {"kind": "function", "fn": "createIf", "inputs": ["G1"]},
        "G1": {"kind": "source", "field": "/Orders/Order/Tag"}""";
    byte[] model = fixValues("\"table\": {\"\": \"E\"}, \"missing\": \"error\"", createIf);
    String input =
        orders(
            "<Order><Tag>1</Tag><Tag>0</Tag></Order>",
            "<Order><Tag>0</Tag></Order>",
            "<Order><Tag>1</Tag></Order>");

    assertThat(run(model, input)).isEqualTo(out(List.of(List.of("E"), List.of(), List.of("E"))));
  }

  /** Only the exact key matches: case and leading or trailing spaces make another key. */
  @Test
  void theKeyMatchIsExactInCaseAndWhitespace() throws Exception {
    byte[] model =
        fixValues(
            "\"table\": {\"a\": \"A\", \" b\": \"B\"}, \"missing\": \"default\","
                + " \"defaultValue\": \"?\"",
            SKU);
    String input = orders(skus("A", " a", "a ", "a"), skus("b", " b", " b "));

    assertThat(run(model, input))
        .isEqualTo(out(List.of(List.of("?", "?", "?", "A"), List.of("?", "B", "?"))));
  }

  // --- design-time diagnostics at the box (ADR-0026 "Table params")

  private List<String> diagnostics(byte[] model) throws Exception {
    CompileResult result =
        facade.compile(
            Fingerprints.refreshed(model, observations()),
            DirectoryResolver.over(observations()),
            CompileOptions.DEPLOY);
    if (result instanceof CompileResult.Refused(List<Diagnostic> found)) {
      assertThat(found).allSatisfy(d -> assertThat(d.message()).isNotBlank());
      return found.stream().map(d -> d.severity() + " " + d.code() + " " + d.location()).toList();
    }
    throw new AssertionError("expected Refused, got " + result);
  }

  private static String atF1(String code) {
    return "ERROR " + code + " " + new AtAuthoringId(new AuthoringId("F1"));
  }

  /**
   * A string where the table belongs is {@code wrong-parameter-type}, and a {@code missing} outside
   * key, default and error is {@code invalid-parameter-value}, each at the box. The same box with a
   * table and a valid {@code missing} compiles.
   */
  @Test
  void aStringForTheTableAndAnUnknownMissingAreDiagnosticsAtTheBox() throws Exception {
    assertThat(diagnostics(fixValues("\"table\": \"a=A\"", SKU)))
        .containsExactly(atF1("wrong-parameter-type"));
    assertThat(diagnostics(fixValues("\"table\": {\"a\": \"A\"}, \"missing\": \"ignore\"", SKU)))
        .containsExactly(atF1("invalid-parameter-value"));
    assertThat(diagnostics(fixValues("\"table\": {\"a\": \"A\"}, \"missing\": \"Key\"", SKU)))
        .containsExactly(atF1("invalid-parameter-value"));
    // witness: the well-formed box compiles
    compiled(
        fixValues("\"table\": {\"a\": \"A\"}, \"missing\": \"error\"", SKU), CompileOptions.DEPLOY);
  }

  // --- the export (ADR-0026 "Table params")

  /**
   * The table travels as one XPath string literal of its compact canonical JSON (ADR-0010: keys by
   * UTF-16 code unit, JSON.stringify escapes), the apostrophe doubled, escaped for the attribute;
   * then {@code missing}, {@code defaultValue} and the box id.
   */
  @Test
  void theExportPassesTheTableAsOneStringLiteral() throws Exception {
    byte[] model = fixValues("\"table\": {\"it's\": \"x\", \"b\": \"\\\"q\\\"\"}", SKU);

    ExportResult result =
        facade.export(
            Fingerprints.refreshed(model, observations()), DirectoryResolver.over(observations()));

    assertThat(result).isInstanceOf(ExportResult.Exported.class);
    assertThat(((ExportResult.Exported) result).stylesheet())
        .contains(
            "po:fixValues($in1, '{&quot;b&quot;:&quot;\\&quot;q\\&quot;&quot;,"
                + "&quot;it''s&quot;:&quot;x&quot;}', 'key', '', 'F1')");
  }
}
