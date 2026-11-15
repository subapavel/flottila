package io.flottila.mapping.structure.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.Refusal;
import io.flottila.mapping.structure.RootSelector;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.StructureType;
import io.flottila.mapping.structure.ValueType;
import io.flottila.mapping.structure.WsdlDirection;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The flat XSD structure import (spec #21, ticket #25) at the reader's exported API. */
class StructureReaderTest {

  private static final Path SKELETON =
      Path.of(System.getProperty("flottila.corpus"), "structures", "skeleton");
  private static final String ORDER_PATH = "/Q{urn:flottila:corpus:skeleton}Order";
  private static final RootSelector ORDER = new ElementRoot("Q{urn:flottila:corpus:skeleton}Order");

  private static Structure readSkeleton(String file) throws StructureLoadException {
    return new StructureReader(DirectoryResolver.over(SKELETON))
        .read(new SchemaBundle(List.of(file)), ORDER);
  }

  /** The observation kit's structures (ADR-0018), which #50 maps (#62, F-14). */
  private static final Path OBSERVATIONS =
      Path.of(System.getProperty("flottila.corpus"), "structures", "observations");

  private static Structure readObservation(String file, String root) throws StructureLoadException {
    return new StructureReader(DirectoryResolver.over(OBSERVATIONS))
        .read(new SchemaBundle(List.of(file)), new ElementRoot(root));
  }

  @Test
  void the_skeleton_structure_has_ADR_0007_field_paths_attributes_first() throws Exception {
    Structure structure = readSkeleton("order.xsd");

    assertThat(structure.rootPath()).hasToString(ORDER_PATH);
    assertThat(structure.children(structure.rootPath()))
        .extracting(FieldPath::toString)
        .containsExactly(
            ORDER_PATH + "/@channel",
            ORDER_PATH + "/OrderId",
            ORDER_PATH + "/Quantity",
            ORDER_PATH + "/Amount",
            ORDER_PATH + "/Urgent",
            ORDER_PATH + "/DeliveryDate",
            ORDER_PATH + "/PickupTime",
            ORDER_PATH + "/CreatedAt",
            ORDER_PATH + "/Note");
  }

  @Test
  void effective_occurrences_and_value_types_match_the_skeleton_XSD() throws Exception {
    Structure structure = readSkeleton("order.xsd");
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put(ORDER_PATH, describe(structure, structure.rootPath()));
    for (FieldPath child : structure.children(structure.rootPath())) {
      fields.put(child.toString(), describe(structure, child));
    }

    // root 1..1; OrderId the one required leaf; every other field optional (spec #21, Structures)
    assertThat(fields)
        .containsExactly(
            entry(ORDER_PATH, "1..1 structure"),
            entry(ORDER_PATH + "/@channel", "0..1 xs:string"),
            entry(ORDER_PATH + "/OrderId", "1..1 xs:string"),
            entry(ORDER_PATH + "/Quantity", "0..1 xs:integer"),
            entry(ORDER_PATH + "/Amount", "0..1 xs:decimal nillable"),
            entry(ORDER_PATH + "/Urgent", "0..1 xs:boolean"),
            entry(ORDER_PATH + "/DeliveryDate", "0..1 xs:date"),
            entry(ORDER_PATH + "/PickupTime", "0..1 xs:time"),
            entry(ORDER_PATH + "/CreatedAt", "0..1 xs:dateTime"),
            entry(ORDER_PATH + "/Note", "0..unbounded xs:string"));
  }

  @Test
  void the_skeleton_XSD_loads_as_source_and_as_target_with_one_fingerprint() throws Exception {
    Structure source = readSkeleton("order.xsd");
    Structure target = readSkeleton("order.xsd");

    assertThat(source).isEqualTo(target);
    assertThat(source.fingerprint()).matches("sha256:[0-9a-f]{64}");
    assertThat(target.fingerprint()).isEqualTo(source.fingerprint());
  }

  @Test
  void a_changed_occurrence_gives_a_different_fingerprint() throws Exception {
    String original = readSkeleton("order.xsd").fingerprint();
    String changed = readSkeleton("test-fixtures/order-quantity-repeats.xsd").fingerprint();

    assertThat(changed).matches("sha256:[0-9a-f]{64}").isNotEqualTo(original);
  }

  @Test
  void another_prefix_and_whitespace_give_the_same_fingerprint() throws Exception {
    String original = readSkeleton("order.xsd").fingerprint();
    String respelled = readSkeleton("test-fixtures/order-other-prefix.xsd").fingerprint();

    assertThat(respelled).isEqualTo(original);
  }

  @Test
  void a_malformed_XSD_is_refused_at_the_line_and_column_of_the_error() {
    String file = "test-fixtures/malformed.xsd";

    assertThatThrownBy(() -> readSkeleton(file))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-not-well-formed");
              assertThat(e.file()).isEqualTo(file);
              // the mismatched end tag </xs:complexType> starts at line 7, column 5; the parser
              // points at its name, column 7
              assertThat(e.line()).isEqualTo(7);
              assertThat(e.column()).isEqualTo(7);
            });
  }

  /** A refused construct points at the end of its start tag, where SAX reports an element. */
  @ParameterizedTest(name = "{0} is refused with {1} at {2}:{3}")
  @CsvSource({
    // a nested complex type without xs:sequence (#62): refused at its xs:element
    "nested-complex-type,        schema-construct-unsupported,  9, 33",
    "type-unknown-built-in,      schema-construct-unsupported,  9, 51",
    "type-unbound-prefix,        schema-construct-unsupported,  9, 53",
    "missing-type,               schema-construct-unsupported,  9, 34",
    "nillable-not-true-or-false, schema-construct-unsupported,  9, 64",
    "foreign-element,            schema-construct-unsupported,  9, 56",
    "redefine,                   schema-construct-unsupported,  5, 44",
    "not-a-schema,               schema-construct-unsupported,  3, 71",
    // #89 review: a root of an anonymous simple type is refused at the root's declaration
    "root-anonymous-simple-type, schema-construct-unsupported,  5, 28",
    "root-without-complex-type,  schema-construct-unsupported,  5, 29",
    "root-two-complex-types,     schema-construct-unsupported,  7, 22",
    "sequence-after-attribute,   schema-construct-unsupported,  8, 21",
    "min-occurs-invalid,         schema-invalid,                9, 68",
    "max-occurs-invalid,         schema-invalid,                9, 66",
    "min-above-max,              schema-invalid,                9, 79",
    "max-occurs-too-large,       schema-invalid,                9, 83",
    // text is reported at its end: just before </xs:sequence> on line 10
    "text-content,               schema-construct-unsupported, 10,  7",
    // a DOCTYPE is refused, so no entity or external DTD is ever read
    "doctype,                    schema-not-well-formed,        3, 10",
  })
  void a_construct_outside_the_flat_subset_is_refused_where_it_stands(
      String fixture, String code, int line, int column) {
    String file = "test-fixtures/" + fixture + ".xsd";

    assertThatThrownBy(() -> readSkeleton(file))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.file()).isEqualTo(file);
              assertThat(e.line()).isEqualTo(line);
              assertThat(e.column()).isEqualTo(column);
              assertThat(e.getMessage()).isNotBlank();
            });
  }

  /**
   * The nested subset (#62, F-14) grows the flat one by anonymous complex types at any depth, and
   * nothing else: each construct still outside it is refused where it stands.
   */
  @ParameterizedTest(name = "{0} is refused with {1} at {2}:{3}")
  @CsvSource({
    "element-form-default-invalid,   schema-construct-unsupported,  4, 87",
    "nested-two-sequences,           schema-construct-unsupported, 10, 55",
    "nested-two-complex-types,       schema-construct-unsupported, 10, 75",
    "nested-complex-and-simple-type, schema-construct-unsupported, 10, 74",
    "nested-type-and-complex-type,   schema-construct-unsupported, 10, 27",
    "nested-without-name,            schema-construct-unsupported,  9, 43",
    "nested-structure-min-above-max, schema-invalid,                9, 61",
  })
  void a_construct_outside_the_nested_subset_is_refused_where_it_stands(
      String fixture, String code, int line, int column) {
    a_construct_outside_the_flat_subset_is_refused_where_it_stands(fixture, code, line, column);
  }

  /**
   * #93 reads imports, includes, element references and named types, so these fixtures of the
   * subsets above are refused at the same position for another reason: the one-file bundle lists no
   * {@code other.xsd}, and the bundle declares no {@code tns:Money} and no global {@code Order} in
   * no namespace.
   */
  @ParameterizedTest(name = "{0} is refused with {1} at {2}:{3}")
  @CsvSource({
    "import,             schema-reference-outside-bundle,  5, 80",
    "include,            schema-reference-outside-bundle,  5, 43",
    "type-not-built-in,  schema-invalid,                  10, 53",
    "nested-element-ref, schema-invalid,                  10, 65",
  })
  void a_fixture_of_the_subsets_names_what_the_bundle_lacks(
      String fixture, String code, int line, int column) {
    a_construct_outside_the_flat_subset_is_refused_where_it_stands(fixture, code, line, column);
  }

  /**
   * #93 reads named complex types, attributes at any depth, and annotations and default values with
   * no effect (ADR-0008), and #94 reads xs:choice and xs:all, so these fixtures of the subsets
   * above load. A choice of one branch keeps the branch's minimum. The #89 review reads mixed
   * content, nillable on a structure field and attributes in other namespaces with no effect (US
   * 13), and anonymous simple types, whose value type comes from the built-in ancestry (US 5).
   */
  @ParameterizedTest(name = "{0} loads")
  @CsvSource(
      delimiter = '|',
      value = {
        "annotation              | /OrderId 1..1 xs:string; /Note 1..1 xs:string",
        "attribute-not-in-subset | /OrderId 1..1 xs:string; /Note 1..1 xs:string",
        "named-complex-type      | ''",
        "nested-attribute        | /OrderId 1..1 xs:string; /Line 1..unbounded structure;"
            + " /Line/@id 0..1 xs:string",
        "choice                  | /OrderId 1..1 xs:string; /A 1..1 xs:string",
        "complex-type-all        | /A 1..1 xs:string",
        "nested-choice           | /OrderId 1..1 xs:string; /Line 1..unbounded structure;"
            + " /Line/Sku 1..1 xs:string",
        "nested-all              | /OrderId 1..1 xs:string; /Line 1..unbounded structure;"
            + " /Line/Sku 1..1 xs:string",
        "nested-mixed            | /OrderId 1..1 xs:string; /Line 1..unbounded structure",
        "named-mixed             | /OrderId 1..1 xs:string",
        "nested-nillable         | /OrderId 1..1 xs:string; /Line 1..unbounded structure",
        "foreign-attribute       | /OrderId 1..1 xs:string; /Note 1..1 xs:string",
        "nested-simple-type      | /OrderId 1..1 xs:string; /Line 1..unbounded xs:date",
        "root-simple-type        | /Q{urn:flottila:corpus:skeleton}Code 1..1 xs:decimal",
        "schema-defaults         | /OrderId 1..1 xs:string",
        "complex-content-mixed   | /OrderId 1..1 xs:string; /Note 1..1 xs:string",
      })
  void a_fixture_the_subsets_refused_loads(String fixture, String fields) throws Exception {
    Structure structure = readSkeleton("test-fixtures/" + fixture + ".xsd");

    List<String> expected = new ArrayList<>(List.of(ORDER_PATH + " 1..1 structure"));
    for (String field : fields.isEmpty() ? new String[0] : fields.split("; ")) {
      expected.add(ORDER_PATH + field);
    }
    assertThat(tree(structure).entrySet())
        .extracting(e -> e.getKey() + " " + e.getValue())
        .containsExactlyElementsOf(expected);
  }

  @Test
  void the_observation_kit_source_loads_with_its_nested_fields() throws Exception {
    Structure structure = readObservation("src-orders.xsd", "Orders");

    // elementFormDefault="unqualified" and no target namespace: every step is a bare name
    assertThat(tree(structure))
        .containsExactly(
            entry("/Orders", "1..1 structure"),
            entry("/Orders/Order", "0..unbounded structure"),
            entry("/Orders/Order/Id", "0..1 xs:string"),
            entry("/Orders/Order/Tag", "0..unbounded xs:string"),
            entry("/Orders/Order/Line", "0..unbounded structure"),
            entry("/Orders/Order/Line/Sku", "0..1 xs:string"),
            entry("/Orders/Order/Line/Qty", "0..1 xs:string"),
            entry("/Orders/Order/Memo", "0..1 structure"),
            entry("/Orders/Order/Memo/Text", "0..1 xs:string"));
  }

  @Test
  void the_observation_kit_target_loads_with_its_nested_fields() throws Exception {
    Structure structure = readObservation("tgt-list.xsd", "Out");

    assertThat(tree(structure))
        .containsExactly(
            entry("/Out", "1..1 structure"),
            entry("/Out/Item", "0..unbounded structure"),
            entry("/Out/Item/V", "0..unbounded xs:string"),
            entry("/Out/Item/W", "0..unbounded xs:string"),
            entry("/Out/All", "0..unbounded xs:string"));
  }

  @ParameterizedTest(name = "{0} under {1}")
  @CsvSource({"src-orders.xsd, Orders", "tgt-list.xsd, Out"})
  void a_nested_structure_has_one_fingerprint_across_two_loads(String file, String root)
      throws Exception {
    String first = readObservation(file, root).fingerprint();
    String second = readObservation(file, root).fingerprint();

    assertThat(first).matches("sha256:[0-9a-f]{64}");
    assertThat(second).isEqualTo(first);
  }

  @Test
  void element_form_default_qualified_puts_local_elements_at_any_depth_in_the_namespace()
      throws Exception {
    Structure structure = readSkeleton("test-fixtures/nested-qualified.xsd");
    String line = ORDER_PATH + "/Q{urn:flottila:corpus:skeleton}Line";

    // an attribute keeps no namespace: attributeFormDefault is unqualified when absent
    assertThat(tree(structure))
        .containsExactly(
            entry(ORDER_PATH, "1..1 structure"),
            entry(ORDER_PATH + "/@channel", "0..1 xs:string"),
            entry(line, "1..unbounded structure"),
            entry(line + "/Q{urn:flottila:corpus:skeleton}Sku", "1..1 xs:string"));
  }

  @Test
  void sibling_structure_fields_with_one_step_load_each_with_a_type_of_its_own() throws Exception {
    Structure structure = readSkeleton("test-fixtures/nested-same-name-siblings.xsd");
    List<Field> parts = structure.types().get(structure.root().type().orElseThrow()).fields();

    // ADR-0007: the two have no unique path, refused only when a mapping uses them
    assertThat(parts).extracting(f -> f.step().localName()).containsExactly("Part", "Part");
    List<String> children = new ArrayList<>();
    for (Field part : parts) {
      Field child = structure.types().get(part.type().orElseThrow()).fields().getFirst();
      children.add(child.step().localName() + " " + child.valueType().orElseThrow().typeName());
    }
    assertThat(children).containsExactly("A xs:string", "B xs:integer");
    assertThat(structure.types()).hasSize(3);
  }

  // --- refused fields (ADR-0008 "Refused when a mapping uses the field", Shape (#92)): same-step
  // siblings load and each carries ambiguous-field-path at the step's second declaration, the
  // end of its start tag as in StructureLoadException

  @Test
  void same_step_siblings_load_each_refused_at_the_second_declaration() throws Exception {
    String file = "test-fixtures/nested-same-name-siblings.xsd";
    Structure structure = readSkeleton(file);
    List<Field> parts = structure.types().get(structure.root().type().orElseThrow()).fields();
    // the second <xs:element name="Part"> (8 spaces, 24 characters) ends at line 16, column 33
    Refusal second = new Refusal(Refusal.Reason.AMBIGUOUS_FIELD_PATH, file, 16, 33);

    assertThat(parts)
        .extracting(Field::refusal)
        .containsExactly(Optional.of(second), Optional.of(second));
    // A and B are each the one field of their type: the path through Part is refused, not they
    for (Field part : parts) {
      assertThat(structure.types().get(part.type().orElseThrow()).fields())
          .extracting(Field::refusal)
          .containsExactly(Optional.empty());
    }
    assertThat(structure.root().refusal()).isEmpty();
  }

  @Test
  void same_step_leaf_siblings_are_refused_and_their_neighbour_is_not() throws Exception {
    String file = "test-fixtures/nested-same-name-leaves.xsd";
    Structure structure = readSkeleton(file);
    Field line = structure.types().get(structure.root().type().orElseThrow()).fields().getFirst();
    List<Field> fields = structure.types().get(line.type().orElseThrow()).fields();
    List<Map.Entry<String, Optional<Refusal>>> refusals = new ArrayList<>();
    for (Field field : fields) {
      refusals.add(
          entry(
              field.step().localName() + " " + field.valueType().orElseThrow().typeName(),
              field.refusal()));
    }
    // A B A A: the second A (14 spaces, 51 characters) ends its start tag at line 15, column 66;
    // the first and the last A carry that position too
    Refusal second = new Refusal(Refusal.Reason.AMBIGUOUS_FIELD_PATH, file, 15, 66);

    assertThat(refusals)
        .containsExactly(
            entry("A xs:string", Optional.of(second)),
            entry("B xs:integer", Optional.empty()),
            entry("A xs:date", Optional.of(second)),
            entry("A xs:boolean", Optional.of(second)));
    assertThat(line.refusal()).isEmpty();
    assertThat(fields.getFirst().refusal().orElseThrow().reason().code())
        .isEqualTo("ambiguous-field-path");
  }

  private static final Path REFUSED =
      Path.of(System.getProperty("flottila.corpus"), "structures", "refused");

  private static final String PAIR = "Q{urn:flottila:corpus:refused:b}Pair";

  private static final String ITEM = "/Q{urn:flottila:corpus:refused:ref-b}Item";

  /**
   * Bundles whose same-step siblings are declared in another file than the root (ADR-0008: a
   * refusal carries the file, line and column of the declaration that causes it, wherever that
   * declaration is written): {row, fixture directory under corpus/structures/refused, bundle, root,
   * each refused field as "type id, index, step, file line:column" with the types in id order, the
   * type ids the root's fields name}.
   */
  static Stream<Arguments> refusalsWhereDeclared() {
    return Stream.of(
        // Part twice in Pair (6 spaces, 24 characters: 10:31), Leaf twice in the second Part's
        // anonymous type (12 spaces, 43 characters: 14:56); First and Second share Pair, so both
        // paths carry its refusals at one position
        Arguments.of(
            "same-step siblings in a named complex type of the second bundle file",
            "two-file",
            List.of("a.xsd", "b.xsd"),
            "Q{urn:flottila:corpus:refused:a}Doc",
            List.of(
                PAIR + " 0 Part b.xsd 10:31",
                PAIR + " 1 Part b.xsd 10:31",
                PAIR + "/Part 0 Leaf b.xsd 14:56",
                PAIR + "/Part 1 Leaf b.xsd 14:56"),
            List.of(PAIR, PAIR)),
        // Code twice in Item's anonymous type (8 spaces, 43 characters: 11:52)
        Arguments.of(
            "same-step siblings in the anonymous type of a global element of the second bundle"
                + " file, reached by reference",
            "element-ref",
            List.of("a.xsd", "b.xsd"),
            "Q{urn:flottila:corpus:refused:ref-a}Doc",
            List.of(ITEM + " 0 Code b.xsd 11:52", ITEM + " 1 Code b.xsd 11:52"),
            List.of(ITEM)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusalsWhereDeclared")
  void a_refusal_names_the_file_line_and_column_of_the_second_declaration(
      String row,
      String fixture,
      List<String> bundle,
      String root,
      List<String> refused,
      List<String> rootTypes)
      throws Exception {
    Structure structure =
        new StructureReader(DirectoryResolver.over(REFUSED.resolve(fixture)))
            .read(new SchemaBundle(bundle), new ElementRoot(root));
    List<String> refusals = new ArrayList<>();
    for (StructureType type : new TreeMap<>(structure.types()).values()) {
      for (int i = 0; i < type.fields().size(); i++) {
        Field field = type.fields().get(i);
        if (field.refusal().isPresent()) {
          Refusal refusal = field.refusal().get();
          refusals.add(
              type.id()
                  + " "
                  + i
                  + " "
                  + field.step()
                  + " "
                  + refusal.file()
                  + " "
                  + refusal.line()
                  + ":"
                  + refusal.column());
        }
      }
    }

    assertThat(refusals).containsExactlyElementsOf(refused);
    assertThat(structure.types().get(structure.root().type().orElseThrow()).fields())
        .extracting(f -> f.type().orElseThrow())
        .containsExactlyElementsOf(rootTypes);
  }

  /**
   * The structures {@code FingerprintPinTest} (engine/api) pinned before #92, as {directory under
   * corpus/structures, file, root}: none has same-step siblings, so no canonical line gains a
   * reason and none of those literals moves (ADR-0009 as refined by #92).
   */
  private static final List<List<String>> PINNED_BEFORE_92 =
      List.of(
          List.of("observations", "src-orders.xsd", "Orders"),
          List.of("observations", "tgt-create.xsd", "Create"),
          List.of("observations", "tgt-gen.xsd", "Gens"),
          List.of("observations", "tgt-list.xsd", "Out"),
          List.of("observations", "tgt-nested.xsd", "Doc"),
          List.of("observations", "tgt-parents.xsd", "Parents"),
          List.of("observations", "tgt-required-attr.xsd", "Coded"),
          List.of("observations", "tgt-unmapped.xsd", "Unmapped"),
          List.of("skeleton", "order.xsd", "Q{urn:flottila:corpus:skeleton}Order"));

  /** Every refused field of {@code structure}: the root, then each type in id order. */
  private static List<String> refusedFields(Structure structure) {
    List<String> refused = new ArrayList<>();
    structure.root().refusal().ifPresent(r -> refused.add("root " + structure.root().step()));
    for (StructureType type : new TreeMap<>(structure.types()).values()) {
      for (int i = 0; i < type.fields().size(); i++) {
        Field field = type.fields().get(i);
        if (field.refusal().isPresent()) {
          refused.add(type.id() + " " + i + " " + field.step());
        }
      }
    }
    return refused;
  }

  @Test
  void no_field_of_a_pinned_corpus_structure_is_refused() throws Exception {
    Map<String, List<String>> refused = new LinkedHashMap<>();
    Map<String, List<String>> none = new LinkedHashMap<>();
    for (List<String> pinned : PINNED_BEFORE_92) {
      Path directory =
          Path.of(System.getProperty("flottila.corpus"), "structures", pinned.getFirst());
      Structure structure =
          new StructureReader(DirectoryResolver.over(directory))
              .read(new SchemaBundle(List.of(pinned.get(1))), new ElementRoot(pinned.get(2)));
      String key = pinned.getFirst() + "/" + pinned.get(1) + " " + pinned.get(2);
      refused.put(key, refusedFields(structure));
      none.put(key, List.of());
    }

    // witness: the walk finds the two refused Parts of a same-step fixture, in its root's type
    assertThat(refusedFields(readSkeleton("test-fixtures/nested-same-name-siblings.xsd")))
        .containsExactly(ORDER_PATH + " 0 Part", ORDER_PATH + " 1 Part");
    assertThat(refused).hasSize(9).isEqualTo(none);
  }

  /**
   * ADR-0008: the nearest of the seven atomic types by built-in ancestry (XSD 1.1 Part 2, the
   * built-in datatype hierarchy), otherwise xs:string. Each leaf is named after its type.
   */
  @Test
  void every_built_in_simple_type_gets_the_value_type_of_its_ancestry() throws Exception {
    Structure structure = readSkeleton("test-fixtures/built-in-types.xsd");
    Map<String, String> valueTypes = new LinkedHashMap<>();
    for (FieldPath child : structure.children(structure.rootPath())) {
      valueTypes.put(
          child.steps().getLast().localName(),
          structure.field(child).orElseThrow().valueType().orElseThrow().typeName());
    }

    Map<String, String> expected = new LinkedHashMap<>();
    for (String notAtomicOfTheSeven :
        List.of(
            "anySimpleType",
            "anyAtomicType",
            "string",
            "normalizedString",
            "token",
            "language",
            "Name",
            "NCName",
            "NMTOKEN",
            "NMTOKENS",
            "ID",
            "IDREF",
            "IDREFS",
            "ENTITY",
            "ENTITIES",
            "anyURI",
            "QName",
            "NOTATION",
            "base64Binary",
            "hexBinary",
            "float",
            "double",
            "duration",
            "dayTimeDuration",
            "yearMonthDuration",
            "gYear",
            "gYearMonth",
            "gMonth",
            "gMonthDay",
            "gDay")) {
      expected.put(notAtomicOfTheSeven, "xs:string");
    }
    expected.put("boolean", "xs:boolean");
    expected.put("decimal", "xs:decimal");
    for (String derivedFromInteger :
        List.of(
            "integer",
            "nonPositiveInteger",
            "negativeInteger",
            "long",
            "int",
            "short",
            "byte",
            "nonNegativeInteger",
            "unsignedLong",
            "unsignedInt",
            "unsignedShort",
            "unsignedByte",
            "positiveInteger")) {
      expected.put(derivedFromInteger, "xs:integer");
    }
    expected.put("date", "xs:date");
    expected.put("time", "xs:time");
    expected.put("dateTime", "xs:dateTime");
    expected.put("dateTimeStamp", "xs:dateTime");

    assertThat(valueTypes).containsExactlyEntriesOf(expected);
  }

  @Test
  void a_root_with_attributes_and_no_sequence_is_in_the_flat_subset() throws Exception {
    Structure structure = readSkeleton("test-fixtures/attributes-only.xsd");

    assertThat(structure.children(structure.rootPath()))
        .extracting(FieldPath::toString)
        .containsExactly(ORDER_PATH + "/@id");
    assertThat(describe(structure, structure.children(structure.rootPath()).getFirst()))
        .isEqualTo("1..1 xs:integer");
  }

  @ParameterizedTest(name = "root {0} is not found")
  @ValueSource(strings = {"Q{urn:flottila:corpus:skeleton}Invoice", "Order", "Q{unclosed"})
  void a_root_the_schema_does_not_declare_is_refused_at_the_schema_element(String root) {
    assertThatThrownBy(
            () ->
                new StructureReader(DirectoryResolver.over(SKELETON))
                    .read(new SchemaBundle(List.of("order.xsd")), new ElementRoot(root)))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-root-not-found");
              assertThat(e.file()).isEqualTo("order.xsd");
              // <xs:schema …> spans lines 5 and 6 and ends at column 58 of line 6
              assertThat(e.line()).isEqualTo(6);
              assertThat(e.column()).isEqualTo(59);
            });
  }

  /**
   * #96 reads WSDL: a WSDL root over a bundle of no WSDL names an interface the bundle does not
   * declare, at the first file's xs:schema (6:59).
   */
  @Test
  void a_WSDL_root_over_a_bundle_of_no_WSDL_is_schema_root_not_found() {
    RootSelector wsdl =
        new WsdlRoot(
            new WsdlOperation("OrderService", "submit", WsdlDirection.INPUT, Optional.empty()));
    assertThatThrownBy(
            () ->
                new StructureReader(DirectoryResolver.over(SKELETON))
                    .read(new SchemaBundle(List.of("order.xsd")), wsdl))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-root-not-found");
              assertThat(e.file()).isEqualTo("order.xsd");
              assertThat(e.line()).isEqualTo(6);
              assertThat(e.column()).isEqualTo(59);
            });
  }

  /**
   * #93 reads bundles of several files, so the second file is read and, missing, is unreadable; an
   * empty bundle is still refused.
   */
  @Test
  void a_bundle_of_two_files_reads_both_and_an_empty_one_is_refused() {
    assertThatThrownBy(
            () ->
                new StructureReader(DirectoryResolver.over(SKELETON))
                    .read(new SchemaBundle(List.of("order.xsd", "extra.xsd")), ORDER))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-unreadable");
              assertThat(e.file()).isEqualTo("extra.xsd");
              assertThat(e.line()).isEqualTo(1);
              assertThat(e.column()).isEqualTo(1);
            });
    assertThatThrownBy(
            () ->
                new StructureReader(DirectoryResolver.over(SKELETON))
                    .read(new SchemaBundle(List.of()), ORDER))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-construct-unsupported");
              assertThat(e.file()).isEmpty();
            });
  }

  @Test
  void a_missing_schema_file_is_refused_as_unreadable() {
    assertThatThrownBy(() -> readSkeleton("no-such-file.xsd"))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-unreadable");
              assertThat(e.file()).isEqualTo("no-such-file.xsd");
              assertThat(e.line()).isEqualTo(1);
              assertThat(e.column()).isEqualTo(1);
            });
  }

  /**
   * #90 (ADR-0014 "Reference resolver shape"): a reference the host's resolver refuses is a load
   * error about the whole file, at the reference as the bundle spells it, with the engine's code
   * and message for the resolver's reason. A host that spells the refused reference its own way, an
   * absolute path for one, shows nowhere in it.
   */
  @ParameterizedTest(name = "{0} is {1}, the host spelling {3}")
  @CsvSource(
      delimiter = '|',
      value = {
        "UNREADABLE                    | schema-unreadable             | ../xsd/missing.xsd"
            + " | ../xsd/missing.xsd"
            + " | cannot read the schema file \"../xsd/missing.xsd\"",
        "PACKAGE_REFERENCE_UNSUPPORTED | package-reference-unsupported | shared@1.2.0:/xsd/o.xsd"
            + " | shared@1.2.0:/xsd/o.xsd"
            + " | package references need a host resolver: \"shared@1.2.0:/xsd/o.xsd\"",
        "UNREADABLE                    | schema-unreadable             | missing.xsd"
            + " | /srv/host/workspace/missing.xsd"
            + " | cannot read the schema file \"missing.xsd\"",
        "PACKAGE_REFERENCE_UNSUPPORTED | package-reference-unsupported | shared@1.2.0:/xsd/o.xsd"
            + " | /srv/host/packages/shared/1.2.0/xsd/o.xsd"
            + " | package references need a host resolver: \"shared@1.2.0:/xsd/o.xsd\""
      })
  void a_reference_the_resolver_refuses_is_a_load_error_with_the_engines_message(
      UnresolvedReferenceException.Reason reason,
      String code,
      String reference,
      String hostSpelling,
      String message) {
    ReferenceResolver refusing =
        asked -> {
          throw new UnresolvedReferenceException(reason, hostSpelling);
        };

    assertThatThrownBy(
            () -> new StructureReader(refusing).read(new SchemaBundle(List.of(reference)), ORDER))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.file()).isEqualTo(reference);
              assertThat(e.line()).isEqualTo(1);
              assertThat(e.column()).isEqualTo(1);
              assertThat(e.getMessage()).isEqualTo(message);
            });
  }

  /**
   * #90: the name a host gives a file it returns is not the engine's. A load error inside the file
   * sits at the reference the engine asked for, as the bundle spells it, and the host's name, here
   * an absolute path, shows nowhere in it.
   */
  @Test
  void a_load_error_inside_a_resolved_file_names_the_asked_reference_never_the_hosts_name() {
    String hostName = "/srv/host/workspace/xsd/order.xsd";
    ReferenceResolver renaming =
        asked -> new ResolvedFile(hostName, "<xs:schema\n  <".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () ->
                new StructureReader(renaming)
                    .read(new SchemaBundle(List.of("../xsd/order.xsd")), new ElementRoot("Order")))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-not-well-formed");
              // the attribute list of <xs:schema breaks at the "<" on line 2 (StructuresTest)
              assertThat(e.file()).isEqualTo("../xsd/order.xsd");
              assertThat(e.line()).isEqualTo(2);
              assertThat(e.column()).isEqualTo(3);
              assertThat(e.getMessage()).doesNotContain(hostName).doesNotContain("/srv/");
            });
  }

  /** Every field of {@code structure}, depth first in child order, by field path. */
  private static Map<String, String> tree(Structure structure) {
    Map<String, String> fields = new LinkedHashMap<>();
    addTree(structure, structure.rootPath(), fields);
    return fields;
  }

  private static void addTree(Structure structure, FieldPath path, Map<String, String> fields) {
    fields.put(path.toString(), describe(structure, path));
    for (FieldPath child : structure.children(path)) {
      addTree(structure, child, fields);
    }
  }

  private static String describe(Structure structure, FieldPath path) {
    Field field = structure.field(path).orElseThrow();
    String kind = field.valueType().map(ValueType::typeName).orElse("structure");
    return field.occurrence() + " " + kind + (field.nillable() ? " nillable" : "");
  }
}
