package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.MARK;
import static io.github.subapavel.jqj.Inlining.outcome;
import static io.github.subapavel.jqj.Inlining.results;
import static io.github.subapavel.jqj.Inlining.shown;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #86 (F-44 "Not changed here"): the parser builds a left-associative operator chain ({@code +}, {@code and}, {@code
 * or}, a comparison inside one) in a loop, so the Binder and the Resolver meet a chain as long as the filter. They walk
 * it without a Java frame per term, and a chain of constants folds to its Const. Since #94 (ADR-0043) a chain of one
 * operator is one Binds, and one of {@code and} or {@code or} is balanced, so a chain of any length runs.
 *
 * <p>jq-ref 1.8.2 (observed 2026-11-13, the filter read with -nc -f): {@code .a+…} runs 6 554 terms and {@code .a and
 * …} and {@code .a or …} 3 277, {@code .a == .a and …} and {@code .a // …} 2 185; one more term is its bytecode limit
 * {@code function compiled to … bytes which is too long}, rc 3, as are 20 000 terms of each. 20 000 terms of {@code
 * .a // …} are its {@code memory exhausted}, rc 3, and 50 000 of {@code .a+} crash it (rc 139). {@code 1+1+…} folds:
 * 20 000 and 200 000 ones print their sum, rc 0, and so do they with a last {@code +.a}, and one more with a last
 * {@code +.a+1}.
 *
 * <p>As in IrDepthTest, the 512 KiB edges are pinned in one fresh child JVM with no agent, the case a host meets, since
 * an instrumented or warmed JVM needs more stack per level (F-44); this JVM pins the same filters on a 16 MiB thread,
 * with chains long enough that a frame per term would overflow it.
 */
class OperatorChainTest {

  /** ADR-0017's default stack for a run. */
  private static final long STACK = 512 * 1024;

  /** A stack the Resolver's 512 levels fit in whatever this JVM's state and instrumentation. */
  private static final long LARGE = 16L * 1024 * 1024;

  /** Terms of the child JVM's chains: past jq-ref's bytecode limit for every operator here. */
  private static final int CHILD_TERMS = 20_000;

  /** Terms of this JVM's chains: a Java frame or more per term would overflow {@link #LARGE}. */
  private static final int TERMS = 200_000;

  /**
   * What {@link Child} prints for its chains, in its order. #94 (ADR-0043) ended the Stubs of the first four and the
   * parse-depth compile error of {@code .a //}, entries that ended with their Stub: past jq-ref's limits each chain
   * gives what jq-ref 1.8.2 gives at its limit, every term being the same (observed 2026-11-13, -nc -f,
   * .logs/94-jqref-edges.log of the test-author's run): null for 6 554 terms of {@code .a+}, false for 3 277 of {@code
   * .a and} and of {@code .a or}, true for 2 185 of {@code .a == .a and}, null for 2 185 of {@code .a //}. A chain whose
   * operators alternate stays nested and is the IR bound's Stub ({@link #aLongAlternatingChainIsTheIrDepthStub}).
   */
  private static final String EDGES =
      "20000 terms of .a+: null\n"
          + "20000 terms of .a and: false\n"
          + "20000 terms of .a or: false\n"
          + "20000 terms of .a == .a and: true\n"
          + "20000 terms of 1+: 20000\n"
          + "20000 terms of 1+ and a last +.a: 20000\n"
          + "20000 terms of .a //: null\n"
          + "20000 terms alternating .a+ and .a-: a Stub\n"
          + "600 chains, then 30001 levels: a Stub\n";

  @TempDir Path dir;

  /**
   * One child JVM, started with the test class path, no agent and no tool or JVM options, runs {@link Child}: the
   * stack claim of every test here.
   */
  @Test
  void onA512KiBThreadInAFreshJvmLongOperatorChainsRunOrFold() throws IOException, InterruptedException {
    File out = dir.resolve("out").toFile();
    ProcessBuilder launch =
        new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(out);
    // Each would reach the child's command line, and an agent there would make it the instrumented case.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    Process child = launch.start();
    try {
      assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("the child JVM ends within 120 s").isTrue();
      assertThat(Files.readString(out.toPath(), UTF_8)).as("what the child JVM printed").isEqualTo(EDGES);
      assertThat(child.exitValue()).as("the child JVM's exit code").isZero();
    } finally {
      child.destroyForcibly();
    }
  }

  /**
   * #94 (ADR-0043): a chain of one operator is one Binds, or a balanced {@code and} or {@code or}, so a chain of any
   * length compiles and runs; until #94 it was the IR bound's Stub, an entry that ended with its Stub. Each gives on
   * null what jq-ref 1.8.2 gives at its limit, every term being the same (observed 2026-11-13, -nc -f,
   * .logs/94-jqref-pins.log of the test-author's run, 6 500 terms of each arithmetic operator in {@code try (…) catch
   * .}): null for {@code +}, and its error for the others, raised by the first operator, as one term more is past
   * jq-ref's bytecode limit. Its 512 KiB claim is pinned by the child JVM.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "'+' | .a | null",
        "' and ' | .a | false",
        "' or ' | .a | false",
        "' and ' | .a == .a | true",
        "'-' | .a | error \"null (null) and null (null) cannot be subtracted\"",
        "'*' | .a | error \"null (null) and null (null) cannot be multiplied\"",
        "'/' | .a | error \"null (null) and null (null) cannot be divided\"",
        "'%' | .a | error \"null (null) and null (null) cannot be divided (remainder)\""
      })
  void aLongOperatorChainRuns(String operator, String term, String outcome) throws InterruptedException {
    String filter = terms(TERMS, operator, term);
    AtomicReference<List<String>> got = new AtomicReference<>();
    Throwable thrown = on(LARGE, () -> got.set(outcome(filter)));
    assertThat(thrown).as("what running %s threw on a 16 MiB thread", shown(filter)).isNull();
    assertThat(got.get()).as("outcome of %s on null", shown(filter)).isEqualTo(List.of(outcome));
  }

  /**
   * #94 (ADR-0043 "one operator"): a chain whose operators alternate, {@code .a+.a-.a+…}, is runs of one operator each,
   * so it stays nested, two levels an operator, and past the IR bound it is the bound's Stub: the Binder and the
   * Resolver walk it by a loop, so 200 000 terms are that Stub, not a StackOverflowError. jq-ref 1.8.2 compiles 400
   * such terms and raises {@code null (null) and null (null) cannot be subtracted}, rc 5 (observed 2026-11-14, -nc -f,
   * .logs/94-jqref-alt.log; DEVIATIONS "IR depth"). Its 512 KiB claim is pinned by the child JVM.
   */
  @Test
  void aLongAlternatingChainIsTheIrDepthStub() throws InterruptedException {
    String filter = alternating(TERMS);
    Throwable thrown = on(LARGE, () -> Inlining.ENGINE.compile(filter));
    assertThat(thrown).as("what compiling %s threw on a 16 MiB thread", shown(filter)).isInstanceOf(JqCompileException.class);
    assertThat(thrown.getMessage()).as("message of the compile error of %s", shown(filter)).isEqualTo(MARK + "a filter of more than 512 levels of IR is not built yet");
  }

  /** {@code .a+.a-.a+…}, {@code n} terms, the operators alternating. */
  private static String alternating(int n) {
    StringBuilder chain = new StringBuilder(".a");
    for (int i = 1; i < n; i++) {
      chain.append(i % 2 == 1 ? "+" : "-").append(".a");
    }
    return chain.toString();
  }

  /**
   * A chain of constants folds to one Const, as jq's parser folds it (jq-ref 1.8.2 prints the sum), and a chain whose
   * last term is no constant lowers to its folded left operand plus that term.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {"'' | 200000", "'+.a' | 200000", "'+.a+1' | 200001"})
  void aLongChainOfConstantsFolds(String last, String result) throws InterruptedException {
    String filter = terms(TERMS, "+", "1") + last;
    AtomicReference<List<String>> got = new AtomicReference<>();
    Throwable thrown = on(LARGE, () -> got.set(results(filter)));
    assertThat(thrown).as("what running %s threw on a 16 MiB thread", shown(filter)).isNull();
    assertThat(got.get()).as("results of %s on null", shown(filter)).isEqualTo(List.of(result));
  }

  /**
   * A chain's levels end with it: 600 chains side by side, each inside its operators' levels only while it is lowered,
   * run, one folded in part, as jq-ref 1.8.2 prints them (observed 2026-11-13, -nc -f): 600 nulls, and 600 twos.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {"(.a+.a+.a) | null", "(1+1+.a) | 2"})
  void chainsSideBySideRun(String chain, String each) {
    String filter = "[" + terms(600, ", ", chain) + "]";
    assertThat(results(filter)).as("results of 600 of %s side by side", chain).isEqualTo(List.of("[" + terms(600, ",", each) + "]"));
  }

  /**
   * #94 (ADR-0043): a chain is one Binds, a few levels however long, so inside a definition 40 000 terms are no IR
   * depth Stub, as they were until #94, but the inline cap's: each term and each operator spend a node, 79 999 in all,
   * past the cap of 65 536 (ADR-0040 "Inline cap", {@link #eachOperatorOfAChainSpendsANode}).
   */
  @Test
  void aLongChainInADefinitionIsTheInlineCapStub() throws InterruptedException {
    String filter = "def f: " + terms(40_000, " + ", "length") + "; f";
    Throwable thrown = on(LARGE, () -> Inlining.ENGINE.compile(filter));
    assertThat(thrown).as("what compiling %s threw on a 16 MiB thread", shown(filter)).isInstanceOf(JqCompileException.class);
    assertThat(thrown.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK + "a filter that inlines more than 65536 nodes");
  }

  /**
   * Each operator of a chain spends a node of the inline cap, as its own lowering would: 15 000 chains of three terms
   * inside a definition are 75 000 nodes, past the cap of 65 536, and its Stub, while their terms alone are 45 000.
   */
  @Test
  void eachOperatorOfAChainSpendsANode() {
    String filter = "def f: [" + terms(15_000, ", ", "(length+length+length)") + "]; f";
    JqCompileException e = catchThrowableOfType(JqCompileException.class, () -> Inlining.ENGINE.compile(filter));
    assertThat(e).as("what compiling %s threw", shown(filter)).isNotNull();
    assertThat(e.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK + "a filter that inlines more than 65536 nodes");
  }

  /**
   * And no more than one: 13 000 chains of three terms inside a definition are 65 000 nodes, under the cap, and
   * compile, where two nodes an operator would be 91 000.
   */
  @Test
  void eachOperatorOfAChainSpendsOnlyOneNode() {
    String filter = "def f: [" + terms(13_000, ", ", "(length+length+length)") + "]; f";
    assertThatCode(() -> Inlining.ENGINE.compile(filter)).as("compiling %s", shown(filter)).doesNotThrowAnyException();
  }

  /** {@code n} copies of {@code term} joined by {@code separator}. */
  private static String terms(int n, String separator, String term) {
    StringJoiner joined = new StringJoiner(separator);
    for (int i = 0; i < n; i++) {
      joined.add(term);
    }
    return joined.toString();
  }

  /**
   * The child JVM of {@link #onA512KiBThreadInAFreshJvmLongOperatorChainsAreStubsOrFold}: on a 512 KiB thread each,
   * it compiles and runs on null each chain of {@link #CHILD_TERMS} terms, then 600 short chains before a filter past
   * the IR bound, and prints a line for each.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      run(CHILD_TERMS + " terms of .a+", terms(CHILD_TERMS, "+", ".a"));
      run(CHILD_TERMS + " terms of .a and", terms(CHILD_TERMS, " and ", ".a"));
      run(CHILD_TERMS + " terms of .a or", terms(CHILD_TERMS, " or ", ".a"));
      run(CHILD_TERMS + " terms of .a == .a and", terms(CHILD_TERMS, " and ", ".a == .a"));
      run(CHILD_TERMS + " terms of 1+", terms(CHILD_TERMS, "+", "1"));
      run(CHILD_TERMS + " terms of 1+ and a last +.a", terms(CHILD_TERMS, "+", "1") + "+.a");
      run(CHILD_TERMS + " terms of .a //", terms(CHILD_TERMS, " // ", ".a"));
      run(CHILD_TERMS + " terms alternating .a+ and .a-", alternating(CHILD_TERMS));
      run("600 chains, then 30001 levels", chainsThenLevels());
    }

    private static void run(String name, String filter) throws InterruptedException {
      AtomicReference<List<String>> got = new AtomicReference<>();
      Throwable thrown = on(STACK, () -> got.set(results(filter)));
      String shown;
      if (thrown == null) {
        shown = String.join(", ", got.get());
      } else if (thrown instanceof JqCompileException) {
        shown = thrown.getMessage().startsWith(MARK) ? "a Stub" : "a compile error";
      } else {
        shown = thrown.toString();
      }
      System.out.println(name + ": " + shown);
    }

    /**
     * 600 chains of three terms, then 30 000 brackets around {@code .} through 300 definitions of 100 each, as
     * IrDepthTest nests them: the lowering stops the brackets at the bound only if each chain's levels ended with it.
     */
    private static String chainsThenLevels() {
      StringBuilder f = new StringBuilder();
      for (int i = 1; i <= 300; i++) {
        f.append("def f").append(i).append(": ").append("[".repeat(100)).append(i == 1 ? "." : "f" + (i - 1)).append("]".repeat(100)).append("; ");
      }
      return f.append(terms(600, ", ", "(.a+.a+.a)")).append(", f300").toString();
    }
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
