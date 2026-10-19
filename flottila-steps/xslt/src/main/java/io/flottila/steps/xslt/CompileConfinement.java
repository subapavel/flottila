package io.flottila.steps.xslt;

import io.flottila.api.StepResources;
import net.sf.saxon.Configuration;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.lib.CollectionFinder;
import net.sf.saxon.lib.ResourceCollection;
import net.sf.saxon.lib.ResourceRequest;
import net.sf.saxon.lib.ResourceResolver;
import net.sf.saxon.trans.XPathException;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Every file a stylesheet reads while it compiles lies inside the flow's directory (D-052): {@code xsl:include} /
 * {@code xsl:import}, an external entity or DTD, and a static {@code doc()}, {@code unparsed-text()},
 * {@code unparsed-text-available()}, {@code collection()} or {@code uri-collection()} in a static variable or
 * {@code use-when}.
 *
 * <p>Saxon-HE 12 evaluates the static expressions in a context built from the {@link Configuration} alone (not from the
 * compiler), and the parser resolves entities through the Configuration's resolver too, so the hook is the shared
 * Configuration's {@link ResourceResolver} and its {@link CollectionFinder}: installed once per Configuration, it
 * confines a request while
 * {@link #open} has a {@link Scope} open on the calling thread and hands every other request — the run time's
 * {@code doc()}, {@code unparsed-text()} and {@code collection()}, an {@code xpath} expression — to the resolver and
 * finder it replaced, unchanged; a collection inside the directory is listed by Saxon's finder. The
 * scope is thread-local because the Configuration is shared: a compilation reads only on the thread that compiles,
 * and two builders compiling at once on two threads see their own directory each.
 */
final class CompileConfinement implements ResourceResolver, CollectionFinder {

    private final ResourceResolver saxons;
    private final CollectionFinder saxonsFinder;
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    private CompileConfinement(ResourceResolver saxons, CollectionFinder saxonsFinder) {
        this.saxons = Objects.requireNonNull(saxons, "the Configuration's resource resolver");
        this.saxonsFinder = Objects.requireNonNull(saxonsFinder, "the Configuration's collection finder");
    }

    /** The confinement on {@code config}, installed in place of its resource resolver and collection finder on first use. */
    static CompileConfinement on(Configuration config) {
        synchronized (config) {
            if (config.getResourceResolver() instanceof CompileConfinement installed) return installed;
            CompileConfinement fresh = new CompileConfinement(config.getResourceResolver(), config.getCollectionFinder());
            config.setResourceResolver(fresh);
            config.setCollectionFinder(fresh);
            return fresh;
        }
    }

    /** Confines what this thread reads to {@code resources}' directory until the scope is closed. */
    Scope open(StepResources resources) {
        Scope scope = new Scope(resources);
        current.set(scope);
        return scope;
    }

    @Override
    public Source resolve(ResourceRequest request) throws XPathException {
        Scope scope = current.get();
        if (scope == null) return saxons.resolve(request);   // not compiling: Saxon's own resolution
        String reference = Objects.requireNonNullElse(request.relativeUri, request.uri);
        Path file = scope.confine(request.baseUri, reference);
        try {
            return new StreamSource(Files.newInputStream(file), file.toUri().toString());   // never null: Saxon would read it itself
        } catch (IOException x) {
            throw new XPathException(file + " cannot be read: " + x.getMessage(), x);   // inside: unparsed-text-available() may say false
        }
    }

    /**
     * A collection — Saxon hands its absolute URI — listed by Saxon's finder only inside the directory: the directory
     * part (the URI without its {@code ?select=…} query or a fragment) is confined, and a refusal names it; Saxon's
     * finder gets the URI whole.
     */
    @Override
    public ResourceCollection findCollection(XPathContext context, String collectionUri) throws XPathException {
        Scope scope = current.get();
        if (scope != null) scope.confine(null, collectionUri.split("[?#]", 2)[0]);
        return saxonsFinder.findCollection(context, collectionUri);
    }

    /** One compilation's confinement on one thread. */
    final class Scope implements AutoCloseable {
        private final StepResources resources;
        /** The first reference the compilation tried to read outside the directory; {@code null} while none. */
        String refused;

        private Scope(StepResources resources) { this.resources = resources; }

        /** {@code reference} inside the directory, or the refusal recorded and thrown. */
        private Path confine(String baseUri, String reference) throws XPathException {
            try {
                return resources.resolve(baseUri, reference);
            } catch (IllegalArgumentException x) {
                // unparsed-text-available() swallows the error and the compilation goes on: the factory fails on the first
                if (refused == null) refused = x.getMessage();
                throw new XPathException(x.getMessage());
            }
        }

        @Override public void close() { current.remove(); }
    }
}
