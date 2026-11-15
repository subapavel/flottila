package io.flottila.mapping.structure.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.StructureType;
import io.flottila.mapping.structure.ValueType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Derivation, substitution groups and recursion (#95, ADR-0008 "XSD") at the reader behind {@code
 * Structures.load}, over the fixtures of {@code corpus/structures/derivation/}: an extension gives
 * the base content and then its own, a restriction its own; an element of a complex type with
 * simple content is a leaf whose attributes are fields of their own (ADR-0006 since #102); a
 * reference to a substitution-group head is a choice of its non-abstract members, head first and
 * then by QName; an abstract element or type is refused in a target with {@code
 * abstract-target-element} at its abstract declaration; and {@code xs:redefine}, {@code
 * xs:override} and XSD 1.1 are refused at load.
 *
 * <p>In the expected rows, {@code {x}} stands for {@code Q{urn:flottila:corpus:x}}.
 */
class XsdDerivationTest {

  private static final Path FIXTURES =
      Path.of(System.getProperty("flottila.corpus"), "structures", "derivation");

  private static Structure load(List<String> bundle, String root) throws StructureLoadException {
    return new StructureReader(DirectoryResolver.over(FIXTURES))
        .read(new SchemaBundle(bundle), new ElementRoot(expand(root)));
  }

  static Stream<Arguments> loads() {
    String abstractTarget = " refused:abstract-target-element ";
    return Stream.of(
        Arguments.of(
            "an extension of complex content, named and anonymous, and a restriction, across"
                + " namespaces; the restriction inherits @id, redeclares @since as required in"
                + " its place and prohibits @note, which the anonymous extension's prohibition"
                + " keeps",
            List.of("complex/base.xsd", "complex/derived.xsd"),
            "{derived}Deal",
            List.of(
                "/{derived}Deal 1..1 structure",
                "/{derived}Deal/Buyer 1..1 structure",
                "/{derived}Deal/Buyer/@id 1..1 xs:string",
                "/{derived}Deal/Buyer/@since 0..1 xs:date",
                "/{derived}Deal/Buyer/@note 0..1 xs:string",
                "/{derived}Deal/Buyer/@tier 0..1 xs:string",
                "/{derived}Deal/Buyer/Name 1..1 xs:string",
                "/{derived}Deal/Buyer/Phone 0..1 xs:string",
                "/{derived}Deal/Buyer/Number 1..1 xs:integer",
                "/{derived}Deal/Seller 0..1 structure",
                "/{derived}Deal/Seller/@id 1..1 xs:string",
                "/{derived}Deal/Seller/@since 1..1 xs:date",
                "/{derived}Deal/Seller/Name 1..1 xs:string",
                "/{derived}Deal/Agent 1..1 structure",
                "/{derived}Deal/Agent/@id 1..1 xs:string",
                "/{derived}Deal/Agent/@since 0..1 xs:date",
                "/{derived}Deal/Agent/@note 0..1 xs:string",
                "/{derived}Deal/Agent/@desk 0..1 xs:string",
                "/{derived}Deal/Agent/Name 1..1 xs:string",
                "/{derived}Deal/Agent/Phone 0..1 xs:string")),
        Arguments.of(
            "an extension of simple content: a leaf whose attributes are fields of their own; a"
                + " restriction keeps its base's value type and the attributes it does not"
                + " prohibit; an attribute group's attributes and prohibitions count as the"
                + " type's own",
            List.of("simple-content.xsd"),
            "{simple}Invoice",
            List.of(
                "/{simple}Invoice 1..1 structure",
                "/{simple}Invoice/Total 1..1 xs:decimal nillable",
                "/{simple}Invoice/Total/@currency 1..1 xs:string",
                "/{simple}Invoice/Tax 0..1 xs:decimal",
                "/{simple}Invoice/Tax/@currency 1..1 xs:string",
                "/{simple}Invoice/Tax/@tag 0..1 xs:string",
                "/{simple}Invoice/Code 1..1 xs:string",
                "/{simple}Invoice/Code/@list 0..1 xs:string",
                "/{simple}Invoice/Count 1..1 xs:integer",
                "/{simple}Invoice/Small 1..1 xs:decimal",
                "/{simple}Invoice/Small/@currency 1..1 xs:string",
                // attribute groups in simple content (#94 with #95)
                "/{simple}Invoice/Stamped 1..1 xs:decimal",
                "/{simple}Invoice/Stamped/@currency 1..1 xs:string",
                "/{simple}Invoice/Stamped/@stamp 0..1 xs:string",
                "/{simple}Invoice/Tagged 1..1 xs:decimal",
                "/{simple}Invoice/Tagged/@currency 1..1 xs:string",
                "/{simple}Invoice/Tagged/@tag 1..1 xs:string",
                "/{simple}Invoice/Untagged 1..1 xs:decimal",
                "/{simple}Invoice/Untagged/@currency 1..1 xs:string")),
        Arguments.of(
            "a substitution group across two namespaces with an abstract head; Disc, with no type,"
                + " takes its head's",
            List.of("substitution/heads.xsd", "substitution/members.xsd"),
            "{heads}Catalog",
            List.of(
                "/{heads}Catalog 1..1 structure",
                "/{heads}Catalog/{heads}Gadget 0..unbounded structure",
                "/{heads}Catalog/{heads}Gadget/Sku 1..1 xs:string",
                "/{heads}Catalog/{members}Book 0..unbounded structure",
                "/{heads}Catalog/{members}Book/Sku 1..1 xs:string",
                "/{heads}Catalog/{members}Disc 0..unbounded structure",
                "/{heads}Catalog/{members}Disc/Sku 1..1 xs:string",
                "/{heads}Catalog/{members}Single 0..unbounded structure",
                "/{heads}Catalog/{members}Single/Sku 1..1 xs:string",
                "/{heads}Catalog/{heads}Note 0..1 xs:string",
                "/{heads}Catalog/{heads}Aside 0..1 xs:string",
                "/{heads}Catalog/{members}Memo 0..1 xs:string",
                "/{heads}Catalog/{heads}Plain 1..1 xs:string",
                "/{heads}Catalog/{heads}Ghost 0..1 xs:string"
                    + abstractTarget
                    + "substitution/heads.xsd 28:62")),
        Arguments.of(
            "a type recursive through its own extension",
            List.of("recursive.xsd"),
            "{recursive}Tree",
            List.of(
                "/{recursive}Tree 1..1 structure",
                "/{recursive}Tree/Label 1..1 xs:string",
                "/{recursive}Tree/Child 0..unbounded structure",
                "/{recursive}Tree/Child/Label 1..1 xs:string",
                "/{recursive}Tree/Child/Child 0..unbounded structure",
                "/{recursive}Tree/Child/Child/Label 1..1 xs:string",
                "/{recursive}Tree/Child/Child/Child 0..unbounded structure")),
        Arguments.of(
            "an abstract type and an abstract element, refused at their declaration; same-step"
                + " siblings of the abstract type are ambiguous-field-path instead",
            List.of("abstract/shapes.xsd", "abstract/drawing.xsd"),
            "{drawing}Drawing",
            List.of(
                "/{drawing}Drawing 1..1 structure",
                "/{drawing}Drawing/Shape 0..1 structure"
                    + abstractTarget
                    + "abstract/shapes.xsd 7:48",
                "/{drawing}Drawing/Shape/Colour 1..1 xs:string",
                "/{drawing}Drawing/Circle 1..1 structure",
                "/{drawing}Drawing/Circle/Colour 1..1 xs:string",
                "/{drawing}Drawing/Circle/Radius 1..1 xs:decimal",
                "/{drawing}Drawing/{shapes}Figure 0..1 structure"
                    + abstractTarget
                    + "abstract/shapes.xsd 21:61",
                "/{drawing}Drawing/{shapes}Figure/Colour 1..1 xs:string",
                "/{drawing}Drawing/Twin 0..1 structure refused:ambiguous-field-path"
                    + " abstract/drawing.xsd 16:63",
                "/{drawing}Drawing/Twin/Colour 1..1 xs:string",
                "/{drawing}Drawing/Twin 0..1 structure refused:ambiguous-field-path"
                    + " abstract/drawing.xsd 16:63",
                "/{drawing}Drawing/Twin/Colour 1..1 xs:string")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("loads")
  void a_bundle_with_derivation_loads_as_one_structure(
      String row, List<String> bundle, String root, List<String> tree) throws Exception {
    Structure structure = load(bundle, root);

    assertThat(tree(structure))
        .containsExactlyElementsOf(tree.stream().map(XsdDerivationTest::expand).toList());
  }

  /**
   * A recursive named type is one type of the structure graph, never expanded: a path that repeats
   * it beyond any depth resolves lazily. Its base, which no element uses, is no type of its own.
   */
  @Test
  void a_recursive_type_is_one_type_of_the_structure_graph() throws Exception {
    Structure structure = load(List.of("recursive.xsd"), "{recursive}Tree");
    FieldPath child = structure.children(structure.rootPath()).getLast();
    FieldPath deep = structure.rootPath();
    for (int i = 0; i < 40; i++) {
      deep = deep.child(child.steps().getLast());
    }

    assertThat(structure.types()).containsOnlyKeys(expand("{recursive}Branch"));
    assertThat(structure.field(deep).orElseThrow().type())
        .isEqualTo(structure.root().type())
        .contains(expand("{recursive}Branch"));
  }

  /**
   * ADR-0009 as refined by #92: the reason {@code abstract-target-element} is in the fingerprint,
   * so the abstract Ghost and the same field without its refusal fingerprint apart.
   */
  @Test
  void an_abstract_target_element_is_in_the_fingerprint() throws Exception {
    Structure catalog =
        load(List.of("substitution/heads.xsd", "substitution/members.xsd"), "{heads}Catalog");
    String id = catalog.root().type().orElseThrow();
    List<Field> fields = new ArrayList<>(catalog.types().get(id).fields());
    Field ghost = fields.removeLast();
    fields.add(
        new Field(
            ghost.step(), ghost.occurrence(), ghost.valueType(), ghost.nillable(), ghost.type()));
    Map<String, StructureType> types = new HashMap<>(catalog.types());
    types.put(id, new StructureType(id, fields));

    assertThat(ghost.refusal()).isPresent();
    assertThat(new Structure(catalog.root(), types).fingerprint())
        .isNotEqualTo(catalog.fingerprint());
  }

  static Stream<Arguments> refusals() {
    String unsupported = "schema-construct-unsupported ";
    String invalid = "schema-invalid ";
    String atLoad = " is refused when a schema loads (ADR-0008)";
    String outside = " is outside the XSD the reader reads";
    String here = "> here" + outside;
    return Stream.of(
        Arguments.of(
            "xs:redefine",
            List.of("refused/redefine.xsd"),
            List.of(unsupported + "refused/redefine.xsd 5:47 xs:redefine" + atLoad)),
        Arguments.of(
            "xs:override",
            List.of("refused/override.xsd"),
            List.of(unsupported + "refused/override.xsd 12:48 xs:override" + atLoad)),
        Arguments.of(
            "the elements only XSD 1.1 has, wherever they stand, named as outside the subset",
            List.of("refused/xsd11-elements.xsd"),
            List.of(
                unsupported + "refused/xsd11-elements.xsd 6:26 <xs:defaultOpenContent" + here,
                unsupported + "refused/xsd11-elements.xsd 13:59 <xs:alternative" + here,
                unsupported + "refused/xsd11-elements.xsd 16:29 <xs:assert" + here,
                unsupported + "refused/xsd11-elements.xsd 20:21 <xs:openContent" + here,
                unsupported + "refused/xsd11-elements.xsd 26:36 <xs:assertion" + here)),
        Arguments.of(
            "vc:minVersion 1.1 on the schema",
            List.of("refused/min-version.xsd"),
            List.of(
                unsupported
                    + "refused/min-version.xsd 6:78 vc:minVersion=\"1.1\" asks for XSD 1.1,"
                    + " which is refused when a schema loads (ADR-0008)")),
        Arguments.of(
            "vc:minVersion 1.1 or above anywhere; below 1.1 it has no effect (#89 review); one that"
                + " is no decimal and other versioning attributes stay refused",
            List.of("refused/version-attributes.xsd"),
            List.of(
                unsupported
                    + "refused/version-attributes.xsd 11:66 vc:minVersion=\"2\" asks for XSD 1.1,"
                    + " which is refused when a schema loads (ADR-0008)",
                unsupported
                    + "refused/version-attributes.xsd 13:69 the attribute vc:minVersion of"
                    + " <xs:element>"
                    + outside,
                unsupported
                    + "refused/version-attributes.xsd 14:68 the attribute vc:maxVersion of"
                    + " <xs:element>"
                    + outside,
                unsupported
                    + "refused/version-attributes.xsd 16:48 vc:minVersion=\"1.10\" asks for XSD"
                    + " 1.1, which is refused when a schema loads (ADR-0008)")),
        Arguments.of(
            "derivations outside the subset",
            List.of("invalid/constructs.xsd"),
            List.of(
                unsupported
                    + "invalid/constructs.xsd 22:49 <xs:attribute> after xs:complexContent or"
                    + " xs:simpleContent"
                    + outside,
                unsupported
                    + "invalid/constructs.xsd 26:24 <xs:complexContent> after another child"
                    + outside,
                unsupported
                    + "invalid/constructs.xsd 31:25 <xs:complexContent> without xs:extension or"
                    + " xs:restriction"
                    + outside,
                unsupported
                    + "invalid/constructs.xsd 34:24 <xs:simpleContent> without xs:extension or"
                    + " xs:restriction"
                    + outside,
                unsupported
                    + "invalid/constructs.xsd 39:38 <xs:restriction> after another child"
                    + outside,
                unsupported
                    + "invalid/constructs.xsd 44:42 a derivation from the built-in type"
                    + " xs:anyType"
                    + outside,
                unsupported + "invalid/constructs.xsd 49:52 <xs:sequence> here" + outside,
                unsupported + "invalid/constructs.xsd 57:58 abstract=\"1\"" + outside,
                unsupported
                    + "invalid/constructs.xsd 60:23 <xs:simpleContent> after another child"
                    + outside,
                unsupported
                    + "invalid/constructs.xsd 67:39 <xs:extension> after another child"
                    + outside,
                unsupported + "invalid/constructs.xsd 72:21 <xs:sequence> here" + outside,
                unsupported + "invalid/constructs.xsd 77:21 <xs:sequence> here" + outside,
                unsupported
                    + "invalid/constructs.xsd 81:60 the attribute default of <xs:attribute>"
                    + outside)),
        Arguments.of(
            "bases of the wrong kind or declared nowhere, and a head declared nowhere",
            List.of("invalid/bases.xsd"),
            List.of(
                unsupported
                    + "invalid/bases.xsd 24:49 the attribute fixed of <xs:complexType>"
                    + outside,
                invalid
                    + "invalid/bases.xsd 27:37 the complex type {bases}Money with element content"
                    + " is declared in no file of the schema bundle",
                invalid
                    + "invalid/bases.xsd 32:41 the complex type {bases}Nowhere with element"
                    + " content is declared in no file of the schema bundle",
                invalid
                    + "invalid/bases.xsd 42:37 the simple type or complex type with simple"
                    + " content {bases}Party is declared in no file of the schema bundle",
                invalid
                    + "invalid/bases.xsd 47:39 the simple type or complex type with simple"
                    + " content {bases}Nowhere is declared in no file of the schema bundle",
                invalid
                    + "invalid/bases.xsd 55:77 the element {bases}Missing is declared in no file"
                    + " of the schema bundle")),
        Arguments.of(
            "complex types that derive from themselves and elements in their own substitution"
                + " group",
            List.of("invalid/cycles.xsd"),
            List.of(
                invalid
                    + "invalid/cycles.xsd 16:35 the complex type {cycles}Egg derives from itself",
                invalid
                    + "invalid/cycles.xsd 21:37 the complex type {cycles}Hen derives from itself",
                invalid
                    + "invalid/cycles.xsd 26:36 the complex type {cycles}Self derives from itself",
                invalid
                    + "invalid/cycles.xsd 29:72 the element {cycles}Ping is in its own"
                    + " substitution group",
                invalid
                    + "invalid/cycles.xsd 30:72 the element {cycles}Pong is in its own"
                    + " substitution group")),
        Arguments.of(
            "two attributes of one name in one complex type, once each at the second declaration:"
                + " in the type, through an extension, a reference and an attribute group of"
                + " another file (ct-props-correct.4)",
            List.of("invalid/duplicates.xsd", "invalid/duplicate-group.xsd"),
            List.of(
                invalid
                    + "invalid/duplicates.xsd 14:43 the attribute Q{}a is declared a second time"
                    + " in one complex type",
                invalid
                    + "invalid/duplicates.xsd 30:50 the attribute Q{}b is declared a second time"
                    + " in one complex type",
                invalid
                    + "invalid/duplicates.xsd 48:68 the attribute {duplicates}code is declared a"
                    + " second time in one complex type",
                invalid
                    + "invalid/duplicates.xsd 52:34 the attribute group {duplicates}Loop refers to"
                    + " itself",
                invalid
                    + "invalid/duplicates.xsd 63:65 the attribute Q{}b is declared a second time"
                    + " in one complex type",
                invalid
                    + "invalid/duplicate-group.xsd 8:49 the attribute {duplicates}code is declared"
                    + " a second time in one complex type")));
  }

  /**
   * Each refusal at load is {@code schema-construct-unsupported} or {@code schema-invalid} at the
   * file, line and column of the construct, and its message names the construct. One load reports
   * them all, in document order. The root is not looked up beside other load errors (#93), so every
   * row names the same one.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  void a_refused_construct_is_a_load_error_where_it_stands(
      String row, List<String> bundle, List<String> errors) {
    assertThatThrownBy(() -> load(bundle, "{refused}Doc"))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e ->
                assertThat(e.errors())
                    .extracting(
                        error ->
                            error.code()
                                + " "
                                + error.file()
                                + " "
                                + error.line()
                                + ":"
                                + error.column()
                                + " "
                                + error.getMessage())
                    .containsExactlyElementsOf(
                        errors.stream().map(XsdDerivationTest::expand).toList()));
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

  /**
   * Every field, depth first in field order, same-step siblings each with its own row: {@code
   * "<path> <occurrence> <value type or structure>[ nillable][ refused:<code> <file>
   * <line>:<column>]"}. A leaf's attributes follow it.
   */
  private static List<String> tree(Structure structure) {
    List<String> rows = new ArrayList<>();
    addTree(structure, structure.rootPath(), structure.root(), rows);
    return rows;
  }

  private static void addTree(Structure structure, FieldPath path, Field field, List<String> rows) {
    rows.add(
        path
            + " "
            + field.occurrence()
            + " "
            + field.valueType().map(ValueType::typeName).orElse("structure")
            + (field.nillable() ? " nillable" : "")
            + field
                .refusal()
                .map(
                    r ->
                        " refused:"
                            + r.reason().code()
                            + " "
                            + r.file()
                            + " "
                            + r.line()
                            + ":"
                            + r.column())
                .orElse(""));
    if (path.steps().size() < DEPTH) {
      List<Field> children =
          field
              .type()
              .map(id -> structure.types().get(id).fields())
              .orElse(Collections.emptyList());
      for (Field child : children) {
        addTree(structure, path.child(child.step()), child, rows);
      }
    }
  }
}
