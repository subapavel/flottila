package io.flottila.mapping.structure.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.ValueType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * JSON Schema 2020-12 as a structure (#97, ADR-0008 "JSON Schema and WSDL", ADR-0007), at the
 * reader's exported API: one row per rule and per load-time refusal, over the fixtures in {@code
 * corpus/structures/json-schema/}. Every file is read through a test-scope resolver. A $ref cycle
 * between files or types must end, so a read that loops fails on the timeout, in its own thread
 * because a loop does not see the interrupt.
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JsonSchemaReaderTest {

  private static final Path FIXTURES =
      Path.of(System.getProperty("flottila.corpus"), "structures", "json-schema");

  /** The deepest field path {@link #tree} lists: a recursive type is never expanded further. */
  private static final int DEPTH = 4;

  private static Structure read(Path directory, String... bundle) throws StructureLoadException {
    return new StructureReader(DirectoryResolver.over(directory))
        .read(new SchemaBundle(List.of(bundle)), new JsonRoot());
  }

  static Stream<Arguments> rules() {
    return Stream.of(
        argumentSet(
            "properties keep their declared order, required gives minimum 1, and type and"
                + " format give the value type",
            "properties.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/zeta 1..1 xs:string",
                "/$/alpha 0..1 xs:date",
                "/$/count 1..1 xs:integer",
                "/$/amount 0..1 xs:decimal",
                "/$/urgent 0..1 xs:boolean",
                "/$/pickup 0..1 xs:time",
                "/$/created 0..1 xs:dateTime",
                "/$/email 0..1 xs:string",
                "/$/anything 0..1 xs:string")),
        argumentSet(
            "an absent $schema counts as 2020-12",
            "no-dollar-schema.schema.json",
            List.of("/$ 1..1 structure", "/$/id 0..1 xs:integer")),
        argumentSet(
            "an array property is one repeated field with minItems and maxItems, marked as one,"
                + " items without type and an array without items too (#118)",
            "arrays.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/lines 1..5 structure array",
                "/$/lines/sku 1..1 xs:string",
                "/$/lines/qty 0..1 xs:integer",
                "/$/tags 0..unbounded xs:string array",
                "/$/notes 0..unbounded xs:date array",
                "/$/untyped 0..unbounded xs:boolean array",
                "/$/bare 0..unbounded xs:string array")),
        argumentSet(
            "an array property is marked whatever its maxItems: 1, 0, or 1 with a null in its type"
                + " list; a plain property and the fields below an array's items are not (#118)",
            "short-arrays.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/one 0..1 xs:string array",
                "/$/none 0..0 xs:string array",
                "/$/maybe 0..1 xs:string nillable array",
                "/$/objs 0..1 structure array",
                "/$/objs/sku 0..1 xs:string",
                "/$/plain 0..1 xs:string")),
        argumentSet(
            "$ref and $defs resolve through named types, by pointer and by $anchor",
            "refs.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/billTo 0..1 structure",
                "/$/billTo/city 1..1 xs:string",
                "/$/shipTo 0..1 structure",
                "/$/shipTo/city 1..1 xs:string",
                "/$/category 0..1 structure",
                "/$/category/name 0..1 xs:string",
                "/$/category/parent 0..1 structure",
                "/$/category/parent/name 0..1 xs:string",
                "/$/category/parent/parent 0..1 structure",
                "/$/total 0..1 xs:decimal",
                "/$/home 0..1 structure",
                "/$/home/street 0..1 xs:string")),
        argumentSet(
            "allOf merges its subschemas, even a lone one, and a $ref beside properties merges too",
            "all-of.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/id 1..1 xs:string",
                "/$/audit 0..1 structure",
                "/$/audit/by 0..1 xs:string",
                "/$/audit/createdAt 0..1 xs:dateTime",
                "/$/meta 0..1 structure",
                "/$/meta/createdAt 0..1 xs:dateTime",
                "/$/createdAt 0..1 xs:dateTime",
                "/$/note 1..1 xs:string")),
        argumentSet(
            "oneOf and anyOf give the union of their properties, each with minimum 0, even one"
                + " required beside them",
            "one-of.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/kind 1..1 xs:string",
                "/$/value 0..1 xs:string",
                "/$/card 0..1 xs:string",
                "/$/iban 0..1 xs:string",
                "/$/cheque 0..1 xs:string",
                "/$/memo 0..1 xs:string")),
        argumentSet(
            "a type list [T, null] marks the field nillable",
            "nullable.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/price 1..1 xs:decimal nillable",
                "/$/code 0..1 xs:string",
                "/$/address 0..1 structure nillable",
                "/$/address/city 0..1 xs:string",
                "/$/dates 0..unbounded xs:date nillable array",
                "/$/nothing 0..1 xs:string nillable")),
        argumentSet(
            "field paths start with $ and percent-encode % / @ { }",
            "names.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/a%2Fb 0..1 xs:string",
                "/$/50%25 0..1 xs:string",
                "/$/%40id 0..1 xs:string",
                "/$/%7Bx%7D 0..1 xs:string",
                "/$/$ 0..1 xs:string",
                "/$/$ref 0..1 xs:string",
                "/$/$dynamicRef 0..1 xs:string",
                "/$/plain name 0..1 xs:string")),
        argumentSet(
            "additionalProperties, patternProperties, if/then/else and not are ignored",
            "ignored.schema.json",
            List.of("/$ 1..1 structure", "/$/id 0..1 xs:string")),
        argumentSet(
            "a schema that refers back to itself through allOf, anyOf or $ref alone ends",
            "cycle.schema.json",
            List.of(
                "/$ 1..1 structure",
                "/$/node 0..1 structure",
                "/$/node/label 0..1 xs:string",
                "/$/node/next 0..1 structure",
                "/$/node/next/label 0..1 xs:string",
                "/$/node/next/next 0..1 structure",
                "/$/loop 0..1 xs:string")));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("rules")
  void aJsonSchemaRuleGivesItsFields(String fixture, List<String> fields) throws Exception {
    Structure structure = read(FIXTURES, fixture);

    assertThat(tree(structure)).containsExactlyElementsOf(fields);
    assertThat(read(FIXTURES, fixture).fingerprint())
        .as("a second load")
        .isEqualTo(structure.fingerprint());
  }

  @Test
  void everyFieldIsAJsonPropertyWithoutANamespaceUnderTheRootDollar() throws Exception {
    Structure structure = read(FIXTURES, "properties.schema.json");

    List<FieldPath> paths = new ArrayList<>(structure.children(structure.rootPath()));
    paths.add(structure.rootPath());
    for (FieldPath path : paths) {
      FieldPath.Step step = path.steps().getLast();
      assertThat(step.kind().name()).as("%s", path).isEqualTo("JSON_PROPERTY");
      assertThat(step.namespace()).as("%s", path).isEmpty();
    }
    assertThat(structure.root().step().localName()).isEqualTo("$");
  }

  /** ADR-0008: a named type is one structure type, however often and however deep it is used. */
  @Test
  void aNamedTypeIsOneStructureTypeAndARecursiveOneRefersToItself() throws Exception {
    Structure structure = read(FIXTURES, "refs.schema.json");

    assertThat(type(structure, "/$/billTo"))
        .isEqualTo(type(structure, "/$/shipTo"))
        .isEqualTo("refs.schema.json#/$defs/Address");
    assertThat(type(structure, "/$/category/parent"))
        .isEqualTo(type(structure, "/$/category"))
        .isEqualTo("refs.schema.json#/$defs/Category");
    assertThat(structure.root().type()).contains("refs.schema.json#");
    assertThat(structure.types())
        .containsOnlyKeys(
            "refs.schema.json#",
            "refs.schema.json#/$defs/Address",
            "refs.schema.json#/$defs/Category",
            "refs.schema.json#/$defs/Home");
  }

  /**
   * ADR-0008 "closed bundle": a $ref to another bundle file is mapped to the bundle's reference by
   * text, and that file is read through the resolver; nothing else is read.
   */
  @Test
  void aRefToASecondBundleFileIsReadThroughTheResolver() throws Exception {
    Path bundle = FIXTURES.resolve("bundle");
    MemoryResolver resolver =
        new MemoryResolver()
            .file("order.schema.json", Files.readAllBytes(bundle.resolve("order.schema.json")))
            .file(
                "defs/address.schema.json",
                Files.readAllBytes(bundle.resolve("defs/address.schema.json")));

    Structure structure =
        new StructureReader(resolver)
            .read(
                new SchemaBundle(List.of("order.schema.json", "defs/address.schema.json")),
                new JsonRoot());

    assertThat(tree(structure))
        .containsExactly(
            "/$ 1..1 structure",
            "/$/id 0..1 xs:string",
            "/$/shipTo 0..1 structure",
            "/$/shipTo/city 0..1 xs:string",
            "/$/shipTo/country 0..1 xs:string",
            "/$/country 0..1 xs:string");
    assertThat(type(structure, "/$/shipTo")).isEqualTo("defs/address.schema.json#/$defs/Address");
    assertThat(resolver.calls()).containsExactly("order.schema.json", "defs/address.schema.json");
  }

  /**
   * The bundle's own spelling, {@code ../} and all, is what the resolver is asked for; a $ref that
   * climbs above the referring file's directory and further still names its file.
   */
  @Test
  void bundleReferencesAboveTheBaseAndWithASpaceAreReadAsSpelled() throws Exception {
    MemoryResolver resolver =
        new MemoryResolver()
            .file(
                "../json/my order.schema.json",
                """
                {"type": "object",
                 "properties": {"line": {"$ref": "../../shared/line.schema.json"}}}
                """)
            .file(
                "../../shared/line.schema.json",
                """
                {"type": "object", "properties": {"sku": {"type": "string"}}}
                """);

    Structure structure =
        new StructureReader(resolver)
            .read(
                new SchemaBundle(
                    List.of("../json/my order.schema.json", "../../shared/line.schema.json")),
                new JsonRoot());

    assertThat(tree(structure))
        .containsExactly(
            "/$ 1..1 structure", "/$/line 0..1 structure", "/$/line/sku 0..1 xs:string");
    assertThat(resolver.calls())
        .containsExactly("../json/my order.schema.json", "../../shared/line.schema.json");
  }

  /**
   * ADR-0008: a $ref to a file the bundle does not list is {@code schema-reference-outside-bundle}
   * at the $ref, naming the missing reference; the file is never asked for.
   */
  @ParameterizedTest(name = "{0} in a bundle of {1} names {4}")
  @CsvSource({
    "outside.schema.json, outside.schema.json, 5, 15, party.schema.json",
    "order.schema.json,   order.schema.json,   7, 16, defs/address.schema.json",
  })
  void aRefOutsideTheBundleIsRefusedAtTheRef(
      String file, String bundle, int line, int column, String missing) {
    MemoryResolver resolver = new MemoryResolver();
    assertThatThrownBy(
            () -> {
              resolver.file(file, Files.readAllBytes(FIXTURES.resolve("bundle").resolve(file)));
              new StructureReader(resolver).read(new SchemaBundle(List.of(bundle)), new JsonRoot());
            })
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-reference-outside-bundle");
              assertThat(e.file()).isEqualTo(file);
              assertThat(e.line()).isEqualTo(line);
              assertThat(e.column()).isEqualTo(column);
              assertThat(e.getMessage()).contains("\"" + missing + "\"");
            });
    assertThat(resolver.calls()).containsExactly(file);
  }

  static Stream<Arguments> ids() {
    return Stream.of(
        argumentSet(
            "a relative $ref resolves against the bundle reference, whatever $id says",
            List.of("relative.schema.json", "defs/address.schema.json"),
            "/$/shipTo",
            List.of(
                "/$ 1..1 structure",
                "/$/shipTo 0..1 structure",
                "/$/shipTo/city 1..1 xs:string",
                "/$/shipTo/country 0..1 xs:string",
                "/$/shipTo/home 0..1 structure",
                "/$/shipTo/home/street 0..1 xs:string")),
        argumentSet(
            "an absolute $ref names the bundle file whose root $id it is",
            List.of("absolute.schema.json", "defs/address.schema.json"),
            "/$/billTo",
            List.of(
                "/$ 1..1 structure",
                "/$/billTo 0..1 structure",
                "/$/billTo/city 1..1 xs:string",
                "/$/billTo/country 0..1 xs:string",
                "/$/billTo/home 0..1 structure",
                "/$/billTo/home/street 0..1 xs:string",
                "/$/home 0..1 structure",
                "/$/home/street 0..1 xs:string")));
  }

  /**
   * ADR-0008 (#97): the file part of a relative $ref resolves against the referring file's bundle
   * reference, whatever its $id says; an absolute $ref names the one bundle file whose root $id it
   * is. A named type's id is its bundle file and pointer, an anchor's too.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("ids")
  void aRefNamesItsBundleFileByReferenceOrByRootId(
      List<String> bundle, String address, List<String> fields) throws Exception {
    MemoryResolver resolver = idsResolver();

    Structure structure =
        new StructureReader(resolver).read(new SchemaBundle(bundle), new JsonRoot());

    assertThat(tree(structure)).containsExactlyElementsOf(fields);
    assertThat(type(structure, address)).isEqualTo("defs/address.schema.json#/$defs/Address");
    assertThat(type(structure, address + "/home"))
        .isEqualTo("defs/address.schema.json#/$defs/Home");
    assertThat(resolver.calls()).containsExactlyElementsOf(bundle);
  }

  /** ADR-0008 (#97): an absolute $ref that no bundle file's root $id matches is outside it. */
  @Test
  void anAbsoluteRefNoRootIdMatchesIsRefusedAtTheRef() throws Exception {
    MemoryResolver resolver = idsResolver();

    assertThatThrownBy(
            () ->
                new StructureReader(resolver)
                    .read(
                        new SchemaBundle(
                            List.of("unknown.schema.json", "defs/address.schema.json")),
                        new JsonRoot()))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-reference-outside-bundle");
              assertThat(e.file()).isEqualTo("unknown.schema.json");
              assertThat(e.line()).isEqualTo(6);
              assertThat(e.column()).isEqualTo(15);
              assertThat(e.getMessage()).contains("\"https://example.com/shared/party.json\"");
            });
  }

  /** The files of {@code ids/}, by their reference under it. */
  private static MemoryResolver idsResolver() throws Exception {
    Path ids = FIXTURES.resolve("ids");
    MemoryResolver resolver = new MemoryResolver();
    for (String file :
        List.of(
            "relative.schema.json",
            "absolute.schema.json",
            "unknown.schema.json",
            "defs/address.schema.json")) {
      resolver.file(file, Files.readAllBytes(ids.resolve(file)));
    }
    return resolver;
  }

  static Stream<Arguments> everyError() {
    return Stream.of(
        argumentSet(
            "two $refs outside the bundle in one file, in document order",
            List.of("errors/two-outside.schema.json"),
            List.of(
                "schema-reference-outside-bundle errors/two-outside.schema.json 5:15",
                "schema-reference-outside-bundle errors/two-outside.schema.json 6:14")),
        argumentSet(
            "a $ref outside the bundle in each of two files, in bundle order",
            List.of("errors/first.schema.json", "errors/second.schema.json"),
            List.of(
                "schema-reference-outside-bundle errors/first.schema.json 6:15",
                "schema-reference-outside-bundle errors/second.schema.json 5:14")),
        argumentSet(
            "the same two files listed the other way round",
            List.of("errors/second.schema.json", "errors/first.schema.json"),
            List.of(
                "schema-reference-outside-bundle errors/second.schema.json 5:14",
                "schema-reference-outside-bundle errors/first.schema.json 6:15")),
        argumentSet(
            "a root schema the resolver cannot read, then the errors of the next file",
            List.of("errors/missing.schema.json", "errors/two-outside.schema.json"),
            List.of(
                "schema-unreadable errors/missing.schema.json 1:1",
                "schema-reference-outside-bundle errors/two-outside.schema.json 5:15",
                "schema-reference-outside-bundle errors/two-outside.schema.json 6:14")),
        argumentSet(
            "json-sKema's problems beside a $ref outside the bundle and a $dynamicRef, which are"
                + " dropped for the load",
            List.of("errors/mixed.schema.json"),
            List.of(
                "schema-reference-outside-bundle errors/mixed.schema.json 5:15",
                "schema-invalid errors/mixed.schema.json 6:15",
                "schema-construct-unsupported errors/mixed.schema.json 7:14",
                "schema-invalid errors/mixed.schema.json 8:15")),
        argumentSet(
            "the walk's refusals, a shared one once",
            List.of("errors/walk.schema.json"),
            List.of(
                "schema-invalid errors/walk.schema.json 7:30",
                "schema-construct-unsupported errors/walk.schema.json 10:34",
                "schema-construct-unsupported errors/walk.schema.json 12:33")));
  }

  /**
   * ADR-0008: one load reports every load-time error of the bundle, files in bundle order and each
   * file in document order.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("everyError")
  void oneLoadReportsEveryLoadError(List<String> bundle, List<String> errors) {
    assertThatThrownBy(() -> read(FIXTURES, bundle.toArray(String[]::new)))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e ->
                assertThat(
                        e.errors().stream()
                            .map(
                                error ->
                                    error.code()
                                        + " "
                                        + error.file()
                                        + " "
                                        + error.line()
                                        + ":"
                                        + error.column())
                            .toList())
                    .containsExactlyElementsOf(errors));
  }

  /** ADR-0008's load-time refusals, and schemas that are not well-formed or not valid. */
  @ParameterizedTest(name = "{0} is refused with {1} at {2}:{3}")
  @CsvSource({
    "draft-07,         schema-construct-unsupported, 2, 3",
    "dynamic-ref,      schema-construct-unsupported, 4, 45",
    "root-not-object,  schema-construct-unsupported, 1, 1",
    "root-string,      schema-construct-unsupported, 1, 1",
    "root-array,       schema-construct-unsupported, 2, 3",
    "array-of-arrays,  schema-construct-unsupported, 4, 33",
    // the second declaration of the merged property, at its schema
    "two-shapes,       schema-construct-unsupported, 5, 31",
    "not-well-formed,  schema-not-well-formed,       3, 3",
    "invalid-type,     schema-invalid,               4, 12",
    "missing-pointer,  schema-invalid,               5, 15",
    // two problems: the first in document order
    "two-problems,     schema-invalid,               4, 15",
    "missing-anchor,   schema-invalid,               4, 14",
    "type-list-number, schema-invalid,               4, 32",
  })
  void aRefusedSchemaIsRefusedWhereItStands(String fixture, String code, int line, int column) {
    String file = "refused/" + fixture + ".schema.json";

    assertThatThrownBy(() -> read(FIXTURES, file))
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
   * #118 guard (ADR-0008 "JSON Schema and WSDL"): an array property is marked whatever its
   * maxItems, and an array of arrays stays refused at its outer {@code items}, with maxItems 1 on
   * both arrays too.
   */
  @Test
  void anArrayOfArraysWithMaxItemsOneIsStillRefusedAtItems() {
    MemoryResolver resolver =
        new MemoryResolver()
            .file(
                "pair.schema.json",
                """
                {
                  "type": "object",
                  "properties": {
                    "matrix": {
                      "type": "array",
                      "maxItems": 1,
                      "items": {"type": "array", "maxItems": 1, "items": {"type": "number"}}
                    }
                  }
                }
                """);

    assertThatThrownBy(
            () ->
                new StructureReader(resolver)
                    .read(new SchemaBundle(List.of("pair.schema.json")), new JsonRoot()))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-construct-unsupported");
              assertThat(e.file()).isEqualTo("pair.schema.json");
              assertThat(e.line()).isEqualTo(7);
              assertThat(e.column()).isEqualTo(7);
            });
  }

  /** A schema whose required array {@code tags} has {@code counts} at line 5, column 31. */
  private static Structure readTags(String counts) throws StructureLoadException {
    String schema =
        """
        {
          "type": "object",
          "required": ["tags"],
          "properties": {
            "tags": {"type": "array", %s, "items": {"type": "string"}}
          }
        }
        """
            .formatted(counts);
    return new StructureReader(new MemoryResolver().file("tags.schema.json", schema))
        .read(new SchemaBundle(List.of("tags.schema.json")), new JsonRoot());
  }

  /** Each load error of a refused read, as code, file, line and column. */
  private static List<String> errors(StructureLoadException e) {
    return e.errors().stream()
        .map(error -> error.code() + " " + error.file() + " " + error.line() + ":" + error.column())
        .toList();
  }

  static Stream<Arguments> invalidItemCounts() {
    return Stream.of(
        argumentSet(
            "a required array whose minItems 1 is greater than its maxItems 0",
            "\"minItems\": 1, \"maxItems\": 0",
            31),
        argumentSet(
            "an array whose minItems 3 is greater than its maxItems 2",
            "\"minItems\": 3, \"maxItems\": 2",
            31),
        argumentSet(
            "a maxItems above 2^64, which a long would wrap",
            "\"minItems\": 0, \"maxItems\": 18446744073709551617",
            46),
        argumentSet(
            "a minItems of 19 digits, as XSD refuses a maxOccurs of 19 digits",
            "\"minItems\": 1000000000000000000",
            31),
        argumentSet("a negative minItems", "\"minItems\": -1", 31),
        argumentSet("a maxItems that is no integer", "\"maxItems\": 1.5", 31));
  }

  /**
   * A minItems or maxItems that is no count of an XSD occurrence, at most 18 digits, or a minItems
   * greater than its maxItems, is schema-invalid at that keyword, as XSD refuses such a minOccurs
   * or maxOccurs (XsdSubsetCheck): no occurrence wraps, turns negative or has its minimum above its
   * maximum (#89).
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("invalidItemCounts")
  void anItemCountNoOccurrenceHoldsIsSchemaInvalidAtItsKeyword(String counts, int column) {
    assertThatThrownBy(() -> readTags(counts))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e ->
                assertThat(errors(e))
                    .containsExactly("schema-invalid tags.schema.json 5:" + column));
  }

  /** The witness: 18 digits and an integer written with a fraction of zero are counts. */
  @Test
  void anItemCountOfEighteenDigitsOrAWholeFractionIsACount() throws Exception {
    assertThat(tree(readTags("\"minItems\": 2.0, \"maxItems\": 999999999999999999")))
        .containsExactly("/$ 1..1 structure", "/$/tags 2..999999999999999999 xs:string array");
  }

  /**
   * ADR-0008: an absolute $ref names the one bundle file whose root $id it is, so a second bundle
   * file with the same root $id is schema-invalid at its $id, naming the first file (#89).
   */
  @Test
  void aRootIdTwiceInTheBundleIsSchemaInvalidAtTheSecond() throws Exception {
    MemoryResolver resolver =
        new MemoryResolver()
            .file(
                "root.schema.json",
                """
                {
                  "type": "object",
                  "properties": {"a": {"$ref": "https://example.com/shared.json#/$defs/A"}}
                }
                """)
            .file(
                "one.schema.json",
                """
                {
                  "$id": "https://example.com/shared.json",
                  "$defs": {"A": {"type": "string"}}
                }
                """)
            .file(
                "two.schema.json",
                """
                {
                  "$defs": {"A": {"type": "integer"}},
                  "$id": "https://example.com/shared.json"
                }
                """);
    StructureReader reader = new StructureReader(resolver);

    // witness: with the one file, the $ref names it
    assertThat(
            tree(
                reader.read(
                    new SchemaBundle(List.of("root.schema.json", "one.schema.json")),
                    new JsonRoot())))
        .containsExactly("/$ 1..1 structure", "/$/a 0..1 xs:string");
    assertThatThrownBy(
            () ->
                reader.read(
                    new SchemaBundle(
                        List.of("root.schema.json", "one.schema.json", "two.schema.json")),
                    new JsonRoot()))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(errors(e)).containsExactly("schema-invalid two.schema.json 3:3");
              assertThat(e.getMessage()).contains("one.schema.json");
            });
  }

  @Test
  void anXsdReadUnderTheJsonRootIsNotWellFormedJson() {
    Path skeleton = Path.of(System.getProperty("flottila.corpus"), "structures", "skeleton");

    assertThatThrownBy(() -> read(skeleton, "order.xsd"))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-not-well-formed");
              assertThat(e.file()).isEqualTo("order.xsd");
              assertThat(e.line()).isEqualTo(1);
              assertThat(e.column()).isEqualTo(1);
            });
  }

  @Test
  void bytesThatAreNotUtf8AreNotWellFormed() {
    MemoryResolver resolver =
        new MemoryResolver().file("order.schema.json", new byte[] {'{', '"', (byte) 0xC3, '"'});

    assertThatThrownBy(
            () ->
                new StructureReader(resolver)
                    .read(new SchemaBundle(List.of("order.schema.json")), new JsonRoot()))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-not-well-formed");
              assertThat(e.file()).isEqualTo("order.schema.json");
              assertThat(e.line()).isEqualTo(1);
              assertThat(e.column()).isEqualTo(1);
            });
  }

  /** A listed file the resolver cannot read is the resolver's refusal, at that file. */
  @Test
  void aListedFileTheResolverCannotReadIsUnreadable() {
    MemoryResolver resolver =
        new MemoryResolver()
            .file(
                "order.schema.json",
                "{\"type\": \"object\", \"properties\": {\"a\": {\"$ref\": \"a.schema.json\"}}}");

    assertThatThrownBy(
            () ->
                new StructureReader(resolver)
                    .read(
                        new SchemaBundle(List.of("order.schema.json", "a.schema.json")),
                        new JsonRoot()))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-unreadable");
              assertThat(e.file()).isEqualTo("a.schema.json");
              assertThat(e.line()).isEqualTo(1);
              assertThat(e.column()).isEqualTo(1);
            });
  }

  @Test
  void anEmptyBundleHasNoRootSchema() {
    assertThatThrownBy(() -> read(FIXTURES))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.code()).isEqualTo("schema-construct-unsupported");
              assertThat(e.file()).isEmpty();
              assertThat(e.line()).isEqualTo(1);
              assertThat(e.column()).isEqualTo(1);
            });
  }

  /** The id of the structure type of the structure field at a path's text. */
  private static String type(Structure structure, String path) {
    return fieldAt(structure, path).type().orElseThrow();
  }

  /** The field at a path's text, found by walking the tree. */
  private static Field fieldAt(Structure structure, String path) {
    return walk(structure, structure.rootPath()).stream()
        .filter(p -> p.toString().equals(path))
        .findFirst()
        .flatMap(structure::field)
        .orElseThrow(() -> new AssertionError("no field " + path));
  }

  private static List<FieldPath> walk(Structure structure, FieldPath path) {
    List<FieldPath> paths = new ArrayList<>(List.of(path));
    if (path.steps().size() < DEPTH) {
      for (FieldPath child : structure.children(path)) {
        paths.addAll(walk(structure, child));
      }
    }
    return paths;
  }

  /** Every field to {@link #DEPTH} steps, depth first in child order, described. */
  private static List<String> tree(Structure structure) {
    return walk(structure, structure.rootPath()).stream()
        .map(path -> path + " " + describe(structure.field(path).orElseThrow()))
        .toList();
  }

  /** Occurrence, value type or structure, then nillable and the array marker (#118) when set. */
  private static String describe(Field field) {
    String kind = field.valueType().map(ValueType::typeName).orElse("structure");
    return field.occurrence()
        + " "
        + kind
        + (field.nillable() ? " nillable" : "")
        + (field.array() ? " array" : "");
  }
}
