package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.AtAuthoringId;
import io.flottila.mapping.mapping.AtFilePosition;
import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Location;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link EngineFacade#diagnostics}, what {@code POST /compile} answers (ADR-0015, ADR-0027): the
 * diagnostics of the stage that refuses the model, as {@link EngineFacade#compile} reports them,
 * and for a model that passes stages 1 and 2 the {@code unreachable-box} warnings beside them
 * (ADR-0011, ADR-0013), all sorted by location, then code.
 */
class EngineFacadeDiagnosticsTest {

  private static final String NS = "urn:flottila:corpus:skeleton";
  private static final String ORDER = "/Q{" + NS + "}Order";

  /** The real fingerprint of {@code order.xsd}, as {@code EngineFacadeTest} has it. */
  private static final String FINGERPRINT =
      "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d";

  private static ReferenceResolver skeleton() {
    return DirectoryResolver.over(CorpusStructures.directory("skeleton"));
  }

  /** A model from {@code order.xsd} to itself with the given target field mappings. */
  private static byte[] orderToOrder(String targetFieldMappings) {
    String side =
        """
        {"bundle": ["order.xsd"], "fingerprint": "%s", "root": {"element": "Q{%s}Order"}}"""
            .formatted(FINGERPRINT, NS);
    return """
        {"formatVersion": 1, "source": %1$s, "target": %1$s, "targetFieldMappings": %2$s}
        """
        .formatted(side, targetFieldMappings.formatted(ORDER))
        .getBytes(UTF_8);
  }

  /** {@code OrderId <- a1}, and the function box f9 over a1 that nothing reads. */
  private static final byte[] DANGLING =
      orderToOrder(
          """
          {"%1$s/OrderId": {"id": "t1", "result": "a1",
            "boxes": {"a1": {"kind": "source", "field": "%1$s/OrderId"},
                      "f9": {"kind": "function", "fn": "trim", "inputs": ["a1"]}}}}
          """);

  /** Severity, code and location, in order; the message only has to be there (ADR-0015). */
  private static List<String> described(List<Diagnostic> diagnostics) {
    assertThat(diagnostics).allSatisfy(d -> assertThat(d.message()).isNotBlank());
    return diagnostics.stream()
        .map(d -> d.severity() + " " + d.code() + " " + d.location())
        .toList();
  }

  private static String warning(String code, Location location) {
    return "WARNING " + code + " " + location;
  }

  private static String error(String code, Location location) {
    return "ERROR " + code + " " + location;
  }

  private static AtAuthoringId at(String id) {
    return new AtAuthoringId(new AuthoringId(id));
  }

  @Test
  void aModelThatCompilesWithEveryBoxReachedHasNoDiagnostics() throws Exception {
    byte[] model =
        orderToOrder(
            """
            {"%1$s/OrderId": {"id": "t1", "result": "a1",
              "boxes": {"a1": {"kind": "source", "field": "%1$s/OrderId"}}}}
            """);

    assertThat(new EngineFacade().diagnostics(model, skeleton())).isEmpty();
  }

  @Test
  void aModelThatCompilesGivesTheUnreachableBoxWarningAlone() throws Exception {
    EngineFacade facade = new EngineFacade();
    // witness: the warning never refuses the model
    assertThat(facade.compile(DANGLING, skeleton(), CompileOptions.DEPLOY))
        .isInstanceOf(CompileResult.Compiled.class);

    assertThat(described(facade.diagnostics(DANGLING, skeleton())))
        .containsExactly(warning("unreachable-box", at("f9")));
  }

  @Test
  void stage3ErrorsAndTheWarningsComeTogetherByLocation() throws Exception {
    byte[] model =
        orderToOrder(
            """
            {"%1$s/OrderId": {"id": "t1", "result": null,
              "boxes": {"a1": {"kind": "source", "field": "%1$s/OrderId"}}},
             "%1$s/Quantity": {"id": "t2", "result": "a2",
              "boxes": {"a2": {"kind": "source", "field": "%1$s/Missing"}}}}
            """);

    assertThat(described(new EngineFacade().diagnostics(model, skeleton())))
        .containsExactly(
            warning("unreachable-box", at("a1")),
            error("orphaned-reference", at("a2")),
            error("unconnected-target-field", at("t1")));
  }

  @Test
  void aStructureLoadErrorComesWithTheWarnings(@TempDir Path empty) throws Exception {
    assertThat(described(new EngineFacade().diagnostics(DANGLING, DirectoryResolver.over(empty))))
        .containsExactly(
            warning("unreachable-box", at("f9")),
            error("schema-unreadable", new AtFilePosition("order.xsd", 1, 1)),
            error("schema-unreadable", new AtFilePosition("order.xsd", 1, 1)));
  }

  @Test
  void aStage1RefusalIsItsDiagnosticsAlone() throws Exception {
    byte[] newer =
        new String(DANGLING, UTF_8)
            .replace("\"formatVersion\": 1", "\"formatVersion\": 2")
            .getBytes(UTF_8);

    assertThat(described(new EngineFacade().diagnostics(newer, skeleton())))
        .containsExactly(error("format-version-unsupported", new AtPointer("/formatVersion")));
  }
}
