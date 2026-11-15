package io.flottila.mapping.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict RFC 8259 reader for the tests, so the drift tests read the schema without the code under
 * test. Members keep document order; a duplicate key is rejected.
 */
sealed interface JsonTree {

  record Obj(Map<String, JsonTree> members) implements JsonTree {}

  record Arr(List<JsonTree> items) implements JsonTree {}

  record Str(String value) implements JsonTree {}

  record Num(String text) implements JsonTree {}

  record Bool(boolean value) implements JsonTree {}

  record Null() implements JsonTree {}

  static JsonTree parse(String text) {
    return new Reader(text).document();
  }

  /** Recursive-descent reader over the decoded text. */
  final class Reader {
    private final String s;
    private int i;

    private Reader(String s) {
      this.s = s;
    }

    private JsonTree document() {
      ws();
      JsonTree value = value();
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

    private JsonTree value() {
      return switch (peek()) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> new Str(string());
        case 't' -> literal("true", new Bool(true));
        case 'f' -> literal("false", new Bool(false));
        case 'n' -> literal("null", new Null());
        default -> number();
      };
    }

    private JsonTree literal(String word, JsonTree value) {
      if (!s.startsWith(word, i)) {
        throw fail("expected " + word);
      }
      i += word.length();
      return value;
    }

    private JsonTree object() {
      expect('{');
      Map<String, JsonTree> members = new LinkedHashMap<>();
      ws();
      if (peek() == '}') {
        i++;
        return new Obj(members);
      }
      while (true) {
        ws();
        String key = string();
        ws();
        expect(':');
        ws();
        if (members.put(key, value()) != null) {
          throw fail("duplicate key " + key);
        }
        ws();
        if (peek() == ',') {
          i++;
        } else {
          expect('}');
          return new Obj(members);
        }
      }
    }

    private JsonTree array() {
      expect('[');
      List<JsonTree> items = new ArrayList<>();
      ws();
      if (peek() == ']') {
        i++;
        return new Arr(items);
      }
      while (true) {
        ws();
        items.add(value());
        ws();
        if (peek() == ',') {
          i++;
        } else {
          expect(']');
          return new Arr(items);
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
            if (i + 4 > s.length()
                || !s.substring(i, i + 4).chars().allMatch(h -> Character.digit(h, 16) >= 0)) {
              throw fail("bad \\u escape");
            }
            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> throw fail("bad escape");
        }
      }
    }

    private JsonTree number() {
      int start = i;
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
      return new Num(s.substring(start, i));
    }

    private void digits() {
      if (peek() < '0' || peek() > '9') {
        throw fail("digit expected");
      }
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
        i++;
      }
    }
  }
}
