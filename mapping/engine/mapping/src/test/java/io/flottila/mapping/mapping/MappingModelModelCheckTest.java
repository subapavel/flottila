package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.Fixtures.utf8;
import static io.flottila.mapping.mapping.LoadResults.errorAtId;
import static io.flottila.mapping.mapping.LoadResults.loaded;
import static io.flottila.mapping.mapping.LoadResults.refused;
import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.elementRoot;
import static io.flottila.mapping.mapping.Models.function;
import static io.flottila.mapping.mapping.Models.inputs;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.noParams;
import static io.flottila.mapping.mapping.Models.params;
import static io.flottila.mapping.mapping.Models.source;
import static io.flottila.mapping.mapping.Models.targetFieldMapping;
import static io.flottila.mapping.mapping.Models.targetFieldMappings;
import static io.flottila.mapping.mapping.Models.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Stage 2 of loading, the model check (ADR-0011): ids unique across the mapping, references
 * resolved inside their target field mapping, an acyclic graph, a context that is a proper ancestor
 * of its field, and declared UDFs. Each diagnostic is located at the consuming box's authoring id,
 * or at the target field mapping's for {@code result}. Every document passes stage 1.
 */
class MappingModelModelCheckTest {

  static final String FP_ORDER =
      "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  static final String FP_INVOICE =
      "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

  static String document(String mappings) {
    return document(mappings, "{}");
  }

  static String document(String mappings, String udfs) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["order.xsd"], "fingerprint": "%s", "root": {"element": "Order"}},
         "target": {"bundle": ["invoice.xsd"], "fingerprint": "%s", "root": {"element": "Invoice"}},
         "targetFieldMappings": %s,
         "udfs": %s}
        """
        .formatted(FP_ORDER, FP_INVOICE, mappings, udfs);
  }

  static Stream<Arguments> duplicateIds() {
    return Stream.of(
        Arguments.of(
            "a box id in two target field mappings",
            """
            {"/Invoice/Number": {"id": "t1", "result": "a1", "boxes": {
               "a1": {"kind": "source", "field": "/Order/Number"}}},
             "/Invoice/Date": {"id": "t2", "result": "a1", "boxes": {
               "a1": {"kind": "source", "field": "/Order/Date"}}}}""",
            "a1"),
        Arguments.of(
            "a target field mapping id equal to another mapping's box id",
            """
            {"/Invoice/Number": {"id": "t1", "result": "a1", "boxes": {
               "a1": {"kind": "source", "field": "/Order/Number"}}},
             "/Invoice/Date": {"id": "a1", "result": "a2", "boxes": {
               "a2": {"kind": "source", "field": "/Order/Date"}}}}""",
            "a1"),
        Arguments.of(
            "a target field mapping id equal to its own box id",
            """
            {"/Invoice/Number": {"id": "a1", "result": "a1", "boxes": {
               "a1": {"kind": "source", "field": "/Order/Number"}}}}""",
            "a1"),
        Arguments.of(
            "two target field mappings with one id",
            """
            {"/Invoice/Number": {"id": "t1", "result": "a1", "boxes": {
               "a1": {"kind": "source", "field": "/Order/Number"}}},
             "/Invoice/Date": {"id": "t1", "result": "a2", "boxes": {
               "a2": {"kind": "source", "field": "/Order/Date"}}}}""",
            "t1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("duplicateIds")
  void loadReportsDuplicateIdAcrossTargetFieldMappings(String what, String mappings, String id)
      throws Exception {
    // how often a duplicate is reported is not decided; every report names the duplicated id
    assertThat(refused(MappingModelFormat.load(utf8(document(mappings)))))
        .containsOnly(errorAtId("duplicate-authoring-id", id));
  }

  @Test
  void loadAcceptsDistinctIdsAcrossTargetFieldMappings() throws Exception {
    String distinct =
        """
        {"/Invoice/Number": {"id": "t1", "result": "a1", "boxes": {
           "a1": {"kind": "source", "field": "/Order/Number"}}},
         "/Invoice/Date": {"id": "t2", "result": "a2", "boxes": {
           "a2": {"kind": "source", "field": "/Order/Date"}}}}""";

    assertThat(loaded(MappingModelFormat.load(utf8(document(distinct)))).targetFieldMappings())
        .containsOnlyKeys("/Invoice/Number", "/Invoice/Date");
  }

  static Stream<Arguments> unknownReferences() {
    return Stream.of(
        Arguments.of(
            "an input naming no box",
            """
            {"/Invoice/Number": {"id": "t1", "result": "b1", "boxes": {
               "a1": {"kind": "source", "field": "/Order/Number"},
               "b1": {"kind": "function", "fn": "concat", "inputs": ["a1", "zz"]}}}}""",
            "b1"),
        Arguments.of(
            "a result naming no box",
            """
            {"/Invoice/Number": {"id": "t1", "result": "zz", "boxes": {
               "a1": {"kind": "source", "field": "/Order/Number"}}}}""",
            "t1"),
        Arguments.of(
            "a result naming its own target field mapping, which is not a box",
            """
            {"/Invoice/Number": {"id": "t1", "result": "t1", "boxes": {
               "a1": {"kind": "source", "field": "/Order/Number"}}}}""",
            "t1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unknownReferences")
  void loadReportsUnknownBoxReferenceInInputsAndResult(String what, String mappings, String id)
      throws Exception {
    assertThat(refused(MappingModelFormat.load(utf8(document(mappings)))))
        .containsExactly(errorAtId("unknown-box-reference", id));
  }

  @Test
  void loadReportsCrossTargetFieldReference() throws Exception {
    String mappings =
        """
        {"/Invoice/Number": {"id": "t1", "result": "b1", "boxes": {
           "a1": {"kind": "source", "field": "/Order/Number"},
           "b1": {"kind": "function", "fn": "concat", "inputs": ["a1", "a2"]}}},
         "/Invoice/Date": {"id": "t2", "result": "a1", "boxes": {
           "a2": {"kind": "source", "field": "/Order/Date"}}}}""";

    assertThat(refused(MappingModelFormat.load(utf8(document(mappings)))))
        .containsExactlyInAnyOrder(
            errorAtId("cross-target-field-reference", "b1"),
            errorAtId("cross-target-field-reference", "t2"));
  }

  @Test
  void loadReportsEachCycleOnceAtItsSmallestId() throws Exception {
    // three disjoint cycles: {b9, b10}, {Zc, ab} and the self-loop x1. The smallest id is by
    // String.compareTo: "b10" < "b9" and "Zc" < "ab". a0 feeds on a cycle but is not in one.
    String mappings =
        """
        {"/Invoice/Number": {"id": "t1", "result": "a0", "boxes": {
           "a0": {"kind": "function", "fn": "concat", "inputs": ["b9"]},
           "b9": {"kind": "function", "fn": "concat", "inputs": ["b10"]},
           "b10": {"kind": "function", "fn": "concat", "inputs": ["b9"]},
           "s1": {"kind": "source", "field": "/Order/Number"},
           "Zc": {"kind": "function", "fn": "concat", "inputs": ["s1", "ab"]},
           "ab": {"kind": "udf", "udf": "fmt", "inputs": ["Zc"]}}},
         "/Invoice/Date": {"id": "t2", "result": null, "boxes": {
           "x1": {"kind": "unresolved", "name": "CopyValue", "origin": "po", "inputs": ["x1"]}}}}""";
    String udfs = "{\"fmt\": {\"executionKind\": \"single\", \"source\": \"udf/Udfs.java#fmt\"}}";

    assertThat(refused(MappingModelFormat.load(utf8(document(mappings, udfs)))))
        .containsExactlyInAnyOrder(
            errorAtId("box-cycle", "b10"),
            errorAtId("box-cycle", "Zc"),
            errorAtId("box-cycle", "x1"));
  }

  @Test
  void loadAcceptsFanOutWithoutCycle() throws Exception {
    // one output feeding several inputs, and a diamond, is not a cycle
    String mappings =
        """
        {"/Invoice/Number": {"id": "t1", "result": "d1", "boxes": {
           "s1": {"kind": "source", "field": "/Order/Number"},
           "f1": {"kind": "function", "fn": "trim", "inputs": ["s1"]},
           "f2": {"kind": "function", "fn": "toUpperCase", "inputs": ["s1"]},
           "d1": {"kind": "function", "fn": "concat", "inputs": ["f1", "f2", "s1", "s1"]}}}}""";

    assertThat(
            loaded(MappingModelFormat.load(utf8(document(mappings))))
                .targetFieldMappings()
                .get("/Invoice/Number")
                .boxes())
        .hasSize(4);
  }

  static Stream<Arguments> contextsNotAProperAncestor() {
    return Stream.of(
        Arguments.of("equal to the field", "/Order/Item/@id", "/Order/Item/@id"),
        Arguments.of("a sibling", "/Order/Item/@id", "/Order/Header"),
        Arguments.of(
            "a sibling the field's path text starts with", "/Order/ItemList/Entry", "/Order/Item"),
        Arguments.of("a descendant", "/Order/Item", "/Order/Item/@id"),
        Arguments.of("a deeper descendant", "/Order", "/Order/Item/Line"),
        Arguments.of("another root", "/Order/Item", "/Invoice"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contextsNotAProperAncestor")
  void loadReportsContextNotAProperAncestor(String what, String field, String context)
      throws Exception {
    String mappings =
        """
        {"/Invoice/Number": {"id": "t1", "result": "a1", "boxes": {
           "a1": {"kind": "source", "field": "%s", "context": "%s"}}}}"""
            .formatted(field, context);

    assertThat(refused(MappingModelFormat.load(utf8(document(mappings)))))
        .containsExactly(errorAtId("context-not-ancestor", "a1"));
  }

  @Test
  void loadAcceptsContextOnAProperAncestor() throws Exception {
    String ns = "Q{http://example.com/order}";
    String mappings =
        """
        {"/Invoice/Number": {"id": "t1", "result": "a1", "boxes": {
           "a1": {"kind": "source", "field": "/Order/Item/@id", "context": "/Order/Item"},
           "a2": {"kind": "source", "field": "/Order/Item/@id", "context": "/Order"},
           "a3": {"kind": "source", "field": "/%1$sOrder/%1$sItem/%1$sPrice", "context": "/%1$sOrder"},
           "a4": {"kind": "source", "field": "/$/order/items/id", "context": "/$/order"}}}}"""
            .formatted(ns);

    assertThat(
            loaded(MappingModelFormat.load(utf8(document(mappings))))
                .targetFieldMappings()
                .get("/Invoice/Number")
                .boxes())
        .containsEntry(
            Models.id("a3"),
            source("/" + ns + "Order/" + ns + "Item/" + ns + "Price", "/" + ns + "Order"))
        .containsEntry(Models.id("a2"), source("/Order/Item/@id", "/Order"));
  }

  /**
   * 100 000 function boxes in one chain, listed head first: {@code b000000} (the result) reads
   * {@code b000001}, and so on down to a source box. A check that recursed once per box would
   * overflow the stack long before the end.
   */
  @Test
  void loadAcceptsALongAcyclicChainWithoutOverflowingTheStack() throws Exception {
    int functions = 100_000;
    StringBuilder boxes = new StringBuilder();
    for (int k = 0; k < functions; k++) {
      boxes.append(
          "\"b%06d\": {\"kind\": \"function\", \"fn\": \"concat\", \"inputs\": [\"b%06d\"]},\n"
              .formatted(k, k + 1));
    }
    boxes.append(
        "\"b%06d\": {\"kind\": \"source\", \"field\": \"/Order/Number\"}".formatted(functions));
    String mappings =
        "{\"/Invoice/Number\": {\"id\": \"t1\", \"result\": \"b000000\", \"boxes\": {"
            + boxes
            + "}}}";

    MappingModel model = loaded(MappingModelFormat.load(utf8(document(mappings))));

    assertThat(model.targetFieldMappings().get("/Invoice/Number").boxes())
        .hasSize(100_001)
        .containsEntry(Models.id("b000000"), function("concat", inputs("b000001"), noParams()))
        .containsEntry(Models.id("b100000"), source("/Order/Number"));
  }

  @Test
  void loadReportsUndeclaredUdf() throws Exception {
    String mappings =
        """
        {"/Invoice/Number": {"id": "t1", "result": "u1", "boxes": {
           "u1": {"kind": "udf", "udf": "roundAmount"},
           "u2": {"kind": "udf", "udf": "formatPartner"}}}}""";
    String otherCase =
        "{\"RoundAmount\": {\"executionKind\": \"single\", \"source\": \"udf/U.java#RoundAmount\"},"
            + " \"formatPartner\": {\"executionKind\": \"single\", \"source\": \"udf/U.java#fp\"}}";

    assertThat(refused(MappingModelFormat.load(utf8(document(mappings, otherCase)))))
        .containsExactly(errorAtId("undeclared-udf", "u1"));
    assertThat(refused(MappingModelFormat.load(utf8(document(mappings, "{}")))))
        .containsExactlyInAnyOrder(
            errorAtId("undeclared-udf", "u1"), errorAtId("undeclared-udf", "u2"));
  }

  @Test
  void loadAcceptsNullResultAndUnreachableBoxes() throws Exception {
    String mappings =
        """
        {"/Invoice/Number": {"id": "t1", "result": null, "boxes": {
           "a1": {"kind": "source", "field": "/Order/Number"},
           "b1": {"kind": "function", "fn": "concat", "inputs": ["a1", null]}}},
         "/Invoice/Date": {"id": "t2", "result": "c1", "boxes": {
           "c1": {"kind": "source", "field": "/Order/Date"},
           "c2": {"kind": "function", "fn": "formatDate", "inputs": [null],
                  "params": {"pattern": "yyyy"}},
           "c3": {"kind": "function", "fn": "trim", "inputs": ["c2"]}}}}""";

    assertThat(loaded(MappingModelFormat.load(utf8(document(mappings)))))
        .isEqualTo(
            model(
                elementRoot("Order", FP_ORDER, "order.xsd"),
                elementRoot("Invoice", FP_INVOICE, "invoice.xsd"),
                targetFieldMappings(
                    "/Invoice/Number",
                    targetFieldMapping(
                        "t1",
                        null,
                        boxes(
                            "a1",
                            source("/Order/Number"),
                            "b1",
                            function("concat", inputs("a1", null), noParams()))),
                    "/Invoice/Date",
                    targetFieldMapping(
                        "t2",
                        "c1",
                        boxes(
                            "c1",
                            source("/Order/Date"),
                            "c2",
                            function(
                                "formatDate",
                                inputs((String) null),
                                params("pattern", text("yyyy"))),
                            "c3",
                            function("trim", inputs("c2"), noParams())))),
                Map.of()));
  }

  @Test
  void loadReportsAllModelCheckErrorsInLocationOrder() throws Exception {
    // The ids are chosen so that the order of the ids, the canonical document order and the tree
    // order (a target field mapping before its boxes) agree; a check-by-check order does not.
    String mappings =
        """
        {"/Invoice/C": {"id": "c9", "result": "c2", "boxes": {
           "c2": {"kind": "function", "fn": "concat", "inputs": ["c2"]},
           "c1": {"kind": "udf", "udf": "nope"}}},
         "/Invoice/B": {"id": "b5", "result": "c1", "boxes": {
           "b6": {"kind": "source", "field": "/Order/Number"}}},
         "/Invoice/A": {"id": "a9", "result": "a2", "boxes": {
           "a2": {"kind": "function", "fn": "concat", "inputs": ["zz", "a1"]},
           "a1": {"kind": "source", "field": "/Order/Item", "context": "/Order/Item/Line"}}}}""";

    assertThat(refused(MappingModelFormat.load(utf8(document(mappings)))))
        .containsExactly(
            errorAtId("context-not-ancestor", "a1"),
            errorAtId("unknown-box-reference", "a2"),
            errorAtId("cross-target-field-reference", "b5"),
            errorAtId("undeclared-udf", "c1"),
            errorAtId("box-cycle", "c2"));
  }
}
