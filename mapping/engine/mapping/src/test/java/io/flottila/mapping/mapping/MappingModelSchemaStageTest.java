package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.Fixtures.utf8;
import static io.flottila.mapping.mapping.LoadResults.errorAtId;
import static io.flottila.mapping.mapping.LoadResults.errorAtPointer;
import static io.flottila.mapping.mapping.LoadResults.loaded;
import static io.flottila.mapping.mapping.LoadResults.refused;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Stage 1 of loading (ADR-0011): the schema checks shape, kinds, id patterns and unknown members,
 * fails closed, and locates each failure at the JSON pointer (RFC 6901) of the instance. Every
 * document here is valid but for the one thing a case breaks, so it is also clean for stage 2.
 */
class MappingModelSchemaStageTest {

  static final String SOURCE =
      """
      {"bundle": ["order.xsd"],
       "fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
       "root": {"element": "Order"}}""";

  static final String TARGET =
      """
      {"bundle": ["invoice.xsd"],
       "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
       "root": {"element": "Invoice"}}""";

  static final String UDFS =
      """
      {"fmt": {"executionKind": "single", "inputs": ["value"], "params": {"scale": "int"},
               "source": "udf/Udfs.java#fmt"}}""";

  static final String TFM = "/targetFieldMappings/~1Invoice~1Number";
  static final String BOX = TFM + "/boxes/";

  static final String ID_64 = "Aa0_-".repeat(12) + "Zz9-";
  static final String ID_65 = ID_64 + "x";

  static String document(String source, String target, String mappings, String udfs) {
    return "{\"formatVersion\": 1,\n\"source\": "
        + source
        + ",\n\"target\": "
        + target
        + ",\n\"targetFieldMappings\": "
        + mappings
        + ",\n\"udfs\": "
        + udfs
        + "}\n";
  }

  static String withMappings(String mappings) {
    return document(SOURCE, TARGET, mappings, UDFS);
  }

  /**
   * One target field mapping {@code /Invoice/Number} with id {@code t1} and {@code result: null}.
   */
  static String withBoxes(String boxes) {
    return withMappings(
        "{\"/Invoice/Number\": {\"id\": \"t1\", \"result\": null, \"boxes\": " + boxes + "}}");
  }

  static String withUdfs(String udfs) {
    return document(SOURCE, TARGET, VALID_MAPPINGS, udfs);
  }

  static final String VALID_MAPPINGS =
      """
      {"/Invoice/Number": {"id": "t1", "result": "u1", "boxes": {
        "a1": {"kind": "source", "field": "/Order/Number"},
        "b1": {"kind": "function", "fn": "concat", "inputs": ["a1", null]},
        "u1": {"kind": "udf", "udf": "fmt", "inputs": ["b1"], "params": {"scale": "2"}},
        "x1": {"kind": "unresolved", "name": "CopyValue", "origin": "po", "inputs": ["a1"]}}}}""";

  @Test
  void theValidDocumentTheCasesBreakLoads() throws Exception {
    MappingModel model = loaded(MappingModelFormat.load(utf8(withMappings(VALID_MAPPINGS))));

    assertThat(model.targetFieldMappings().get("/Invoice/Number").boxes()).hasSize(4);
  }

  @Test
  void loadAcceptsIdsAndNamesAtTheirPatternLimits() throws Exception {
    String name64 = "_" + "aZ9".repeat(21);
    String mappings =
        """
        {"/Invoice/Number": {"id": "-", "result": "%1$s", "boxes": {
          "%1$s": {"kind": "udf", "udf": "%2$s", "inputs": ["A", "_"]},
          "A": {"kind": "source", "field": "/Order/Number"},
          "_": {"kind": "function", "fn": "constant", "params": {"value": "x"}}}}}"""
            .formatted(ID_64, name64);
    String udfs =
        """
        {"%1$s": {"executionKind": "single", "inputs": ["x", "%1$s"], "params": {"%1$s": "string"},
                  "source": "udf/Udfs.java#fmt"}}"""
            .formatted(name64);

    MappingModel model =
        loaded(MappingModelFormat.load(utf8(document(SOURCE, TARGET, mappings, udfs))));

    assertThat(ID_64).hasSize(64);
    assertThat(name64).hasSize(64);
    assertThat(model.targetFieldMappings().get("/Invoice/Number").boxes())
        .containsKeys(new AuthoringId(ID_64), new AuthoringId("A"), new AuthoringId("_"));
    assertThat(model.udfs()).containsOnlyKeys(name64);
  }

  static Stream<Arguments> unknownMembers() {
    return Stream.of(
        Arguments.of(
            "a top-level member",
            withMappings(VALID_MAPPINGS)
                .replace("\"formatVersion\": 1,", "\"formatVersion\": 1, \"layout\": {},"),
            "/layout"),
        Arguments.of(
            "a member of a structure reference",
            document(
                SOURCE.replace("\"bundle\"", "\"hash\": \"x\", \"bundle\""),
                TARGET,
                VALID_MAPPINGS,
                UDFS),
            "/source/hash"),
        Arguments.of(
            "a function box's member on a source box, under a key that needs ~0 and ~1",
            withMappings(
                """
                {"/Q{urn:x~y}Invoice/Number": {"id": "t1", "result": "a1", "boxes": {
                  "a1": {"kind": "source", "field": "/Order/Number", "fn": "concat"}}}}"""),
            "/targetFieldMappings/~1Q{urn:x~0y}Invoice~1Number/boxes/a1/fn"),
        Arguments.of(
            "a formatVersion 2 member on a target field mapping",
            withMappings(
                """
                {"/Invoice/Number": {"id": "t1", "result": null, "switchedOff": "true", "boxes": {
                  "a1": {"kind": "source", "field": "/Order/Number"}}}}"""),
            TFM + "/switchedOff"),
        Arguments.of(
            "a member of a UDF declaration",
            withUdfs(UDFS.replace("\"source\"", "\"deterministic\": \"true\", \"source\"")),
            "/udfs/fmt/deterministic"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unknownMembers")
  void loadReportsUnknownMemberAtItsPointer(String what, String json, String pointer)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("unknown-member", pointer));
  }

  static Stream<Arguments> unknownBoxKinds() {
    return Stream.of(
        Arguments.of(
            "a kind no branch has",
            withBoxes("{\"c1\": {\"kind\": \"constant\", \"params\": {\"value\": \"x\"}}}"),
            BOX + "c1/kind"),
        Arguments.of(
            "a kind spelled with another case",
            withBoxes("{\"a1\": {\"kind\": \"Source\", \"field\": \"/Order/Number\"}}"),
            BOX + "a1/kind"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unknownBoxKinds")
  void loadReportsUnknownBoxKindAtKindPointer(String what, String json, String pointer)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("unknown-box-kind", pointer));
  }

  static Stream<Arguments> missingMembers() {
    return Stream.of(
        Arguments.of(
            "fn of a function box",
            withBoxes("{\"b1\": {\"kind\": \"function\", \"inputs\": [null]}}"),
            BOX + "b1"),
        Arguments.of(
            "udf of a udf box",
            withBoxes("{\"u1\": {\"kind\": \"udf\", \"inputs\": [null]}}"),
            BOX + "u1"),
        Arguments.of(
            "origin of an unresolved box",
            withBoxes("{\"x1\": {\"kind\": \"unresolved\", \"name\": \"CopyValue\"}}"),
            BOX + "x1"),
        Arguments.of(
            "field of a source box",
            withBoxes("{\"a1\": {\"kind\": \"source\", \"context\": \"/Order\"}}"),
            BOX + "a1"),
        Arguments.of(
            "result of a target field mapping, which is null rather than absent",
            withMappings(
                """
                {"/Invoice/Number": {"id": "t1", "boxes": {
                  "a1": {"kind": "source", "field": "/Order/Number"}}}}"""),
            TFM),
        Arguments.of(
            "source of a UDF declaration",
            withUdfs("{\"fmt\": {\"executionKind\": \"single\"}}"),
            "/udfs/fmt"),
        Arguments.of(
            "fingerprint of a structure reference",
            document(
                "{\"bundle\": [\"order.xsd\"], \"root\": {\"element\": \"Order\"}}",
                TARGET,
                VALID_MAPPINGS,
                UDFS),
            "/source"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("missingMembers")
  void loadReportsMissingMemberOfTheMatchedKind(String what, String json, String pointer)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("missing-member", pointer));
  }

  static Stream<Arguments> badIds() {
    String oneSource = "{\"kind\": \"source\", \"field\": \"/Order/Number\"}";
    return Stream.of(
        Arguments.of(
            "a target field mapping id with a space",
            withMappings(
                "{\"/Invoice/Number\": {\"id\": \"t 1\", \"result\": null, \"boxes\": {\"a1\": "
                    + oneSource
                    + "}}}"),
            errorAtPointer("pattern-mismatch", TFM + "/id")),
        Arguments.of(
            "an input with a dot",
            withBoxes(
                "{\"a1\": "
                    + oneSource
                    + ", \"b1\": {\"kind\": \"function\", \"fn\": \"concat\", \"inputs\": [\"a1\", \"a.1\"]}}"),
            errorAtPointer("pattern-mismatch", BOX + "b1/inputs/1")),
        Arguments.of(
            "a result of 65 characters",
            withMappings(
                "{\"/Invoice/Number\": {\"id\": \"t1\", \"result\": \""
                    + ID_65
                    + "\", \"boxes\": {\"a1\": "
                    + oneSource
                    + "}}}"),
            errorAtPointer("pattern-mismatch", TFM + "/result")),
        Arguments.of(
            "a UDF input name starting with a digit",
            withUdfs(UDFS.replace("[\"value\"]", "[\"1st\"]")),
            errorAtPointer("pattern-mismatch", "/udfs/fmt/inputs/0")),
        // a map closes with patternProperties on its key and additionalProperties: false, so a key
        // outside the pattern is an unknown member (ADR-0011)
        Arguments.of(
            "a box key with a dot",
            withBoxes("{\"a.1\": " + oneSource + "}"),
            errorAtPointer("unknown-member", BOX + "a.1")),
        Arguments.of(
            "a box key of 65 characters",
            withBoxes("{\"" + ID_65 + "\": " + oneSource + "}"),
            errorAtPointer("unknown-member", BOX + ID_65)),
        Arguments.of(
            "a UDF name starting with a digit",
            withUdfs(
                UDFS.replace(
                    "{\"fmt\":",
                    "{\"1fmt\": {\"executionKind\": \"single\", \"source\": \"udf/Udfs.java#f\"},"
                        + " \"fmt\":")),
            errorAtPointer("unknown-member", "/udfs/1fmt")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badIds")
  void loadReportsBadIdPatternInValueAndInKey(String what, String json, String expected)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json)))).containsExactly(expected);
  }

  static Stream<Arguments> outputPorts() {
    return Stream.of(
        Arguments.of(
            "an input",
            withBoxes(
                """
                {"a1": {"kind": "source", "field": "/Order/Number"},
                 "b1": {"kind": "function", "fn": "concat", "inputs": ["a1#1"]}}"""),
            BOX + "b1/inputs/0"),
        Arguments.of(
            "a result",
            withMappings(
                """
                {"/Invoice/Number": {"id": "t1", "result": "a1#1", "boxes": {
                  "a1": {"kind": "source", "field": "/Order/Number"}}}}"""),
            TFM + "/result"));
  }

  @ParameterizedTest(name = "in {0}")
  @MethodSource("outputPorts")
  void loadRefusesOutputPortReference(String where, String json, String pointer) throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("pattern-mismatch", pointer));
  }

  static Stream<Arguments> nonStrings() {
    return Stream.of(
        Arguments.of(
            "a number as a field",
            withBoxes("{\"a1\": {\"kind\": \"source\", \"field\": 5}}"),
            BOX + "a1/field"),
        Arguments.of(
            "a boolean as a function name",
            withBoxes("{\"b1\": {\"kind\": \"function\", \"fn\": true}}"),
            BOX + "b1/fn"),
        Arguments.of(
            "a number in a bundle",
            document(
                SOURCE.replace("[\"order.xsd\"]", "[\"order.xsd\", 3]"),
                TARGET,
                VALID_MAPPINGS,
                UDFS),
            "/source/bundle/1"),
        Arguments.of(
            "a number as a target field mapping id",
            withMappings(
                """
                {"/Invoice/Number": {"id": 7, "result": null, "boxes": {
                  "a1": {"kind": "source", "field": "/Order/Number"}}}}"""),
            TFM + "/id"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonStrings")
  void loadRefusesNonStringValue(String what, String json, String pointer) throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("wrong-type", pointer));
  }

  @Test
  void loadRefusesTargetFieldMappingWithoutBoxes() throws Exception {
    String emptyBoxes =
        withMappings("{\"/Invoice/Number\": {\"id\": \"t1\", \"result\": null, \"boxes\": {}}}");
    String noBoxes = withMappings("{\"/Invoice/Number\": {\"id\": \"t1\", \"result\": null}}");

    assertThat(refused(MappingModelFormat.load(utf8(emptyBoxes))))
        .containsExactly(errorAtPointer("invalid-value", TFM + "/boxes"));
    assertThat(refused(MappingModelFormat.load(utf8(noBoxes))))
        .containsExactly(errorAtPointer("missing-member", TFM));
  }

  static Stream<Arguments> invalidValues() {
    return Stream.of(
        Arguments.of(
            "an empty bundle",
            document(SOURCE.replace("[\"order.xsd\"]", "[]"), TARGET, VALID_MAPPINGS, UDFS),
            errorAtPointer("invalid-value", "/source/bundle")),
        Arguments.of(
            "a bundle naming one file twice",
            document(
                SOURCE,
                TARGET.replace("[\"invoice.xsd\"]", "[\"invoice.xsd\", \"invoice.xsd\"]"),
                VALID_MAPPINGS,
                UDFS),
            errorAtPointer("invalid-value", "/target/bundle")),
        Arguments.of(
            "an execution kind in upper case",
            withUdfs(UDFS.replace("\"single\"", "\"SINGLE\"")),
            errorAtPointer("invalid-value", "/udfs/fmt/executionKind")),
        Arguments.of(
            "a UDF parameter type outside string and int",
            withUdfs(UDFS.replace("\"int\"", "\"integer\"")),
            errorAtPointer("invalid-value", "/udfs/fmt/params/scale")),
        Arguments.of(
            "an origin outside po, cpi and editor",
            withBoxes(
                "{\"x1\": {\"kind\": \"unresolved\", \"name\": \"CopyValue\", \"origin\": \"sap\"}}"),
            errorAtPointer("invalid-value", BOX + "x1/origin")),
        Arguments.of(
            "a fingerprint in upper-case hex",
            document(
                SOURCE.replace(
                    "0123456789abcdef0123456789abcdef", "0123456789ABCDEF0123456789ABCDEF"),
                TARGET,
                VALID_MAPPINGS,
                UDFS),
            errorAtPointer("pattern-mismatch", "/source/fingerprint")),
        Arguments.of(
            "a fingerprint of 63 hex digits",
            document(
                SOURCE,
                TARGET.replace("fedcba9876543210\",", "fedcba987654321\","),
                VALID_MAPPINGS,
                UDFS),
            errorAtPointer("pattern-mismatch", "/target/fingerprint")),
        Arguments.of(
            "a UDF source without #method",
            withUdfs(UDFS.replace("udf/Udfs.java#fmt", "udf/Udfs.java")),
            errorAtPointer("pattern-mismatch", "/udfs/fmt/source")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidValues")
  void loadReportsInvalidValueAndPatternMismatchAtTheirPointer(
      String what, String json, String expected) throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json)))).containsExactly(expected);
  }

  static final String TFM_DATE = "/targetFieldMappings/~1Invoice~1Date";

  /**
   * {@code /Invoice/Number} as in {@link #withBoxes}, then {@code /Invoice/Date} with {@code tail}.
   */
  static String withSecondMapping(String tail) {
    return withMappings(
        """
        {"/Invoice/Number": {"id": "t1", "result": null, "boxes": {
           "a1": {"kind": "source", "field": "/Order/Number"}}},
         "/Invoice/Date": {%s, "boxes": {
           "a2": {"kind": "source", "field": "/Order/Date"}}}}"""
            .formatted(tail));
  }

  /**
   * json-sKema 0.32.0 keeps only the first member's result of a {@code patternProperties} map
   * (F-8), so each failure sits in a later member of a map of at least two, after a valid first.
   */
  static Stream<Arguments> laterMembers() {
    return Stream.of(
        Arguments.of(
            "an unknown member on the second target field mapping",
            withSecondMapping("\"id\": \"t2\", \"result\": null, \"switchedOff\": \"true\""),
            errorAtPointer("unknown-member", TFM_DATE + "/switchedOff")),
        Arguments.of(
            "a bad id on the second target field mapping",
            withSecondMapping("\"id\": \"t 2\", \"result\": null"),
            errorAtPointer("pattern-mismatch", TFM_DATE + "/id")),
        Arguments.of(
            "an unknown member on the second box",
            withBoxes(
                """
                {"a1": {"kind": "source", "field": "/Order/Number"},
                 "a2": {"kind": "source", "field": "/Order/Date", "fn": "concat"}}"""),
            errorAtPointer("unknown-member", BOX + "a2/fn")),
        Arguments.of(
            "an unknown member on the second UDF declaration",
            withUdfs(
                """
                {"fmt": {"executionKind": "single", "inputs": ["value"], "params": {"scale": "int"},
                         "source": "udf/Udfs.java#fmt"},
                 "pad": {"executionKind": "single", "deterministic": "true",
                         "source": "udf/Udfs.java#pad"}}"""),
            errorAtPointer("unknown-member", "/udfs/pad/deterministic")),
        Arguments.of(
            "a number as the second box parameter",
            withBoxes(
                "{\"c1\": {\"kind\": \"function\", \"fn\": \"constant\","
                    + " \"params\": {\"a\": \"x\", \"b\": 5}}}"),
            errorAtPointer("wrong-type", BOX + "c1/params/b")),
        Arguments.of(
            "a number as the second value of a table that is the second box parameter",
            withBoxes(
                "{\"f1\": {\"kind\": \"function\", \"fn\": \"fixValues\","
                    + " \"params\": {\"mode\": \"x\", \"table\": {\"k\": \"v\", \"l\": 1}}}}"),
            errorAtPointer("wrong-type", BOX + "f1/params/table/l")),
        Arguments.of(
            "a type outside string and int as the second UDF parameter",
            withUdfs(
                UDFS.replace("{\"scale\": \"int\"}", "{\"scale\": \"int\", \"width\": \"long\"}")),
            errorAtPointer("invalid-value", "/udfs/fmt/params/width")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("laterMembers")
  void loadReportsAFailureInALaterMemberOfAMap(String what, String json, String expected)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json)))).containsExactly(expected);
  }

  // --- the studio members (ADR-0029): an optional description of at most 4,000 code points, and an
  // optional layout per target field mapping whose positions are integers in [-1,000,000,
  // 1,000,000]; minimum, maximum and maxLength are invalid-value. The positions of layout.boxes sit
  // in a map, so each is validated on its own, the first as much as a later one (F-8).

  static final String LAYOUT = TFM + "/layout";

  /**
   * One target field mapping {@code /Invoice/Number} with id {@code t1}, result {@code a1}, the
   * source boxes {@code a1} and {@code b1}, and {@code layout} as its layout.
   */
  static String withLayout(String layout) {
    return withMappings(
        """
        {"/Invoice/Number": {"id": "t1", "result": "a1", "layout": %s, "boxes": {
          "a1": {"kind": "source", "field": "/Order/Number"},
          "b1": {"kind": "source", "field": "/Order/Date"}}}}"""
            .formatted(layout));
  }

  /** The document of {@link #VALID_MAPPINGS} with {@code description}, a JSON value, first. */
  static String withDescription(String description) {
    return "{\"description\": " + description + ", " + withMappings(VALID_MAPPINGS).substring(1);
  }

  @Test
  void loadAcceptsADescriptionAndALayoutWithCoordinatesAtTheirLimits() throws Exception {
    String layout =
        """
        {"target": {"x": 1000000, "y": -1000000},
         "boxes": {"a1": {"x": -1000000, "y": 1000000}, "b1": {"x": 0, "y": -7}}}""";
    String json = "{\"description\": \"Orders to invoices\", " + withLayout(layout).substring(1);

    MappingModel model = loaded(MappingModelFormat.load(utf8(json)));

    assertThat(model.targetFieldMappings().get("/Invoice/Number").boxes()).hasSize(2);
  }

  static Stream<Arguments> descriptionsOfAtMost4000CodePoints() {
    return Stream.of(
        Arguments.of("4,000 ASCII characters", "a".repeat(4000)),
        Arguments.of("3,999 ASCII and one astral: 4,001 UTF-16 units", "a".repeat(3999) + "😀"),
        Arguments.of("4,000 astral characters: 8,000 UTF-16 units", "😀".repeat(4000)),
        Arguments.of("the empty string", ""));
  }

  /** {@code maxLength} counts code points (JSON Schema 2020-12, 6.3.1), not UTF-16 units. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("descriptionsOfAtMost4000CodePoints")
  void loadAcceptsADescriptionOfAtMost4000CodePoints(String what, String description)
      throws Exception {
    MappingModel model =
        loaded(MappingModelFormat.load(utf8(withDescription("\"" + description + "\""))));

    assertThat(model.targetFieldMappings()).containsOnlyKeys("/Invoice/Number");
  }

  static Stream<Arguments> descriptionsOver4000CodePoints() {
    return Stream.of(
        Arguments.of("4,001 ASCII characters", "a".repeat(4001)),
        Arguments.of("4,001 astral characters", "😀".repeat(4001)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("descriptionsOver4000CodePoints")
  void loadRefusesADescriptionOver4000CodePointsAsInvalidValue(String what, String description)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(withDescription("\"" + description + "\"")))))
        .containsExactly(errorAtPointer("invalid-value", "/description"));
  }

  static Stream<Arguments> wrongTypedStudioMembers() {
    return Stream.of(
        Arguments.of("a number as the description", withDescription("4000"), "/description"),
        Arguments.of("null as the description", withDescription("null"), "/description"),
        Arguments.of(
            "null as the source, which is optional but not nullable",
            document("null", TARGET, VALID_MAPPINGS, UDFS),
            "/source"),
        Arguments.of("a string as the layout", withLayout("\"auto\""), LAYOUT),
        Arguments.of("null as the layout", withLayout("null"), LAYOUT),
        Arguments.of(
            "an array as the target's position",
            withLayout("{\"target\": [1, 2]}"),
            LAYOUT + "/target"),
        Arguments.of(
            "an array as the layout's boxes",
            withLayout("{\"boxes\": [{\"x\": 1, \"y\": 2}]}"),
            LAYOUT + "/boxes"),
        Arguments.of(
            "a string as the target's x",
            withLayout("{\"target\": {\"x\": \"10\", \"y\": 0}}"),
            LAYOUT + "/target/x"),
        Arguments.of(
            "a fraction as the first box's y",
            withLayout(
                "{\"boxes\": {\"a1\": {\"x\": 0, \"y\": 0.5}, \"b1\": {\"x\": 0, \"y\": 0}}}"),
            LAYOUT + "/boxes/a1/y"),
        Arguments.of(
            "null as the second box's x",
            withLayout(
                "{\"boxes\": {\"a1\": {\"x\": 0, \"y\": 0}, \"b1\": {\"x\": null, \"y\": 0}}}"),
            LAYOUT + "/boxes/b1/x"),
        Arguments.of(
            "a number as the second box's position",
            withLayout("{\"boxes\": {\"a1\": {\"x\": 0, \"y\": 0}, \"b1\": 5}}"),
            LAYOUT + "/boxes/b1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("wrongTypedStudioMembers")
  void loadReportsAWrongTypedStudioMemberAtItsPointer(String what, String json, String pointer)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("wrong-type", pointer));
  }

  static Stream<Arguments> coordinatesOutOfRange() {
    return Stream.of(
        Arguments.of(
            "the target's x one above the maximum",
            withLayout("{\"target\": {\"x\": 1000001, \"y\": 0}}"),
            LAYOUT + "/target/x"),
        Arguments.of(
            "the target's y one below the minimum",
            withLayout("{\"target\": {\"x\": 0, \"y\": -1000001}}"),
            LAYOUT + "/target/y"),
        Arguments.of(
            "the first box's x beyond a Java int",
            withLayout("{\"boxes\": {\"a1\": {\"x\": 2147483648, \"y\": 0}}}"),
            LAYOUT + "/boxes/a1/x"),
        Arguments.of(
            "the second box's y one below the minimum",
            withLayout(
                "{\"boxes\": {\"a1\": {\"x\": 0, \"y\": 0}, \"b1\": {\"x\": 0, \"y\": -1000001}}}"),
            LAYOUT + "/boxes/b1/y"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("coordinatesOutOfRange")
  void loadRefusesACoordinateOutOfRangeAsInvalidValue(String what, String json, String pointer)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("invalid-value", pointer));
  }

  static Stream<Arguments> unknownStudioMembers() {
    return Stream.of(
        Arguments.of(
            "the viewer's zoom in the layout", withLayout("{\"zoom\": \"1.5\"}"), LAYOUT + "/zoom"),
        Arguments.of(
            "a z on the target's position",
            withLayout("{\"target\": {\"x\": 0, \"y\": 0, \"z\": 0}}"),
            LAYOUT + "/target/z"),
        Arguments.of(
            "a width on the second box's position",
            withLayout(
                "{\"boxes\": {\"a1\": {\"x\": 0, \"y\": 0}, \"b1\": {\"x\": 0, \"y\": 0, \"width\": 80}}}"),
            LAYOUT + "/boxes/b1/width"),
        Arguments.of(
            "a layout box key with a dot",
            withLayout("{\"boxes\": {\"a.1\": {\"x\": 0, \"y\": 0}}}"),
            LAYOUT + "/boxes/a.1"),
        Arguments.of(
            "a layout box key of 65 characters",
            withLayout("{\"boxes\": {\"" + ID_65 + "\": {\"x\": 0, \"y\": 0}}}"),
            LAYOUT + "/boxes/" + ID_65),
        Arguments.of(
            "a description on a target field mapping, which only the model has",
            withMappings(
                """
                {"/Invoice/Number": {"id": "t1", "result": "a1", "description": "x", "boxes": {
                  "a1": {"kind": "source", "field": "/Order/Number"}}}}"""),
            TFM + "/description"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unknownStudioMembers")
  void loadReportsAnUnknownStudioMemberAtItsPointer(String what, String json, String pointer)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("unknown-member", pointer));
  }

  static Stream<Arguments> missingCoordinates() {
    return Stream.of(
        Arguments.of(
            "y of the target's position",
            withLayout("{\"target\": {\"x\": 0}}"),
            LAYOUT + "/target"),
        Arguments.of(
            "x of the second box's position",
            withLayout("{\"boxes\": {\"a1\": {\"x\": 0, \"y\": 0}, \"b1\": {\"y\": 0}}}"),
            LAYOUT + "/boxes/b1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("missingCoordinates")
  void loadReportsAMissingCoordinateAtItsPosition(String what, String json, String pointer)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("missing-member", pointer));
  }

  // --- a mapping without its structures (ADR-0029): a new mapping has none until the Signature tab
  // chooses them, and save (stages 1 and 2) accepts it

  static Stream<Arguments> withoutStructures() {
    return Stream.of(
        Arguments.of("neither structure", "{\"formatVersion\": 1}"),
        Arguments.of("only the source", "{\"formatVersion\": 1, \"source\": " + SOURCE + "}"),
        Arguments.of("only the target", "{\"formatVersion\": 1, \"target\": " + TARGET + "}"),
        Arguments.of(
            "neither, with a description, target field mappings and UDFs",
            "{\"formatVersion\": 1, \"description\": \"draft\", \"targetFieldMappings\": "
                + VALID_MAPPINGS
                + ", \"udfs\": "
                + UDFS
                + "}"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("withoutStructures")
  void loadAcceptsAModelWithoutItsStructures(String what, String json) throws Exception {
    MappingModel model = loaded(MappingModelFormat.load(utf8(json)));

    assertThat(model.formatVersion()).isEqualTo(1);
  }

  /** Stages 1 and 2 accept a model without structures, but stage 2 still checks it. */
  @Test
  void stage2StillRefusesAModelWithoutItsStructures() throws Exception {
    String json =
        """
        {"formatVersion": 1, "targetFieldMappings": {"/Invoice/Number": {"id": "t1", "result": "zz",
          "boxes": {"a1": {"kind": "source", "field": "/Order/Number"}}}}}""";

    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtId("unknown-box-reference", "t1"));
  }

  /**
   * ADR-0011 maps no stage-1 code to {@code oneOf}, so a root selector that is none of its three
   * kinds (here an element root and a JSON root at once) fails closed with {@code schema-violation}
   * at the selector.
   */
  @Test
  void loadFailsClosedWithSchemaViolationOnAKeywordWithoutACode() throws Exception {
    String json =
        document(
            SOURCE.replace("{\"element\": \"Order\"}", "{\"element\": \"Order\", \"json\": \"$\"}"),
            TARGET,
            VALID_MAPPINGS,
            UDFS);

    assertThat(refused(MappingModelFormat.load(utf8(json))))
        .containsExactly(errorAtPointer("schema-violation", "/source/root"));
  }
}
