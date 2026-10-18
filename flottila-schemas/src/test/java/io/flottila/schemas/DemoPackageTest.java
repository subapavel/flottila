package io.flottila.schemas;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0-12 acceptance: the schemas validate the demo package (packages/demo), file by file and across files. */
class DemoPackageTest {

    static final Path DEMO = Path.of("..", "packages", "demo");

    @Test
    void everyDocumentOfTheDemoPackageIsValidAgainstTheSchemaOfItsKind() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(DEMO)) {
            files = s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml") || p.toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(files.size() >= 6, "package.yaml, three endpoints, a flow and its test: " + files);
        for (Path f : files) {
            JsonObject doc = Documents.read(f);
            Schemas schema = Schemas.forKind(doc.getString("kind"));
            Schemas.Report r = schema.validate(doc);
            assertTrue(r.valid(), f + ": " + r);
        }
    }

    @Test
    void theFlowReferencesOnlyEndpointsThatExistAndTheTestOnlyFixturesThatExist() throws IOException {
        Package pkg = Package.load(DEMO);
        assertEquals("demo", pkg.name());
        assertEquals(Set.of("http-in", "partner-domestic", "partner-foreign"), pkg.endpointIds());
        assertEquals(List.of("invoice-sync"), pkg.flowIds());
        List<String> problems = pkg.crossCheck();
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void crossCheckNamesADanglingEndpointAndAMissingFixture(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("flows"));
        Files.writeString(dir.resolve("package.yaml"), "schemaVersion: 1\nkind: package\nname: p\nversion: 1.0.0\n");
        Files.writeString(dir.resolve("flows/f.yaml"), """
                schemaVersion: 1
                kind: flow
                id: f
                version: 1.0.0
                from: { endpoint: nowhere }
                delivery: { mode: sync }
                limits: { maxBodyBytes: 10 }
                steps: [ { type: xslt, name: map, stylesheet: lost.xsl }, { type: to, name: out, endpoint: also-nowhere } ]
                """);
        Files.writeString(dir.resolve("flows/f.test.yaml"), """
                schemaVersion: 1
                kind: test
                flow: g
                mocks: { ghost: { reply: ../fixtures/no-ack.xml } }
                cases: [ { name: c, send: ../fixtures/missing.xml, expect: { steps: 1 } } ]
                """);
        Package pkg = Package.load(dir);
        assertEquals("package p at " + dir, pkg.toString());
        List<String> problems = pkg.crossCheck();
        assertTrue(problems.stream().anyMatch(p -> p.contains("nowhere")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("also-nowhere")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("missing.xml")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("flow 'g'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("mock for endpoint 'ghost'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("no-ack.xml")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("lost.xsl")), problems.toString());
        assertEquals(7, problems.size(), problems.toString());
    }
}
