package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.FINGERPRINT;
import static dev.rail8.mapping.studio.Fixtures.bytes;
import static dev.rail8.mapping.studio.Fixtures.corpusMapping;
import static dev.rail8.mapping.studio.Fixtures.workingCopy;
import static dev.rail8.mapping.studio.Wire.assertProblem;
import static dev.rail8.mapping.studio.Wire.described;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rail8.mapping.api.EngineFacade;
import dev.rail8.mapping.mapping.LoadResult;
import dev.rail8.mapping.mapping.Loaded;
import dev.rail8.mapping.mapping.MalformedJsonException;
import dev.rail8.mapping.mapping.MappingModel;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * {@code refresh}: the working copy in the request comes back with both fingerprints re-derived and
 * nothing else changed, with its orphaned references and every other stage-3 diagnostic. A side
 * whose bundle fails to load keeps its fingerprint and gives its load diagnostics, never a 4xx.
 * Nothing is saved.
 *
 * <p>An answer's {@code model} is compared with its member order ({@link JsonNode#toString} keeps
 * it), against the canonical corpus mapping or a copy of it with values replaced in place.
 */
class RefreshTest {

  private static final Calls CALLS = Calls.studio();

  /** The corpus mapping's bundle reference, the same on both sides. */
  private static final String SKELETON = "../structures/skeleton/order.xsd";

  /** A well-formed fingerprint that no structure gives. */
  private static final String STALE = "sha256:" + "0".repeat(64);

  /** A refresh of {@code body}, its bundle files from {@code workspace}. */
  private static Calls.Answer refresh(Workspace workspace, byte[] body) {
    return CALLS.post("refresh", body, workspace);
  }

  /** A refresh of {@code body} by a mapping in {@code corpus/mappings/}. */
  private static Calls.Answer refresh(byte[] body) {
    return refresh(Workspace.mappings(), body);
  }

  /**
   * The corpus mapping with the source side's bundle reference and each side's fingerprint replaced
   * in place. The corpus mapping is canonical and each side has one bundle file, so the copy is
   * canonical too.
   */
  private static String corpusWith(
      String sourceBundle, String sourceFingerprint, String targetFingerprint) {
    String corpus = new String(corpusMapping(), UTF_8);
    int target = corpus.indexOf("\"target\"");
    return corpus
            .substring(0, target)
            .replace(SKELETON, sourceBundle)
            .replace(FINGERPRINT, sourceFingerprint)
        + corpus.substring(target).replace(FINGERPRINT, targetFingerprint);
  }

  /** The member-ordered text of a JSON value. */
  private static String ordered(JsonNode value) {
    return value.toString();
  }

  private static String ordered(String json) {
    return ordered(Wire.read(bytes(json)));
  }

  /** The 200 answer's body, which is a refresh result and never a problem. */
  private static JsonNode refreshed(Calls.Answer answer) {
    assertThat(answer.status()).as("%s", answer).isEqualTo(200);
    return answer.body();
  }

  /**
   * The fingerprint of {@code order.xsd} with {@code OrderId} renamed {@code OrderNo}, under {@code
   * Order}: SHA-256 over this canonical text written by hand, the method that reproduces {@link
   * Fixtures#FINGERPRINT} (28ec3b…) from the same text with {@code OrderId}. Each line ends in a
   * line feed.
   *
   * <pre>
   * root Q{urn:rail8:corpus:skeleton}Order 1..1 - /Q{urn:rail8:corpus:skeleton}Order
   * type /Q{urn:rail8:corpus:skeleton}Order
   * field @channel 0..1 xs:string -
   * field OrderNo 1..1 xs:string -
   * field Quantity 0..1 xs:integer -
   * field Amount 0..1 xs:decimal -
   * field Urgent 0..1 xs:boolean -
   * field DeliveryDate 0..1 xs:date -
   * field PickupTime 0..1 xs:time -
   * field CreatedAt 0..1 xs:dateTime -
   * field Note 0..unbounded xs:string -
   * </pre>
   */
  private static final String RENAMED_FINGERPRINT =
      "sha256:b467d743f6ae3a5dc61ae97a9547cfaa06a65baa045fe73283a04319e823e92b";

  /**
   * The source schema renames the mapped field {@code OrderId} to {@code OrderNo}: the source side
   * gets the new structure's fingerprint, the target side keeps the one it already had, and the box
   * that reads {@code OrderId} is {@code orphaned-reference}.
   */
  @Test
  void aSchemaThatRenamedAMappedFieldGivesTheNewFingerprintAndAnOrphanedReferenceAtTheBox()
      throws IOException {
    String order =
        Files.readString(Fixtures.repositoryRoot().resolve("corpus/structures/skeleton/order.xsd"));
    String renamed = order.replace("name=\"OrderId\"", "name=\"OrderNo\"");
    assertThat(renamed).as("order.xsd has OrderId to rename").isNotEqualTo(order);

    JsonNode result =
        refreshed(
            refresh(
                Workspace.mappings().with("renamed.xsd", renamed),
                bytes(corpusWith("renamed.xsd", FINGERPRINT, FINGERPRINT))));

    assertThat(ordered(result.path("model")))
        .isEqualTo(ordered(corpusWith("renamed.xsd", RENAMED_FINGERPRINT, FINGERPRINT)));
    assertThat(described(result.path("diagnostics")))
        .containsExactly("error orphaned-reference at id a1");
  }

  @Test
  void anUnchangedMappingReturnsTheSameModelAndNoDiagnostics() {
    assertThat(corpusWith(SKELETON, FINGERPRINT, FINGERPRINT))
        .as("witness: corpusWith replaces nothing else")
        .isEqualTo(new String(corpusMapping(), UTF_8));

    JsonNode result = refreshed(refresh(corpusMapping()));

    assertThat(ordered(result.path("model"))).isEqualTo(ordered(Wire.read(corpusMapping())));
    assertThat(described(result.path("diagnostics"))).isEmpty();
  }

  /**
   * The source bundle is not well-formed: the source keeps its stale fingerprint, the target's
   * stale one is re-derived, and the answer is the load error at its position in the file, with no
   * stage-3 diagnostic, as stage 3 needs both structures.
   */
  @Test
  void aSideThatFailsToLoadKeepsItsFingerprintAndReturnsItsFilePositionDiagnostics()
      throws IOException {
    byte[] malformed =
        Files.readAllBytes(
            Fixtures.repositoryRoot()
                .resolve("corpus/structures/skeleton/test-fixtures/malformed.xsd"));

    JsonNode result =
        refreshed(
            refresh(
                Workspace.mappings().with("malformed.xsd", malformed),
                bytes(corpusWith("malformed.xsd", STALE, STALE))));

    assertThat(ordered(result.path("model")))
        .isEqualTo(ordered(corpusWith("malformed.xsd", STALE, FINGERPRINT)));
    assertThat(described(result.path("diagnostics")))
        .containsExactly("error schema-not-well-formed at file malformed.xsd 7:7");
  }

  /** A package reference is a load error of its side, a 200 like any other, never a 4xx. */
  @Test
  void aPackageReferenceGivesPackageReferenceUnsupported() {
    String reference = "shared@1.2.0:/xsd/o.xsd";

    JsonNode result = refreshed(refresh(bytes(corpusWith(reference, STALE, FINGERPRINT))));

    assertThat(ordered(result.path("model")))
        .isEqualTo(ordered(corpusWith(reference, STALE, FINGERPRINT)));
    assertThat(described(result.path("diagnostics")))
        .containsExactly("error package-reference-unsupported at file " + reference + " 1:1");
  }

  /**
   * The working copy's keys are out of order and on one line; the answer is the model in canonical
   * form, which stages 1 and 2 accept and which the canonical writer leaves as it is.
   */
  @Test
  void theResponseModelPassesStages1And2AndIsCanonical() throws MalformedJsonException {
    EngineFacade facade = new EngineFacade();

    JsonNode model = refreshed(refresh(bytes(workingCopy("\"a1\"")))).path("model");

    LoadResult loaded = facade.load(bytes(ordered(model)));
    assertThat(loaded).as("stages 1 and 2").isInstanceOf(Loaded.class);
    MappingModel accepted = ((Loaded) loaded).model();
    assertThat(ordered(model)).isEqualTo(ordered(Wire.read(facade.save(accepted))));
    assertThat(ordered(model)).isEqualTo(ordered(Wire.read(corpusMapping())));
  }

  /**
   * A working copy that stage 1 or 2 refuses has no model to answer with: 422 {@code model-refused}
   * with its diagnostics, as a save answers it.
   */
  @Test
  void aWorkingCopyThatStage1Or2RefusesIs422ModelRefused() {
    String newer =
        new String(corpusMapping(), UTF_8).replace("\"formatVersion\": 1", "\"formatVersion\": 2");

    JsonNode problem = assertProblem(refresh(bytes(newer)), 422, "model-refused");

    assertThat(described(problem.path("diagnostics")))
        .containsExactly("error format-version-unsupported at pointer /formatVersion");
  }

  @Test
  void aBodyThatIsNoJsonDocumentIs400MalformedJson() {
    assertProblem(refresh(bytes("{\"formatVersion\": 1,}")), 400, "malformed-json");
  }
}
