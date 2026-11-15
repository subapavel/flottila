package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.flottila.mapping.mapping.AtFilePosition;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Loaded;
import io.flottila.mapping.mapping.Severity;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.Occurrence;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine facade delegates structure loading to the reader (spec #21, seam 1). The reader's
 * behaviour is tested in {@code engine/structure-import}.
 */
class StructuresTest {

  @Test
  void a_flat_XSD_loads_through_the_facade(@TempDir Path base) throws Exception {
    Files.writeString(
        base.resolve("order.xsd"),
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:a">
          <xs:element name="Order">
            <xs:complexType><xs:attribute name="id" type="xs:string"/></xs:complexType>
          </xs:element>
        </xs:schema>
        """);

    StructureResult result =
        Structures.load(
            DirectoryResolver.over(base),
            new SchemaBundle(List.of("order.xsd")),
            new ElementRoot("Q{urn:a}Order"));

    assertThat(result).isInstanceOf(StructureResult.Loaded.class);
    StructureResult.Loaded loaded = (StructureResult.Loaded) result;
    assertThat(loaded.structure().rootPath()).hasToString("/Q{urn:a}Order");
  }

  /** #62: the observation kit's nested structures, which #50 maps, load through the facade. */
  @Test
  void the_observation_kit_structures_load_through_the_facade_with_one_fingerprint() {
    Path observations = CorpusStructures.directory("observations");
    Map<String, String> roots = new LinkedHashMap<>();
    roots.put("src-orders.xsd", "Orders");
    roots.put("tgt-list.xsd", "Out");
    Map<String, List<String>> repeatingChildren = new LinkedHashMap<>();

    for (Map.Entry<String, String> file : roots.entrySet()) {
      SchemaBundle bundle = new SchemaBundle(List.of(file.getKey()));
      ElementRoot root = new ElementRoot(file.getValue());
      Structure first = loaded(Structures.load(DirectoryResolver.over(observations), bundle, root));
      Structure second =
          loaded(Structures.load(DirectoryResolver.over(observations), bundle, root));

      assertThat(second.fingerprint()).as(file.getKey()).isEqualTo(first.fingerprint());
      // the first repeating structure field below the root, and its children
      FieldPath repeating = first.children(first.rootPath()).getFirst();
      assertThat(first.field(repeating).orElseThrow().occurrence())
          .isEqualTo(new Occurrence(0, Occurrence.UNBOUNDED));
      repeatingChildren.put(
          repeating.toString(),
          first.children(repeating).stream().map(FieldPath::toString).toList());
    }

    assertThat(repeatingChildren)
        .containsExactly(
            entry(
                "/Orders/Order",
                List.of(
                    "/Orders/Order/Id",
                    "/Orders/Order/Tag",
                    "/Orders/Order/Line",
                    "/Orders/Order/Memo")),
            entry("/Out/Item", List.of("/Out/Item/V", "/Out/Item/W")));
  }

  private static Structure loaded(StructureResult result) {
    if (result instanceof StructureResult.Loaded(Structure structure)) {
      return structure;
    }
    throw new AssertionError("expected Loaded, got " + result);
  }

  @Test
  void a_load_error_is_a_result_with_its_file_position(@TempDir Path base) throws Exception {
    Files.writeString(base.resolve("broken.xsd"), "<xs:schema\n  <");

    StructureResult result =
        Structures.load(
            DirectoryResolver.over(base),
            new SchemaBundle(List.of("broken.xsd")),
            new ElementRoot("Order"));

    if (!(result instanceof StructureResult.Refused(List<Diagnostic> diagnostics))) {
      throw new AssertionError("expected Refused, got " + result);
    }
    assertThat(diagnostics).hasSize(1);
    Diagnostic diagnostic = diagnostics.getFirst();
    assertThat(diagnostic.severity()).isEqualTo(Severity.ERROR);
    assertThat(diagnostic.code()).isEqualTo("schema-not-well-formed");
    assertThat(diagnostic.message()).isNotBlank();
    // the attribute list of <xs:schema breaks at the "<" on line 2
    assertThat(diagnostic.location()).isEqualTo(new AtFilePosition("broken.xsd", 2, 3));
  }

  // --- #90: every bundle file is read through the host's reference resolver (ADR-0014
  // "Reference resolver shape"); the engine opens no file.

  private static final ElementRoot ORDER = new ElementRoot("Q{urn:flottila:corpus:skeleton}Order");

  /**
   * The fingerprint of the skeleton's {@code order.xsd} under {@link #ORDER}: the one stored in
   * {@code corpus/mappings/order-id-copy.mapping.json}, and {@code EngineFacadeTest.FINGERPRINT}.
   */
  private static final String SKELETON_FINGERPRINT =
      "sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d";

  /** The bytes of {@code corpus/structures/skeleton/order.xsd}. */
  private static byte[] skeletonOrder() throws Exception {
    return Files.readAllBytes(CorpusStructures.directory("skeleton").resolve("order.xsd"));
  }

  private static SchemaBundle bundle(String reference) {
    return new SchemaBundle(List.of(reference));
  }

  /** The one diagnostic of a refused load. */
  private static Diagnostic refused(StructureResult result) {
    List<Diagnostic> diagnostics = refusedAll(result);
    assertThat(diagnostics).hasSize(1);
    return diagnostics.getFirst();
  }

  private static List<Diagnostic> refusedAll(StructureResult result) {
    if (result instanceof StructureResult.Refused(List<Diagnostic> diagnostics)) {
      return diagnostics;
    }
    throw new AssertionError("expected Refused, got " + result);
  }

  /** The resolver gets the reference as the bundle spells it, once, and its bytes are read. */
  @Test
  void eachBundleFileIsResolvedOnceByItsReference() throws Exception {
    RecordingResolver resolver = new RecordingResolver().file("../xsd/order.xsd", skeletonOrder());

    StructureResult result = Structures.load(resolver, bundle("../xsd/order.xsd"), ORDER);

    assertThat(loaded(result).rootPath()).hasToString("/Q{urn:flottila:corpus:skeleton}Order");
    assertThat(resolver.calls()).containsExactly("../xsd/order.xsd");
  }

  @Test
  void skeletonFingerprintIsUnchangedThroughTheResolver() throws Exception {
    RecordingResolver resolver = new RecordingResolver().file("order.xsd", skeletonOrder());

    Structure structure = loaded(Structures.load(resolver, bundle("order.xsd"), ORDER));

    assertThat(structure.fingerprint()).isEqualTo(SKELETON_FINGERPRINT);
  }

  /**
   * A whole-file refusal sits at line 1, column 1 of the reference; the message is the engine's.
   */
  @Test
  void unreadableIsSchemaUnreadableAtTheBundleName() {
    StructureResult result = Structures.load(new RecordingResolver(), bundle("missing.xsd"), ORDER);

    assertThat(refused(result))
        .isEqualTo(
            new Diagnostic(
                Severity.ERROR,
                "schema-unreadable",
                new AtFilePosition("missing.xsd", 1, 1),
                "cannot read the schema file \"missing.xsd\""));
  }

  @Test
  void packageReferenceIsPackageReferenceUnsupported() {
    String reference = "shared@1.2.0:/xsd/o.xsd";
    RecordingResolver resolver = new RecordingResolver().packageReference(reference);

    StructureResult result = Structures.load(resolver, bundle(reference), ORDER);

    assertThat(refused(result))
        .isEqualTo(
            new Diagnostic(
                Severity.ERROR,
                "package-reference-unsupported",
                new AtFilePosition(reference, 1, 1),
                "package references need a host resolver: \"shared@1.2.0:/xsd/o.xsd\""));
    assertThat(resolver.calls()).containsExactly(reference);
  }

  /**
   * An unchecked exception from the resolver is the host's (the service's workspace escape): it
   * passes through {@code load}, {@code compile} and {@code export} as the same instance, never as
   * a diagnostic.
   */
  @Test
  void anUncheckedResolverExceptionPropagatesUnchanged() throws Exception {
    IllegalStateException hosts = new IllegalStateException("the host refuses this reference");
    ReferenceResolver resolver =
        reference -> {
          throw hosts;
        };
    byte[] model =
        """
        {"formatVersion": 1,
         "source": {"bundle": ["order.xsd"], "fingerprint": "%1$s", "root": {"element": "%2$s"}},
         "target": {"bundle": ["order.xsd"], "fingerprint": "%1$s", "root": {"element": "%2$s"}}}
        """
            .formatted(SKELETON_FINGERPRINT, ORDER.element())
            .getBytes(UTF_8);
    EngineFacade facade = new EngineFacade();
    // the witness: the model loads, so compile and export go on to read its structures
    assertThat(facade.load(model)).isInstanceOf(Loaded.class);

    assertThatThrownBy(() -> Structures.load(resolver, bundle("order.xsd"), ORDER))
        .as("load")
        .isSameAs(hosts);
    assertThatThrownBy(() -> facade.compile(model, resolver, CompileOptions.DEPLOY))
        .as("compile")
        .isSameAs(hosts);
    assertThatThrownBy(() -> facade.export(model, resolver)).as("export").isSameAs(hosts);
  }

  // --- #97: a JSON Schema bundle under the JSON root (ADR-0008); the reader's rules are tested in
  // engine/structure-import (JsonSchemaReaderTest).

  private static final Path JSON_BUNDLE =
      CorpusStructures.directory("json-schema").resolve("bundle");

  @Test
  void aJsonSchemaBundleLoadsThroughTheFacadeReadingEachListedFileOnce() throws Exception {
    RecordingResolver resolver =
        new RecordingResolver()
            .file("order.schema.json", Files.readAllBytes(JSON_BUNDLE.resolve("order.schema.json")))
            .file(
                "defs/address.schema.json",
                Files.readAllBytes(JSON_BUNDLE.resolve("defs/address.schema.json")));
    SchemaBundle bundle =
        new SchemaBundle(List.of("order.schema.json", "defs/address.schema.json"));

    Structure structure = loaded(Structures.load(resolver, bundle, new JsonRoot()));

    assertThat(structure.rootPath()).hasToString("/$");
    assertThat(structure.children(structure.rootPath()))
        .extracting(FieldPath::toString)
        .containsExactly("/$/id", "/$/shipTo", "/$/country");
    assertThat(resolver.calls()).containsExactly("order.schema.json", "defs/address.schema.json");
  }

  /** Each $ref outside the bundle is a load error at that $ref, in document order (ADR-0008). */
  @Test
  void aJsonRefOutsideTheBundleIsALoadErrorAtTheRef() throws Exception {
    RecordingResolver resolver =
        new RecordingResolver()
            .file(
                "order.schema.json", Files.readAllBytes(JSON_BUNDLE.resolve("order.schema.json")));

    List<Diagnostic> diagnostics =
        refusedAll(Structures.load(resolver, bundle("order.schema.json"), new JsonRoot()));

    assertThat(diagnostics)
        .extracting(Diagnostic::code)
        .containsOnly("schema-reference-outside-bundle");
    assertThat(diagnostics)
        .extracting(Diagnostic::location)
        .containsExactly(
            new AtFilePosition("order.schema.json", 7, 16),
            new AtFilePosition("order.schema.json", 8, 17));
    assertThat(diagnostics.getFirst().message()).contains("\"defs/address.schema.json\"");
    assertThat(resolver.calls()).containsExactly("order.schema.json");
  }

  /**
   * A refusal inside a file names the file as the bundle spells it, {@code ../} and all; the
   * position is the one {@link #a_load_error_is_a_result_with_its_file_position} pins.
   */
  @Test
  void aMalformedFileIsNamedAsTheBundleSpellsIt() {
    RecordingResolver resolver =
        new RecordingResolver().file("../xsd/order.xsd", "<xs:schema\n  <");

    Diagnostic diagnostic =
        refused(Structures.load(resolver, bundle("../xsd/order.xsd"), new ElementRoot("Order")));

    assertThat(diagnostic.code()).isEqualTo("schema-not-well-formed");
    assertThat(diagnostic.location()).isEqualTo(new AtFilePosition("../xsd/order.xsd", 2, 3));
  }

  // --- #93: multi-file bundles (ADR-0008 as amended 2026-11-05; carried in from #90). The
  // reader's table is XsdFilesAndNamesTest in engine/structure-import, over the same fixtures.

  /** {@code corpus/structures/files-and-names/<fixture>}, read through a recording resolver. */
  private static RecordingResolver fixture(String fixture, String... files) throws Exception {
    Path directory = CorpusStructures.directory("files-and-names").resolve(fixture);
    RecordingResolver resolver = new RecordingResolver();
    for (String file : files) {
      resolver.file(file, Files.readAllBytes(directory.resolve(file)));
    }
    return resolver;
  }

  /**
   * #90's untested guard, XsdReader's URIResolver that declines every schemaLocation: an import
   * whose schemaLocation names {@code unlisted.xsd}, a file beside it on disk that the bundle does
   * not list, is {@code schema-reference-outside-bundle}, and the resolver never sees that file.
   * With a bundle file of the import's namespace, the import falls back to it: XmlSchema then reads
   * the import, and without the declining URIResolver it would open {@code unlisted.xsd} itself.
   */
  @Test
  void anImportOfAnUnlistedFileOnDiskIsOutsideTheBundleAndNeverRead() throws Exception {
    Path directory = CorpusStructures.directory("files-and-names").resolve("outside-bundle");
    assertThat(directory.resolve("unlisted.xsd")).as("the witness: it is on disk").isRegularFile();
    RecordingResolver alone = fixture("outside-bundle", "a.xsd", "unlisted.xsd");

    List<Diagnostic> outside =
        refusedAll(
            Structures.load(
                alone, bundle("a.xsd"), new ElementRoot("Q{urn:flottila:corpus:a}Doc")));

    // two more references outside the bundle, and a name no file declares (XsdFilesAndNamesTest)
    assertThat(outside).hasSize(4);
    assertThat(outside.getFirst())
        .isEqualTo(
            new Diagnostic(
                Severity.ERROR,
                "schema-reference-outside-bundle",
                new AtFilePosition("a.xsd", 6, 86),
                "the schemaLocation \"unlisted.xsd\" of this xs:import names no file of the schema"
                    + " bundle, and no bundle file has its namespace"
                    + " \"urn:flottila:corpus:unlisted\""));
    assertThat(alone.calls()).containsExactly("a.xsd");

    RecordingResolver withFallback =
        fixture("outside-bundle", "fallback.xsd", "listed.xsd", "unlisted.xsd");
    Structure structure =
        loaded(
            Structures.load(
                withFallback,
                new SchemaBundle(List.of("fallback.xsd", "listed.xsd")),
                new ElementRoot("Q{urn:flottila:corpus:a}Doc")));

    assertThat(structure.children(structure.rootPath()))
        .extracting(FieldPath::toString)
        .containsExactly("/Q{urn:flottila:corpus:a}Doc/Q{urn:flottila:corpus:unlisted}Listed");
    assertThat(withFallback.calls()).containsExactly("fallback.xsd", "listed.xsd");
  }

  /**
   * #90's other untested guard, the system id XmlSchema gets for each file: an include's relative
   * schemaLocation maps against the bundle name of the including file, by text ({@code
   * ./parts/line.xsd} from {@code xsd/main.xsd} is {@code xsd/parts/line.xsd}); both files share
   * one target namespace, which XmlSchema keeps apart by their system ids.
   */
  @Test
  void anIncludeMapsAgainstTheBundleNameOfTheIncludingFile() throws Exception {
    RecordingResolver resolver = fixture("include", "xsd/main.xsd", "xsd/parts/line.xsd");
    String order = "/Q{urn:flottila:corpus:include}Order";

    Structure structure =
        loaded(
            Structures.load(
                resolver,
                new SchemaBundle(List.of("xsd/main.xsd", "xsd/parts/line.xsd")),
                new ElementRoot("Q{urn:flottila:corpus:include}Order")));

    FieldPath line = structure.children(structure.rootPath()).getFirst();
    assertThat(line).hasToString(order + "/Line");
    assertThat(structure.children(line))
        .extracting(FieldPath::toString)
        .containsExactly(order + "/Line/Sku", order + "/Line/Qty");
    assertThat(resolver.calls()).containsExactly("xsd/main.xsd", "xsd/parts/line.xsd");
  }

  /**
   * One load reports every load error of the bundle (ADR-0008): files in bundle order, each file in
   * document order, the bundle order kept even where the names sort the other way.
   */
  @Test
  void refusalsInSeveralFilesComeBackTogetherInBundleOrder() throws Exception {
    RecordingResolver resolver =
        fixture("all-at-once", "refused-z.xsd", "broken.xsd", "refused-a.xsd");

    List<Diagnostic> diagnostics =
        refusedAll(
            Structures.load(
                resolver,
                new SchemaBundle(List.of("refused-z.xsd", "broken.xsd", "refused-a.xsd")),
                new ElementRoot("Doc")));

    // refused-z.xsd's abstract="true" on Other (11:62) loads since #95
    assertThat(diagnostics)
        .extracting(StructuresTest::described)
        .containsExactly(
            "schema-construct-unsupported refused-z.xsd 6:18",
            "schema-construct-unsupported broken.xsd 4:23",
            "schema-not-well-formed broken.xsd 6:3",
            "schema-construct-unsupported refused-a.xsd 5:40");
    assertThat(diagnostics)
        .allSatisfy(
            diagnostic -> {
              assertThat(diagnostic.severity()).isEqualTo(Severity.ERROR);
              assertThat(diagnostic.message()).isNotBlank();
            });
    assertThat(resolver.calls()).containsExactly("refused-z.xsd", "broken.xsd", "refused-a.xsd");
  }

  /**
   * A file that is not well-formed stops only that file (ADR-0008): the other file's names are
   * still checked, and a reference into the namespace of the stopped file gives no diagnostic.
   */
  @Test
  void aMalformedFileAndANameErrorInAnotherFileComeBackTogether() throws Exception {
    RecordingResolver resolver = fixture("names-after-errors", "user.xsd", "broken.xsd");

    List<Diagnostic> diagnostics =
        refusedAll(
            Structures.load(
                resolver,
                new SchemaBundle(List.of("user.xsd", "broken.xsd")),
                new ElementRoot("Q{urn:flottila:corpus:user}Doc")));

    assertThat(diagnostics)
        .extracting(StructuresTest::described)
        .containsExactly(
            "schema-construct-unsupported user.xsd 10:48",
            "schema-invalid user.xsd 17:52",
            "schema-construct-unsupported user.xsd 18:67",
            "schema-not-well-formed broken.xsd 8:3");
    assertThat(diagnostics.get(1).message())
        .isEqualTo(
            "the type Q{urn:flottila:corpus:user}Missing is declared in no file of the schema"
                + " bundle");
  }

  private static String described(Diagnostic diagnostic) {
    AtFilePosition at = (AtFilePosition) diagnostic.location();
    return diagnostic.code() + " " + at.file() + " " + at.line() + ":" + at.column();
  }
}
