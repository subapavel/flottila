package io.flottila.schemas;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** YAML and JSON documents become the same JsonObject; durations and ports stay what the author wrote. */
class DocumentsTest {

    @Test
    void yamlBecomesAJsonObjectWithScalarsIntact() {
        JsonObject o = Documents.yaml("""
                a: 1
                b: 100ms
                c: [x, y]
                d: { e: true, f: 1.5 }
                g: "0080"
                level: off
                h: { X: yes, n: no, y: y }
                """);
        assertEquals(1, o.getInteger("a"));
        assertEquals("off", o.getString("level"), "YAML 1.1 boolean words stay strings: `level: off` is a recording level, not false");
        assertEquals("yes", o.getJsonObject("h").getString("X"));
        assertEquals("no", o.getJsonObject("h").getString("n"));
        assertEquals("y", o.getJsonObject("h").getString("y"));
        assertEquals("100ms", o.getString("b"), "a duration is a string, never a number");
        assertEquals("y", o.getJsonArray("c").getString(1));
        assertEquals(true, o.getJsonObject("d").getBoolean("e"));
        assertEquals(1.5, o.getJsonObject("d").getDouble("f"));
        assertEquals("0080", o.getString("g"), "a quoted scalar stays a string");
    }

    @Test
    void readPicksTheFormatFromTheExtension(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.yaml"), "kind: flow\n");
        Files.writeString(dir.resolve("b.yml"), "kind: package\n");
        Files.writeString(dir.resolve("c.json"), "{\"kind\":\"endpoint\"}\n");
        Files.writeString(dir.resolve("d.txt"), "kind: test\n");
        assertEquals("flow", Documents.read(dir.resolve("a.yaml")).getString("kind"));
        assertEquals("package", Documents.read(dir.resolve("b.yml")).getString("kind"));
        assertEquals("endpoint", Documents.read(dir.resolve("c.json")).getString("kind"));
        assertThrows(IllegalArgumentException.class, () -> Documents.read(dir.resolve("d.txt")));
    }

    @Test
    void aYamlDocumentThatIsNotAMappingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Documents.yaml("- just\n- a list\n"));
        assertThrows(IllegalArgumentException.class, () -> Documents.yaml("just a string"));
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class, () -> Documents.yaml("~"));   // an explicit null document
        assertTrue(empty.getMessage().contains("empty"), empty.getMessage());
    }

    @Test
    void brokenYamlAndAMissingFileAreReportedAsWhatTheyAre(@TempDir Path tmp) throws Exception {
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> Documents.yaml("a: [unclosed\n"));
        assertTrue(bad.getMessage().startsWith("not valid YAML"), bad.getMessage());
        Path missing = tmp.resolve("nope.yaml");
        assertThrows(java.io.UncheckedIOException.class, () -> Documents.read(missing));
    }
}
