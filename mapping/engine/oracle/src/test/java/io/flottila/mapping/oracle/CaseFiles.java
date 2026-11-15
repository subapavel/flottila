package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.flottila.mapping.api.EngineFacade;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The golden corpus on disk (ADR-0018): {@code cases/<area>/<topic>/<nnn>/} holds {@code case.json}
 * {@code {mapping, provenance, ref, expect, note, now?}}, the input and the expectation. {@code
 * mapping} is the model's path under {@code corpus/}; the model's bundle references are relative to
 * the model's own directory. The JDK has no JSON reader, so a strict one is included.
 */
final class CaseFiles {

  /** The time a run of a case without {@code now} starts at, and of every other oracle run. */
  static final OffsetDateTime DEFAULT_NOW = OffsetDateTime.parse("2026-01-01T12:00:00Z");

  private CaseFiles() {}

  static Path corpus() {
    return Path.of(System.getProperty("flottila.corpus"));
  }

  static Path cases() {
    return corpus().resolve("cases");
  }

  /** One case: its permanent id, the path under {@code cases/}, and its {@code case.json}. */
  record CorpusCase(String id, Path dir, Map<String, Object> json) {

    String text(String member) {
      Object value = json.get(member);
      if (!(value instanceof String s)) {
        throw new AssertionError(id + ": case.json member " + member + " is not a string");
      }
      return s;
    }

    String mapping() {
      return text("mapping");
    }

    String provenance() {
      return text("provenance");
    }

    String ref() {
      return text("ref");
    }

    String expect() {
      return text("expect");
    }

    /**
     * The time the case's runs start at, in both back ends (#80): its optional {@code now}, an
     * xs:dateTime with an offset, or {@link #DEFAULT_NOW}. Its offset is the run's time zone.
     */
    OffsetDateTime now() {
      return json.containsKey("now") ? OffsetDateTime.parse(text("now")) : DEFAULT_NOW;
    }

    /** An engine facade whose runs start at {@link #now()}. */
    EngineFacade facade() {
      OffsetDateTime now = now();
      return new EngineFacade(Clock.fixed(now.toInstant(), now.getOffset()));
    }

    Path input() {
      Path xml = dir.resolve("input.xml");
      return Files.isRegularFile(xml) ? xml : dir.resolve("input.json");
    }

    Path expectedOutput() {
      return dir.resolve("expected.xml");
    }

    /**
     * {@code expected-error.json}: the error QName or diagnostic code, and the authoring id or, for
     * a design-time error at a place in the model, the pointer (#65).
     */
    Map<String, Object> expectedError() {
      return object(read(dir.resolve("expected-error.json")));
    }
  }

  /** Every case under {@code cases/}, sorted by case id. */
  static List<CorpusCase> all() {
    Path cases = cases();
    if (!Files.isDirectory(cases)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(cases)) {
      return files
          .filter(p -> p.getFileName().toString().equals("case.json"))
          .map(p -> read(cases, p.getParent()))
          .sorted((a, b) -> a.id().compareTo(b.id()))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The case in {@code dir}; its id is its path under {@code cases}, joined by {@code /}. */
  static CorpusCase read(Path cases, Path dir) {
    String id =
        StreamSupport.stream(cases.relativize(dir).spliterator(), false)
            .map(Path::toString)
            .collect(Collectors.joining("/"));
    return new CorpusCase(id, dir, object(read(dir.resolve("case.json"))));
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(String text) {
    Object value = new Json(text).document();
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("not a JSON object");
    }
    return (Map<String, Object>) value;
  }

  /** Strict RFC 8259 reader: objects keep member order, a duplicate key is refused. */
  static final class Json {
    private final String s;
    private int i;

    Json(String s) {
      this.s = s;
    }

    Object document() {
      ws();
      Object value = value();
      ws();
      if (i != s.length()) {
        throw fail("trailing content");
      }
      return value;
    }

    private IllegalArgumentException fail(String why) {
      return new IllegalArgumentException(why + " at offset " + i);
    }

    private void ws() {
      while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
    }

    private char peek() {
      if (i >= s.length()) {
        throw fail("unexpected end");
      }
      return s.charAt(i);
    }

    private void expect(char c) {
      if (peek() != c) {
        throw fail("expected '" + c + "'");
      }
      i++;
    }

    private Object value() {
      return switch (peek()) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Object literal(String word, Object value) {
      if (!s.startsWith(word, i)) {
        throw fail("expected " + word);
      }
      i += word.length();
      return value;
    }

    private Map<String, Object> object() {
      expect('{');
      Map<String, Object> members = new LinkedHashMap<>();
      ws();
      if (peek() == '}') {
        i++;
        return members;
      }
      while (true) {
        ws();
        String key = string();
        ws();
        expect(':');
        ws();
        if (members.containsKey(key)) {
          throw fail("duplicate key " + key);
        }
        members.put(key, value());
        ws();
        if (peek() == ',') {
          i++;
        } else {
          expect('}');
          return members;
        }
      }
    }

    private List<Object> array() {
      expect('[');
      List<Object> items = new ArrayList<>();
      ws();
      if (peek() == ']') {
        i++;
        return items;
      }
      while (true) {
        ws();
        items.add(value());
        ws();
        if (peek() == ',') {
          i++;
        } else {
          expect(']');
          return items;
        }
      }
    }

    private String string() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (true) {
        char c = peek();
        i++;
        if (c == '"') {
          return out.toString();
        }
        if (c < 0x20) {
          throw fail("control character in string");
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        char e = peek();
        i++;
        switch (e) {
          case '"', '\\', '/' -> out.append(e);
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (i + 4 > s.length()) {
              throw fail("bad \\u escape");
            }
            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> throw fail("bad escape");
        }
      }
    }

    private BigDecimal number() {
      int start = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
      if (start == i) {
        throw fail("unexpected character");
      }
      return new BigDecimal(s.substring(start, i));
    }
  }
}
