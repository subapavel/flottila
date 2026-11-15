package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.FINGERPRINT;
import static io.flottila.mapping.service.Fixtures.assertBytes;
import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Fixtures.corpusMapping;
import static io.flottila.mapping.service.Fixtures.workingCopy;
import static io.flottila.mapping.service.Wire.assertProblem;
import static io.flottila.mapping.service.Wire.described;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /api/mappings/{id}/compile} (#105; ADR-0015, ADR-0027): the working copy in the body
 * answers 200 with its diagnostics, stages 1 to 3 and the {@code unreachable-box} warning
 * (ADR-0011, ADR-0013) alike, never a 4xx for them. Only a transport error is a problem; an
 * escaping id or bundle is in {@code WorkspaceEscapeTest}. Nothing is saved.
 */
@QuarkusTest
class CompileResourceTest {

  /** An id whose directory holds the corpus mapping, so its bundle references resolve from it. */
  private static final String CORPUS_ID = "mappings/order-id-copy";

  @TestHTTPResource URI root;

  private Http.Reply compile(String id, String contentType, byte[] body) {
    return new Http(root).post("api/mappings/" + id + "/compile", contentType, body);
  }

  /** The diagnostics of the 200 reply, which is a compile result and never a problem. */
  private Http.Reply compiled(String model) {
    Http.Reply reply = compile(CORPUS_ID, "application/json", bytes(model));
    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.contentType()).as("%s", reply).startsWith("application/json");
    return reply;
  }

  private List<String> diagnostics(String model) {
    JsonNode result = compiled(model).json();
    assertThat(result.properties()).extracting(Map.Entry::getKey).containsExactly("diagnostics");
    return described(result.path("diagnostics"));
  }

  @Test
  void aWorkingCopyThatCompilesHasNoDiagnosticsAndTheStoredFileIsUntouched() {
    assertThat(diagnostics(workingCopy("\"a1\""))).isEmpty();
    assertBytes(TempWorkspace.read(CORPUS_ID + ".mapping.json"), corpusMapping());
  }

  @Test
  void aBoxTheResultDoesNotReachIsTheUnreachableBoxWarningAtItsAuthoringId() {
    String model =
        workingCopy("\"a1\"")
            .replace(
                "\"boxes\": {",
                "\"boxes\": {\"f9\": {\"kind\": \"function\", \"fn\": \"trim\", \"inputs\": [\"a1\"]}, ");
    assertThat(model)
        .as("the working copy has boxes to add to")
        .isNotEqualTo(workingCopy("\"a1\""));

    assertThat(diagnostics(model)).containsExactly("warning unreachable-box at id f9");
  }

  @Test
  void stage1ErrorsAre200() {
    String model = "{\"extra\": 1, " + workingCopy("\"a1\"").substring(1);

    assertThat(diagnostics(model)).containsExactly("error unknown-member at pointer /extra");
  }

  @Test
  void stage2ErrorsAre200() {
    assertThat(diagnostics(workingCopy("\"zz\"")))
        .containsExactly("error unknown-box-reference at id t1");
  }

  @Test
  void stage3ErrorsAre200WithTheWarningsBesideThem() {
    assertThat(diagnostics(workingCopy("null")))
        .containsExactly(
            "warning unreachable-box at id a1", "error unconnected-target-field at id t1");
  }

  @Test
  void aStaleFingerprintIs200WithFingerprintMismatch() {
    String stale = workingCopy("\"a1\"").replace(FINGERPRINT, "sha256:" + "0".repeat(64));
    assertThat(stale).as("both sides store the fingerprint").isNotEqualTo(workingCopy("\"a1\""));

    assertThat(diagnostics(stale))
        .containsExactly(
            "error fingerprint-mismatch at pointer /source/fingerprint",
            "error fingerprint-mismatch at pointer /target/fingerprint");
  }

  @Test
  void aBundleThatDoesNotLoadIs200WithItsFilePositions() {
    String missing = workingCopy("\"a1\"").replace("order.xsd", "missing.xsd");

    assertThat(diagnostics(missing))
        .containsExactly(
            "error schema-unreadable at file ../structures/skeleton/missing.xsd 1:1",
            "error schema-unreadable at file ../structures/skeleton/missing.xsd 1:1");
  }

  @Test
  void aBodyThatIsNoJsonDocumentIs400MalformedJson() {
    assertProblem(
        compile(CORPUS_ID, "application/json", bytes("{\"formatVersion\": 1,}")),
        400,
        "malformed-json");
  }

  @Test
  void anIdWhoseDirectoryIsMissingIs404NotFound() {
    assertProblem(
        compile("compiling-nowhere/order", "application/json", corpusMapping()), 404, "not-found");
  }

  @Test
  void aBodyThatIsNotApplicationJsonIs415UnsupportedMediaType() {
    assertProblem(compile(CORPUS_ID, "text/plain", corpusMapping()), 415, "unsupported-media-type");
  }
}
