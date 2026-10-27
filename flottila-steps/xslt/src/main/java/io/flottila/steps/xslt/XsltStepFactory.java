package io.flottila.steps.xslt;

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
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;

import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@code xslt} step: {@code stylesheet} (a path relative to the flow file) compiled at build time — once for
 * every flow whose stylesheet has the same content and whose files it reads while compiling are the same
 * ({@link CompiledFiles}: the same bytes, absences and directory entries at the same places relative to the
 * stylesheet), each other flow compiling its own — and applied to the body's document view; the
 * result is the next step's body, written to the blob store under the envelope's {@link Envelope#nextKey() next key} (the
 * run's own from its second attempt on, D-066) and the flow's body limit, with
 * {@code content-type} from the stylesheet's output method. The stylesheet and every file it reads while it compiles —
 * a module it includes or imports (relative to the including module), an external entity, a static {@code doc()},
 * {@code unparsed-text()} or {@code collection()} — lie inside the flow's directory (D-052, {@link CompileConfinement}):
 * one that leaves it, or a URL, is a build error naming the step and the reference. What the stylesheet reads at run
 * time is not confined here.
 *
 * @since 0.1
 */
public final class XsltStepFactory implements StepFactory {

    static final String CONFIG_SCHEMA = """
            {"type":"object","required":["stylesheet"],"properties":{"stylesheet":{"type":"string","minLength":1}}}""";

    private final SaxonXml xml;
    /** By the main file's content hash: one per distinct set of files its compile read. */
    private final Map<Hash, List<Compiled>> compiled = new ConcurrentHashMap<>();

    private final CompileConfinement confinement;

    XsltStepFactory(SaxonXml xml) {
        this.xml = xml;
        this.confinement = CompileConfinement.on(xml.processor().getUnderlyingConfiguration());
    }

    @Override public String type() { return "xslt"; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    /** Stylesheets compiled and kept so far: one per main content and distinct set of files the compile read. */
    public int compiledStylesheets() { return compiled.values().stream().mapToInt(List::size).sum(); }

    /** A compiled stylesheet and what its compile read. */
    private record Compiled(CompiledFiles files, XsltExecutable executable) { }

    @Override
    public Stage create(StepConfig step, StepResources resources) {
        String stylesheet = step.requireString("stylesheet");
        Path file;
        try {
            file = resources.file(stylesheet);
        } catch (IllegalArgumentException x) {
            throw new IllegalArgumentException(step + ": " + x.getMessage(), x);
        }
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
                throw new XmlException(e.id(), io.flottila.api.Fault.Kind.INTERNAL, step + " failed", x);
            }
            Body body = resources.blobs().write(e.nextKey(), Buffer.buffer(out), maxBodyBytes).await();
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
        for (Compiled entry : compiled.getOrDefault(hash, List.of())) {
            if (entry.files().sameIn(file, resources)) return entry.executable();   // reported what it read, as a compile here would
        }
        CompiledFiles.Recorder reads = new CompiledFiles.Recorder(file);
        CompileConfinement.Scope scope = confinement.open(resources, reads);
        XsltExecutable fresh;
        try {
            StreamSource in = new StreamSource(new ByteArrayInputStream(source));
            in.setSystemId(file.toUri().toString());   // xsl:import / xsl:include resolve next to the file
            XsltCompiler compiler = xml.processor().newXsltCompiler();
            compiler.setResourceResolver(confinement);
            fresh = compiler.compile(in);
        } catch (SaxonApiException x) {
            throw doesNotCompile(step, file, scope, x.getMessage(), x);
        } catch (RuntimeException x) {   // Saxon failing inside (12.4: a static collection() casts PackageData): still the flow's build error
            throw doesNotCompile(step, file, scope, x.toString(), x);
        } finally {
            scope.close();
        }
        // unparsed-text-available() answers false on a refusal and the stylesheet compiles: still a refusal
        if (scope.refused != null) throw new IllegalArgumentException(step + ": stylesheet " + file + " does not compile: " + scope.refused);
        // two builds of the same files at once may both keep theirs: the second is never served, the first matches first
        compiled.computeIfAbsent(hash, h -> new CopyOnWriteArrayList<>()).add(new Compiled(reads.held(resources.hasher()), fresh));
        return fresh;
    }

    /** The build error; a refusal Saxon wrapped in its own words is reported as the confinement words it. */
    private static IllegalArgumentException doesNotCompile(StepConfig step, Path file, CompileConfinement.Scope scope, String saxons, Exception x) {
        String why = scope.refused != null ? scope.refused : saxons;
        return new IllegalArgumentException(step + ": stylesheet " + file + " does not compile: " + why, x);
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
