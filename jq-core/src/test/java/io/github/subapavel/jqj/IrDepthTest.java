package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.MARK;
import static io.github.subapavel.jqj.Inlining.outcome;
import static io.github.subapavel.jqj.Inlining.results;
import static io.github.subapavel.jqj.Inlining.shown;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

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
 * ADR-0040 "IR depth" (F-44): past {@code Resolver.MAX_IR_DEPTH} = 512 levels of built IR the filter is a Stub, as the
 * 512th nested expansion is. A level is one node of the IR on the way from its root to a leaf, the leaf counted: {@code
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

  /** What {@link Child} prints for its edges, in its order. */
  private static final String EDGES =
      "512 levels: " + NESTED_512 + "\n" + "513 levels: a Stub\n" + "30001 levels: a Stub\n" + "500 value parameters: a Stub\n" + "10000 computed indexes: a Stub\n";

  @TempDir Path dir;

  /**
   * One child JVM, started with the test class path, no agent and no tool or JVM options, runs {@link Child}: the
   * stack claim of every test here.
   */
  @Test
  void onA512KiBThreadInAFreshJvm512LevelsRunAndDeeperFiltersAreStubs() throws IOException, InterruptedException {
    assertChildPrintsTheEdges();
  }

  /**
   * The same child JVM with C1 only and compiles in the foreground, the state of a loaded machine whose C2 compiles
   * lag (#86): the 512 levels the child runs first get the Resolver C1-compiled, and the descent of 513 levels ran out
   * of 512 KiB about 500 levels down, before the bound's Stub (#86, 5 of 5 runs before the lowering's frames were made small).
   */
  @Test
  void onA512KiBThreadInAFreshC1OnlyJvm512LevelsRunAndDeeperFiltersAreStubs() throws IOException, InterruptedException {
    assertChildPrintsTheEdges("-Xbatch", "-XX:TieredStopAtLevel=1");
  }

  /**
   * The same child JVM stopped at tier 3, C1 with full profiling, the code a loaded machine runs while C2 compiles
   * lag: its frames are as large as tier 1's (#86, measured: 512 levels fit 472 KiB and 500 value parameters 488 KiB
   * under either tier).
   */
  @Test
  void onA512KiBThreadInAFreshProfilingC1Jvm512LevelsRunAndDeeperFiltersAreStubs() throws IOException, InterruptedException {
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
  }

  /** The bound's Stub; its 512 KiB stack claim is pinned by the child JVM's test. */
  @Test
  void aFilterOf513LevelsIsAStub() throws InterruptedException {
    assertStubOnA16MiBThread(nested(513));
  }

  /** 300 definitions of 100 brackets; the bound's Stub, its 512 KiB stack claim pinned by the child JVM's test. */
  @Test
  void aFilterOf30001LevelsIsAStub() throws InterruptedException {
    assertStubOnA16MiBThread(nested(30_001));
  }

  /**
   * 500 nested expansions of one value parameter each around 501 levels: each Bind of {@code $a} is a level of its own
   * (F-44, the #70 review at b0b5095). Its 512 KiB stack claim is pinned by the child JVM's test.
   */
  @Test
  void nestedValueParameterExpansionsAreAStub() throws InterruptedException {
    assertStubOnA16MiBThread(valueParameters(500));
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
   * The child JVM of {@link #onA512KiBThreadInAFreshJvm512LevelsRunAndDeeperFiltersAreStubs}: on a 512 KiB thread
   * each, it runs 512 levels on null and compiles 513 levels, 30 001 levels, 500 value parameters and 10 000 computed
   * indexes, in that order, and prints a line for each. The lowering's count stops the first three Stubs; only the walk
   * of the built IR stops the last.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      AtomicReference<List<String>> got = new AtomicReference<>();
      Throwable thrown = on(STACK, () -> got.set(results(nested(512))));
      System.out.println("512 levels: " + (thrown == null ? String.join(", ", got.get()) : thrown));
      stub("513 levels", nested(513));
      stub("30001 levels", nested(30_001));
      stub("500 value parameters", valueParameters(500));
      stub("10000 computed indexes", "." + "[.a]".repeat(10_000));
    }

    private static void stub(String name, String filter) throws InterruptedException {
      Throwable stub = on(STACK, () -> Inlining.ENGINE.compile(filter));
      boolean isStub = stub instanceof JqCompileException && stub.getMessage().startsWith(MARK);
      System.out.println(name + ": " + (isStub ? "a Stub" : stub == null ? "compiled" : stub));
    }
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
