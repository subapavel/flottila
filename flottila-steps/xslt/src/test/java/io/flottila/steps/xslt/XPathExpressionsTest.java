package io.flottila.steps.xslt;

import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Fault;
import io.flottila.api.Headers;
import io.flottila.api.StepResources;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The xpath language: conditions and values over the document view, compiled at build time. */
class XPathExpressionsTest {

    final FakeRuntime rt = FakeRuntime.create();
    final SaxonXml xml = new SaxonXml();
    final Expressions xpath = xml.languages().get(0);
    final StepResources res = new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), 4096, Path.of(""));
    final Envelope czk = rt.ingress("f", Headers.empty(), Buffer.buffer("<Invoice><Id>4711</Id><Currency>CZK</Currency></Invoice>"));

    @Test
    void conditionsAreEffectiveBooleanValuesAndValuesAreTheFirstItemsStringValue() {
        assertEquals("xpath", xpath.language());
        assertTrue(xpath.predicate("/Invoice/Currency = 'CZK'", res).test(czk));
        assertFalse(xpath.predicate("/Invoice/Currency = 'EUR'", res).test(czk));
        assertTrue(xpath.predicate("/Invoice/Id", res).test(czk), "a non-empty node set is true");
        assertFalse(xpath.predicate("/Invoice/Missing", res).test(czk));
        assertEquals(Optional.of("4711"), xpath.value("/Invoice/Id", res).apply(czk));
        assertEquals(Optional.of("4711CZK"), xpath.value("string-join(/Invoice/*, '')", res).apply(czk), "XPath 3.1 functions");
        assertEquals(Optional.empty(), xpath.value("/Invoice/Missing", res).apply(czk));
        assertEquals(Optional.of("4711"), xpath.value("/Invoice/*", res).apply(czk), "several items: the first");
    }

    @Test
    void aBadExpressionFailsAtBuildTimeAndABadBodyOrDynamicErrorFailsTheMessage() {
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> xpath.predicate("/Invoice/[", res));
        assertTrue(bad.getMessage().contains("/Invoice/["), bad.getMessage());
        assertThrows(IllegalArgumentException.class, () -> xpath.value("1 +", res));
        assertThrows(NullPointerException.class, () -> xpath.value(null, res));
        Envelope notXml = rt.ingress("f", Headers.empty(), Buffer.buffer("<open>"));
        XmlException parse = assertThrows(XmlException.class, () -> xpath.predicate("true()", res).test(notXml));
        assertEquals(notXml.id(), parse.messageId());
        XmlException dynamic = assertThrows(XmlException.class, () -> xpath.value("error(xs:QName('err:X'), 'dyn')", res).apply(czk));
        assertTrue(dynamic.getMessage().contains("dyn"), dynamic.getMessage());
        XmlException dynamicTest = assertThrows(XmlException.class, () -> xpath.predicate("error(xs:QName('err:X'), 'dyn2')", res).test(czk));
        assertTrue(dynamicTest.getMessage().contains("dyn2"), dynamicTest.getMessage());
    }

    @Test
    void aDynamicXPathErrorIsAnInternalFault() {
        XmlException test = assertThrows(XmlException.class, () -> xpath.predicate("error(xs:QName('err:X'), 'dyn')", res).test(czk));
        assertEquals(Fault.Kind.INTERNAL, assertInstanceOf(Fault.class, test).kind(), "a well-formed body the expression fails on is the flow's failure, 500");
        XmlException value = assertThrows(XmlException.class, () -> xpath.value("1 div 0", res).apply(czk));   // FOAR0001
        assertEquals(Fault.Kind.INTERNAL, value.kind());
        XmlException parts = assertThrows(XmlException.class, () -> xpath.parts("error(xs:QName('err:X'), 'dyn')", res).apply(czk));
        assertEquals(Fault.Kind.INTERNAL, parts.kind());
        XmlException notANode = assertThrows(XmlException.class, () -> xpath.parts("'text'", res).apply(czk));
        assertEquals(Fault.Kind.INTERNAL, notANode.kind(), "an expression that selects no nodes to split is the flow's failure too");
    }
}
