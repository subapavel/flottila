package io.flottila.mapping.structure.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.ValueType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Multi-file XSD bundles and names (#93, ADR-0008 as amended 2026-11-05) at the reader behind
 * {@code Structures.load}, over the fixtures of {@code corpus/structures/files-and-names/} read
 * through a test-scope resolver that records every reference it is asked for (ADR-0014 "Reference
 * resolver shape", F-24).
 *
 * <p>In the expected rows, {@code {x}} stands for {@code Q{urn:flottila:corpus:x}}.
 */
class XsdFilesAndNamesTest {

  private static final Path FIXTURES =
      Path.of(System.getProperty("flottila.corpus"), "structures", "files-and-names");

  /** A resolver over one fixture directory that records the references it is asked for. */
  private static final class Recording implements ReferenceResolver {
    final List<String> calls = new ArrayList<>();
    private final ReferenceResolver directory;

    Recording(String fixture) {
      directory = DirectoryResolver.over(FIXTURES.resolve(fixture));
    }

    @Override
    public ResolvedFile resolve(String reference) throws UnresolvedReferenceException {
      calls.add(reference);
      return directory.resolve(reference);
    }
  }

  private static Structure load(Recording resolver, List<String> bundle, String root)
      throws StructureLoadException {
    return new StructureReader(resolver).read(new SchemaBundle(bundle), new ElementRoot(root));
  }

  static Stream<Arguments> loads() {
    return Stream.of(
        Arguments.of(
            "an import with a location relative to the importing file; the root in the second file",
            "import-location",
            List.of("common/types.xsd", "po/order.xsd"),
            "{po}Order",
            List.of(
                "/{po}Order 1..1 structure",
                "/{po}Order/{po}Id 1..1 xs:string",
                "/{po}Order/{po}ShipTo 1..1 structure",
                "/{po}Order/{po}ShipTo/Street 1..1 xs:string",
                "/{po}Order/{po}ShipTo/City 1..1 xs:string",
                "/{po}Order/{po}Total 1..1 xs:decimal",
                "/{po}Order/{common}Note 0..unbounded xs:string")),
        Arguments.of(
            "an import by namespace only, and one with an absolute location",
            "import-namespace",
            List.of("a.xsd", "b.xsd", "c.xsd"),
            "{a}Doc",
            List.of(
                "/{a}Doc 1..1 structure",
                "/{a}Doc/{b}Item 1..unbounded structure",
                "/{a}Doc/{b}Item/Qty 1..1 xs:integer",
                "/{a}Doc/Code 1..1 xs:date")),
        Arguments.of(
            "the import of http://www.w3.org/2001/xml.xsd falls back to a bundle file",
            "import-xml",
            List.of("doc.xsd", "xml.xsd"),
            "Doc",
            List.of("/Doc 1..1 structure", "/Doc/Text 1..1 xs:string")),
        Arguments.of(
            "an include with a location relative to the including file",
            "include",
            List.of("xsd/main.xsd", "xsd/parts/line.xsd"),
            "{include}Order",
            List.of(
                "/{include}Order 1..1 structure",
                "/{include}Order/Line 1..unbounded structure",
                "/{include}Order/Line/Sku 1..1 xs:string",
                "/{include}Order/Line/Qty 0..1 xs:integer")),
        Arguments.of(
            "an import naming a file beside it that the bundle does not list falls back",
            "outside-bundle",
            List.of("fallback.xsd", "listed.xsd"),
            "{a}Doc",
            List.of("/{a}Doc 1..1 structure", "/{a}Doc/{unlisted}Listed 1..1 xs:boolean")),
        Arguments.of(
            "each document's own form defaults, and a declaration's own form",
            "forms",
            List.of("a.xsd", "b.xsd"),
            "{forms:a}Doc",
            List.of(
                "/{forms:a}Doc 1..1 structure",
                "/{forms:a}Doc/@a1 0..1 xs:string",
                "/{forms:a}Doc/@{forms:a}a2 0..1 xs:string",
                "/{forms:a}Doc/{forms:a}X 1..1 xs:string",
                "/{forms:a}Doc/Y 1..1 xs:string",
                "/{forms:a}Doc/{forms:b}Item 1..1 xs:integer",
                "/{forms:a}Doc/{forms:a}Z 1..1 structure",
                "/{forms:a}Doc/{forms:a}Z/@{forms:b}code 0..1 xs:string",
                "/{forms:a}Doc/{forms:a}Z/@plain 0..1 xs:string",
                "/{forms:a}Doc/{forms:a}Z/W 1..1 xs:string",
                "/{forms:a}Doc/{forms:a}Z/{forms:b}V 1..1 xs:string")),
        Arguments.of(
            "named complex and simple types; a list is one value and a union a string",
            "named-types",
            List.of("deal.xsd"),
            "{named}Deal",
            List.of(
                "/{named}Deal 1..1 structure",
                "/{named}Deal/@currency 1..1 xs:string",
                "/{named}Deal/Buyer 1..1 structure",
                "/{named}Deal/Buyer/Name 1..1 xs:string",
                "/{named}Deal/Seller 0..1 structure",
                "/{named}Deal/Seller/Name 1..1 xs:string",
                "/{named}Deal/Amount 1..1 xs:decimal",
                "/{named}Deal/Count 1..1 xs:integer",
                "/{named}Deal/Codes 1..1 xs:string",
                "/{named}Deal/Either 1..1 xs:string",
                "/{named}Deal/Tree 1..1 structure",
                "/{named}Deal/Tree/Label 1..1 xs:string",
                "/{named}Deal/Tree/Child 0..unbounded structure",
                "/{named}Deal/Tree/Child/Label 1..1 xs:string",
                "/{named}Deal/Tree/Child/Child 0..unbounded structure")),
        Arguments.of(
            "element references to global elements of a simple type, under a named root type",
            "salami",
            List.of("leaf.xsd"),
            "{leaf}Doc",
            List.of(
                "/{leaf}Doc 1..1 structure",
                "/{leaf}Doc/{leaf}Code 1..1 xs:string",
                "/{leaf}Doc/{leaf}Amount 0..1 xs:decimal")),
        Arguments.of(
            "annotations, any, anyAttribute, default, fixed, facets and a key: no effect",
            "no-effect",
            List.of("annotated.xsd"),
            "{no-effect}Order",
            List.of(
                "/{no-effect}Order 1..1 structure",
                "/{no-effect}Order/@channel 0..1 xs:string",
                "/{no-effect}Order/Id 1..1 xs:string",
                "/{no-effect}Order/Status 1..1 xs:string",
                "/{no-effect}Order/Line 1..unbounded structure",
                "/{no-effect}Order/Line/No 1..1 xs:integer")),
        Arguments.of(
            "id, version, block, final, mixed, nillable on a structure field, attributes in other"
                + " namespaces and vc:minVersion below 1.1: no effect",
            "no-effect",
            List.of("xsd-attributes.xsd"),
            "{no-effect}Order",
            List.of(
                "/{no-effect}Order 1..1 structure",
                "/{no-effect}Order/@channel 0..1 xs:string",
                "/{no-effect}Order/Id 1..1 xs:string",
                "/{no-effect}Order/Status 1..1 xs:string",
                "/{no-effect}Order/Line 1..unbounded structure",
                "/{no-effect}Order/Line/No 1..1 xs:integer")),
        Arguments.of(
            "anonymous simple types under local and global elements and attributes take the value"
                + " type of their built-in ancestry",
            "anonymous-types",
            List.of("anonymous.xsd"),
            "{anonymous}Doc",
            List.of(
                "/{anonymous}Doc 1..1 structure",
                "/{anonymous}Doc/@paid 0..1 xs:boolean",
                "/{anonymous}Doc/@{anonymous}rate 1..1 xs:decimal",
                "/{anonymous}Doc/Amount 1..1 xs:decimal",
                "/{anonymous}Doc/Count 0..1 xs:integer",
                "/{anonymous}Doc/Codes 1..1 xs:string",
                "/{anonymous}Doc/Either 1..unbounded xs:string",
                "/{anonymous}Doc/{anonymous}Due 1..1 xs:date")),
        Arguments.of(
            "attribute references, to xml:lang through the import of xml.xsd by URL, are fields"
                + " named by the referenced QName",
            "attribute-ref",
            List.of("doc.xsd", "xml.xsd"),
            "{attribute-ref}Doc",
            List.of(
                "/{attribute-ref}Doc 1..1 structure",
                "/{attribute-ref}Doc/@Q{http://www.w3.org/XML/1998/namespace}lang 1..1 xs:string",
                "/{attribute-ref}Doc/@Q{http://www.w3.org/XML/1998/namespace}space 0..1 xs:string",
                "/{attribute-ref}Doc/@{attribute-ref}code 0..1 xs:integer",
                "/{attribute-ref}Doc/Text 1..1 xs:string")));
  }

  /** Each bundle file is read once, in bundle order, and no other file. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("loads")
  void a_bundle_loads_as_one_structure(
      String row, String fixture, List<String> bundle, String root, List<String> tree)
      throws Exception {
    Recording resolver = new Recording(fixture);

    Structure structure = load(resolver, bundle, expand(root));

    assertThat(tree(structure))
        .containsExactlyElementsOf(tree.stream().map(t -> expand(t)).toList());
    assertThat(resolver.calls).containsExactlyElementsOf(bundle);
  }

  static Stream<Arguments> refusals() {
    String outside = "schema-reference-outside-bundle ";
    String unsupported = "schema-construct-unsupported ";
    String invalid = "schema-invalid ";
    return Stream.of(
        Arguments.of(
            "references outside the bundle: a file beside it, an escape, a namespace; a name in"
                + " none of their namespaces",
            "outside-bundle",
            List.of("a.xsd"),
            "{a}Doc",
            List.of(
                outside + "a.xsd 6:86",
                outside + "a.xsd 7:53",
                outside + "a.xsd 8:55",
                invalid + "a.xsd 12:49")),
        Arguments.of(
            "the import of xml.xsd by URL, with no bundle file of its namespace",
            "import-xml",
            List.of("doc.xsd"),
            "Doc",
            List.of(outside + "doc.xsd 6:63")),
        Arguments.of(
            "an include of a file the bundle does not list",
            "include",
            List.of("xsd/main.xsd"),
            "{include}Order",
            List.of(outside + "xsd/main.xsd 6:50")),
        Arguments.of(
            "an include of an unlisted file, beside a bundle file that never reaches its namespace",
            "include",
            List.of("xsd/main.xsd", "xsd/not-xml.xsd"),
            "{include}Order",
            List.of(outside + "xsd/main.xsd 6:50", "schema-not-well-formed xsd/not-xml.xsd 3:72")),
        Arguments.of(
            "an import whose location and namespace name no bundle file",
            "import-location",
            List.of("po/order.xsd"),
            "{po}Order",
            List.of(outside + "po/order.xsd 7:91")),
        Arguments.of(
            "refusals in several files, together: bundle order, then document order",
            "all-at-once",
            List.of("refused-z.xsd", "broken.xsd", "refused-a.xsd"),
            "Doc",
            // refused-z.xsd's abstract="true" on Other loads since #95 (ADR-0008)
            List.of(
                unsupported + "refused-z.xsd 6:18",
                unsupported + "broken.xsd 4:23",
                "schema-not-well-formed broken.xsd 6:3",
                unsupported + "refused-a.xsd 5:40")),
        Arguments.of(
            "an XSD 1.1 assertion, complex content in a simple type, an attribute's own simple type;"
                + " an import with no namespace falls back to a file with none",
            "refusals",
            List.of("refused.xsd", "plain.xsd"),
            "{refusals}Doc",
            List.of(
                unsupported + "refused.xsd 9:36",
                unsupported + "refused.xsd 13:25",
                unsupported + "refused.xsd 18:25")),
        Arguments.of(
            "names no bundle file declares, and complex types where a simple type is needed",
            "undeclared",
            List.of("undeclared.xsd"),
            "{undeclared}Doc",
            List.of(
                invalid + "undeclared.xsd 10:55",
                invalid + "undeclared.xsd 11:37",
                invalid + "undeclared.xsd 13:50",
                invalid + "undeclared.xsd 18:38",
                invalid + "undeclared.xsd 21:40")),
        Arguments.of(
            "names are checked beside a file that stopped, but none into its namespace and none"
                + " of a refused declaration",
            "names-after-errors",
            List.of("user.xsd", "broken.xsd"),
            "{user}Doc",
            List.of(
                unsupported + "user.xsd 10:48",
                invalid + "user.xsd 17:52",
                unsupported + "user.xsd 18:67",
                "schema-not-well-formed broken.xsd 8:3")),
        Arguments.of(
            "a root no file declares is not reported beside other load errors",
            "names-after-errors",
            List.of("user.xsd", "broken.xsd"),
            "{user}Absent",
            List.of(
                unsupported + "user.xsd 10:48",
                invalid + "user.xsd 17:52",
                unsupported + "user.xsd 18:67",
                "schema-not-well-formed broken.xsd 8:3")),
        Arguments.of(
            "a file never read may declare any name and namespace",
            "names-after-errors",
            List.of("user.xsd", "missing.xsd"),
            "{user}Doc",
            List.of(
                unsupported + "user.xsd 10:48",
                unsupported + "user.xsd 18:67",
                "schema-unreadable missing.xsd 1:1")),
        Arguments.of(
            "simple types that restrict each other",
            "simple-cycle",
            List.of("cycle.xsd"),
            "{cycle}Doc",
            List.of(invalid + "cycle.xsd 14:35", invalid + "cycle.xsd 17:35")),
        Arguments.of(
            "a root of a built-in simple type",
            "salami",
            List.of("leaf.xsd"),
            "{leaf}Code",
            List.of(unsupported + "leaf.xsd 6:45")),
        Arguments.of(
            "a root of a named simple type",
            "salami",
            List.of("leaf.xsd"),
            "{leaf}Amount",
            List.of(unsupported + "leaf.xsd 7:47")),
        Arguments.of(
            "anonymous simple types beside a type, named, or of a base no bundle file declares or"
                + " that is complex; attributes without a type or with one in the XSD namespace",
            "anonymous-types",
            List.of("refused.xsd"),
            "{anonymous}Doc",
            List.of(
                unsupported + "refused.xsd 11:26",
                unsupported + "refused.xsd 14:39",
                invalid + "refused.xsd 17:62",
                invalid + "refused.xsd 20:59",
                unsupported + "refused.xsd 24:24",
                unsupported + "refused.xsd 26:37",
                unsupported + "refused.xsd 27:65",
                unsupported + "refused.xsd 28:52",
                unsupported + "refused.xsd 31:30")),
        Arguments.of(
            "an attribute reference to an attribute no bundle file declares, and ones with a type",
            "attribute-ref",
            List.of("undeclared.xsd"),
            "{attribute-ref}Doc",
            List.of(
                invalid + "undeclared.xsd 9:40",
                unsupported + "undeclared.xsd 10:54",
                unsupported + "undeclared.xsd 11:52")),
        Arguments.of(
            "references into the namespace of an import outside the bundle are no second error",
            "attribute-ref",
            List.of("doc.xsd"),
            "{attribute-ref}Doc",
            List.of(outside + "doc.xsd 10:63")));
  }

  /**
   * One load reports every load error (ADR-0008): files in bundle order, each in document order.
   * The first is the exception's own code and position. Names are checked in every file, whatever
   * load errors other files have; a name a stopped or unread file may declare is not reported.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  void every_load_error_is_reported_at_once(
      String row, String fixture, List<String> bundle, String root, List<String> errors) {
    Recording resolver = new Recording(fixture);

    assertThatThrownBy(() -> load(resolver, bundle, expand(root)))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.errors())
                  .extracting(
                      error ->
                          error.code()
                              + " "
                              + error.file()
                              + " "
                              + error.line()
                              + ":"
                              + error.column())
                  .containsExactlyElementsOf(errors);
              assertThat(e.errors())
                  .allSatisfy(error -> assertThat(error.getMessage()).isNotBlank());
              assertThat(e.errors().getFirst().code()).isEqualTo(e.code());
              assertThat(e.errors().getFirst().line()).isEqualTo(e.line());
            });
    assertThat(resolver.calls).containsExactlyElementsOf(bundle);
  }

  /**
   * ADR-0014 "Reference resolver shape (#90)": a file beside the schema that the bundle does not
   * list is never read. The witness: the directory resolver can read it.
   */
  @Test
  void a_reference_outside_the_bundle_names_it_and_the_resolver_never_sees_it() throws Exception {
    Recording resolver = new Recording("outside-bundle");
    assertThat(Files.isRegularFile(FIXTURES.resolve("outside-bundle/unlisted.xsd"))).isTrue();

    assertThatThrownBy(() -> load(resolver, List.of("a.xsd"), expand("{a}Doc")))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e ->
                assertThat(e.errors())
                    .extracting(Throwable::getMessage)
                    .containsExactly(
                        "the schemaLocation \"unlisted.xsd\" of this xs:import names no file of"
                            + " the schema bundle, and no bundle file has its namespace"
                            + " \"urn:flottila:corpus:unlisted\"",
                        "the schemaLocation \"../../../etc/passwd\" of this xs:include names no"
                            + " file of the schema bundle",
                        "no file of the schema bundle has the namespace"
                            + " \"urn:flottila:corpus:nowhere\" this xs:import names",
                        "the type Q{}Missing is declared in no file of the schema bundle"));
    assertThat(resolver.calls).containsExactly("a.xsd");
  }

  /**
   * A location maps to a bundle name by text: {@code ..} takes off the segment before it unless
   * that is {@code ..} too, so the bundle names may start above the resolver's base.
   */
  @Test
  void a_location_maps_by_text_above_the_base_of_the_bundle() throws Exception {
    Path base = FIXTURES.resolve("up/m/n");
    List<String> calls = new ArrayList<>();
    ReferenceResolver above =
        reference -> {
          calls.add(reference);
          try {
            return new ResolvedFile(
                reference, Files.readAllBytes(base.resolve(reference).normalize()));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        };
    List<String> bundle = List.of("../a/main.xsd", "../../b/part.xsd");

    Structure structure =
        new StructureReader(above)
            .read(new SchemaBundle(bundle), new ElementRoot(expand("{up}Doc")));

    assertThat(tree(structure))
        .containsExactly(
            expand("/{up}Doc 1..1 structure"),
            expand("/{up}Doc/Part 1..1 structure"),
            expand("/{up}Doc/Part/No 1..1 xs:integer"));
    assertThat(calls).containsExactlyElementsOf(bundle);
  }

  /** A file the bundle lists twice is read once. */
  @Test
  void a_file_listed_twice_is_read_once() throws Exception {
    Recording resolver = new Recording("include");

    load(
        resolver,
        List.of("xsd/main.xsd", "xsd/parts/line.xsd", "xsd/main.xsd"),
        expand("{include}Order"));

    assertThat(resolver.calls).containsExactly("xsd/main.xsd", "xsd/parts/line.xsd");
  }

  /** A named complex type used at two paths is one type; a recursive one is never expanded. */
  @Test
  void a_named_complex_type_is_one_type_of_the_structure_graph() throws Exception {
    Structure structure =
        load(new Recording("named-types"), List.of("deal.xsd"), expand("{named}Deal"));
    FieldPath deal = structure.rootPath();

    String party = type(structure, deal, "Buyer");
    assertThat(type(structure, deal, "Seller")).isEqualTo(party);
    FieldPath tree = deal.child(step(structure, deal, "Tree"));
    String node = type(structure, deal, "Tree");
    assertThat(type(structure, tree, "Child")).isEqualTo(node);
    assertThat(structure.types())
        .containsOnlyKeys(
            "/Q{urn:flottila:corpus:named}Deal",
            "Q{urn:flottila:corpus:named}Party",
            "Q{urn:flottila:corpus:named}Node");
  }

  /**
   * ADR-0008: accepted with no effect, so the structure and its fingerprint are the plain one's.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"annotated.xsd", "xsd-attributes.xsd"})
  void constructs_with_no_effect_leave_the_fingerprint_unchanged(String file) throws Exception {
    String root = expand("{no-effect}Order");

    Structure annotated = load(new Recording("no-effect"), List.of(file), root);
    Structure plain = load(new Recording("no-effect"), List.of("plain.xsd"), root);

    assertThat(annotated).isEqualTo(plain);
    assertThat(annotated.fingerprint()).isEqualTo(plain.fingerprint());
  }

  private static String type(Structure structure, FieldPath parent, String localName) {
    return structure
        .field(parent.child(step(structure, parent, localName)))
        .orElseThrow()
        .type()
        .orElseThrow();
  }

  private static FieldPath.Step step(Structure structure, FieldPath parent, String localName) {
    return structure.children(parent).stream()
        .map(p -> p.steps().getLast())
        .filter(s -> s.localName().equals(localName))
        .findFirst()
        .orElseThrow();
  }

  private static final Pattern ABBREVIATION = Pattern.compile("\\{([a-z:-]+)}");

  /** {@code {x}} to {@code Q{urn:flottila:corpus:x}}. */
  private static String expand(String text) {
    return ABBREVIATION
        .matcher(text)
        .replaceAll(m -> Matcher.quoteReplacement("Q{urn:flottila:corpus:" + m.group(1) + "}"));
  }

  /** Paths of at most this many steps; a recursive structure is never walked to its end. */
  private static final int DEPTH = 4;

  /** Every field, depth first in child order: {@code "<path> <occurrence> <kind>"}. */
  private static List<String> tree(Structure structure) {
    List<String> rows = new ArrayList<>();
    addTree(structure, structure.rootPath(), rows);
    return rows;
  }

  private static void addTree(Structure structure, FieldPath path, List<String> rows) {
    Field field = structure.field(path).orElseThrow();
    rows.add(
        path
            + " "
            + field.occurrence()
            + " "
            + field.valueType().map(ValueType::typeName).orElse("structure"));
    if (path.steps().size() < DEPTH) {
      for (FieldPath child : structure.children(path)) {
        addTree(structure, child, rows);
      }
    }
  }
}
