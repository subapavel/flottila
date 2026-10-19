package io.flottila.steps.validate;

import io.flottila.api.Fault;
import io.flottila.api.Hash;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.vertx.core.buffer.Buffer;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

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
 * Secure processing is on and nothing is fetched over the network: the schema and every file it includes or imports
 * (relative to the including file) lie inside the flow's directory (D-052) — one that leaves it, or a URL, is a build
 * error naming the step and the reference — a document's external DTD is never read (it does not validate) and a
 * document's schema hint never replaces the configured schema.
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
        String schemaFile = step.requireString("schema");
        Path file;
        try {
            file = resources.file(schemaFile);
        } catch (IllegalArgumentException x) {
            throw new IllegalArgumentException(step + ": " + x.getMessage(), x);
        }
        Schema schema = compile(step, file, resources);
        String name = step.name();
        return Stage.of(e -> {
            byte[] bytes = e.body().whole().getBytes();
            Parsing parsing = new Parsing();
            try {
                Validator validator = schema.newValidator();   // inherits the factory's secure processing: no external DTD, no fetched schema
                validator.setErrorHandler(parsing);
                validator.validate(new StreamSource(new ByteArrayInputStream(bytes)));
            } catch (SAXException | IOException x) {
                // a fatal error is the parser's: the body could not be read as XML; any other failure broke the schema (D-049 a)
                throw new XsdValidationException(e.id(), name, parsing.fatal ? Fault.Kind.MALFORMED : Fault.Kind.INVALID, x);
            }
            return e;
        });
    }

    /** The validator's default handling (warnings ignored, errors thrown) that remembers whether the parser gave up on the body. */
    private static final class Parsing extends DefaultHandler {
        boolean fatal;

        @Override public void error(SAXParseException x) throws SAXParseException { throw x; }

        @Override public void fatalError(SAXParseException x) throws SAXParseException {
            fatal = true;
            throw x;
        }
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
        ConfinedSchemaResolver resolver = new ConfinedSchemaResolver(resources);
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);   // no external DTD or schema access
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");   // the parser loads nothing by itself ...
            factory.setResourceResolver(resolver);   // ... xs:include / xs:import come through the resolver, inside the flow's directory
            StreamSource in = new StreamSource(new ByteArrayInputStream(source));
            in.setSystemId(file.toUri().toString());
            Schema fresh = factory.newSchema(in);   // a reference the resolver did not hand over is the parser's error: it may load nothing itself
            Schema raced = compiled.putIfAbsent(hash, fresh);
            return raced == null ? fresh : raced;
        } catch (SAXException x) {
            String why = resolver.failure != null ? resolver.failure : x.getMessage();   // names the reference and why, the parser only its last segment and the belt
            throw new IllegalArgumentException(step + ": schema " + file + " does not compile: " + why, x);
        }
    }
}
