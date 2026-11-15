package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Wire.described;
import static io.flottila.mapping.service.Wire.quoted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * GATE check 1 (#100) through {@code POST /api/structures} over the service's workspace (ADR-0014,
 * ADR-0015): the gate structures of {@code corpus/structures/gate/} load, and each load-time
 * refusal of ADR-0008 is a 200 {@code refused} whose one diagnostic has a {@code file-position}
 * location. The positions are counted from the fixture text: an XML refusal stands after its
 * element's start tag, a JSON one at its keyword or schema.
 */
@QuarkusTest
class GateStructuresTest {

  private static final String REFUSED = "structures/gate/refused";
  private static final String NS = "urn:flottila:corpus:gate:refused";
  private static final String DOC = "{\"element\": \"Q{" + NS + "}Doc\"}";
  private static final String JSON = "{\"json\": \"$\"}";
  private static final String UNSUPPORTED = "schema-construct-unsupported";

  @TestHTTPResource URI root;

  private static String wsdl(String interfaceName) {
    return "{\"wsdl\": {\"interface\": \"Q{"
        + NS
        + "}"
        + interfaceName
        + "\", \"operation\": \"Op\", \"direction\": \"input\"}}";
  }

  static Stream<Arguments> refusals() {
    return Stream.of(
        argumentSet("xs:redefine", "redefine.xsd", DOC, UNSUPPORTED, "6:42"),
        argumentSet("xs:override", "override.xsd", DOC, UNSUPPORTED, "6:42"),
        argumentSet("XSD 1.1 (xs:assert)", "xsd11-assert.xsd", DOC, UNSUPPORTED, "11:29"),
        argumentSet(
            "an include outside the bundle",
            "outside-bundle.xsd",
            DOC,
            "schema-reference-outside-bundle",
            "6:45"),
        argumentSet("JSON Schema draft-07", "draft-07.schema.json", JSON, UNSUPPORTED, "2:3"),
        argumentSet("$dynamicRef", "dynamic-ref.schema.json", JSON, UNSUPPORTED, "6:42"),
        argumentSet(
            "a JSON root that is not an object",
            "root-not-object.schema.json",
            JSON,
            UNSUPPORTED,
            "1:1"),
        argumentSet("an array of arrays", "array-of-arrays.schema.json", JSON, UNSUPPORTED, "6:31"),
        argumentSet(
            "a merged property with two shapes",
            "two-shapes.schema.json",
            JSON,
            UNSUPPORTED,
            "7:30"),
        argumentSet("WSDL 1.1 RPC part@type", "rpc-part.wsdl", wsdl("Port"), UNSUPPORTED, "9:44"),
        argumentSet(
            "WSDL 1.1 message with two parts",
            "two-parts.wsdl",
            wsdl("Port"),
            UNSUPPORTED,
            "21:49"),
        argumentSet("WSDL 2.0 message #any", "message-any.wsdl", wsdl("Port"), UNSUPPORTED, "9:30"),
        argumentSet(
            "WSDL 2.0 message #none", "message-none.wsdl", wsdl("Port"), UNSUPPORTED, "9:31"),
        argumentSet(
            "WSDL 2.0 message #other", "message-other.wsdl", wsdl("Port"), UNSUPPORTED, "9:32"),
        argumentSet(
            "WSDL 2.0 interface with extends",
            "extends.wsdl",
            wsdl("Child"),
            UNSUPPORTED,
            "20:46"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("refusals")
  void eachLoadTimeRefusalIs200RefusedAtItsFilePosition(
      String file, String rootSelector, String code, String position) throws IOException {
    copy(REFUSED + "/" + file);

    Http.Reply reply = structure(REFUSED, List.of(file), rootSelector);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.json().path("kind").textValue()).as("%s", reply).isEqualTo("refused");
    JsonNode diagnostics = reply.json().path("diagnostics");
    assertThat(diagnostics.path(0).path("location").path("kind").textValue())
        .isEqualTo("file-position");
    assertThat(described(diagnostics))
        .containsExactly("error " + code + " at file " + file + " " + position);
  }

  static Stream<Arguments> gateStructures() {
    return Stream.of(
        argumentSet(
            "purchase-order: three files, an include and an import",
            List.of(
                "../structures/gate/purchase-order/common/common.xsd",
                "../structures/gate/purchase-order/order.xsd",
                "../structures/gate/purchase-order/types.xsd"),
            "{\"element\": \"Q{urn:flottila:corpus:gate:po}PurchaseOrder\"}",
            "sha256:c7a83f28ce9966a4cb158317dc7a29a6e89c59ec6ed129bbd3317aa50a7293f3"),
        argumentSet(
            "invoice: a second namespace and an element reference across files",
            List.of(
                "../structures/gate/invoice/invoice.xsd", "../structures/gate/invoice/party.xsd"),
            "{\"element\": \"Q{urn:flottila:corpus:gate:invoice}Invoice\"}",
            "sha256:58de05eec9c995263cd630786bcdecf224806c5e23cdecad4681bea25648ea4a"),
        argumentSet(
            "order.schema.json: JSON Schema 2020-12",
            List.of("../structures/gate/order.schema.json"),
            JSON,
            "sha256:ee54dcfa5f2ce0e42c2cbae54b270f17d2a89877141513ca5a9b8a3944df7f5d"));
  }

  /**
   * Each gate structure loads from the workspace as the gate mappings name it, from {@code
   * mappings/}, with the fingerprint {@code FingerprintPinTest} pins: a JSON structure's type ids
   * hold its file as the bundle spells it, so its fingerprint does too.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("gateStructures")
  void eachGateStructureLoadsFromTheWorkspace(
      List<String> bundle, String rootSelector, String fingerprint) throws IOException {
    TempWorkspace.mkdirs("mappings");
    for (String file : bundle) {
      copy(file.substring("../".length()));
    }

    Http.Reply reply = structure("mappings", bundle, rootSelector);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.json().path("kind").textValue()).as("%s", reply).isEqualTo("loaded");
    assertThat(reply.json().path("fingerprint").textValue()).isEqualTo(fingerprint);
  }

  /** Copies {@code corpus/<relative>} to the same path in the workspace. */
  private static void copy(String relative) throws IOException {
    TempWorkspace.write(
        relative, Files.readAllBytes(Fixtures.repositoryRoot().resolve("corpus/" + relative)));
  }

  private Http.Reply structure(String base, List<String> bundle, String rootSelector) {
    String files = String.join(", ", bundle.stream().map(Wire::quoted).toList());
    return new Http(root)
        .post(
            "api/structures",
            "application/json",
            bytes(
                "{\"base\": "
                    + quoted(base)
                    + ", \"bundle\": ["
                    + files
                    + "], \"root\": "
                    + rootSelector
                    + "}"));
  }
}
