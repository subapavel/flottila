package io.flottila.schemas;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One negative fixture per rule worth protecting, under src/test/resources/invalid/<kind>/. Each file's first
 * line is {@code # expect: <instance path>} ({@code /} for the root) — the path the schema must complain about; a fixture that passes, or
 * fails somewhere else, fails the test.
 */
class InvalidDocumentsTest {

    @TestFactory
    Stream<DynamicTest> everyInvalidFixtureIsRefusedAtTheExpectedPath() throws IOException {
        Path root = Path.of("src", "test", "resources", "invalid");
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) { files = s.filter(Files::isRegularFile).sorted().toList(); }
        assertTrue(files.size() >= 39, "a lost fixture directory would show here; 39 fixtures on 2026-10-19, add, never drop: " + files.size());
        return files.stream().map(f -> DynamicTest.dynamicTest(root.relativize(f).toString(), () -> {
            String first = Files.readAllLines(f).get(0);
            assertTrue(first.startsWith("# expect: "), f + " must start with '# expect: <path>'");
            String marker = first.substring("# expect: ".length()).trim();
            String expected = marker.equals("/") ? "" : marker;   // "/" in a fixture marks the document root, which Report paths spell ""
            Schemas schema = Schemas.forKind(f.getParent().getFileName().toString());
            Schemas.Report r = schema.validate(Documents.read(f));
            assertFalse(r.valid(), f + " must be invalid");
            assertTrue(r.problems().stream().anyMatch(p -> p.path().equals(expected)),
                    f + ": expected a problem at '" + expected + "' but got " + r);
        }));
    }
}
