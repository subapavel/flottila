package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.LoadResults.lowered;
import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.id;
import static io.flottila.mapping.mapping.Models.jsonRoot;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.source;
import static io.flottila.mapping.mapping.Models.targetFieldMapping;
import static io.flottila.mapping.mapping.Models.targetFieldMappings;
import static io.flottila.mapping.mapping.TestStructures.ORDER;
import static io.flottila.mapping.mapping.TestStructures.order;
import static io.flottila.mapping.mapping.TestStructures.ref;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.InputFormat;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldKind;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.Occurrence;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.StructureType;
import io.flottila.mapping.structure.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Lowering of a JSON source (ADR-0008 "JSON as a source at run time", #98): the input format
 * follows the source's root selector, and a source-field box over a JSON property gives a {@code
 * SourceQueue} whose steps are {@code $} and the property names, with the depths of the array
 * properties on its path, so that a back end reads each array item as an occurrence. An array
 * property is one by its array marker, whatever its maximum (#118).
 */
class JsonSourceLoweringTest {

  private static final String ORDER_ID = ORDER + "/OrderId";
  private static final String NOTE = ORDER + "/Note";
  private static final Name ROOT = new Name("", "$");

  /**
   * The JSON source, as the JSON Schema reader gives it, each array property with its array marker
   * (#118): the root {@code $} holds {@code order} 0..1, an object with the leaf {@code id}; {@code
   * lines} 0..unbounded, an array of objects with the leaf {@code sku}; {@code tags} 0..unbounded,
   * an array of strings; {@code one} 0..1, an array of strings with maxItems 1; and {@code objs}
   * 0..1, an array of objects with maxItems 1 and the leaf {@code sku}. {@code many} is an unmarked
   * JSON property at 0..unbounded, which no schema gives: it tells the marker from the maximum.
   */
  private static Structure json() {
    String root = "/$";
    return new Structure(
        new Field(
            property("$"),
            new Occurrence(1, 1),
            Optional.empty(),
            false,
            Optional.of(root),
            Optional.empty(),
            false),
        Map.of(
            root,
            new StructureType(
                root,
                List.of(
                    object("order", 0, 1, "/$/order", false),
                    object("lines", 0, Occurrence.UNBOUNDED, "/$/lines", true),
                    leaf("tags", 0, Occurrence.UNBOUNDED, true),
                    leaf("one", 0, 1, true),
                    object("objs", 0, 1, "/$/objs", true),
                    leaf("many", 0, Occurrence.UNBOUNDED, false))),
            "/$/order",
            new StructureType("/$/order", List.of(leaf("id", 0, 1, false))),
            "/$/lines",
            new StructureType("/$/lines", List.of(leaf("sku", 0, 1, false))),
            "/$/objs",
            new StructureType("/$/objs", List.of(leaf("sku", 0, 1, false)))));
  }

  private static FieldPath.Step property(String name) {
    return new FieldPath.Step(FieldKind.JSON_PROPERTY, "", name);
  }

  /** A JSON object property, an array property's items when {@code array} (#118). */
  private static Field object(String name, long min, long max, String type, boolean array) {
    return new Field(
        property(name),
        new Occurrence(min, max),
        Optional.empty(),
        false,
        Optional.of(type),
        Optional.empty(),
        array);
  }

  /** A JSON string property, an array property's items when {@code array} (#118). */
  private static Field leaf(String name, long min, long max, boolean array) {
    return new Field(
        property(name),
        new Occurrence(min, max),
        Optional.of(ValueType.STRING),
        false,
        Optional.empty(),
        Optional.empty(),
        array);
  }

  /**
   * {@code OrderId <- /$/order/id}, which the skeleton target needs, and {@code Note <- box s2}
   * over {@code field}.
   */
  private static TargetDocument lowerNote(String field) {
    MappingModel model =
        model(
            jsonRoot(json().fingerprint(), "order.schema.json"),
            ref(order()),
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t1", "s1", boxes("s1", source("/$/order/id"))),
                NOTE,
                targetFieldMapping("t2", "s2", boxes("s2", source(field)))),
            Map.of());
    return lowered(Lowering.lower(model, json(), order()));
  }

  private static SourceQueue queue(TargetDocument document, int index) {
    return (SourceQueue) ((TargetSlots) document.content().get(index)).queue();
  }

  private static Name name(String property) {
    return new Name("", property);
  }

  @Test
  void aJsonRootMakesTheInputJson() {
    TargetDocument document = lowerNote("/$/tags");

    assertThat(document.input()).isEqualTo(InputFormat.JSON);
    // witness: the same target over an XML source reads XML
    MappingModel xml =
        model(
            ref(order()),
            ref(order()),
            targetFieldMappings(
                ORDER_ID, targetFieldMapping("t1", "a1", boxes("a1", source(ORDER_ID)))),
            Map.of());
    assertThat(lowered(Lowering.lower(xml, order(), order())).input()).isEqualTo(InputFormat.XML);
  }

  @Test
  void aJsonLeafUnderAnObjectHasNoArrayStep() {
    // its steps are $ and the property names, without a namespace, its context the parent order
    assertThat(queue(lowerNote("/$/tags"), 0))
        .isEqualTo(
            new SourceQueue(
                List.of(ROOT, name("order"), name("id")), 2, false, false, List.of(), id("s1")));
  }

  @Test
  void anArrayOfScalarsIsAnArrayStep() {
    assertThat(queue(lowerNote("/$/tags"), 1))
        .isEqualTo(
            new SourceQueue(List.of(ROOT, name("tags")), 1, false, false, List.of(2), id("s2")));
  }

  @Test
  void aLeafUnderAnArrayOfObjectsHasItsArrayStep() {
    assertThat(queue(lowerNote("/$/lines/sku"), 1))
        .isEqualTo(
            new SourceQueue(
                List.of(ROOT, name("lines"), name("sku")), 2, false, false, List.of(2), id("s2")));
  }

  @Test
  void anArrayOfObjectsIsAStructureFieldWithItsArrayStep() {
    assertThat(queue(lowerNote("/$/lines"), 1))
        .isEqualTo(
            new SourceQueue(List.of(ROOT, name("lines")), 1, true, false, List.of(2), id("s2")));
  }

  /**
   * #118 (ADR-0008 "JSON as a source at run time"): an array property whose maxItems is 1 reads its
   * items as occurrences, so it is an array step, for an array of strings and for the array of
   * objects above a leaf.
   */
  @Test
  void anArrayPropertyWithMaxOneIsAnArrayStep() {
    assertThat(queue(lowerNote("/$/one"), 1))
        .isEqualTo(
            new SourceQueue(List.of(ROOT, name("one")), 1, false, false, List.of(2), id("s2")));
    assertThat(queue(lowerNote("/$/objs/sku"), 1))
        .isEqualTo(
            new SourceQueue(
                List.of(ROOT, name("objs"), name("sku")), 2, false, false, List.of(2), id("s2")));
  }

  /** #118: the array marker, never the maximum, makes an array step. */
  @Test
  void theMarkerNotTheMaximumMakesAnArrayStep() {
    assertThat(queue(lowerNote("/$/many"), 1))
        .isEqualTo(
            new SourceQueue(List.of(ROOT, name("many")), 1, false, false, List.of(), id("s2")));
  }
}
