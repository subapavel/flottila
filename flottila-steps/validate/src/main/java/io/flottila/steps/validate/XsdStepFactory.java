package io.flottila.steps.validate;

import io.flottila.api.Hash;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.vertx.core.buffer.Buffer;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code xsd} step: {@code schema} (a path relative to the flow file) compiled at build time — by content hash,
 * so a schema shared by several flows compiles once — and applied to the body's bytes with a fresh
 * {@link Validator} per message (a {@link Schema} is thread-safe, a validator is not). A valid message goes on
 * untouched; an invalid one, or one that is not well-formed, fails with an {@link XsdValidationException}.
 * Secure processing is on and nothing is fetched over the network: a schema includes or imports local files only
 * (relative to itself; any {@code file:} path the process can read, not confined to the flow's directory — a URL
 * does not compile), a document's external DTD is never read (it does not validate) and a document's schema hint
 * never replaces the configured schema.
 *
 * @since 0.1
 */
public final class XsdStepFactory implements StepFactory {

    static final String CONFIG_SCHEMA = """
            {"type":"object","required":["schema"],"properties":{"schema":{"type":"string","minLength":1}}}""";

    private final Map<Hash, Schema> compiled = new ConcurrentHashMap<>();

    XsdStepFactory() { }

    @Override public String type() { return "xsd"; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    /** Schemas compiled so far, by content hash. */
    public int compiledSchemas() { return compiled.size(); }

    @Override
    public Stage create(StepConfig step, StepResources resources) {
        Path file = resources.file(step.requireString("schema"));
        Schema schema = compile(step, file, resources);
        String name = step.name();
        return Stage.of(e -> {
            byte[] bytes = e.body().whole().getBytes();
            try {
                Validator validator = schema.newValidator();   // inherits the factory's secure processing: no external DTD, no fetched schema
                validator.validate(new StreamSource(new ByteArrayInputStream(bytes)));
            } catch (SAXException | IOException x) {
                throw new XsdValidationException(e.id(), name, x);
            }
            return e;
        });
    }

    private Schema compile(StepConfig step, Path file, StepResources resources) {
        byte[] source;
        try {
            source = Files.readAllBytes(file);
        } catch (NoSuchFileException x) {
            throw new IllegalArgumentException(step + ": schema not found: " + file, x);
        } catch (IOException x) {
            throw new IllegalArgumentException(step + ": schema " + file + " cannot be read: " + x.getMessage(), x);
        }
        Hash hash = resources.hasher().hashOf(Buffer.buffer(source));
        Schema cached = compiled.get(hash);
        if (cached != null) return cached;
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);   // no external DTD or schema access unless re-allowed below
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");   // xs:include / xs:import from local files, never over the network
            StreamSource in = new StreamSource(new ByteArrayInputStream(source));
            in.setSystemId(file.toUri().toString());
            Schema fresh = factory.newSchema(in);
            Schema raced = compiled.putIfAbsent(hash, fresh);
            return raced == null ? fresh : raced;
        } catch (SAXException x) {
            throw new IllegalArgumentException(step + ": schema " + file + " does not compile: " + x.getMessage(), x);
        }
    }
}
