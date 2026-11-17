package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The case and recording format of the differential oracle (ADR-0020). */
class CaseFormatTest {

  private static List<DiffCase> cases(String text) {
    return CaseFormat.parse("t.cases", "t", text.getBytes(UTF_8)).stream().map(b -> DiffCase.of("t.cases", b)).toList();
  }

  @Test
  void aCaseIsItsArgumentsInOrderItsStdinFilesAndEnvironment() {
    DiffCase c =
        cases("# comment\n\ncase t/x\narg -c\nfilter .a\narg in.json\nstdin [1]\\n\nfile in.json {}\nfile empty.json\nenv K=a=b\nend\n").get(0);

    assertThat(c.id()).isEqualTo("t/x");
    assertThat(c.args()).containsExactly("-c", ".a", "in.json");
    assertThat(c.stdin()).isEqualTo("[1]\n".getBytes(UTF_8));
    assertThat(c.files()).containsOnlyKeys("in.json", "empty.json");
    assertThat(c.files().get("in.json")).isEqualTo("{}".getBytes(UTF_8));
    assertThat(c.files().get("empty.json")).isEmpty();
    assertThat(c.env()).isEqualTo(Map.of("K", "a=b"));
  }

  @Test
  void aCaseWithoutStdinHasEmptyInput() {
    assertThat(cases("case t/x\nfilter .\nend\n").get(0).stdin()).isEmpty();
  }

  @Test
  void aCaseNamesTheDeviationItPins() {
    assertThat(cases("case t/x\ndeviation nul\nend\n").get(0).deviation()).isEqualTo("nul");
    assertThat(cases("case t/x\nend\n").get(0).deviation()).isNull();
  }

  /** ADR-0020: a deviation case carries jqj's column as {@code jqj-rc}, {@code jqj-stdout} and {@code jqj-stderr}. */
  @Test
  void aDeviationCaseCarriesJqjsColumnInItsJqjKeys() {
    DiffCase.JqjColumn all = cases("case t/x\njqj-rc 5\ndeviation nul\njqj-stdout\njqj-stderr e\\x00\\n\nend\n").get(0).jqj();

    assertThat(all.rc()).isEqualTo(5);
    assertThat(all.stdout()).isEmpty();
    assertThat(all.stderr()).isEqualTo(new byte[] {'e', 0, '\n'});

    DiffCase.JqjColumn rcOnly = cases("case t/x\ndeviation nul\njqj-rc 5\nend\n").get(0).jqj();
    assertThat(rcOnly.rc()).isEqualTo(5);
    assertThat(rcOnly.stdout()).isNull();
    assertThat(rcOnly.stderr()).isNull();

    DiffCase.JqjColumn none = cases("case t/x\nfilter .\nend\n").get(0).jqj();
    assertThat(none.rc()).isNull();
    assertThat(none.stdout()).isNull();
    assertThat(none.stderr()).isNull();
  }

  /** ADR-0020: the deviation is gone when every part jqj's column gives equals that part of the recording. */
  @Test
  void jqjsColumnMatchesTheRecordingWhenEveryPartItGivesEqualsIt() {
    Outcome recorded = new Outcome(0, bytes("123\n"), bytes("w\n"));

    assertThat(column("jqj-rc 0\n").matches(recorded)).isTrue();
    assertThat(column("jqj-rc 5\n").matches(recorded)).isFalse();
    assertThat(column("jqj-stdout 123\\n\n").matches(recorded)).isTrue();
    assertThat(column("jqj-stdout 123\\n\njqj-rc 5\n").matches(recorded)).isFalse();
    assertThat(column("jqj-stdout\n").matches(recorded)).isFalse();
    assertThat(column("jqj-stderr w\\n\n").matches(recorded)).isTrue();
    assertThat(column("jqj-stderr w\\n\njqj-stdout\n").matches(recorded)).isFalse();
    assertThat(column("jqj-stderr x\n").matches(recorded)).isFalse();
    assertThat(column("").matches(recorded)).as("no jqj-* key: jqj's column is the recording").isTrue();
  }

  private static DiffCase.JqjColumn column(String jqjLines) {
    return cases("case t/x\ndeviation nul\n" + jqjLines + "end\n").get(0).jqj();
  }

  /** Each key of jqj's column replaces jq-ref's part of the recording; an absent key keeps jq-ref's. */
  @Test
  void jqjsColumnReplacesOnlyThePartsItGives() {
    Outcome recorded = new Outcome(0, bytes("123\n"), bytes("w\n"));

    Outcome rc = cases("case t/x\ndeviation nul\njqj-rc 5\nend\n").get(0).jqj().over(recorded);
    assertThat(rc.sameAs(new Outcome(5, bytes("123\n"), bytes("w\n")))).isTrue();

    Outcome streams = cases("case t/x\ndeviation nul\njqj-stdout\njqj-stderr e\nend\n").get(0).jqj().over(recorded);
    assertThat(streams.sameAs(new Outcome(0, bytes(""), bytes("e")))).isTrue();

    assertThat(cases("case t/x\nend\n").get(0).jqj().over(recorded).sameAs(recorded)).isTrue();
  }

  /** ADR-0047 #118: {@code limit recursion-depth n} gives the case its recursion-depth budget; a case without it has none. */
  @Test
  void aCaseCarriesItsRecursionDepthLimit() {
    assertThat(cases("case t/x\nfilter .\nlimit recursion-depth 16\nend\n").get(0).recursionDepth()).isEqualTo(16L);
    assertThat(cases("case t/x\nfilter .\nlimit recursion-depth 1\nend\n").get(0).recursionDepth()).as("the least budget").isEqualTo(1L);
    assertThat(cases("case t/x\nfilter .\nlimit recursion-depth 2147483647\nend\n").get(0).recursionDepth()).as("the largest budget, Limits' largest int").isEqualTo(2147483647L);
    assertThat(cases("case t/x\nfilter .\nend\n").get(0).recursionDepth()).isNull();
  }

  @Test
  void theDefIsTwelveHexOfTheSha256OfTheCaseBlock() {
    // printf 'case t/x\nfilter .\nend\n' | sha256sum
    assertThat(cases("# header\ncase t/x\nfilter .\nend\n").get(0).def()).isEqualTo("98ada9f8a537");
    assertThat(cases("case t/x\nfilter .b\nend\n").get(0).def()).isNotEqualTo("98ada9f8a537");
    assertThat(cases("case t/w\nfilter .w\nend\n\ncase t/x\nfilter .\nend\n").get(1).def()).isEqualTo("98ada9f8a537");
  }

  @Test
  void escapesStandForTheirBytesAndBytesEscapeBackTheSameWay() {
    // a \ LF TAB NUL DEL é, a malformed byte before a plain b, a space, CR
    byte[] bytes = HexFormat.of().parseHex("615c0a09007fc3a9ff62200d");
    String escaped = "a\\\\\\n\\t\\x00\\x7fé\\xffb \\x0d";

    assertThat(CaseFormat.escape(bytes)).isEqualTo(escaped);
    assertThat(CaseFormat.unescape(escaped)).isEqualTo(bytes);
    assertThat(CaseFormat.unescape("\\xFF")).isEqualTo(new byte[] {(byte) 0xff});
  }

  static Stream<Arguments> malformed() {
    return Stream.of(
        Arguments.argumentSet("not UTF-8", new byte[] {'c', (byte) 0xff, '\n'}, "not UTF-8"),
        Arguments.argumentSet("a line outside a block", bytes("filter .\n"), "t.cases:1: expected 'case t/<name>'"),
        Arguments.argumentSet("an id of another family", bytes("case u/x\nend\n"), "not t/<name>"),
        Arguments.argumentSet("an id without a name", bytes("case t/\nend\n"), "not t/<name>"),
        Arguments.argumentSet("an id twice", bytes("case t/x\nend\ncase t/x\nend\n"), "t.cases:3: case t/x occurs twice"),
        Arguments.argumentSet("a block without end", bytes("case t/x\nfilter .\n"), "t.cases:1: case t/x has no 'end'"),
        Arguments.argumentSet("an expected output written by hand", bytes("case t/x\nfilter .\nstdout {}\nend\n"), "t.cases:3: case t/x: unknown key 'stdout'"),
        Arguments.argumentSet("a line starting with a space", bytes("case t/x\n filter .\nend\n"), "unknown key ''"),
        Arguments.argumentSet("a file without a name", bytes("case t/x\nfile  {}\nend\n"), "'file' needs a name"),
        Arguments.argumentSet("stdin twice", bytes("case t/x\nstdin 1\nstdin 2\nend\n"), "'stdin' given twice"),
        Arguments.argumentSet("deviation twice", bytes("case t/x\ndeviation a\ndeviation b\nend\n"), "'deviation' given twice"),
        Arguments.argumentSet("deviation without a slug", bytes("case t/x\ndeviation\nend\n"), "'deviation' needs a slug"),
        Arguments.argumentSet("jqj-rc without deviation", bytes("case t/x\nfilter .\njqj-rc 5\njqj-stdout\nend\n"), "t.cases:3: case t/x: 'jqj-rc' needs 'deviation'"),
        Arguments.argumentSet("jqj-stdout without deviation", bytes("case t/x\njqj-stdout 1\nend\n"), "t.cases:2: case t/x: 'jqj-stdout' needs 'deviation'"),
        Arguments.argumentSet("jqj-stderr without deviation", bytes("case t/x\njqj-stderr e\nend\n"), "t.cases:2: case t/x: 'jqj-stderr' needs 'deviation'"),
        Arguments.argumentSet("jqj-rc twice", bytes("case t/x\ndeviation a\njqj-rc 5\njqj-rc 2\nend\n"), "'jqj-rc' given twice"),
        Arguments.argumentSet("jqj-stdout twice", bytes("case t/x\ndeviation a\njqj-stdout\njqj-stdout\nend\n"), "'jqj-stdout' given twice"),
        Arguments.argumentSet("jqj-stderr twice", bytes("case t/x\ndeviation a\njqj-stderr\njqj-stderr\nend\n"), "'jqj-stderr' given twice"),
        Arguments.argumentSet("jqj-rc not an integer", bytes("case t/x\ndeviation a\njqj-rc five\nend\n"), "'jqj-rc' needs an integer"),
        Arguments.argumentSet("an unknown jqj key", bytes("case t/x\ndeviation a\njqj-exit 5\nend\n"), "unknown key 'jqj-exit'"),
        Arguments.argumentSet("limit twice", bytes("case t/x\nlimit recursion-depth 16\nlimit recursion-depth 8\nend\n"), "t.cases:3: case t/x: 'limit' given twice"),
        Arguments.argumentSet("a limit of another budget", bytes("case t/x\nlimit steps 16\nend\n"), "'limit' needs 'recursion-depth <n>' with an integer n in 1..2147483647, not 'steps 16'"),
        Arguments.argumentSet("a limit that is no integer", bytes("case t/x\nlimit recursion-depth x\nend\n"), "not 'recursion-depth x'"),
        Arguments.argumentSet("a limit of zero", bytes("case t/x\nlimit recursion-depth 0\nend\n"), "not 'recursion-depth 0'"),
        // Limits takes the budget as an int: a larger one is the case's error here, not the runner's at run time.
        Arguments.argumentSet("a limit past the largest int", bytes("case t/x\nlimit recursion-depth 2147483648\nend\n"), "not 'recursion-depth 2147483648'"),
        Arguments.argumentSet("env without =", bytes("case t/x\nenv K\nend\n"), "'env' needs K=V"),
        Arguments.argumentSet("env with an empty name", bytes("case t/x\nenv =v\nend\n"), "'env' needs K=V"),
        Arguments.argumentSet("a lone backslash", bytes("case t/x\nstdin a\\\nend\n"), "lone"),
        Arguments.argumentSet("\\x without two hex digits", bytes("case t/x\nstdin \\x4\nend\n"), "two hex digits"),
        Arguments.argumentSet("\\x with a non-hex digit", bytes("case t/x\nstdin \\x4g\nend\n"), "two hex digits"),
        Arguments.argumentSet("\\x with a non-hex first digit", bytes("case t/x\nstdin \\xg4\nend\n"), "two hex digits"),
        Arguments.argumentSet("an unknown escape", bytes("case t/x\nstdin \\r\nend\n"), "unknown escape '\\r'"));
  }

  private static byte[] bytes(String s) {
    return s.getBytes(UTF_8);
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("malformed")
  void aMalformedCaseFileIsRefusedWithItsLine(byte[] content, String message) {
    assertThatThrownBy(() -> CaseFormat.parse("t.cases", "t", content).forEach(b -> DiffCase.of("t.cases", b)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  static Stream<Arguments> malformedRecordings() {
    return Stream.of(
        Arguments.argumentSet("an unknown key", "case t/x\ndef 0\nrc 0\nstdout\nstderr\nfilter .\nend\n", "unknown key 'filter'"),
        Arguments.argumentSet("a key twice", "case t/x\ndef 0\nrc 0\nrc 1\nstdout\nstderr\nend\n", "'rc' given twice"),
        Arguments.argumentSet("a missing key", "case t/x\ndef 0\nrc 0\nstdout\nend\n", "needs def, rc, stdout and stderr"),
        Arguments.argumentSet("an rc that is not an integer", "case t/x\ndef 0\nrc five\nstdout\nstderr\nend\n", "rc 'five'"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("malformedRecordings")
  void aMalformedRecordingIsRefused(String content, String message) {
    assertThatThrownBy(() -> CaseFormat.parse("t.rec", "t", bytes(content)).forEach(b -> Recording.of("t.rec", b)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  @Test
  void aRecordingReadsBackWhatTheRecorderWrites(@TempDir Path dir) throws Exception {
    Recording r = new Recording("t/x", "98ada9f8a537", new Outcome(5, new byte[0], "e\t\\\n".getBytes(UTF_8)));
    Path file = dir.resolve("t.rec");
    Files.write(file, Recording.file("# jq-1.8.2 sha256 00", List.of(r)));

    assertThat(Files.readString(file, UTF_8))
        .isEqualTo("# jq-1.8.2 sha256 00\n\ncase t/x\ndef 98ada9f8a537\nrc 5\nstdout\nstderr e\\t\\\\\\n\nend\n");
    Recording back = Recording.read(file).get("t/x");
    assertThat(back.def()).isEqualTo("98ada9f8a537");
    assertThat(back.outcome().sameAs(r.outcome())).isTrue();
    assertThat(Recording.read(dir.resolve("absent.rec"))).isEmpty();
  }

  /** ADR-0025: a family is the path relative to {@code diff/}, so {@code diff/gen/values-v1.cases} holds gen ids. */
  @Test
  void aFamilyIsThePathBelowTheNearestDiffDirectory(@TempDir Path dir) throws Exception {
    assertThat(CaseFormat.family(Path.of("oracle/src/test/resources/diff/gen/values-v1.rec"))).isEqualTo("gen/values-v1");
    assertThat(CaseFormat.family(Path.of("/r/diff/a/diff/basics.cases"))).isEqualTo("basics");
    assertThat(CaseFormat.family(Path.of("/r/json/json.cases"))).isEqualTo("json");
    assertThat(CaseFormat.family(Path.of("t.cases"))).isEqualTo("t");
    assertThat(CaseFormat.family(dir, dir.resolve("gen/sub/x.cases"))).isEqualTo("gen/sub/x");

    Path file = dir.resolve("diff/gen/v.rec");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "case gen/v/1\ndef 0\nrc 0\nstdout\nstderr\nend\n");
    assertThat(Recording.read(file)).containsOnlyKeys("gen/v/1");
    assertThat(CaseFormat.read(file, "gen/v")).hasSize(1);
    assertThatThrownBy(() -> CaseFormat.read(file, "v")).hasMessageContaining("is not v/<name>");
  }

  @Test
  void outcomesDifferByExitCodeOrByAnyByteOfEitherStream() {
    Outcome o = new Outcome(0, bytes("a"), bytes("b"));

    assertThat(o.sameAs(new Outcome(0, bytes("a"), bytes("b")))).isTrue();
    assertThat(o.sameAs(new Outcome(1, bytes("a"), bytes("b")))).isFalse();
    assertThat(o.sameAs(new Outcome(0, bytes("x"), bytes("b")))).isFalse();
    assertThat(o.sameAs(new Outcome(0, bytes("a"), bytes("x")))).isFalse();
  }

  @Test
  void aCaseDirectoryIsDeletedWhenAFileCannotBeWritten(@TempDir Path dir) throws Exception {
    assertThatThrownBy(() -> CaseDirectory.of(Map.of("missing/sub.json", new byte[0]), dir)).isInstanceOf(java.io.UncheckedIOException.class);

    try (Stream<Path> left = Files.list(dir)) {
      assertThat(left).isEmpty();
    }
  }
}
