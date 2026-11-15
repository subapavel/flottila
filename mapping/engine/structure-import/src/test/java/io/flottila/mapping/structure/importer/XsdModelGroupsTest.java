package io.flottila.mapping.structure.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.Refusal;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.StructureType;
import io.flottila.mapping.structure.ValueType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * XSD model groups (#94, ADR-0008 "XSD" and "Refused when a mapping uses the field") at the reader
 * behind {@code Structures.load}, over the fixtures of {@code corpus/structures/model-groups/}:
 * model groups disappear at import, and their occurrence moves into the fields as the effective
 * occurrence (GLOSSARY).
 *
 * <p>In the expected rows, {@code {x}} stands for {@code Q{urn:flottila:corpus:x}}, and a refused
 * field's row ends with {@code refused:<code>}, as its canonical line does (ADR-0009).
 */
class XsdModelGroupsTest {

  private static final Path FIXTURES =
      Path.of(System.getProperty("flottila.corpus"), "structures", "model-groups");

  private static final String INTERLEAVED = " refused:interleaved-target-group";

  private static Structure load(List<String> bundle, String root) throws StructureLoadException {
    return new StructureReader(DirectoryResolver.over(FIXTURES))
        .read(new SchemaBundle(bundle), new ElementRoot(expand(root)));
  }

  static Stream<Arguments> loads() {
    return Stream.of(
        Arguments.of(
            "choice: the branches become siblings, minimum 0 where a choice has two or more",
            List.of("choice.xsd"),
            List.of(
                "/{groups}Doc 1..1 structure",
                "/{groups}Doc/Id 1..1 xs:string",
                "/{groups}Doc/Cash 0..1 xs:decimal",
                "/{groups}Doc/Card 0..2 xs:string",
                "/{groups}Doc/Only 0..3 xs:integer",
                "/{groups}Doc/Sole 1..1 xs:string",
                "/{groups}Doc/Tail 1..1 xs:date")),
        Arguments.of(
            "all: the declared order, its own occurrence multiplied in",
            List.of("all.xsd"),
            List.of(
                "/{groups}Doc 1..1 structure",
                "/{groups}Doc/@code 0..1 xs:string",
                "/{groups}Doc/Zeta 0..1 xs:string",
                "/{groups}Doc/Alpha 0..1 xs:date",
                "/{groups}Doc/Line 0..1 structure",
                "/{groups}Doc/Line/@no 0..1 xs:integer",
                "/{groups}Doc/Line/Sku 1..1 xs:string")),
        Arguments.of(
            "nested repeated sequences: the product of every enclosing particle's occurrence",
            List.of("nested.xsd"),
            List.of(
                "/{groups}Doc 1..1 structure",
                "/{groups}Doc/Pair 4..30 xs:string",
                "/{groups}Doc/Many 0..unbounded xs:string",
                "/{groups}Doc/Last 0..2 xs:string",
                "/{groups}Doc/Other 0..1 xs:string")),
        Arguments.of(
            "group and attributeGroup references inlined where they stand, across files",
            List.of("groups.xsd", "common.xsd"),
            List.of(
                "/{groups}Doc 1..1 structure",
                "/{groups}Doc/@id 0..1 xs:string",
                "/{groups}Doc/@by 0..1 xs:string",
                "/{groups}Doc/Buyer 1..1 structure",
                "/{groups}Doc/Buyer/@created 1..1 xs:dateTime",
                "/{groups}Doc/Buyer/@by 0..1 xs:string",
                "/{groups}Doc/Buyer/Name 1..1 xs:string",
                "/{groups}Doc/Buyer/City 0..1 xs:string",
                "/{groups}Doc/Seller 0..1 structure",
                "/{groups}Doc/Seller/Name 1..1 xs:string",
                "/{groups}Doc/Seller/City 0..1 xs:string",
                "/{groups}Doc/Seller/Rating 0..1 xs:integer",
                "/{groups}Doc/Line 0..unbounded structure",
                "/{groups}Doc/Line/Sku 1..1 xs:string")),
        Arguments.of(
            "a group that refers to itself through an element",
            List.of("recursive.xsd"),
            List.of(
                "/{groups}Doc 1..1 structure",
                "/{groups}Doc/Label 1..1 xs:string",
                "/{groups}Doc/Node 0..unbounded structure",
                "/{groups}Doc/Node/Label 1..1 xs:string",
                "/{groups}Doc/Node/Node 0..unbounded structure",
                "/{groups}Doc/Node/Node/Label 1..1 xs:string",
                "/{groups}Doc/Node/Node/Node 0..unbounded structure")),
        Arguments.of(
            "repeated groups of two or more particles are refused, a choice of single elements"
                + " is not",
            List.of("interleaved.xsd", "common.xsd"),
            List.of(
                "/{groups}Doc 1..1 structure",
                "/{groups}Doc/Id 1..1 xs:string",
                "/{groups}Doc/A 1..unbounded xs:string" + INTERLEAVED,
                "/{groups}Doc/B 0..unbounded xs:string" + INTERLEAVED,
                "/{groups}Doc/C 0..unbounded xs:string",
                "/{groups}Doc/D 0..unbounded xs:string",
                "/{groups}Doc/E 0..2 xs:string" + INTERLEAVED,
                "/{groups}Doc/F 0..2 xs:string" + INTERLEAVED,
                "/{groups}Doc/G 0..2 xs:string" + INTERLEAVED,
                "/{groups}Doc/H 1..unbounded xs:string" + INTERLEAVED,
                "/{groups}Doc/I 1..unbounded xs:string" + INTERLEAVED,
                "/{groups}Doc/X 1..unbounded xs:string" + INTERLEAVED,
                "/{groups}Doc/Y 1..unbounded xs:string" + INTERLEAVED,
                "/{groups}Doc/Solo 1..unbounded xs:string",
                "/{groups}Doc/K 0..2 xs:string refused:ambiguous-field-path",
                "/{groups}Doc/K 0..2 xs:integer refused:ambiguous-field-path")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("loads")
  void model_groups_disappear_into_fields_with_effective_occurrences(
      String row, List<String> bundle, List<String> tree) throws Exception {
    Structure structure = load(bundle, "{groups}Doc");

    assertThat(tree(structure))
        .containsExactlyElementsOf(tree.stream().map(XsdModelGroupsTest::expand).toList());
  }

  /**
   * ADR-0008 "Shape (#92)" and "in a target": a field of a repeated group of two or more particles
   * carries interleaved-target-group at the repeated group's declaration, in the file that declares
   * it: the repeated xs:sequence or xs:choice, or the repeated xs:group reference. A field carries
   * at most one refusal: same-step siblings carry ambiguous-field-path, which also holds in a
   * source, instead.
   */
  @Test
  void a_field_of_a_repeated_group_is_refused_at_the_group_declaration() throws Exception {
    Structure structure = load(List.of("interleaved.xsd", "common.xsd"), "{groups}Doc");
    StructureType doc = structure.types().get(structure.root().type().orElseThrow());
    List<String> refusals = new ArrayList<>();
    for (int i = 0; i < doc.fields().size(); i++) {
      Field field = doc.fields().get(i);
      if (field.refusal().isPresent()) {
        Refusal refusal = field.refusal().get();
        refusals.add(
            i
                + " "
                + field.step().localName()
                + " "
                + refusal.reason().code()
                + " "
                + refusal.file()
                + " "
                + refusal.line()
                + ":"
                + refusal.column());
      }
    }

    String interleaved = "interleaved-target-group ";
    // <xs:sequence maxOccurs="unbounded"> (8 spaces, 35 characters) ends at 20:44,
    // <xs:choice maxOccurs="2"> (8, 25) at 28:34, <xs:group ref="g:Pair" maxOccurs="unbounded"/>
    // (8, 46) at 35:55, common.xsd's <xs:sequence maxOccurs="unbounded"> (6, 35) at 20:42, and the
    // second <xs:element name="K" type="xs:integer"/> (10, 40) at 42:51
    assertThat(refusals)
        .containsExactly(
            "1 A " + interleaved + "interleaved.xsd 20:44",
            "2 B " + interleaved + "interleaved.xsd 20:44",
            "5 E " + interleaved + "interleaved.xsd 28:34",
            "6 F " + interleaved + "interleaved.xsd 28:34",
            "7 G " + interleaved + "interleaved.xsd 28:34",
            "8 H " + interleaved + "interleaved.xsd 35:55",
            "9 I " + interleaved + "interleaved.xsd 35:55",
            "10 X " + interleaved + "common.xsd 20:42",
            "11 Y " + interleaved + "common.xsd 20:42",
            "13 K ambiguous-field-path interleaved.xsd 42:51",
            "14 K ambiguous-field-path interleaved.xsd 42:51");
  }

  /**
   * The anonymous type of an element declared in a group is one type of the structure graph, whose
   * id is the group's EQName and the element's step, shared by every use of the group: a group that
   * refers to itself through such an element is never expanded (ADR-0008).
   */
  @Test
  void the_anonymous_type_of_an_element_in_a_group_is_shared_by_every_use() throws Exception {
    Structure structure = load(List.of("recursive.xsd"), "{groups}Doc");
    String node = "Q{urn:flottila:corpus:groups}Tree/Node";

    assertThat(structure.types()).containsOnlyKeys("/Q{urn:flottila:corpus:groups}Doc", node);
    assertThat(structure.types().get(node).fields())
        .extracting(f -> f.step().localName() + " " + f.type().orElse("-"))
        .containsExactly("Label -", "Node " + node);
  }

  /**
   * An element declared in a group names its anonymous type after the group, wherever the group is
   * used; one declared in a type, after its owner as before (ADR-0009: an anonymous type's id).
   */
  @Test
  void an_element_in_a_group_names_its_anonymous_type_after_the_group() throws Exception {
    Structure structure = load(List.of("groups.xsd", "common.xsd"), "{groups}Doc");

    assertThat(new TreeMap<>(structure.types()).keySet())
        .containsExactly(
            "/Q{urn:flottila:corpus:groups}Doc",
            "/Q{urn:flottila:corpus:groups}Doc/Buyer",
            "Q{urn:flottila:corpus:groups:common}Lines/Line",
            "Q{urn:flottila:corpus:groups}Seller");
  }

  /**
   * Load errors of model groups, each where it stands (ADR-0008: one load reports every load
   * error): a group without a model group, two model groups in one group, an element directly in a
   * group, a declaration or a reference with an attribute outside the subset, an occurrence that is
   * no count, an xs:all inside a sequence and a choice inside an xs:all; a group or attribute group
   * that refers to itself, at its declaration; a reference to a group or attribute group no bundle
   * file declares. A reference to a refused declaration is no second error.
   */
  @Test
  void every_load_error_of_a_model_group_is_reported_where_it_stands() {
    String unsupported = "schema-construct-unsupported errors.xsd ";
    String invalid = "schema-invalid errors.xsd ";

    assertThatThrownBy(() -> load(List.of("errors.xsd"), "Doc"))
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
                  .containsExactly(
                      unsupported + "4:27",
                      invalid + "5:25",
                      invalid + "10:25",
                      invalid + "14:31",
                      unsupported + "19:17",
                      unsupported + "22:44",
                      unsupported + "24:37",
                      invalid + "25:34",
                      invalid + "32:40",
                      unsupported + "33:18",
                      unsupported + "34:40",
                      unsupported + "38:27",
                      invalid + "43:41");
              assertThat(e.errors())
                  .extracting(Throwable::getMessage)
                  .contains(
                      "the group Q{}Loop refers to itself",
                      "the group Q{}Back refers to itself",
                      "the group Q{}Absent is declared in no file of the schema bundle",
                      "the attribute group Q{}Self refers to itself",
                      "the attribute group Q{}Nowhere is declared in no file of the schema"
                          + " bundle");
            });
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
   * Every field, depth first in child order, each same-step sibling with its own field: {@code
   * "<path> <occurrence> <kind>"}, and {@code " refused:<code>"} for a refused field.
   */
  private static List<String> tree(Structure structure) {
    List<String> rows = new ArrayList<>();
    addTree(structure, "", 1, structure.root(), rows);
    return rows;
  }

  private static void addTree(
      Structure structure, String parent, int depth, Field field, List<String> rows) {
    String path = parent + "/" + field.step();
    rows.add(
        path
            + " "
            + field.occurrence()
            + " "
            + field.valueType().map(ValueType::typeName).orElse("structure")
            + field.refusal().map(r -> " refused:" + r.reason().code()).orElse(""));
    if (depth < DEPTH && field.type().isPresent()) {
      for (Field child : structure.types().get(field.type().get()).fields()) {
        addTree(structure, path, depth + 1, child, rows);
      }
    }
  }
}
