package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.MARK;
import static io.github.subapavel.jqj.Inlining.outcome;
import static io.github.subapavel.jqj.Inlining.results;
import static io.github.subapavel.jqj.Inlining.shown;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.value.JqNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-0040 "IR depth" (F-44): a call whose expansion would pass {@code Resolver.MAX_IR_DEPTH} = 512 levels of built IR,
 * or would be the 512th nested expansion, is a PLAIN Call of its lifted definition, so the filter runs (ADR-0040
 * "Amendment (ticket #114)"); any other construct past the bound, such as a chain of computed keys, makes the filter a
 * Stub. A level is one node of the IR on the way from its root to a leaf, the leaf counted: {@code
 * [[.]]} has three. The depth is the built IR's wherever the Resolver builds it (ADR-0040 "What counts"): nested
 * Collects through definitions, each of {@link #PER} brackets around the previous one's call, while the parser's 512
 * nesting levels (DEVIATIONS "Parse depth") bound each body alone; the Bind of each value parameter around the body of
 * nested expansions; the Bind of each computed-key step around the chain before it; and the levels of a wide
 * construct, such as a chain of operators. jq-ref 1.8.2 compiles every filter here (DEVIATIONS "IR depth").
 *
 * <p>#70's probe at f7670a7, one fresh JVM per depth and no bound: such Collects compiled and ran on a cold 512 KiB
 * thread at 625 levels and overflowed at 650; warmed first, at 4 096 and not at 8 192. A warm or instrumented JVM can
 * need more stack than 512 KiB for 512 levels (F-44, measured at 4e9f444 and 025adcf): under JaCoCo a warmed JVM ran
 * out of it about 500 levels down. So the 512 KiB edges are pinned in fresh child JVMs with no agent, the case a host
 * meets: one with the default JIT, one with C1 alone and one stopped at tier 3; this JVM pins the bound's logic on a 16 MiB thread.
 */
class IrDepthTest {

  /** ADR-0017's default stack for a run. */
  private static final long STACK = 512 * 1024;

  /** A stack the Resolver's 512 levels fit in whatever this JVM's state and instrumentation. */
  private static final long LARGE = 16L * 1024 * 1024;

  /** The brackets of each definition: fewer than the parser's 127 (DEVIATIONS "Parse depth"). */
  private static final int PER = 100;

  /** jq-ref 1.8.2's result for {@code nested(512)} on null (observed 2026-11-13, -nc -f): 511 nested arrays. */
  private static final String NESTED_512 = "[".repeat(511) + "null" + "]".repeat(511);

  /** 300 indexes by a computed key, each bound around the chain before it: past the bound alone. */
  private static final String DEEP = "." + "[.a]".repeat(300);

  /**
   * jq-ref 1.8.2's result for {@code nested(513)} on null (observed 2026-11-15, -nc -f, .logs/114-jqref-probe.log, 1 029
   * bytes): 512 nested arrays, as DEVIATIONS "IR depth" gave it.
   */
  private static final String NESTED_513 = "[".repeat(512) + "null" + "]".repeat(512);

  /**
   * jq-ref 1.8.2's result for {@code nested(30_001)} on null (observed 2026-11-15, -nc -f, .logs/114-jqref-probe.log):
   * 20 022 bytes, 10 001 brackets, {@code <skipped: too deep>}, 10 001 brackets and an LF, sha256 7abcf37e…, the bytes
   * of DeepBuiltArrayTest's oracle; {@code JqValue.toJson} is that text without its LF (F-54, diff/over-cap.rec {@code
   * over-cap/depth-30001}).
   */
  private static final String NESTED_30001 = "[".repeat(10_001) + "<skipped: too deep>" + "]".repeat(10_001);

  /** jq-ref 1.8.2's result for {@code valueParameters(500)} on null (observed 2026-11-15, -nc -f, 1 005 bytes). */
  private static final String VALUE_PARAMETERS_500 = "[".repeat(500) + "null" + "]".repeat(500);

  /**
   * What {@link Child} prints for its edges, in its order. The three call rows were a Stub until #114, when a call
   * whose expansion would pass the bound became a PLAIN Call (ADR-0040 #114 amendment, F-54); the computed indexes,
   * a chain and no call, stay the bound's Stub.
   */
  private static final String EDGES =
      "512 levels: " + NESTED_512 + "\n" + "513 levels: ran\n" + "30001 levels: ran\n" + "500 value parameters: ran\n" + "10000 computed indexes: a Stub\n";

  @TempDir Path dir;

  /**
   * One child JVM, started with the test class path, no agent and no tool or JVM options, runs {@link Child}: the
   * stack claim of every test here.
   */
  @Test
  void onA512KiBThreadInAFreshJvmDeepCallsRunAndComputedIndexesStayAStub() throws IOException, InterruptedException {
    assertChildPrintsTheEdges();
  }

  /**
   * The same child JVM with C1 only and compiles in the foreground, the state of a loaded machine whose C2 compiles
   * lag (#86): the 512 levels the child runs first get the Resolver C1-compiled, and the descent of 513 levels ran out
   * of 512 KiB about 500 levels down, before the bound's Stub (#86, 5 of 5 runs before the lowering's frames were made small).
   */
  @Test
  void onA512KiBThreadInAFreshC1OnlyJvmDeepCallsRunAndComputedIndexesStayAStub() throws IOException, InterruptedException {
    assertChildPrintsTheEdges("-Xbatch", "-XX:TieredStopAtLevel=1");
  }

  /**
   * The same child JVM stopped at tier 3, C1 with full profiling, the code a loaded machine runs while C2 compiles
   * lag: its frames are as large as tier 1's (#86, measured: 512 levels fit 472 KiB and 500 value parameters 488 KiB
   * under either tier).
   */
  @Test
  void onA512KiBThreadInAFreshProfilingC1JvmDeepCallsRunAndComputedIndexesStayAStub() throws IOException, InterruptedException {
    assertChildPrintsTheEdges("-Xbatch", "-XX:TieredStopAtLevel=3");
  }

  /** Runs {@link Child} in a fresh JVM with {@code flags} and asserts it prints {@link #EDGES}. */
  private void assertChildPrintsTheEdges(String... flags) throws IOException, InterruptedException {
    assertThat(runChild(Child.class, List.of(flags))).as("what the child JVM printed").isEqualTo(EDGES);
  }

  /**
   * What {@code main} printed, run in a fresh JVM with {@code flags} and {@code args}, once it ended within 120 s with
   * exit code 0.
   */
  private String runChild(Class<?> main, List<String> flags, String... args) throws IOException, InterruptedException {
    File out = dir.resolve(main.getSimpleName() + ".out").toFile();
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(flags);
    command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
    command.addAll(List.of(args));
    ProcessBuilder launch = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out);
    // Each would reach the child's command line, and an agent there would make it the instrumented case.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    Process child = launch.start();
    try {
      assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("the child JVM ends within 120 s").isTrue();
      String printed = Files.readString(out.toPath(), UTF_8);
      assertThat(child.exitValue()).as("the child JVM's exit code; it printed %s", printed).isZero();
      return printed;
    } finally {
      child.destroyForcibly();
    }
  }

  /**
   * 512 levels are not past the bound: jq-ref 1.8.2 (observed 2026-11-13, the filter read with -f) prints the 511
   * nested arrays around null. Their 512 KiB stack claim is pinned by the child JVM's test.
   */
  @Test
  void aFilterOf512LevelsRuns() throws InterruptedException {
    assertThat(resultsOnA16MiBThread(nested(512))).as("results of 512 levels on null").isEqualTo(List.of(NESTED_512));
    AtomicReference<List<String>> lifted = new AtomicReference<>();
    Throwable thrown = on(LARGE, () -> lifted.set(Inlining.lifted(nested(512))));
    assertThat(thrown).as("what compiling 512 levels threw on a 16 MiB thread").isNull();
    assertThat(lifted.get()).as("lifted functions of 512 levels: every call fits the bound").isEmpty();
  }

  /**
   * One level past the bound: the call whose expansion would pass it is a PLAIN Call (ADR-0040 #114 amendment), so the
   * filter runs and gives {@link #NESTED_513}; it was the bound's Stub until #114. Its 512 KiB stack claim is pinned by
   * the child JVM's test.
   */
  @Test
  void aFilterOf513LevelsRuns() throws InterruptedException {
    assertThat(resultsOnA16MiBThread(nested(513))).as("results of 513 levels on null").isEqualTo(List.of(NESTED_513));
  }

  /**
   * 300 definitions of 100 brackets: each call past the bound is a PLAIN Call to a function whose depth restarts at 0,
   * so the filter runs and gives {@link #NESTED_30001}; it was the bound's Stub until #114. Its 512 KiB stack claim is
   * pinned by the child JVM's test.
   */
  @Test
  void aFilterOf30001LevelsRuns() throws InterruptedException {
    assertThat(resultsOnA16MiBThread(nested(30_001))).as("results of 30001 levels on null").isEqualTo(List.of(NESTED_30001));
  }

  /**
   * 500 nested expansions of one value parameter each around 501 levels: each Bind of {@code $a} is a level of its own
   * (F-44, the #70 review at b0b5095), so the expansion that would pass the bound is a PLAIN Call, and the filter runs
   * and gives {@link #VALUE_PARAMETERS_500}; it was the bound's Stub until #114. Its 512 KiB stack claim is pinned by
   * the child JVM's test.
   */
  @Test
  void nestedValueParameterExpansionsRun() throws InterruptedException {
    assertThat(resultsOnA16MiBThread(valueParameters(500))).as("results of 500 nested value parameters on null").isEqualTo(List.of(VALUE_PARAMETERS_500));
  }

  /** Each {@code [.a:]} binds its bound around the chain before it (F-44, the #70 review at b0b5095). */
  @Test
  void aChainOf2000ComputedSlicesIsAStub() throws InterruptedException {
    assertStubOnA16MiBThread("." + "[.a:]".repeat(2_000));
  }

  /** Each {@code [.a]} binds its key around the chain before it (F-44, the #70 review at b0b5095). */
  @Test
  void aChainOf10000ComputedIndexesIsAStub() throws InterruptedException {
    assertStubOnA16MiBThread("." + "[.a]".repeat(10_000));
  }

  /**
   * #94 (ADR-0043): a run of one left-associative operator past {@code BIND_RUN} is one Binds, so 256 terms of {@code
   * .a+}, 512 levels as nested Binds, are a few levels, and in brackets, 513 levels as nested Binds and a Stub until
   * #94, they run too. jq-ref 1.8.2 (observed 2026-11-13, the filter read with -nc -f, .logs/94-jqref-pins.log of the
   * test-author's run): null, and [null] in brackets. The Stub this test pinned for the brackets ended with #94.
   */
  @Test
  void anOperatorChainOf513LevelsRuns() throws InterruptedException {
    String chain = terms(256, "+", ".a");
    assertThat(resultsOnA16MiBThread(chain)).as("results of 256 terms of .a+ on null").isEqualTo(List.of("null"));
    assertThat(resultsOnA16MiBThread("[" + chain + "]")).as("results of 256 terms of .a+ in brackets on null").isEqualTo(List.of("[null]"));
  }

  /**
   * A value parameter's level ends with its expansion: 600 calls of {@code def f($a): .} side by side each bind
   * {@code $a} once, so the lowering is inside one such level at a time and the filter runs. jq-ref 1.8.2 (observed
   * 2026-11-13, the filter read with -f) prints the array of 600 nulls.
   */
  @Test
  void callsOfAValueParameterDefinitionSideBySideRun() {
    String filter = "def f($a): .; [" + terms(600, ", ", "f(1)") + "]";
    assertThat(results(filter)).as("results of 600 calls of f($a) on null").isEqualTo(List.of("[" + terms(600, ",", "null") + "]"));
  }

  /**
   * The bound counts every part of a node that IrVerifier visits: {@link #DEEP}, a chain past the bound, placed in
   * one part of one node kind, is a Stub, whichever the part.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("deepParts")
  void aChainPastTheBoundInAnyPartOfANodeIsAStub(String part, String filter) throws InterruptedException {
    assertStubOnA16MiBThread(filter);
  }

  static Stream<Arguments> deepParts() {
    return Stream.of(
        Arguments.of("a Pipe's left", DEEP + " | .a"),
        Arguments.of("a Pipe's right", ".a | " + DEEP),
        Arguments.of("a Comma's left", DEEP + ", 1"),
        Arguments.of("a Comma's right", "1, " + DEEP),
        Arguments.of("a Collect's body", "[" + DEEP + "]"),
        Arguments.of("an If's condition", "if " + DEEP + " then . else . end"),
        Arguments.of("an If's then", "if . then " + DEEP + " else . end"),
        Arguments.of("an If's otherwise", "if . then . else " + DEEP + " end"),
        Arguments.of("an Alt's left", DEEP + " // 1"),
        Arguments.of("an Alt's right", "1 // " + DEEP),
        Arguments.of("a Bind's value", DEEP + " as $v | ."),
        Arguments.of("a Bind's body", ". as $v | " + DEEP),
        Arguments.of("an AltBind's value", DEEP + " as [$a] ?// $a | ."),
        Arguments.of("an AltBind's match", ". as [$a] ?// {(" + DEEP + "): $a} | ."),
        Arguments.of("an AltBind's body", ". as [$a] ?// $a | " + DEEP),
        Arguments.of("a Try's body", "try " + DEEP),
        Arguments.of("a Try's handler", "try . catch " + DEEP),
        Arguments.of("a Label's body", "label $l | " + DEEP),
        Arguments.of("a Reduce's init", "reduce . as $x (" + DEEP + "; .)"),
        Arguments.of("a Reduce's each", "reduce " + DEEP + " as $x (0; .)"),
        Arguments.of("an Advance's update in a Reduce", "reduce . as $x (0; " + DEEP + ")"),
        Arguments.of("a Foreach's init", "foreach . as $x (" + DEEP + "; .)"),
        Arguments.of("a Foreach's each", "foreach " + DEEP + " as $x (0; .)"),
        Arguments.of("an Advance's update in a Foreach", "foreach . as $x (0; " + DEEP + ")"));
  }

  /**
   * A construct whose IR is as deep as it is wide, past the bound (F-44, the #70 review at b0b5095), as DEVIATIONS "IR
   * depth" names it. Objects, interpolations, patterns and value parameters left this list at #93 (ADR-0043) for
   * {@link #aWideConstructOf600PartsRunsAsJqRefRunsIt}, and the runs of one operator, {@code and}, {@code or} and
   * {@code //} at #94 for {@link #aWideOperatorRunRuns}, at the same widths: their Stub ended, no row was dropped. What
   * stays is a chain that alternates operators, a run of one operator each, nested, two levels an operator (ADR-0043
   * "one operator"); jq-ref 1.8.2 compiles it and raises
   * {@code null (null) and null (null) cannot be subtracted}, rc 5 (observed 2026-11-14, -nc -f, .logs/94-jqref-alt.log).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wideConstructs")
  void aWideConstructPastTheBoundIsAStub(String construct, String filter) throws InterruptedException {
    assertStubOnA16MiBThread(filter);
  }

  static Stream<Arguments> wideConstructs() {
    return Stream.of(
        Arguments.of("400 terms alternating .a+ and .a-", alternating(400)));
  }

  /** {@code .a+.a-.a+…}, {@code n} terms, the operators alternating, so each run of one operator is one operator long. */
  private static String alternating(int n) {
    StringBuilder chain = new StringBuilder(".a");
    for (int i = 1; i < n; i++) {
      chain.append(i % 2 == 1 ? "+" : "-").append(".a");
    }
    return chain.toString();
  }

  /**
   * ADR-0043 (#93): past {@code BIND_RUN} nested Binds an object, an interpolation, a pattern and a value parameter's
   * expansion lower to one Binds, a level whatever its width, so the rows that were {@link
   * #aWideConstructPastTheBoundIsAStub}'s compile and run, on null, at the same widths. An object's computed value was a
   * level and its computed key two; an interpolation, a pattern's variable and a value parameter a level each. Each
   * expected value is jq-ref 1.8.2's (observed 2026-11-13 with -nc -f, .logs/93-jqref-limits.log of the test-author's
   * run): the 600 keys each with null, the 300 computed keys one key k with 1, 600 times null in one string, and 1.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wideConstructsThatRun")
  void aWideConstructOf600PartsRunsAsJqRefRunsIt(String construct, String filter, String expected) throws InterruptedException {
    assertThat(resultsOnA16MiBThread(filter)).as("results of %s on null", construct).containsExactly(expected);
  }

  static Stream<Arguments> wideConstructsThatRun() {
    String nulls = "{" + terms(600, ",", "\"k%d\":null") + "}";
    return Stream.of(
        Arguments.of("600 computed values", "{" + terms(600, ", ", "k%d: .a") + "}", nulls),
        Arguments.of("600 keys without values", "{" + terms(600, ", ", "k%d") + "}", nulls),
        Arguments.of("300 computed keys", "{\"a\": \"k\"} | {" + terms(300, ", ", "(.a): 1") + "}", "{\"k\":1}"),
        Arguments.of("600 interpolations", "\"" + "\\(.a)".repeat(600) + "\"", "\"" + "null".repeat(600) + "\""),
        Arguments.of("an array pattern of 600 variables", ". as [" + terms(600, ", ", "$a%d") + "] | 1", "1"),
        Arguments.of("an object pattern of 600 keys", ". as {" + terms(600, ", ", "k%d: $a%d") + "} | 1", "1"),
        Arguments.of("an object pattern of 600 variables", ". as {" + terms(600, ", ", "$a%d") + "} | 1", "1"),
        Arguments.of("600 value parameters", "def f(" + terms(600, "; ", "$a%d") + "): 1; f(" + terms(600, "; ", "1") + ")", "1"));
  }

  /**
   * #94 (ADR-0043): the operator, {@code and} and {@code or} rows of DEVIATIONS "IR depth", at the same widths, compile
   * and run, as do 600 terms of {@code //}, which nest to the right and so were bounded by the parse depth. Each was a
   * Stub of {@link #aWideConstructPastTheBoundIsAStub} until #94 (the {@code //} row a compile error), an entry that
   * ended with its Stub. jq-ref 1.8.2 (observed 2026-11-13, the filter read with -nc -f, .logs/94-jqref-limits.log and
   * 94-jqref-pins.log of the test-author's run) gives each result, and for {@code .a*} rc 5 with this error.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wideOperatorRuns")
  void aWideOperatorRunRuns(String construct, String filter, String outcome) throws InterruptedException {
    assertThat(outcomeOnA16MiBThread(filter)).as("outcome of %s on null", construct).isEqualTo(List.of(outcome));
  }

  static Stream<Arguments> wideOperatorRuns() {
    return Stream.of(
        Arguments.of("257 terms of .a+", terms(257, "+", ".a"), "null"),
        Arguments.of("257 terms of .a*", terms(257, "*", ".a"), "error \"null (null) and null (null) cannot be multiplied\""),
        Arguments.of("600 terms of $x+", "1 as $x | " + terms(600, "+", "$x"), "600"),
        Arguments.of("600 terms of and", terms(600, " and ", ".a"), "false"),
        Arguments.of("600 terms of or", terms(600, " or ", ".a"), "false"),
        Arguments.of("600 terms of //", terms(600, " // ", ".a"), "null"));
  }

  /**
   * ADR-0043 (#93, #94, F-45): each wide construct, as wide as jq-ref 1.8.2 compiles it, below 20 000 parts for every
   * one, compiles and runs on null on a 512 KiB thread in a fresh JVM with no agent, at each of the three tiers of
   * {@link #assertChildPrintsTheEdges} (F-49), and gives jq-ref's outcome, its results or {@code error <value>}. Until
   * #93 and #94 each was a Stub past the IR bound, the {@code //} rows a compile error of the parse depth.
   *
   * <p>#93's constructs, jq-ref's limits observed 2026-11-13 with -nc -f on {@code /usr/local/bin/jq-ref}
   * (.logs/93-jqref-limits.log and 93-jqref-alt.log of the test-author's run): one part more is rc 3, "memory exhausted"
   * for 4998 computed values, 4999 keys without values and 4996 computed keys; "function compiled to 65537 bytes which
   * is too long" for 4369 interpolations, 65542 bytes for each pattern of 9362, 65554 for two alternatives of 3277
   * variables each; and "too many function parameters or local function definitions (max 4095)" for 4096 value
   * parameters. At the limit jq-ref gives the keys each with null, one key k with 1, 4368 times null in one string, and
   * 1 for every pattern and for the value parameters.
   *
   * <p>#94's operator runs, each at jq-ref's own limit, observed 2026-11-13 with -nc -f (.logs/94-jqref-edges.log of the
   * test-author's run): one term more is jq-ref's bytecode limit, {@code function compiled to … bytes which is too
   * long}, rc 3, for every row; 20 000 terms of {@code .a //} are its {@code memory exhausted}, rc 3. A comparison does
   * not chain in jq's grammar ({@code 1 == 1 == true} is jq-ref's syntax error, rc 3), so the comparison row is an
   * {@code and} run of comparisons.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("tiers")
  void aWideConstructOf20000PartsRunsOnA512KiBThreadInAFreshJvm(String tier, List<String> flags) throws IOException, InterruptedException {
    Path results = Files.createDirectories(dir.resolve("results"));
    List<String> printed = new ArrayList<>();
    List<Arguments> rows = widest().toList();
    for (Arguments row : rows) {
      printed.add(row.get()[0] + ": ran");
    }
    assertThat(runChild(WideChild.class, flags, results.toString()))
        .as("what the child JVM (%s) printed, a line per construct", tier)
        .isEqualTo(String.join("\n", printed) + "\n");
    for (int i = 0; i < rows.size(); i++) {
      Object[] row = rows.get(i).get();
      assertThat(Files.readString(results.resolve(i + ".out"), UTF_8)).as("outcome of %s on null in the child JVM (%s)", row[0], tier).isEqualTo(row[2]);
    }
  }

  static Stream<Arguments> tiers() {
    return Stream.of(
        Arguments.of("the default JIT", List.of()),
        Arguments.of("C1 only", List.of("-Xbatch", "-XX:TieredStopAtLevel=1")),
        Arguments.of("stopped at tier 3", List.of("-Xbatch", "-XX:TieredStopAtLevel=3")));
  }

  /** The rows of {@link #aWideConstructOf20000PartsRunsOnA512KiBThreadInAFreshJvm}: name, filter, jq-ref's outcome. */
  static Stream<Arguments> widest() {
    return Stream.of(
        Arguments.of("4997 computed values", "{" + terms(4997, ", ", "k%d: .a") + "}", "{" + terms(4997, ",", "\"k%d\":null") + "}"),
        Arguments.of("4998 keys without values", "{" + terms(4998, ", ", "k%d") + "}", "{" + terms(4998, ",", "\"k%d\":null") + "}"),
        Arguments.of("4995 computed keys", "{\"a\": \"k\"} | {" + terms(4995, ", ", "(.a): 1") + "}", "{\"k\":1}"),
        Arguments.of("4368 interpolations", "\"" + "\\(.a)".repeat(4368) + "\"", "\"" + "null".repeat(4368) + "\""),
        Arguments.of("an array pattern of 9361 variables", ". as [" + terms(9361, ", ", "$a%d") + "] | 1", "1"),
        Arguments.of("an object pattern of 9361 keys", ". as {" + terms(9361, ", ", "k%d: $a%d") + "} | 1", "1"),
        Arguments.of("an object pattern of 9361 variables", ". as {" + terms(9361, ", ", "$a%d") + "} | 1", "1"),
        Arguments.of("two alternatives of 3276 variables", ". as [" + terms(3276, ", ", "$a%d") + "] ?// [" + terms(3276, ", ", "$a%d") + "] | 1", "1"),
        Arguments.of("4095 value parameters", "def f(" + terms(4095, "; ", "$a%d") + "): 1; f(" + terms(4095, "; ", "1") + ")", "1"),
        Arguments.of("6553 terms of .a+ on {\"a\":1}", "{\"a\":1} | " + terms(6_553, "+", ".a"), "6553"),
        Arguments.of("6553 terms of .a- on {\"a\":1}", "{\"a\":1} | " + terms(6_553, "-", ".a"), "-6551"),
        Arguments.of("6553 terms of .a* on {\"a\":1}", "{\"a\":1} | " + terms(6_553, "*", ".a"), "1"),
        Arguments.of("6554 terms of .a* on null", terms(6_554, "*", ".a"), "error \"null (null) and null (null) cannot be multiplied\""),
        Arguments.of("6553 terms of $x+", "1 as $x | " + terms(6_553, "+", "$x"), "6553"),
        Arguments.of("3277 terms of .a and on {\"a\":true}", "{\"a\":true} | " + terms(3_277, " and ", ".a"), "true"),
        Arguments.of("3277 terms of .a or on {\"a\":false}", "{\"a\":false} | " + terms(3_277, " or ", ".a"), "false"),
        Arguments.of("2185 terms of .a == .a and on null", terms(2_185, " and ", ".a == .a"), "true"),
        Arguments.of("2185 terms of .a // on null", terms(2_185, " // ", ".a"), "null"),
        Arguments.of("2185 terms of .a // on {\"a\":false}", "{\"a\":false} | " + terms(2_185, " // ", ".a"), "false"));
  }

  /**
   * The child JVM of {@link #aWideConstructOf20000PartsRunsOnA512KiBThreadInAFreshJvm}: on a 512 KiB thread each, it
   * compiles and runs each of {@link #widest} on null, prints {@code <name>: ran}, {@code <name>: a Stub} or what was
   * thrown, and writes its {@link Inlining#outcome}, one result per line, to {@code <i>.out} in the directory {@code args[0]},
   * i the row's number from 0.
   */
  static final class WideChild {
    private WideChild() {}

    public static void main(String[] args) throws InterruptedException, IOException {
      List<Arguments> rows = widest().toList();
      for (int i = 0; i < rows.size(); i++) {
        Object[] row = rows.get(i).get();
        String filter = (String) row[1];
        AtomicReference<List<String>> got = new AtomicReference<>();
        Throwable thrown = on(STACK, () -> got.set(outcome(filter)));
        boolean isStub = thrown instanceof JqCompileException && thrown.getMessage().startsWith(MARK);
        System.out.println(row[0] + ": " + (thrown == null ? "ran" : isStub ? "a Stub" : thrown));
        Files.writeString(Path.of(args[0], i + ".out"), thrown == null ? String.join("\n", got.get()) : "", UTF_8);
      }
    }
  }

  /** {@link Inlining#outcome} of {@code filter} on a 16 MiB thread; what else the run threw fails the test with it as the cause. */
  private static List<String> outcomeOnA16MiBThread(String filter) throws InterruptedException {
    AtomicReference<List<String>> got = new AtomicReference<>();
    Throwable thrown = on(LARGE, () -> got.set(outcome(filter)));
    if (thrown != null) {
      throw new AssertionError("running " + shown(filter) + " on a 16 MiB thread threw " + thrown, thrown);
    }
    return got.get();
  }

  /** {@code n} copies of {@code term}, each {@code %d} in it the copy's number from 0, joined by {@code separator}. */
  private static String terms(int n, String separator, String term) {
    StringJoiner joined = new StringJoiner(separator);
    for (int i = 0; i < n; i++) {
      joined.add(term.replace("%d", Integer.toString(i)));
    }
    return joined.toString();
  }

  /**
   * The child JVM of {@link #onA512KiBThreadInAFreshJvmDeepCallsRunAndComputedIndexesStayAStub}: on a 512 KiB thread
   * each, it runs 512 levels on null, then compiles and runs 513 levels, 30 001 levels, 500 value parameters and 10 000
   * computed indexes, in that order, and prints a line for each: {@code ran}, {@code a Stub} or what was thrown. Since
   * #114 the first three lift their calls past the bound; only the walk of the built IR stops the last, a chain.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      AtomicReference<List<String>> got = new AtomicReference<>();
      Throwable thrown = on(STACK, () -> got.set(results(nested(512))));
      System.out.println("512 levels: " + (thrown == null ? String.join(", ", got.get()) : thrown));
      run("513 levels", nested(513));
      run("30001 levels", nested(30_001));
      run("500 value parameters", valueParameters(500));
      run("10000 computed indexes", "." + "[.a]".repeat(10_000));
    }

    private static void run(String name, String filter) throws InterruptedException {
      Throwable thrown = on(STACK, () -> results(filter));
      boolean isStub = thrown instanceof JqCompileException && thrown.getMessage().startsWith(MARK);
      System.out.println(name + ": " + (isStub ? "a Stub" : thrown == null ? "ran" : thrown));
    }
  }

  /** The definitions of {@link #aDoublingChainOf4000DefinitionsCompilesAndEndsAtTheStepBudgetInAFreshJvm}. */
  private static final int DOUBLING = 4_000;

  /**
   * The bound on each compile of the 4 000-definition doubling chain in {@link DoublingChild}, in ms: about 10× the
   * time the red measured (the design of #114), when the compile of the same filter in the same child JVM ran to the
   * inline cap's Stub: {@value} ms against 183 ms (optimised, the first compile in the JVM) and 38 ms (conservative)
   * measured by the red of #114 (.logs/114-red.log of the test-author's run).
   */
  private static final long COMPILE_BOUND_MS = 2_000;

  /**
   * Q4 of #114: {@code def f0: .; def f1: f0,f0; …; def f4000: f3999,f3999; f4000} would lower 2^4001 - 1 nodes inlined.
   * In a fresh JVM with no agent, on a 512 KiB thread, it compiles in the optimised and the conservative mode, each
   * within {@link #COMPILE_BOUND_MS}, since its calls past the cap are PLAIN and no call is expanded twice (ADR-0040 #114
   * amendment), and then its run under {@link Limits#embedded()}'s budgets without its deadline ends with {@code
   * JqTermination(BUDGET, STEPS)}: each PLAIN call ticks one step (F-54). The run has no deadline because the 10⁹ steps
   * take longer than the embedded 10 s, so that deadline ended the run first (observed by the #114 implementer after
   * #111). The compile times are printed into this test's output. Until #114 each compile was the cap's Stub.
   */
  @Test
  void aDoublingChainOf4000DefinitionsCompilesAndEndsAtTheStepBudgetInAFreshJvm() throws IOException, InterruptedException {
    Path times = dir.resolve("times");
    String printed = runChild(DoublingChild.class, List.of(), times.toString());
    String measured = Files.exists(times) ? Files.readString(times, UTF_8) : "";
    System.out.println("IrDepthTest: compile of the " + DOUBLING + "-definition doubling chain, ms: " + measured.strip());
    assertThat(printed).as("what the child JVM printed").isEqualTo("optimised: compiled\nconservative: compiled\nembedded run: STEPS\n");
    for (String line : measured.strip().split("\n")) {
      String[] parts = line.split(" ");
      assertThat(Long.parseLong(parts[1])).as("ms of the %s compile of the doubling chain", parts[0]).isLessThanOrEqualTo(COMPILE_BOUND_MS);
    }
  }

  /**
   * The child JVM of {@link #aDoublingChainOf4000DefinitionsCompilesAndEndsAtTheStepBudgetInAFreshJvm}: on a 512 KiB
   * thread each, it compiles the chain with an engine of {@link Limits#embedded()} in each mode, prints {@code <mode>:
   * compiled}, {@code a Stub} or what was thrown, and writes {@code <mode> <ms>} lines to the file {@code args[0]}; then it
   * compiles it once more with {@link Limits#embedded()}'s budgets and no deadline, runs that filter on null and prints
   * {@code embedded run: <budget>}, the budget that ended it, or what else happened.
   */
  static final class DoublingChild {
    private DoublingChild() {}

    public static void main(String[] args) throws InterruptedException, IOException {
      String filter = doublingChain(DOUBLING);
      StringBuilder times = new StringBuilder();
      AtomicReference<JqFilter> optimised = new AtomicReference<>();
      for (CompileMode mode : List.of(CompileMode.OPTIMISED, CompileMode.CONSERVATIVE)) {
        JqEngine engine = JqEngine.builder().limits(Limits.embedded()).compileMode(mode).build();
        AtomicReference<JqFilter> compiled = new AtomicReference<>();
        long start = System.nanoTime();
        Throwable thrown = on(STACK, () -> compiled.set(engine.compile(filter)));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String name = mode == CompileMode.OPTIMISED ? "optimised" : "conservative";
        times.append(name).append(' ').append(ms).append('\n');
        boolean isStub = thrown instanceof JqCompileException && thrown.getMessage().startsWith(MARK);
        System.out.println(name + ": " + (isStub ? "a Stub" : thrown == null ? "compiled" : thrown));
        if (mode == CompileMode.OPTIMISED) {
          optimised.set(compiled.get());
        }
      }
      Files.writeString(Path.of(args[0]), times.toString(), UTF_8);
      if (optimised.get() == null) {
        System.out.println("embedded run: not compiled");
        return;
      }
      Limits e = Limits.embedded();
      Limits noDeadline = new Limits(e.steps(), Limits.UNLIMITED_TIME, e.nestingDepth(), e.recursionDepth(), e.bytesBuilt(), e.bytesWritten(), e.regexMatchTime(), e.stackBytes());
      JqEngine engine = JqEngine.builder().limits(noDeadline).compileMode(CompileMode.OPTIMISED).build();
      AtomicReference<Boolean> answer = new AtomicReference<>();
      Throwable thrown = on(STACK, () -> answer.set(engine.compile(filter).run(JqNull.NULL, RunBudgets.NONE, v -> true, engine.newWorkspace())));
      System.out.println("embedded run: " + (thrown instanceof JqTermination t ? String.valueOf(t.budget()) : thrown == null ? "ended, " + answer.get() : thrown));
    }
  }

  /**
   * The depth edge of each kind the expansion measure counts (ADR-0040 "Amendment (ticket #114)", the #114 Standards
   * review): {@link #atDepth} puts {@code def f: <body>} under four definitions of 100 brackets each and {@code j}
   * brackets of the main filter, so a call of p4 there would reach {@code j + 400 + reach} levels, reach being f's
   * measured levels. At 512 every call is inlined; at 513 p4's would pass {@code MAX_IR_DEPTH}, so it is a PLAIN Call of
   * the lifted p4, not the IR depth Stub, and the filter gives the same result one bracket deeper. The measured levels
   * are the measure's arms: a leaf, a variable, {@code $__loc__} or a format one; a native call without arguments two,
   * with them five or its arguments three down; a negation three, its operand one down; an if, a try or a label three,
   * their parts two down; an interpolated string {@code min(parts, 64) + 3}, its interpolations one less down; a Bind,
   * reduce or foreach {@code min(2·patterns + 2, 64)} plus 5, 8 or 8, their parts one less down; an object {@code
   * 3·min(pairs, 64) + 4}, its keys and values two less down; a chain of steps {@code 3·steps + 3}, its keys and bounds
   * two less down; {@code ..} its prelude call's seven. A wrong count moves the edge: one level more lifts p4 at 512,
   * one less inlines it at 513 and the IR depth Stub fires. Each result is reference jq's: 1.8.2 gives each body on null
   * ({@code def f: <body>; [f]}, -nc -f, observed 2026-11-15, .logs/114-jqref-arms.log), and the whole filter for
   * {@code -length}, {@code .[]?}, {@code {$__loc__}} and {@code . as $x | {$x}} at 513 levels, those brackets around
   * it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("measuredLevels")
  void eachKindMeetsTheIrDepthAtItsMeasuredLevels(String body, int reach, String result) throws InterruptedException {
    int j = 512 - 400 - reach;
    String inner = result == null ? "" : result;
    String fits = atDepth(body, j);
    assertThat(ownFunctionsOnA16MiBThread(fits)).as("lifted functions of %s at 512 measured levels", body).isEmpty();
    assertThat(resultsOnA16MiBThread(fits)).as("results of %s at 512 measured levels", body).containsExactly("[".repeat(400 + j) + inner + "]".repeat(400 + j));
    String passes = atDepth(body, j + 1);
    assertThat(ownFunctionsOnA16MiBThread(passes)).as("lifted functions of %s at 513 measured levels", body).containsExactly("p4/0");
    assertThat(resultsOnA16MiBThread(passes)).as("results of %s at 513 measured levels", body).containsExactly("[".repeat(401 + j) + inner + "]".repeat(401 + j));
  }

  static Stream<Arguments> measuredLevels() {
    return Stream.of(
        Arguments.of("-length", 3, "-0"),
        Arguments.of("if . then 1 else 2 end", 3, "2"),
        Arguments.of("if . then 1 end", 3, "null"),
        Arguments.of("try length catch 0", 4, "0"),
        Arguments.of("try length", 4, "0"),
        Arguments.of("label $out | .", 3, "null"),
        Arguments.of("label $f | break $f", 3, null),
        Arguments.of("reduce . as $x (.; $x)", 12, "null"),
        Arguments.of("foreach . as $x (.; $x)", 12, "null"),
        Arguments.of("foreach . as $x (.; $x; $x)", 12, "null"),
        Arguments.of(". as [$a, $b] | $a", 13, "null"),
        Arguments.of(". as $x | $x", 9, "null"),
        Arguments.of("\"a\\(.)\"", 5, "\"anull\""),
        Arguments.of(".[length]", 6, "null"),
        Arguments.of(".[length:]", 6, "null"),
        Arguments.of(".[:length]", 6, "null"),
        Arguments.of(".[length:length]", 6, "null"),
        Arguments.of(".\"\\(.)\"", 8, "null"),
        Arguments.of(".\"a\"", 6, "null"),
        Arguments.of("length?", 6, "0"),
        Arguments.of(".[]?", 6, null),
        Arguments.of("..", 7, "null"),
        Arguments.of("length", 2, "0"),
        Arguments.of("last(.)", 5, "null"),
        Arguments.of("range(length; 1)", 5, "0"),
        Arguments.of("{(tostring): .}", 7, "{\"null\":null}"),
        Arguments.of("{a}", 7, "{\"a\":null}"),
        Arguments.of("{\"a\"}", 9, "{\"a\":null}"),
        Arguments.of("{a: 1}", 7, "{\"a\":1}"),
        Arguments.of("{\"a\\(.)\": 1}", 10, "{\"anull\":1}"),
        Arguments.of(". as $x | {$x}", 15, "{\"x\":null}"),
        Arguments.of("{$__loc__}", 7, "{\"__loc__\":{\"file\":\"<top-level>\",\"line\":1}}"),
        Arguments.of("$__loc__", 1, "{\"file\":\"<top-level>\",\"line\":1}"),
        Arguments.of("@text", 1, "\"null\""),
        Arguments.of("null", 1, "null"));
  }

  /** {@code def f: <body>; def p1: [×100 f ]×100; … def p4: [×100 p3 ]×100;} and p4 in {@code j} brackets. */
  private static String atDepth(String body, int j) {
    StringBuilder text = new StringBuilder("def f: ").append(body).append("; ");
    String inner = "f";
    for (int i = 1; i <= 4; i++) {
      text.append("def p").append(i).append(": ").append("[".repeat(PER)).append(inner).append("]".repeat(PER)).append("; ");
      inner = "p" + i;
    }
    return text.append("[".repeat(j)).append("p4").append("]".repeat(j)).toString();
  }

  /** The functions of f and the p definitions {@code filter}'s program has, compiled on a 16 MiB thread: the lifted ones. */
  private static List<String> ownFunctionsOnA16MiBThread(String filter) throws InterruptedException {
    AtomicReference<List<String>> got = new AtomicReference<>();
    Throwable thrown = on(LARGE, () -> got.set(Inlining.lifted(filter)));
    if (thrown != null) {
      throw new AssertionError("compiling " + shown(filter) + " on a 16 MiB thread threw", thrown);
    }
    return got.get().stream().filter(name -> List.of("f/0", "p1/0", "p2/0", "p3/0", "p4/0").contains(name)).toList();
  }

  /** {@code def f0: .; def f1: f0,f0; …; def fn: f(n-1),f(n-1); fn}, Inlining's doubling chain and its call. */
  private static String doublingChain(int n) {
    return Inlining.doubling(n) + "f" + n;
  }

  /**
   * {@code levels} - 1 brackets around {@code .}, through definitions: {@code def f1: [[…[.]…]]; def f2: [[…[f1]…]];
   * …} and the main filter's rest of brackets around the last call, so its IR has {@code levels} levels.
   */
  private static String nested(int levels) {
    return definitions("f", levels) + call("f", levels);
  }

  /**
   * The #70 review's filter: {@code def g1: …; … def g5: …;} of 501 levels as {@link #nested} builds them, then {@code
   * def v1($a): g5; def v2($a): v1(.); …}, called as {@code vn(.)}: n expansions, each binding {@code $a} around the
   * next.
   */
  private static String valueParameters(int n) {
    StringBuilder f = new StringBuilder(definitions("g", 501)).append("def v1($a): ").append(call("g", 501)).append("; ");
    for (int i = 2; i <= n; i++) {
      f.append("def v").append(i).append("($a): v").append(i - 1).append("(.); ");
    }
    return f.append('v').append(n).append("(.)").toString();
  }

  /** {@code def <name>1: [[…[.]…]]; def <name>2: [[…[<name>1]…]]; …}, {@link #PER} brackets each, for {@code levels}. */
  private static String definitions(String name, int levels) {
    StringBuilder f = new StringBuilder();
    int defs = (levels - 1) / PER;
    for (int i = 1; i <= defs; i++) {
      f.append("def ").append(name).append(i).append(": ").append("[".repeat(PER)).append(i == 1 ? "." : name + (i - 1)).append("]".repeat(PER)).append("; ");
    }
    return f.toString();
  }

  /** The rest of the brackets of {@code levels} around the last of {@link #definitions}, or around {@code .}. */
  private static String call(String name, int levels) {
    int defs = (levels - 1) / PER;
    int rest = (levels - 1) % PER;
    return "[".repeat(rest) + (defs == 0 ? "." : name + defs) + "]".repeat(rest);
  }

  private static void assertStubOnA16MiBThread(String filter) throws InterruptedException {
    Throwable thrown = on(LARGE, () -> Inlining.ENGINE.compile(filter));
    assertThat(thrown).as("what compiling %s threw on a 16 MiB thread", shown(filter)).isInstanceOf(JqCompileException.class);
    assertThat(thrown.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK);
  }

  /** The results of {@code filter} on null on a 16 MiB thread; what the run threw fails the test with it as the cause. */
  private static List<String> resultsOnA16MiBThread(String filter) throws InterruptedException {
    AtomicReference<List<String>> got = new AtomicReference<>();
    Throwable thrown = on(LARGE, () -> got.set(results(filter)));
    if (thrown != null) {
      throw new AssertionError("running " + shown(filter) + " on a 16 MiB thread threw", thrown);
    }
    return got.get();
  }

  /** What {@code work} threw on a thread of {@code stack} bytes, or null. */
  private static Throwable on(long stack, Runnable work) throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread = new Thread(null, () -> {
      try {
        work.run();
      } catch (Throwable t) {
        failure.set(t);
      }
    }, stack + " bytes", stack);
    thread.start();
    thread.join();
    return failure.get();
  }
}
