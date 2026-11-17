package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.assertStale;
import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The compact writer (ADR-0015, ADR-0027, #33 design section 1): {@code JsonWriter.sink(..., compact())} prints each
 * result as jq's {@code -c} does, one line per result, drained before emit returns and never flushed. Every expected
 * text was observed on jq-ref 1.8.2 and is quoted beside its case. {@link #ticksCountReadAndPrintedKiB} is the one test
 * below the API (F-23): it reads the Workspace's package-private step counter {@code steps}.
 */
class JsonWriterTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  /** The compact output of one read document's root, through the OutputStream sink. */
  private static String compact(String json) {
    Workspace workspace = newWorkspace();
    RecordingTarget target = new RecordingTarget();
    JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(read(workspace, json).root());
    return target.text();
  }

  @Test
  void duplicateKeysPrintFirstPositionLastValue() {
    // ADR-0002: "jq keeps the first position with the last value". jq-ref 1.8.2, observed 2026-11-11 with -c .:
    //   {"x":0,"a":1,"b":2,"a":3,"x":9}                      -> {"x":9,"a":3,"b":2}
    //   [{"a":1,"a":{"b":1,"b":2}},{"k":[1],"j":0,"k":[2]}]  -> [{"a":{"b":2}},{"k":[2],"j":0}]
    assertThat(compact("{\"x\":0,\"a\":1,\"b\":2,\"a\":3,\"x\":9}")).as("a flat object").isEqualTo("{\"x\":9,\"a\":3,\"b\":2}\n");
    assertThat(compact("[{\"a\":1,\"a\":{\"b\":1,\"b\":2}},{\"k\":[1],\"j\":0,\"k\":[2]}]"))
        .as("repeated keys whose values are containers, nested")
        .isEqualTo("[{\"a\":{\"b\":2}},{\"k\":[2],\"j\":0}]\n");
    JqObject flat = kind(JqObject.class, read(newWorkspace(), "{\"x\":0,\"a\":1,\"b\":2,\"a\":3,\"x\":9}").root(), ".");
    assertThat(flat.toJson()).as("JqObject.toJson() of the flat object").isEqualTo("{\"x\":9,\"a\":3,\"b\":2}");
  }

  @Test
  void canonicalNumbersCopyOthersPrintInCanonicalForm() {
    // ADR-0012 "CANONICAL": a strict token without an exponent is copied, except [-]0. with 6 or more leading zeros
    // in the fraction, or 7 or more when it is all zeros. jq-ref 1.8.2, observed 2026-11-11, each alone with -c .:
    // every token below prints unchanged.
    for (String token : List.of("-0", "0", "0.000001", "-0.000001", "0.000000", "-0.000000", "100000000000000000000", "1.0000001", "1.000000000", "1.10", "-1.5")) {
      assertThat(compact(token)).as("the writer's output of %s", token).isEqualTo(token + "\n");
      JqNumber number = kind(JqNumber.class, read(newWorkspace(), token).root(), token);
      assertThat(number.toJson()).as("JqNumber.toJson() of %s", token).isEqualTo(token);
    }
    assertThat(compact("[-0,100000000000000000000,-1.5]")).as("numbers in an array").isEqualTo("[-0,100000000000000000000,-1.5]\n");
    // Any other literal prints in jq's canonical form (#36, the Stub ending of #33's C12 rule: these tokens were
    // Stubs until #36). jq-ref 1.8.2, observed 2026-11-12, each alone with printf -- '<token>' | jq-ref -c .:
    //   1e2 -> 1E+2, 1E+2 -> 1E+2, 0.0000001 -> 1E-7, -0.0000001 -> -1E-7, 0.0000000 -> 0E-7, -0.0000000 -> -0E-7
    String[][] printed = {{"1e2", "1E+2"}, {"1E+2", "1E+2"}, {"0.0000001", "1E-7"}, {"-0.0000001", "-1E-7"}, {"0.0000000", "0E-7"}, {"-0.0000000", "-0E-7"}};
    for (String[] row : printed) {
      assertThat(compact(row[0])).as("the writer's output of %s", row[0]).isEqualTo(row[1] + "\n");
      JqNumber number = kind(JqNumber.class, read(newWorkspace(), row[0]).root(), row[0]);
      assertThat(number.toJson()).as("JqNumber.toJson() of %s", row[0]).isEqualTo(row[1]);
    }
    // number.rec, case ticket-object: {"a":1e2,"b":1.10,"c":-0,"d":0.0000001} -> {"a":1E+2,"b":1.10,"c":-0,"d":1E-7}
    assertThat(compact("{\"a\":1e2,\"b\":1.10,\"c\":-0,\"d\":0.0000001}"))
        .as("copied and canonical numbers in one object")
        .isEqualTo("{\"a\":1E+2,\"b\":1.10,\"c\":-0,\"d\":1E-7}\n");
  }

  /** 100 000 digits, "1234567890" ten thousand times, so a piece printed twice, dropped or moved shows. */
  private static final String DIGITS = "1234567890".repeat(10_000);

  @Test
  void numberLongerThanTheBufferPrintsInPieces() {
    // A token that is not CANONICAL and whose canonical form is longer than the 64 KiB print buffer. jq-ref 1.8.2,
    // observed 2026-11-12: the 100 002-byte token DIGITS followed by e1 under -c . prints "1.", the other 99 999
    // digits, "E+100000" and an LF: 100 010 bytes, byte-identical (cmp) to that text.
    String token = DIGITS + "e1";
    String expected = "1." + DIGITS.substring(1) + "E+100000\n";
    Workspace workspace = newWorkspace();

    RecordingTarget whole = new RecordingTarget();
    assertThat(JsonWriter.sink(workspace, whole, OutputOptions.compact()).emit(read(workspace, token).root()))
        .as("emit's answer for the 100 002-byte token")
        .isTrue();
    assertThat(whole.text()).as("output of the 100 002-byte token").isEqualTo(expected);
    assertThat(whole.writes).as("write calls for a 100 010-byte result").isGreaterThan(1);
    assertThat(kind(JqNumber.class, read(workspace, token).root(), "the token").toJson())
        .as("JqNumber.toJson() of the 100 002-byte token")
        .isEqualTo(expected.substring(0, expected.length() - 1));

    // ADR-0027: the target reads another document into the same Workspace during its first write, in the middle
    // of the number, so the rest of it must not print.
    List<JsonDoc> readInside = new ArrayList<>();
    RecordingTarget reading =
        new RecordingTarget(
            n -> {
              if (n == 1) readInside.add(read(workspace, "[7]"));
            });
    JqSink sink = JsonWriter.sink(workspace, reading, OutputOptions.compact());
    JqValue root = read(workspace, token).root();
    assertStale(() -> sink.emit(root),"emit of the 100 002-byte token after its target read [7]");
    assertThat(reading.writes).as("write calls after the read inside the first one").isEqualTo(1);
    assertThat(reading.text()).as("bytes written before the token went stale").isNotEmpty();
    assertThat(expected).as("the bytes written are a prefix of the result").startsWith(reading.text());
    // Witness: the document read inside the write is the current one and reads.
    assertThat(readInside).as("documents read inside the write").hasSize(1);
    JqArray seven = kind(JqArray.class, readInside.get(0).root(), "[7]");
    assertThat(kind(JqNumber.class, seven.get(0), "[7][0]").longValue()).as("[7][0]").isEqualTo(7L);
  }

  @Test
  void tenThousandNestedArraysPrint() {
    // ADR-0015 "Depth": 10 000 arrays pass. jq-ref 1.8.2, observed 2026-11-11: 10 000 '[' then 10 000 ']' under -c .
    // prints the input and an LF (20 001 bytes, rc 0).
    String nested = "[".repeat(10_000) + "]".repeat(10_000);
    assertThat(compact(nested)).as("10 000 nested arrays").isEqualTo(nested + "\n");
  }

  @Test
  void eachResultIsOneLine() {
    // ADR-0027: each emit writes the result and one LF, which reach the target before emit returns; the sink never
    // flushes. jq-ref 1.8.2, observed 2026-11-11: null | jq-ref -c '., true, false' -> null, true, false on three
    // lines; printf '{"a":1} [2]' | jq-ref -c . -> {"a":1} and [2] on two lines.
    Workspace workspace = newWorkspace();
    RecordingTarget stream = new RecordingTarget();
    RecordingTarget behindChannel = new RecordingTarget();
    List<JqSink> sinks =
        List.of(
            JsonWriter.sink(workspace, stream, OutputOptions.compact()),
            JsonWriter.sink(workspace, Channels.newChannel(behindChannel), OutputOptions.compact()));
    for (JqSink sink : sinks) {
      assertThat(sink.emit(JqNull.NULL)).as("emit's answer for null").isTrue();
      assertThat(sink.emit(JqBool.TRUE)).as("emit's answer for true").isTrue();
      assertThat(sink.emit(JqBool.FALSE)).as("emit's answer for false").isTrue();
    }
    assertThat(stream.text()).as("OutputStream target after three emits").isEqualTo("null\ntrue\nfalse\n");
    assertThat(behindChannel.text()).as("channel target after three emits").isEqualTo("null\ntrue\nfalse\n");

    for (JqSink sink : sinks) {
      assertThat(sink.emit(read(workspace, "{\"a\":1}").root())).as("emit's answer for {\"a\":1}").isTrue();
    }
    assertThat(stream.text()).as("OutputStream target right after emitting {\"a\":1}").isEqualTo("null\ntrue\nfalse\n{\"a\":1}\n");
    assertThat(behindChannel.text()).as("channel target right after emitting {\"a\":1}").isEqualTo("null\ntrue\nfalse\n{\"a\":1}\n");
    for (JqSink sink : sinks) {
      assertThat(sink.emit(read(workspace, "[2]").root())).as("emit's answer for [2]").isTrue();
    }
    assertThat(stream.text()).as("OutputStream target after the second document").isEqualTo("null\ntrue\nfalse\n{\"a\":1}\n[2]\n");
    assertThat(behindChannel.text()).as("channel target after the second document").isEqualTo("null\ntrue\nfalse\n{\"a\":1}\n[2]\n");
    assertThat(stream.flushes).as("flush calls on the OutputStream target").isZero();
  }

  @Test
  void prettySinkPrintsIndentTwo() {
    // #33 design kept pretty() a Stub at sink until #37, which built it (#33 C12: the entry ends with its Stub).
    // pretty.rec pretty/nested-empty, jq-ref 1.8.2: {"a":[],"b":{},"c":[[]]} without -c prints the text below.
    String document = "{\"a\":[],\"b\":{},\"c\":[[]]}";
    String recorded = "{\n  \"a\": [],\n  \"b\": {},\n  \"c\": [\n    []\n  ]\n}\n";
    Workspace workspace = newWorkspace();
    RecordingTarget prettyStream = new RecordingTarget();
    RecordingTarget prettyBehindChannel = new RecordingTarget();
    JsonWriter.sink(workspace, prettyStream, OutputOptions.pretty()).emit(read(workspace, document).root());
    JsonWriter.sink(workspace, Channels.newChannel(prettyBehindChannel), OutputOptions.pretty()).emit(read(workspace, document).root());
    assertThat(prettyStream.text()).as("pretty over an OutputStream").isEqualTo(recorded);
    assertThat(prettyBehindChannel.text()).as("pretty over a channel").isEqualTo(recorded);
    // Witness: compact() on the same overloads gives sinks that write.
    RecordingTarget stream = new RecordingTarget();
    RecordingTarget behindChannel = new RecordingTarget();
    JsonWriter.sink(workspace, stream, OutputOptions.compact()).emit(JqNull.NULL);
    JsonWriter.sink(workspace, Channels.newChannel(behindChannel), OutputOptions.compact()).emit(JqNull.NULL);
    assertThat(stream.text()).as("compact over an OutputStream").isEqualTo("null\n");
    assertThat(behindChannel.text()).as("compact over a channel").isEqualTo("null\n");
  }

  @Test
  void aTargetsIOExceptionLeavesEmitAsUncheckedIOException() {
    // ADR-0027 "Errors": a target's IOException leaves emit as UncheckedIOException with it as the cause.
    IOException full = new IOException("the target is full");
    Workspace workspace = newWorkspace();
    JqSink sink =
        JsonWriter.sink(
            workspace,
            new RecordingTarget(
                n -> {
                  throw full;
                }),
            OutputOptions.compact());

    assertThatThrownBy(() -> sink.emit(read(workspace, "[1]").root()))
        .isInstanceOfSatisfying(UncheckedIOException.class, e -> assertThat(e.getCause()).as("the cause").isSameAs(full));
  }

  /** A document, its byte length's ticks, and the ticks of printing it with its LF (F-23, one per started KiB). */
  private record Ticks(String name, String document, long read, long printed, int outputBytes) {}

  @Test
  void ticksCountReadAndPrintedKiB() {
    // F-23: Workspace.tick(int) adds to the package-private counter `steps`. The reader ticks once per read with
    // (len+1023)>>>10, the printer once per drain with the KiB drained, the LF included; the print buffer is 64 KiB.
    // jq-ref 1.8.2, observed 2026-11-11 with -c .: a string of k letters prints unchanged plus LF (1 024, 1 025,
    // 1 026 and 70 003 bytes for k = 1 021, 1 022, 1 023 and 70 000); the 998 letters followed by 2 000 spaces print
    // 1 001 bytes; 1 prints 2.
    List<Ticks> cases =
        List.of(
            new Ticks("the number 1", "1", 1, 1, 2),
            new Ticks("a 1 023-byte string", letters(1_021), 1, 1, 1_024),
            new Ticks("a 1 024-byte string", letters(1_022), 1, 2, 1_025),
            new Ticks("a 1 025-byte string", letters(1_023), 2, 2, 1_026),
            new Ticks("a 1 000-byte string and 2 000 spaces", letters(998) + " ".repeat(2_000), 3, 1, 1_001),
            // 70 003 bytes drain as 64 KiB, then 4 467 bytes: 64 + 5 ticks.
            new Ticks("a 70 002-byte string", letters(70_000), 69, 69, 70_003));
    for (Ticks c : cases) {
      Workspace workspace = newWorkspace();
      assertThat(workspace.steps()).as("%s: steps of a new Workspace", c.name()).isZero();

      JsonDoc doc = workspace.read(MemorySegment.ofArray(c.document().getBytes(UTF_8)));
      assertThat(workspace.steps()).as("%s: steps after reading %d bytes", c.name(), c.document().length()).isEqualTo(c.read());

      RecordingTarget target = new RecordingTarget();
      JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(doc.root());
      assertThat(target.bytes().length).as("%s: bytes printed", c.name()).isEqualTo(c.outputBytes());
      assertThat(workspace.steps()).as("%s: steps after printing %d bytes", c.name(), c.outputBytes()).isEqualTo(c.read() + c.printed());
    }
  }

  private static String letters(int k) {
    return "\"" + "a".repeat(k) + "\"";
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  /**
   * Reader and writer round trip (CODING_STANDARDS "Property tests"). Each seed makes a document of the kinds #33
   * prints (null, booleans, CANONICAL numbers, tag-5 strings with non-ASCII, nested containers, distinct keys) with
   * SP, TAB, CR and LF between its tokens, and its compact text by the rules of ADR-0012 and ADR-0015. #35's design
   * adds strings and keys holding the units of {@link #UNITS}: escape sequences, DEL and bad bytes. The writer's
   * output of the document must be that text, and its output of its own output must be the same bytes.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void writerIsAFixpointOverItsOwnOutput(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    ByteArrayOutputStream spaced = new ByteArrayOutputStream();
    StringBuilder expected = new StringBuilder();
    space(random, spaced);
    value(random, 0, spaced, expected);
    space(random, spaced);

    byte[] input = spaced.toByteArray();
    String first = compact(input);
    assertThat(first).as("seed %d: the writer's output of %s", seed, HexFormat.ofDelimiter(" ").formatHex(input)).isEqualTo(expected + "\n");
    String second = compact(first);
    assertThat(second).as("seed %d: the writer's output of its own output", seed).isEqualTo(first);
  }

  /** The compact output of the document in {@code json}'s bytes, through the OutputStream sink. */
  private static String compact(byte[] json) {
    Workspace workspace = newWorkspace();
    RecordingTarget target = new RecordingTarget();
    JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(workspace.read(MemorySegment.ofArray(json)).root());
    return target.text();
  }

  /** A string unit: its bytes inside a JSON string, and the text jq prints for it there. */
  private record Unit(byte[] input, String printed) {}

  private static Unit unit(String hexOrNull, String escaped, String printed) {
    return new Unit(hexOrNull == null ? escaped.getBytes(UTF_8) : HexFormat.ofDelimiter(" ").parseHex(hexOrNull), printed);
  }

  /**
   * Units whose printed text does not depend on their neighbours. jq-ref 1.8.2, observed 2026-11-12, each alone in an
   * array: ["\\u0041","\/","\n","\"","\\","\b","\f","\r","\t","\\u00E9","\\u0001","\\u001F","\\u007f","&lt;DEL&gt;",
   * "\\ud834\\udd1e","\\udc00","\\uFFFE","\x80","\xff","\xc0\x80","\xed\xa0\x80","\xf4\x90\x80\x80","\xe0\x80\x80"]
   * -> ["A","/","\n","\"","\\","\b","\f","\r","\t","é","\\u0001","\\u001f","\\u007f","\\u007f","𝄞","�","￾","�","�",
   * "��","�","�","�"]; and side by side in one string they print the concatenation of these texts (PrinterStringTest's
   * last row of tag6PrintsAsJvpDumpString). Here \\u is one backslash and u, and \xHH a raw byte.
   */
  private static final Unit[] UNITS = {
    unit(null, "\\u0041", "A"),
    unit(null, "\\/", "/"),
    unit(null, "\\n", "\\n"),
    unit(null, "\\\"", "\\\""),
    unit(null, "\\\\", "\\\\"),
    unit(null, "\\b", "\\b"),
    unit(null, "\\f", "\\f"),
    unit(null, "\\r", "\\r"),
    unit(null, "\\t", "\\t"),
    unit(null, "\\u00E9", "é"),
    unit(null, "\\u0001", "\\u0001"),
    unit(null, "\\u001F", "\\u001f"),
    unit(null, "\\u007f", "\\u007f"),
    unit("7f", null, "\\u007f"),
    unit(null, "\\ud834\\udd1e", "𝄞"),
    unit(null, "\\udc00", "\ufffd"),
    unit(null, "\\uFFFE", "\ufffe"),
    unit("80", null, "\ufffd"),
    unit("ff", null, "\ufffd"),
    unit("c0 80", null, "\ufffd\ufffd"),
    unit("ed a0 80", null, "\ufffd"),
    unit("f4 90 80 80", null, "\ufffd"),
    unit("e0 80 80", null, "\ufffd"),
  };

  private static final int MAX_DEPTH = 4;

  /** ADR-0015: the whitespace jq's reader skips. */
  private static final String[] WHITESPACE = {"", "", " ", "\t", "\r\n", "\n", "  ", " \t\r\n"};

  /** Code points that need no escape and are well-formed UTF-8 of 2, 3 and 4 bytes, edges included. */
  private static final int[] NON_ASCII = {0x80, 0xE9, 0x3C0, 0x7FF, 0x800, 0x20AC, 0x2028, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 0x1D11E, 0x10FFFF};

  private static void space(RandomGenerator random, ByteArrayOutputStream spaced) {
    spaced.writeBytes(WHITESPACE[random.nextInt(WHITESPACE.length)].getBytes(UTF_8));
  }

  private static void value(RandomGenerator random, int depth, ByteArrayOutputStream spaced, StringBuilder expected) {
    int kind = random.nextInt(depth < MAX_DEPTH ? 7 : 5);
    switch (kind) {
      case 0 -> both("null", spaced, expected);
      case 1 -> both("true", spaced, expected);
      case 2 -> both("false", spaced, expected);
      case 3 -> both(number(random), spaced, expected);
      case 4 -> both(string(random), spaced, expected);
      case 5 -> {
        both("[", spaced, expected);
        int n = random.nextInt(0, 5);
        for (int i = 0; i < n; i++) {
          if (i > 0) both(",", spaced, expected);
          space(random, spaced);
          value(random, depth + 1, spaced, expected);
          space(random, spaced);
        }
        if (n == 0) space(random, spaced);
        both("]", spaced, expected);
      }
      default -> {
        both("{", spaced, expected);
        int n = random.nextInt(0, 5);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < n; i++) {
          Unit key = string(random);
          // Distinct by the printed text: two spellings of one decoded key are one key (ADR-0002).
          if (!keys.add(key.printed())) continue;
          if (keys.size() > 1) both(",", spaced, expected);
          space(random, spaced);
          both(key, spaced, expected);
          space(random, spaced);
          both(":", spaced, expected);
          space(random, spaced);
          value(random, depth + 1, spaced, expected);
          space(random, spaced);
        }
        if (keys.isEmpty()) space(random, spaced);
        both("}", spaced, expected);
      }
    }
  }

  private static void both(String token, ByteArrayOutputStream spaced, StringBuilder expected) {
    spaced.writeBytes(token.getBytes(UTF_8));
    expected.append(token);
  }

  private static void both(Unit string, ByteArrayOutputStream spaced, StringBuilder expected) {
    spaced.writeBytes(string.input());
    expected.append(string.printed());
  }

  /** A strict JSON number without an exponent that ADR-0012 calls CANONICAL. */
  private static String number(RandomGenerator random) {
    StringBuilder b = new StringBuilder();
    if (random.nextBoolean()) b.append('-');
    boolean zero = random.nextInt(4) == 0;
    if (zero) {
      b.append('0');
    } else {
      b.append((char) ('1' + random.nextInt(9)));
      int more = random.nextInt(0, 21);
      for (int i = 0; i < more; i++) b.append((char) ('0' + random.nextInt(10)));
    }
    if (random.nextBoolean()) {
      char[] fraction = new char[random.nextInt(1, 9)];
      for (int i = 0; i < fraction.length; i++) fraction[i] = (char) ('0' + random.nextInt(10));
      if (zero) {
        int lead = 0;
        while (lead < fraction.length && fraction[lead] == '0') lead++;
        // ADR-0012: not CANONICAL with 6 or more leading zeros, or 7 or more when the fraction is all zeros.
        if (lead == fraction.length && fraction.length > 6) fraction = new char[] {'0', '0', '0', '0', '0', '0'};
        else if (lead < fraction.length && lead >= 6) fraction[0] = '1';
      }
      b.append('.').append(fraction);
    }
    return b.toString();
  }

  /**
   * A string, quotes included, and its compact text: plain ASCII and well-formed non-ASCII (tag 5 when alone), and a
   * unit of {@link #UNITS} in one char of five (tag 6).
   */
  private static Unit string(RandomGenerator random) {
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    StringBuilder printed = new StringBuilder("\"");
    input.write('"');
    int n = random.nextInt(0, 9);
    for (int i = 0; i < n; i++) {
      int pick = random.nextInt(10);
      if (pick < 6) {
        char c;
        do {
          c = (char) random.nextInt(0x20, 0x7F);
        } while (c == '"' || c == '\\');
        input.write(c);
        printed.append(c);
      } else if (pick < 8) {
        String s = Character.toString(NON_ASCII[random.nextInt(NON_ASCII.length)]);
        input.writeBytes(s.getBytes(UTF_8));
        printed.append(s);
      } else {
        Unit u = UNITS[random.nextInt(UNITS.length)];
        input.writeBytes(u.input());
        printed.append(u.printed());
      }
    }
    input.write('"');
    return new Unit(input.toByteArray(), printed.append('"').toString());
  }
}
