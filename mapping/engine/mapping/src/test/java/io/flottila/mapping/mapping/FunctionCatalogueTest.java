package io.flottila.mapping.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.mapping.JsonTree.Arr;
import io.flottila.mapping.mapping.JsonTree.Bool;
import io.flottila.mapping.mapping.JsonTree.Null;
import io.flottila.mapping.mapping.JsonTree.Num;
import io.flottila.mapping.mapping.JsonTree.Obj;
import io.flottila.mapping.mapping.JsonTree.Str;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The engine-internal function catalogue (ADR-0025): {@code function-catalogue.json}, a resource of
 * {@code engine/mapping}, data that only lowering reads. It is read here as the file the module
 * packs, with the tests' own strict reader, never through the code under test. Where the resource
 * lies inside the module is the implementation's choice; that there is exactly one is not.
 */
class FunctionCatalogueTest {

  static final String FILE = "function-catalogue.json";

  /** The one {@value #FILE} among the classes and resources this module packs. */
  static byte[] catalogue() throws Exception {
    Path classes =
        Path.of(Lowering.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertThat(classes).as("engine/mapping's classes").isDirectory();
    List<Path> found;
    try (Stream<Path> files = Files.walk(classes)) {
      found = files.filter(p -> p.getFileName().toString().equals(FILE)).toList();
    }
    assertThat(found).as("%s under %s", FILE, classes).hasSize(1);
    return Files.readAllBytes(found.getFirst());
  }

  /**
   * The catalogue is published as it is packed (ADR-0026): {@code FunctionCatalogue.bytes()} is the
   * packed file, a copy of its own on each call, so a caller that writes into one changes no other.
   */
  @Test
  void thePublishedCatalogueIsThePackedFile() throws Exception {
    byte[] packed = catalogue();
    byte[] published = FunctionCatalogue.bytes();

    assertThat(published).isEqualTo(packed);
    published[0] = (byte) ' ';
    assertThat(FunctionCatalogue.bytes()).isEqualTo(packed);
  }

  // --- canonical form (ADR-0010, as ADR-0011 applies it to the catalogue)

  /**
   * ECMA-262 {@code JSON.stringify(value, null, 2)} with every object's keys sorted by UTF-16 code
   * unit, which is {@code String.compareTo}.
   */
  static String stringify(JsonTree value, String indent) {
    String inner = indent + "  ";
    return switch (value) {
      case Obj(Map<String, JsonTree> members) when members.isEmpty() -> "{}";
      case Obj(Map<String, JsonTree> members) ->
          members.keySet().stream()
              .sorted()
              .map(key -> inner + quote(key) + ": " + stringify(members.get(key), inner))
              .collect(Collectors.joining(",\n", "{\n", "\n" + indent + "}"));
      case Arr(List<JsonTree> items) when items.isEmpty() -> "[]";
      case Arr(List<JsonTree> items) ->
          items.stream()
              .map(item -> inner + stringify(item, inner))
              .collect(Collectors.joining(",\n", "[\n", "\n" + indent + "]"));
      case Str(String text) -> quote(text);
      case Num(String text) -> {
        // JSON.stringify writes an integer as its digits; the catalogue needs no other number
        assertThat(text).as("a number in the catalogue").matches("-?(0|[1-9][0-9]*)");
        yield text;
      }
      case Bool(boolean b) -> Boolean.toString(b);
      case Null() -> "null";
    };
  }

  /** ECMA-262 QuoteJSONString: short escapes, {@code \\u00xx} below U+0020 and lone surrogates. */
  static String quote(String s) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\b' -> out.append("\\b");
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\f' -> out.append("\\f");
        case '\r' -> out.append("\\r");
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        default -> {
          boolean lone =
              Character.isHighSurrogate(c)
                  ? i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))
                  : Character.isLowSurrogate(c)
                      && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
          if (c < 0x20 || lone) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  /**
   * The pointers of the empty arrays and objects in {@code value}, which the canonical form leaves
   * out; an empty table is a value, so it may stand as a parameter's {@code default}.
   */
  static List<String> emptyCollections(JsonTree value, String pointer, String member) {
    List<String> empty = new ArrayList<>();
    switch (value) {
      case Obj(Map<String, JsonTree> members) -> {
        if (members.isEmpty() && !member.equals("default")) {
          empty.add(pointer);
        }
        members.forEach((k, v) -> empty.addAll(emptyCollections(v, pointer + "/" + k, k)));
      }
      case Arr(List<JsonTree> items) -> {
        if (items.isEmpty()) {
          empty.add(pointer);
        }
        for (int i = 0; i < items.size(); i++) {
          empty.addAll(emptyCollections(items.get(i), pointer + "/" + i, ""));
        }
      }
      case Str _, Num _, Bool _, Null _ -> {}
    }
    return empty;
  }

  @Test
  void theCatalogueIsCanonical() throws Exception {
    byte[] bytes = catalogue();
    // strict UTF-8; a BOM is no JSON whitespace, so the strict reader refuses it
    String text = Fixtures.strictUtf8(bytes);
    JsonTree tree = JsonTree.parse(text);

    // keyed by function name (ADR-0011), and not empty: removeContexts is in it
    assertThat(tree).isInstanceOfSatisfying(Obj.class, o -> assertThat(o.members()).isNotEmpty());
    assertThat(emptyCollections(tree, "", "")).as("empty arrays and objects").isEmpty();
    // sorted keys, JSON.stringify's two-space indent, LF line ends and a final LF, no BOM
    Fixtures.assertBytes(stringify(tree, "") + "\n", bytes);
  }

  // --- parameters (ADR-0025: every param has a default, so lowering makes the IR total)

  /**
   * What is wrong with the params of each function: a param without a {@code default}, a default of
   * another type than the param's, or one outside the param's {@code values}. A function without
   * params leaves {@code params} out, as the canonical form does with an empty array.
   */
  static List<String> defaultProblems(JsonTree catalogue) {
    List<String> problems = new ArrayList<>();
    if (!(catalogue instanceof Obj(Map<String, JsonTree> functions))) {
      return List.of("the catalogue is no object");
    }
    functions.forEach(
        (name, entry) -> {
          if (!(entry instanceof Obj(Map<String, JsonTree> members))) {
            problems.add(name + ": no object");
            return;
          }
          JsonTree params = members.get("params");
          if (params == null) {
            return;
          }
          if (!(params instanceof Arr(List<JsonTree> items))) {
            problems.add(name + ": params is no array");
            return;
          }
          for (int i = 0; i < items.size(); i++) {
            problems.addAll(paramProblems(name + " params[" + i + "]", items.get(i)));
          }
        });
    return problems;
  }

  private static List<String> paramProblems(String where, JsonTree param) {
    if (!(param instanceof Obj(Map<String, JsonTree> members))) {
      return List.of(where + ": no object");
    }
    JsonTree fallback = members.get("default");
    if (fallback == null) {
      return List.of(where + ": no default");
    }
    List<String> problems = new ArrayList<>();
    JsonTree type = members.get("type");
    if (type instanceof Str(String t) && t.equals("string")) {
      if (!(fallback instanceof Str)) {
        problems.add(where + ": the default of a string param is no string");
      }
    } else if (type instanceof Str(String t) && t.equals("table")) {
      if (!(fallback instanceof Obj(Map<String, JsonTree> rows)
          && rows.values().stream().allMatch(Str.class::isInstance))) {
        problems.add(where + ": the default of a table param is no table of strings");
      }
    } else {
      problems.add(where + ": the type is neither string nor table");
    }
    JsonTree values = members.get("values");
    if (values != null
        && !(values instanceof Arr(List<JsonTree> allowed) && allowed.contains(fallback))) {
      problems.add(where + ": the default is not one of its values");
    }
    return problems;
  }

  @Test
  void everyParamHasADefault() throws Exception {
    // witness: the check reports each defect in a catalogue that has them
    JsonTree broken =
        JsonTree.parse(
            """
            {"f": {"params": [{"name": "mode", "type": "string"},
                              {"name": "order", "type": "string", "default": "up",
                               "values": ["ascending", "descending"]},
                              {"name": "rows", "type": "table", "default": "x"},
                              {"name": "ok", "type": "string", "default": "b",
                               "values": ["a", "b"]}]},
             "g": {"params": [{"name": "value", "type": "string", "default": {}}]},
             "h": {"inputs": [{"name": "in"}]}}
            """);
    assertThat(defaultProblems(broken))
        .containsExactly(
            "f params[0]: no default",
            "f params[1]: the default is not one of its values",
            "f params[2]: the default of a table param is no table of strings",
            "g params[0]: the default of a string param is no string");

    JsonTree catalogue = JsonTree.parse(Fixtures.strictUtf8(catalogue()));

    assertThat(catalogue)
        .isInstanceOfSatisfying(Obj.class, o -> assertThat(o.members()).isNotEmpty());
    assertThat(defaultProblems(catalogue)).isEmpty();
  }

  // --- function families (ADR-0025 "Families", F-15): one canonical file per family, which the
  // build merges into the packed catalogue, giving each entry its family

  static final String FAMILY_DIR = "engine/mapping/src/main/catalogue";

  /**
   * The packed catalogue of main at 9c6fd1a, before the split, re-pinned once by #71 (ADR-0026
   * Consequences): each of the eleven entries gains {@code fidelity: {level: assumed}}, every other
   * byte is main's. #218 re-pins the fidelity of collapseContexts and splitByValue, to {@code
   * exact}, and of sort and sortByKey, to the deviation of F-43 as F-47 corrects its text, alone:
   * their last assumed cases are observed (F-47), and ADR-0026 sets the level of such a function.
   * The replaceValue entry is re-pinned by #216 (F-45): an observed rule changes replaceValue's
   * execution kind, to {@code context}, and its fidelity follows its cases, now {@code exact}. #219
   * re-pins the fidelity of exists, mapWithDefault and useOneAsMany, to {@code exact}, alone: their
   * last assumed cases are observed (F-48). #214 re-pins formatByExample and removeContexts, and
   * #221 re-pins createIf, to {@code exact}, alone each. The assertions below are #69's, unchanged.
   */
  static final String MAIN_CATALOGUE =
      "engine/mapping/src/test/resources/pinned-71/function-catalogue.json";

  /** Every {@code *.json} of {@value #FAMILY_DIR}, by its family: the file's stem. */
  static SortedMap<String, Path> familyFiles() throws IOException {
    Path dir = Fixtures.repoRoot().resolve(FAMILY_DIR);
    assertThat(dir).as("the family files' directory").isDirectory();
    SortedMap<String, Path> families = new TreeMap<>();
    List<Path> files;
    try (Stream<Path> listing = Files.list(dir)) {
      files = listing.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
    }
    for (Path file : files) {
      String name = file.getFileName().toString();
      families.put(name.substring(0, name.length() - ".json".length()), file);
    }
    // today's eleven functions form the family context (ADR-0025)
    assertThat(families).as("the family files in %s", dir).containsKey("context");
    return families;
  }

  /** The functions of a catalogue or a family file, keyed by name. */
  static Map<String, JsonTree> functions(JsonTree tree, String what) {
    if (!(tree instanceof Obj(Map<String, JsonTree> functions))) {
      throw new AssertionError(what + " is no JSON object");
    }
    return functions;
  }

  /** The members of the entry {@code name}. */
  static Map<String, JsonTree> members(JsonTree entry, String name) {
    if (!(entry instanceof Obj(Map<String, JsonTree> members))) {
      throw new AssertionError("the entry " + name + " is no JSON object");
    }
    return members;
  }

  @Test
  void everyFamilyFileIsCanonical() throws Exception {
    // witness: a file whose keys are out of order is not its own canonical form
    String unsorted = "{\n  \"b\": \"1\",\n  \"a\": \"2\"\n}\n";
    assertThat(stringify(JsonTree.parse(unsorted), "") + "\n").isNotEqualTo(unsorted);

    for (Map.Entry<String, Path> family : familyFiles().entrySet()) {
      byte[] bytes = Files.readAllBytes(family.getValue());
      String text = Fixtures.strictUtf8(bytes);
      JsonTree tree = JsonTree.parse(text);
      String what = "the family file " + family.getKey() + ".json";

      // keyed by function name, as the catalogue it merges into (ADR-0011)
      assertThat(functions(tree, what)).as(what).isNotEmpty();
      assertThat(emptyCollections(tree, "", ""))
          .as("empty arrays and objects of %s", what)
          .isEmpty();
      // sorted keys, JSON.stringify's two-space indent, LF line ends and a final LF, no BOM
      assertThat(text).as(what).isEqualTo(stringify(tree, "") + "\n");
      assertThat(bytes).as(what).isEqualTo(Fixtures.utf8(stringify(tree, "") + "\n"));
    }
  }

  /**
   * The build merges the family files: every entry of every family file, given its {@code family}
   * (the file's stem), in one catalogue in canonical form, whose key order comes from the canonical
   * form and never from the order the files are listed in.
   */
  @Test
  void thePackedCatalogueIsTheMergeOfItsFamilyFiles() throws Exception {
    Map<String, JsonTree> merged = new LinkedHashMap<>();
    List<String> inTwoFamilies = new ArrayList<>();
    for (Map.Entry<String, Path> family : familyFiles().entrySet()) {
      String what = "the family file " + family.getKey() + ".json";
      JsonTree tree = JsonTree.parse(Fixtures.strictUtf8(Files.readAllBytes(family.getValue())));
      for (Map.Entry<String, JsonTree> function : functions(tree, what).entrySet()) {
        Map<String, JsonTree> entry =
            new LinkedHashMap<>(members(function.getValue(), function.getKey()));
        entry.put("family", new Str(family.getKey()));
        if (merged.put(function.getKey(), new Obj(entry)) != null) {
          inTwoFamilies.add(function.getKey());
        }
      }
    }
    assertThat(inTwoFamilies).as("names in two family files").isEmpty();

    Fixtures.assertBytes(stringify(new Obj(merged), "") + "\n", catalogue());
  }

  /**
   * ADR-0026 re-pins main's copy once (see {@link #MAIN_CATALOGUE}); the assertions are #69's.
   *
   * <p>The split changes no entry (ADR-0025 "Families"): today's eleven functions form the family
   * {@code context}, and the packed entries of that family, their {@code family} taken out, are
   * main's catalogue before the split, byte for byte. Entries of the families spec 3 adds leave it
   * as it is.
   */
  @Test
  void thePackedCatalogueOfTheContextFamilyIsMainsByteForByte() throws Exception {
    byte[] main = Files.readAllBytes(Fixtures.repoRoot().resolve(MAIN_CATALOGUE));
    Map<String, JsonTree> mains =
        functions(JsonTree.parse(Fixtures.strictUtf8(main)), "main's catalogue");
    // witness: main's copy is the catalogue of the eleven functions spec 2 left
    assertThat(mains).hasSize(11).containsKeys("collapseContexts", "replaceValue", "useOneAsMany");

    Map<String, JsonTree> packed =
        functions(JsonTree.parse(Fixtures.strictUtf8(catalogue())), "the packed catalogue");
    Map<String, JsonTree> context = new LinkedHashMap<>();
    List<String> withoutFamily = new ArrayList<>();
    for (Map.Entry<String, JsonTree> function : packed.entrySet()) {
      Map<String, JsonTree> entry =
          new LinkedHashMap<>(members(function.getValue(), function.getKey()));
      JsonTree family = entry.remove("family");
      if (!(family instanceof Str)) {
        withoutFamily.add(function.getKey());
      } else if (family.equals(new Str("context"))) {
        context.put(function.getKey(), new Obj(entry));
      }
    }
    assertThat(withoutFamily).as("entries without a string family").isEmpty();

    Fixtures.assertBytes(
        Fixtures.strictUtf8(main), Fixtures.utf8(stringify(new Obj(context), "") + "\n"));
  }

  // --- generating functions (#217, F-46): a queue function whose result is one value that goes
  // into every parent occurrence, as a constant's does

  /**
   * What is wrong with each function's {@code generating}: anything but {@code true}, or {@code
   * true} on a function whose execution kind is not {@code queue}.
   */
  static List<String> generatingProblems(JsonTree catalogue) {
    List<String> problems = new ArrayList<>();
    functions(catalogue, "the catalogue")
        .forEach(
            (name, entry) -> {
              Map<String, JsonTree> members = members(entry, name);
              JsonTree generating = members.get("generating");
              if (generating == null) {
                return;
              }
              if (!generating.equals(new Bool(true))) {
                problems.add(name + ": generating is not true");
              }
              if (!new Str("queue").equals(members.get("executionKind"))) {
                problems.add(name + ": generating, but no queue function");
              }
            });
    return problems;
  }

  @Test
  void onlyAQueueFunctionIsGenerating() throws Exception {
    // witness: the check reports each defect in a catalogue that has them
    JsonTree broken =
        JsonTree.parse(
            """
            {"f": {"executionKind": "context", "generating": true},
             "g": {"executionKind": "queue", "generating": false},
             "h": {"executionKind": "queue", "generating": true},
             "k": {"executionKind": "queue"}}
            """);
    assertThat(generatingProblems(broken))
        .containsExactlyInAnyOrder(
            "f: generating, but no queue function", "g: generating is not true");

    Map<String, JsonTree> packed =
        functions(JsonTree.parse(Fixtures.strictUtf8(catalogue())), "the packed catalogue");
    assertThat(generatingProblems(new Obj(packed))).isEmpty();
    // copyValue's result is generating (F-46)
    assertThat(members(packed.get("copyValue"), "copyValue"))
        .containsEntry("executionKind", new Str("queue"))
        .containsEntry("generating", new Bool(true));

    // lowering reads it as the catalogue holds it; constant's single value is no generating block
    for (Map.Entry<String, JsonTree> function : packed.entrySet()) {
      boolean generating =
          new Bool(true).equals(members(function.getValue(), function.getKey()).get("generating"));
      assertThat(FunctionCatalogue.entry(function.getKey()).orElseThrow().generating())
          .as(function.getKey())
          .isEqualTo(generating);
    }
    assertThat(FunctionCatalogue.entry("copyValue").orElseThrow().generating()).isTrue();
    assertThat(FunctionCatalogue.entry("constant").orElseThrow().generating()).isFalse();
  }
}
