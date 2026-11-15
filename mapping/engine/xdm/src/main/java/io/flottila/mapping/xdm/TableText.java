package io.flottila.mapping.xdm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;

/**
 * The text of a table param (ADR-0026 "Table params", #83): a table travels through the IR as one
 * {@code Literal} of its compact canonical JSON, the keys sorted by UTF-16 code unit and every
 * string quoted as {@code JSON.stringify} quotes it (ADR-0010), with no white space. The runtime
 * reads it back once, when it binds the call; the export's helper reads the same text with {@code
 * parse-json}.
 */
public final class TableText {

  /**
   * The escape of each C0 control character: the two-character escapes {@code JSON.stringify}
   * writes, and a lowercase {@code \}{@code u} escape for the others.
   */
  private static final String[] CONTROLS = new String[0x20];

  static {
    for (int c = 0; c < CONTROLS.length; c++) {
      CONTROLS[c] = String.format(Locale.ROOT, "\\u%04x", c);
    }
    CONTROLS['\b'] = "\\b";
    CONTROLS['\t'] = "\\t";
    CONTROLS['\n'] = "\\n";
    CONTROLS['\f'] = "\\f";
    CONTROLS['\r'] = "\\r";
  }

  /** The letters of the two-character escapes, each before the character it stands for. */
  private static final String ESCAPE_LETTERS = "btnfr\"\\";

  /** The characters the two-character escapes stand for, in {@link #ESCAPE_LETTERS}' order. */
  private static final String ESCAPED = "\b\t\n\f\r\"\\";

  private TableText() {}

  /** {@code table} as its compact canonical JSON: keys by UTF-16 code unit, whatever its order. */
  public static String write(Map<String, String> table) {
    List<String> keys = new ArrayList<>(table.keySet());
    // String's natural order compares UTF-16 code units, as the canonical form does (ADR-0010)
    Collections.sort(keys);
    StringBuilder text = new StringBuilder("{");
    String separator = "";
    for (String key : keys) {
      text.append(separator);
      quote(key, text);
      text.append(':');
      quote(table.get(key), text);
      separator = ",";
    }
    return text.append('}').toString();
  }

  /**
   * The table of {@code text}, which {@link #write} gave, its rows in the text's order.
   *
   * @param text a table's compact canonical JSON
   */
  public static SequencedMap<String, String> read(String text) {
    SequencedMap<String, String> table = new LinkedHashMap<>();
    // at: a key's opening quotation mark, until the closing brace is reached
    int at = 1;
    while (at < text.length() - 1) {
      StringBuilder key = new StringBuilder();
      at = string(text, at, key);
      StringBuilder value = new StringBuilder();
      // past the key's closing quotation mark and the colon
      at = string(text, at + 2, value);
      table.put(key.toString(), value.toString());
      // past the value's closing quotation mark and the comma or the closing brace
      at += 2;
    }
    return Collections.unmodifiableSequencedMap(table);
  }

  /** Appends {@code value} to {@code out} as {@code JSON.stringify} quotes it. */
  private static void quote(String value, StringBuilder out) {
    out.append('"');
    for (int k = 0; k < value.length(); k++) {
      char c = value.charAt(k);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < CONTROLS.length) {
        out.append(CONTROLS[c]);
      } else {
        // everything else as it is, a surrogate pair included
        out.append(c);
      }
    }
    out.append('"');
  }

  /**
   * Appends the string of {@code text} whose opening quotation mark is at {@code open} to {@code
   * out}, its escapes decoded.
   *
   * @return the index of its closing quotation mark
   */
  private static int string(String text, int open, StringBuilder out) {
    int k = open + 1;
    for (char c = text.charAt(k); c != '"'; c = text.charAt(++k)) {
      if (c == '\\') {
        int escape = ESCAPE_LETTERS.indexOf(text.charAt(++k));
        if (escape >= 0) {
          out.append(ESCAPED.charAt(escape));
        } else {
          // the u escape: four hex digits
          out.append((char) Integer.parseInt(text, k + 1, k + 5, 16));
          k += 4;
        }
      } else {
        out.append(c);
      }
    }
    return k;
  }
}
