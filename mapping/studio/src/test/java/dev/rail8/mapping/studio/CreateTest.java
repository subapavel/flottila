package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.FINGERPRINT;
import static dev.rail8.mapping.studio.Fixtures.INPUT;
import static dev.rail8.mapping.studio.Fixtures.NEW_MAPPING;
import static dev.rail8.mapping.studio.Fixtures.ORDER;
import static dev.rail8.mapping.studio.Fixtures.assertBytes;
import static dev.rail8.mapping.studio.Fixtures.bytes;
import static dev.rail8.mapping.studio.Wire.assertProblem;
import static dev.rail8.mapping.studio.Wire.createRequest;
import static dev.rail8.mapping.studio.Wire.described;
import static dev.rail8.mapping.studio.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code create}: 201 with the new mapping in canonical form, its structures read against the
 * directory of the new mapping, here the one next to {@code structures/}. A structure that fails to
 * load is 422 {@code model-refused} with its load diagnostics. Expected models are corpus files or
 * the engine's canonical form, never the studio's answer.
 *
 * <p>A mapping can be created without its structures, so a working copy without one, or with a
 * package reference, is compiled and test-run here too.
 */
class CreateTest {

  private static final Calls CALLS = Calls.studio();

  private static final String BUNDLE = "../structures/skeleton/order.xsd";

  private static Calls.Answer create(String request) {
    return CALLS.post("create", request, Workspace.mappings());
  }

  @Test
  void createIs201WithModelEtagAndLocation() {
    String request = createRequest("creating/order-to-order", BUNDLE, ORDER);

    Calls.Answer answer = create(request);

    assertThat(answer.status()).as("%s", answer).isEqualTo(201);
    assertBytes(answer.bytes(), bytes(NEW_MAPPING));
    // the fingerprint is the corpus file's
    assertThat(answer.body().path("source").path("fingerprint").textValue()).isEqualTo(FINGERPRINT);
    assertThat(answer.body().path("target").path("fingerprint").textValue()).isEqualTo(FINGERPRINT);
    // the created model, stored as it was answered, reads back as the same bytes
    assertBytes(CALLS.stored(answer.bytes()).bytes(), bytes(NEW_MAPPING));
  }

  @Test
  void createWithUnreadableSchemaIs422() {
    String request =
        createRequest("creating-unreadable/order", "../structures/skeleton/missing.xsd", ORDER);

    JsonNode problem = assertProblem(create(request), 422, "model-refused");

    // both sides name the missing file; the engine reports it at line 1, column 1 of the file
    assertThat(described(problem.path("diagnostics")))
        .isNotEmpty()
        .containsOnly("error schema-unreadable at file ../structures/skeleton/missing.xsd 1:1");
  }

  /** A package reference, which only a host's resolver could read. */
  private static final String PACKAGE_REFERENCE = "shared@1.2.0:/xsd/o.xsd";

  /**
   * A create whose bundle is a package reference gets the engine's load error, as a missing file
   * does: 422 {@code model-refused}, never 400.
   */
  @Test
  void createWithAPackageReferenceIs422ModelRefused() {
    String request = createRequest("creating-package/order", PACKAGE_REFERENCE, ORDER);

    JsonNode problem = assertProblem(create(request), 422, "model-refused");

    assertThat(described(problem.path("diagnostics")))
        .isNotEmpty()
        .containsOnly("error package-reference-unsupported at file " + PACKAGE_REFERENCE + " 1:1");
  }

  /**
   * A test run whose working copy names a package reference as its source bundle is refused with
   * the engine's load error: a 200 {@code refused} with no output and no trace, never a 4xx. The
   * target is the skeleton's {@code order.xsd}, which loads, so the one diagnostic is the source's.
   */
  @Test
  void packageReferenceRefusesTheTestRun() {
    String model =
        """
        {"formatVersion": 1,
         "source": {"bundle": ["%1$s"], "fingerprint": "%2$s", "root": {"element": "%3$s"}},
         "target": {"bundle": ["../structures/skeleton/order.xsd"], "fingerprint": "%2$s",
                    "root": {"element": "%3$s"}},
         "targetFieldMappings": {"/%3$s/OrderId": {"id": "t1", "result": "a1",
           "boxes": {"a1": {"kind": "source", "field": "/%3$s/OrderId"}}}}}
        """
            .formatted(PACKAGE_REFERENCE, FINGERPRINT, ORDER);

    Calls.Answer answer = CALLS.post("test", testRequest(model, INPUT), Workspace.mappings());

    assertThat(answer.status()).as("%s", answer).isEqualTo(200);
    JsonNode result = answer.body();
    assertThat(result.path("kind").textValue()).as("%s", answer).isEqualTo("refused");
    assertThat(described(result.path("diagnostics")))
        .containsExactly(
            "error package-reference-unsupported at file " + PACKAGE_REFERENCE + " 1:1");
    assertThat(result.has("output")).isFalse();
    assertThat(result.has("trace")).isFalse();
  }

  /**
   * A create whose root is a wsdl shape the model schema allows reaches the WSDL reader, which
   * finds no interface in a bundle of no WSDL, at the first file's xs:schema: 422 {@code
   * model-refused}, never 400 {@code invalid-request}. Only the source has the shape.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wsdlAllowed")
  void createWithAWsdlRootIsTheWsdlReadersRefusal(String rootShape) {
    String target = createRequest("creating-root/order", BUNDLE, ORDER);
    String request =
        target.replaceFirst(
            "\"root\": \\{\"element\": \"[^\"]*\"\\}",
            Matcher.quoteReplacement("\"root\": " + rootShape));
    assertThat(request).as("only the source's root is replaced").isNotEqualTo(target);

    JsonNode problem = assertProblem(create(request), 422, "model-refused");

    assertThat(described(problem.path("diagnostics")))
        .containsExactly(
            "error schema-root-not-found at file ../structures/skeleton/order.xsd 6:56");
  }

  /**
   * A create whose root is a wsdl shape the model schema forbids is no root selector, as for a
   * structure: 400 {@code invalid-request}, before any structure is read.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wsdlForbidden")
  void createWithAWsdlRootTheModelSchemaForbidsIsAnInvalidRequest(String rootShape) {
    String target = createRequest("creating-root/order", BUNDLE, ORDER);
    String request =
        target.replaceFirst(
            "\"root\": \\{\"element\": \"[^\"]*\"\\}",
            Matcher.quoteReplacement("\"root\": " + rootShape));
    assertThat(request).as("only the source's root is replaced").isNotEqualTo(target);

    assertProblem(create(request), 400, "invalid-request");
  }

  // --- a mapping created with only its id, or with a description; a working copy without its
  // structures

  @Test
  void createWithOnlyAnIdIs201WithAMappingWithoutStructures() {
    Calls.Answer answer = create("{\"id\": \"creating-bare/draft\"}");

    assertThat(answer.status()).as("%s", answer).isEqualTo(201);
    assertBytes(answer.bytes(), bytes(Fixtures.BARE_MAPPING));
    // the created model, stored as it was answered, reads back as the same bytes
    assertBytes(CALLS.stored(answer.bytes()).bytes(), bytes(Fixtures.BARE_MAPPING));
  }

  /** The description is written as the model's first member, with or without the structures. */
  @Test
  void createWithADescriptionWritesIt() {
    String withStructures = createRequest("creating-described/both", BUNDLE, ORDER);
    String described = ", \"description\": " + Wire.quoted(Fixtures.DESCRIPTION) + "}";

    Calls.Answer bare = create("{\"id\": \"creating-described/bare\"" + described);
    Calls.Answer both =
        create(withStructures.substring(0, withStructures.length() - 1) + described);

    assertThat(bare.status()).as("%s", bare).isEqualTo(201);
    assertBytes(bare.bytes(), bytes(Fixtures.DESCRIBED_MAPPING));
    assertThat(both.status()).as("%s", both).isEqualTo(201);
    assertBytes(both.bytes(), bytes(Fixtures.DESCRIBED_NEW_MAPPING));
  }

  /**
   * A description is at most 4,000 code points: one more is 422 {@code model-refused} with stage
   * 1's {@code invalid-value} at {@code /description}; exactly 4,000 is created. Each code point
   * here is a supplementary character, two UTF-16 units.
   */
  @Test
  void createWithADescriptionOver4000CodePointsIs422AndNothingIsWritten() {
    String clef = "𝄞";

    Calls.Answer over = createDescribed("creating-long/over", clef.repeat(4001));
    Calls.Answer at = createDescribed("creating-long/at", clef.repeat(4000));

    JsonNode problem = assertProblem(over, 422, "model-refused");
    assertThat(described(problem.path("diagnostics")))
        .containsExactly("error invalid-value at pointer /description");
    assertThat(at.status()).as("%s", at).isEqualTo(201);
    assertThat(at.body().path("description").textValue()).isEqualTo(clef.repeat(4000));
  }

  /** A create of {@code id} with {@code description} and no structure. */
  private static Calls.Answer createDescribed(String id, String description) {
    return create(
        "{\"id\": " + Wire.quoted(id) + ", \"description\": " + Wire.quoted(description) + "}");
  }

  /** The corpus mapping's working copy without its target side. */
  private static final String WITHOUT_TARGET =
      """
      {"formatVersion": 1,
       "source": {"bundle": ["../structures/skeleton/order.xsd"], "fingerprint": "%2$s",
                  "root": {"element": "%1$s"}},
       "targetFieldMappings": {"/%1$s/OrderId": {"id": "t1", "result": "a1",
         "boxes": {"a1": {"kind": "source", "field": "/%1$s/OrderId"}}}}}
      """
          .formatted(ORDER, FINGERPRINT);

  /** A test run of a working copy without a structure is refused as a compile error. */
  @Test
  void aTestRunOfAWorkingCopyWithoutItsTargetIsRefusedWithMissingStructure() {
    Calls.Answer answer =
        CALLS.post("test", testRequest(WITHOUT_TARGET, INPUT), Workspace.mappings());

    assertThat(answer.status()).as("%s", answer).isEqualTo(200);
    JsonNode result = answer.body();
    assertThat(result.path("kind").textValue()).as("%s", answer).isEqualTo("refused");
    assertThat(described(result.path("diagnostics")))
        .containsExactly("error missing-structure at pointer /target");
    assertThat(result.has("output")).isFalse();
    assertThat(result.has("trace")).isFalse();
  }

  @Test
  void compileOfAWorkingCopyWithoutStructuresIs200WithMissingStructureAtEachSide() {
    Calls.Answer neither = CALLS.post("compile", "{\"formatVersion\": 1}", Workspace.mappings());
    Calls.Answer noTarget = CALLS.post("compile", WITHOUT_TARGET, Workspace.mappings());

    assertThat(neither.status()).as("%s", neither).isEqualTo(200);
    assertThat(described(neither.body().path("diagnostics")))
        .containsExactly(
            "error missing-structure at pointer /source",
            "error missing-structure at pointer /target");
    assertThat(noTarget.status()).as("%s", noTarget).isEqualTo(200);
    assertThat(described(noTarget.body().path("diagnostics")))
        .containsExactly("error missing-structure at pointer /target");
  }

  // --- the wsdl root shapes as contract/mapping-model.schema.json defines them ($defs/wsdlRoot and
  // wsdlOperation); the schema is read and checked first, so a change to it fails here before a
  // root test passes on a stale shape. The schema allows a fault exactly when the direction is
  // fault.

  /** Input and output without a fault, and fault with one. */
  static List<Named<String>> wsdlAllowed() {
    return wsdl(true);
  }

  /** Input and output with a fault, and fault without one. */
  static List<Named<String>> wsdlForbidden() {
    return wsdl(false);
  }

  /** Every direction of the schema's enum, with or without a fault, as the schema allows or not. */
  private static List<Named<String>> wsdl(boolean allowed) {
    List<String> directions = checkedSchema();
    List<Named<String>> shapes = new ArrayList<>();
    for (String direction : directions) {
      String members =
          "\"interface\": \"OrderService\", \"operation\": \"submit\", \"direction\": \""
              + direction
              + "\"";
      boolean fault = direction.equals("fault");
      if (fault != allowed) {
        shapes.add(Named.of("wsdl " + direction, "{\"wsdl\": {" + members + "}}"));
      } else {
        shapes.add(
            Named.of(
                "wsdl " + direction + " with a fault",
                "{\"wsdl\": {" + members + ", \"fault\": \"Rejected\"}}"));
      }
    }
    return shapes;
  }

  /** Checks the schema's root shapes and returns the directions of its enum. */
  private static List<String> checkedSchema() {
    JsonNode defs = schema().path("$defs");
    JsonNode operation = defs.path("wsdlOperation");
    assertThat(Wire.names(operation.path("properties")))
        .as("wsdlOperation members")
        .containsExactlyInAnyOrder("interface", "operation", "direction", "fault");
    assertThat(strings(operation.path("required")))
        .as("wsdlOperation required")
        .containsExactlyInAnyOrder("interface", "operation", "direction");
    assertThat(operation.path("additionalProperties").isBoolean()).isTrue();
    assertThat(operation.path("additionalProperties").booleanValue()).isFalse();
    assertThat(operation.path("properties").path("fault").path("type").textValue())
        .isEqualTo("string");
    List<String> directions = strings(operation.path("properties").path("direction").path("enum"));
    assertThat(directions)
        .as("wsdlOperation directions")
        .containsExactly("input", "output", "fault");
    assertThat(Wire.names(defs.path("wsdlRoot").path("properties"))).containsExactly("wsdl");
    assertThat(defs.path("jsonRoot").path("properties").path("json").path("const").textValue())
        .isEqualTo("$");
    // fault is present exactly when the direction is fault
    assertThat(operation.path("if").path("properties").path("direction").path("const").textValue())
        .isEqualTo("fault");
    assertThat(strings(operation.path("then").path("required"))).containsExactly("fault");
    assertThat(strings(operation.path("else").path("not").path("required")))
        .containsExactly("fault");
    return directions;
  }

  private static JsonNode schema() {
    try {
      return Wire.read(
          Files.readAllBytes(
              Fixtures.repositoryRoot().resolve("contract/mapping-model.schema.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> strings(JsonNode array) {
    List<String> strings = new ArrayList<>();
    for (JsonNode s : array) {
      strings.add(s.textValue());
    }
    return strings;
  }
}
