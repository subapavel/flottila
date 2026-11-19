package dev.rail8.node;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Only the node's build brings the shipped modules. A module is a project under {@code platform/} whose main resources hold
 * {@code META-INF/services/dev.rail8.api.plugin.Plugin}; the sample plugin under {@code examples/} is none. Every module is
 * a dependency of {@code platform/node/pom.xml} at runtime scope. The runtime's pom names a module at test scope only; so
 * does the CLI's, but for the one module its code compiles against. Read from the poms as documents; nothing is built.
 */
class BuildHygieneTest {

    private static final Path PLATFORM = Path.of("..").toAbsolutePath().normalize();
    private static final Path SERVICE_FILE = Path.of("src", "main", "resources", "META-INF", "services", "dev.rail8.api.plugin.Plugin");

    /**
     * The modules the CLI's pom may name outside test scope, each with the reason: the CLI compiles against the http
     * component to compare a case's {@code expect.status} with the http door's answer to it.
     */
    private static final Map<String, String> CLI_COMPILES_AGAINST = Map.of(
            "rail8-component-http", "rail8 test compares a case's expect.status with HttpComponent.status");

    @Test
    void everyModuleIsADependencyOfTheNodeAtRuntimeScope() throws Exception {
        Set<String> modules = modules();
        assertFalse(modules.isEmpty(), "the scan of " + PLATFORM + " found no module");
        Map<String, String> node = dependencies(PLATFORM.resolve("node/pom.xml"));
        Map<String, String> missing = new TreeMap<>();
        for (String module : modules) {
            String scope = node.getOrDefault(module, "absent");
            if (!scope.equals("runtime")) missing.put(module, scope);
        }
        assertEquals(Map.of(), missing, "each module of " + modules + " is a runtime dependency of platform/node/pom.xml; these are not");
    }

    @Test
    void theRuntimesPomNamesAModuleAtTestScopeOnly() throws Exception {
        assertEquals(Map.of(), outsideTestScope("runtime/pom.xml"), "the runtime's build brings no module");
    }

    @Test
    void theCliPomNamesAModuleAtTestScopeOnlyButTheOneItCompilesAgainst() throws Exception {
        Map<String, String> outside = outsideTestScope("cli/pom.xml");
        outside.keySet().removeAll(CLI_COMPILES_AGAINST.keySet());
        assertEquals(Map.of(), outside, "the CLI gets the modules from rail8-node; it names only " + CLI_COMPILES_AGAINST);
    }

    /** The modules {@code pom} (under {@code platform/}) names at a scope other than test, by artifact id, with that scope. */
    private static Map<String, String> outsideTestScope(String pom) throws Exception {
        Set<String> modules = modules();
        Map<String, String> outside = new TreeMap<>();
        dependencies(PLATFORM.resolve(pom)).forEach((artifact, scope) -> {
            if (modules.contains(artifact) && !scope.equals("test")) outside.put(artifact, scope);
        });
        return outside;
    }

    /** The artifact id of every module under {@code platform/}. */
    private static Set<String> modules() throws Exception {
        List<Path> projects;
        try (Stream<Path> walk = Files.walk(PLATFORM)) {
            projects = walk.filter(p -> p.endsWith(SERVICE_FILE) && !builtFile(p)).map(BuildHygieneTest::projectOf).toList();
        }
        Set<String> modules = new TreeSet<>();
        for (Path project : projects) modules.add(text(child(pom(project.resolve("pom.xml")), "artifactId").orElseThrow()));
        return modules;
    }

    /** Whether {@code file} lies in a build's output, such as the copy of a sample's sources the CLI's jar carries. */
    private static boolean builtFile(Path file) {
        for (Path segment : PLATFORM.relativize(file)) if (segment.toString().equals("target")) return true;
        return false;
    }

    /** The project directory of a module's service file: the file's path without {@link #SERVICE_FILE}. */
    private static Path projectOf(Path serviceFile) {
        Path project = serviceFile;
        for (int i = 0; i < SERVICE_FILE.getNameCount(); i++) project = project.getParent();
        return project;
    }

    /**
     * The dependencies of {@code pom}'s own {@code <dependencies>} (not its dependency management) whose group is
     * {@code dev.rail8}: artifact id to scope, compile when it names none. An artifact named twice, such as a module and its
     * test-jar, keeps the scope that is not test, if either is.
     */
    private static Map<String, String> dependencies(Path pom) throws Exception {
        Map<String, String> found = new TreeMap<>();
        Optional<Element> dependencies = child(pom(pom), "dependencies");
        if (dependencies.isEmpty()) return found;
        for (Element dependency : children(dependencies.get(), "dependency")) {
            if (!child(dependency, "groupId").map(BuildHygieneTest::text).orElse("").equals("dev.rail8")) continue;
            String scope = child(dependency, "scope").map(BuildHygieneTest::text).orElse("compile");
            found.merge(text(child(dependency, "artifactId").orElseThrow()), scope, (a, b) -> a.equals("test") ? b : a);
        }
        return found;
    }

    private static Element pom(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(file.toFile());
        return document.getDocumentElement();
    }

    private static Optional<Element> child(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) found.add(e);
        }
        return found;
    }

    private static String text(Element element) {
        return element.getTextContent().trim();
    }
}
