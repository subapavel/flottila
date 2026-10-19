package io.flottila.steps.xslt;

import io.flottila.api.StepConfig;
import io.flottila.api.StepResources;
import io.flottila.testkit.FakeRuntime;
import net.sf.saxon.Configuration;
import net.sf.saxon.Controller;
import net.sf.saxon.lib.CollectionFinder;
import net.sf.saxon.lib.ResourceCollection;
import net.sf.saxon.lib.ResourceRequest;
import net.sf.saxon.lib.ResourceResolver;
import net.sf.saxon.s9api.Processor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The compile-time confinement (D-052) is the resource resolver installed on the shared Saxon Configuration: it confines
 * a request only while a compilation runs on the calling thread. Outside a compilation — on the same thread, after any
 * build, refused or not — every request reaches Saxon's own resolver unchanged (the run time is not confined here).
 */
class CompileConfinementTest {

    final FakeRuntime rt = FakeRuntime.create();
    final SaxonXml xml = new SaxonXml();
    final XsltStepFactory factory = (XsltStepFactory) xml.steps().get(0);
    final Configuration config = xml.processor().getUnderlyingConfiguration();
    /** Saxon's own resolver on an untouched Configuration: what an unconfined request is answered with. */
    final ResourceResolver saxons = new Processor(false).getUnderlyingConfiguration().getResourceResolver();
    /** Saxon's own collection finder on an untouched Configuration. */
    final Configuration untouched = new Processor(false).getUnderlyingConfiguration();

    StepResources resources(Path baseDir) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), 65536, baseDir);
    }

    static StepConfig step(String stylesheet) { return new StepConfig(2, "xslt", "toPartner", false, Map.of("stylesheet", stylesheet)); }

    /** {@code <dir>/flow} with a refused stylesheet (includes ../outside.xsl) and a compiling one (main.xsl + its sibling lib.xsl). */
    static Path flow(Path dir) throws IOException {
        Path flow = XsltStepFactoryTest.flowWithOutside(dir);
        Files.writeString(dir.resolve("outside.txt"), "outside");
        Files.writeString(flow.resolve("up.xsl"), XsltStepFactoryTest.XSL + "<xsl:include href=\"../outside.xsl\"/></xsl:stylesheet>");
        Files.writeString(flow.resolve("lib.xsl"), XsltStepFactoryTest.XSL + "<xsl:template name=\"lib\">lib</xsl:template></xsl:stylesheet>");
        Files.writeString(flow.resolve("main.xsl"), XsltStepFactoryTest.XSL + "<xsl:include href=\"lib.xsl\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\"><xsl:call-template name=\"lib\"/></xsl:template></xsl:stylesheet>");
        return flow;
    }

    /** The files outside the flow's directory, resolved on this thread through the Configuration's resolver, are answered as Saxon answers them. */
    void resolvesAsSaxonDoes(Path dir, Path flow, String after) {
        ResourceResolver resolver = config.getResourceResolver();
        for (String outside : new String[] {"outside.xsl", "outside.txt"}) {
            ResourceRequest request = new ResourceRequest();
            request.uri = dir.resolve(outside).toUri().toString();
            request.baseUri = flow.resolve("main.xsl").toUri().toString();
            request.nature = outside.endsWith(".xsl") ? ResourceRequest.XSLT_NATURE : ResourceRequest.TEXT_NATURE;
            request.purpose = ResourceRequest.ANY_PURPOSE;
            Source expected = assertDoesNotThrow(() -> saxons.resolve(request.copy()));
            Source actual = assertDoesNotThrow(() -> resolver.resolve(request),
                    after + ", no compilation runs: " + outside + " outside the last flow's directory is not confined");
            // Saxon's catalog resolver answers null for a plain file: URI — the caller then reads the file itself
            assertEquals(systemId(expected), systemId(actual), after + ": " + outside + " is resolved by Saxon's own resolver, unchanged");
            assertEquals(expected == null, actual == null, after + ": " + outside + " is resolved by Saxon's own resolver, unchanged");
        }
    }

    static String systemId(Source source) { return source == null ? null : source.getSystemId(); }

    @Test
    void afterARefusedAndASuccessfulBuildNothingIsConfinedOnTheBuildingThread(@TempDir Path dir) throws IOException {
        Path flow = flow(dir);
        assertThrows(IllegalArgumentException.class, () -> factory.create(step("up.xsl"), resources(flow)), "the include leaves the flow's directory");
        resolvesAsSaxonDoes(dir, flow, "after a refused build");
        factory.create(step("main.xsl"), resources(flow));
        resolvesAsSaxonDoes(dir, flow, "after a successful build");
    }

    @Test
    void aSecondFactoryOverTheSameProcessorKeepsTheInstalledConfinementAndBothConfine(@TempDir Path dir) throws IOException {
        Path flow = flow(dir);
        ResourceResolver installed = config.getResourceResolver();
        assertEquals(CompileConfinement.class, installed.getClass(), "the first factory installed the confinement on the Configuration");
        XsltStepFactory second = new XsltStepFactory(xml);
        assertSame(installed, config.getResourceResolver(), "a second factory over the same Configuration reuses the installed confinement, not a wrapper around it");
        assertThrows(IllegalArgumentException.class, () -> second.create(step("up.xsl"), resources(flow)), "the second factory confines");
        assertThrows(IllegalArgumentException.class, () -> factory.create(step("up.xsl"), resources(flow)), "the first factory still confines");
        second.create(step("main.xsl"), resources(flow));
        assertEquals(1, second.compiledStylesheets());
        resolvesAsSaxonDoes(dir, flow, "after both factories built");
    }

    /** The listing of {@code collectionUri} by {@code finder}, in a dynamic context on {@code over}. */
    static Set<String> listing(CollectionFinder finder, Configuration over, String collectionUri) throws Exception {
        ResourceCollection collection = finder.findCollection(new Controller(over).newXPathContext(), collectionUri);
        assertEquals(collectionUri, collection.getCollectionURI(), "the collection asked for");
        Set<String> uris = new TreeSet<>();
        collection.getResourceURIs(new Controller(over).newXPathContext()).forEachRemaining(uris::add);
        return uris;
    }

    @Test
    void afterBuildsCollectionsOutsideTheDirectoryStillGoToSaxonsFinder(@TempDir Path dir) throws Exception {
        Path flow = flow(dir);
        String outside = dir.toUri().toString();   // <dir>/: outside.xsl, outside.txt and the flow's directory
        Set<String> expected = listing(untouched.getCollectionFinder(), untouched, outside);
        assertEquals(Set.of("outside.txt", "outside.xsl"), names(expected), "Saxon's own finder lists the files of <dir>/");
        assertEquals(CompileConfinement.class, config.getCollectionFinder().getClass(), "the factory installed the confinement as the collection finder too");
        assertThrows(IllegalArgumentException.class, () -> factory.create(step("up.xsl"), resources(flow)), "the include leaves the flow's directory");
        assertEquals(expected, assertDoesNotThrow(() -> listing(config.getCollectionFinder(), config, outside),
                "after a refused build, no compilation runs: a collection outside the last flow's directory is not confined"));
        factory.create(step("main.xsl"), resources(flow));
        assertEquals(expected, assertDoesNotThrow(() -> listing(config.getCollectionFinder(), config, outside),
                "after a successful build, no compilation runs: a collection outside the last flow's directory is listed by Saxon's finder"));
    }

    static Set<String> names(Set<String> uris) {
        Set<String> names = new TreeSet<>();
        for (String uri : uris) names.add(uri.substring(uri.lastIndexOf('/') + 1));
        return names;
    }

    @Test
    void afterBuildsAResourceSaxonsResolverAnswersIsStillAnsweredByIt(@TempDir Path dir) throws Exception {
        Path flow = flow(dir);
        // the W3C xml.xsd: Saxon's catalog resolver answers it from the xmlresolver data jar (no network)
        ResourceRequest request = new ResourceRequest();
        request.uri = "http://www.w3.org/2001/xml.xsd";
        request.nature = ResourceRequest.XSD_NATURE;
        request.purpose = ResourceRequest.ANY_PURPOSE;
        Source expected = saxons.resolve(request.copy());
        assertNotNull(expected, "Saxon's own resolver answers the W3C xml.xsd from its catalog");
        close(expected);
        assertThrows(IllegalArgumentException.class, () -> factory.create(step("up.xsl"), resources(flow)), "the include leaves the flow's directory");
        factory.create(step("main.xsl"), resources(flow));
        Source actual = assertDoesNotThrow(() -> config.getResourceResolver().resolve(request.copy()), "after builds, no compilation runs: nothing is confined");
        assertNotNull(actual, "the request reaches Saxon's own resolver, which answers it — not dropped");
        close(actual);
        assertEquals(expected.getSystemId(), actual.getSystemId(), "Saxon's own resolver's answer, unchanged");
        assertEquals(expected.getClass(), actual.getClass(), "Saxon's own resolver's answer, unchanged");
    }

    static void close(Source source) throws IOException {
        if (source instanceof StreamSource stream && stream.getInputStream() != null) stream.getInputStream().close();
    }
}
