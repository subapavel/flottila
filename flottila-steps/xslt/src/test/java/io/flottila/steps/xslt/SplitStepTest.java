package io.flottila.steps.xslt;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.BodyKind;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Headers;
import io.flottila.api.StepResources;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code split} step from a flow file (P1-07): the xpath language cuts the body into parts — every selected
 * node serialized as a child body, stored under the parent's id and the split's step as {@code split-<i>} so the
 * bytes exist once and the children reference them (ARCHITECTURE §4, §5).
 */
class SplitStepTest {

    final FakeRuntime rt = FakeRuntime.create();
    final SaxonXml xml = new SaxonXml();
    final Expressions xpath = xml.languages().get(0);

    StepResources resources(long maxBodyBytes) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), maxBodyBytes, Path.of(""));
    }

    Envelope invoice() {
        return rt.ingress("invoice-sync", Headers.empty(), Buffer.buffer(XsltStepFactoryTest.INVOICE)).advance();
    }

    @Test
    void everySelectedNodeBecomesAPartStoredUnderTheParentsIdAndTheSplitsStep() {
        Envelope parent = invoice();
        List<Body> parts = xpath.parts("/Invoice/Lines/Line", resources(4096)).apply(parent);
        assertEquals(2, parts.size());
        assertEquals("<Line><Sku>A-1</Sku><Qty>2</Qty><Price>100</Price></Line>", parts.get(0).whole().toString(), "serialized without a declaration");
        assertEquals("<Line><Sku>B-7</Sku><Qty>1</Qty><Price>350.5</Price></Line>", parts.get(1).whole().toString());
        assertEquals(BlobKey.attachment(parent.id(), 2, "split-0"), parts.get(0).key(), "the parent's id, the child's step, the part's index");
        assertEquals(BlobKey.attachment(parent.id(), 2, "split-1"), parts.get(1).key());
        assertTrue(rt.blobs().contains(parts.get(1).key()), "the part is in the store, not only in memory");
        assertSame(xml.document(parent), xml.document(parent), "the parent's document view is parsed once");
    }

    /**
     * D-066: every part is stored under the parent's next key for its {@code split-<i>} part — suffixed with the attempt from
     * the second attempt on — so a rerun's parts never write over the first run's.
     */
    @Test
    void thePartsOfASecondAttemptAreKeyedByItsAttempt() {
        Envelope parent = invoice();
        List<Body> one = xpath.parts("/Invoice/Lines/Line", resources(4096)).apply(parent);
        List<Body> two = xpath.parts("/Invoice/Lines/Line", resources(4096)).apply(parent.with(Envelope.ATTEMPT, 2));
        assertEquals(List.of(BlobKey.attachment(parent.id(), 2, "split-0"), BlobKey.attachment(parent.id(), 2, "split-1")),
                one.stream().map(Body::key).toList(), "a first attempt: the keys of before");
        assertEquals(List.of(BlobKey.attachment(parent.id(), 2, "split-0~2"), BlobKey.attachment(parent.id(), 2, "split-1~2")),
                two.stream().map(Body::key).toList(), "the second attempt: each part's name suffixed with it");
        for (int i = 0; i < 2; i++) {
            assertTrue(rt.blobs().contains(one.get(i).key()) && rt.blobs().contains(two.get(i).key()), "part " + i + " stored for both runs");
            assertEquals(one.get(i).hash(), two.get(i).hash(), "part " + i + ": the same bytes, only the key tells the runs apart");
        }
    }

    @Test
    void anEmptySelectionIsNoPartsAndATextNodeIsAPartOfItsOwn() {
        assertEquals(List.of(), xpath.parts("/Invoice/Nothing", resources(4096)).apply(invoice()));
        List<Body> ids = xpath.parts("/Invoice/Id/text()", resources(4096)).apply(invoice());
        assertEquals(1, ids.size());
        assertEquals("4711", ids.get(0).whole().toString());
    }

    @Test
    void anAtomicResultABadBodyAndAPartOverTheFlowsLimitFailTheMessageAndABadExpressionTheBuild() {
        Envelope parent = invoice();
        XmlException atomic = assertThrows(XmlException.class, () -> xpath.parts("count(/Invoice/Lines/Line)", resources(4096)).apply(parent));
        assertEquals(parent.id(), atomic.messageId());
        assertTrue(atomic.getMessage().contains("not a node"), atomic.getMessage());
        Envelope notXml = rt.ingress("f", Headers.empty(), Buffer.buffer("<open>"));
        assertEquals(notXml.id(), assertThrows(XmlException.class, () -> xpath.parts("/a", resources(4096)).apply(notXml)).messageId());
        assertThrows(BodyTooLargeException.class, () -> xpath.parts("/Invoice/Lines/Line", resources(16)).apply(parent), "a part is a body under the flow's limit");
        XmlException dynamic = assertThrows(XmlException.class, () -> xpath.parts("error(xs:QName('err:X'), 'dyn')", resources(4096)).apply(parent));
        assertTrue(dynamic.getMessage().contains("dyn") && dynamic.getMessage().contains("failed"), dynamic.getMessage());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> xpath.parts("/Invoice/[", resources(4096)));
        assertTrue(bad.getMessage().contains("/Invoice/["), bad.getMessage());
    }

    @Test
    void theXmlStepsReadTheWholeBody() {
        assertEquals(BodyKind.WHOLE, xpath.bodyKind(), "xpath needs the document view");
        assertEquals(BodyKind.WHOLE, xml.steps().get(0).bodyKind(), "xslt on Saxon-HE needs the document view; streaming is Saxon-EE territory");
    }
}
