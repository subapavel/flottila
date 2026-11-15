package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.AtAuthoringId;
import io.flottila.mapping.mapping.AtFilePosition;
import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Location;
import io.flottila.mapping.mapping.MalformedJsonException;
import io.flottila.mapping.mapping.Severity;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link EngineFacade#refresh} (#99; ADR-0009 as amended on 2026-11-05, ADR-0015): the model comes
 * back in canonical form with both fingerprints re-derived and nothing else changed, with its
 * orphaned references and every other stage-3 diagnostic. A side whose bundle fails to load keeps
 * its fingerprint and gives its load diagnostic. The structures are the skeleton's {@code
 * order.xsd} and a copy of it that renames {@code OrderId}, in a directory of the test's own.
 */
class EngineFacadeRefreshTest {

  private static final String NS = "urn:flottila:corpus:skeleton";

  /** The real fingerprint of {@code order.xsd} under {@code Order} ({@code FingerprintPinTest}). */
  private static final String FINGERPRINT =
      "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d";

  /** A well-formed fingerprint that no structure gives. */
  private static final String STALE = "sha256:" + "0".repeat(64);

  /**
   * A canonical model from the bundle file {@code %1$s} with fingerprint {@code %2$s} to {@code
   * order.xsd} with fingerprint {@code %3$s}, whose one target field mapping, {@code OrderId <- a1}
   * at t1, has the result {@code %4$s}.
   */
  private static final String MODEL =
      """
      {
        "formatVersion": 1,
        "source": {
          "bundle": [
            "%1$s"
          ],
          "fingerprint": "%2$s",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        },
        "target": {
          "bundle": [
            "order.xsd"
          ],
          "fingerprint": "%3$s",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        },
        "targetFieldMappings": {
          "/Q{urn:flottila:corpus:skeleton}Order/OrderId": {
            "boxes": {
              "a1": {
                "field": "/Q{urn:flottila:corpus:skeleton}Order/OrderId",
                "kind": "source"
              }
            },
            "id": "t1",
            "result": %4$s
          }
        }
      }
      """;

  private static byte[] model(String source, String sourceFingerprint, String targetFingerprint) {
    return model(source, sourceFingerprint, targetFingerprint, "\"a1\"");
  }

  private static byte[] model(
      String source, String sourceFingerprint, String targetFingerprint, String result) {
    return MODEL.formatted(source, sourceFingerprint, targetFingerprint, result).getBytes(UTF_8);
  }

  @TempDir Path base;

  private ReferenceResolver resolver;

  private final EngineFacade facade = new EngineFacade();

  @BeforeEach
  void layOutTheBundles() throws IOException {
    String order = Files.readString(CorpusStructures.directory("skeleton").resolve("order.xsd"));
    String renamed = order.replace("name=\"OrderId\"", "name=\"OrderNo\"");
    assertThat(renamed).as("order.xsd has OrderId to rename").isNotEqualTo(order);
    Files.writeString(base.resolve("order.xsd"), order);
    Files.writeString(base.resolve("renamed.xsd"), renamed);
    resolver = DirectoryResolver.over(base);
  }

  private static RefreshResult.Refreshed refreshed(RefreshResult result) {
    if (result instanceof RefreshResult.Refreshed refreshed) {
      assertThat(refreshed.diagnostics()).allSatisfy(d -> assertThat(d.message()).isNotBlank());
      return refreshed;
    }
    throw new AssertionError("expected Refreshed, got " + result);
  }

  /** Severity, code and location; never the message (ADR-0015). */
  private static List<String> described(List<Diagnostic> diagnostics) {
    return diagnostics.stream()
        .map(d -> d.severity() + " " + d.code() + " " + d.location())
        .toList();
  }

  /** An error as {@link #described} shows it. */
  private static String error(String code, Location location) {
    return described(List.of(new Diagnostic(Severity.ERROR, code, location, "ignored"))).getFirst();
  }

  private static AtAuthoringId atId(String id) {
    return new AtAuthoringId(new AuthoringId(id));
  }

  private static void assertBytes(byte[] actual, byte[] expected) {
    assertThat(new String(actual, UTF_8)).isEqualTo(new String(expected, UTF_8));
  }

  @Test
  void aRenamedMappedFieldIsAnOrphanedReferenceAtItsBoxUnderTheNewFingerprint()
      throws MalformedJsonException {
    String renamed =
        ((StructureResult.Loaded)
                Structures.load(
                    resolver,
                    new SchemaBundle(List.of("renamed.xsd")),
                    new ElementRoot("Q{" + NS + "}Order")))
            .structure()
            .fingerprint();
    assertThat(renamed).isNotEqualTo(FINGERPRINT);

    RefreshResult.Refreshed result =
        refreshed(facade.refresh(model("renamed.xsd", FINGERPRINT, FINGERPRINT), resolver));

    assertBytes(result.model(), model("renamed.xsd", renamed, FINGERPRINT));
    assertThat(described(result.diagnostics()))
        .containsExactly(error("orphaned-reference", atId("a1")));
  }

  @Test
  void anUnchangedModelComesBackAsItIsWithNoDiagnostics() throws MalformedJsonException {
    byte[] unchanged = model("order.xsd", FINGERPRINT, FINGERPRINT);

    RefreshResult.Refreshed result = refreshed(facade.refresh(unchanged, resolver));

    assertBytes(result.model(), unchanged);
    assertThat(result.diagnostics()).isEmpty();
  }

  /**
   * Both stale fingerprints are re-derived, so the copy has no {@code fingerprint-mismatch}, and
   * its other stage-3 errors are returned: here the unconnected target field mapping.
   */
  @Test
  void staleFingerprintsAreRederivedAndTheOtherStage3DiagnosticsReturned()
      throws MalformedJsonException {
    RefreshResult.Refreshed result =
        refreshed(facade.refresh(model("order.xsd", STALE, STALE, "null"), resolver));

    assertBytes(result.model(), model("order.xsd", FINGERPRINT, FINGERPRINT, "null"));
    assertThat(described(result.diagnostics()))
        .containsExactly(error("unconnected-target-field", atId("t1")));
  }

  /**
   * A side whose bundle file is missing keeps its stale fingerprint and gives its load error; the
   * other side's is re-derived. Stage 3 needs both structures, so it reports nothing.
   */
  @Test
  void aSideThatFailsToLoadKeepsItsFingerprintAndGivesItsLoadError()
      throws MalformedJsonException, IOException {
    RefreshResult.Refreshed source =
        refreshed(facade.refresh(model("missing.xsd", STALE, STALE), resolver));

    assertBytes(source.model(), model("missing.xsd", STALE, FINGERPRINT));
    assertThat(described(source.diagnostics()))
        .containsExactly(error("schema-unreadable", new AtFilePosition("missing.xsd", 1, 1)));

    // the target side: its file is gone, and the source's stale fingerprint is re-derived
    Files.delete(base.resolve("order.xsd"));
    Files.writeString(
        base.resolve("source.xsd"),
        Files.readString(CorpusStructures.directory("skeleton").resolve("order.xsd")));
    RefreshResult.Refreshed target =
        refreshed(facade.refresh(model("source.xsd", STALE, STALE), resolver));

    assertBytes(target.model(), model("source.xsd", FINGERPRINT, STALE));
    assertThat(described(target.diagnostics()))
        .containsExactly(error("schema-unreadable", new AtFilePosition("order.xsd", 1, 1)));
  }

  /**
   * A side whose bundle has two load errors (#93, an assertion and a {@code form} attribute on a
   * global element; #94 opened the choice and #95 the abstract element this fixture used before)
   * keeps its fingerprint and gives both, in the order the structure load gives them.
   */
  @Test
  void aSideWithTwoLoadErrorsGivesBoth() throws MalformedJsonException, IOException {
    Files.writeString(
        base.resolve("two-errors.xsd"),
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="%s">
          <xs:element name="Order">
            <xs:complexType>
              <xs:assert>
                <xs:element name="OrderId" type="xs:string"/>
              </xs:assert>
            </xs:complexType>
          </xs:element>
          <xs:element name="Other" form="qualified" type="xs:string"/>
        </xs:schema>
        """
            .formatted(NS));
    StructureResult load =
        Structures.load(
            resolver,
            new SchemaBundle(List.of("two-errors.xsd")),
            new ElementRoot("Q{" + NS + "}Order"));
    if (!(load instanceof StructureResult.Refused(List<Diagnostic> loadErrors))) {
      throw new AssertionError("expected Refused, got " + load);
    }
    assertThat(loadErrors)
        .extracting(Diagnostic::code)
        .containsExactly("schema-construct-unsupported", "schema-construct-unsupported");

    RefreshResult.Refreshed result =
        refreshed(facade.refresh(model("two-errors.xsd", STALE, STALE), resolver));

    assertBytes(result.model(), model("two-errors.xsd", STALE, FINGERPRINT));
    assertThat(described(result.diagnostics())).containsExactlyElementsOf(described(loadErrors));
  }

  @Test
  void aModelThatStage1Or2RefusesIsRefusedWithItsDiagnostics() throws MalformedJsonException {
    byte[] newer =
        new String(model("order.xsd", FINGERPRINT, FINGERPRINT), UTF_8)
            .replace("\"formatVersion\": 1", "\"formatVersion\": 2")
            .getBytes(UTF_8);

    RefreshResult result = facade.refresh(newer, resolver);

    assertThat(result).isInstanceOf(RefreshResult.Refused.class);
    assertThat(described(((RefreshResult.Refused) result).diagnostics()))
        .containsExactly(error("format-version-unsupported", new AtPointer("/formatVersion")));
  }

  /**
   * {@link #MODEL} with a description and a layout (ADR-0029), canonical, from {@code order.xsd}
   * with fingerprint {@code %1$s} to {@code order.xsd} with fingerprint {@code %2$s}.
   */
  private static final String STUDIO_MODEL =
      """
      {
        "description": "Copies the order id; laid out in the studio",
        "formatVersion": 1,
        "source": {
          "bundle": [
            "order.xsd"
          ],
          "fingerprint": "%1$s",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        },
        "target": {
          "bundle": [
            "order.xsd"
          ],
          "fingerprint": "%2$s",
          "root": {
            "element": "Q{urn:flottila:corpus:skeleton}Order"
          }
        },
        "targetFieldMappings": {
          "/Q{urn:flottila:corpus:skeleton}Order/OrderId": {
            "boxes": {
              "a1": {
                "field": "/Q{urn:flottila:corpus:skeleton}Order/OrderId",
                "kind": "source"
              }
            },
            "id": "t1",
            "layout": {
              "boxes": {
                "a1": {
                  "x": -120,
                  "y": 48
                }
              },
              "target": {
                "x": 360,
                "y": 48
              }
            },
            "result": "a1"
          }
        }
      }
      """;

  /** Refresh re-derives the fingerprints and keeps the description and the layout as they are. */
  @Test
  void refreshKeepsDescriptionAndLayout() throws MalformedJsonException {
    byte[] stale = STUDIO_MODEL.formatted(STALE, STALE).getBytes(UTF_8);

    RefreshResult.Refreshed result = refreshed(facade.refresh(stale, resolver));

    assertBytes(result.model(), STUDIO_MODEL.formatted(FINGERPRINT, FINGERPRINT).getBytes(UTF_8));
    assertThat(result.diagnostics()).isEmpty();
  }

  /** A working copy with its keys out of order on one line comes back in canonical form. */
  @Test
  void theModelComesBackInCanonicalForm() throws MalformedJsonException {
    String side =
        """
        {"root": {"element": "Q{%s}Order"}, "fingerprint": "%s", "bundle": ["order.xsd"]}"""
            .formatted(NS, STALE);
    String workingCopy =
        """
        {"targetFieldMappings": {"/Q{%1$s}Order/OrderId": {"result": "a1", "id": "t1", \
        "boxes": {"a1": {"kind": "source", "field": "/Q{%1$s}Order/OrderId"}}}}, \
        "target": %2$s, "source": %2$s, "formatVersion": 1}
        """
            .formatted(NS, side);

    RefreshResult.Refreshed result =
        refreshed(facade.refresh(workingCopy.getBytes(UTF_8), resolver));

    assertBytes(result.model(), model("order.xsd", FINGERPRINT, FINGERPRINT));
    assertThat(result.diagnostics()).isEmpty();
  }
}
