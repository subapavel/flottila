package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.FINGERPRINT;
import static io.flottila.mapping.service.Fixtures.NS;
import static io.flottila.mapping.service.Fixtures.ORDER;
import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Wire.assertProblem;
import static io.flottila.mapping.service.Wire.described;
import static io.flottila.mapping.service.Wire.structureRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * {@code POST /api/structures}: the structure graph and its fingerprint, or a load error as a 200
 * {@code refused} at its file position (ADR-0015, ADR-0024). The expected fields are those of
 * {@code corpus/structures/skeleton/order.xsd}, in the engine's order (attributes first).
 */
@QuarkusTest
class StructuresResourceTest {

  @TestHTTPResource URI root;

  private Http.Reply structure(String base, String bundle, String rootElement) {
    return new Http(root)
        .post(
            "api/structures",
            "application/json",
            bytes(structureRequest(base, bundle, rootElement)));
  }

  @Test
  void returnsGraphAndFingerprint() {
    Http.Reply reply = structure("structures/skeleton", "order.xsd", ORDER);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode loaded = reply.json();
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

    // base "" is the workspace root
    Http.Reply fromRoot = structure("", "structures/skeleton/order.xsd", ORDER);
    assertThat(fromRoot.json().path("fingerprint").textValue()).isEqualTo(FINGERPRINT);
  }

  @Test
  void unreadableSchemaIs200RefusedAtFilePosition() throws IOException {
    Http.Reply missing = structure("structures/skeleton", "missing.xsd", ORDER);

    assertThat(missing.status()).as("%s", missing).isEqualTo(200);
    assertThat(missing.json().path("kind").textValue()).isEqualTo("refused");
    assertThat(described(missing.json().path("diagnostics")))
        .containsExactly("error schema-unreadable at file missing.xsd 1:1");

    // a file that is not well-formed: the engine's position of the error (StructureReaderTest)
    TempWorkspace.write(
        "structures/broken/malformed.xsd",
        Files.readAllBytes(
            Fixtures.repositoryRoot()
                .resolve("corpus/structures/skeleton/test-fixtures/malformed.xsd")));
    Http.Reply malformed = structure("structures/broken", "malformed.xsd", ORDER);

    assertThat(malformed.status()).as("%s", malformed).isEqualTo(200);
    assertThat(malformed.json().path("kind").textValue()).isEqualTo("refused");
    assertThat(described(malformed.json().path("diagnostics")))
        .containsExactly("error schema-not-well-formed at file malformed.xsd 7:7");
  }

  /**
   * #93 (ADR-0008): every load error of a bundle comes back in the one {@code refused}, files in
   * bundle order and each file in document order.
   */
  @Test
  void everyLoadErrorOfABundleIs200Refused() throws IOException {
    for (String file : List.of("user.xsd", "broken.xsd")) {
      TempWorkspace.write(
          "structures/names-after-errors/" + file,
          Files.readAllBytes(
              Fixtures.repositoryRoot()
                  .resolve("corpus/structures/files-and-names/names-after-errors/" + file)));
    }

    Http.Reply reply =
        new Http(root)
            .post(
                "api/structures",
                "application/json",
                bytes(
                    "{\"base\": \"structures/names-after-errors\", \"bundle\": [\"user.xsd\","
                        + " \"broken.xsd\"], \"root\": {\"element\":"
                        + " \"Q{urn:flottila:corpus:user}Doc\"}}"));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.json().path("kind").textValue()).isEqualTo("refused");
    assertThat(described(reply.json().path("diagnostics")))
        .containsExactly(
            "error schema-construct-unsupported at file user.xsd 10:48",
            "error schema-invalid at file user.xsd 17:52",
            "error schema-construct-unsupported at file user.xsd 18:67",
            "error schema-not-well-formed at file broken.xsd 8:3");
  }

  /**
   * A package reference (ADR-0024, since the spec-4 grilling) passes the pattern and reaches the
   * engine, whose refusal is a load error at line 1, column 1 of the reference: a 200 {@code
   * refused}, never a 4xx (#90, ADR-0014 "Reference resolver shape").
   */
  @Test
  void packageReferenceIs200Refused() {
    Http.Reply reply = structure("", "shared@1.2.0:/xsd/o.xsd", ORDER);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.json().path("kind").textValue()).as("%s", reply).isEqualTo("refused");
    assertThat(described(reply.json().path("diagnostics")))
        .containsExactly("error package-reference-unsupported at file shared@1.2.0:/xsd/o.xsd 1:1");
  }

  /**
   * Text with the {@code @} and {@code :} of a package reference that is not of its ADR-0024 form
   * passes neither pattern: 400 {@code invalid-workspace-path}, named as the request spelled it.
   * {@link #packageReferenceIs200Refused} is the witness that the form itself reaches the engine.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"p@1:x.xsd", "p@:/x.xsd", "@1:/x.xsd", "p@1:/../x.xsd", "p@1:/a//b.xsd"})
  void malformedPackageReferenceIsInvalidWorkspacePath(String reference) {
    JsonNode problem =
        assertProblem(structure("", reference, ORDER), 400, "invalid-workspace-path");

    assertThat(problem.path("detail").textValue())
        .isEqualTo("not a workspace path: \"" + reference + "\"");
  }

  /**
   * A missing bundle file's message names the file as the bundle spells it, never where the service
   * looked: it contains neither the workspace's directory nor any token that starts with {@code /}
   * (#90, F-12).
   */
  @Test
  void schemaUnreadableShowsNoAbsolutePath() throws IOException {
    Http.Reply missing = structure("structures/skeleton", "missing.xsd", ORDER);

    assertThat(missing.status()).as("%s", missing).isEqualTo(200);
    JsonNode diagnostics = missing.json().path("diagnostics");
    assertThat(described(diagnostics))
        .containsExactly("error schema-unreadable at file missing.xsd 1:1");
    String message = diagnostics.path(0).path("message").textValue();
    assertThat(message).isEqualTo("cannot read the schema file \"missing.xsd\"");
    assertThat(message)
        .doesNotContain(TempWorkspace.root().toString())
        .doesNotContain(TempWorkspace.root().toRealPath().toString());
    assertThat(message.split("[\\s\"'`:;,=()<>\\[\\]{}]+"))
        .as("the tokens of %s", message)
        .noneMatch(token -> token.startsWith("/"));
  }

  /**
   * #96: the service's root translator hands every wsdl shape the model schema allows to the
   * engine's WSDL reader. Over a bundle of no WSDL the interface is not found, at the first file's
   * xs:schema ({@code
   * StructureReaderTest.a_WSDL_root_over_a_bundle_of_no_WSDL_is_schema_root_not_found}). A 400
   * would mean the translator refused a shape the schema defines.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("io.flottila.mapping.service.RootShapes#wsdlAllowed")
  void wsdlRootsReachTheWsdlReaderAsTheModelSchemaDefinesThem(String rootShape) {
    String request =
        "{\"base\": \"structures/skeleton\", \"bundle\": [\"order.xsd\"], \"root\": "
            + rootShape
            + "}";

    Http.Reply reply = new Http(root).post("api/structures", "application/json", bytes(request));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.json().path("kind").textValue()).as("%s", reply).isEqualTo("refused");
    assertThat(described(reply.json().path("diagnostics")))
        .containsExactly("error schema-root-not-found at file order.xsd 6:59");
  }

  /** #96: a wsdl shape the model schema forbids is not a root selector: 400 invalid-request. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("io.flottila.mapping.service.RootShapes#wsdlForbidden")
  void wsdlShapesTheModelSchemaForbidsAreInvalidRequests(String rootShape) {
    String request =
        "{\"base\": \"structures/skeleton\", \"bundle\": [\"order.xsd\"], \"root\": "
            + rootShape
            + "}";

    assertProblem(
        new Http(root).post("api/structures", "application/json", bytes(request)),
        400,
        "invalid-request");
  }

  /**
   * #96: a WSDL 1.1 bundle loads under the input of an operation: the element of its message's
   * part, from the XSD its inline schema imports, with the fingerprint of the corpus mapping {@code
   * wsdl-order-receipt} pins.
   */
  @Test
  void loadsAWsdlRoot() throws IOException {
    List<String> bundle =
        List.of(
            "wsdl/order-11.wsdl",
            "files-and-names/import-location/po/order.xsd",
            "files-and-names/import-location/common/types.xsd");
    for (String file : bundle) {
      TempWorkspace.write(
          "structures/wsdl-load/" + file,
          Files.readAllBytes(Fixtures.repositoryRoot().resolve("corpus/structures/" + file)));
    }

    Http.Reply reply =
        new Http(root)
            .post(
                "api/structures",
                "application/json",
                bytes(
                    "{\"base\": \"structures/wsdl-load\", \"bundle\": [\""
                        + String.join("\", \"", bundle)
                        + "\"], \"root\": {\"wsdl\": {\"interface\":"
                        + " \"Q{urn:flottila:corpus:wsdl11}OrderPort\", \"operation\": \"Submit\","
                        + " \"direction\": \"input\"}}}"));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode loaded = reply.json();
    assertThat(loaded.path("kind").textValue()).as("%s", reply).isEqualTo("loaded");
    assertThat(loaded.path("fingerprint").textValue())
        .isEqualTo("sha256:510d96ec830e4ad8aa2e477a6fe73640f58587cc84ce10ec68ce666ca65edd16");
    JsonNode rootField = loaded.path("root");
    assertThat(field(rootField)).isEqualTo("Q{urn:flottila:corpus:po}Order 1..1");
    List<String> fields = new ArrayList<>();
    for (JsonNode f : loaded.path("types").path(rootField.path("type").textValue())) {
      fields.add(field(f));
    }
    assertThat(fields)
        .containsExactly(
            "Q{urn:flottila:corpus:po}Id 1..1",
            "Q{urn:flottila:corpus:po}ShipTo 1..1",
            "Q{urn:flottila:corpus:po}Total 1..1",
            "Q{urn:flottila:corpus:common}Note 0..unbounded");
  }

  /** The JSON Schema bundle of {@code corpus/structures/json-schema/bundle}, in the workspace. */
  private static void jsonBundle() throws IOException {
    Path bundle = Fixtures.repositoryRoot().resolve("corpus/structures/json-schema/bundle");
    for (String file : List.of("order.schema.json", "defs/address.schema.json")) {
      TempWorkspace.write("structures/json/" + file, Files.readAllBytes(bundle.resolve(file)));
    }
  }

  private Http.Reply jsonStructure(String bundle) {
    return new Http(root)
        .post(
            "api/structures",
            "application/json",
            bytes(
                "{\"base\": \"structures/json\", \"bundle\": "
                    + bundle
                    + ", \"root\": {\"json\": \"$\"}}"));
  }

  /**
   * #97 (ADR-0008): the json root of the model schema reads a JSON Schema, a $ref to a second
   * bundle file and all. Its fields are JSON properties under the root {@code $}, and a named type
   * is one structure type with its file and pointer as id.
   */
  @Test
  void aJsonRootReadsAJsonSchemaBundle() throws IOException {
    jsonBundle();

    Http.Reply reply = jsonStructure("[\"order.schema.json\", \"defs/address.schema.json\"]");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode loaded = reply.json();
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
   * A $ref to a file the bundle does not list is a load error at the $ref (ADR-0008): a 200 {@code
   * refused} with its file position, and the unlisted file is never read: {@code
   * WorkspaceTest.aJsonRefToAnUnlistedFileIsOutsideTheBundleAndNeverRead} observes the opens.
   */
  @Test
  void aJsonRefOutsideTheBundleIs200RefusedAtTheRef() throws IOException {
    jsonBundle();

    Http.Reply reply = jsonStructure("[\"order.schema.json\"]");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.json().path("kind").textValue()).as("%s", reply).isEqualTo("refused");
    assertThat(described(reply.json().path("diagnostics")))
        .containsExactly(
            "error schema-reference-outside-bundle at file order.schema.json 7:16",
            "error schema-reference-outside-bundle at file order.schema.json 8:17");
  }

  /**
   * ADR-0027 "Refused fields on the wire" (#116): same-step siblings each carry the structure's
   * refusal (#92), {@code ambiguous-field-path} at the step's second declaration, here {@code Note}
   * at line 14 and {@code Ref} at line 19 of {@code corpus/structures/refused/same-step.xsd}; an
   * ordinary field carries none.
   */
  @Test
  void sameStepSiblingsCarryTheirRefusalAndAnOrdinaryFieldNone() throws IOException {
    TempWorkspace.write(
        "structures/refused/same-step.xsd",
        Files.readAllBytes(
            Fixtures.repositoryRoot().resolve("corpus/structures/refused/same-step.xsd")));

    Http.Reply reply = structure("structures/refused", "same-step.xsd", "Doc");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode loaded = reply.json();
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
   * one (line 11). A type's fields are shared by every field of that type (ADR-0015, a graph), so a
   * child of a refused field carries only its own refusal on the wire, here none; the editor's tree
   * gives the row below a refused field the refusal nearest the root (ADR-0027, {@code rows.ts}).
   */
  @Test
  void aChildOfARefusedFieldCarriesItsOwnRefusalOnlyOnTheGraph() {
    TempWorkspace.write(
        "structures/refused-parts/parts.xsd",
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
        """
            .getBytes(StandardCharsets.UTF_8));

    Http.Reply reply = structure("structures/refused-parts", "parts.xsd", "Body");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode types = reply.json().path("types");
    JsonNode parts = types.path(reply.json().path("root").path("type").textValue());
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
   * {@code interleaved-target-group} on the fields of a repeated sequence (#94), and {@code both}
   * for {@code ambiguous-field-path} (ADR-0027, #116): the editor reads it, never a list of codes.
   */
  @Test
  void aRefusalNamesTheSideItHoldsOn() {
    TempWorkspace.write(
        "structures/refused-sides/sides.xsd",
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
        """
            .getBytes(StandardCharsets.UTF_8));

    Http.Reply reply = structure("structures/refused-sides", "sides.xsd", "Doc");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    List<String> sides = new ArrayList<>();
    for (JsonNode f : reply.json().path("types").path("/Doc")) {
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
