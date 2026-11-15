package io.flottila.mapping.mapping;

import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldKind;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.Occurrence;
import io.flottila.mapping.structure.Refusal;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.StructureType;
import io.flottila.mapping.structure.ValueType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Structures for lowering, built from the structure records as the reader gives them (ADR-0008):
 * one type per structure field, keyed by its field path, attributes first.
 */
final class TestStructures {

  static final String SKELETON = "urn:flottila:corpus:skeleton";
  static final String ORDER = "/Q{" + SKELETON + "}Order";

  private TestStructures() {}

  /** The structure field under the root that {@link #order()} adds to the skeleton's shape. */
  static final String ADDRESS = ORDER + "/Address";

  /**
   * The flat shape of {@code corpus/structures/skeleton/order.xsd}, shortened to four fields, and
   * one structure field the skeleton lacks: {@code Address}, last, which holds the leaf {@code
   * Street}.
   */
  static Structure order() {
    return structure(
        SKELETON,
        "Order",
        Map.of(
            ADDRESS,
            new StructureType(ADDRESS, List.of(leaf("", "Street", 0, 1, ValueType.STRING)))),
        attribute("channel", 0, 1),
        leaf("", "OrderId", 1, 1, ValueType.STRING),
        leaf("", "Quantity", 0, 1, ValueType.INTEGER),
        leaf("", "Note", 0, Occurrence.UNBOUNDED, ValueType.STRING),
        structureField("Address", 0, 1, ADDRESS));
  }

  /**
   * A target whose leaves use three namespaces besides none: {@code Number} and {@code Other} in
   * {@code urn:u}, {@code Ref} in {@code urn:v}, {@code Same} in the root's {@code urn:t}.
   */
  static Structure invoice() {
    return structure(
        "urn:t",
        "Invoice",
        Map.of(),
        leaf("urn:u", "Number", 1, 1, ValueType.STRING),
        leaf("", "Plain", 0, 1, ValueType.STRING),
        leaf("urn:v", "Ref", 0, 1, ValueType.STRING),
        leaf("urn:t", "Same", 0, 1, ValueType.STRING),
        leaf("urn:u", "Other", 0, 1, ValueType.STRING));
  }

  /**
   * A target whose root {@code Invoice} has no namespace and whose leaves meet {@code urn:v} before
   * {@code urn:u}, against the order of their names: {@code Plain} in none, {@code Ref} and {@code
   * Other} in {@code urn:v}, {@code Number} in {@code urn:u}.
   */
  static Structure invoiceWithoutNamespace() {
    return structure(
        "",
        "Invoice",
        Map.of(),
        leaf("", "Plain", 0, 1, ValueType.STRING),
        leaf("urn:v", "Ref", 0, 1, ValueType.STRING),
        leaf("urn:u", "Number", 1, 1, ValueType.STRING),
        leaf("urn:v", "Other", 0, 1, ValueType.STRING));
  }

  /**
   * The shape of {@code corpus/structures/observations/src-orders.xsd} without {@code Memo}: {@code
   * Orders/Order} 0..unbounded with {@code Id}, {@code Tag} and {@code Line} 0..unbounded with
   * {@code Sku} and {@code Qty}. No namespace.
   */
  static Structure orders() {
    return structure(
        "",
        "Orders",
        Map.of(
            "/Orders/Order",
            new StructureType(
                "/Orders/Order",
                List.of(
                    leaf("", "Id", 0, 1, ValueType.STRING),
                    leaf("", "Tag", 0, Occurrence.UNBOUNDED, ValueType.STRING),
                    structureField("Line", 0, Occurrence.UNBOUNDED, "/Orders/Order/Line"))),
            "/Orders/Order/Line",
            new StructureType(
                "/Orders/Order/Line",
                List.of(
                    leaf("", "Sku", 0, 1, ValueType.STRING),
                    leaf("", "Qty", 0, 1, ValueType.STRING)))),
        structureField("Order", 0, Occurrence.UNBOUNDED, "/Orders/Order"));
  }

  /**
   * The shape of {@code corpus/structures/tgt-nested.xsd} (#52): {@code Doc/Grp} 0..unbounded,
   * {@code Row} 1..2 in each Grp, {@code Val} 0..1 in each Row, a leaf at depth 4. No namespace.
   */
  static Structure nested() {
    return structure(
        "",
        "Doc",
        Map.of(
            "/Doc/Grp",
            new StructureType("/Doc/Grp", List.of(structureField("Row", 1, 2, "/Doc/Grp/Row"))),
            "/Doc/Grp/Row",
            new StructureType("/Doc/Grp/Row", List.of(leaf("", "Val", 0, 1, ValueType.STRING)))),
        structureField("Grp", 0, Occurrence.UNBOUNDED, "/Doc/Grp"));
  }

  /**
   * A target with two unmapped structure fields under its root {@code Doc} (#63): {@code None}
   * 0..0, holding the leaf {@code X}, and {@code Pair} 2..2, holding the leaf {@code V}. No
   * namespace.
   */
  static Structure repeated() {
    return structure(
        "",
        "Doc",
        Map.of(
            "/Doc/None",
            new StructureType("/Doc/None", List.of(leaf("", "X", 0, 1, ValueType.STRING))),
            "/Doc/Pair",
            new StructureType(
                "/Doc/Pair", List.of(leaf("", "V", 0, Occurrence.UNBOUNDED, ValueType.STRING)))),
        structureField("None", 0, 0, "/Doc/None"),
        structureField("Pair", 2, 2, "/Doc/Pair"));
  }

  /**
   * A target whose type requires itself twice (#63): {@code Doc/Self} 1..1 of the type {@code
   * /Doc/Self}, which holds the leaf {@code V} 0..1, then {@code Self} and {@code Again}, both 1..1
   * of the same type. No instance is finite, and the unmapped {@code Self} is created whatever the
   * mapping. No namespace.
   */
  static Structure selfRequired() {
    Field self = structureField("Self", 1, 1, "/Doc/Self");
    return structure(
        "",
        "Doc",
        Map.of(
            "/Doc/Self",
            new StructureType(
                "/Doc/Self",
                List.of(
                    leaf("", "V", 0, 1, ValueType.STRING),
                    self,
                    structureField("Again", 1, 1, "/Doc/Self")))),
        self);
  }

  /**
   * A finite recursion through two types (#63): the root {@code Doc} holds {@code Fix} 1..1 of the
   * type {@code /T}, and {@code /T} holds the leaf {@code V} 0..1 and {@code Down} 0..1 of the
   * root's type {@code /Doc}. The unmapped Fix under each mapped Down repeats {@code /T} once more
   * on its path, and an unmapped Down ends the chain. No namespace.
   */
  static Structure fixDown() {
    return structure(
        "",
        "Doc",
        Map.of(
            "/T",
            new StructureType(
                "/T",
                List.of(
                    leaf("", "V", 0, 1, ValueType.STRING), structureField("Down", 0, 1, "/Doc")))),
        structureField("Fix", 1, 1, "/T"));
  }

  /** The namespace of {@link #mandatory()}'s {@code Tilde}, whose {@code ~} a pointer escapes. */
  static final String TILDE = "urn:a~b";

  /**
   * A target with mandatory leaves under each kind of parent (#65): {@code Doc/Head} 1..1 holds
   * {@code A} 0..1 and {@code Req} 1..1; {@code Opt} 0..1 holds {@code Must} 1..1; {@code Item}
   * 0..unbounded holds {@code Need} 1..1; then the leaves {@code Tilde} 1..1 in {@link #TILDE} and
   * {@code Free} 0..1. Otherwise no namespace.
   */
  static Structure mandatory() {
    return structure(
        "",
        "Doc",
        Map.of(
            "/Doc/Head",
            new StructureType(
                "/Doc/Head",
                List.of(
                    leaf("", "A", 0, 1, ValueType.STRING),
                    leaf("", "Req", 1, 1, ValueType.STRING))),
            "/Doc/Opt",
            new StructureType("/Doc/Opt", List.of(leaf("", "Must", 1, 1, ValueType.STRING))),
            "/Doc/Item",
            new StructureType("/Doc/Item", List.of(leaf("", "Need", 1, 1, ValueType.STRING)))),
        structureField("Head", 1, 1, "/Doc/Head"),
        structureField("Opt", 0, 1, "/Doc/Opt"),
        structureField("Item", 0, Occurrence.UNBOUNDED, "/Doc/Item"),
        leaf(TILDE, "Tilde", 1, 1, ValueType.STRING),
        leaf("", "Free", 0, 1, ValueType.STRING));
  }

  /** The schema file {@link #sameStep()}'s refusals name. */
  static final String SAME_STEP_FILE = "same-step.xsd";

  /**
   * Same-step siblings as the reader gives them (ADR-0008 Shape (#92)): each carries
   * ambiguous-field-path at its step's second declaration. Under {@code Doc}, the optional leaves
   * {@code Id}, {@code Note} (refused at 8:65), {@code Code} and {@code Note} again; {@code Head}
   * 0..1 holds the leaves {@code Ref} 0..1, 1..1 and 1..1 (refused at 13:56); {@code Body} 0..1
   * holds {@code Part} 1..1 with the leaf {@code A} 0..1, and {@code Part} 0..2 (both refused at
   * 28:53) with the leaf {@code B} 0..1 twice (refused at 30:60). No namespace.
   */
  static Structure sameStep() {
    return structure(
        "",
        "Doc",
        Map.of(
            "/Doc/Head",
            new StructureType(
                "/Doc/Head",
                List.of(
                    refused(leaf("", "Ref", 0, 1, ValueType.STRING), 13, 56),
                    refused(leaf("", "Ref", 1, 1, ValueType.STRING), 13, 56),
                    refused(leaf("", "Ref", 1, 1, ValueType.STRING), 13, 56))),
            "/Doc/Body",
            new StructureType(
                "/Doc/Body",
                List.of(
                    refused(structureField("Part", 1, 1, "/Doc/Body/Part"), 28, 53),
                    refused(structureField("Part", 0, 2, "/Doc/Body/Part#2"), 28, 53))),
            "/Doc/Body/Part",
            new StructureType("/Doc/Body/Part", List.of(leaf("", "A", 0, 1, ValueType.STRING))),
            "/Doc/Body/Part#2",
            new StructureType(
                "/Doc/Body/Part#2",
                List.of(
                    refused(leaf("", "B", 0, 1, ValueType.STRING), 30, 60),
                    refused(leaf("", "B", 0, 1, ValueType.STRING), 30, 60)))),
        leaf("", "Id", 0, 1, ValueType.STRING),
        refused(leaf("", "Note", 0, 1, ValueType.STRING), 8, 65),
        leaf("", "Code", 0, 1, ValueType.STRING),
        refused(leaf("", "Note", 0, 1, ValueType.STRING), 8, 65),
        structureField("Head", 0, 1, "/Doc/Head"),
        structureField("Body", 0, 1, "/Doc/Body"));
  }

  /**
   * Same-step siblings of their own type (#89): under {@code Doc}, the leaf {@code Id} 0..1 and
   * {@code a} 0..1 of the type {@code /A}, which holds {@code a} 0..1 twice of the type {@code /A}
   * (both refused at 5:40). A path of n {@code a} steps is held by 2^n chains. No namespace.
   */
  static Structure twinSelf() {
    return structure(
        "",
        "Doc",
        Map.of(
            "/A",
            new StructureType(
                "/A",
                List.of(
                    refused(structureField("a", 0, 1, "/A"), 5, 40),
                    refused(structureField("a", 0, 1, "/A"), 5, 40)))),
        leaf("", "Id", 0, 1, ValueType.STRING),
        structureField("a", 0, 1, "/A"));
  }

  /** The schema file {@link #abstractTarget()}'s refusals name. */
  static final String SHAPES_FILE = "shapes.xsd";

  /**
   * Abstract declarations as the reader gives them (ADR-0008, #95): under {@code Doc}, the leaf
   * {@code Id} 0..1; {@code Shape} 0..1 of an abstract type (abstract-target-element at 7:48),
   * holding the leaf {@code Size} 0..1 and the same-step leaves {@code Colour} 0..1 twice
   * (ambiguous-field-path at 9:51); {@code Box} 0..1 holding the leaf {@code Need} 1..1, a
   * reference to an abstract element (abstract-target-element at 21:61). No namespace.
   */
  static Structure abstractTarget() {
    Refusal.Reason colour = Refusal.Reason.AMBIGUOUS_FIELD_PATH;
    Refusal.Reason isAbstract = Refusal.Reason.ABSTRACT_TARGET_ELEMENT;
    return structure(
        "",
        "Doc",
        Map.of(
            "/Doc/Shape",
            new StructureType(
                "/Doc/Shape",
                List.of(
                    leaf("", "Size", 0, 1, ValueType.STRING),
                    refused(leaf("", "Colour", 0, 1, ValueType.STRING), colour, 9, 51),
                    refused(leaf("", "Colour", 0, 1, ValueType.STRING), colour, 9, 51))),
            "/Doc/Box",
            new StructureType(
                "/Doc/Box",
                List.of(refused(leaf("", "Need", 1, 1, ValueType.STRING), isAbstract, 21, 61)))),
        leaf("", "Id", 0, 1, ValueType.STRING),
        refused(structureField("Shape", 0, 1, "/Doc/Shape"), isAbstract, 7, 48),
        structureField("Box", 0, 1, "/Doc/Box"));
  }

  /** {@code field} refused for {@code reason} at {@link #SHAPES_FILE} line:column. */
  private static Field refused(Field field, Refusal.Reason reason, int line, int column) {
    return new Field(
        field.step(),
        field.occurrence(),
        field.valueType(),
        field.nillable(),
        field.type(),
        Optional.of(new Refusal(reason, SHAPES_FILE, line, column)));
  }

  /** The schema file {@link #interleaved()}'s refusals name. */
  static final String INTERLEAVED_FILE = "interleaved.xsd";

  /**
   * A repeated sequence of two leaves as the reader gives it (#94, ADR-0008 "in a target"): under
   * {@code Doc}, the leaf {@code Id} 0..1, then {@code A} 1..unbounded and {@code B} 0..unbounded,
   * both refused with interleaved-target-group at {@link #INTERLEAVED_FILE} 20:44, the repeated
   * sequence's declaration. No namespace.
   */
  static Structure interleaved() {
    Refusal refusal =
        new Refusal(Refusal.Reason.INTERLEAVED_TARGET_GROUP, INTERLEAVED_FILE, 20, 44);
    return structure(
        "",
        "Doc",
        Map.of(),
        leaf("", "Id", 0, 1, ValueType.STRING),
        refused(leaf("", "A", 1, Occurrence.UNBOUNDED, ValueType.STRING), refusal),
        refused(leaf("", "B", 0, Occurrence.UNBOUNDED, ValueType.STRING), refusal));
  }

  /**
   * Same-step siblings that carry no refusal, which the reader never gives (#92): {@code Doc/Body}
   * 0..1 holds {@code Part} 0..1 with the leaf {@code A} 0..1, then {@code Part} 1..1 with the leaf
   * {@code B} 0..1. No namespace.
   */
  static Structure unrefusedSiblings() {
    return structure(
        "",
        "Doc",
        Map.of(
            "/Doc/Body",
            new StructureType(
                "/Doc/Body",
                List.of(
                    structureField("Part", 0, 1, "/Doc/Body/Part"),
                    structureField("Part", 1, 1, "/Doc/Body/Part#2"))),
            "/Doc/Body/Part",
            new StructureType("/Doc/Body/Part", List.of(leaf("", "A", 0, 1, ValueType.STRING))),
            "/Doc/Body/Part#2",
            new StructureType("/Doc/Body/Part#2", List.of(leaf("", "B", 0, 1, ValueType.STRING)))),
        structureField("Body", 0, 1, "/Doc/Body"));
  }

  /**
   * A structure with attributes wherever #102 lowers one, built by hand because the import subset
   * puts attributes on the root's type only: the root {@code Doc} carries {@code code} 0..1 and
   * {@code Q{urn:b}flag} 1..1; {@code Item} 0..unbounded carries {@code id} 0..1 and holds the leaf
   * {@code Name} 0..1; {@code Q{urn:c}Note} 0..1 is a simple-content leaf with the attributes
   * {@code lang} 0..1 and {@code Q{urn:d}ref} 0..1. Otherwise no namespace.
   */
  static Structure tagged() {
    return structure(
        "",
        "Doc",
        Map.of(
            "/Doc/Item",
            new StructureType(
                "/Doc/Item",
                List.of(attribute("", "id", 0, 1), leaf("", "Name", 0, 1, ValueType.STRING))),
            "/Doc/Q{urn:c}Note",
            new StructureType(
                "/Doc/Q{urn:c}Note",
                List.of(attribute("", "lang", 0, 1), attribute("urn:d", "ref", 0, 1)))),
        attribute("", "code", 0, 1),
        attribute("urn:b", "flag", 1, 1),
        structureField("Item", 0, Occurrence.UNBOUNDED, "/Doc/Item"),
        new Field(
            new FieldPath.Step(FieldKind.ELEMENT, "urn:c", "Note"),
            new Occurrence(0, 1),
            Optional.of(ValueType.STRING),
            false,
            Optional.of("/Doc/Q{urn:c}Note")));
  }

  static StructureRef ref(Structure structure) {
    FieldPath.Step root = structure.root().step();
    String element =
        root.namespace().isEmpty()
            ? root.localName()
            : "Q{" + root.namespace() + "}" + root.localName();
    return new StructureRef(
        new SchemaBundle(List.of("structure.xsd")),
        new ElementRoot(element),
        structure.fingerprint());
  }

  /**
   * A structure whose root holds {@code fields}; {@code types} holds the type of each structure
   * field among them, by its field path.
   */
  private static Structure structure(
      String namespace, String root, Map<String, StructureType> types, Field... fields) {
    FieldPath.Step step = new FieldPath.Step(FieldKind.ELEMENT, namespace, root);
    String type = FieldPath.of(step).toString();
    Map<String, StructureType> all = new HashMap<>(types);
    all.put(type, new StructureType(type, List.of(fields)));
    return new Structure(
        new Field(step, new Occurrence(1, 1), Optional.empty(), false, Optional.of(type)), all);
  }

  /** A structure field without a namespace: no value type, its child fields in {@code type}. */
  private static Field structureField(String name, long min, long max, String type) {
    return new Field(
        new FieldPath.Step(FieldKind.ELEMENT, "", name),
        new Occurrence(min, max),
        Optional.empty(),
        false,
        Optional.of(type));
  }

  private static Field leaf(
      String namespace, String name, long min, long max, ValueType valueType) {
    return new Field(
        new FieldPath.Step(FieldKind.ELEMENT, namespace, name),
        new Occurrence(min, max),
        Optional.of(valueType),
        false,
        Optional.empty());
  }

  /** {@code field} refused with ambiguous-field-path at {@link #SAME_STEP_FILE} line:column. */
  private static Field refused(Field field, int line, int column) {
    return refused(
        field, new Refusal(Refusal.Reason.AMBIGUOUS_FIELD_PATH, SAME_STEP_FILE, line, column));
  }

  /** {@code field} with {@code refusal}. */
  private static Field refused(Field field, Refusal refusal) {
    return new Field(
        field.step(),
        field.occurrence(),
        field.valueType(),
        field.nillable(),
        field.type(),
        Optional.of(refusal));
  }

  private static Field attribute(String name, long min, long max) {
    return attribute("", name, min, max);
  }

  private static Field attribute(String namespace, String name, long min, long max) {
    return new Field(
        new FieldPath.Step(FieldKind.ATTRIBUTE, namespace, name),
        new Occurrence(min, max),
        Optional.of(ValueType.STRING),
        false,
        Optional.empty());
  }
}
