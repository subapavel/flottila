package io.flottila.runtime;

import io.flottila.core.Body;
import io.flottila.core.Envelope;
import io.flottila.core.Step;
import io.flottila.core.XmlStep;
import io.smallrye.mutiny.Uni;
import net.sf.saxon.s9api.*;

import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.Executor;

/** Saxon-HE helpers: parsed-view caching, XPath, XSLT — all CPU work goes to the worker pool. */
public final class Xml {
    public final Processor processor = new Processor(false);
    private final Executor cpuPool;

    public Xml(Executor cpuPool) { this.cpuPool = cpuPool; }

    /** Memoized on the envelope; a second step on the same body pays nothing. */
    public XdmNode tree(Envelope e) {
        return e.view(XdmNode.class, body -> {
            try (InputStream in = body.open()) {
                return processor.newDocumentBuilder().build(new StreamSource(in));
            } catch (Exception ex) { throw new IllegalArgumentException("not well-formed XML: " + ex.getMessage(), ex); }
        });
    }

    public XPathSelector xpath(String expr) {
        try { return processor.newXPathCompiler().compile(expr).load(); }
        catch (SaxonApiException ex) { throw new IllegalArgumentException(expr, ex); }
    }

    public String evalString(String expr, Envelope e) {
        try { XPathSelector s = xpath(expr); s.setContextItem(tree(e)); XdmItem i = s.evaluateSingle(); return i == null ? null : i.getStringValue(); }
        catch (SaxonApiException ex) { throw new IllegalStateException(ex); }
    }

    public Body serialize(XdmNode node) {
        try {
            var out = new ByteArrayOutputStream();
            Serializer ser = processor.newSerializer(out);
            ser.setOutputProperty(Serializer.Property.INDENT, "yes");
            ser.serializeNode(node);
            return Body.of(out.toByteArray());
        } catch (SaxonApiException ex) { throw new IllegalStateException(ex); }
    }

    /** Wrap an XmlStep into a Step: parse (cached) → apply → serialize, on the CPU pool. */
    public Step step(XmlStep f) {
        return e -> Uni.createFrom().item(() -> {
            try { return e.next(serialize(f.apply(tree(e), e.headers().asMap()))); }
            catch (RuntimeException ex) { throw ex; }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        }).runSubscriptionOn(cpuPool);
    }

    public Step xslt(String stylesheet) {
        XsltExecutable exec;
        try { exec = processor.newXsltCompiler().compile(new StreamSource(Xml.class.getResourceAsStream(stylesheet))); }
        catch (SaxonApiException ex) { throw new IllegalArgumentException(stylesheet, ex); }
        return step((in, h) -> {
            Xslt30Transformer t = exec.load30();
            XdmDestination dest = new XdmDestination();
            t.applyTemplates(in, dest);
            return dest.getXdmNode();
        });
    }
}
