package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.FINGERPRINT;
import static dev.rail8.mapping.studio.Fixtures.NS;
import static dev.rail8.mapping.studio.Fixtures.ORDER;
import static dev.rail8.mapping.studio.Wire.assertProblem;
import static dev.rail8.mapping.studio.Wire.described;
import static dev.rail8.mapping.studio.Wire.structureRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code structure}: the structure graph and its fingerprint, or a load error as a 200 {@code
 * refused} at its file position. The expected fields are those of {@code
 * corpus/structures/skeleton/order.xsd}, in the engine's order (attributes first). Each bundle
 * reference is read against the request's {@code base} as a directory of the corpus.
 */
class StructureTest {

  private static final Calls CALLS = Calls.studio();

  /** {@code structure} of one bundle file, read against {@code base} in the corpus. */
  private static Calls.Answer structure(String base, String bundle, String rootElement) {
    return structure(Workspace.corpus(base), base, bundle, rootElement);
  }

  /** {@code structure} of one bundle file, read from {@code workspace}. */
  private static Calls.Answer structure(
      Workspace workspace, String base, String bundle, String rootElement) {
    return CALLS.post("structure", structureRequest(base, bundle, rootElement), workspace);
  }

  /** The bytes of {@code corpus/<relative>}. */
  private static byte[] corpus(String relative) throws IOException {
    return Files.readAllBytes(Fixtures.repositoryRoot().resolve("corpus/" + relative));
  }

  @Test
  void returnsGraphAndFingerprint() {
    Calls.Answer reply = structure("structures/skeleton", "order.xsd", ORDER);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode loaded = reply.body();
    assertThat(loaded.path("kind").textValue()).isEqualTo("loaded");
    assertThat(loaded.path("fingerprint").textValue()).isEqualTo(FINGERPRINT);
    JsonNode rootField = loaded.path("root");
    assertThat(field(rootField)).isEqualTo("Q{" + NS + "}Order 1..1");
    assertThat(rootField.path("type").textValue()).isEqualTo("/" + ORDER);
    JsonNode types = loaded.path("types");
    assertThat(types.properties()).extracting(e -> e.getKey()).containsExactly("/" + ORDER);
    List<String> fields = new ArrayList<>();
    for (JsonNode f : types.path("/" + ORDER)) {
      fields.add(field(f));
    }
    assertThat(fields)
        .containsExactly(
            "channel 0..1",
            "OrderId 1..1",
            "Quantity 0..1",
            "Amount 0..1 nillable",
            "Urgent 0..1",
            "DeliveryDate 0..1",
            "PickupTime 0..1",
            "CreatedAt 0..1",
            "Note 0..unbounded");
    JsonNode channel = types.path("/" + ORDER).path(0);
    JsonNode orderId = types.path("/" + ORDER).path(1);
    assertThat(channel.path("kind").textValue())
        .as("an attribute's kind is not an element's")
        .isNotNull()
        .isNotEqualTo(orderId.path("kind").textValue());
    assertThat(orderId.has("valueType")).isTrue();
    assertThat(rootField.has("valueType")).as("a complex root has no value type").isFalse();

    // base "" is the workspace root: the reference is read against the corpus itself
    Calls.Answer fromRoot = structure("", "structures/skeleton/order.xsd", ORDER);
    assertThat(fromRoot.body().path("fingerprint").textValue()).isEqualTo(FINGERPRINT);
  }

  @Test
  void unreadableSchemaIs200RefusedAtFilePosition() throws IOException {
    Calls.Answer missing = structure("structures/skeleton", "missing.xsd", ORDER);

    assertThat(missing.status()).as("%s", missing).isEqualTo(200);
    assertThat(missing.body().path("kind").textValue()).isEqualTo("refused");
    assertThat(described(missing.body().path("diagnostics")))
        .containsExactly("error schema-unreadable at file missing.xsd 1:1");

    // a file that is not well-formed: the engine's position of the error
    Workspace broken =
        Workspace.corpus("structures/broken")
            .with("malformed.xsd", corpus("structures/skeleton/test-fixtures/malformed.xsd"));
    Calls.Answer malformed = structure(broken, "structures/broken", "malformed.xsd", ORDER);

    assertThat(malformed.status()).as("%s", malformed).isEqualTo(200);
    assertThat(malformed.body().path("kind").textValue()).isEqualTo("refused");
    assertThat(described(malformed.body().path("diagnostics")))
        .containsExactly("error schema-not-well-formed at file malformed.xsd 7:7");
  }

  /**
   * Every load error of a bundle comes back in the one {@code refused}, files in bundle order and
   * each file in document order.
   */
  @Test
  void everyLoadErrorOfABundleIs200Refused() throws IOException {
    Workspace workspace = Workspace.corpus("structures/names-after-errors");
    for (String file : List.of("user.xsd", "broken.xsd")) {
      workspace.with(file, corpus("structures/files-and-names/names-after-errors/" + file));
    }

    Calls.Answer reply =
        CALLS.post(
            "structure",
            "{\"base\": \"structures/names-after-errors\", \"bundle\": [\"user.xsd\","
                + " \"broken.xsd\"], \"root\": {\"element\":"
                + " \"Q{urn:rail8:corpus:user}Doc\"}}",
            workspace);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.body().path("kind").textValue()).isEqualTo("refused");
    assertThat(described(reply.body().path("diagnostics")))
        .containsExactly(
            "error schema-construct-unsupported at file user.xsd 10:48",
            "error schema-invalid at file user.xsd 17:52",
            "error schema-construct-unsupported at file user.xsd 18:67",
            "error schema-not-well-formed at file broken.xsd 8:3");
  }

  /**
   * A package reference reaches the engine, whose refusal is a load error at line 1, column 1 of
   * the reference: a 200 {@code refused}, never a 4xx.
   */
  @Test
  void packageReferenceIs200Refused() {
    Calls.Answer reply = structure("", "shared@1.2.0:/xsd/o.xsd", ORDER);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.body().path("kind").textValue()).as("%s", reply).isEqualTo("refused");
    assertThat(described(reply.body().path("diagnostics")))
        .containsExactly("error package-reference-unsupported at file shared@1.2.0:/xsd/o.xsd 1:1");
  }

  /**
   * Text with the {@code @} and {@code :} of a package reference that is not of its form is no
   * workspace path either, so the test names it as escaping: 400 {@code invalid-workspace-path},
   * named as the request spelled it. {@link #packageReferenceIs200Refused} is the witness that the
   * form itself reaches the engine.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"p@1:x.xsd", "p@:/x.xsd", "@1:/x.xsd", "p@1:/../x.xsd", "p@1:/a//b.xsd"})
  void malformedPackageReferenceIsInvalidWorkspacePath(String reference) {
    JsonNode problem =
        assertProblem(
            structure(Workspace.corpus("").escaping(reference), "", reference, ORDER),
            400,
            "invalid-workspace-path");

    assertThat(problem.path("detail").textValue())
        .isEqualTo("not a workspace path: \"" + reference + "\"");
  }

  /**
   * A missing bundle file's message names the file as the bundle spells it, never where its files
   * were looked for: it contains neither the corpus's directory nor any token that starts with
   * {@code /}.
   */
  @Test
  void schemaUnreadableShowsNoAbsolutePath() throws IOException {
    Calls.Answer missing = structure("structures/skeleton", "missing.xsd", ORDER);

    assertThat(missing.status()).as("%s", missing).isEqualTo(200);
    JsonNode diagnostics = missing.body().path("diagnostics");
    assertThat(described(diagnostics))
        .containsExactly("error schema-unreadable at file missing.xsd 1:1");
    String message = diagnostics.path(0).path("message").textValue();
    assertThat(message).isEqualTo("cannot read the schema file \"missing.xsd\"");
    Path corpus = Fixtures.repositoryRoot().resolve("corpus");
    assertThat(message)
        .doesNotContain(corpus.toString())
        .doesNotContain(corpus.toRealPath().toString());
    assertThat(message.split("[\\s\"'`:;,=()<>\\[\\]{}]+"))
        .as("the tokens of %s", message)
        .noneMatch(token -> token.startsWith("/"));
  }

  /**
   * The studio's root translator hands every wsdl shape the model schema allows to the engine's
   * WSDL reader. Over a bundle of no WSDL the interface is not found, at the first file's
   * xs:schema. A 400 would mean the translator refused a shape the schema defines.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("dev.rail8.mapping.studio.RootShapes#wsdlAllowed")
  void wsdlRootsReachTheWsdlReaderAsTheModelSchemaDefinesThem(String rootShape) {
    String request =
        "{\"base\": \"structures/skeleton\", \"bundle\": [\"order.xsd\"], \"root\": "
            + rootShape
            + "}";

    Calls.Answer reply = CALLS.post("structure", request, Workspace.corpus("structures/skeleton"));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.body().path("kind").textValue()).as("%s", reply).isEqualTo("refused");
    assertThat(described(reply.body().path("diagnostics")))
        .containsExactly("error schema-root-not-found at file order.xsd 6:56");
  }

  /** A wsdl shape the model schema forbids is not a root selector: 400 invalid-request. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("dev.rail8.mapping.studio.RootShapes#wsdlForbidden")
  void wsdlShapesTheModelSchemaForbidsAreInvalidRequests(String rootShape) {
    String request =
        "{\"base\": \"structures/skeleton\", \"bundle\": [\"order.xsd\"], \"root\": "
            + rootShape
            + "}";

    assertProblem(
        CALLS.post("structure", request, Workspace.corpus("structures/skeleton")),
        400,
        "invalid-request");
  }

  /**
   * A WSDL 1.1 bundle loads under the input of an operation: the element of its message's part,
   * from the XSD its inline schema imports, with the fingerprint the corpus mapping {@code
   * wsdl-order-receipt} pins.
   */
  @Test
  void loadsAWsdlRoot() throws IOException {
    List<String> bundle =
        List.of(
            "wsdl/order-11.wsdl",
            "files-and-names/import-location/po/order.xsd",
            "files-and-names/import-location/common/types.xsd");
    Workspace workspace = Workspace.corpus("structures/wsdl-load");
    for (String file : bundle) {
      workspace.with(file, corpus("structures/" + file));
    }

    Calls.Answer reply =
        CALLS.post(
            "structure",
            "{\"base\": \"structures/wsdl-load\", \"bundle\": [\""
                + String.join("\", \"", bundle)
                + "\"], \"root\": {\"wsdl\": {\"interface\":"
                + " \"Q{urn:rail8:corpus:wsdl11}OrderPort\", \"operation\": \"Submit\","
                + " \"direction\": \"input\"}}}",
            workspace);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode loaded = reply.body();
    assertThat(loaded.path("kind").textValue()).as("%s", reply).isEqualTo("loaded");
    assertThat(loaded.path("fingerprint").textValue())
        .isEqualTo("sha256:249dfbcb6f3d5834555fa5262f198fd6a817f13c41c9526862e3ea2f4b5d3b83");
    JsonNode rootField = loaded.path("root");
    assertThat(field(rootField)).isEqualTo("Q{urn:rail8:corpus:po}Order 1..1");
    List<String> fields = new ArrayList<>();
    for (JsonNode f : loaded.path("types").path(rootField.path("type").textValue())) {
      fields.add(field(f));
    }
    assertThat(fields)
        .containsExactly(
            "Q{urn:rail8:corpus:po}Id 1..1",
            "Q{urn:rail8:corpus:po}ShipTo 1..1",
            "Q{urn:rail8:corpus:po}Total 1..1",
            "Q{urn:rail8:corpus:common}Note 0..unbounded");
  }

  /** The JSON Schema bundle of {@code corpus/structures/json-schema/bundle}, as a workspace. */
  private static Workspace jsonBundle() throws IOException {
    Workspace workspace = Workspace.corpus("structures/json");
    for (String file : List.of("order.schema.json", "defs/address.schema.json")) {
      workspace.with(file, corpus("structures/json-schema/bundle/" + file));
    }
    return workspace;
  }

  private static Calls.Answer jsonStructure(Workspace workspace, String bundle) {
    return CALLS.post(
        "structure",
        "{\"base\": \"structures/json\", \"bundle\": " + bundle + ", \"root\": {\"json\": \"$\"}}",
        workspace);
  }

  /**
   * The json root of the model schema reads a JSON Schema, a $ref to a second bundle file and all.
   * Its fields are JSON properties under the root {@code $}, and a named type is one structure type
   * with its file and pointer as id.
   */
  @Test
  void aJsonRootReadsAJsonSchemaBundle() throws IOException {
    Calls.Answer reply =
        jsonStructure(jsonBundle(), "[\"order.schema.json\", \"defs/address.schema.json\"]");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode loaded = reply.body();
    assertThat(loaded.path("kind").textValue()).as("%s", reply).isEqualTo("loaded");
    assertThat(loaded.path("fingerprint").textValue()).matches("sha256:[0-9a-f]{64}");
    JsonNode rootField = loaded.path("root");
    assertThat(field(rootField)).isEqualTo("$ 1..1");
    assertThat(rootField.path("kind").textValue()).isEqualTo("json-property");
    assertThat(rootField.path("step").textValue()).isEqualTo("$");
    assertThat(rootField.path("type").textValue()).isEqualTo("order.schema.json#");
    JsonNode types = loaded.path("types");
    assertThat(types.properties())
        .extracting(e -> e.getKey())
        .containsExactlyInAnyOrder("order.schema.json#", "defs/address.schema.json#/$defs/Address");
    List<String> fields = new ArrayList<>();
    for (JsonNode f : types.path("order.schema.json#")) {
      fields.add(f.path("kind").textValue() + " " + field(f) + " " + f.path("valueType").asText());
    }
    assertThat(fields)
        .containsExactly(
            "json-property id 0..1 xs:string",
            "json-property shipTo 0..1 ",
            "json-property country 0..1 xs:string");
    assertThat(types.path("order.schema.json#").path(1).path("type").textValue())
        .isEqualTo("defs/address.schema.json#/$defs/Address");
  }

  /**
   * A $ref to a file the bundle does not list is a load error at the $ref: a 200 {@code refused}
   * with its file position, and the unlisted file is never read: the call needs only the bundle's
   * file.
   */
  @Test
  void aJsonRefOutsideTheBundleIs200RefusedAtTheRef() throws IOException {
    Calls.Answer reply = jsonStructure(jsonBundle(), "[\"order.schema.json\"]");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.needed()).as("%s", reply).containsExactly("order.schema.json");
    assertThat(reply.body().path("kind").textValue()).as("%s", reply).isEqualTo("refused");
    assertThat(described(reply.body().path("diagnostics")))
        .containsExactly(
            "error schema-reference-outside-bundle at file order.schema.json 7:16",
            "error schema-reference-outside-bundle at file order.schema.json 8:17");
  }

  /**
   * Same-step siblings each carry the structure's refusal, {@code ambiguous-field-path} at the
   * step's second declaration, here {@code Note} at line 14 and {@code Ref} at line 19 of {@code
   * corpus/structures/refused/same-step.xsd}; an ordinary field carries none.
   */
  @Test
  void sameStepSiblingsCarryTheirRefusalAndAnOrdinaryFieldNone() throws IOException {
    Workspace workspace =
        Workspace.corpus("structures/refused")
            .with("same-step.xsd", corpus("structures/refused/same-step.xsd"));

    Calls.Answer reply = structure(workspace, "structures/refused", "same-step.xsd", "Doc");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode loaded = reply.body();
    assertThat(loaded.path("kind").textValue()).as("%s", reply).isEqualTo("loaded");
    assertThat(refusals(loaded.path("types").path(loaded.path("root").path("type").textValue())))
        .containsExactly(
            "Id -",
            "Note ambiguous-field-path same-step.xsd 14:65",
            "Code -",
            "Note ambiguous-field-path same-step.xsd 14:65",
            "Head -");
    assertThat(refusals(loaded.path("types").path("/Doc/Head")))
        .containsExactly(
            "Ref ambiguous-field-path same-step.xsd 19:56",
            "Ref ambiguous-field-path same-step.xsd 19:56");
    assertThat(loaded.path("root").has("refusal")).as("the root is an ordinary field").isFalse();
  }

  /**
   * Same-step structure fields: both {@code Part}s carry {@code ambiguous-field-path} at the second
   * one (line 11). A type's fields are shared by every field of that type, since the structure is a
   * graph, so a child of a refused field carries only its own refusal on the wire, here none; the
   * editor's tree gives the row below a refused field the refusal nearest the root.
   */
  @Test
  void aChildOfARefusedFieldCarriesItsOwnRefusalOnlyOnTheGraph() {
    Workspace workspace =
        Workspace.corpus("structures/refused-parts")
            .with(
                "parts.xsd",
                """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="Body">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="Part">
                          <xs:complexType>
                            <xs:sequence><xs:element name="A" type="xs:string"/></xs:sequence>
                          </xs:complexType>
                        </xs:element>
                        <xs:element name="Part" maxOccurs="2">
                          <xs:complexType>
                            <xs:sequence><xs:element name="B" type="xs:string"/></xs:sequence>
                          </xs:complexType>
                        </xs:element>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

    Calls.Answer reply = structure(workspace, "structures/refused-parts", "parts.xsd", "Body");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode types = reply.body().path("types");
    JsonNode parts = types.path(reply.body().path("root").path("type").textValue());
    assertThat(refusals(parts))
        .containsExactly(
            "Part ambiguous-field-path parts.xsd 10:47",
            "Part ambiguous-field-path parts.xsd 10:47");
    for (JsonNode part : parts) {
      assertThat(refusals(types.path(part.path("type").textValue())))
          .as("the child type %s", part.path("type"))
          .singleElement(InstanceOfAssertFactories.STRING)
          .endsWith(" -");
    }
  }

  /**
   * A refusal's {@code side} is {@code target} for a reason that holds in a target only, here
   * {@code interleaved-target-group} on the fields of a repeated sequence, and {@code both} for
   * {@code ambiguous-field-path}: the editor reads it, never a list of codes.
   */
  @Test
  void aRefusalNamesTheSideItHoldsOn() {
    Workspace workspace =
        Workspace.corpus("structures/refused-sides")
            .with(
                "sides.xsd",
                """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="Doc">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:sequence maxOccurs="unbounded">
                          <xs:element name="A" type="xs:string"/>
                          <xs:element name="B" type="xs:string"/>
                        </xs:sequence>
                        <xs:element name="Note" type="xs:string" minOccurs="0"/>
                        <xs:element name="Note" type="xs:string" minOccurs="0"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

    Calls.Answer reply = structure(workspace, "structures/refused-sides", "sides.xsd", "Doc");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    List<String> sides = new ArrayList<>();
    for (JsonNode f : reply.body().path("types").path("/Doc")) {
      sides.add(
          f.path("localName").textValue()
              + " "
              + f.path("refusal").path("code").textValue()
              + " "
              + f.path("refusal").path("side").textValue());
    }
    assertThat(sides)
        .containsExactly(
            "A interleaved-target-group target",
            "B interleaved-target-group target",
            "Note ambiguous-field-path both",
            "Note ambiguous-field-path both");
  }

  /** Each field as {@code localName code file line:column}, or {@code localName -} without one. */
  private static List<String> refusals(JsonNode fields) {
    List<String> described = new ArrayList<>();
    for (JsonNode f : fields) {
      JsonNode refusal = f.path("refusal");
      described.add(
          f.path("localName").textValue()
              + (refusal.isMissingNode()
                  ? " -"
                  : " "
                      + refusal.path("code").textValue()
                      + " "
                      + refusal.path("file").textValue()
                      + " "
                      + refusal.path("line").asInt()
                      + ":"
                      + refusal.path("column").asInt()));
    }
    return described;
  }

  /**
   * A field as {@code name min..max}, with {@code Q{ns}} for a namespace and {@code nillable}; an
   * absent {@code max} is unbounded.
   */
  private static String field(JsonNode f) {
    String namespace = f.path("namespace").textValue();
    String name =
        (namespace == null || namespace.isEmpty() ? "" : "Q{" + namespace + "}")
            + f.path("localName").textValue();
    String max = f.has("max") ? f.path("max").asText() : "unbounded";
    return name
        + " "
        + f.path("min").asText()
        + ".."
        + max
        + (f.path("nillable").asBoolean(false) ? " nillable" : "");
  }
}
