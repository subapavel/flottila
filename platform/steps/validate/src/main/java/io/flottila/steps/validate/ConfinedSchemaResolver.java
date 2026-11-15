package io.flottila.steps.validate;

import io.flottila.api.StepResources;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hands the schema parser every file a schema includes or imports, and only files inside the flow's directory
 * (D-052): a reference that leaves it or is not a file is not opened, and one inside that cannot be read is not handed
 * over; either way the failure is kept in {@link #failure} for the build error, and the parser — which may load
 * nothing by itself — fails on it, so no second reference reaches the resolver. Every file inside the directory is
 * recorded as read, for the compile cache (FIX-17). One per compilation.
 */
final class ConfinedSchemaResolver implements LSResourceResolver {

    private final StepResources resources;
    private final CompiledFiles.Recorder reads;
    /**
     * The reference the resolver could not hand over: refused, as {@link StepResources#resolve} words it, or unreadable, as
     * {@code <reference>: <exception> <message>}; {@code null} while none was.
     */
    String failure;

    /** @param reads records every file handed over, or found inside the directory and not readable */
    ConfinedSchemaResolver(StepResources resources, CompiledFiles.Recorder reads) {
        this.resources = resources;
        this.reads = reads;
    }

    @Override
    public LSInput resolveResource(String type, String namespace, String publicId, String systemId, String baseUri) {
        if (systemId == null) return null;   // an import by namespace only: nothing to read
        Path file;
        try {
            file = resources.resolve(baseUri, systemId);
        } catch (IllegalArgumentException x) {
            failure = x.getMessage();
            return null;
        }
        reads.read(baseUri, systemId, file);
        InputStream stream;
        try {
            stream = Files.newInputStream(file);
        } catch (IOException x) {
            failure = systemId + ": " + x.getClass().getSimpleName() + " " + x.getMessage();   // the parser would only say it may not read it
            return null;
        }
        return new SchemaInput(stream, file.toUri().toString());   // the base its own includes resolve against
    }

}
