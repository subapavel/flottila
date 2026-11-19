package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.corpusMapping;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A call of the studio's own shape, {@code {"request", "files", "escaping", "reads"}}, is read
 * before its request; a call of any other shape is 400 {@code invalid-request}, and an operation
 * the studio does not have is an {@link IllegalArgumentException}.
 */
class CallTest {

  private static final String MODEL = Base64.getEncoder().encodeToString(corpusMapping());

  private static Studio.Reply answer(String op, String call) {
    return Studio.answer(op, call, Calls.UTC);
  }

  private static String detail(Studio.Reply reply) {
    assertThat(reply.status()).as("%s", reply).isEqualTo(400);
    JsonNode problem = Wire.read(reply.json().getBytes(UTF_8));
    assertThat(Wire.names(problem)).containsExactly("type", "title", "status", "detail");
    assertThat(problem.path("type").textValue())
        .isEqualTo("urn:rail8:mapping:problem:invalid-request");
    assertThat(problem.path("title").textValue()).isEqualTo("Not a request of this endpoint");
    assertThat(problem.path("status").intValue()).isEqualTo(400);
    return problem.path("detail").textValue();
  }

  @Test
  void aCallOfTheStudiosShapeIsAnswered() {
    assertThat(
            answer("canonical", "{\"request\": \"" + MODEL + "\", \"reads\": \"stored\"}").status())
        .isEqualTo(200);
    assertThat(
            answer(
                    "compile",
                    "{\"request\": \""
                        + MODEL
                        + "\", \"files\": {\"a.xsd\": \"\"}, \"escaping\": [\"../b.xsd\"]}")
                .status())
        .isEqualTo(200);
    assertThat(answer("compile", "{\"request\": \"" + MODEL + "\", \"files\": null}").status())
        .as("null files are no files")
        .isEqualTo(409);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "no JSON | {",
        "null | null",
        "no request | {}",
        "a null request | {\"request\": null}",
        "a number for the request | {\"request\": 1}",
        "an unknown member | {\"request\": \"\", \"other\": 1}",
        "a key twice | {\"request\": \"\", \"request\": \"\"}",
        "a null file | {\"request\": \"\", \"files\": {\"a.xsd\": null}}",
        "a null escaping reference | {\"request\": \"\", \"escaping\": [null]}",
      })
  void aCallOfAnotherShapeIsInvalidRequest(String what, String call) {
    assertThat(detail(answer("compile", call))).as(what).startsWith("not a call of the studio: ");
  }

  @Test
  void textThatIsNoBase64IsInvalidRequest() {
    assertThat(detail(answer("compile", "{\"request\": \"*\"}")))
        .startsWith("the request is not base64: ");
    assertThat(detail(answer("compile", "{\"request\": \"\", \"files\": {\"a.xsd\": \"*\"}}")))
        .startsWith("the file a.xsd is not base64: ");
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "canonical | {\"request\": \"\"}",
        "canonical | {\"request\": \"\", \"reads\": \"other\"}",
        "compile | {\"request\": \"\", \"reads\": \"stored\"}",
        "test | {\"request\": \"\", \"reads\": \"sent\"}",
      })
  void readsIsCanonicalsAndRequiredThere(String op, String call) {
    assertThat(detail(answer(op, call)))
        .isEqualTo("a canonical call reads \"stored\" or \"sent\", and no other call has reads");
  }

  @Test
  void theCatalogueTakesNoCall() {
    assertThat(answer("catalogue", "no call at all").status()).isEqualTo(200);
  }

  @Test
  void anOperationTheStudioDoesNotHaveIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> answer("list", "{\"request\": \"\"}"))
        .withMessage("no operation of the studio: list");
  }
}
