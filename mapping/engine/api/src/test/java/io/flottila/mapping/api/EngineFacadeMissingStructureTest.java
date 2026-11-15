package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A mapping without its source or target structure (ADR-0029, #153): a new mapping has none until
 * the Signature tab chooses them. Compile, test and export give the stage-3 error {@code
 * missing-structure} at {@code /source} or {@code /target} before any bundle is read, and run no
 * further stage; refresh loads only the sides present. The structure is the skeleton's {@code
 * order.xsd}, held by a resolver that records every reference it is asked for.
 */
class EngineFacadeMissingStructureTest {

  private static final String NS = "urn:flottila:corpus:skeleton";

  /** The real fingerprint of {@code order.xsd} under {@code Order} ({@code FingerprintPinTest}). */
  private static final String FINGERPRINT =
      "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d";

  /** A well-formed fingerprint that no structure gives. */
  private static final String STALE = "sha256:" + "0".repeat(64);

  /**
   * A side: the bundle file {@code %s} under the root {@code Order}, storing fingerprint {@code
   * %s}.
   */
  private static final String SIDE =
      """
      {"bundle": ["%s"], "fingerprint": "%s", "root": {"element": "Q{urn:flottila:corpus:skeleton}Order"}}""";

  /** {@code OrderId <- a1} at t1, with the result {@code %s}. */
  private static final String FIELD_LINK =
      """
      {"/Q{urn:flottila:corpus:skeleton}Order/OrderId": {"id": "t1", "result": %s,
        "boxes": {"a1": {"kind": "source", "field": "/Q{urn:flottila:corpus:skeleton}Order/OrderId"}}}}""";

  private static String side(String file, String fingerprint) {
    return SIDE.formatted(file, fingerprint);
  }

  /** A model with {@code members}, the JSON members after {@code formatVersion}. */
  private static byte[] model(String members) {
    return ("{\"formatVersion\": 1" + members + "}\n").getBytes(UTF_8);
  }

  private static String source(String side) {
    return ", \"source\": " + side;
  }

  private static String target(String side) {
    return ", \"target\": " + side;
  }

  private static String linked(String result) {
    return ", \"targetFieldMappings\": " + FIELD_LINK.formatted(result);
  }

  private static final String ORDER = side("order.xsd", FINGERPRINT);

  private static final byte[] BOTH = model(source(ORDER) + target(ORDER) + linked("\"a1\""));
  private static final byte[] NO_SOURCE = model(target(ORDER) + linked("\"a1\""));
  private static final byte[] NO_TARGET = model(source(ORDER) + linked("\"a1\""));
  private static final byte[] NEITHER = model("");

  /** A resolver that holds the skeleton's {@code order.xsd} and records each reference read. */
  private static RecordingResolver resolver() throws IOException {
    return new RecordingResolver()
        .file(
            "order.xsd",
            Files.readAllBytes(CorpusStructures.directory("skeleton").resolve("order.xsd")));
  }

  /** Severity, code and location; never the message (ADR-0015). */
  private static List<String> described(List<Diagnostic> diagnostics) {
    assertThat(diagnostics).allSatisfy(d -> assertThat(d.message()).isNotBlank());
    return diagnostics.stream()
        .map(d -> d.severity() + " " + d.code() + " " + d.location())
        .toList();
  }

  /** {@code missing-structure} at each pointer, as {@link #described} shows it. */
  private static List<String> missingAt(String... pointers) {
    return described(
        Stream.of(pointers)
            .map(
                p ->
                    new Diagnostic(
                        Severity.ERROR,
                        "missing-structure",
                        new AtPointer(p),
                        "the side is missing"))
            .toList());
  }

  private static List<Diagnostic> refusal(CompileResult result) {
    if (result instanceof CompileResult.Refused(List<Diagnostic> diagnostics)) {
      return diagnostics;
    }
    throw new AssertionError("expected Refused, got " + result);
  }

  static Stream<Arguments> modelsWithoutASide() {
    return Stream.of(
        Arguments.of("no source", NO_SOURCE, missingAt("/source")),
        Arguments.of("no target", NO_TARGET, missingAt("/target")),
        Arguments.of("neither", NEITHER, missingAt("/source", "/target")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("modelsWithoutASide")
  void compileOfAModelWithoutASideIsMissingStructureBeforeAnyBundleIsRead(
      String what, byte[] model, List<String> expected) throws Exception {
    EngineFacade facade = new EngineFacade();
    // witness: with both sides the model compiles, reading the bundle
    RecordingResolver both = resolver();
    assertThat(facade.compile(BOTH, both, CompileOptions.DEPLOY))
        .isInstanceOf(CompileResult.Compiled.class);
    assertThat(both.calls()).contains("order.xsd");

    for (CompileOptions options : List.of(CompileOptions.DEPLOY, CompileOptions.TEST_RUN)) {
      RecordingResolver resolver = resolver();

      List<Diagnostic> diagnostics = refusal(facade.compile(model, resolver, options));

      assertThat(described(diagnostics)).as("%s", options).containsExactlyElementsOf(expected);
      assertThat(resolver.calls()).as("%s: references read", options).isEmpty();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("modelsWithoutASide")
  void theDiagnosticsOfAModelWithoutASideAreMissingStructure(
      String what, byte[] model, List<String> expected) throws Exception {
    RecordingResolver resolver = resolver();

    List<Diagnostic> diagnostics = new EngineFacade().diagnostics(model, resolver);

    assertThat(described(diagnostics)).containsExactlyElementsOf(expected);
    assertThat(resolver.calls()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("modelsWithoutASide")
  void exportOfAModelWithoutASideIsRefusedWithMissingStructure(
      String what, byte[] model, List<String> expected) throws Exception {
    RecordingResolver resolver = resolver();

    ExportResult result = new EngineFacade().export(model, resolver);

    assertThat(result)
        .isInstanceOfSatisfying(
            ExportResult.Refused.class,
            r -> assertThat(described(r.diagnostics())).containsExactlyElementsOf(expected));
    assertThat(resolver.calls()).isEmpty();
  }

  /**
   * No further stage runs: the side present names a file the resolver lacks and the target field
   * mapping is unconnected, yet {@code missing-structure} is the one diagnostic. With both sides
   * the same model gives the load error instead.
   */
  @Test
  void aMissingSideIsTheOnlyDiagnosticEvenWhenTheOtherSideAndStage3WouldFail() throws Exception {
    String missingFile = side("missing.xsd", FINGERPRINT);
    byte[] noTarget = model(source(missingFile) + linked("null"));
    byte[] withTarget = model(source(missingFile) + target(ORDER) + linked("null"));
    EngineFacade facade = new EngineFacade();
    // witness: with its target the model is refused for the missing file
    assertThat(described(refusal(facade.compile(withTarget, resolver(), CompileOptions.DEPLOY))))
        .anySatisfy(d -> assertThat(d).contains("schema-unreadable"));

    RecordingResolver resolver = resolver();
    List<Diagnostic> diagnostics =
        refusal(facade.compile(noTarget, resolver, CompileOptions.DEPLOY));

    assertThat(described(diagnostics)).containsExactlyElementsOf(missingAt("/target"));
    assertThat(resolver.calls()).isEmpty();
  }

  /** The canonical model of one side, {@code order.xsd} under {@code fingerprint}, and the link. */
  private static String oneSideCanonical(String side, String fingerprint) {
    return """
        {
          "formatVersion": 1,
          "%s": {
            "bundle": [
              "order.xsd"
            ],
            "fingerprint": "%s",
            "root": {
              "element": "Q{urn:flottila:corpus:skeleton}Order"
            }
          },
          "targetFieldMappings": {
            "/Q{urn:flottila:corpus:skeleton}Order/OrderId": {
              "boxes": {
                "a1": {
                  "field": "/Q{urn:flottila:corpus:skeleton}Order/OrderId",
                  "kind": "source"
                }
              },
              "id": "t1",
              "result": "a1"
            }
          }
        }
        """
        .formatted(side, fingerprint);
  }

  private static RefreshResult.Refreshed refreshed(RefreshResult result) {
    if (result instanceof RefreshResult.Refreshed refreshed) {
      return refreshed;
    }
    throw new AssertionError("expected Refreshed, got " + result);
  }

  static Stream<Arguments> oneSide() {
    return Stream.of(
        Arguments.of("source", model(source(side("order.xsd", STALE)) + linked("\"a1\""))),
        Arguments.of("target", model(target(side("order.xsd", STALE)) + linked("\"a1\""))));
  }

  /**
   * Refresh loads only the side present and re-derives its fingerprint; the absent side stays
   * absent, and with one structure stage 3 has nothing to run, so there is no diagnostic.
   */
  @ParameterizedTest(name = "only the {0}")
  @MethodSource("oneSide")
  void refreshOfAModelWithOneSideRederivesItAndLeavesTheOtherOut(String present, byte[] model)
      throws Exception {
    RecordingResolver resolver = resolver();

    RefreshResult.Refreshed result = refreshed(new EngineFacade().refresh(model, resolver));

    assertThat(new String(result.model(), UTF_8)).isEqualTo(oneSideCanonical(present, FINGERPRINT));
    assertThat(result.diagnostics()).isEmpty();
    assertThat(resolver.calls()).containsOnly("order.xsd");
  }

  @Test
  void refreshOfAModelWithoutStructuresReadsNothingAndReturnsItAsItIs() throws Exception {
    RecordingResolver resolver = resolver();

    RefreshResult.Refreshed result = refreshed(new EngineFacade().refresh(NEITHER, resolver));

    assertThat(new String(result.model(), UTF_8)).isEqualTo("{\n  \"formatVersion\": 1\n}\n");
    assertThat(result.diagnostics()).isEmpty();
    assertThat(resolver.calls()).isEmpty();
  }
}
