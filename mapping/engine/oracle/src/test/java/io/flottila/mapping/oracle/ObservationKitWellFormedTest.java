package io.flottila.mapping.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The observation kit (ADR-0018) hands files to the owner. Every XSD, XML and JSON file under
 * {@code corpus/structures/observations} and {@code corpus/observations} must be well-formed; every
 * XSD must also compile as a schema. The JDK has no JSON parser, so a strict RFC 8259 syntax check
 * is included.
 */
class ObservationKitWellFormedTest {

  private static final Path CORPUS = Path.of(System.getProperty("flottila.corpus"));
  private static final List<Path> ROOTS =
      List.of(CORPUS.resolve("structures/observations"), CORPUS.resolve("observations"));

  private static List<Path> files() throws IOException {
    try (Stream<Path> all = ROOTS.stream().flatMap(ObservationKitWellFormedTest::walk)) {
      return all.filter(Files::isRegularFile).sorted().toList();
    }
  }

  private static Stream<Path> walk(Path root) {
    try {
      return Files.walk(root);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void kitIsNotEmpty() throws IOException {
    assertFalse(files().isEmpty(), "no observation kit files under " + ROOTS);
  }

  @TestFactory
  Stream<DynamicTest> everyFileIsWellFormed() throws IOException {
    return files().stream()
        .filter(p -> p.toString().matches(".*\\.(xsd|xml|json)"))
        .map(p -> DynamicTest.dynamicTest(CORPUS.relativize(p).toString(), () -> check(p)));
  }

  private static void check(Path p) throws Exception {
    String name = p.getFileName().toString();
    if (name.endsWith(".json")) {
      new Json(Files.readString(p, StandardCharsets.UTF_8)).parseDocument();
      return;
    }
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    dbf.newDocumentBuilder().parse(p.toFile());
    if (name.endsWith(".xsd")) {
      SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      sf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      sf.newSchema(new StreamSource(p.toFile()));
    }
  }

  @Test
  void jsonCheckerRejectsMalformedAndAcceptsValid() {
    for (String bad :
        List.of("", "{", "{\"a\":}", "[1,]", "{\"a\":1,}", "nul", "01", "\"x", "{} x")) {
      boolean rejected = false;
      try {
        new Json(bad).parseDocument();
      } catch (IllegalArgumentException e) {
        rejected = true;
      }
      assertTrue(rejected, "accepted: " + bad);
    }
    new Json(" {\"a\":[1,-2.5e3,true,false,null,\"s\\u00e9\\n\"],\"b\":{}} ").parseDocument();
  }

  /** Minimal strict JSON syntax checker. */
  private static final class Json {
    private final String s;
    private int i;

    Json(String s) {
      this.s = s;
    }

    void parseDocument() {
      ws();
      value();
      ws();
      if (i != s.length()) {
        fail("trailing content");
      }
    }

    private void fail(String why) {
      throw new IllegalArgumentException(why + " at offset " + i);
    }

    private void ws() {
      while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
    }

    private char peek() {
      if (i >= s.length()) {
        fail("unexpected end");
      }
      return s.charAt(i);
    }

    private void expect(char c) {
      if (peek() != c) {
        fail("expected '" + c + "'");
      }
      i++;
    }

    private void literal(String w) {
      if (!s.startsWith(w, i)) {
        fail("expected " + w);
      }
      i += w.length();
    }

    private void value() {
      switch (peek()) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true");
        case 'f' -> literal("false");
        case 'n' -> literal("null");
        default -> number();
      }
    }

    private void object() {
      expect('{');
      ws();
      if (peek() == '}') {
        i++;
        return;
      }
      while (true) {
        ws();
        string();
        ws();
        expect(':');
        ws();
        value();
        ws();
        if (peek() == ',') {
          i++;
        } else {
          expect('}');
          return;
        }
      }
    }

    private void array() {
      expect('[');
      ws();
      if (peek() == ']') {
        i++;
        return;
      }
      while (true) {
        ws();
        value();
        ws();
        if (peek() == ',') {
          i++;
        } else {
          expect(']');
          return;
        }
      }
    }

    private void string() {
      expect('"');
      while (true) {
        char c = peek();
        i++;
        if (c == '"') {
          return;
        }
        if (c < 0x20) {
          fail("control character in string");
        }
        if (c == '\\') {
          char e = peek();
          i++;
          if (e == 'u') {
            for (int k = 0; k < 4; k++) {
              if (Character.digit(peek(), 16) < 0) {
                fail("bad \\u escape");
              }
              i++;
            }
          } else if ("\"\\/bfnrt".indexOf(e) < 0) {
            fail("bad escape");
          }
        }
      }
    }

    private void number() {
      if (peek() == '-') {
        i++;
      }
      if (peek() == '0') {
        i++;
      } else {
        digits();
      }
      if (i < s.length() && s.charAt(i) == '.') {
        i++;
        digits();
      }
      if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        i++;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
          i++;
        }
        digits();
      }
    }

    private void digits() {
      if (peek() < '0' || peek() > '9') {
        fail("digit expected");
      }
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
        i++;
      }
    }
  }
}
