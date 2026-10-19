package io.flottila.steps.xslt;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.StepResources;
import io.vertx.core.buffer.Buffer;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * XPath 3.1 over the body's document view: the {@code xpath} language of the flow's {@code route}, {@code filter},
 * {@code extractKey}, {@code validate} and {@code split}. Compiled at build time (a bad expression is an
 * {@link IllegalArgumentException} there); a condition is the effective boolean value, a value is the string value
 * of the first selected item, empty when nothing is selected; the parts of a split are the selected nodes, each
 * serialized (no XML declaration) and stored as an attachment of the parent under the child's step —
 * {@code split-<i>} — within the flow's body limit. An atomic item cannot be a part: the message fails.
 *
 * @since 0.1
 */
public final class XPathExpressions implements Expressions {

    private final SaxonXml xml;

    XPathExpressions(SaxonXml xml) { this.xml = Objects.requireNonNull(xml, "xml"); }

    @Override public String language() { return "xpath"; }

    @Override
    public Predicate<Envelope> predicate(String expression, StepResources resources) {
        XPathExecutable compiled = compile(expression);
        return e -> {
            try {
                return selector(compiled, e).effectiveBooleanValue();
            } catch (SaxonApiException x) {
                throw new XmlException(e.id(), "xpath '" + expression + "' failed", x);
            }
        };
    }

    @Override
    public Function<Envelope, Optional<String>> value(String expression, StepResources resources) {
        XPathExecutable compiled = compile(expression);
        return e -> {
            try {
                XdmValue v = selector(compiled, e).evaluate();
                return v.size() == 0 ? Optional.empty() : Optional.of(v.itemAt(0).getStringValue());
            } catch (SaxonApiException x) {
                throw new XmlException(e.id(), "xpath '" + expression + "' failed", x);
            }
        };
    }

    @Override
    public Function<Envelope, List<Body>> parts(String expression, StepResources resources) {
        XPathExecutable compiled = compile(expression);
        long maxBodyBytes = resources.maxBodyBytes();
        return e -> {
            List<Body> parts = new ArrayList<>();
            try {
                int i = 0;
                for (XdmItem item : selector(compiled, e).evaluate()) {
                    if (!(item instanceof XdmNode node)) {
                        throw new XmlException(e.id(), "xpath '" + expression + "' selected an item that is not a node", new SaxonApiException(String.valueOf(item)));
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    Serializer serializer = xml.processor().newSerializer(out);
                    serializer.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
                    serializer.serializeNode(node);
                    BlobKey key = BlobKey.attachment(e.id(), e.step() + 1, "split-" + i++);
                    parts.add(resources.blobs().write(key, Buffer.buffer(out.toByteArray()), maxBodyBytes).await());
                }
            } catch (SaxonApiException x) {
                throw new XmlException(e.id(), "xpath '" + expression + "' failed", x);
            }
            return List.copyOf(parts);
        };
    }

    private XPathExecutable compile(String expression) {
        Objects.requireNonNull(expression, "expression");
        try {
            return xml.processor().newXPathCompiler().compile(expression);
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("not a valid xpath '" + expression + "': " + e.getMessage(), e);
        }
    }

    private XPathSelector selector(XPathExecutable compiled, Envelope e) throws SaxonApiException {
        XPathSelector s = compiled.load();
        s.setContextItem(xml.document(e));
        return s;
    }
}
