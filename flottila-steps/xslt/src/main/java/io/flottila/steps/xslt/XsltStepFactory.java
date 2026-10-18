package io.flottila.steps.xslt;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.vertx.core.buffer.Buffer;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltExecutable;

import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code xslt} step: {@code stylesheet} (a path relative to the flow file) compiled at build time — by content
 * hash, so a stylesheet shared by several flows compiles once — and applied to the body's document view; the
 * result is the next step's body, written to the blob store under the flow's body limit, with
 * {@code content-type} from the stylesheet's output method.
 *
 * @since 0.1
 */
public final class XsltStepFactory implements StepFactory {

    static final String CONFIG_SCHEMA = """
            {"type":"object","required":["stylesheet"],"properties":{"stylesheet":{"type":"string","minLength":1}}}""";

    private final SaxonXml xml;
    private final Map<Hash, XsltExecutable> compiled = new ConcurrentHashMap<>();

    XsltStepFactory(SaxonXml xml) { this.xml = xml; }

    @Override public String type() { return "xslt"; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    /** Stylesheets compiled so far, by content hash. */
    public int compiledStylesheets() { return compiled.size(); }

    @Override
    public Stage create(StepConfig step, StepResources resources) {
        Path file = resources.file(step.requireString("stylesheet"));
        XsltExecutable executable = compile(step, file, resources);
        long maxBodyBytes = resources.maxBodyBytes();
        return Stage.of(e -> {
            byte[] out;
            String contentType;
            try {
                Xslt30Transformer t = executable.load30();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                Serializer serializer = t.newSerializer(buffer);
                t.transform(xml.document(e).asSource(), serializer);
                out = buffer.toByteArray();
                contentType = contentType(serializer.getOutputProperty(Serializer.Property.METHOD));
            } catch (SaxonApiException x) {
                throw new XmlException(e.id(), step + " failed", x);
            }
            Body body = resources.blobs().write(BlobKey.body(e.id(), e.step() + 1), Buffer.buffer(out), maxBodyBytes).await();
            return e.next(body).withHeader(Envelope.H_CONTENT_TYPE, contentType);
        });
    }

    private XsltExecutable compile(StepConfig step, Path file, StepResources resources) {
        byte[] source;
        try {
            source = Files.readAllBytes(file);
        } catch (NoSuchFileException x) {
            throw new IllegalArgumentException(step + ": stylesheet not found: " + file, x);
        } catch (IOException x) {
            throw new IllegalArgumentException(step + ": stylesheet " + file + " cannot be read: " + x.getMessage(), x);
        }
        Hash hash = resources.hasher().hashOf(Buffer.buffer(source));
        XsltExecutable cached = compiled.get(hash);
        if (cached != null) return cached;
        try {
            StreamSource in = new StreamSource(new ByteArrayInputStream(source));
            in.setSystemId(file.toUri().toString());   // xsl:import / xsl:include resolve next to the file
            XsltExecutable fresh = xml.processor().newXsltCompiler().compile(in);
            XsltExecutable raced = compiled.putIfAbsent(hash, fresh);
            return raced == null ? fresh : raced;
        } catch (SaxonApiException x) {
            throw new IllegalArgumentException(step + ": stylesheet " + file + " does not compile: " + x.getMessage(), x);
        }
    }

    /** The reply's content type from the stylesheet's output method; {@code application/xml} when it declares none. */
    static String contentType(String method) {
        if (method == null) return "application/xml";
        return switch (method) {
            case "text" -> "text/plain";
            case "json" -> "application/json";
            case "html" -> "text/html";
            case "xhtml" -> "application/xhtml+xml";
            default -> "application/xml";
        };
    }
}
