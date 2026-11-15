package io.flottila.mapping.xslt.export;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AttributeConstructor;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrType;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Lift;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetRepeat;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The portable export (ADR-0023, #27 design) at {@code XsltExporter}: unoptimised IR, built by hand
 * as {@code Lowering.lower} gives it, in; one stylesheet that imports the helper module out. The
 * exact text of the field link's stylesheet is the design's; whether a stylesheet runs is the
 * differential oracle's question ({@code engine/oracle}).
 */
class XsltExporterTest {

  private static final String NS = "urn:flottila:corpus:skeleton";
  private static final Name ORDER = new Name(NS, "Order");
  private static final Name ORDER_ID = new Name("", "OrderId");
  private static final long UNBOUNDED = Long.MAX_VALUE;

  /**
   * The variables lowering binds: the root's slot number, and a field's slot number and value at
   * depth 2, directly under the root (ADR-0006 since #52).
   */
  private static final String ROOT = "root";

  private static final String SLOT = "slot2";

  private static final String VALUE = "value2";

  // --- IR, built by hand as lowering gives it (Lowering, ADR-0022)

  private static AuthoringId id(String value) {
    return new AuthoringId(value);
  }

  /** {@code <prefix:target>{value}</prefix:target>} per slot of {@code queue} in the root slot. */
  private static TargetSlots slots(
      SourceQueue queue, long min, long max, String prefix, Name target, String mapping) {
    AuthoringId origin = id(mapping);
    return new TargetSlots(
        queue,
        new VarRef(ROOT, AtomicType.INTEGER, origin),
        min,
        max,
        SLOT,
        VALUE,
        new ElementConstructor(
            prefix,
            target,
            List.of(new TextConstructor(new VarRef(VALUE, AtomicType.STRING, origin), origin)),
            origin),
        origin);
  }

  /** The queue of {@code field} directly under {@code root}, in the root's context. */
  private static SourceQueue underRoot(Name root, Name field, String box) {
    return new SourceQueue(List.of(root, field), 1, id(box));
  }

  /** {@code corpus/mappings/order-id-copy.mapping.json} lowered: OrderId 1..1 into ns0:Order. */
  private static TargetDocument fieldLink(long max) {
    return new TargetDocument(
        "ns0",
        ORDER,
        ROOT,
        List.of(slots(underRoot(ORDER, ORDER_ID, "a1"), 1, max, "", ORDER_ID, "t1")));
  }

  /**
   * The #27 design's stylesheet of the field link: one element per line, two-space indent. Since
   * #52 (ADR-0006) {@code po:slots} gives {@code [slot number, value]} per created occurrence,
   * bound to the slot and value variables, and {@code po:at-least} checks OrderId's min of 1.
   */
  private static final String FIELD_LINK_STYLESHEET =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" \
      xmlns:xs="http://www.w3.org/2001/XMLSchema" \
      xmlns:array="http://www.w3.org/2005/xpath-functions/array" \
      xmlns:po="urn:flottila:mapping:po" xmlns:s0="urn:flottila:corpus:skeleton" \
      xmlns:ns0="urn:flottila:corpus:skeleton" exclude-result-prefixes="#all">
        <xsl:import href="po-runtime.xsl"/>
        <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="no" \
      omit-xml-declaration="no"/>
        <xsl:template match="/">
          <xsl:variable name="root" as="xs:integer" select="1"/>
          <ns0:Order>
            <xsl:for-each \
      select="po:at-least(po:slots(po:source-queue(array:join(/s0:Order ! [OrderId ! string()])), $root, 1), 1, 't1')">
              <xsl:variable name="slot2" as="xs:integer" select="?1"/>
              <xsl:variable name="value2" as="xs:string" select="?2"/>
              <OrderId><xsl:value-of select="$value2"/></OrderId>
            </xsl:for-each>
          </ns0:Order>
        </xsl:template>
      </xsl:stylesheet>
      """;

  @Test
  void theFieldLinkExportsToTheDocumentedShape() {
    assertThat(XsltExporter.export(fieldLink(1))).isEqualTo(FIELD_LINK_STYLESHEET);
  }

  /** The start tag of the {@code xsl:stylesheet} element. */
  private static String stylesheetStartTag(String stylesheet) {
    int start = stylesheet.indexOf("<xsl:stylesheet ");
    assertThat(start).as("start of xsl:stylesheet").isNotNegative();
    return stylesheet.substring(start, stylesheet.indexOf('>', start) + 1);
  }

  private static int occurrences(String text, String part) {
    int count = 0;
    for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
      count++;
    }
    return count;
  }

  /**
   * A root without a namespace, so {@code ns0} goes to the first namespace in target field order
   * (ADR-0022); two output and two source namespaces, each used twice, and URIs that hold {@code
   * &}, {@code "} and {@code <}.
   */
  @Test
  void namespacesAreDeclaredOnceOnTheStylesheetAndEscaped() {
    String a = "urn:flottila:a&b";
    String c = "urn:flottila:c\"<d";
    Name sourceRoot = new Name(a, "Order");
    TargetDocument document =
        new TargetDocument(
            "",
            new Name("", "Order"),
            ROOT,
            List.of(
                slots(
                    underRoot(sourceRoot, new Name(a, "Item"), "a1"),
                    0,
                    UNBOUNDED,
                    "ns0",
                    new Name(a, "Item"),
                    "t1"),
                slots(
                    underRoot(sourceRoot, new Name(c, "Note"), "a2"),
                    0,
                    UNBOUNDED,
                    "ns1",
                    new Name(c, "Note"),
                    "t2"),
                slots(
                    underRoot(sourceRoot, new Name(c, "Note"), "a3"),
                    0,
                    1,
                    "ns1",
                    new Name(c, "Remark"),
                    "t3"),
                slots(
                    underRoot(sourceRoot, new Name("", "Plain"), "a4"),
                    0,
                    1,
                    "ns0",
                    new Name(a, "Again"),
                    "t4")));

    String stylesheet = XsltExporter.export(document);
    String startTag = stylesheetStartTag(stylesheet);

    assertThat(startTag)
        .contains(" xmlns:ns0=\"urn:flottila:a&amp;b\"")
        .contains(" xmlns:ns1=\"urn:flottila:c&quot;&lt;d\"")
        .contains(" xmlns:s0=\"urn:flottila:a&amp;b\"")
        .contains(" xmlns:s1=\"urn:flottila:c&quot;&lt;d\"")
        .contains(" exclude-result-prefixes=\"#all\"");
    for (String prefix : List.of("ns0", "ns1", "s0", "s1")) {
      assertThat(occurrences(stylesheet, " xmlns:" + prefix + "="))
          .as("declarations of %s", prefix)
          .isEqualTo(1);
    }
    assertThat(stylesheet)
        .doesNotContain("xmlns:ns2=", "xmlns:s2=", "xmlns=", "xpath-default-namespace")
        .doesNotContain(c, a);
    // the root without a namespace stays bare; the IR's prefixes name the other elements
    assertThat(stylesheet)
        .contains("<Order>", "</Order>", "<ns0:Item>", "<ns1:Note>", "<ns1:Remark>", "<ns0:Again>")
        // source names in walk order; a step without a namespace stays bare
        .contains(
            "po:source-queue(array:join(/s0:Order ! [s0:Item ! string()]))",
            "po:source-queue(array:join(/s0:Order ! [s1:Note ! string()]))",
            "po:source-queue(array:join(/s0:Order ! [Plain ! string()]))");
  }

  @Test
  void anUnboundedMaxIsTheEmptySequence() {
    String unbounded = XsltExporter.export(fieldLink(UNBOUNDED));
    String three = XsltExporter.export(fieldLink(3));

    assertThat(unbounded)
        .contains(
            "<xsl:for-each select=\"po:at-least(po:slots(po:source-queue(array:join(/s0:Order ! [OrderId !"
                + " string()])), $root, ()), 1, 't1')\">")
        .doesNotContain(Long.toString(UNBOUNDED));
    // witness: a bounded max is written as its number
    assertThat(three)
        .contains(
            "<xsl:for-each select=\"po:at-least(po:slots(po:source-queue(array:join(/s0:Order ! [OrderId !"
                + " string()])), $root, 3), 1, 't1')\">");
  }

  /** {@code OrderId <- OrderId} at {@code min..max}. */
  private static TargetDocument fieldLink(long min, long max) {
    return new TargetDocument(
        "ns0",
        ORDER,
        ROOT,
        List.of(slots(underRoot(ORDER, ORDER_ID, "a1"), min, max, "", ORDER_ID, "t1")));
  }

  /**
   * ADR-0006 since #52: the export of TargetSlots is {@code po:at-least(po:slots(queue, p, max),
   * min, mapping)}, and {@code po:at-least} is written only when min is above 0. The helper module
   * holds both functions, po:slots with its queue, parent slot and max, po:at-least with the slots,
   * the min and the id of the target field mapping, which its error names.
   */
  @Test
  void minAboveZeroWrapsPoAtLeast() {
    String slots =
        "po:slots(po:source-queue(array:join(/s0:Order ! [OrderId ! string()])), $root, 3)";

    assertThat(XsltExporter.export(fieldLink(0, 3)))
        .contains("<xsl:for-each select=\"" + slots + "\">")
        .doesNotContain("po:at-least");
    assertThat(XsltExporter.export(fieldLink(1, 3)))
        .contains("<xsl:for-each select=\"po:at-least(" + slots + ", 1, 't1')\">");
    assertThat(XsltExporter.export(fieldLink(2, 3)))
        .contains("<xsl:for-each select=\"po:at-least(" + slots + ", 2, 't1')\">");
    assertThat(helperParams("po:slots")).isEqualTo(3);
    assertThat(helperParams("po:at-least")).isEqualTo(3);
  }

  /** The field link with {@code Note} in {@code namespace} under {@code prefix} added. */
  private static TargetDocument withNote(String prefix, String namespace) {
    return new TargetDocument(
        "ns0",
        ORDER,
        ROOT,
        List.of(
            slots(underRoot(ORDER, ORDER_ID, "a1"), 1, 1, "", ORDER_ID, "t1"),
            slots(
                underRoot(ORDER, new Name("", "Note"), "a2"),
                0,
                UNBOUNDED,
                prefix,
                new Name(namespace, "Note"),
                "t2")));
  }

  @Test
  void anIrItCannotWriteIsACompilerBug() {
    TargetDocument rootUnderNoPrefix = new TargetDocument("", ORDER, ROOT, fieldLink(1).content());

    assertThatThrownBy(() -> XsltExporter.export(withNote("", "urn:flottila:other")))
        .as("a namespace under prefix \"\"")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> XsltExporter.export(rootUnderNoPrefix))
        .as("the root's namespace under prefix \"\"")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> XsltExporter.export(withNote("ns0", "urn:flottila:other")))
        .as("ns0 bound to the root's namespace and to another")
        .isInstanceOf(IllegalArgumentException.class);
    // xmlns:ns1="" is no declaration XML 1.0 allows, and a bare Note would be the right name
    assertThatThrownBy(() -> XsltExporter.export(withNote("ns1", "")))
        .as("a prefix without a namespace")
        .isInstanceOf(IllegalArgumentException.class);
    // witness: the same IR with the namespace under a prefix of its own exports
    assertThat(XsltExporter.export(withNote("ns1", "urn:flottila:other")))
        .contains("xmlns:ns1=\"urn:flottila:other\"", "<ns1:Note>");
  }

  /** The field link with {@code queue} and {@code content} as its target field's queue and body. */
  private static TargetDocument fieldLinkWith(Expr queue, List<Expr> content) {
    AuthoringId t1 = id("t1");
    return new TargetDocument(
        "ns0",
        ORDER,
        ROOT,
        List.of(
            new TargetSlots(
                queue,
                new VarRef(ROOT, AtomicType.INTEGER, t1),
                1,
                1,
                SLOT,
                VALUE,
                new ElementConstructor("", ORDER_ID, content, t1),
                t1)));
  }

  private static TextConstructor valueText() {
    return new TextConstructor(new VarRef(VALUE, AtomicType.STRING, id("t1")), id("t1"));
  }

  /**
   * The verifier checks scopes, not places (IrVerifier), so the exporter meets these. Each IR is
   * the field link with one node, whose origin is {@code x9}, where the IR's shape has no place for
   * it.
   */
  static Stream<Arguments> misplacedNodes() {
    SourceQueue queue = underRoot(ORDER, ORDER_ID, "a1");
    AuthoringId x9 = id("x9");
    return Stream.of(
        Arguments.of(
            "a SourceQueue as an element's content",
            fieldLinkWith(queue, List.of(new SourceQueue(List.of(ORDER, ORDER_ID), 1, x9)))),
        Arguments.of(
            "a VarRef as an element's content",
            fieldLinkWith(queue, List.of(new VarRef(VALUE, AtomicType.STRING, x9)))),
        Arguments.of(
            "a TextConstructor as a queue",
            fieldLinkWith(new TextConstructor(queue, x9), List.of(valueText()))),
        Arguments.of(
            "an ElementConstructor as a queue",
            fieldLinkWith(
                new ElementConstructor("", ORDER_ID, List.of(), x9), List.of(valueText()))),
        Arguments.of(
            "an AttributeConstructor as a queue",
            fieldLinkWith(
                new AttributeConstructor("", new Name("", "code"), new Literal("x", x9), x9),
                List.of(valueText()))),
        Arguments.of(
            "a TargetSlots as a text's value",
            fieldLinkWith(
                queue,
                List.of(
                    new TextConstructor(
                        slots(underRoot(ORDER, ORDER_ID, "a2"), 0, 1, "", ORDER_ID, "x9"),
                        id("t1"))))),
        Arguments.of(
            "a TargetRepeat as a text's value",
            fieldLinkWith(
                queue,
                List.of(
                    new TextConstructor(
                        new TargetRepeat(
                            1,
                            new VarRef(ROOT, AtomicType.INTEGER, x9),
                            "slot3",
                            new ElementConstructor("", ORDER_ID, List.of(), x9),
                            x9),
                        id("t1"))))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("misplacedNodes")
  void aNodeWhereTheIrHasNoPlaceForItIsACompilerBug(String why, TargetDocument document) {
    assertThatThrownBy(() -> XsltExporter.export(document))
        .as(why)
        .isInstanceOf(IllegalArgumentException.class)
        // the message names the node's origin
        .hasMessageContaining("x9");
  }

  /**
   * A {@code TargetSlots} inside a mapped field's element, as lowering gives it since #52: each
   * level is two spaces deeper, one element per line (#27 design), the inner queue's context is
   * {@code Item}, and the inner field reads Item's slot number {@code $slot2} as its parent's. The
   * inner queue is bound once at the top of the template, where the focus is the source document,
   * and the inner {@code xsl:for-each} reads it by name: each back end evaluates a queue at most
   * once per mapping run (ADR-0006 since #52).
   */
  @Test
  void aTargetFieldInsideAnElementIsWrittenOneLevelDeeper() {
    Name item = new Name("", "Item");
    Name name = new Name("", "Name");
    AuthoringId t1 = id("t1");
    AuthoringId t2 = id("t2");
    TargetSlots names =
        new TargetSlots(
            new SourceQueue(List.of(ORDER, item, name), 2, id("a2")),
            new VarRef(SLOT, AtomicType.INTEGER, t2),
            0,
            1,
            "slot3",
            "value3",
            new ElementConstructor(
                "",
                name,
                List.of(new TextConstructor(new VarRef("value3", AtomicType.STRING, t2), t2)),
                t2),
            t2);
    TargetDocument document =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                new TargetSlots(
                    underRoot(ORDER, item, "a1"),
                    new VarRef(ROOT, AtomicType.INTEGER, t1),
                    0,
                    UNBOUNDED,
                    SLOT,
                    VALUE,
                    new ElementConstructor("", item, List.of(names), t1),
                    t1)));
    IrVerifier.verify(document);

    assertThat(XsltExporter.export(document))
        .isEqualTo(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" \
            xmlns:xs="http://www.w3.org/2001/XMLSchema" \
            xmlns:array="http://www.w3.org/2005/xpath-functions/array" \
            xmlns:po="urn:flottila:mapping:po" xmlns:s0="urn:flottila:corpus:skeleton" \
            xmlns:ns0="urn:flottila:corpus:skeleton" exclude-result-prefixes="#all">
              <xsl:import href="po-runtime.xsl"/>
              <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="no" \
            omit-xml-declaration="no"/>
              <xsl:template match="/">
                <xsl:variable name="root" as="xs:integer" select="1"/>
                <xsl:variable name="po:queue1" as="array(item()*)" \
            select="po:source-queue(array:join(/s0:Order/Item ! [Name ! string()]))"/>
                <ns0:Order>
                  <xsl:for-each \
            select="po:slots(po:source-queue(array:join(/s0:Order ! [Item ! string()])), $root, ())">
                    <xsl:variable name="slot2" as="xs:integer" select="?1"/>
                    <xsl:variable name="value2" as="xs:string" select="?2"/>
                    <Item>
                      <xsl:for-each select="po:slots($po:queue1, $slot2, 1)">
                        <xsl:variable name="slot3" as="xs:integer" select="?1"/>
                        <xsl:variable name="value3" as="xs:string" select="?2"/>
                        <Name><xsl:value-of select="$value3"/></Name>
                      </xsl:for-each>
                    </Item>
                  </xsl:for-each>
                </ns0:Order>
              </xsl:template>
            </xsl:stylesheet>
            """);
  }

  /**
   * {@code TargetSlots} of {@code queue} under the parent slot {@code parentSlot}, unbounded,
   * binding {@code slot<depth>} and {@code value<depth>}, with {@code content} in its element.
   */
  private static TargetSlots nested(
      SourceQueue queue, String parentSlot, int depth, Name element, List<Expr> content) {
    AuthoringId origin = id("t" + depth);
    return new TargetSlots(
        queue,
        new VarRef(parentSlot, AtomicType.INTEGER, origin),
        0,
        UNBOUNDED,
        "slot" + depth,
        "value" + depth,
        new ElementConstructor("", element, content, origin),
        origin);
  }

  /**
   * Each back end evaluates a {@code TargetSlots} queue at most once per mapping run (ADR-0006
   * since #52), and an {@code xsl:for-each} evaluates its select once per occurrence of its parent:
   * no queue is written inside an occurrence, at any depth. Each queue inside one is bound once at
   * the top of the template, in IR walk order, and read there by name.
   */
  @Test
  void noQueueIsWrittenInsideAnOccurrence() {
    Name item = new Name("", "Item");
    Name line = new Name("", "Line");
    Name sku = new Name("", "Sku");
    TargetSlots skus =
        nested(
            new SourceQueue(List.of(ORDER, item, line, sku), 3, id("a3")),
            "slot3",
            4,
            sku,
            List.of(
                new TextConstructor(new VarRef("value4", AtomicType.STRING, id("t4")), id("t4"))));
    TargetSlots lines =
        nested(
            new SourceQueue(List.of(ORDER, item, line), 2, id("a2")), SLOT, 3, line, List.of(skus));
    TargetDocument document =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(nested(underRoot(ORDER, item, "a1"), ROOT, 2, item, List.of(lines))));
    IrVerifier.verify(document);

    String stylesheet = XsltExporter.export(document);

    String root = "<ns0:Order>";
    assertThat(stylesheet.substring(0, stylesheet.indexOf(root) + root.length()))
        .as("the top of the template")
        .contains(
            "<xsl:variable name=\"po:queue1\" as=\"array(item()*)\""
                + " select=\"po:source-queue(array:join(/s0:Order/Item ! [Line ! string()]))\"/>\n"
                + "    <xsl:variable name=\"po:queue2\" as=\"array(item()*)\""
                + " select=\"po:source-queue(array:join(/s0:Order/Item/Line ! [Sku ! string()]))\"/>\n"
                + "    "
                + root);
    // everything after Item's slot-number variable stands inside Item's occurrences
    String inside = stylesheet.substring(stylesheet.indexOf("select=\"?1\""));
    assertThat(inside)
        .as("inside an occurrence")
        .doesNotContain("array:join")
        .contains(
            "<xsl:for-each select=\"po:slots($po:queue1, $slot2, ())\">",
            "<xsl:for-each select=\"po:slots($po:queue2, $slot3, ())\">");
  }

  /**
   * A {@code TargetRepeat} under the root, as lowering gives an unmapped {@code Pair} of 2..2 since
   * #63: an {@code xsl:for-each} over {@code 1 to 2} that binds its slot-number variable to {@code
   * ($root - 1) * 2 + .}, the j-th copy under parent slot p (ADR-0006). Inside it stands an
   * occurrence, so the queue of the {@code TargetSlots} below is bound at the top of the template.
   * That queue is of the structure field {@code Item}: one {@code ''} per occurrence (ADR-0008).
   */
  @Test
  void aTargetRepeatIsAForEachOverItsCopiesNumberedAcrossItsParentsSlots() {
    Name pair = new Name("", "Pair");
    Name item = new Name("", "Item");
    AuthoringId origin = id("/Order/Pair");
    TargetSlots items =
        nested(new SourceQueue(List.of(ORDER, item), 1, true, id("a1")), SLOT, 3, item, List.of());
    TargetDocument document =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                new TargetRepeat(
                    2,
                    new VarRef(ROOT, AtomicType.INTEGER, origin),
                    SLOT,
                    new ElementConstructor("", pair, List.of(items), origin),
                    origin)));
    IrVerifier.verify(document);

    String stylesheet = XsltExporter.export(document);

    assertThat(stylesheet)
        .contains(
            "<xsl:variable name=\"po:queue1\" as=\"array(item()*)\""
                + " select=\"po:source-queue(array:join(/s0:Order ! [Item ! '']))\"/>\n"
                + "    <ns0:Order>\n"
                + "      <xsl:for-each select=\"1 to 2\">\n"
                + "        <xsl:variable name=\"slot2\" as=\"xs:integer\""
                + " select=\"($root - 1) * 2 + .\"/>\n"
                + "        <Pair>\n"
                + "          <xsl:for-each select=\"po:slots($po:queue1, $slot2, ())\">")
        // the copies' xsl:for-each ends after Pair, on a line of its own
        .contains("        </Pair>\n      </xsl:for-each>\n    </ns0:Order>\n");
  }

  // --- attributes (#102): a target attribute is xsl:attribute inside its item's xsl:for-each, its
  // name through the output prefixes; a source attribute is an @ step

  private static final Name CODE = new Name("", "code");
  private static final Name LANG = new Name("urn:b", "lang");

  /** {@code code} 1..1 from OrderId, then {@code ns1:lang} generated, then the OrderId leaf. */
  private static TargetDocument attributes() {
    AuthoringId t1 = id("t1");
    AuthoringId t2 = id("t2");
    return new TargetDocument(
        "ns0",
        ORDER,
        ROOT,
        List.of(
            new TargetSlots(
                underRoot(ORDER, ORDER_ID, "a1"),
                new VarRef(ROOT, AtomicType.INTEGER, t1),
                1,
                1,
                SLOT,
                VALUE,
                new AttributeConstructor("", CODE, new VarRef(VALUE, AtomicType.STRING, t1), t1),
                t1),
            new TargetRepeat(
                1,
                new VarRef(ROOT, AtomicType.INTEGER, t2),
                SLOT,
                new AttributeConstructor("ns1", LANG, new Literal("en", id("c2")), t2),
                t2),
            slots(underRoot(ORDER, ORDER_ID, "a3"), 0, 1, "", ORDER_ID, "t3")));
  }

  @Test
  void attributeExportsAsXslAttribute() {
    String stylesheet = XsltExporter.export(attributes());

    // the qualified attribute's prefix is an output prefix, declared once on the stylesheet
    assertThat(stylesheetStartTag(stylesheet))
        .contains(" xmlns:ns0=\"urn:flottila:corpus:skeleton\" xmlns:ns1=\"urn:b\" ");
    assertThat(stylesheet)
        .contains(
            "    <ns0:Order>\n"
                + "      <xsl:for-each select=\"po:at-least(po:slots(po:source-queue("
                + "array:join(/s0:Order ! [OrderId ! string()])), $root, 1), 1, 't1')\">\n"
                + "        <xsl:variable name=\"slot2\" as=\"xs:integer\" select=\"?1\"/>\n"
                + "        <xsl:variable name=\"value2\" as=\"xs:string\" select=\"?2\"/>\n"
                + "        <xsl:attribute name=\"code\" select=\"$value2\"/>\n"
                + "      </xsl:for-each>\n"
                + "      <xsl:for-each select=\"1 to 1\">\n"
                + "        <xsl:variable name=\"slot2\" as=\"xs:integer\""
                + " select=\"($root - 1) * 1 + .\"/>\n"
                + "        <xsl:attribute name=\"ns1:lang\" select=\"'en'\"/>\n"
                + "      </xsl:for-each>\n"
                + "      <xsl:for-each select=\"po:slots(po:source-queue("
                + "array:join(/s0:Order ! [OrderId ! string()])), $root, 1)\">\n");
  }

  /**
   * A simple-content leaf: its attribute item, then its text, which starts a line of its own after
   * the item, and its end tag on a line of its own (#102).
   */
  @Test
  void aTextAfterAttributeItemsStartsItsOwnLine() {
    Name note = new Name("", "Note");
    AuthoringId t1 = id("t1");
    AuthoringId t2 = id("t2");
    TargetDocument document =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                new TargetSlots(
                    underRoot(ORDER, note, "a1"),
                    new VarRef(ROOT, AtomicType.INTEGER, t1),
                    0,
                    1,
                    SLOT,
                    VALUE,
                    new ElementConstructor(
                        "",
                        note,
                        List.of(
                            new TargetRepeat(
                                1,
                                new VarRef(SLOT, AtomicType.INTEGER, t2),
                                "slot3",
                                new AttributeConstructor("", CODE, new Literal("x", id("c2")), t2),
                                t2),
                            new TextConstructor(new VarRef(VALUE, AtomicType.STRING, t1), t1)),
                        t1),
                    t1)));

    assertThat(XsltExporter.export(document))
        .contains(
            "        <Note>\n"
                + "          <xsl:for-each select=\"1 to 1\">\n"
                + "            <xsl:variable name=\"slot3\" as=\"xs:integer\""
                + " select=\"($slot2 - 1) * 1 + .\"/>\n"
                + "            <xsl:attribute name=\"code\" select=\"'x'\"/>\n"
                + "          </xsl:for-each>\n"
                + "          <xsl:value-of select=\"$value2\"/>\n"
                + "        </Note>\n");
  }

  /**
   * A source attribute's queue reads its last step as {@code @name}, under its source prefix when
   * it has a namespace, in its element's context or any ancestor's (#102).
   */
  @Test
  void sourceAttributeExportsAsAtStep() {
    Name item = new Name("", "Item");
    Name id = new Name("", "id");
    TargetDocument document =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                slots(
                    new SourceQueue(List.of(ORDER, CODE), 1, false, true, id("a1")),
                    0,
                    1,
                    "",
                    ORDER_ID,
                    "t1"),
                slots(
                    new SourceQueue(List.of(ORDER, item, id), 1, false, true, id("a2")),
                    0,
                    UNBOUNDED,
                    "",
                    new Name("", "Note"),
                    "t2"),
                slots(
                    new SourceQueue(List.of(ORDER, item, LANG), 2, false, true, id("a3")),
                    0,
                    UNBOUNDED,
                    "",
                    new Name("", "Lang"),
                    "t3")));
    IrVerifier.verify(document);

    String stylesheet = XsltExporter.export(document);

    assertThat(stylesheetStartTag(stylesheet)).contains(" xmlns:s1=\"urn:b\" ");
    assertThat(stylesheet)
        .contains(
            "po:source-queue(array:join(/s0:Order ! [@code ! string()]))",
            "po:source-queue(array:join(/s0:Order ! [Item/@id ! string()]))",
            "po:source-queue(array:join(/s0:Order/Item ! [@s1:lang ! string()]))");
  }

  /**
   * IrVerifier accepts an element without content, which spec 1's lowering does not give yet: like
   * any content but a single text, its end tag stands on a line of its own (#27 design, #49).
   */
  @Test
  void anElementWithoutContentEndsOnALineOfItsOwn() {
    TargetDocument document = fieldLinkWith(underRoot(ORDER, ORDER_ID, "a1"), List.of());
    IrVerifier.verify(document);

    assertThat(XsltExporter.export(document))
        .isEqualTo(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" \
            xmlns:xs="http://www.w3.org/2001/XMLSchema" \
            xmlns:array="http://www.w3.org/2005/xpath-functions/array" \
            xmlns:po="urn:flottila:mapping:po" xmlns:s0="urn:flottila:corpus:skeleton" \
            xmlns:ns0="urn:flottila:corpus:skeleton" exclude-result-prefixes="#all">
              <xsl:import href="po-runtime.xsl"/>
              <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="no" \
            omit-xml-declaration="no"/>
              <xsl:template match="/">
                <xsl:variable name="root" as="xs:integer" select="1"/>
                <ns0:Order>
                  <xsl:for-each \
            select="po:at-least(po:slots(po:source-queue(array:join(/s0:Order ! [OrderId ! string()])), $root, 1), 1, 't1')">
                    <xsl:variable name="slot2" as="xs:integer" select="?1"/>
                    <xsl:variable name="value2" as="xs:string" select="?2"/>
                    <OrderId>
                    </OrderId>
                  </xsl:for-each>
                </ns0:Order>
              </xsl:template>
            </xsl:stylesheet>
            """);
  }

  @Test
  void theHelperModuleIsTheModulesResource() throws Exception {
    String resource;
    try (InputStream in = XsltExporter.class.getResourceAsStream("po-runtime.xsl")) {
      assertThat(in).as("resource io/flottila/mapping/xslt/export/po-runtime.xsl").isNotNull();
      resource = new String(in.readAllBytes(), UTF_8);
    }

    assertThat(XsltExporter.HELPER_MODULE).isEqualTo("po-runtime.xsl");
    assertThat(XsltExporter.helperModule()).isEqualTo(resource);
    // spec 1's helper holds po:slots in the po namespace (ADR-0023)
    assertThat(resource)
        .contains("xmlns:po=\"urn:flottila:mapping:po\"")
        .contains("<xsl:function ")
        .contains("name=\"po:slots\"");
  }

  // --- function calls (#50, ADR-0025): a FunCall is po:<catalogue name>(args…), its args in
  // FunCall order, the inputs and then every param as a Literal; the back ends bind by name only

  private static final QueueType QUEUE = new QueueType();

  private static FunCall removeContexts(Expr queue, String box) {
    return new FunCall(
        new FunctionRef("removeContexts", List.of(QUEUE), QUEUE), List.of(queue), id(box));
  }

  /** The number of {@code xsl:param} of the helper module's {@code xsl:function} {@code name}. */
  private static int helperParams(String name) {
    String helper = XsltExporter.helperModule();
    int at = helper.indexOf("name=\"" + name + "\"");
    assertThat(at).as("%s in the helper module", name).isNotNegative();
    assertThat(helper.substring(helper.lastIndexOf('<', at)))
        .as("the element named %s", name)
        .startsWith("<xsl:function ");
    int end = helper.indexOf("</xsl:function>", at);
    assertThat(end).as("the end of %s", name).isGreaterThan(at);
    return occurrences(helper.substring(at, end), "<xsl:param ");
  }

  @Test
  void aFunCallIsItsPoFunction() {
    SourceQueue orderIds = underRoot(ORDER, ORDER_ID, "a1");
    String queue = "po:source-queue(array:join(/s0:Order ! [OrderId ! string()]))";
    String once = "po:slots(po:removeContexts(" + queue + "), $root, 1)";
    String nested = "po:slots(po:removeContexts(po:removeContexts(" + queue + ")), $root, 1)";
    String fieldLink = "po:slots(" + queue + ", $root, 1)";
    assertThat(FIELD_LINK_STYLESHEET).as("the field link's call").contains(fieldLink);

    // the field link's stylesheet with the call around its queue, and nothing else changed
    assertThat(
            XsltExporter.export(
                fieldLinkWith(removeContexts(orderIds, "r1"), List.of(valueText()))))
        .isEqualTo(FIELD_LINK_STYLESHEET.replace(fieldLink, once));
    // an argument is any queue expression, a call included
    assertThat(
            XsltExporter.export(
                fieldLinkWith(
                    removeContexts(removeContexts(orderIds, "r1"), "r2"), List.of(valueText()))))
        .contains("<xsl:for-each select=\"po:at-least(" + nested + ", 1, 't1')\">");
    // the helper module holds the function, with one parameter per argument
    assertThat(helperParams("po:removeContexts")).isEqualTo(1);
  }

  // --- the box in data errors (ADR-0026, F-19): a po: function that can raise one declares box as
  // its last param, and the exporter appends the FunCall's origin exactly there

  /**
   * The names of the {@code xsl:param} of the helper module's {@code xsl:function} {@code name}:
   * its own, which stand before its body, so not those of an {@code xsl:iterate} in it, as
   * formatByExample has.
   */
  private static List<String> helperParamNames(String name) {
    String helper = XsltExporter.helperModule();
    int at = helper.indexOf("name=\"" + name + "\"");
    assertThat(at).as("%s in the helper module", name).isNotNegative();
    String function = helper.substring(helper.indexOf('>', at) + 1);
    List<String> names = new ArrayList<>();
    Matcher param =
        Pattern.compile("\\G\\s*<xsl:param\\s+name=\"([^\"]*)\"[^>]*/>").matcher(function);
    while (param.find()) {
      names.add(param.group(1));
    }
    return names;
  }

  private static FunCall call(String name, List<Expr> args, int params, String box) {
    List<IrType> types = new ArrayList<>();
    for (int k = 0; k < args.size(); k++) {
      types.add(k < args.size() - params ? QUEUE : AtomicType.STRING);
    }
    return new FunCall(new FunctionRef(name, types, QUEUE), args, id(box));
  }

  @Test
  void aFunctionThatCanRaiseTakesItsBoxIdLast() {
    String queue = "po:source-queue(array:join(/s0:Order ! [OrderId ! string()]))";
    String fieldLink = "po:slots(" + queue + ", $root, 1)";
    FunCall useOneAsMany =
        call(
            "useOneAsMany",
            List.of(
                underRoot(ORDER, ORDER_ID, "a1"),
                underRoot(ORDER, ORDER_ID, "a2"),
                underRoot(ORDER, ORDER_ID, "a3")),
            0,
            "u1");
    String uoam = "po:useOneAsMany(" + queue + ", " + queue + ", " + queue + ", 'u1')";

    // the field link's stylesheet with the call around its queue, its box id last
    assertThat(XsltExporter.export(fieldLinkWith(useOneAsMany, List.of(valueText()))))
        .isEqualTo(FIELD_LINK_STYLESHEET.replace(fieldLink, "po:slots(" + uoam + ", $root, 1)"));
    // the box id comes after the params, and each call gets its own: here sort around useOneAsMany
    FunCall sort =
        call(
            "sort",
            List.of(
                useOneAsMany,
                new Literal("numeric", id("s1")),
                new Literal("descending", id("s1"))),
            2,
            "s1");
    assertThat(XsltExporter.export(fieldLinkWith(sort, List.of(valueText()))))
        .contains("po:slots(po:sort(" + uoam + ", 'numeric', 'descending', 's1'), $root, 1)");
    // a function that cannot raise one is unchanged
    assertThat(
            XsltExporter.export(
                fieldLinkWith(removeContexts(useOneAsMany, "r1"), List.of(valueText()))))
        .contains("po:slots(po:removeContexts(" + uoam + "), $root, 1)")
        .doesNotContain("'r1'");

    // the helper module declares box last exactly where a function can raise a data error
    assertThat(helperParamNames("po:useOneAsMany"))
        .containsExactly("value", "occurrences", "contexts", "box");
    assertThat(helperParamNames("po:formatByExample")).containsExactly("values", "example", "box");
    assertThat(helperParamNames("po:sort")).containsExactly("queue", "comparison", "order", "box");
    assertThat(helperParamNames("po:sortByKey"))
        .containsExactly("keys", "values", "comparison", "order", "box");
    assertThat(helperParamNames("po:removeContexts")).containsExactly("queue");
    assertThat(helperParamNames("po:replaceValue")).containsExactly("value", "replacement");
  }

  // --- the constant family since #83 (ADR-0006 generating function, ADR-0026 "Table params")

  /**
   * A table param is one Literal of the table's compact canonical JSON, so it is one XPath string
   * literal: its apostrophe doubled, its quotation marks escaped for the attribute. Read back as an
   * XML parser and then an XPath parser read it, it is the table's text. {@code po:fixValues} takes
   * its input, then table, missing and defaultValue in catalogue order, and its box last, as it can
   * raise {@code po:FIXVALUES}.
   */
  @Test
  void aTableParamIsOneStringLiteralWithItsApostropheDoubled() {
    AuthoringId f1 = id("f1");
    String table = "{\"it's\":\"x\",\"q\":\"\\\"\"}";
    FunCall fixValues =
        new FunCall(
            new FunctionRef(
                "fixValues",
                List.of(AtomicType.STRING, AtomicType.STRING, AtomicType.STRING, AtomicType.STRING),
                AtomicType.STRING),
            List.of(
                new VarRef("in1", AtomicType.STRING, f1),
                new Literal(table, f1),
                new Literal("key", f1),
                new Literal("", f1)),
            f1);
    Lift lift = new Lift(List.of(underRoot(ORDER, ORDER_ID, "a1")), List.of("in1"), fixValues, f1);

    String stylesheet = XsltExporter.export(fieldLinkWith(lift, List.of(valueText())));

    assertThat(stylesheet)
        .contains(
            "po:fixValues($in1, '{&quot;it''s&quot;:&quot;x&quot;,&quot;q&quot;:&quot;\\&quot;"
                + "&quot;}', 'key', '', 'f1')");
    String select = attributeValue(forEachSelect(stylesheet));
    String call = "po:fixValues($in1, ";
    assertThat(StringLiteral.at(select, select.indexOf(call) + call.length()).value())
        .isEqualTo(table);
    assertThat(helperParamNames("po:fixValues"))
        .containsExactly("key", "table", "missing", "defaultValue", "box");
  }

  /**
   * A constant box is an atomic FunCall, {@code po:constant} over its value, which the text of its
   * TargetRepeat's element computes once per copy (ADR-0006 since #83); it cannot raise, so it
   * takes no box.
   */
  @Test
  void aConstantIsItsPoConstantCallInTheTextOfItsTargetRepeat() {
    AuthoringId c1 = id("c1");
    AuthoringId t1 = id("t1");
    FunCall constant =
        new FunCall(
            new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING),
            List.of(new Literal("it's", c1)),
            c1);
    TargetDocument document =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                new TargetRepeat(
                    1,
                    new VarRef(ROOT, AtomicType.INTEGER, t1),
                    SLOT,
                    new ElementConstructor(
                        "", ORDER_ID, List.of(new TextConstructor(constant, t1)), t1),
                    t1)));
    // witness: the IR verifier accepts the document as it stands
    IrVerifier.verify(document);

    assertThat(XsltExporter.export(document))
        .contains("<xsl:for-each select=\"1 to 1\">")
        .contains("<OrderId><xsl:value-of select=\"po:constant('it''s')\"/></OrderId>");
    assertThat(helperParamNames("po:constant")).containsExactly("value");
  }

  /**
   * A generating structure field's value (ADR-0006 as amended by #83): inside the copies' {@code
   * xsl:for-each}, after the slot-number variable and before the body, a zero-length {@code
   * xsl:value-of} of {@code substring(value, 1, 0)}, so each copy evaluates it and writes no text.
   * The value's apostrophe is doubled, its quotation marks escaped for the attribute. Witness: a
   * TargetRepeat without a value writes no such line.
   */
  @Test
  void aTargetRepeatValueIsAZeroLengthValueOfBeforeItsBody() {
    AuthoringId c1 = id("c1");
    AuthoringId t1 = id("t1");
    Name address = new Name("", "Address");
    FunCall constant =
        new FunCall(
            new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING),
            List.of(new Literal("it's \"g\"", c1)),
            c1);
    ElementConstructor body = new ElementConstructor("", address, List.of(), t1);
    TargetDocument valued =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                new TargetRepeat(
                    2,
                    new VarRef(ROOT, AtomicType.INTEGER, t1),
                    SLOT,
                    Optional.of(constant),
                    body,
                    t1)));
    TargetDocument unvalued =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(new TargetRepeat(2, new VarRef(ROOT, AtomicType.INTEGER, t1), SLOT, body, t1)));
    IrVerifier.verify(valued);

    assertThat(XsltExporter.export(valued))
        .contains(
            "      <xsl:for-each select=\"1 to 2\">\n"
                + "        <xsl:variable name=\"slot2\" as=\"xs:integer\""
                + " select=\"($root - 1) * 2 + .\"/>\n"
                + "        <xsl:value-of select=\"substring(po:constant('it''s &quot;g&quot;'),"
                + " 1, 0)\"/>\n"
                + "        <Address>\n"
                + "        </Address>\n"
                + "      </xsl:for-each>\n");
    assertThat(XsltExporter.export(unvalued)).doesNotContain("substring(");
  }

  // --- a generating block (#217, F-46): copyValue's result, one value for every parent

  /** {@code po:copyValue} at {@code box} over Note directly under the root. */
  private static FunCall copyValue(String position, String box, String sourceBox) {
    return new FunCall(
        new FunctionRef("copyValue", List.of(QUEUE, AtomicType.STRING), QUEUE),
        List.of(underRoot(ORDER, new Name("", "Note"), sourceBox), new Literal(position, id(box))),
        id(box));
  }

  /**
   * A generating block's {@code TargetRepeat}: the block is bound once as a queue variable at the
   * top of the template, under the root too, since a copy reads it; inside each copy an {@code
   * xsl:for-each} over {@code po:generated} of it binds the value variable, so a SUPPRESS or
   * missing first slot writes nothing for that copy, whose slot number stays {@code (p - 1) *
   * copies + j}.
   */
  @Test
  void aGeneratingBlockIsBoundOnceAndReadThroughPoGenerated() {
    AuthoringId t1 = id("t1");
    TargetDocument underTheRoot =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                new TargetRepeat(
                    1,
                    new VarRef(ROOT, AtomicType.INTEGER, t1),
                    SLOT,
                    Optional.of(copyValue("1", "f1", "a1")),
                    Optional.of(VALUE),
                    new ElementConstructor(
                        "",
                        ORDER_ID,
                        List.of(new TextConstructor(new VarRef(VALUE, AtomicType.STRING, t1), t1)),
                        t1),
                    t1)));
    IrVerifier.verify(underTheRoot);

    assertThat(XsltExporter.export(underTheRoot))
        .isEqualTo(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" \
            xmlns:xs="http://www.w3.org/2001/XMLSchema" \
            xmlns:array="http://www.w3.org/2005/xpath-functions/array" \
            xmlns:po="urn:flottila:mapping:po" xmlns:s0="urn:flottila:corpus:skeleton" \
            xmlns:ns0="urn:flottila:corpus:skeleton" exclude-result-prefixes="#all">
              <xsl:import href="po-runtime.xsl"/>
              <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="no" \
            omit-xml-declaration="no"/>
              <xsl:template match="/">
                <xsl:variable name="root" as="xs:integer" select="1"/>
                <xsl:variable name="po:queue1" as="array(item()*)" \
            select="po:copyValue(po:source-queue(array:join(/s0:Order ! [Note ! string()])), '1', 'f1')"/>
                <ns0:Order>
                  <xsl:for-each select="1 to 1">
                    <xsl:variable name="slot2" as="xs:integer" select="($root - 1) * 1 + ."/>
                    <xsl:for-each select="po:generated($po:queue1)">
                      <xsl:variable name="value2" as="xs:string" select="."/>
                      <OrderId><xsl:value-of select="$value2"/></OrderId>
                    </xsl:for-each>
                  </xsl:for-each>
                </ns0:Order>
              </xsl:template>
            </xsl:stylesheet>
            """);

    // under an occurrence of Item: two copies of Name per Item, each its own slot number
    Name item = new Name("", "Item");
    AuthoringId t3 = id("t3");
    TargetRepeat names =
        new TargetRepeat(
            2,
            new VarRef(SLOT, AtomicType.INTEGER, t3),
            "slot3",
            Optional.of(copyValue("0", "f3", "a3")),
            Optional.of("value3"),
            new ElementConstructor(
                "",
                new Name("", "Name"),
                List.of(new TextConstructor(new VarRef("value3", AtomicType.STRING, t3), t3)),
                t3),
            t3);
    TargetDocument underAnOccurrence =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                nested(
                    new SourceQueue(List.of(ORDER, item), 1, true, id("a1")),
                    ROOT,
                    2,
                    item,
                    List.of(names))));
    IrVerifier.verify(underAnOccurrence);

    assertThat(XsltExporter.export(underAnOccurrence))
        .isEqualTo(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" \
            xmlns:xs="http://www.w3.org/2001/XMLSchema" \
            xmlns:array="http://www.w3.org/2005/xpath-functions/array" \
            xmlns:po="urn:flottila:mapping:po" xmlns:s0="urn:flottila:corpus:skeleton" \
            xmlns:ns0="urn:flottila:corpus:skeleton" exclude-result-prefixes="#all">
              <xsl:import href="po-runtime.xsl"/>
              <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="no" \
            omit-xml-declaration="no"/>
              <xsl:template match="/">
                <xsl:variable name="root" as="xs:integer" select="1"/>
                <xsl:variable name="po:queue1" as="array(item()*)" \
            select="po:copyValue(po:source-queue(array:join(/s0:Order ! [Note ! string()])), '0', 'f3')"/>
                <ns0:Order>
                  <xsl:for-each select="po:slots(po:source-queue(array:join(/s0:Order ! [Item ! ''])), $root, ())">
                    <xsl:variable name="slot2" as="xs:integer" select="?1"/>
                    <xsl:variable name="value2" as="xs:string" select="?2"/>
                    <Item>
                      <xsl:for-each select="1 to 2">
                        <xsl:variable name="slot3" as="xs:integer" select="($slot2 - 1) * 2 + ."/>
                        <xsl:for-each select="po:generated($po:queue1)">
                          <xsl:variable name="value3" as="xs:string" select="."/>
                          <Name><xsl:value-of select="$value3"/></Name>
                        </xsl:for-each>
                      </xsl:for-each>
                    </Item>
                  </xsl:for-each>
                </ns0:Order>
              </xsl:template>
            </xsl:stylesheet>
            """);
    assertThat(helperParamNames("po:generated")).containsExactly("block");

    // witness: a single STRING value, a constant's, is no block and is read without po:generated
    FunCall constant =
        new FunCall(
            new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING),
            List.of(new Literal("x", id("c1"))),
            id("c1"));
    TargetDocument single =
        new TargetDocument(
            "ns0",
            ORDER,
            ROOT,
            List.of(
                new TargetRepeat(
                    1,
                    new VarRef(ROOT, AtomicType.INTEGER, t1),
                    SLOT,
                    new ElementConstructor(
                        "", ORDER_ID, List.of(new TextConstructor(constant, t1)), t1),
                    t1)));
    assertThat(XsltExporter.export(single)).doesNotContain("po:generated", "po:queue1");
  }

  /**
   * The exporter reads the helper module's signatures (ADR-0026), so a FunCall whose function the
   * helper does not declare is a compiler bug, as the runtime's binding treats it.
   */
  @Test
  void aFunCallTheHelperLacksIsACompilerBug() {
    FunCall unknown = call("noSuchFunction", List.of(underRoot(ORDER, ORDER_ID, "a1")), 0, "f1");
    // witness: the same call of a function the helper declares exports
    assertThat(
            XsltExporter.export(
                fieldLinkWith(
                    removeContexts(underRoot(ORDER, ORDER_ID, "a1"), "f1"), List.of(valueText()))))
        .contains("po:removeContexts(");

    assertThatThrownBy(() -> XsltExporter.export(fieldLinkWith(unknown, List.of(valueText()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("noSuchFunction");
  }

  /** formatByExample can raise po:FORMATBYEXAMPLE, so its box id comes after its two queues. */
  @Test
  void formatByExampleTakesItsBoxIdLast() {
    String queue = "po:source-queue(array:join(/s0:Order ! [OrderId ! string()]))";
    FunCall formatByExample =
        call(
            "formatByExample",
            List.of(underRoot(ORDER, ORDER_ID, "a1"), underRoot(ORDER, ORDER_ID, "a2")),
            0,
            "f1");

    assertThat(XsltExporter.export(fieldLinkWith(formatByExample, List.of(valueText()))))
        .contains("po:slots(po:formatByExample(" + queue + ", " + queue + ", 'f1'), $root, 1)");
  }

  /**
   * How the exporter reads the helper's signatures (ADR-0026): a function's own params are the
   * {@code xsl:param} right after its start tag, in any attribute order, so the params of an {@code
   * xsl:iterate} in its body, as formatByExample has, are none of them; a function without params
   * ends in no box.
   */
  @Test
  void aSignatureIsTheFunctionsOwnParams() {
    String helper =
        """
        <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
            xmlns:xs="http://www.w3.org/2001/XMLSchema"
            xmlns:array="http://www.w3.org/2005/xpath-functions/array" xmlns:po="urn:flottila:mapping:po">
          <xsl:variable name="po:SUPPRESS" as="map(*)" select="map{}"/>
          <xsl:function name="po:formatLike" as="array(item()*)">
            <xsl:param name="values" as="array(item()*)"/>
            <xsl:param name="example" as="array(item()*)"/>
            <xsl:param name="box" as="xs:string"/>
            <xsl:variable name="all" as="item()*" select="$values?*"/>
            <xsl:iterate select="1 to array:size($example)">
              <xsl:param name="offset" as="xs:integer" select="0"/>
              <xsl:on-completion select="$values"/>
            </xsl:iterate>
          </xsl:function>
          <xsl:function name="po:copyLike" as="array(item()*)">
            <xsl:param name="queue" as="array(item()*)"/>
            <xsl:iterate select="1 to 2">
              <xsl:param name="box" as="xs:string" select="''"/>
              <xsl:on-completion select="$queue"/>
            </xsl:iterate>
          </xsl:function>
          <xsl:function as="xs:string" name="po:nameLast">
            <xsl:param as="xs:string" name="value"/>
            <xsl:param as="xs:string" name="box"/>
            <xsl:sequence select="$value"/>
          </xsl:function>
          <xsl:function name="po:none" as="xs:string">
            <xsl:sequence select="''"/>
          </xsl:function>
        </xsl:stylesheet>
        """;

    assertThat(XsltExporter.signatures(helper))
        .isEqualTo(
            Map.of(
                "po:formatLike", new XsltExporter.Signature(3, true),
                "po:copyLike", new XsltExporter.Signature(1, false),
                "po:nameLast", new XsltExporter.Signature(2, true),
                "po:none", new XsltExporter.Signature(0, false)));
  }

  /**
   * A FunCall whose args, with the box id where the helper takes it, are not as many as the helper
   * declares params is a compiler bug too (ADR-0026): removeContexts takes one, useOneAsMany three
   * and its box.
   */
  @Test
  void aFunCallOfAnotherArityThanTheHelpersIsACompilerBug() {
    SourceQueue a1 = underRoot(ORDER, ORDER_ID, "a1");
    // witness: removeContexts over one queue and useOneAsMany over three export
    assertThat(XsltExporter.export(fieldLinkWith(removeContexts(a1, "r1"), List.of(valueText()))))
        .contains("po:removeContexts(");
    assertThat(
            XsltExporter.export(
                fieldLinkWith(
                    call("useOneAsMany", List.of(a1, a1, a1), 0, "u1"), List.of(valueText()))))
        .contains("po:useOneAsMany(");

    for (FunCall wrong :
        List.of(
            call("removeContexts", List.of(a1, a1), 0, "r1"),
            call("useOneAsMany", List.of(a1, a1), 0, "u1"),
            call("useOneAsMany", List.of(a1, a1, a1, a1), 0, "u1"))) {
      assertThatThrownBy(() -> XsltExporter.export(fieldLinkWith(wrong, List.of(valueText()))))
          .as("%s over %d args", wrong.function().name(), wrong.args().size())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("po:" + wrong.function().name());
    }
  }

  /**
   * The raw value of the first {@code xsl:for-each}'s {@code select}, as written between quotes.
   */
  private static String forEachSelect(String stylesheet) {
    String start = "<xsl:for-each select=\"";
    int at = stylesheet.indexOf(start);
    assertThat(at).as("an xsl:for-each with a select").isNotNegative();
    int from = at + start.length();
    return stylesheet.substring(from, stylesheet.indexOf('"', from));
  }

  /**
   * The value an XML 1.0 parser gives for an attribute value written as {@code raw} in double
   * quotes: line ends normalised (§2.11), then references resolved and every literal white-space
   * character made a space (§3.3.3). A raw {@code <} or an {@code &} that starts no reference is no
   * well-formed XML.
   */
  private static String attributeValue(String raw) {
    String text = raw.replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      assertThat(c).as("a raw < in the attribute %s", raw).isNotEqualTo('<');
      if (c == '&') {
        int semicolon = text.indexOf(';', i);
        assertThat(semicolon).as("a reference at %d of %s", i, raw).isGreaterThan(i + 1);
        String name = text.substring(i + 1, semicolon);
        if (name.startsWith("#x")) {
          value.appendCodePoint(Integer.parseInt(name.substring(2), 16));
        } else if (name.startsWith("#")) {
          value.appendCodePoint(Integer.parseInt(name.substring(1)));
        } else {
          String entity =
              switch (name) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                default ->
                    throw new AssertionError("no predefined entity &" + name + "; in " + raw);
              };
          value.append(entity);
        }
        i = semicolon;
      } else if (c == ' ' || c == '\t' || c == '\n') {
        value.append(' ');
      } else {
        value.append(c);
      }
    }
    return value.toString();
  }

  /**
   * An XPath 3.1 StringLiteral starting at {@code at}: in apostrophes or quotation marks, where the
   * delimiter doubled stands for itself; its value and the index after it.
   */
  private record StringLiteral(String value, int end) {

    static StringLiteral at(String expression, int at) {
      char delimiter = expression.charAt(at);
      assertThat(delimiter).as("a string literal at %d of %s", at, expression).isIn('\'', '"');
      StringBuilder value = new StringBuilder();
      int i = at + 1;
      while (true) {
        assertThat(i)
            .as("the end of the literal in %s", expression)
            .isLessThan(expression.length());
        char c = expression.charAt(i);
        if (c == delimiter
            && i + 1 < expression.length()
            && expression.charAt(i + 1) == delimiter) {
          value.append(delimiter);
          i += 2;
        } else if (c == delimiter) {
          return new StringLiteral(value.toString(), i + 1);
        } else {
          value.append(c);
          i++;
        }
      }
    }
  }

  static Stream<Arguments> literals() {
    return Stream.of(
        Arguments.of("the empty string", ""),
        Arguments.of("plain text", "each value"),
        Arguments.of("an apostrophe", "it's"),
        Arguments.of("quotation marks", "say \"hi\""),
        Arguments.of("both delimiters, doubled", "'\"''\"\""),
        Arguments.of("markup characters", "a&b<c>d&amp;"),
        Arguments.of("a tab, a line feed and a carriage return", "a\tb\nc\rd"),
        Arguments.of("a CR LF pair and leading and trailing spaces", " a\r\nb "),
        Arguments.of("braces, which select never treats as a template", "{x}"),
        Arguments.of("non-ASCII and a character outside the BMP", "Grüße 😀"));
  }

  /**
   * A box parameter is a {@code Literal} argument. Read back as an XML parser and then an XPath
   * parser read it, the literal is the parameter's value, whatever characters it holds; how it is
   * quoted and escaped is the exporter's choice. The function name stands for any context function
   * with a parameter: the exporter binds by name only.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("literals")
  void aLiteralIsQuotedAndEscaped(String why, String value) {
    FunCall split =
        new FunCall(
            new FunctionRef("splitByValue", List.of(QUEUE, AtomicType.STRING), QUEUE),
            List.of(underRoot(ORDER, ORDER_ID, "a1"), new Literal(value, id("s1"))),
            id("s1"));
    String call =
        "po:at-least(po:slots(po:splitByValue(po:source-queue(array:join(/s0:Order ! [OrderId ! string()]))";

    String select =
        attributeValue(
            forEachSelect(XsltExporter.export(fieldLinkWith(split, List.of(valueText())))));

    assertThat(select).startsWith(call);
    int comma = select.indexOf(',', call.length());
    assertThat(select.substring(call.length(), comma)).as("before the second argument").isBlank();
    int start = comma + 1;
    while (select.charAt(start) == ' ') {
      start++;
    }
    StringLiteral literal = StringLiteral.at(select, start);
    assertThat(literal.value()).isEqualTo(value);
    assertThat(select.substring(literal.end()))
        .matches(" *\\) *, *\\$root *, *1 *\\) *, *1 *, *'t1' *\\)");
  }

  // --- the helper module's sources (ADR-0025 "Families", F-15): core.xsl and one module per
  // function family, which the build joins into po-runtime.xsl, core first, then the families by
  // id; ADR-0023's one-file export is unchanged

  private static final String HELPER_SOURCES = "engine/xslt-export/src/main/xsl/po-runtime";

  private static final String CORE = "core.xsl";

  /**
   * The packed helper module of main at 9c6fd1a, before the split, re-pinned once by #71 (ADR-0026
   * Consequences): core gains po:data-error, whose code is an {@code xs:QName} so that a family can
   * raise {@code err:FORG0001} through it, and the root declares {@code err} (#71 review); the five
   * declarations that raise a data error (formatByExample, sort, sortByKey, sort-contexts,
   * useOneAsMany) gain {@code box} as their last param and raise through it, and the header names
   * the box; every other byte is main's. #133 re-pins po:createIf alone (ADR-0006 as amended,
   * F-37): it casts as PO does and gains {@code box}. #218 re-pins po:collapseContexts,
   * po:sort-contexts and po:sortByKey-context alone (ADR-0006 as amended, F-47): collapseContexts
   * keeps a SUPPRESS first slot and gives no value for an empty queue, and a SUPPRESS key is the
   * greatest, as PO 7.50 and CPI do. #216 re-pins po:replaceValue alone (F-45): an observed rule
   * changes replaceValue's execution kind, so it takes and gives a queue. #219 re-pins po:exists,
   * po:mapWithDefault and po:useOneAsMany alone (ADR-0006 and ADR-0025 as amended, F-48): exists
   * gives true per value and SUPPRESS counts, mapWithDefault keeps a SUPPRESS-only context, and
   * useOneAsMany needs one value per context of its first input, as PO 7.50 and CPI do. The
   * assertions below are #69's, unchanged.
   */
  private static final String MAIN_HELPER_MODULE =
      "engine/xslt-export/src/test/resources/pinned-71/po-runtime.xsl";

  /** Surefire runs in the module directory: the root is the nearest ancestor holding mvnw. */
  private static Path repoRoot() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("mvnw")) && Files.isDirectory(p.resolve("contract"))) {
        return p;
      }
    }
    throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
  }

  private static final Pattern FUNCTION_NAME =
      Pattern.compile("<xsl:function\\s[^>]*?\\bname=\"([^\"]*)\"");

  /** The names of the {@code xsl:function} elements of {@code stylesheet}, in document order. */
  private static List<String> functionNames(String stylesheet) {
    List<String> names = new ArrayList<>();
    Matcher function = FUNCTION_NAME.matcher(stylesheet);
    while (function.find()) {
      names.add(function.group(1));
    }
    return names;
  }

  @Test
  void theHelperModuleIsCoreThenItsFamilyModules() throws Exception {
    // witness: a function's name is read whatever attribute comes first
    assertThat(
            functionNames(
                "<xsl:function name=\"po:a\" as=\"xs:string\"/><xsl:function as=\"item()\""
                    + " name=\"po:b\"/><xsl:variable name=\"po:c\"/>"))
        .containsExactly("po:a", "po:b");

    Path dir = repoRoot().resolve(HELPER_SOURCES);
    assertThat(dir).as("the helper module's sources").isDirectory();
    String core = Files.readString(dir.resolve(CORE), UTF_8);
    SortedMap<String, String> families = new TreeMap<>();
    List<Path> modules;
    try (Stream<Path> listing = Files.list(dir)) {
      modules = listing.filter(p -> p.getFileName().toString().endsWith(".xsl")).toList();
    }
    for (Path module : modules) {
      String name = module.getFileName().toString();
      if (!name.equals(CORE)) {
        families.put(
            name.substring(0, name.length() - ".xsl".length()), Files.readString(module, UTF_8));
      }
    }
    // core holds SUPPRESS, source-queue, slots, at-least and lift; today's eleven functions form
    // the family context (ADR-0025)
    assertThat(core).contains("<xsl:variable name=\"po:SUPPRESS\"");
    assertThat(functionNames(core))
        .contains("po:source-queue", "po:slots", "po:at-least", "po:lift");
    assertThat(families).as("the family modules beside %s", CORE).containsKey("context");
    assertThat(functionNames(families.get("context")))
        .contains("po:removeContexts", "po:replaceValue");

    // every module carries core's root start tag, byte for byte
    String coreTag = stylesheetStartTag(core);
    assertThat(coreTag).contains("xmlns:po=\"urn:flottila:mapping:po\"");
    families.forEach(
        (family, module) ->
            assertThat(stylesheetStartTag(module))
                .as("the root start tag of %s.xsl", family)
                .isEqualTo(coreTag));

    // core's functions, then each family's, the families by id, each declared once
    List<String> expected = new ArrayList<>(functionNames(core));
    families.values().forEach(module -> expected.addAll(functionNames(module)));
    assertThat(expected).as("the functions the modules declare").doesNotHaveDuplicates();
    String packed = XsltExporter.helperModule();

    assertThat(functionNames(packed))
        .as("the packed helper module's functions")
        .containsExactlyElementsOf(expected);
    // one root, core's, and core's prolog up to it begins the packed module
    assertThat(occurrences(packed, coreTag))
        .as("core's root start tag in the packed module")
        .isEqualTo(1);
    assertThat(packed).startsWith(core.substring(0, core.indexOf(coreTag) + coreTag.length()));
  }

  /** The index just past the {@code >} of the tag that starts at {@code start}, quotes skipped. */
  private static int tagEnd(String module, int start) {
    char quote = 0;
    for (int i = start + 1; i < module.length(); i++) {
      char c = module.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return i + 1;
      }
    }
    throw new AssertionError("a tag at " + start + " never ends");
  }

  /**
   * The spans {@code [start, end)} of a module's tags: first the root's start tag, then each
   * top-level declaration inside the root, a whole element. Comments, processing instructions and
   * CDATA sections are skipped.
   */
  private static List<int[]> declarations(String module) {
    List<int[]> spans = new ArrayList<>();
    int depth = 0;
    int start = -1;
    for (int i = module.indexOf('<'); i >= 0; i = module.indexOf('<', i)) {
      if (module.startsWith("<!--", i)) {
        i = module.indexOf("-->", i) + "-->".length();
      } else if (module.startsWith("<?", i)) {
        i = module.indexOf("?>", i) + "?>".length();
      } else if (module.startsWith("<![CDATA[", i)) {
        i = module.indexOf("]]>", i) + "]]>".length();
      } else {
        int end = tagEnd(module, i);
        if (module.charAt(i + 1) == '/') {
          depth--;
          if (depth == 1) {
            spans.add(new int[] {start, end});
          }
        } else if (module.charAt(end - 2) == '/') {
          if (depth == 1) {
            spans.add(new int[] {i, end});
          }
        } else {
          if (depth == 0) {
            spans.add(new int[] {i, end});
          } else if (depth == 1) {
            start = i;
          }
          depth++;
        }
        i = end;
      }
    }
    return spans;
  }

  /** The text of each top-level declaration of {@code module}. */
  private static List<String> declarationTexts(String module) {
    List<int[]> spans = declarations(module);
    return spans.subList(1, spans.size()).stream()
        .map(span -> module.substring(span[0], span[1]))
        .toList();
  }

  /**
   * {@code module} without each top-level declaration whose text is one of {@code removed}, each
   * taken out together with what stands between it and the declaration (or the root start tag)
   * before it: its comments and the whitespace ahead of it.
   */
  private static String withoutDeclarations(String module, Set<String> removed) {
    List<int[]> spans = declarations(module);
    int previous = spans.getFirst()[1];
    StringBuilder kept = new StringBuilder(module.substring(0, previous));
    for (int[] span : spans.subList(1, spans.size())) {
      if (!removed.contains(module.substring(span[0], span[1]))) {
        kept.append(module, previous, span[1]);
      }
      previous = span[1];
    }
    return kept.append(module.substring(previous)).toString();
  }

  /** A top-level declaration's element and its {@code name}, empty when it has none. */
  private static String declarationName(String declaration) {
    String startTag = declaration.substring(0, tagEnd(declaration, 0));
    Matcher element = Pattern.compile("\\A<([^\\s/>]+)").matcher(startTag);
    Matcher name = Pattern.compile("\\sname=\"([^\"]*)\"").matcher(startTag);
    return (element.find() ? element.group(1) : "") + " " + (name.find() ? name.group(1) : "");
  }

  /**
   * {@code module} without each top-level declaration whose element and name {@code main} does not
   * declare, each taken out with the comments and whitespace ahead of it.
   */
  private static String restrictedTo(String module, String main) {
    Set<String> mains =
        new HashSet<>(
            declarationTexts(main).stream().map(XsltExporterTest::declarationName).toList());
    Set<String> removed =
        new HashSet<>(
            declarationTexts(module).stream()
                .filter(declaration -> !mains.contains(declarationName(declaration)))
                .toList());
    return withoutDeclarations(module, removed);
  }

  /**
   * ADR-0026 re-pins main's copy once (see {@link #MAIN_HELPER_MODULE}); the assertions are #69's.
   *
   * <p>The split changes no byte of what spec 2 shipped (ADR-0025 "Families", ADR-0023): the packed
   * helper module, every top-level declaration that main's copy before the split does not declare
   * taken out with the comments and whitespace ahead of it, is main's copy, byte for byte. A
   * declaration is known by its element and its name, so one of main's that changes, moves or goes
   * fails this, and one that core or a family module adds leaves it as it is: spec 3 adds shared
   * helpers to core.xsl (#74) and may not edit this test.
   */
  @Test
  void thePackedHelperModuleOfCoreAndTheContextFamilyIsMainsByteForByte() throws Exception {
    // witness: a declaration goes with its comment and the whitespace ahead of it, and only it
    String sample =
        "<?xml version=\"1.0\"?>\n<!-- c -->\n<r a=\"x>y\">\n\n  <!-- one -->\n"
            + "  <f n=\"1\"><p>x</p><q/></f>\n\n  <v n=\"2\"/>\n\n  <!-- three -->\n  <f n=\"3\">t</f>\n</r>\n";
    assertThat(declarationTexts(sample))
        .containsExactly("<f n=\"1\"><p>x</p><q/></f>", "<v n=\"2\"/>", "<f n=\"3\">t</f>");
    assertThat(withoutDeclarations(sample, Set.of())).isEqualTo(sample);
    assertThat(withoutDeclarations(sample, Set.of("<v n=\"2\"/>")))
        .isEqualTo(
            "<?xml version=\"1.0\"?>\n<!-- c -->\n<r a=\"x>y\">\n\n  <!-- one -->\n"
                + "  <f n=\"1\"><p>x</p><q/></f>\n\n  <!-- three -->\n  <f n=\"3\">t</f>\n</r>\n");
    assertThat(withoutDeclarations(sample, Set.of("<f n=\"1\"><p>x</p><q/></f>")))
        .isEqualTo(
            "<?xml version=\"1.0\"?>\n<!-- c -->\n<r a=\"x>y\">\n\n  <v n=\"2\"/>\n\n"
                + "  <!-- three -->\n  <f n=\"3\">t</f>\n</r>\n");
    // witness: a declaration is its element and its name, whatever attribute comes first
    assertThat(declarationName("<xsl:function as=\"xs:string\" name=\"po:a\"><p name=\"b\"/>"))
        .isEqualTo("xsl:function po:a");

    byte[] main = Files.readAllBytes(repoRoot().resolve(MAIN_HELPER_MODULE));
    String mains = new String(main, UTF_8);
    // witness: main's copy is the helper module of spec 2, with po:useOneAsMany
    assertThat(functionNames(mains)).contains("po:slots", "po:useOneAsMany");
    // witness: a declaration that core adds is taken out (#74); one of main's that changes or goes
    // is not, so the restriction is no longer main's copy
    String added =
        mains.replace(
            "\n  <!-- A SourceQueue",
            "\n  <!-- A shared helper of core. -->\n"
                + "  <xsl:function name=\"po:shared-helper\" as=\"xs:string\"/>\n\n"
                + "  <!-- A SourceQueue");
    assertThat(added).isNotEqualTo(mains);
    assertThat(restrictedTo(added, mains)).isEqualTo(mains);
    String changed = mains.replace("then [()] else $queue", "then [] else $queue");
    assertThat(changed).isNotEqualTo(mains);
    assertThat(restrictedTo(changed, mains)).isNotEqualTo(mains);
    String gone = withoutDeclarations(mains, Set.of(declarationTexts(mains).get(1)));
    assertThat(restrictedTo(gone, mains)).isNotEqualTo(mains);

    byte[] packed;
    try (InputStream in = XsltExporter.class.getResourceAsStream(XsltExporter.HELPER_MODULE)) {
      assertThat(in).as("resource io/flottila/mapping/xslt/export/po-runtime.xsl").isNotNull();
      packed = in.readAllBytes();
    }
    assertThat(XsltExporter.helperModule().getBytes(UTF_8)).isEqualTo(packed);

    String restricted = restrictedTo(new String(packed, UTF_8), mains);

    assertThat(restricted).isEqualTo(mains);
    assertThat(restricted.getBytes(UTF_8)).isEqualTo(main);
  }
}
