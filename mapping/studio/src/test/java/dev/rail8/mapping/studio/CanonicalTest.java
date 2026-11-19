package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.assertBytes;
import static dev.rail8.mapping.studio.Fixtures.bytes;
import static dev.rail8.mapping.studio.Fixtures.corpusMapping;
import static dev.rail8.mapping.studio.Fixtures.unwired;
import static dev.rail8.mapping.studio.Fixtures.workingCopy;
import static dev.rail8.mapping.studio.Wire.assertProblem;
import static dev.rail8.mapping.studio.Wire.described;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rail8.mapping.api.EngineFacade;
import dev.rail8.mapping.mapping.Loaded;
import dev.rail8.mapping.mapping.MappingModel;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code canonical}: a stored model, as a read of the mapping's file answers it, and a model the
 * browser sent, as a save answers it, each in canonical form. A stored model that is no JSON
 * document or is refused is 422 {@code model-refused}; a sent one that is no JSON document is 400
 * {@code malformed-json}, and one that stage 1 or 2 refuses is 422. Stage-3 errors never refuse a
 * model. Expected models are corpus files or the engine's canonical form, never the studio's
 * answer.
 */
class CanonicalTest {

  private static final Calls CALLS = Calls.studio();

  @Test
  void aStoredModelIsAnsweredInCanonicalForm() {
    Calls.Answer answer = CALLS.stored(corpusMapping());

    assertThat(answer.status()).as("%s", answer).isEqualTo(200);
    assertBytes(answer.bytes(), corpusMapping());

    // a stored file out of canonical form is answered in canonical form, not as its raw bytes
    Calls.Answer reordered = CALLS.stored(bytes(workingCopy("\"a1\"")));

    assertThat(reordered.status()).as("%s", reordered).isEqualTo(200);
    assertBytes(reordered.bytes(), corpusMapping());
  }

  @Test
  void saveWritesCanonicalFormAndGetReturnsItByteIdentical() throws Exception {
    byte[] body = bytes(workingCopy("\"a1\""));
    byte[] canonical = engineCanonical(body);
    assertBytes(canonical, corpusMapping());

    Calls.Answer put = CALLS.sent(body);

    assertThat(put.status()).as("%s", put).isEqualTo(200);
    assertBytes(put.bytes(), canonical);
    // the saved model, stored as it was answered, reads back as the same bytes
    Calls.Answer get = CALLS.stored(put.bytes());
    assertBytes(get.bytes(), canonical);
  }

  @Test
  void saveStage1Is422AtPointer() {
    String body = "{\"extra\": 1, " + workingCopy("\"a1\"").substring(1);

    JsonNode problem = assertProblem(CALLS.sent(bytes(body)), 422, "model-refused");

    assertThat(described(problem.path("diagnostics")))
        .containsExactly("error unknown-member at pointer /extra");
  }

  @Test
  void saveStage2Is422AtAuthoringId() {
    String body = workingCopy("\"zz\"");

    JsonNode problem = assertProblem(CALLS.sent(bytes(body)), 422, "model-refused");

    assertThat(described(problem.path("diagnostics")))
        .containsExactly("error unknown-box-reference at id t1");
  }

  @Test
  void saveKeepsStage3Errors() {
    // an unconnected target field is a stage 3 error: an unfinished mapping saves
    Calls.Answer put = CALLS.sent(bytes(workingCopy("null")));

    assertThat(put.status()).as("%s", put).isEqualTo(200);
    assertBytes(put.bytes(), unwired());
  }

  @Test
  void aSentModelThatIsNoJsonIs400MalformedJson() {
    String wired = workingCopy("\"a1\"");
    byte[] withBom = new byte[3 + bytes(wired).length];
    withBom[0] = (byte) 0xEF;
    withBom[1] = (byte) 0xBB;
    withBom[2] = (byte) 0xBF;
    System.arraycopy(bytes(wired), 0, withBom, 3, bytes(wired).length);
    Map<String, byte[]> bodies =
        Map.of(
            "trailing comma",
            bytes(wired.replace("\"formatVersion\": 1}", "\"formatVersion\": 1,}")),
            "duplicate key",
            bytes(
                wired.replace(
                    "\"formatVersion\": 1}", "\"formatVersion\": 1, \"formatVersion\": 1}")),
            "byte order mark",
            withBom);

    bodies.forEach(
        (name, body) -> {
          assertThat(new String(body, UTF_8)).as(name).isNotEqualTo(wired);
          assertProblem(CALLS.sent(body), 400, "malformed-json");
        });
  }

  /**
   * A stored file that is no JSON document is refused like a model: 422 {@code model-refused} with
   * no diagnostics, and the reader's message as the detail.
   */
  @Test
  void getOfAStoredFileThatIsNoJsonIs422WithNoDiagnosticsAndADetail() {
    Map<String, String> files =
        Map.of(
            "text", "not a JSON document",
            "truncated", "{\"formatVersion\": 1,",
            "empty", "");
    files.forEach(
        (name, content) -> {
          JsonNode problem = assertProblem(CALLS.stored(bytes(content)), 422, "model-refused");

          assertThat(problem.path("diagnostics").isArray()).as("%s: diagnostics", name).isTrue();
          assertThat(problem.path("diagnostics")).as("%s: diagnostics", name).isEmpty();
          assertThat(problem.path("detail").textValue()).as("%s: detail", name).isNotBlank();
        });
  }

  /** A save is stages 1 and 2, which accept a model without structures. */
  @Test
  void saveOfAModelWithoutStructuresIs200() {
    Calls.Answer put = CALLS.sent(bytes("{\"formatVersion\":1}"));

    assertThat(put.status()).as("%s", put).isEqualTo(200);
    assertBytes(put.bytes(), bytes(Fixtures.BARE_MAPPING));
  }

  @Test
  void saveKeepsTheDescriptionAndLayoutAndGetReturnsThem() {
    String body =
        workingCopy("\"a1\"")
            .replace(
                "\"id\": \"t1\", ",
                "\"id\": \"t1\", \"layout\": {\"target\": {\"y\": 48, \"x\": 360},"
                    + " \"boxes\": {\"a1\": {\"y\": 48, \"x\": -120}}}, ")
            .replace(
                "\"formatVersion\": 1}",
                "\"formatVersion\": 1, \"description\": \"Copies the order id\"}");
    assertThat(body).contains("\"layout\"", "\"description\"");

    Calls.Answer put = CALLS.sent(bytes(body));

    assertThat(put.status()).as("%s", put).isEqualTo(200);
    assertBytes(put.bytes(), bytes(Fixtures.STUDIO_MAPPING));
    // the saved model, stored as it was answered, reads back as the same bytes
    Calls.Answer get = CALLS.stored(put.bytes());
    assertBytes(get.bytes(), bytes(Fixtures.STUDIO_MAPPING));
  }

  /** {@code facade.save(facade.load(body))}: the engine's canonical form, the oracle. */
  private static byte[] engineCanonical(byte[] body) throws Exception {
    EngineFacade facade = new EngineFacade();
    if (facade.load(body) instanceof Loaded(MappingModel model)) {
      return facade.save(model);
    }
    throw new AssertionError("the engine refuses the working copy");
  }
}
