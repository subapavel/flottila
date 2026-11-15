package io.flottila.mapping.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The structure model (ADR-0007, ADR-0008, ADR-0009) at its exported API. */
class StructureTest {

  private static final FieldPath.Step ORDER = element("urn:a", "Order");
  private static final FieldPath.Step ITEM = element("", "Item");
  private static final FieldPath.Step ID = new FieldPath.Step(FieldKind.ATTRIBUTE, "", "id");
  private static final FieldPath.Step NAME = element("", "Name");

  private static FieldPath.Step element(String namespace, String localName) {
    return new FieldPath.Step(FieldKind.ELEMENT, namespace, localName);
  }

  private static Field leaf(FieldPath.Step step, ValueType type) {
    return new Field(step, new Occurrence(0, 1), Optional.of(type), false, Optional.empty());
  }

  /** {@code /Q{urn:a}Order/Item/@id} and {@code /Q{urn:a}Order/Item/Name}, the ADR-0007 example. */
  private static Structure order(List<Field> itemFields) {
    Field root = new Field(ORDER, new Occurrence(1, 1), Optional.empty(), false, Optional.of("O"));
    Field item =
        new Field(
            ITEM,
            new Occurrence(0, Occurrence.UNBOUNDED),
            Optional.empty(),
            false,
            Optional.of("I"));
    return new Structure(
        root,
        Map.of(
            "O", new StructureType("O", List.of(item)), "I", new StructureType("I", itemFields)));
  }

  private static final Structure ORDER_STRUCTURE =
      order(List.of(leaf(ID, ValueType.INTEGER), leaf(NAME, ValueType.STRING)));

  @Test
  void a_field_path_is_EQNames_with_at_for_attributes_and_bare_names_without_a_namespace() {
    FieldPath id = FieldPath.of(ORDER).child(ITEM).child(ID);

    assertThat(id).hasToString("/Q{urn:a}Order/Item/@id");
    assertThat(new FieldPath.Step(FieldKind.ATTRIBUTE, "urn:b", "lang"))
        .hasToString("@Q{urn:b}lang");
  }

  /** ADR-0007 (#97): a JSON path starts with {@code $}, and names percent-encode % / @ { }. */
  @Test
  void a_JSON_path_starts_with_dollar_and_percent_encodes_its_names() {
    FieldPath path =
        FieldPath.of(json("$")).child(json("a/b%")).child(json("@{x}")).child(json("plain $"));

    assertThat(path).hasToString("/$/a%2Fb%25/%40%7Bx%7D/plain $");
  }

  private static FieldPath.Step json(String name) {
    return new FieldPath.Step(FieldKind.JSON_PROPERTY, "", name);
  }

  @Test
  void fields_resolve_by_field_path_through_the_type_graph() {
    FieldPath item = ORDER_STRUCTURE.rootPath().child(ITEM);

    assertThat(ORDER_STRUCTURE.children(item))
        .extracting(FieldPath::toString)
        .containsExactly("/Q{urn:a}Order/Item/@id", "/Q{urn:a}Order/Item/Name");
    assertThat(ORDER_STRUCTURE.field(item.child(NAME)).orElseThrow().valueType())
        .contains(ValueType.STRING);
    assertThat(ORDER_STRUCTURE.field(item).orElseThrow().occurrence()).hasToString("0..unbounded");
    assertThat(ORDER_STRUCTURE.children(item.child(NAME))).isEmpty();
  }

  @Test
  void a_path_the_structure_does_not_have_resolves_to_no_field() {
    FieldPath item = ORDER_STRUCTURE.rootPath().child(ITEM);

    assertThat(ORDER_STRUCTURE.field(FieldPath.of(element("urn:b", "Order")))).isEmpty();
    assertThat(ORDER_STRUCTURE.field(item.child(element("", "Price")))).isEmpty();
    assertThat(ORDER_STRUCTURE.field(item.child(NAME).child(NAME))).isEmpty();
    assertThatThrownBy(() -> ORDER_STRUCTURE.children(item.child(element("", "Price"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/Q{urn:a}Order/Item/Price");
  }

  @Test
  void the_fingerprint_follows_what_the_compiler_reads() {
    Structure same = order(List.of(leaf(ID, ValueType.INTEGER), leaf(NAME, ValueType.STRING)));
    Structure otherValueType =
        order(List.of(leaf(ID, ValueType.STRING), leaf(NAME, ValueType.STRING)));
    Structure otherOrder =
        order(List.of(leaf(NAME, ValueType.STRING), leaf(ID, ValueType.INTEGER)));

    assertThat(ORDER_STRUCTURE.fingerprint()).matches("sha256:[0-9a-f]{64}");
    assertThat(same.fingerprint()).isEqualTo(ORDER_STRUCTURE.fingerprint());
    assertThat(otherValueType.fingerprint()).isNotEqualTo(ORDER_STRUCTURE.fingerprint());
    assertThat(otherOrder.fingerprint()).isNotEqualTo(ORDER_STRUCTURE.fingerprint());
  }

  // --- a refused field in the fingerprint (ADR-0009 as refined by #92): its canonical line ends
  // in its reason; the refusal's file, line and column never enter

  /**
   * A structure of 1 to 4 types {@code T0} … whose first field of each type but the last is the
   * structure field of the next type; every other field is a leaf. Each type has 2 to 4 fields.
   */
  private static Structure randomStructure(Random random) {
    int typeCount = 1 + random.nextInt(4);
    Map<String, StructureType> types = new HashMap<>();
    for (int t = 0; t < typeCount; t++) {
      List<Field> fields = new ArrayList<>();
      int fieldCount = 2 + random.nextInt(3);
      for (int f = 0; f < fieldCount; f++) {
        Occurrence occurrence =
            new Occurrence(random.nextInt(2), random.nextBoolean() ? 1 : Occurrence.UNBOUNDED);
        String name = "f" + random.nextInt(3);
        String namespace = random.nextBoolean() ? "" : "urn:x";
        if (f == 0 && t + 1 < typeCount) {
          fields.add(
              new Field(
                  element(namespace, name),
                  occurrence,
                  Optional.empty(),
                  false,
                  Optional.of("T" + (t + 1))));
        } else {
          FieldKind kind = random.nextBoolean() ? FieldKind.ELEMENT : FieldKind.ATTRIBUTE;
          ValueType valueType = ValueType.values()[random.nextInt(ValueType.values().length)];
          fields.add(
              new Field(
                  new FieldPath.Step(kind, namespace, name),
                  occurrence,
                  Optional.of(valueType),
                  random.nextBoolean(),
                  Optional.empty()));
        }
      }
      types.put("T" + t, new StructureType("T" + t, fields));
    }
    Field root = new Field(ORDER, new Occurrence(1, 1), Optional.empty(), false, Optional.of("T0"));
    return new Structure(root, types);
  }

  /** {@code structure} with field {@code index} of type {@code typeId} carrying {@code refusal}. */
  private static Structure refusing(
      Structure structure, String typeId, int index, Refusal refusal) {
    List<Field> fields = new ArrayList<>(structure.types().get(typeId).fields());
    Field field = fields.get(index);
    fields.set(
        index,
        new Field(
            field.step(),
            field.occurrence(),
            field.valueType(),
            field.nillable(),
            field.type(),
            Optional.of(refusal)));
    Map<String, StructureType> types = new HashMap<>(structure.types());
    types.put(typeId, new StructureType(typeId, fields));
    return new Structure(structure.root(), types);
  }

  /** Every field of {@code structure} as {@code {typeId, index}}, types in id order. */
  private static List<Map.Entry<String, Integer>> fieldsOf(Structure structure) {
    List<Map.Entry<String, Integer>> all = new ArrayList<>();
    for (StructureType type : new TreeMap<>(structure.types()).values()) {
      for (int i = 0; i < type.fields().size(); i++) {
        all.add(Map.entry(type.id(), i));
      }
    }
    return all;
  }

  /**
   * Seeded structures (ADR-0018: parameterised over seeds, the seed in the case name): refusing one
   * field changes the fingerprint; refusing it at another file, line and column gives the same
   * fingerprint; refusing another field of the same structure gives another one, because the reason
   * sits on the refused field's own line.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("refusalSeeds")
  void a_refusal_enters_the_fingerprint_and_its_position_does_not(long seed) {
    Random random = new Random(seed);
    Structure plain = randomStructure(random);
    List<Map.Entry<String, Integer>> fields = fieldsOf(plain);
    Map.Entry<String, Integer> refused = fields.get(random.nextInt(fields.size()));
    List<Map.Entry<String, Integer>> others = new ArrayList<>(fields);
    others.remove(refused);
    Map.Entry<String, Integer> other = others.get(random.nextInt(others.size()));
    int line = 1 + random.nextInt(500);
    int column = 1 + random.nextInt(120);
    Refusal here = new Refusal(Refusal.Reason.AMBIGUOUS_FIELD_PATH, "same-step.xsd", line, column);
    Refusal moved =
        new Refusal(
            Refusal.Reason.AMBIGUOUS_FIELD_PATH,
            "moved/other.xsd",
            line + 1 + random.nextInt(500),
            column + 1 + random.nextInt(120));

    String withRefusal = refusing(plain, refused.getKey(), refused.getValue(), here).fingerprint();

    assertThat(withRefusal)
        .as("seed %d: %s refused", seed, refused)
        .matches("sha256:[0-9a-f]{64}")
        .isNotEqualTo(plain.fingerprint());
    assertThat(refusing(plain, refused.getKey(), refused.getValue(), moved).fingerprint())
        .as("seed %d: %s refused at another file, line and column", seed, refused)
        .isEqualTo(withRefusal);
    assertThat(refusing(plain, other.getKey(), other.getValue(), here).fingerprint())
        .as("seed %d: %s refused instead of %s", seed, other, refused)
        .isNotEqualTo(withRefusal);
  }

  static LongStream refusalSeeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /**
   * #94: interleaved-target-group is a reason of its own, so the same field refused for it gives
   * another fingerprint than for ambiguous-field-path; it holds in a target only, and
   * ambiguous-field-path in a source too (ADR-0008 "Refused when a mapping uses the field").
   */
  @Test
  void each_reason_gives_its_own_canonical_line_and_says_where_it_holds() {
    Structure plain = randomStructure(new Random(7));
    String typeId = new TreeMap<>(plain.types()).firstKey();
    Refusal ambiguous = new Refusal(Refusal.Reason.AMBIGUOUS_FIELD_PATH, "a.xsd", 3, 4);
    Refusal interleaved = new Refusal(Refusal.Reason.INTERLEAVED_TARGET_GROUP, "a.xsd", 3, 4);

    assertThat(refusing(plain, typeId, 1, interleaved).fingerprint())
        .isNotEqualTo(refusing(plain, typeId, 1, ambiguous).fingerprint())
        .isNotEqualTo(plain.fingerprint());
    assertThat(Refusal.Reason.INTERLEAVED_TARGET_GROUP.code())
        .isEqualTo("interleaved-target-group");
    assertThat(Refusal.Reason.INTERLEAVED_TARGET_GROUP.targetOnly()).isTrue();
    assertThat(Refusal.Reason.AMBIGUOUS_FIELD_PATH.targetOnly()).isFalse();
  }

  // --- the array marker (ADR-0008, ADR-0009 since #118): a JSON property's only; its canonical
  // line has " array" after its child type, before any " refused:"; every other line is unchanged

  private static final Occurrence[] OCCURRENCES = {
    new Occurrence(0, 0),
    new Occurrence(0, 1),
    new Occurrence(1, 1),
    new Occurrence(0, Occurrence.UNBOUNDED),
    new Occurrence(1, Occurrence.UNBOUNDED),
    new Occurrence(1, 5)
  };

  /**
   * A JSON structure under the root {@code $}, shaped as {@link #randomStructure}: every field a
   * JSON property at any occurrence, 0..0 and 0..1 among them, each with a random array marker, and
   * one in four refused.
   */
  private static Structure randomJsonStructure(Random random) {
    int typeCount = 1 + random.nextInt(4);
    Map<String, StructureType> types = new HashMap<>();
    for (int t = 0; t < typeCount; t++) {
      List<Field> fields = new ArrayList<>();
      int fieldCount = 2 + random.nextInt(3);
      for (int f = 0; f < fieldCount; f++) {
        Occurrence occurrence = OCCURRENCES[random.nextInt(OCCURRENCES.length)];
        FieldPath.Step step = json("f" + random.nextInt(3));
        Optional<Refusal> refusal =
            random.nextInt(4) == 0
                ? Optional.of(
                    new Refusal(
                        Refusal.Reason.AMBIGUOUS_FIELD_PATH,
                        "a.schema.json",
                        1 + random.nextInt(50),
                        1 + random.nextInt(80)))
                : Optional.empty();
        boolean array = random.nextBoolean();
        if (f == 0 && t + 1 < typeCount) {
          fields.add(
              new Field(
                  step,
                  occurrence,
                  Optional.empty(),
                  false,
                  Optional.of("T" + (t + 1)),
                  refusal,
                  array));
        } else {
          ValueType valueType = ValueType.values()[random.nextInt(ValueType.values().length)];
          fields.add(
              new Field(
                  step,
                  occurrence,
                  Optional.of(valueType),
                  random.nextBoolean(),
                  Optional.empty(),
                  refusal,
                  array));
        }
      }
      types.put("T" + t, new StructureType("T" + t, fields));
    }
    Field root =
        new Field(
            json("$"),
            new Occurrence(1, 1),
            Optional.empty(),
            false,
            Optional.of("T0"),
            Optional.empty(),
            false);
    return new Structure(root, types);
  }

  /** {@code structure} with field {@code at} marked as an array property, or not. */
  private static Structure marking(
      Structure structure, Map.Entry<String, Integer> at, boolean array) {
    List<Field> fields = new ArrayList<>(structure.types().get(at.getKey()).fields());
    Field field = fields.get(at.getValue());
    fields.set(
        at.getValue(),
        new Field(
            field.step(),
            field.occurrence(),
            field.valueType(),
            field.nillable(),
            field.type(),
            field.refusal(),
            array));
    Map<String, StructureType> types = new HashMap<>(structure.types());
    types.put(at.getKey(), new StructureType(at.getKey(), fields));
    return new Structure(structure.root(), types);
  }

  private static boolean marked(Structure structure, Map.Entry<String, Integer> at) {
    return structure.types().get(at.getKey()).fields().get(at.getValue()).array();
  }

  /**
   * Seeded JSON structures (ADR-0018: parameterised over seeds, the seed in the case name):
   * toggling one field's marker changes the fingerprint, whatever the field's occurrence and
   * refusal; toggling it back restores it, so an unmarked field keeps its line; toggling another
   * field gives another fingerprint, because the marker sits on the field's own line. One case per
   * seed, named by it.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("markerSeeds")
  void an_array_marker_enters_the_fingerprint_and_an_unmarked_field_keeps_its_line(long seed) {
    Random random = new Random(seed);
    Structure structure = randomJsonStructure(random);
    List<Map.Entry<String, Integer>> fields = fieldsOf(structure);
    Map.Entry<String, Integer> toggled = fields.get(random.nextInt(fields.size()));
    List<Map.Entry<String, Integer>> others = new ArrayList<>(fields);
    others.remove(toggled);
    Map.Entry<String, Integer> other = others.get(random.nextInt(others.size()));
    boolean was = marked(structure, toggled);

    Structure flipped = marking(structure, toggled, !was);

    assertThat(flipped.fingerprint())
        .as("seed %d: %s marked %s", seed, toggled, !was)
        .matches("sha256:[0-9a-f]{64}")
        .isNotEqualTo(structure.fingerprint());
    assertThat(marking(flipped, toggled, was).fingerprint())
        .as("seed %d: %s toggled back", seed, toggled)
        .isEqualTo(structure.fingerprint());
    assertThat(marking(structure, other, !marked(structure, other)).fingerprint())
        .as("seed %d: %s toggled instead of %s", seed, other, toggled)
        .isNotEqualTo(flipped.fingerprint());
  }

  /** The seeds of the array-marker property test: 1 to 200. */
  static LongStream markerSeeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /**
   * A JSON structure: {@code $} 1..1 of type {@code R}, which holds {@code tags} 0..unbounded
   * xs:string and {@code objs} 0..1 of type {@code O}, which holds {@code sku} 0..1 xs:string.
   * {@code tags} and {@code objs} carry {@code array}; {@code objs} carries {@code refusal}.
   */
  private static Structure worked(boolean array, Optional<Refusal> refusal) {
    Field root =
        new Field(
            json("$"),
            new Occurrence(1, 1),
            Optional.empty(),
            false,
            Optional.of("R"),
            Optional.empty(),
            false);
    Field tags =
        new Field(
            json("tags"),
            new Occurrence(0, Occurrence.UNBOUNDED),
            Optional.of(ValueType.STRING),
            false,
            Optional.empty(),
            Optional.empty(),
            array);
    Field objs =
        new Field(
            json("objs"),
            new Occurrence(0, 1),
            Optional.empty(),
            false,
            Optional.of("O"),
            refusal,
            array);
    Field sku =
        new Field(
            json("sku"),
            new Occurrence(0, 1),
            Optional.of(ValueType.STRING),
            false,
            Optional.empty(),
            Optional.empty(),
            false);
    return new Structure(
        root,
        Map.of(
            "R", new StructureType("R", List.of(tags, objs)),
            "O", new StructureType("O", List.of(sku))));
  }

  /**
   * ADR-0009 (#118), worked by hand: each literal is SHA-256 over the canonical text below,
   * computed outside the code under test. Unmarked, the lines are the ones before #118:
   *
   * <pre>
   * root $ 1..1 - R
   * type O
   * field sku 0..1 xs:string -
   * type R
   * field tags 0..unbounded xs:string -
   * field objs 0..1 - O
   * </pre>
   *
   * Marked, {@code tags} and {@code objs} end in {@code " - array"} and {@code " - O array"}; with
   * {@code objs} refused too, its line is {@code field objs 0..1 - O array
   * refused:ambiguous-field-path}.
   */
  @Test
  void an_array_marker_is_spelled_after_the_child_type_and_before_a_refusal() {
    Refusal ambiguous = new Refusal(Refusal.Reason.AMBIGUOUS_FIELD_PATH, "a.schema.json", 3, 4);

    assertThat(worked(false, Optional.empty()).fingerprint())
        .as("unmarked")
        .isEqualTo("sha256:f964c206850702fb4a7309b67007f092053e8725953b3ab19f436bd32a59e242");
    assertThat(worked(true, Optional.empty()).fingerprint())
        .as("marked")
        .isEqualTo("sha256:09ae025ca05e439b32fe7ff87e653f25ab2126c33bae6de49c38e11be778812b");
    assertThat(worked(true, Optional.of(ambiguous)).fingerprint())
        .as("marked and refused")
        .isEqualTo("sha256:b11ca61be02dae04f7c9fd26ad8d832e62d44ce2e59f8048ed381960bed76490");
  }

  /** ADR-0008: the array marker belongs to JSON properties only. */
  @Test
  void an_array_marker_on_an_element_or_attribute_is_refused() {
    for (FieldKind kind : List.of(FieldKind.ELEMENT, FieldKind.ATTRIBUTE)) {
      FieldPath.Step step = new FieldPath.Step(kind, "", "tags");

      assertThatThrownBy(
              () ->
                  new Field(
                      step,
                      new Occurrence(0, Occurrence.UNBOUNDED),
                      Optional.of(ValueType.STRING),
                      false,
                      Optional.empty(),
                      Optional.empty(),
                      true))
          .as("%s marked", kind)
          .isInstanceOf(IllegalArgumentException.class);
      // witness: the same field unmarked is a field
      assertThat(
              new Field(
                      step,
                      new Occurrence(0, Occurrence.UNBOUNDED),
                      Optional.of(ValueType.STRING),
                      false,
                      Optional.empty(),
                      Optional.empty(),
                      false)
                  .array())
          .as("%s unmarked", kind)
          .isFalse();
    }
    // witness: a JSON property carries the marker
    assertThat(
            new Field(
                    json("tags"),
                    new Occurrence(0, Occurrence.UNBOUNDED),
                    Optional.of(ValueType.STRING),
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    true)
                .array())
        .isTrue();
  }

  /**
   * A refusal added to a field (#92) keeps every other component, the array marker among them
   * (#118): a marked JSON property stays marked, an unmarked element stays unmarked.
   */
  @Test
  void a_refusal_added_to_a_field_keeps_its_array_marker() {
    Refusal ambiguous = new Refusal(Refusal.Reason.AMBIGUOUS_FIELD_PATH, "a.schema.json", 3, 4);
    Field tags =
        new Field(
            json("tags"),
            new Occurrence(1, 5),
            Optional.empty(),
            false,
            Optional.of("T"),
            Optional.empty(),
            true);
    Field name =
        new Field(
            NAME,
            new Occurrence(0, 1),
            Optional.of(ValueType.DATE),
            true,
            Optional.empty(),
            Optional.empty(),
            false);

    assertThat(tags.withRefusal(ambiguous))
        .isEqualTo(
            new Field(
                json("tags"),
                new Occurrence(1, 5),
                Optional.empty(),
                false,
                Optional.of("T"),
                Optional.of(ambiguous),
                true));
    assertThat(name.withRefusal(ambiguous))
        .isEqualTo(
            new Field(
                NAME,
                new Occurrence(0, 1),
                Optional.of(ValueType.DATE),
                true,
                Optional.empty(),
                Optional.of(ambiguous),
                false));
  }
}
