package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.FINGERPRINT;
import static dev.rail8.mapping.studio.Fixtures.ORDER;
import static dev.rail8.mapping.studio.Fixtures.corpusMapping;
import static dev.rail8.mapping.studio.Fixtures.workingCopy;
import static dev.rail8.mapping.studio.Wire.assertProblem;
import static dev.rail8.mapping.studio.Wire.createRequest;
import static dev.rail8.mapping.studio.Wire.described;
import static dev.rail8.mapping.studio.Wire.quoted;
import static dev.rail8.mapping.studio.Wire.testRequest;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A call without files whose request passes every check that comes before a file is read answers
 * 409 {@code files-needed} with every reference the engine reads, once each, in the order it reads
 * them; a call with files is final. A reference in {@code files} is read from it, one in {@code
 * escaping} is 400 {@code invalid-workspace-path} once the engine reaches it, one in neither is
 * {@code schema-unreadable}, and a package reference is {@code package-reference-unsupported}
 * without a file.
 */
class FilesNeededTest {

  private static final Calls CALLS = Calls.studio();

  private static final String ORDER_XSD = "../structures/skeleton/order.xsd";

  /** A direct call of {@code op} without files. */
  private static Studio.Reply withoutFiles(String op, String request) {
    String call =
        "{\"request\": "
            + quoted(Base64.getEncoder().encodeToString(request.getBytes(UTF_8)))
            + "}";
    return Studio.answer(op, call, Calls.UTC);
  }

  private static List<String> references(Studio.Reply reply) {
    assertThat(reply.status()).as("%s", reply).isEqualTo(409);
    JsonNode problem = Wire.read(reply.json().getBytes(UTF_8));
    assertThat(Wire.names(problem))
        .containsExactly("type", "title", "status", "detail", "references");
    assertThat(problem.path("type").textValue())
        .isEqualTo("urn:rail8:mapping:problem:files-needed");
    assertThat(problem.path("title").textValue()).isEqualTo("Files needed");
    assertThat(problem.path("status").intValue()).isEqualTo(409);
    List<String> references = new ArrayList<>();
    problem.path("references").forEach(reference -> references.add(reference.textValue()));
    return references;
  }

  /** A model whose source bundle is {@code source} and target bundle is {@code target}. */
  private static String sides(String source, String target) {
    String side =
        """
        {"bundle": [%s], "fingerprint": "%s", "root": {"element": "%s"}}""";
    return """
        {"formatVersion": 1, "source": %s, "target": %s}"""
        .formatted(
            side.formatted(source, FINGERPRINT, ORDER), side.formatted(target, FINGERPRINT, ORDER));
  }

  @Test
  void theReferencesAreEveryFileTheEngineReadsOnceEachInItsOrder() {
    String model = sides("\"b.xsd\", \"a.xsd\"", "\"c.xsd\", \"b.xsd\"");

    assertThat(references(withoutFiles("refresh", model)))
        .containsExactly("b.xsd", "a.xsd", "c.xsd");
    assertThat(references(withoutFiles("compile", model)))
        .containsExactly("b.xsd", "a.xsd", "c.xsd");
    assertThat(references(withoutFiles("test", testRequest(model, "<x/>"))))
        .containsExactly("b.xsd", "a.xsd", "c.xsd");
    assertThat(references(withoutFiles("create", createRequest("m", ORDER_XSD, ORDER))))
        .containsExactly(ORDER_XSD);
    assertThat(
            references(
                withoutFiles(
                    "structure",
                    "{\"base\": \"\", \"bundle\": [\"z.xsd\", \"y.xsd\"], \"root\": {\"element\": "
                        + quoted(ORDER)
                        + "}}")))
        .containsExactly("z.xsd", "y.xsd");
  }

  @Test
  void theSecondCallReadsTheFilesSentAndIsFinal() {
    Calls.Answer answer = CALLS.post("refresh", corpusMapping(), Workspace.mappings());

    assertThat(answer.rounds()).as("%s", answer).isEqualTo(2);
    assertThat(answer.needed()).containsExactly(ORDER_XSD);
    assertThat(answer.status()).as("%s", answer).isEqualTo(200);
    assertThat(described(answer.body().path("diagnostics"))).isEmpty();
  }

  @Test
  void aReferenceInNeitherFilesNorEscapingIsSchemaUnreadable() {
    Calls.Answer answer = CALLS.post("refresh", corpusMapping(), Workspace.empty());

    assertThat(answer.rounds()).as("%s", answer).isEqualTo(2);
    assertThat(answer.status()).as("%s", answer).isEqualTo(200);
    assertThat(described(answer.body().path("diagnostics")))
        .containsExactly(
            "error schema-unreadable at file " + ORDER_XSD + " 1:1",
            "error schema-unreadable at file " + ORDER_XSD + " 1:1");
  }

  @Test
  void aReferenceInEscapingIsInvalidWorkspacePath() {
    Calls.Answer answer =
        CALLS.post(
            "structure",
            "{\"base\": \"\", \"bundle\": [\"../../outside/evil.xsd\"], \"root\": {\"element\": "
                + quoted(ORDER)
                + "}}",
            Workspace.mappings());

    assertThat(answer.rounds()).as("%s", answer).isEqualTo(2);
    JsonNode problem = assertProblem(answer, 400, "invalid-workspace-path");
    assertThat(problem.path("title").textValue()).isEqualTo("Not a workspace path");
    assertThat(problem.path("detail").textValue())
        .isEqualTo("not a workspace path: \"../../outside/evil.xsd\"");
  }

  @Test
  void aPackageReferenceNeedsNoFileAndIsPackageReferenceUnsupported() {
    String bundle = "\"shared@1.2.0:/xsd/o.xsd\", \"" + ORDER_XSD + "\"";

    assertThat(references(withoutFiles("compile", sides(bundle, quoted(ORDER_XSD)))))
        .containsExactly(ORDER_XSD);

    Calls.Answer only =
        CALLS.post(
            "structure",
            "{\"base\": \"\", \"bundle\": [\"shared@1.2.0:/xsd/o.xsd\"], \"root\": {\"element\": "
                + quoted(ORDER)
                + "}}",
            Workspace.empty());

    assertThat(only.rounds()).as("a package reference alone needs no file: %s", only).isEqualTo(1);
    assertThat(described(only.body().path("diagnostics")))
        .containsExactly("error package-reference-unsupported at file shared@1.2.0:/xsd/o.xsd 1:1");
  }

  @Test
  void aCallWithFilesIsFinalEvenWhenAFileIsMissing() {
    String call =
        "{\"request\": "
            + quoted(Base64.getEncoder().encodeToString(corpusMapping()))
            + ", \"files\": {}}";

    Studio.Reply reply = Studio.answer("refresh", call, Calls.UTC);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(described(Wire.read(reply.json().getBytes(UTF_8)).path("diagnostics")))
        .containsExactly(
            "error schema-unreadable at file " + ORDER_XSD + " 1:1",
            "error schema-unreadable at file " + ORDER_XSD + " 1:1");
  }

  /** A refusal that comes before any file is read is the answer of the first call. */
  @Test
  void aRequestRefusedBeforeAnyFileIsReadAnswersAtOnce() {
    String badRoot = "{\"element\": \"Order\", \"json\": \"$\"}";
    String side =
        "{\"bundle\": [" + quoted(ORDER_XSD) + "], \"root\": {\"element\": " + quoted(ORDER) + "}}";
    String badSide = "{\"bundle\": [" + quoted(ORDER_XSD) + "], \"root\": " + badRoot + "}";

    // the target's root selector is read before the source's bundle
    Calls.Answer create =
        CALLS.post(
            "create",
            "{\"id\": \"m\", \"source\": " + side + ", \"target\": " + badSide + "}",
            Workspace.mappings());
    assertThat(create.rounds()).as("%s", create).isEqualTo(1);
    assertProblem(create, 400, "invalid-request");

    Calls.Answer malformed = CALLS.post("test", "{", Workspace.mappings());
    assertThat(malformed.rounds()).as("%s", malformed).isEqualTo(1);
    assertProblem(malformed, 400, "malformed-json");

    // stage 1 refuses the model before its bundles are read
    Calls.Answer refused = CALLS.post("compile", "{\"formatVersion\": 2}", Workspace.mappings());
    assertThat(refused.rounds()).as("%s", refused).isEqualTo(1);
    assertThat(refused.status()).isEqualTo(200);
    assertThat(described(refused.body().path("diagnostics"))).isNotEmpty();

    Calls.Answer canonical = CALLS.sent(corpusMapping());
    assertThat(canonical.rounds()).as("%s", canonical).isEqualTo(1);
    assertThat(canonical.status()).isEqualTo(200);
  }

  /** A refusal that comes once the files are read is the answer of the second call. */
  @Test
  void aRequestRefusedOnceItsFilesAreReadAnswersAfterThem() {
    Calls.Answer answer =
        CALLS.post("test", testRequest(workingCopy("\"a1\""), "<src:Order"), Workspace.mappings());

    assertThat(answer.rounds()).as("%s", answer).isEqualTo(2);
    assertProblem(answer, 400, "malformed-input");
  }
}
