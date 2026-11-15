package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.CallRuns.F;
import static io.github.subapavel.jqj.CallRuns.onStack;
import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.value.JqValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The form-B trampoline at the public API (#109, ADR-0046 and its #109 amendment, ADR-0030 #109 amendment): a run
 * whose {@code stackBytes} is 0 has {@code U_f <= 0}, so main and every body run on the driver, whose Java depth does
 * not grow with the filter. It is reached here only through {@link Limits#stackBytes()} 0 and {@link JqFilter#run}.
 *
 * <p>Every run with {@code stackBytes} 0 is on a thread of {@value #SMALL} bytes, where a recursion of 10^4 levels in
 * push form ends with {@code JqTermination(STACK)}: each control below runs the same filter with a declared stack of
 * 1 GiB, which S2b.5's {@code U_f} keeps on the Java stack, and shows that end. So a result there shows the driver. A
 * run with the default {@code stackBytes} that must complete 10^4 levels in push form, as the oracle of the step
 * count, gets a thread of {@value #BIG} bytes.
 *
 * <p>Every expected result is reference jq 1.8.2's: the deep ones observed 2026-11-15 on the 9 999-deep array of
 * {@link #DEEP} and on deep numbers ({@code .logs/109-jqref-deep.log} of #109; ADR-0046 cites the first three), F on N
 * gives N for N up to 10^7 (F-87), and the shallow ones are the recordings of diff/calls.rec and diff/heap.rec.
 */
class TrampolineTest {
  /** The thread of every run with {@code stackBytes} 0 and of each control: DeepRuns' small stack. */
  static final long SMALL = 256 * 1024;

  /** The thread of a push-form run that must complete 10^4 levels. */
  static final long BIG = 512L << 20;

  /** A declared stack far larger than {@link #SMALL}: S2b.5's {@code U_f} keeps every level on the Java stack. */
  private static final long GIB = 1L << 30;

  /** 9 999 {@code [} then 9 999 {@code ]}: one level under jq's parse limit of 10 000 (F-22). */
  static final String DEEP = "[".repeat(9_999) + "]".repeat(9_999);

  /** A Collect per level: reference jq gives 1 on {@link #DEEP}. */
  static final String COLLECT = "def d: [.[]?|d]; d|length";

  /** A Reduce step per level: reference jq gives 9998 on {@link #DEEP}. */
  static final String REDUCE = "def r: reduce .[] as $x (0; .+($x|r)+1); r";

  /** first's Label and an Alt per level: reference jq gives 7 on {@link #DEEP}. */
  static final String FIRST_OR = "def l: first(.[]?|l)//7; l";

  /** The constant-Java-depth witness, an Alt's left side per level: reference jq gives 1 on 9999. */
  static final String ALT_CHAIN = "def f: if . == 0 then 1 else (. - 1 | f) // 2 end; f";

  /** An engine of {@link Limits#cli()} with {@code recursionDepth} and {@code stackBytes}, compiling in {@code mode}. */
  private static JqEngine engine(int recursionDepth, long stackBytes, CompileMode mode) {
    Limits cli = Limits.cli();
    return JqEngine.builder()
        .limits(new Limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), recursionDepth, Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), stackBytes))
        .compileMode(mode)
        .build();
  }

  /** An engine of {@link Limits#cli()} whose declared stack is {@code stackBytes}. */
  private static JqEngine engine(long stackBytes, CompileMode mode) {
    return engine(Limits.cli().recursionDepth(), stackBytes, mode);
  }

  /**
   * How {@code filter} on the document {@code json} ended under {@code engine}, run on a new thread of
   * {@code threadBytes}: its result texts, then {@code ran out}, {@code stopped}, {@code error} and the error value's
   * JSON, or {@code termination} and its cause and budget; with {@code steps}, then the Workspace's steps after the
   * run, the read's included. SpecialiseExplainTest (#117) runs its cases through it too.
   */
  static String ended(JqEngine engine, String filter, String json, long threadBytes, boolean steps) {
    AtomicReference<String> outcome = new AtomicReference<>();
    onStack(
        threadBytes,
        () -> {
          JqFilter compiled = engine.compile(filter);
          Workspace workspace = engine.newWorkspace();
          JqValue root = read(workspace, json.getBytes(US_ASCII)).root();
          Results results = new Results();
          String end;
          try {
            end = compiled.run(root, NONE, results, workspace) ? "ran out" : "stopped";
          } catch (JqFilterException e) {
            end = "error " + e.value().toJson();
          } catch (JqTermination t) {
            end = "termination " + t.cause() + (t.budget() == null ? "" : " " + t.budget());
          }
          String texts = results.values.stream().map(JqValue::toJson).toList().toString();
          outcome.set(texts + ", " + end + (steps ? ", " + workspace.steps() + " steps" : ""));
        });
    return outcome.get();
  }

  /** {@link #ended} without the steps, on a {@value #SMALL}-byte thread. */
  private static String onSmall(JqEngine engine, String filter, String json) {
    return ended(engine, filter, json, SMALL, false);
  }

  /**
   * ADR-0046: reference jq completes these on a 9 999-deep array in about 10 ms each, and a spine-only driver would end
   * them near 200 levels. With {@code stackBytes} 0 every kind of their bodies runs on the driver, so a 256 KiB thread
   * holds them; the constant-Java-depth witness nests 9 999 Alts, whose left sides keep their state on the heap.
   */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void deepRecursionOnTheTrampoline(CompileMode mode) {
    JqEngine zero = engine(0, mode);
    assertThat(onSmall(zero, COLLECT, DEEP)).as("%s: %s on the 9 999-deep array with stackBytes 0", mode, COLLECT).isEqualTo("[1], ran out");
    assertThat(onSmall(zero, REDUCE, DEEP)).as("%s: %s on the 9 999-deep array with stackBytes 0", mode, REDUCE).isEqualTo("[9998], ran out");
    assertThat(onSmall(zero, FIRST_OR, DEEP)).as("%s: %s on the 9 999-deep array with stackBytes 0", mode, FIRST_OR).isEqualTo("[7], ran out");
    assertThat(onSmall(zero, ALT_CHAIN, "9999")).as("%s: %s on 9999 with stackBytes 0", mode, ALT_CHAIN).isEqualTo("[1], ran out");

    // Controls: in push form the same runs exhaust this thread.
    JqEngine pushed = engine(GIB, mode);
    assertThat(onSmall(pushed, COLLECT, DEEP)).as("%s: %s on the 9 999-deep array with a declared stack of 1 GiB", mode, COLLECT).isEqualTo("[], termination STACK");
    assertThat(onSmall(pushed, ALT_CHAIN, "9999")).as("%s: %s on 9999 with a declared stack of 1 GiB", mode, ALT_CHAIN).isEqualTo("[], termination STACK");
  }

  /**
   * Every case of diff/calls.cases and diff/heap.cases, read from the oracle's resources (each runs {@code -c} on its
   * stdin), then the four deep runs above. Comments, blank lines and the {@code def} line are skipped.
   */
  static Stream<Arguments> stepCases() {
    List<Arguments> cases = new ArrayList<>();
    for (String family : List.of("calls", "heap")) {
      cases.addAll(caseFile(family));
    }
    assertThat(cases).as("the cases of diff/calls.cases and diff/heap.cases").hasSizeGreaterThanOrEqualTo(36 + 12);
    cases.add(Arguments.argumentSet("deep/collect", COLLECT, DEEP));
    cases.add(Arguments.argumentSet("deep/reduce", REDUCE, DEEP));
    cases.add(Arguments.argumentSet("deep/first-or", FIRST_OR, DEEP));
    cases.add(Arguments.argumentSet("deep/alt-chain", ALT_CHAIN, "9999"));
    return cases.stream();
  }

  /**
   * ADR-0046 #109 amendment: a heap form ticks and charges as its push form does, and a bounce charges no step (ADR-0008).
   * Under the conservative compile, the run with {@code stackBytes} 0 ends as the run with the default does: the same
   * results, the same end, and the same {@code Workspace.steps()}. The shallow cases fit either thread in push form too,
   * so before the driver exists only the deep ones tell the settings apart.
   */
  @ParameterizedTest
  @MethodSource("stepCases")
  void trampolineChargesTheSameSteps(String filter, String json) {
    String pushed = ended(engine(Limits.cli().stackBytes(), CompileMode.CONSERVATIVE), filter, json, BIG, true);
    String zero = ended(engine(0, CompileMode.CONSERVATIVE), filter, json, SMALL, true);
    assertThat(zero).as("%s with stackBytes 0, against the default's end", filter).isEqualTo(pushed);
  }

  /**
   * The witnesses of RecursionDepthTest with {@code stackBytes} 0, a hundred times deeper: the recursion-depth budget
   * counts the call activations on the driver as on the stack (ADR-0046), one per live Call and real-closure
   * invocation, none for an inlined call, and a call leaves the count when its results run out, at a stop or a break,
   * or when an error leaves it; a termination on the trampoline ends the run with its activations still counted until
   * the next run begins. A budget of 10 000 takes F on 9999 (10 000 live Calls) and ends F on 10 000 before any result.
   */
  static Stream<Arguments> depthWitnesses() {
    String raising = "def f: if . == 0 then error(\"x\") else (. - 1 | f) + 1 end; [range(3) | try (%d | f) catch .]";
    String breaking = "[range(3) | (label $l | def f: if . == 0 then break $l else (. - 1 | f) + 1 end; %d | f), .]";
    String closure = "def r(f): if . == 0 then f else (. - 1 | r(f)) + 0 end; r(0)";
    String wrapped = "def w(g): g; def f: if . == 0 then 0 else w(. - 1 | f) + 1 end; f";
    String twice = "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; [(9999 | f), (9999 | f)]";
    String over = "[], termination BUDGET RECURSION_DEPTH";
    return Stream.of(
        Arguments.argumentSet("F at the budget", CompileMode.OPTIMISED, 10_000, F, "9999", "[9999], ran out"),
        Arguments.argumentSet("F one over the budget", CompileMode.OPTIMISED, 10_000, F, "10000", over),
        Arguments.argumentSet("F with one level more of budget", CompileMode.OPTIMISED, 10_001, F, "10000", "[10000], ran out"),
        Arguments.argumentSet("a budget of 0 ends the first Call", CompileMode.OPTIMISED, 0, F, "0", over),
        Arguments.argumentSet("inlined calls only on a budget of 0", CompileMode.OPTIMISED, 0, "def g: . + 1; def h: g | g; [h, h]", "0", "[[2,2]], ran out"),
        Arguments.argumentSet("a passed-on closure at the budget", CompileMode.CONSERVATIVE, 10_000, closure, "9998", "[0], ran out"),
        Arguments.argumentSet("a passed-on closure one over", CompileMode.CONSERVATIVE, 10_000, closure, "9999", over),
        Arguments.argumentSet("an inlined wrapper at the budget", CompileMode.OPTIMISED, 10_000, wrapped, "9999", "[9999], ran out"),
        Arguments.argumentSet("an inlined wrapper one over", CompileMode.OPTIMISED, 10_000, wrapped, "10000", over),
        Arguments.argumentSet("two deep calls in sequence", CompileMode.OPTIMISED, 10_000, twice, "null", "[[9999,9999]], ran out"),
        Arguments.argumentSet("errors leaving deep calls", CompileMode.OPTIMISED, 10_000, raising.formatted(9999), "null", "[[\"x\",\"x\",\"x\"]], ran out"),
        Arguments.argumentSet("errors leaving calls one over", CompileMode.OPTIMISED, 10_000, raising.formatted(10_000), "null", over),
        Arguments.argumentSet("breaks leaving deep calls", CompileMode.OPTIMISED, 10_000, breaking.formatted(9999), "null", "[[0,1,2]], ran out"),
        Arguments.argumentSet("breaks leaving calls one over", CompileMode.OPTIMISED, 10_000, breaking.formatted(10_000), "null", over));
  }

  @ParameterizedTest
  @MethodSource("depthWitnesses")
  void recursionDepthCountsOnTheTrampoline(CompileMode mode, int recursionDepth, String filter, String json, String expected) {
    assertThat(onSmall(engine(recursionDepth, 0, mode), filter, json))
        .as("%s: %s on %s with a recursion depth of %d and stackBytes 0", mode, filter, json, recursionDepth)
        .isEqualTo(expected);
  }

  /** The cases of {@code diff/<family>.cases}: each case's id, its filter and its stdin less the final LF. */
  private static List<Arguments> caseFile(String family) {
    Path file = Path.of(System.getProperty("user.dir")).resolve("../oracle/src/test/resources/diff/" + family + ".cases").normalize();
    List<String> lines;
    try {
      lines = Files.readAllLines(file, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Arguments> cases = new ArrayList<>();
    String id = null;
    String filter = null;
    String stdin = null;
    for (String line : lines) {
      if (line.startsWith("case ")) {
        id = line.substring(5);
        filter = null;
        stdin = null;
      } else if (line.startsWith("arg ")) {
        assertThat(line).as("the only argument of %s", id).isEqualTo("arg -c");
      } else if (line.startsWith("filter ")) {
        filter = unescape(line.substring(7));
      } else if (line.startsWith("stdin ")) {
        stdin = unescape(line.substring(6));
      } else if (line.equals("end")) {
        assertThat(stdin).as("the stdin of %s", id).endsWith("\n");
        cases.add(Arguments.argumentSet(id, filter, stdin.substring(0, stdin.length() - 1)));
      }
    }
    return cases;
  }

  /** The case format's escapes these files use: a backslash and an LF. */
  private static String unescape(String s) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '\\') {
        out.append(c);
        continue;
      }
      char next = s.charAt(++i);
      switch (next) {
        case '\\' -> out.append('\\');
        case 'n' -> out.append('\n');
        default -> throw new IllegalArgumentException("an escape this test does not read: \\" + next);
      }
    }
    return out.toString();
  }
}
