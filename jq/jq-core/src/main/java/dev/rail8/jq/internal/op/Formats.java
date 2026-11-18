/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_format, escape_string, URI_UNRESERVED,
 * BASE64_ENCODE_TABLE and BASE64_DECODE_TABLE in src/builtin.c.
 *
 * jq is copyright (C) 2012 Stephen Dolan
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.rail8.jq.internal.op;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.internal.json.JsonStrings;
import dev.rail8.jq.internal.json.Printer;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.Members;
import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * jq 1.8.2's formats, {@code @name} and {@code format(name)}, the family's one file: {@code @text}, {@code @json},
 * {@code @csv}, {@code @tsv}, {@code @html}, {@code @uri}, {@code @urid}, {@code @sh}, {@code @base64} and {@code
 * @base64d}. A format of a string reads its input as {@code tostring} gives it; {@code @csv}, {@code @tsv} and {@code
 * @sh} read an array's elements, {@code @sh} any other value as an array of it. Each loops over its input and its
 * output once, so it ticks once per started KiB of the larger of the two, an array's input counted as one byte per
 * element; the {@code tostring} of a value that is no string ticks as {@link TextFormat} does on top. The result is
 * charged before it is allocated, except {@code @base64d}'s, whose length is known only after its bytes are made
 * valid UTF-8.
 */
public final class Formats {
  /** {@code @json}: {@code tojson}. */
  private static final Operation JSON = new Operation("@json", (Fn0) (state, in) -> TextFormat.dump(state, (JqValue) in));

  /** {@code @csv}. */
  private static final Operation CSV = new Operation("@csv", (Fn0) (state, in) -> row(state, (JqValue) in, Row.CSV));

  /** {@code @tsv}. */
  private static final Operation TSV = new Operation("@tsv", (Fn0) (state, in) -> row(state, (JqValue) in, Row.TSV));

  /** {@code @html}. */
  private static final Operation HTML = new Operation("@html", (Fn0) (state, in) -> html(state, (JqValue) in));

  /** {@code @uri}. */
  private static final Operation URI = new Operation("@uri", (Fn0) (state, in) -> uri(state, (JqValue) in));

  /** {@code @urid}. */
  private static final Operation URID = new Operation("@urid", (Fn0) (state, in) -> urid(state, (JqValue) in));

  /** {@code @sh}. */
  private static final Operation SH = new Operation("@sh", (Fn0) (state, in) -> row(state, (JqValue) in, Row.SH));

  /** {@code @base64}. */
  private static final Operation BASE64 = new Operation("@base64", (Fn0) (state, in) -> base64(state, (JqValue) in));

  /** {@code @base64d}. */
  private static final Operation BASE64D = new Operation("@base64d", (Fn0) (state, in) -> base64d(state, (JqValue) in));

  /** Each format by its name without the {@code @}. */
  private static final Map<String, Operation> BUILT =
      Map.of(
          "text", new Operation("@text", new TextFormat()),
          "json", JSON,
          "csv", CSV,
          "tsv", TSV,
          "html", HTML,
          "uri", URI,
          "urid", URID,
          "sh", SH,
          "base64", BASE64,
          "base64d", BASE64D);

  /** {@code format(f)}: the format that {@code f} names, on the input. */
  public static final Operation FORMAT = new Operation("format", (Fn1) (state, in, f) -> format(state, in, (JqValue) f));

  /** jq's {@code escape_string} lookups of {@code @html}: a NUL is {@code \0} there, as in every escaped string. */
  private static final byte[][] HTML_ESCAPES = escapes("&&amp;", "<&lt;", ">&gt;", "'&apos;", "\"&quot;");

  /** {@code @csv}'s escapes of a string element. */
  private static final byte[][] CSV_ESCAPES = escapes("\"\"\"");

  /** {@code @tsv}'s escapes of a string element. */
  private static final byte[][] TSV_ESCAPES = escapes("\t\\t", "\r\\r", "\n\\n", "\\\\\\");

  /** {@code @sh}'s escapes of a string element. */
  private static final byte[][] SH_ESCAPES = escapes("''\\''");

  /** jq's {@code URI_UNRESERVED}: the bytes {@code @uri} keeps, {@code A-Z a-z 0-9 - _ . ~}. */
  private static final boolean[] UNRESERVED = new boolean[128];

  private static final byte[] HEX = "0123456789ABCDEF".getBytes(UTF_8);

  /** jq's {@code BASE64_ENCODE_TABLE}. */
  private static final byte[] BASE64_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes(UTF_8);

  /** jq's {@code BASE64_DECODE_TABLE}: each byte's six bits, or -1 for a byte that is no base64 digit. */
  private static final int[] BASE64_VALUES = new int[256];

  static {
    for (int c = 0; c < 128; c++) {
      UNRESERVED[c] = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
    }
    Arrays.fill(BASE64_VALUES, -1);
    for (int i = 0; i < BASE64_DIGITS.length; i++) {
      BASE64_VALUES[BASE64_DIGITS[i]] = i;
    }
  }

  private Formats() {}

  /**
   * The operation of {@code @name}: the format of that name, or for a name that is none an operation that raises jq's
   * "name is not a valid format" on every input.
   */
  public static Operation lookup(String name) {
    Operation op = BUILT.get(name);
    return op != null ? op : new Operation("@" + name, (Fn0) (state, in) -> {
      throw Arithmetic.error(name + " is not a valid format");
    });
  }

  /**
   * {@code v | format(f)}: the format {@code f} names, compared up to its first NUL as jq's {@code strcmp} compares it;
   * a name that is no format jq's "f is not a valid format", with all of {@code f}, and an {@code f} that is no string
   * jq's error that names it.
   */
  private static Object format(RunState state, Object v, JqValue f) {
    if (!(f instanceof JqString name)) {
      throw Arithmetic.cannot(state, f, "is not a valid format");
    }
    byte[] bytes = name.utf8().toArray(JAVA_BYTE);
    int nul = 0;
    while (nul < bytes.length && bytes[nul] != 0) {
      nul++;
    }
    Operation op = BUILT.get(new String(bytes, 0, nul, UTF_8));
    if (op == null) {
      throw Arithmetic.error(new String(bytes, UTF_8) + " is not a valid format");
    }
    return ((Fn0) op.form()).apply(state, v);
  }

  /** {@code tostring}: a string itself, any other value its compact text. */
  private static JqString text(RunState state, JqValue v) {
    return v instanceof JqString s ? s : TextFormat.dump(state, v);
  }

  /** {@code @html}: the text with {@code & < > ' "} and NUL escaped. */
  private static BuiltString html(RunState state, JqValue v) {
    MemorySegment in = text(state, v).utf8();
    long length = escapedLength(in, HTML_ESCAPES);
    state.tick(Built.kibibytes(Math.max(in.byteSize(), length)));
    byte[] out = Built.string(state, length);
    escape(in, HTML_ESCAPES, out, 0);
    return BuiltString.of(out);
  }

  /** {@code @uri}: the text's bytes, each one that is not unreserved as {@code %} and two upper-case hex digits. */
  private static BuiltString uri(RunState state, JqValue v) {
    MemorySegment in = text(state, v).utf8();
    long n = in.byteSize();
    long length = 0;
    for (long i = 0; i < n; i++) {
      length += unreserved(in.get(JAVA_BYTE, i)) ? 1 : 3;
    }
    state.tick(Built.kibibytes(length));
    byte[] out = Built.string(state, length);
    int at = 0;
    for (long i = 0; i < n; i++) {
      byte c = in.get(JAVA_BYTE, i);
      if (unreserved(c)) {
        out[at++] = c;
      } else {
        out[at++] = '%';
        out[at++] = HEX[(c & 0xFF) >>> 4];
        out[at++] = HEX[c & 0x0F];
      }
    }
    return BuiltString.of(out);
  }

  private static boolean unreserved(byte c) {
    return c >= 0 && UNRESERVED[c];
  }

  /**
   * {@code @urid}: the text with each {@code %} and the two hex digits after it as the byte they spell. A {@code %}
   * without two hex digits after it, or bytes that are not valid UTF-8, are jq's "is not a valid uri encoding", which
   * names the text.
   */
  private static BuiltString urid(RunState state, JqValue v) {
    JqString text = text(state, v);
    MemorySegment in = text.utf8();
    int n = (int) in.byteSize();
    state.tick(Built.kibibytes(n));
    byte[] decoded = new byte[n];
    int length = 0;
    for (int i = 0; i < n; i++) {
      byte c = in.get(JAVA_BYTE, i);
      if (c != '%') {
        decoded[length++] = c;
        continue;
      }
      int b = 0;
      for (int j = 0; j < 2; j++) {
        int digit = ++i < n ? Character.digit(in.get(JAVA_BYTE, i), 16) : -1;
        if (digit < 0) {
          throw Arithmetic.cannot(state, text, "is not a valid uri encoding");
        }
        b = (b << 4) | digit;
      }
      decoded[length++] = (byte) b;
    }
    if (!JsonStrings.isValid(decoded, 0, length)) {
      throw Arithmetic.cannot(state, text, "is not a valid uri encoding");
    }
    byte[] out = Built.string(state, length);
    System.arraycopy(decoded, 0, out, 0, length);
    return BuiltString.of(out);
  }

  /** {@code @base64}: the text's bytes in base64, each group of three as four digits, the last padded with {@code =}. */
  private static BuiltString base64(RunState state, JqValue v) {
    MemorySegment in = text(state, v).utf8();
    long n = in.byteSize();
    long length = (n + 2) / 3 * 4;
    state.tick(Built.kibibytes(length));
    byte[] out = Built.string(state, length);
    int at = 0;
    for (long i = 0; i < n; i += 3) {
      long left = n - i;
      int code = 0;
      for (int j = 0; j < 3; j++) {
        code = (code << 8) | (j < left ? in.get(JAVA_BYTE, i + j) & 0xFF : 0);
      }
      for (int j = 0; j < 4; j++) {
        out[at + j] = BASE64_DIGITS[(code >>> (18 - j * 6)) & 0x3F];
      }
      if (left < 3) {
        out[at + 3] = '=';
      }
      if (left < 2) {
        out[at + 2] = '=';
      }
      at += 4;
    }
    return BuiltString.of(out);
  }

  /**
   * {@code @base64d}: the bytes the text's base64 digits up to its first {@code =} spell, a last group of two or three
   * digits giving one or two bytes; each sequence of them that is not valid UTF-8 becomes U+FFFD, as jq's {@code
   * jv_string_sized} makes it. A byte that is no digit is jq's "is not valid base64 data", a last group of one digit
   * its "trailing base64 byte found", each naming the text.
   */
  private static BuiltString base64d(RunState state, JqValue v) {
    JqString text = text(state, v);
    MemorySegment in = text.utf8();
    int n = (int) in.byteSize();
    state.tick(Built.kibibytes(n));
    byte[] decoded = new byte[n / 4 * 3 + 2];
    int length = 0;
    int digits = 0;
    int code = 0;
    for (int i = 0; i < n; i++) {
      int c = in.get(JAVA_BYTE, i) & 0xFF;
      if (c == '=') {
        break;
      }
      if (BASE64_VALUES[c] < 0) {
        throw Arithmetic.cannot(state, text, "is not valid base64 data");
      }
      code = (code << 6) | BASE64_VALUES[c];
      if (++digits == 4) {
        decoded[length++] = (byte) (code >>> 16);
        decoded[length++] = (byte) (code >>> 8);
        decoded[length++] = (byte) code;
        digits = 0;
        code = 0;
      }
    }
    if (digits == 3) {
      decoded[length++] = (byte) (code >>> 10);
      decoded[length++] = (byte) (code >>> 2);
    } else if (digits == 2) {
      decoded[length++] = (byte) (code >>> 4);
    } else if (digits == 1) {
      throw Arithmetic.cannot(state, text, "trailing base64 byte found");
    }
    byte[] out = JsonStrings.replaceBad(decoded, 0, length);
    state.chargeBuilt(Charges.STRING + out.length);
    return BuiltString.of(out);
  }

  /** The row formats: how each kind of element is written, what joins them, and the errors. */
  private enum Row {
    CSV(",", "\"", CSV_ESCAPES, true, "cannot be csv-formatted, only array", "is not valid in a csv row"),
    TSV("\t", "", TSV_ESCAPES, true, "cannot be tsv-formatted, only array", "is not valid in a csv row"),
    SH(" ", "'", SH_ESCAPES, false, null, "can not be escaped for shell");

    final byte[] separator;
    final byte[] quote;
    final byte[][] escapes;

    /** Whether null and NaN are empty fields; otherwise they are their compact text. */
    final boolean emptyNull;

    /** The error of an input that is no array, or null when such an input is an array of itself. */
    final String notArray;

    /** The error of an element that is an array or an object. */
    final String badElement;

    Row(String separator, String quote, byte[][] escapes, boolean emptyNull, String notArray, String badElement) {
      this.separator = separator.getBytes(UTF_8);
      this.quote = quote.getBytes(UTF_8);
      this.escapes = escapes;
      this.emptyNull = emptyNull;
      this.notArray = notArray;
      this.badElement = badElement;
    }
  }

  /**
   * {@code @csv}, {@code @tsv} and {@code @sh}: the elements of an array, joined by the row's separator. A string is
   * written between the row's quotes with its escapes; a boolean and a number as their compact text, a NaN as an empty
   * field in {@code @csv} and {@code @tsv}; null as an empty field there and as {@code null} in {@code @sh}. An array
   * or object element is the row's error, which names it; an input that is no array is {@code @csv}'s and {@code
   * @tsv}'s error, and {@code @sh} writes it as the one element of an array.
   */
  private static BuiltString row(RunState state, JqValue in, Row row) {
    List<JqValue> elements = new ArrayList<>();
    if (in instanceof JqArray array) {
      Members members = (Members) array;
      for (int c = members.first(); c != -1; c = members.next(state, c)) {
        elements.add(members.member(state, c));
      }
    } else if (row.notArray != null) {
      throw Arithmetic.cannot(state, in, row.notArray);
    } else {
      elements.add(in);
    }
    // Each element's text: a byte[] written as it is, or a string's bytes, escaped and quoted when written.
    Object[] texts = new Object[elements.size()];
    long length = 0;
    for (int i = 0; i < texts.length; i++) {
      JqValue x = elements.get(i);
      if (i > 0) {
        length += row.separator.length;
      }
      texts[i] =
          switch (x) {
            case JqString s -> s.utf8();
            case JqNull _ -> row.emptyNull ? new byte[0] : dump(state, x);
            case JqNumber number when row.emptyNull && Double.isNaN(Numbers.doubleValue(state, number)) -> new byte[0];
            case JqBool _, JqNumber _ -> dump(state, x);
            default -> throw Arithmetic.cannot(state, x, row.badElement);
          };
      length +=
          texts[i] instanceof MemorySegment s ? 2L * row.quote.length + escapedLength(s, row.escapes) : ((byte[]) texts[i]).length;
    }
    state.tick(Built.kibibytes(Math.max(texts.length, length)));
    byte[] out = Built.string(state, length);
    int at = 0;
    for (int i = 0; i < texts.length; i++) {
      if (i > 0) {
        at = put(row.separator, out, at);
      }
      if (texts[i] instanceof MemorySegment s) {
        at = put(row.quote, out, at);
        at = escape(s, row.escapes, out, at);
        at = put(row.quote, out, at);
      } else {
        at = put((byte[]) texts[i], out, at);
      }
    }
    return BuiltString.of(out);
  }

  /** The compact text of {@code v}, as {@code jv_dump_string} writes it. */
  private static byte[] dump(RunState state, JqValue v) {
    return Printer.json(state, v).getBytes(UTF_8);
  }

  private static int put(byte[] bytes, byte[] out, int at) {
    System.arraycopy(bytes, 0, out, at, bytes.length);
    return at + bytes.length;
  }

  /**
   * jq's {@code escape_string} lookups: for each ASCII byte its replacement, or null to keep it. Each pair is the byte
   * and its replacement; a NUL is always {@code \0}.
   */
  private static byte[][] escapes(String... pairs) {
    byte[][] lookup = new byte[128][];
    lookup[0] = "\\0".getBytes(UTF_8);
    for (String pair : pairs) {
      lookup[pair.charAt(0)] = pair.substring(1).getBytes(UTF_8);
    }
    return lookup;
  }

  /** The length of {@code s} with {@code escapes}: a UTF-8 string's bytes past ASCII are never escaped. */
  private static long escapedLength(MemorySegment s, byte[][] escapes) {
    long n = s.byteSize();
    long length = n;
    for (long i = 0; i < n; i++) {
      byte c = s.get(JAVA_BYTE, i);
      if (c >= 0 && escapes[c] != null) {
        length += escapes[c].length - 1;
      }
    }
    return length;
  }

  /** Writes {@code s} with {@code escapes} at {@code out[at]}; the index after it. */
  private static int escape(MemorySegment s, byte[][] escapes, byte[] out, int at) {
    long n = s.byteSize();
    for (long i = 0; i < n; i++) {
      byte c = s.get(JAVA_BYTE, i);
      if (c >= 0 && escapes[c] != null) {
        at = put(escapes[c], out, at);
      } else {
        out[at++] = c;
      }
    }
    return at;
  }
}
