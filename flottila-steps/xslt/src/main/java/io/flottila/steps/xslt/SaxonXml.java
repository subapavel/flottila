package io.flottila.steps.xslt;

import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.StepFactory;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;

import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * The XML steps on Saxon-HE (ARCHITECTURE §13): one {@link Processor} shared by the {@code xslt} step and the
 * {@code xpath} expression language, and the parsed view of a body — an {@link XdmNode} computed once per body
 * and shared by every envelope that carries it ({@link Envelope#view}). A body is parsed from
 * {@link Body#whole()}: a tree view exists for bodies under the store's tree limit; streaming transformation is
 * Saxon-EE territory and not here.
 *
 * <p>Create one per runtime and register {@link #steps()} and {@link #languages()} with the flow builder.
 *
 * @since 0.1
 */
public final class SaxonXml {

    private final Processor processor = new Processor(false);

    public Processor processor() { return processor; }

    /** The step factories of this module: {@code xslt}. */
    public List<StepFactory> steps() { return List.of(new XsltStepFactory(this)); }

    /** The expression languages of this module: {@code xpath}. */
    public List<Expressions> languages() { return List.of(new XPathExpressions(this)); }

    /** The envelope's body as a document, parsed once per body. */
    public XdmNode document(Envelope e) {
        return e.view(XdmNode.class, body -> parse(e.id(), body));
    }

    private XdmNode parse(String messageId, Body body) {
        byte[] bytes = body.whole().getBytes();
        try {
            return processor.newDocumentBuilder().build(new StreamSource(new ByteArrayInputStream(bytes)));
        } catch (SaxonApiException e) {
            throw new XmlException(messageId, io.flottila.api.Fault.Kind.MALFORMED, "body is not well-formed XML", e);
        }
    }
}
