package io.flottila.mapping.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.InputFormat;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A JSON input (ADR-0008 "JSON as a source at run time", ADR-0006 "A SourceQueue over a JSON
 * field", #98) at {@code Interpreter.compile} and {@code ExecutableMapping.run}, over IR built by
 * hand as lowering gives it: the queues the projection fills, read from a traced compile, and the
 * input the parser refuses, with the position its message names. JSON texts are written with
 * apostrophes for quotes ({@link #json}).
 */
class JsonInputTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-05T12:00:00Z");
  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final Name OUT = new Name("", "Out");
  private static final long UNBOUNDED = Long.MAX_VALUE;

  /** A backslash, so that no JSON escape below is near a Java one. */
  private static final String BS = "\\";

  // --- the queues of the JSON fields, as lowering gives them

  private static Name step(String name) {
    return new Name("", name);
  }

  private static SourceQueue queue(
      int contextDepth, boolean structureField, List<Integer> arrays, String... properties) {
    List<Name> field = new ArrayList<>();
    field.add(step("$"));
    for (String property : properties) {
      field.add(step(property));
    }
    return new SourceQueue(
        field, contextDepth, structureField, false, arrays, new AuthoringId("unused"));
  }

  /** {@code $/v}, a leaf under the root. */
  private static final SourceQueue V = queue(1, false, List.of(), "v");

  /** {@code $/order}, an object. */
  private static final SourceQueue ORDER = queue(1, true, List.of(), "order");

  /** {@code $/order/id}, a leaf in the context of its order. */
  private static final SourceQueue ID = queue(2, false, List.of(), "order", "id");

  /** {@code $/lines}, an array of objects. */
  private static final SourceQueue LINES = queue(1, true, List.of(2), "lines");

  /** {@code $/lines/sku}, a leaf in the context of its line. */
  private static final SourceQueue SKU = queue(2, false, List.of(2), "lines", "sku");

  /** {@code $/tags}, an array of scalars. */
  private static final SourceQueue TAGS = queue(1, false, List.of(2), "tags");

  // --- running

  /** {@code text} with each apostrophe a quote. */
  private static String json(String text) {
    return text.replace('\'', '"');
  }

  /** The JSON escape {@code \}{@code u} and {@code hex}. */
  private static String u(String hex) {
    return BS + "u" + hex;
  }

  /**
   * A JSON input's document: {@code <All>} per value of each queue's first context, under {@code
   * Out}; queue k's box is {@code s}k.
   */
  private static TargetDocument document(SourceQueue... queues) {
    List<Expr> content = new ArrayList<>();
    for (int k = 0; k < queues.length; k++) {
      SourceQueue q = queues[k];
      AuthoringId box = new AuthoringId("s" + k);
      AuthoringId origin = new AuthoringId("t" + k);
      String value = "v" + k;
      content.add(
          new TargetSlots(
              new SourceQueue(
                  q.field(), q.contextDepth(), q.structureField(), false, q.arrays(), box),
              new VarRef("root", AtomicType.INTEGER, origin),
              0,
              UNBOUNDED,
              "slot" + k,
              value,
              new ElementConstructor(
                  "",
                  step("All"),
                  List.of(
                      new TextConstructor(new VarRef(value, AtomicType.STRING, origin), origin)),
                  origin),
              origin));
    }
    TargetDocument document = new TargetDocument("", OUT, "root", content, InputFormat.JSON);
    IrVerifier.verify(document);
    return document;
  }

  private static String run(TargetDocument document, byte[] input, Tracer tracer) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Interpreter.compile(document, true).run(new ByteArrayInputStream(input), out, tracer, NOW);
    return out.toString(UTF_8);
  }

  /** Each queue's block, as contexts of values, read from {@code text}. */
  private static List<List<List<String>>> read(String text, SourceQueue... queues)
      throws Exception {
    Tracer tracer = new Tracer();
    run(document(queues), text.getBytes(UTF_8), tracer);
    List<List<List<String>>> blocks = new ArrayList<>();
    for (int k = 0; k < queues.length; k++) {
      AuthoringId box = new AuthoringId("s" + k);
      List<QueueBlock> reported =
          tracer.values().stream()
              .filter(e -> e.getKey().equals(box))
              .map(Map.Entry::getValue)
              .toList();
      assertThat(reported).as("reports of %s", box).hasSize(1);
      blocks.add(contexts(reported.getFirst()));
    }
    return blocks;
  }

  /** The one queue's block, read from {@code text}. */
  private static List<List<String>> read(SourceQueue queue, String text) throws Exception {
    return read(text, queue).getFirst();
  }

  private static List<List<String>> contexts(QueueBlock block) {
    List<List<String>> contexts = new ArrayList<>();
    for (int c = 0; c < block.contexts(); c++) {
      List<String> context = new ArrayList<>();
      for (int i = block.start(c); i < block.end(c); i++) {
        context.add(block.value(i));
      }
      contexts.add(context);
    }
    return contexts;
  }

  /** No context, or one empty: a queue whose field never occurs. */
  private static final List<List<String>> ABSENT = List.of(List.of());

  // --- the projection

  @Test
  void eachFieldReadsItsOccurrencesInItsContexts() throws Exception {
    String text =
        json(
            "{'skipped': {'x': [1, {'y': null}], 'z': 'w'},"
                + " 'order': {'id': 'o1'},"
                + " 'lines': [{'sku': 'a'}, {'sku': 1.50}, {}, {'sku': true}, {'sku': false},"
                + " {'sku': null}, 's', 2, [{'sku': 'nested'}], {'sku': {'deep': 1}},"
                + " {'sku': ['x']}],"
                + " 'tags': ['t', 1e2, -0.0, true, false, null, {'no': 1}, ['no']]}");

    List<List<List<String>>> blocks = read(text, ORDER, ID, LINES, SKU, TAGS);

    assertThat(blocks.get(0)).as("order").containsExactly(List.of(""));
    assertThat(blocks.get(1)).as("id").containsExactly(List.of("o1"));
    // one occurrence per object item; a scalar or an array item is none
    assertThat(blocks.get(2)).as("lines").containsExactly(List.of("", "", "", "", "", "", "", ""));
    // a context per object item; an object or an array for the leaf reads as absent
    assertThat(blocks.get(3))
        .as("sku")
        .containsExactly(
            List.of("a"),
            List.of("1.50"),
            List.of(),
            List.of("true"),
            List.of("false"),
            List.of(""),
            List.of(),
            List.of());
    // a scalar item each, an object or an array item none
    assertThat(blocks.get(4))
        .as("tags")
        .containsExactly(List.of("t", "1e2", "-0.0", "true", "false", ""));
  }

  @Test
  void theOutputHoldsTheFirstContextsValues() throws Exception {
    String output = run(document(TAGS), json("{'tags': ['a', 'b']}").getBytes(UTF_8), new Tracer());

    assertThat(output).isEqualTo(DECLARATION + "<Out><All>a</All><All>b</All></Out>");
  }

  static Stream<Arguments> kindMismatches() {
    return Stream.of(
        Arguments.of("scalars for objects and arrays", "{'order': 'o', 'lines': 1, 'tags': 't'}"),
        Arguments.of("an object for an array", "{'lines': {'sku': 'a'}, 'tags': {'t': 1}}"),
        Arguments.of("an array for an object", "{'order': [{'id': 'x'}]}"),
        Arguments.of("null for an object and for an array", "{'order': null, 'lines': null}"),
        Arguments.of("empty arrays", "{'lines': [], 'tags': []}"),
        Arguments.of("a root that is an array", "[{'order': {'id': 'x'}}]"),
        Arguments.of("a root that is a scalar", "'x'"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("kindMismatches")
  void aValueOfTheWrongKindReadsAsAbsent(String why, String text) throws Exception {
    assertThat(read(json(text), ORDER, ID, LINES, SKU, TAGS))
        .containsExactly(ABSENT, ABSENT, ABSENT, ABSENT, ABSENT);
  }

  @Test
  void aLeafReadsAnyScalarAndNoContainer() throws Exception {
    assertThat(read(V, json("{'w': '2', 'v': '1'}"))).containsExactly(List.of("1"));
    assertThat(read(V, json("{'v': {'x': 1}}"))).isEqualTo(ABSENT);
    assertThat(read(V, json("{'v': [1]}"))).isEqualTo(ABSENT);
  }

  @Test
  void aNameIsReadAtItsOwnLevelOnly() throws Exception {
    assertThat(read(ID, json("{'order': {'order': {'id': 'deep'}, 'id': 'o'}, 'id': 'top'}")))
        .containsExactly(List.of("o"));
  }

  @Test
  void aRootArrayReadsEachObjectItem() throws Exception {
    SourceQueue items = queue(1, false, List.of(1), "v");

    assertThat(read(items, json("[{'v': 'a'}, {'v': 'b'}, 'c', {'w': 1}]")))
        .containsExactly(List.of("a"), List.of("b"), List.of());
    // witness: the root as an object is no array, so it reads as absent
    assertThat(read(items, json("{'v': 'a'}"))).isEqualTo(ABSENT);
  }

  @Test
  void numbersKeepTheirSpelling() throws Exception {
    assertThat(read(TAGS, json("{'tags': [0, -0, 90, 1.09, -12.5e10, 1E+2, 1e-2, 1e2]}")))
        .containsExactly(List.of("0", "-0", "90", "1.09", "-12.5e10", "1E+2", "1e-2", "1e2"));
  }

  @Test
  void stringsAreWhatTheyEscape() throws Exception {
    String text =
        json(
            "{'tags': ['"
                + BS
                + "' "
                + BS
                + BS
                + " "
                + BS
                + "/ "
                + BS
                + "b"
                + BS
                + "f"
                + BS
                + "n"
                + BS
                + "r"
                + BS
                + "t', '"
                + u("004a")
                + u("004f")
                + u("004A")
                + u("004F")
                + u("0030")
                + u("0039")
                + "', '"
                + u("00e9")
                + u("D83D")
                + u("DE00")
                + "', ' "
                + "~']}");

    // backspace and form feed are U+FFFD, as json-to-xml gives them
    assertThat(read(TAGS, text))
        .containsExactly(List.of("\" \\ / " + FFFD + FFFD + "\n\r\t", "JOJO09", "é😀", " ~"));
  }

  /** U+FFFD, which json-to-xml gives for a character XML 1.0 cannot hold. */
  private static final String FFFD = String.valueOf((char) 0xFFFD);

  static Stream<Arguments> charactersXmlCannotHold() {
    String high = String.valueOf((char) 0xD800);
    return Stream.of(
        Arguments.of(u("0000"), FFFD),
        Arguments.of(u("001f"), FFFD),
        Arguments.of(u("0020"), " "),
        Arguments.of(u("0009") + u("000a") + u("000d"), "\t\n\r"),
        Arguments.of(u("007f"), String.valueOf((char) 0x7F)),
        Arguments.of(u("fffe"), FFFD),
        Arguments.of(u("FFFF"), FFFD),
        Arguments.of(u("fffd"), FFFD),
        Arguments.of("a" + u("D800") + "b", "a" + FFFD + "b"),
        Arguments.of("a" + u("DC00") + "b", "a" + FFFD + "b"),
        Arguments.of(u("D800") + u("D800") + u("DC00"), FFFD + high + (char) 0xDC00),
        Arguments.of("a" + u("DBFF"), "a" + FFFD),
        Arguments.of(u("D83D") + u("DE00") + "😀", "😀😀"));
  }

  /** The characters as json-to-xml gives them, observed in Saxon-HE 12.5 (#98). */
  @ParameterizedTest(name = "{1}")
  @MethodSource("charactersXmlCannotHold")
  void aCharacterXmlCannotHoldIsReplaced(String escaped, String read) throws Exception {
    assertThat(read(TAGS, json("{'tags': ['" + escaped + "']}"))).containsExactly(List.of(read));
  }

  @Test
  void whiteSpaceStandsAroundEveryToken() throws Exception {
    assertThat(read(TAGS, " \t\n\r{ \t\n\r\"tags\" \t\n\r: \t\n\r[ 1 , 2 ] \t\n\r} \t\n\r"))
        .containsExactly(List.of("1", "2"));
  }

  @Test
  void deepNestingNeedsNoCallStack() throws Exception {
    String deep = "[".repeat(40) + "]".repeat(40);

    assertThat(read(TAGS, json("{'other': " + deep + ", 'tags': [" + deep + ", 'a']}")))
        .containsExactly(List.of("a"));
  }

  @Test
  void aValueLongerThanTheBufferIsReadWhole() throws Exception {
    String long1 = "a".repeat(9000);
    String text = json("{'tags': ['" + long1 + "', 'b']}");

    assertThat(read(TAGS, text)).containsExactly(List.of(long1, "b"));
  }

  // --- malformed input

  /** {@code text} is refused, with {@code message} and the position it names. */
  private static void assertMalformed(String text, String message) {
    assertThatThrownBy(() -> read(TAGS, text))
        .isInstanceOf(MalformedInputException.class)
        .hasMessage(message);
  }

  static Stream<Arguments> malformedTexts() {
    return Stream.of(
        Arguments.of("", "expected a value, not the end of the input at character 1"),
        Arguments.of("  ", "expected a value, not the end of the input at character 3"),
        Arguments.of("{'tags': [1]} x", "content after the JSON value at character 15"),
        Arguments.of("{", "expected a key at character 2"),
        Arguments.of("{1: 2}", "expected a key at character 2"),
        Arguments.of("{'a': 1, }", "expected a key at character 10"),
        Arguments.of("{'a' 1}", "expected : after a key at character 6"),
        Arguments.of("{'a'", "expected : after a key at character 5"),
        Arguments.of("{'a':", "expected a value, not the end of the input at character 6"),
        Arguments.of("{'a': 1", "expected , or } in an object at character 8"),
        Arguments.of("{'a': 1 'b': 2}", "expected , or } in an object at character 9"),
        Arguments.of("{'a': 1]", "expected , or } in an object at character 8"),
        Arguments.of("[", "expected a value, not the end of the input at character 2"),
        Arguments.of("[1 2]", "expected , or ] in an array at character 4"),
        Arguments.of("[1}", "expected , or ] in an array at character 3"),
        Arguments.of("[1, ]", "expected a digit at character 5"),
        Arguments.of("[-]", "expected a digit at character 3"),
        Arguments.of("[+1]", "expected a digit at character 2"),
        Arguments.of("[.5]", "expected a digit at character 2"),
        Arguments.of("[01]", "expected , or ] in an array at character 3"),
        Arguments.of("[1.]", "expected a digit after the decimal point at character 4"),
        Arguments.of("[1.e2]", "expected a digit after the decimal point at character 4"),
        Arguments.of("[1e]", "expected a digit in the exponent at character 4"),
        Arguments.of("[1E+]", "expected a digit in the exponent at character 5"),
        Arguments.of("[1e-x]", "expected a digit in the exponent at character 5"),
        Arguments.of("[tru]", "expected true at character 5"),
        Arguments.of("[trUe]", "expected true at character 4"),
        Arguments.of("[fals", "expected false at character 6"),
        Arguments.of("[nul1]", "expected null at character 5"),
        Arguments.of("['a", "a string without its closing quote at character 4"),
        Arguments.of("['" + BS + "x']", "an escape that RFC 8259 does not define at character 5"),
        Arguments.of("['" + u("12") + "", "expected four hex digits after \\u at character 7"),
        Arguments.of("['" + u("12/4") + "']", "expected four hex digits after \\u at character 7"),
        Arguments.of("['" + u("12:4") + "']", "expected four hex digits after \\u at character 7"),
        Arguments.of("['" + u("12`4") + "']", "expected four hex digits after \\u at character 7"),
        Arguments.of("['" + u("12g4") + "']", "expected four hex digits after \\u at character 7"),
        Arguments.of("['" + u("12@4") + "']", "expected four hex digits after \\u at character 7"),
        Arguments.of("['" + u("12G4") + "']", "expected four hex digits after \\u at character 7"),
        Arguments.of("['a" + (char) 0x1F + "']", "a control character in a string at character 5"),
        // raw, unescaped: unparsed-text refuses them (FOUT1190, observed in Saxon-HE 12.5, #89)
        Arguments.of(
            "['a" + (char) 0xFFFE + "']",
            "a character XML 1.0 cannot hold in a string at character 4"),
        Arguments.of(
            "{'k" + (char) 0xFFFF + "': 1}",
            "a character XML 1.0 cannot hold in a string at character 4"),
        // a byte order mark only leads the text; anywhere else it is no JSON token
        Arguments.of("[" + BOM + "1]", "expected a digit at character 2"),
        Arguments.of(BOM + BOM + "[1]", "expected a digit at character 2"));
  }

  /** U+FEFF, the byte order mark. */
  private static final String BOM = String.valueOf((char) 0xFEFF);

  /**
   * A leading byte order mark is dropped, as {@code unparsed-text} drops it (observed in Saxon-HE
   * 12.5, #89), and counts in a position; U+FEFF inside a string is a character XML holds.
   */
  @Test
  void aLeadingByteOrderMarkIsDropped() throws Exception {
    assertThat(read(TAGS, BOM + json("{'tags': ['a', '" + BOM + "b']}")))
        .containsExactly(List.of("a", BOM + "b"));
    assertMalformed(
        BOM + json("{'tags': [1]} x"),
        "content after the JSON value at character 16 of the JSON input");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedTexts")
  void aTextRfc8259RefusesIsMalformed(String text, String message) {
    assertMalformed(json(text), message + " of the JSON input");
  }

  @Test
  void aPositionPastTheBufferCountsEveryCharacterBefore() {
    // {"tags":[ is 9 characters, the string 9002, then ] and a space: x is character 9014
    String text = json("{'tags':['" + "a".repeat(9000) + "'] x}");

    assertMalformed(text, "expected , or } in an object at character 9014 of the JSON input");
  }

  static Stream<Arguments> duplicateKeys() {
    return Stream.of(
        Arguments.of("{'tags': [1], 'tags': [2]}", "tags", 21),
        Arguments.of("{'other': {'k': 1, 'k': 2}, 'tags': []}", "k", 23),
        Arguments.of("{'tags': [], 't" + u("0061") + "gs': []}", "tags", 25),
        Arguments.of("[{'tags': [[{'a': 1, 'b': 2, 'a': 3}]]}]", "a", 33));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("duplicateKeys")
  void aKeyTwiceInAnObjectIsMalformed(String text, String key, int at) {
    assertMalformed(
        json(text),
        "duplicate key \"" + key + "\" in an object at character " + at + " of the JSON input");
  }

  @Test
  void aKeyOnceInEachOfTwoObjectsIsNoDuplicate() throws Exception {
    // sibling objects, nested objects and an array between them at one depth reuse no keys
    String text =
        json(
            "{'a': {'x': 1}, 'b': {'x': 2}, 'x': {'x': {'x': 3}},"
                + " 'c': [{'x': 1}, {'x': 2}, [{'x': 1, 'y': 2}]], 'tags': ['ok']}");

    assertThat(read(TAGS, text)).containsExactly(List.of("ok"));
  }

  @Test
  void anInputThatIsNoUtf8IsMalformed() {
    byte[] input = {
      '{', '"', 't', 'a', 'g', 's', '"', ':', '[', '"', (byte) 0xC3, '(', '"', ']', '}'
    };

    assertThatThrownBy(() -> run(document(TAGS), input, new Tracer()))
        .isInstanceOf(MalformedInputException.class)
        .hasMessageStartingWith("the JSON input is no UTF-8: ")
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void aPlanWithoutQueuesStillParsesTheWholeInput() throws Exception {
    TargetDocument empty = document();

    assertThat(run(empty, json("{'a': [1]}").getBytes(UTF_8), new Tracer()))
        .isEqualTo(DECLARATION + "<Out/>");
    assertThatThrownBy(() -> run(empty, json("{'a': [1}").getBytes(UTF_8), new Tracer()))
        .isInstanceOf(MalformedInputException.class);
  }

  /** An input that interrupts the thread reading it at its first read. */
  private static final class InterruptingInput extends InputStream {

    private final ByteArrayInputStream bytes;
    private boolean interrupted;

    InterruptingInput(byte[] content) {
      bytes = new ByteArrayInputStream(content);
    }

    @Override
    public int read() {
      interruptOnce();
      return bytes.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      interruptOnce();
      return bytes.read(buffer, offset, length);
    }

    private void interruptOnce() {
      if (!interrupted) {
        interrupted = true;
        Thread.currentThread().interrupt();
      }
    }

    /** Whether every byte of the input was read. */
    boolean readToTheEnd() {
      return bytes.available() == 0;
    }
  }

  @Test
  void anInterruptWhileTheInputIsParsedStopsTheParseAndWritesNothing() throws Exception {
    // about 2 MB of a member the mapping skips, far more than the parser reads at once
    byte[] input =
        json("{'other': [" + "1, ".repeat(700_000) + "1], 'tags': ['a']}").getBytes(UTF_8);
    // witness: without the interrupt the same run reads it all
    assertThat(run(document(TAGS), input, new Tracer())).contains("<All>a</All>");
    ExecutableMapping mapping = Interpreter.compile(document(TAGS), false);
    InterruptingInput interrupting = new InterruptingInput(input);
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try {
      assertThatThrownBy(() -> mapping.run(interrupting, out, new Tracer(), NOW))
          .isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }
    assertThat(interrupting.readToTheEnd()).as("the input read to its end").isFalse();
    assertThat(out.size()).isZero();
  }
}
