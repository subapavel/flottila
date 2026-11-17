package io.github.subapavel.jqj.oracle.gen;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code values-v1} Generator (ADR-0020, ADR-0025): a function from a Seed to one differential case, {@code
 * gen/values-v1/<seed>}, whose stdin is one random document inside jq's accept set. It uses only the JDK and its own
 * {@link SplitMix64}, with the seed as the state, and integer draws only. It is frozen at the first merge of {@code
 * diff/gen/values-v1.rec}: a change to any output byte makes it {@code values-v2}.
 *
 * <p>The parameters, approved on #28 (architect, 2026-11-11):
 *
 * <table>
 *   <caption>values-v1</caption>
 *   <tr><th>parameter</th><th>value</th></tr>
 *   <tr><td>Form</td><td>{@code seed % 7}: 0 {@code -c .}, 1 {@code .}, 2 {@code -c -S .}, 3 {@code -c -a .},
 *       4 {@code -c tojson}, 5 {@code -c [paths]}, 6 {@code -c tostream}</td></tr>
 *   <tr><td>seeds</td><td>[0, 2100), 300 per form</td></tr>
 *   <tr><td>size</td><td>budget class 50 % ≤ 128 B, 35 % ≤ 512 B, 15 % ≤ 4096 B, BOM and whitespace included; a
 *       container stops at the budget, and so does a string</td></tr>
 *   <tr><td>depth</td><td>tree ≤ 8; 2 % of documents are a spine of depth 9–200: one array/object chain of 9–198
 *       levels (one element per level, object keys from the pool) around a small tree of depth ≤ 2. A spine takes
 *       the 4096 B class, and its chain stops early when the next level would not leave room for the tree</td></tr>
 *   <tr><td>top-level kind</td><td>object 35, array 30, number 15, string 10, true/false/null 10</td></tr>
 *   <tr><td>inner kind</td><td>number 28, string 28, array 15, object 15, null 6, true 4, false 4; the container
 *       weights × (8 − d) / 8 at tree depth d</td></tr>
 *   <tr><td>container length</td><td>0 with 20 %, else 1 + a geometric count of mean 3, ≤ 32</td></tr>
 *   <tr><td>keys</td><td>60 % from the pool {@code ""} {@code a} {@code b} {@code aa} {@code A} {@code Z}, é raw,
 *       é as the six bytes <code>&#92;u00e9</code>, U+FF61 and U+1F600 raw (their UTF-16 and UTF-8 orders differ);
 *       40 % random strings. 15 % of non-empty objects repeat one of their keys as a last member, half of those in
 *       another spelling: one raw character drawn from the key as a <code>&#92;u</code> escape (a surrogate pair
 *       above U+FFFF) in mixed-case hex, {@code "a"} then <code>"&#92;u0061"</code>; a key without a raw character
 *       repeats as it is</td></tr>
 *   <tr><td>strings</td><td>0–64 characters, a geometric count of mean 6. Per character: printable ASCII 60;
 *       {@code \" \\ \/ \b \f \n \r \t} 10; <code>&#92;uXXXX</code> 8 (hex digits in random case; of these 25 % a
 *       surrogate pair, 5 % a lone low surrogate, 35 % U+0000–U+001F, 35 % another BMP character); raw DEL 2;
 *       valid 2-, 3- or 4-byte UTF-8 12; invalid UTF-8 8, one seventh each: a lead byte 80–c1 or f5–ff, a valid
 *       2-byte character with a stray continuation byte, {@code c0 80}, {@code e0 80 80}, {@code ed a0 80},
 *       {@code f4 90 80 80}, a valid sequence cut short and followed by printable ASCII or the closing quote</td></tr>
 *   <tr><td>strict numbers</td><td>small int (−1000..999) 40; decimal with 1–4 fraction digits ({@code 1.10}) 20;
 *       a long edge (±2^63 edges, 2^63, ±(2^53+1)) or a 19–40-digit int 10; 18–30 significant digits 5; exponent
 *       forms ({@code 1e2}, {@code 1E+2}, {@code 1e-2}, {@code 0e0}) 12; zero family ({@code -0}, {@code -0.0},
 *       {@code 0.0}, {@code -0e5}) 5; 6–8 leading fraction zeros 3; exponent extremes 5 ({@link #EXTREMES})</td></tr>
 *   <tr><td>lenient</td><td>20 % of documents ({@code below(5) == 0}); each number in such a document is lenient
 *       with p 1/3, and the document is redrawn until at least one is. Mix: {@link #LENIENT_SHAPES} 75,
 *       {@link #NAN_INFINITY} 25</td></tr>
 *   <tr><td>whitespace</td><td>layout 40 % none, 40 % random runs of 0–4 from SP/TAB/LF/CR/CRLF at every gap,
 *       10 % indent 2 with LF, 10 % indent 2 with CRLF; a trailing LF on 70 %</td></tr>
 *   <tr><td>BOM</td><td>a leading {@code EF BB BF} on 3 % of documents</td></tr>
 *   <tr><td>excluded</td><td>raw NUL; a raw byte below 0x20 in a string; a lone high-surrogate escape; FF and VT;
 *       a partial or inner BOM (no raw U+FEC0–U+FEFF either, so no {@code EF BB} pair but the BOM); empty input,
 *       whitespace only, a second value; {@code NaN1}-like spellings</td></tr>
 * </table>
 *
 * <p>Trial run (#28 answer (c), jq-ref 1.8.2, 2026-11-11, under all seven forms): {@code -Infinity}, {@code -inf},
 * {@code 1e2147483648} and the 11-digit exponents {@code 1e10000000000}, {@code 1e-10000000000},
 * {@code 1e99999999999} and {@code 1E+12345678901} all gave rc 0, so every one of them stays in the table and none
 * was removed.
 *
 * <p>A seed that later hits a deviation keeps its block and gains {@code deviation} and {@code jqj-*} lines (#28
 * answer (d)); the freeze check ignores them. {@code DiffCase} does not read {@code jqj-*} keys yet: it gains them
 * with the first deviation seed.
 */
public final class ValuesV1 {

  /** The Generator's name: the family is {@code gen/values-v1}. */
  public static final String NAME = "values-v1";

  public static final String FAMILY = "gen/" + NAME;

  /** The recorded seed range, [{@value #FROM}, {@value #TO}). */
  public static final long FROM = 0;

  public static final long TO = 2100;

  private static final int CAP = 4096;

  private static final List<List<String>> FORMS =
      List.of(
          List.of("-c", "."),
          List.of("."),
          List.of("-c", "-S", "."),
          List.of("-c", "-a", "."),
          List.of("-c", "tojson"),
          List.of("-c", "[paths]"),
          List.of("-c", "tostream"));

  static final List<String> LONG_EDGES =
      List.of("9223372036854775807", "-9223372036854775808", "9223372036854775808", "-9223372036854775809", "9007199254740993", "-9007199254740993");

  static final List<String> ZEROS = List.of("-0", "-0.0", "0.0", "-0e5");

  static final List<String> EXTREMES =
      List.of(
          "1e999999999",
          "10e999999999",
          "1e-1147483646",
          "5e-1147483647",
          "1e-1147483648",
          "0e-1147483650",
          "1e2147483648",
          "1e10000000000",
          "1e-10000000000",
          "1e99999999999",
          "1E+12345678901");

  static final List<String> LENIENT_SHAPES = List.of("+1", ".5", "1.", "007", "2.e3", "-.5", "+1.E+2");

  static final List<String> NAN_INFINITY = List.of("NaN", "nan", "-NaN", "sNaN", "NaN0", "NaN00", "Infinity", "inf", "-Infinity", "-inf");

  /** The key pool, as spelled between the quotes. */
  private static final List<byte[]> POOL =
      List.of(
          new byte[0],
          bytes("a"),
          bytes("b"),
          bytes("aa"),
          bytes("A"),
          bytes("Z"),
          bytes("é"),
          bytes("\\u00e9"),
          bytes("｡"),
          bytes("😀"));

  /** The index of {@code \}{@code u00e9} in {@link #POOL}. */
  private static final int ESCAPED_POOL_KEY = 7;

  private static final String[] ESCAPES = {"\\\"", "\\\\", "\\/", "\\b", "\\f", "\\n", "\\r", "\\t"};

  /** The classes a document drew, for the test that every class occurs. */
  public enum Feature {
    BUDGET_128,
    BUDGET_512,
    BUDGET_4096,
    SPINE,
    BOM,
    LAYOUT_NONE,
    LAYOUT_RUNS,
    LAYOUT_INDENT_LF,
    LAYOUT_INDENT_CRLF,
    TRAILING_LF,
    TOP_OBJECT,
    TOP_ARRAY,
    TOP_NUMBER,
    TOP_STRING,
    TOP_LITERAL,
    EMPTY_CONTAINER,
    KEY_POOL,
    KEY_POOL_ESCAPED,
    KEY_RANDOM,
    REPEATED_KEY,
    RESPELLED_KEY,
    CHAR_ASCII,
    CHAR_ESCAPE,
    CHAR_CONTROL_ESCAPE,
    CHAR_BMP_ESCAPE,
    CHAR_SURROGATE_PAIR,
    CHAR_LONE_LOW,
    CHAR_DEL,
    CHAR_UTF8_2,
    CHAR_UTF8_3,
    CHAR_UTF8_4,
    CHAR_INVALID_LEAD,
    CHAR_STRAY_CONTINUATION,
    CHAR_OVERLONG_2,
    CHAR_OVERLONG_3,
    CHAR_UTF8_SURROGATE,
    CHAR_ABOVE_MAX,
    CHAR_CUT,
    NUM_SMALL_INT,
    NUM_DECIMAL,
    NUM_LONG_EDGE,
    NUM_LONG_DIGITS,
    NUM_SIGNIFICANT,
    NUM_EXPONENT,
    NUM_ZERO,
    NUM_FRACTION_ZEROS,
    NUM_EXPONENT_EXTREME,
    LENIENT_SHAPE,
    LENIENT_NAN_INFINITY
  }

  /**
   * One generated case.
   *
   * @param args the command line of the seed's Form, the filter last
   * @param stdin the document
   * @param lenient whether the document was drawn as one with lenient tokens
   * @param features every class the document drew
   */
  public record Generated(long seed, List<String> args, byte[] stdin, boolean lenient, Set<Feature> features) {

    /** The case block, LF-terminated: {@code case}, an {@code arg} per option, {@code filter}, {@code stdin}, {@code end}. */
    public String block() {
      StringBuilder b = new StringBuilder("case ").append(FAMILY).append('/').append(seed).append('\n');
      for (String a : args.subList(0, args.size() - 1)) b.append("arg ").append(a).append('\n');
      b.append("filter ").append(args.getLast()).append('\n');
      b.append("stdin ").append(CaseFormat.escape(stdin)).append('\n');
      return b.append("end\n").toString();
    }
  }

  private ValuesV1() {}

  /**
   * The text of {@code diff/gen/values-v1.cases} for the seeds [{@code from}, {@code to}): a header naming the
   * Generator, one {@code # dropped <seed> <reason>} line per Dropped seed, then the block of every other seed, each
   * after a blank line.
   *
   * @param dropped the reason ({@code rc3} or {@code timeout}) by Dropped seed, in seed order
   */
  public static String file(long from, long to, Map<Long, String> dropped) {
    StringBuilder b = new StringBuilder();
    b.append("# Generated cases of values-v1 (ADR-0025), seeds [").append(from).append(", ").append(to).append("): ");
    b.append("oracle/src/main/java/io/github/subapavel/jqj/oracle/gen/ValuesV1.java.\n");
    b.append("# Written with values-v1.rec by scripts/oracle-record.sh --generate values-v1 ").append(from).append(' ').append(to);
    b.append(", never by hand; a seed that hits a deviation keeps its block and gains deviation and jqj-* lines.\n");
    for (Map.Entry<Long, String> d : dropped.entrySet()) b.append("# dropped ").append(d.getKey()).append(' ').append(d.getValue()).append('\n');
    for (long seed = from; seed < to; seed++) {
      if (!dropped.containsKey(seed)) b.append('\n').append(generate(seed).block());
    }
    return b.toString();
  }

  /** The case of one seed. */
  public static Generated generate(long seed) {
    SplitMix64 r = new SplitMix64(seed);
    boolean lenient = r.below(5) == 0;
    Doc doc;
    do {
      doc = new Doc(r, lenient);
      doc.document();
    } while (lenient && doc.lenientTokens == 0);
    EnumSet<Feature> features = EnumSet.noneOf(Feature.class);
    for (Feature f : Feature.values()) {
      if ((doc.features & (1L << f.ordinal())) != 0) features.add(f);
    }
    return new Generated(seed, FORMS.get((int) Math.floorMod(seed, 7L)), Arrays.copyOf(doc.buf, doc.size), lenient, Set.copyOf(features));
  }

  private static byte[] bytes(String s) {
    return s.getBytes(UTF_8);
  }

  private enum Layout {
    NONE,
    RUNS,
    INDENT_LF,
    INDENT_CRLF
  }

  /** One attempt at a document: the bytes so far, the budget, and what a rollback must restore. */
  private static final class Doc {

    private final SplitMix64 r;
    private final boolean lenient;
    private byte[] buf = new byte[CAP];
    private int size;
    private long features;
    private int lenientTokens;
    private int budget;
    private Layout layout;

    Doc(SplitMix64 r, boolean lenient) {
      this.r = r;
      this.lenient = lenient;
    }

    void document() {
      boolean spine = r.below(50) == 0;
      int b = r.below(100);
      budget = spine || b >= 85 ? 4096 : b >= 50 ? 512 : 128;
      mark(budget == 128 ? Feature.BUDGET_128 : budget == 512 ? Feature.BUDGET_512 : Feature.BUDGET_4096);
      boolean bom = r.below(100) < 3;
      int l = r.below(10);
      layout = l < 4 ? Layout.NONE : l < 8 ? Layout.RUNS : l == 8 ? Layout.INDENT_LF : Layout.INDENT_CRLF;
      mark(
          switch (layout) {
            case NONE -> Feature.LAYOUT_NONE;
            case RUNS -> Feature.LAYOUT_RUNS;
            case INDENT_LF -> Feature.LAYOUT_INDENT_LF;
            case INDENT_CRLF -> Feature.LAYOUT_INDENT_CRLF;
          });
      boolean trailingLf = r.below(10) < 7;
      if (bom) {
        mark(Feature.BOM);
        put(0xef);
        put(0xbb);
        put(0xbf);
      }
      int tail = (layout == Layout.RUNS ? 8 : 0) + 1;
      if (layout == Layout.RUNS) run();
      if (spine) {
        mark(Feature.SPINE);
        spine(tail);
      } else {
        top(tail);
      }
      if (layout == Layout.RUNS) run();
      if (trailingLf) {
        mark(Feature.TRAILING_LF);
        put('\n');
      }
    }

    private void top(int reserve) {
      switch (pick(105, 90, 45, 30, 10, 10, 10)) {
        case 0 -> {
          mark(Feature.TOP_OBJECT);
          container(true, 0, 0, reserve);
        }
        case 1 -> {
          mark(Feature.TOP_ARRAY);
          container(false, 0, 0, reserve);
        }
        case 2 -> {
          mark(Feature.TOP_NUMBER);
          number();
        }
        case 3 -> {
          mark(Feature.TOP_STRING);
          string(reserve);
        }
        case 4 -> literal(Feature.TOP_LITERAL, "true");
        case 5 -> literal(Feature.TOP_LITERAL, "false");
        default -> literal(Feature.TOP_LITERAL, "null");
      }
    }

    private void literal(Feature f, String text) {
      mark(f);
      put(text);
    }

    /** An inner value at tree depth {@code d} (its weights) and nesting {@code level} (its indent). */
    private void value(int d, int level, int reserve) {
      int c = 15 * (8 - d);
      switch (pick(224, 224, c, c, 48, 32, 32)) {
        case 0 -> number();
        case 1 -> string(reserve);
        case 2 -> container(false, d, level, reserve);
        case 3 -> container(true, d, level, reserve);
        case 4 -> put("null");
        case 5 -> put("true");
        default -> put("false");
      }
    }

    /**
     * A container at tree depth {@code d} and nesting {@code level}. A member that leaves too little room to close
     * this container and every enclosing one is rolled back, and the container ends there.
     */
    private void container(boolean object, int d, int level, int reserve) {
      int length = r.below(5) == 0 ? 0 : geometric(3, 32);
      put(object ? '{' : '[');
      int inner = reserve + closeCost(level);
      List<byte[]> keys = new ArrayList<>();
      int count = 0;
      for (int i = 0; i < length; i++) {
        Mark m = new Mark();
        if (i > 0) separator();
        indent(level + 1);
        byte[] key = null;
        if (object) {
          key = key();
          member(key);
        }
        value(d + 1, level + 1, inner);
        if (size + inner > budget) {
          m.rollback();
          break;
        }
        if (key != null) keys.add(key);
        count++;
      }
      if (object && count > 0 && r.below(100) < 15) {
        Mark m = new Mark();
        mark(Feature.REPEATED_KEY);
        byte[] key = keys.get(r.below(keys.size()));
        byte[] other = r.below(2) == 0 ? respell(key) : null;
        if (other != null) {
          mark(Feature.RESPELLED_KEY);
          key = other;
        }
        separator();
        indent(level + 1);
        member(key);
        value(d + 1, level + 1, inner);
        if (size + inner > budget) m.rollback();
        else count++;
      }
      if (count == 0) {
        mark(Feature.EMPTY_CONTAINER);
        if (layout == Layout.RUNS) run();
      } else {
        if (layout == Layout.RUNS) run();
        indent(level);
      }
      put(object ? '}' : ']');
    }

    /** The gap before a later member: a run, then the comma. */
    private void separator() {
      if (layout == Layout.RUNS) run();
      put(',');
    }

    /** A key, its gaps and the colon. */
    private void member(byte[] key) {
      put('"');
      put(key);
      put('"');
      if (layout == Layout.RUNS) run();
      put(':');
      if (layout == Layout.RUNS) run();
      if (layout == Layout.INDENT_LF || layout == Layout.INDENT_CRLF) put(' ');
    }

    /** A one-element chain of 9–200 levels; each level an array or an object with a pool key. */
    private void spine(int reserve) {
      int levels = r.between(9, 198);
      List<Boolean> objects = new ArrayList<>();
      int closing = reserve;
      for (int level = 0; level < levels; level++) {
        boolean object = r.below(2) == 0;
        byte[] key = POOL.get(r.below(POOL.size()));
        // the worst opening: bracket, a CRLF indent or three runs, the key with its quotes, colon and space
        int opening = 1 + Math.max(2 + 2 * (level + 1), 3 * 8) + key.length + 2 + 1 + 1;
        if (level > 0 && size + opening + closing + closeCost(level) + 64 > budget) break;
        put(object ? '{' : '[');
        indent(level + 1);
        if (layout == Layout.RUNS) run();
        if (object) member(key);
        objects.add(object);
        closing += closeCost(level);
      }
      value(6, objects.size(), closing);
      for (int level = objects.size() - 1; level >= 0; level--) {
        if (layout == Layout.RUNS) run();
        indent(level);
        put(objects.get(level) ? '}' : ']');
      }
    }

    /** The most bytes closing a container at nesting {@code level} can take. */
    private int closeCost(int level) {
      return switch (layout) {
        case NONE -> 1;
        case RUNS -> 9;
        case INDENT_LF -> 1 + 2 * level + 1;
        case INDENT_CRLF -> 2 + 2 * level + 1;
      };
    }

    /** In an indent layout, a line break and two spaces per {@code level}. */
    private void indent(int level) {
      if (layout != Layout.INDENT_LF && layout != Layout.INDENT_CRLF) return;
      if (layout == Layout.INDENT_CRLF) put('\r');
      put('\n');
      for (int k = 0; k < 2 * level; k++) put(' ');
    }

    /** A run of 0–4 whitespace pieces, each SP, TAB, LF, CR or CRLF. */
    private void run() {
      int n = r.below(5);
      for (int k = 0; k < n; k++) {
        switch (r.below(5)) {
          case 0 -> put(' ');
          case 1 -> put('\t');
          case 2 -> put('\n');
          case 3 -> put('\r');
          default -> {
            put('\r');
            put('\n');
          }
        }
      }
    }

    private byte[] key() {
      if (r.below(10) < 6) {
        mark(Feature.KEY_POOL);
        int i = r.below(POOL.size());
        if (i == ESCAPED_POOL_KEY) mark(Feature.KEY_POOL_ESCAPED);
        return POOL.get(i);
      }
      mark(Feature.KEY_RANDOM);
      int start = size;
      chars(0);
      byte[] key = Arrays.copyOfRange(buf, start, size);
      size = start;
      return key;
    }

    /**
     * The key with one raw character, drawn from its raw characters, written as a {@code \}{@code u} escape (a
     * surrogate pair above U+FFFF) in mixed-case hex; null when the key has no raw character (empty, or only escapes
     * and invalid bytes).
     */
    private byte[] respell(byte[] key) {
      List<int[]> raw = new ArrayList<>(); // {start, end, code point}
      for (int i = 0; i < key.length; ) {
        int b = key[i] & 0xff;
        int end;
        if (b == '\\') {
          end = i + (key[i + 1] == 'u' ? 6 : 2);
        } else if (b < 0x80) {
          end = i + 1;
          raw.add(new int[] {i, end, b});
        } else {
          int n = b >= 0xf0 ? 4 : b >= 0xe0 ? 3 : 2;
          String one = i + n <= key.length ? strictUtf8(key, i, n) : null;
          end = one == null ? i + 1 : i + n;
          if (one != null) raw.add(new int[] {i, end, one.codePointAt(0)});
        }
        i = end;
      }
      if (raw.isEmpty()) return null;
      int[] u = raw.get(r.below(raw.size()));
      int start = size;
      if (u[2] < 0x10000) {
        escapeUnit(u[2]);
      } else {
        escapeUnit(Character.highSurrogate(u[2]));
        escapeUnit(Character.lowSurrogate(u[2]));
      }
      byte[] escape = Arrays.copyOfRange(buf, start, size);
      size = start;
      byte[] out = new byte[key.length - (u[1] - u[0]) + escape.length];
      System.arraycopy(key, 0, out, 0, u[0]);
      System.arraycopy(escape, 0, out, u[0], escape.length);
      System.arraycopy(key, u[1], out, u[0] + escape.length, key.length - u[1]);
      return out;
    }

    /** The one character of {@code n} bytes at {@code from} if they are valid UTF-8, else null. */
    private static String strictUtf8(byte[] bytes, int from, int n) {
      try {
        String s =
            UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, from, n))
                .toString();
        return s.codePointCount(0, s.length()) == 1 ? s : null;
      } catch (CharacterCodingException e) {
        return null;
      }
    }

    private void string(int reserve) {
      put('"');
      chars(reserve + 1);
      put('"');
    }

    /** The characters of a string; stops early when a worst-case character would not leave {@code reserve} bytes. */
    private void chars(int reserve) {
      int length = geometric(6, 65) - 1;
      for (int k = 0; k < length; k++) {
        if (size + 12 + reserve > budget) return;
        char1(k == length - 1);
      }
    }

    private void char1(boolean last) {
      int w = r.below(100);
      if (w < 60) {
        mark(Feature.CHAR_ASCII);
        put(printable());
      } else if (w < 70) {
        mark(Feature.CHAR_ESCAPE);
        put(ESCAPES[r.below(ESCAPES.length)]);
      } else if (w < 78) {
        unicodeEscape();
      } else if (w < 80) {
        mark(Feature.CHAR_DEL);
        put(0x7f);
      } else if (w < 92) {
        put(valid(r.below(3)));
      } else {
        invalid(last);
      }
    }

    /** Printable ASCII without the quote and the backslash. */
    private int printable() {
      int c = 0x20 + r.below(93);
      if (c >= '"') c++;
      if (c >= '\\') c++;
      return c;
    }

    private void unicodeEscape() {
      int w = r.below(100);
      if (w < 25) {
        mark(Feature.CHAR_SURROGATE_PAIR);
        escapeUnit(0xd800 + r.below(0x400));
        escapeUnit(0xdc00 + r.below(0x400));
      } else if (w < 30) {
        mark(Feature.CHAR_LONE_LOW);
        escapeUnit(0xdc00 + r.below(0x400));
      } else if (w < 65) {
        mark(Feature.CHAR_CONTROL_ESCAPE);
        escapeUnit(r.below(0x20));
      } else {
        mark(Feature.CHAR_BMP_ESCAPE);
        int u = 0x20 + r.below(0x10000 - 0x20 - 0x800);
        escapeUnit(u < 0xd800 ? u : u + 0x800);
      }
    }

    private void escapeUnit(int unit) {
      put('\\');
      put('u');
      put(hex(unit));
    }

    /** Four hex digits, each letter in upper or lower case at random. */
    private String hex(int unit) {
      StringBuilder b = new StringBuilder(4);
      for (int shift = 12; shift >= 0; shift -= 4) {
        char c = Character.forDigit((unit >> shift) & 0xf, 16);
        b.append(c >= 'a' && r.below(2) == 0 ? (char) (c - 'a' + 'A') : c);
      }
      return b.toString();
    }

    /** A valid 2-, 3- or 4-byte sequence (no surrogate, nothing from U+FEC0 to U+FEFF). */
    private byte[] valid(int kind) {
      int cp;
      switch (kind) {
        case 0 -> {
          mark(Feature.CHAR_UTF8_2);
          cp = r.between(0x80, 0x7ff);
        }
        case 1 -> {
          mark(Feature.CHAR_UTF8_3);
          // 0x800..0xFFFF without the 0x800 surrogates and the 0x40 code points U+FEC0..U+FEFF
          cp = 0x800 + r.below(0x10000 - 0x800 - 0x800 - 0x40);
          if (cp >= 0xd800) cp += 0x800;
          if (cp >= 0xfec0) cp += 0x40;
        }
        default -> {
          mark(Feature.CHAR_UTF8_4);
          cp = r.between(0x10000, 0x10ffff);
        }
      }
      return new String(Character.toChars(cp)).getBytes(UTF_8);
    }

    private void invalid(boolean last) {
      switch (r.below(7)) {
        case 0 -> {
          mark(Feature.CHAR_INVALID_LEAD);
          int i = r.below(77);
          put(i < 66 ? 0x80 + i : 0xf5 + i - 66);
        }
        case 1 -> {
          mark(Feature.CHAR_STRAY_CONTINUATION);
          put(valid(0));
          put(r.between(0x80, 0xbf));
        }
        case 2 -> {
          mark(Feature.CHAR_OVERLONG_2);
          put(0xc0);
          put(0x80);
        }
        case 3 -> {
          mark(Feature.CHAR_OVERLONG_3);
          put(0xe0);
          put(0x80);
          put(0x80);
        }
        case 4 -> {
          mark(Feature.CHAR_UTF8_SURROGATE);
          put(0xed);
          put(0xa0);
          put(0x80);
        }
        case 5 -> {
          mark(Feature.CHAR_ABOVE_MAX);
          put(0xf4);
          put(0x90);
          put(0x80);
          put(0x80);
        }
        default -> {
          mark(Feature.CHAR_CUT);
          byte[] whole = valid(r.below(3));
          put(Arrays.copyOf(whole, r.between(1, whole.length - 1)));
          if (!last) put(printable());
        }
      }
    }

    private void number() {
      if (lenient && r.below(3) == 0) {
        lenientTokens++;
        if (r.below(100) < 75) {
          mark(Feature.LENIENT_SHAPE);
          put(LENIENT_SHAPES.get(r.below(LENIENT_SHAPES.size())));
        } else {
          mark(Feature.LENIENT_NAN_INFINITY);
          put(NAN_INFINITY.get(r.below(NAN_INFINITY.size())));
        }
        return;
      }
      int w = r.below(100);
      if (w < 40) {
        mark(Feature.NUM_SMALL_INT);
        put(Integer.toString(r.below(2000) - 1000));
      } else if (w < 60) {
        mark(Feature.NUM_DECIMAL);
        sign();
        put(Integer.toString(r.below(1000)));
        put('.');
        digits(r.between(1, 4));
      } else if (w < 70) {
        if (r.below(2) == 0) {
          mark(Feature.NUM_LONG_EDGE);
          put(LONG_EDGES.get(r.below(LONG_EDGES.size())));
        } else {
          mark(Feature.NUM_LONG_DIGITS);
          sign();
          put('1' + r.below(9));
          digits(r.between(19, 40) - 1);
        }
      } else if (w < 75) {
        mark(Feature.NUM_SIGNIFICANT);
        sign();
        int n = r.between(18, 30);
        int point = r.between(1, n - 1);
        put('1' + r.below(9));
        digits(point - 1);
        put('.');
        digits(n - point);
      } else if (w < 87) {
        mark(Feature.NUM_EXPONENT);
        sign();
        put(Integer.toString(r.below(100)));
        if (r.below(2) == 0) {
          put('.');
          digits(r.between(1, 2));
        }
        put(r.below(2) == 0 ? 'e' : 'E');
        switch (r.below(3)) {
          case 0 -> {}
          case 1 -> put('+');
          default -> put('-');
        }
        digits(r.between(1, 3));
      } else if (w < 92) {
        mark(Feature.NUM_ZERO);
        put(ZEROS.get(r.below(ZEROS.size())));
      } else if (w < 95) {
        mark(Feature.NUM_FRACTION_ZEROS);
        sign();
        put("0.");
        for (int k = r.between(6, 8); k > 0; k--) put('0');
        put('1' + r.below(9));
        digits(r.below(4));
      } else {
        mark(Feature.NUM_EXPONENT_EXTREME);
        put(EXTREMES.get(r.below(EXTREMES.size())));
      }
    }

    private void sign() {
      if (r.below(4) == 0) put('-');
    }

    private void digits(int n) {
      for (int k = 0; k < n; k++) put('0' + r.below(10));
    }

    /** 1 plus a geometric count of mean {@code mean} (each further step with p mean/(mean+1)), at most {@code max}. */
    private int geometric(int mean, int max) {
      int n = 1;
      while (n < max && r.below(mean + 1) != 0) n++;
      return n;
    }

    /** An index drawn by integer weights. */
    private int pick(int... weights) {
      int total = 0;
      for (int w : weights) total += w;
      int x = r.below(total);
      int i = 0;
      while (x >= weights[i]) x -= weights[i++];
      return i;
    }

    private void mark(Feature f) {
      features |= 1L << f.ordinal();
    }

    private void put(int b) {
      if (size == buf.length) buf = Arrays.copyOf(buf, 2 * buf.length);
      buf[size++] = (byte) b;
    }

    private void put(byte[] bytes) {
      for (byte x : bytes) put(x);
    }

    private void put(String ascii) {
      for (int k = 0; k < ascii.length(); k++) put(ascii.charAt(k));
    }

    /** What a rolled-back member restores: the size, the features and the lenient count before it. */
    private final class Mark {
      private final int size = Doc.this.size;
      private final long features = Doc.this.features;
      private final int lenientTokens = Doc.this.lenientTokens;

      void rollback() {
        Doc.this.size = size;
        Doc.this.features = features;
        Doc.this.lenientTokens = lenientTokens;
      }
    }
  }
}
