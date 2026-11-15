package io.flottila.mapping.xslt.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.InputFormat;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The export of a JSON input (ADR-0008 "JSON as a source at run time", #98) at {@code
 * XsltExporter}: the stylesheet is started at its initial template with the param {@code po:input},
 * reads {@code json-to-xml(unparsed-text($po:input), map{'duplicates': 'reject'})}, and its queues
 * navigate that representation. Whether it runs as the runtime does is the differential oracle's
 * question ({@code engine/oracle}).
 */
class JsonExportTest {

  private static final Name OUT = new Name("", "Out");
  private static final Name ALL = new Name("", "All");
  private static final Name ROOT = new Name("", "$");
  private static final long UNBOUNDED = Long.MAX_VALUE;

  private static Name property(String name) {
    return new Name("", name);
  }

  /** {@code <All>{value}</All>} per slot of each queue, under the root, in a JSON input's IR. */
  private static TargetDocument json(SourceQueue... queues) {
    List<Expr> content = new ArrayList<>();
    for (int k = 0; k < queues.length; k++) {
      AuthoringId origin = new AuthoringId("t" + (k + 1));
      String value = "value" + k;
      content.add(
          new TargetSlots(
              queues[k],
              new VarRef("root", AtomicType.INTEGER, origin),
              0,
              UNBOUNDED,
              "slot" + k,
              value,
              new ElementConstructor(
                  "",
                  ALL,
                  List.of(
                      new TextConstructor(new VarRef(value, AtomicType.STRING, origin), origin)),
                  origin),
              origin));
    }
    TargetDocument document = new TargetDocument("", OUT, "root", content, InputFormat.JSON);
    IrVerifier.verify(document);
    return document;
  }

  private static SourceQueue queue(
      List<Name> field, int contextDepth, boolean structureField, List<Integer> arrays) {
    return new SourceQueue(
        field, contextDepth, structureField, false, arrays, new AuthoringId("s"));
  }

  /**
   * The stylesheet of {@code $/tags}, an array of strings: {@code fn} declared after {@code po},
   * the param and the initial template between the output and the template, and the items of the
   * array as the leaf's occurrences.
   */
  private static final String TAGS_STYLESHEET =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" \
      xmlns:xs="http://www.w3.org/2001/XMLSchema" \
      xmlns:array="http://www.w3.org/2005/xpath-functions/array" \
      xmlns:po="urn:flottila:mapping:po" xmlns:fn="http://www.w3.org/2005/xpath-functions" \
      exclude-result-prefixes="#all">
        <xsl:import href="po-runtime.xsl"/>
        <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="no" \
      omit-xml-declaration="no"/>
        <xsl:param name="po:input" as="xs:string" required="yes"/>
        <xsl:template name="xsl:initial-template">
          <xsl:apply-templates select="json-to-xml(unparsed-text($po:input), \
      map{'duplicates': 'reject'})"/>
        </xsl:template>
        <xsl:template match="/">
          <xsl:variable name="root" as="xs:integer" select="1"/>
          <Out>
            <xsl:for-each \
      select="po:slots(po:source-queue(array:join(/fn:map ! [fn:array[@key='tags']/\
      (fn:string|fn:number|fn:boolean|fn:null) ! string()])), $root, ())">
              <xsl:variable name="slot0" as="xs:integer" select="?1"/>
              <xsl:variable name="value0" as="xs:string" select="?2"/>
              <All><xsl:value-of select="$value0"/></All>
            </xsl:for-each>
          </Out>
        </xsl:template>
      </xsl:stylesheet>
      """;

  @Test
  void aJsonInputIsReadThroughJsonToXmlFromTheInitialTemplate() {
    assertThat(
            XsltExporter.export(json(queue(List.of(ROOT, property("tags")), 1, false, List.of(2)))))
        .isEqualTo(TAGS_STYLESHEET);
  }

  @Test
  void anObjectIsAMapByItsKeyAndALeafAnyScalar() {
    String stylesheet =
        XsltExporter.export(
            json(queue(List.of(ROOT, property("order"), property("id")), 2, false, List.of())));

    // the root's map has no key; the context order is a map by its key, the leaf id any scalar
    assertThat(stylesheet)
        .contains(
            "array:join(/fn:map/fn:map[@key='order'] ! "
                + "[(fn:string|fn:number|fn:boolean|fn:null)[@key='id'] ! string()])");
  }

  @Test
  void anArrayOfObjectsIsEachMapOfItsArray() {
    String stylesheet =
        XsltExporter.export(
            json(
                queue(List.of(ROOT, property("lines")), 1, true, List.of(2)),
                queue(List.of(ROOT, property("lines"), property("sku")), 2, false, List.of(2))));

    // as a structure field each map gives '', as a context each map opens one
    assertThat(stylesheet)
        .contains(
            "array:join(/fn:map ! [fn:array[@key='lines']/fn:map ! ''])",
            "array:join(/fn:map/fn:array[@key='lines']/fn:map ! "
                + "[(fn:string|fn:number|fn:boolean|fn:null)[@key='sku'] ! string()])");
  }

  @Test
  void aStructureFieldThatIsNoArrayIsItsMap() {
    String stylesheet =
        XsltExporter.export(json(queue(List.of(ROOT, property("order")), 1, true, List.of())));

    assertThat(stylesheet).contains("array:join(/fn:map ! [fn:map[@key='order'] ! ''])");
  }

  @Test
  void aRootArrayHasNoKey() {
    String stylesheet =
        XsltExporter.export(json(queue(List.of(ROOT, property("v")), 1, false, List.of(1))));

    assertThat(stylesheet)
        .contains(
            "array:join(/fn:array/fn:map ! "
                + "[(fn:string|fn:number|fn:boolean|fn:null)[@key='v'] ! string()])");
  }

  @Test
  void aKeyIsAStringLiteralEscapedForItsAttribute() {
    String stylesheet =
        XsltExporter.export(
            json(queue(List.of(ROOT, property("it's \"<&>\"")), 1, false, List.of())));

    // the apostrophe doubled in the literal, then the literal escaped for the select attribute
    assertThat(stylesheet).contains("[@key='it''s &quot;&lt;&amp;>&quot;']");
  }
}
