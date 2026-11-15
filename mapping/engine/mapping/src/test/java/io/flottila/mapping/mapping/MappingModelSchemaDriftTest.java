package io.flottila.mapping.mapping;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.mapping.JsonTree.Arr;
import io.flottila.mapping.mapping.JsonTree.Bool;
import io.flottila.mapping.mapping.JsonTree.Obj;
import io.flottila.mapping.mapping.JsonTree.Str;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.RootSelector;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * The schema {@code contract/mapping-model.schema.json} is the source of the format and the Java
 * records are hand-written (ADR-0011); these tests keep the two from drifting. The schema is read
 * with the tests' own JSON reader and walked from its root through {@code $ref}, so no {@code
 * $defs} name is assumed.
 */
class MappingModelSchemaDriftTest {

  /** The box kinds of ADR-0010 and the record of each. */
  private static final Map<String, Class<?>> BOX_KINDS =
      Map.of(
          "source", SourceFieldBox.class,
          "function", StandardFunctionBox.class,
          "udf", UdfBox.class,
          "unresolved", UnresolvedFunctionBox.class);

  /** The one alias: {@code interface} is a Java keyword (F-6). */
  private static final Map<Class<?>, Map<String, String>> ALIASES =
      Map.of(WsdlOperation.class, Map.of("interfaceName", "interface"));

  @Test
  void schemaIsDraft2020WithTheFormatVersionInItsId() throws Exception {
    Schema schema = Schema.read();

    assertThat(schema.root().members().get("$schema"))
        .isEqualTo(new Str("https://json-schema.org/draft/2020-12/schema"));
    assertThat(schema.root().members().get("$id"))
        .isEqualTo(new Str("urn:flottila:mapping-model:1"));
  }

  @Test
  void schemaObjectPropertiesEqualRecordComponents() throws Exception {
    Schema schema = Schema.read();
    Map<Class<?>, Obj> pairs = new LinkedHashMap<>();
    List<String> unknownKinds = new ArrayList<>();
    List<Class<?>> rootSelectors = new ArrayList<>();
    Obj root = schema.root();
    pairs.put(MappingModel.class, root);
    Obj structureRef = schema.property(root, "source");
    pairs.put(StructureRef.class, structureRef);
    Obj targetFieldMapping = schema.mapValue(schema.property(root, "targetFieldMappings"));
    pairs.put(TargetFieldMapping.class, targetFieldMapping);
    Obj layout = schema.property(targetFieldMapping, "layout");
    pairs.put(Layout.class, layout);
    Obj boxPosition = schema.property(layout, "target");
    pairs.put(BoxPosition.class, boxPosition);
    for (Obj branch : schema.oneOf(schema.mapValue(schema.property(targetFieldMapping, "boxes")))) {
      String kind = schema.constOf(schema.property(branch, "kind"));
      if (BOX_KINDS.containsKey(kind)) {
        pairs.put(BOX_KINDS.get(kind), branch);
      } else {
        unknownKinds.add(kind);
      }
    }
    pairs.put(UdfDeclaration.class, schema.mapValue(schema.property(root, "udfs")));
    for (Obj branch : schema.oneOf(schema.property(structureRef, "root"))) {
      Class<?> selector = rootSelectorOf(branch);
      rootSelectors.add(selector);
      pairs.put(selector, branch);
      if (selector == WsdlRoot.class) {
        pairs.put(WsdlOperation.class, schema.property(branch, "wsdl"));
      }
    }

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(unknownKinds).as("box kinds without a record").isEmpty();
    softly
        .assertThat(propertyNames(schema, schema.property(root, "target")))
        .as("target is a structure reference like source")
        .isEqualTo(componentNames(StructureRef.class));
    softly
        .assertThat(propertyNames(schema, schema.mapValue(schema.property(layout, "boxes"))))
        .as("a box's position is a position like the target's (ADR-0029)")
        .isEqualTo(componentNames(BoxPosition.class));
    softly
        .assertThat(rootSelectors)
        .as("root selector branches")
        .containsExactlyInAnyOrder(RootSelector.class.getPermittedSubclasses());
    pairs.forEach(
        (record, node) ->
            softly
                .assertThat(propertyNames(schema, node))
                .as("schema properties of %s", record.getSimpleName())
                .isEqualTo(componentNames(record)));
    softly.assertAll();
    assertThat(pairs.keySet())
        .as("records compared")
        .containsExactlyInAnyOrder(
            MappingModel.class,
            StructureRef.class,
            TargetFieldMapping.class,
            Layout.class,
            BoxPosition.class,
            SourceFieldBox.class,
            StandardFunctionBox.class,
            UdfBox.class,
            UnresolvedFunctionBox.class,
            UdfDeclaration.class,
            ElementRoot.class,
            WsdlRoot.class,
            WsdlOperation.class,
            JsonRoot.class);
  }

  @Test
  void boxOneOfEqualsPermittedSubclasses() throws Exception {
    Schema schema = Schema.read();
    Obj targetFieldMapping = schema.mapValue(schema.property(schema.root(), "targetFieldMappings"));
    List<String> kinds = new ArrayList<>();
    for (Obj branch : schema.oneOf(schema.mapValue(schema.property(targetFieldMapping, "boxes")))) {
      kinds.add(schema.constOf(schema.property(branch, "kind")));
    }

    assertThat(kinds).doesNotHaveDuplicates();
    assertThat(BOX_KINDS).containsOnlyKeys(kinds);
    assertThat(kinds.stream().map(BOX_KINDS::get).collect(Collectors.toSet()))
        .isEqualTo(leafRecords(Box.class));
  }

  @Test
  void schemaHasNoPrefixItemsOrUnevaluatedProperties() throws Exception {
    Set<String> keys = new TreeSet<>();
    collectKeys(Schema.read().root(), keys);

    assertThat(keys).as("the schema was read").contains("oneOf", "additionalProperties");
    assertThat(keys).doesNotContain("prefixItems", "unevaluatedProperties");
  }

  @Test
  void everyObjectSchemaIsClosed() throws Exception {
    Schema schema = Schema.read();
    // the listed exception: a table parameter's keys are source values, so it closes with
    // additionalProperties: {type: string} (ADR-0011, F-6)
    Set<Obj> tableCandidates = Collections.newSetFromMap(new IdentityHashMap<>());
    Obj targetFieldMapping = schema.mapValue(schema.property(schema.root(), "targetFieldMappings"));
    for (Obj branch : schema.oneOf(schema.mapValue(schema.property(targetFieldMapping, "boxes")))) {
      if (branch.members().get("properties") instanceof Obj properties
          && properties.members().containsKey("params")) {
        Obj value = schema.mapValue(schema.property(branch, "params"));
        tableCandidates.add(value);
        for (String combinator : List.of("oneOf", "anyOf")) {
          if (value.members().get(combinator) instanceof Arr branches) {
            for (JsonTree b : branches.items()) {
              tableCandidates.add(schema.resolve(b));
            }
          }
        }
      }
    }
    List<String> open = new ArrayList<>();
    int[] closed = {0};
    int[] tables = {0};
    schema.walk(
        (pointer, node) -> {
          if (!isObjectSchema(node)) {
            return;
          }
          JsonTree additional = node.members().get("additionalProperties");
          if (tableCandidates.contains(node)) {
            tables[0]++;
            if (!new Obj(Map.of("type", new Str("string"))).equals(additional)
                || node.members().containsKey("properties")
                || node.members().containsKey("patternProperties")) {
              open.add(pointer + " (table): additionalProperties " + additional);
            }
          } else if (new Bool(false).equals(additional)) {
            closed[0]++;
          } else {
            open.add(pointer + ": additionalProperties " + additional);
          }
        });

    assertThat(open).as("object schemas not closed").isEmpty();
    assertThat(tables[0]).as("the table schema was found and checked").isPositive();
    assertThat(closed[0]).as("closed object schemas seen").isGreaterThanOrEqualTo(12);
  }

  private static boolean isObjectSchema(Obj node) {
    JsonTree type = node.members().get("type");
    boolean typedObject =
        new Str("object").equals(type)
            || (type instanceof Arr types && types.items().contains(new Str("object")));
    return typedObject
        || node.members().containsKey("properties")
        || node.members().containsKey("patternProperties");
  }

  private static Class<?> rootSelectorOf(Obj branch) {
    Set<String> names =
        branch.members().get("properties") instanceof Obj p ? p.members().keySet() : Set.of();
    if (names.contains("element")) {
      return ElementRoot.class;
    }
    if (names.contains("wsdl")) {
      return WsdlRoot.class;
    }
    if (names.contains("json")) {
      return JsonRoot.class;
    }
    throw new AssertionError("root selector branch with properties " + names);
  }

  /**
   * The properties of an object schema, without the discriminators: a property whose schema is a
   * string {@code const} ({@code kind}, and {@code json: "$"}) has no record component.
   */
  private static Set<String> propertyNames(Schema schema, Obj node) {
    Set<String> names = new TreeSet<>();
    if (node.members().get("properties") instanceof Obj properties) {
      for (Map.Entry<String, JsonTree> e : properties.members().entrySet()) {
        if (!(schema.resolve(e.getValue()).members().get("const") instanceof Str)) {
          names.add(e.getKey());
        }
      }
    }
    return names;
  }

  private static Set<String> componentNames(Class<?> record) {
    assertThat(record.isRecord()).as("%s is a record", record.getSimpleName()).isTrue();
    Map<String, String> alias = ALIASES.getOrDefault(record, Map.of());
    return Arrays.stream(record.getRecordComponents())
        .map(c -> alias.getOrDefault(c.getName(), c.getName()))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<Class<?>> leafRecords(Class<?> sealed) {
    Set<Class<?>> leaves = new HashSet<>();
    for (Class<?> sub : sealed.getPermittedSubclasses()) {
      if (sub.isSealed() && sub.isInterface()) {
        leaves.addAll(leafRecords(sub));
      } else {
        leaves.add(sub);
      }
    }
    return leaves;
  }

  private static void collectKeys(JsonTree node, Set<String> keys) {
    switch (node) {
      case Obj o -> {
        keys.addAll(o.members().keySet());
        o.members().values().forEach(v -> collectKeys(v, keys));
      }
      case Arr a -> a.items().forEach(v -> collectKeys(v, keys));
      default -> {
        // a scalar holds no key
      }
    }
  }

  /** The schema file, navigated from its root; {@code $ref} is followed wherever it appears. */
  record Schema(Obj root) {

    static Schema read() throws IOException {
      String text = Files.readString(Fixtures.schemaFile(), UTF_8);
      if (!(JsonTree.parse(text) instanceof Obj root)) {
        throw new AssertionError("the schema is not a JSON object");
      }
      return new Schema(root);
    }

    Obj resolve(JsonTree node) {
      if (!(node instanceof Obj obj)) {
        throw new AssertionError("expected a schema object, got " + node);
      }
      Obj current = obj;
      for (int hops = 0; current.members().get("$ref") instanceof Str ref; hops++) {
        if (hops > 16 || !ref.value().startsWith("#")) {
          throw new AssertionError("unresolvable $ref " + ref.value());
        }
        JsonTree target = root;
        for (String token : ref.value().substring(1).split("/", -1)) {
          if (token.isEmpty()) {
            continue;
          }
          String name = token.replace("~1", "/").replace("~0", "~");
          if (!(target instanceof Obj o) || !o.members().containsKey(name)) {
            throw new AssertionError("dangling $ref " + ref.value());
          }
          target = o.members().get(name);
        }
        if (!(target instanceof Obj next)) {
          throw new AssertionError("$ref to a non-object " + ref.value());
        }
        current = next;
      }
      return current;
    }

    /** The resolved schema of a property of an object schema. */
    Obj property(Obj node, String name) {
      Obj resolved = resolve(node);
      if (!(resolved.members().get("properties") instanceof Obj properties)
          || !properties.members().containsKey(name)) {
        throw new AssertionError("no property " + name + " in " + resolved.members().keySet());
      }
      return resolve(properties.members().get(name));
    }

    /** The value schema of a map: its one {@code patternProperties} entry. */
    Obj mapValue(Obj map) {
      Obj resolved = resolve(map);
      if (resolved.members().get("patternProperties") instanceof Obj patterns
          && patterns.members().size() == 1) {
        return resolve(patterns.members().values().iterator().next());
      }
      throw new AssertionError("not a map with one patternProperties entry: " + resolved);
    }

    List<Obj> oneOf(Obj node) {
      Obj resolved = resolve(node);
      if (!(resolved.members().get("oneOf") instanceof Arr branches)) {
        throw new AssertionError("no oneOf in " + resolved.members().keySet());
      }
      return branches.items().stream().map(this::resolve).toList();
    }

    String constOf(Obj node) {
      if (!(resolve(node).members().get("const") instanceof Str value)) {
        throw new AssertionError("no string const in " + node);
      }
      return value.value();
    }

    /** Visits every subschema in a schema position, with its pointer; not the conditions. */
    void walk(BiConsumer<String, Obj> visitor) {
      walk("", root, visitor);
    }

    private void walk(String pointer, JsonTree node, BiConsumer<String, Obj> visitor) {
      if (!(node instanceof Obj obj)) {
        return;
      }
      visitor.accept(pointer, obj);
      for (Map.Entry<String, JsonTree> e : obj.members().entrySet()) {
        String at = pointer + "/" + e.getKey();
        switch (e.getKey()) {
          case "properties", "patternProperties", "$defs" -> {
            if (e.getValue() instanceof Obj members) {
              members
                  .members()
                  .forEach(
                      (k, v) ->
                          walk(at + "/" + k.replace("~", "~0").replace("/", "~1"), v, visitor));
            }
          }
          case "additionalProperties", "items", "then", "else" -> walk(at, e.getValue(), visitor);
          case "oneOf", "anyOf", "allOf" -> {
            if (e.getValue() instanceof Arr items) {
              for (int k = 0; k < items.items().size(); k++) {
                walk(at + "/" + k, items.items().get(k), visitor);
              }
            }
          }
          default -> {
            // not a schema position, or a condition (if, not), which is no object definition
          }
        }
      }
    }
  }
}
