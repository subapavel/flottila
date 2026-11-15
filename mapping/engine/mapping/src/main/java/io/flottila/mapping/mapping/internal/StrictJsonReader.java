package io.flottila.mapping.mapping.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.erosb.jsonsKema.IJsonString;
import com.github.erosb.jsonsKema.IJsonValue;
import com.github.erosb.jsonsKema.JsonArray;
import com.github.erosb.jsonsKema.JsonBoolean;
import com.github.erosb.jsonsKema.JsonNull;
import com.github.erosb.jsonsKema.JsonNumber;
import com.github.erosb.jsonsKema.JsonObject;
import com.github.erosb.jsonsKema.JsonPointer;
import com.github.erosb.jsonsKema.JsonString;
import com.github.erosb.jsonsKema.SourceLocation;
import io.flottila.mapping.mapping.MalformedJsonException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a mapping-model document into json-sKema's tree, strictly by RFC 8259: well-formed UTF-8,
 * no byte order mark, no key twice in one object, nothing after the value. json-sKema's own parser
 * cannot be used for documents: 0.32.0 accepts a trailing comma, an unknown escape and a raw
 * control character, and decodes the escape of U+DE00 as U+00DE (observed). Every value carries its
 * JSON pointer, which stage 1 reports.
 */
public final class StrictJsonReader {

  /** The deepest nesting read; a mapping model nests seven levels. */
  static final int MAX_DEPTH = 64;

  private static final URI DOCUMENT = URI.create("mem://mapping-model");

  /** RFC 8259 {@code number}, with at most nine exponent digits so that a scale fits an int. */
  private static final Pattern NUMBER =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,9})?");

  private final String text;
  private final List<String> path = new ArrayList<>();
  private int at;
  private int line = 1;
  private int lineStart;
  private int depth;

  private StrictJsonReader(String text) {
    this.text = text;
  }

  /**
   * @param json the document's bytes
   * @return the document's value
   * @throws MalformedJsonException if the bytes are not one RFC 8259 JSON text in UTF-8 without a
   *     byte order mark, or an object has a key twice
   */
  public static IJsonValue read(byte[] json) throws MalformedJsonException {
    ByteBuffer bytes = ByteBuffer.wrap(json);
    String text;
    try {
      text =
          UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(bytes)
              .toString();
    } catch (CharacterCodingException e) {
      throw new MalformedJsonException("not well-formed UTF-8 at byte " + bytes.position());
    }
    StrictJsonReader reader = new StrictJsonReader(text);
    reader.whitespace();
    IJsonValue value = reader.value();
    reader.whitespace();
    if (reader.at < text.length()) {
      throw reader.fail("content after the document");
    }
    return value;
  }

  private IJsonValue value() throws MalformedJsonException {
    SourceLocation location = location();
    return switch (peek()) {
      case '{' -> object(location);
      case '[' -> array(location);
      case '"' -> new JsonString(string(), location);
      case 't' -> literal("true", new JsonBoolean(true, location));
      case 'f' -> literal("false", new JsonBoolean(false, location));
      case 'n' -> literal("null", new JsonNull(location));
      default -> number(location);
    };
  }

  private IJsonValue object(SourceLocation location) throws MalformedJsonException {
    enter();
    Map<IJsonString, IJsonValue> members = new LinkedHashMap<>();
    Set<String> keys = new HashSet<>();
    whitespace();
    if (peek() == '}') {
      at++;
    } else {
      do {
        whitespace();
        String key = string();
        if (!keys.add(key)) {
          throw fail("the key \"" + key + "\" appears twice in one object");
        }
        whitespace();
        expect(':');
        whitespace();
        path.add(key);
        IJsonValue value = value();
        // the key is located at its member, as json-sKema's own parser locates it: a schema
        // keyword's location is its key's
        members.put(new JsonString(key, value.getLocation()), value);
        path.removeLast();
        whitespace();
      } while (consume(','));
      expect('}');
    }
    depth--;
    return new JsonObject(members, location);
  }

  private IJsonValue array(SourceLocation location) throws MalformedJsonException {
    enter();
    List<IJsonValue> items = new ArrayList<>();
    whitespace();
    if (peek() == ']') {
      at++;
    } else {
      do {
        whitespace();
        path.add(Integer.toString(items.size()));
        items.add(value());
        path.removeLast();
        whitespace();
      } while (consume(','));
      expect(']');
    }
    depth--;
    return new JsonArray(items, location);
  }

  /** Consumes the opening bracket of an object or array, within {@link #MAX_DEPTH}. */
  private void enter() throws MalformedJsonException {
    if (++depth > MAX_DEPTH) {
      throw fail("nested deeper than " + MAX_DEPTH + " levels");
    }
    at++;
  }

  private String string() throws MalformedJsonException {
    expect('"');
    StringBuilder out = new StringBuilder();
    while (true) {
      char c = next();
      if (c == '"') {
        return out.toString();
      }
      if (c == '\\') {
        out.append(escape());
      } else if (c < 0x20) {
        throw fail("a control character in a string");
      } else {
        out.append(c);
      }
    }
  }

  private char escape() throws MalformedJsonException {
    char c = next();
    return switch (c) {
      case '"', '\\', '/' -> c;
      case 'b' -> '\b';
      case 'f' -> '\f';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'u' -> unicodeEscape();
      default -> throw fail("an unknown escape \\" + c);
    };
  }

  /** The four hex digits of a {@code u} escape; a lone surrogate is kept as it is. */
  private char unicodeEscape() throws MalformedJsonException {
    int code = 0;
    for (int k = 0; k < 4; k++) {
      char digit = next();
      if (!HexFormat.isHexDigit(digit)) {
        throw fail("a \\u escape needs four hex digits");
      }
      code = code * 16 + HexFormat.fromHexDigit(digit);
    }
    return (char) code;
  }

  private IJsonValue literal(String word, IJsonValue value) throws MalformedJsonException {
    if (!text.startsWith(word, at)) {
      throw fail("unexpected character '" + text.charAt(at) + "'");
    }
    at += word.length();
    return value;
  }

  private IJsonValue number(SourceLocation location) throws MalformedJsonException {
    Matcher number = NUMBER.matcher(text).region(at, text.length());
    if (!number.lookingAt()) {
      throw fail("unexpected character '" + text.charAt(at) + "'");
    }
    at = number.end();
    return new JsonNumber(new BigDecimal(number.group()), location);
  }

  private void whitespace() {
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c == '\n') {
        line++;
        lineStart = at + 1;
      } else if (c != ' ' && c != '\t' && c != '\r') {
        return;
      }
      at++;
    }
  }

  private boolean consume(char c) {
    if (at < text.length() && text.charAt(at) == c) {
      at++;
      return true;
    }
    return false;
  }

  private void expect(char c) throws MalformedJsonException {
    if (peek() != c) {
      throw fail("expected '" + c + "'");
    }
    at++;
  }

  private char peek() throws MalformedJsonException {
    if (at >= text.length()) {
      throw fail("unexpected end of the document");
    }
    return text.charAt(at);
  }

  private char next() throws MalformedJsonException {
    char c = peek();
    at++;
    return c;
  }

  private SourceLocation location() {
    return new SourceLocation(
        line, at - lineStart + 1, new JsonPointer(List.copyOf(path)), DOCUMENT);
  }

  private MalformedJsonException fail(String what) {
    return new MalformedJsonException(
        what + " at line " + line + ", column " + (at - lineStart + 1));
  }
}
