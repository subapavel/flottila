package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.ExplainLines.Line;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tail calls as pending jumps (#118, ADR-0047 and its #118 amendment, ADR-0006's four conditions, ADR-0046, ADR-0018,
 * GLOSSARY "Tail call", "Call kind"). A call is a tail call only in a tail position of a function body, into the
 * caller's recursive group, with every left context at most one, and with each closure argument either a closure
 * parameter passed on bare or holding no free slot, free Break or ParamCall. Its kind is TAIL_SELF when it calls the
 * caller itself and TAIL_GROUP when it calls another function of the group; {@link JqFilter#explain()} prints it as
 * {@code call=}. The conservative compile stores no tail kind.
 *
 * <p>Lines are read with ExplainLines' {@link Line}, one field at a time. A node line is one whose first field is its
 * body's column, {@code main} or {@code fn<place>}; a function line names its jq name, {@code _until/0} for until's
 * inner function, and a copy's shows {@code copyOf=}. A selection names the jq names of the caller and the callee, and
 * for the prelude loops only the copies #117 makes for main's closed arguments, whose self-calls are the jumps.
 *
 * <p>Every expected result is reference jq 1.8.2's: diff/tail.rec for the deep cases ({@code
 * scripts/oracle-record.sh}, under {@code ulimit -v 2000000}), and {@code .logs/118-jqref.log} of #118's red for the
 * step pin's 1000 and 2000.
 */
class TailCallKindsTest {
  /** ADR-0046: 10 000 000 levels on reference jq; #117's copy of until jumps to itself. */
  static final String UNTIL = "0|until(.>=10000000; .+1)";

  /** ADR-0047: a fresh closed closure at each jump. */
  static final String FRESH_CLOSURE = "def r(f): if .>=5000000 then f else (.+1)|r(.+1) end; 0|r(0)";

  /** ADR-0047: closure parameters passed on bare, permuted. */
  static final String PERMUTED = "def r(f;g): if .>=5000000 then [f,g] else (.+1)|r(g;f) end; 0|r(1;2)";

  static final String RECURSE = "last(limit(3000000; 0|recurse(.+1)))";

  static final String RANGE = "last(range(0;5000000;1))";

  static final String UNTIL_FREE_SLOT = "5000000 as $n|0|until(.>=$n; .+1)";

  static final String REPEAT = "last(limit(5000000; 0|repeat(1)))";

  /** TAIL_GROUP: a jumps to b and b to a. */
  static final String GROUP = "def a: def b: .+1|a; if .>=5000000 then . else .+1|b end; 0|a";

  static final String ALT_RIGHT = "def f: if . >= 5000000 then . else empty end // (.+1|f); 0|f";

  static final String BIND_BODY = "def f: if . >= 5000000 then . else (.+1) as $x | $x | f end; 0|f";

  /** T1: repeat's bare Comma-right call, with no left context, on reference jq {@code [2,2,2,2,2]}. */
  static final String REPEAT_SHALLOW = "[limit(5;1|repeat(.*2))]";

  /** The RECURSIVE pin of ADR-0047: {@code f|.} holds a ParamCall that is not passed on bare; jq-ref gives 1000000. */
  static final String CLOSURE_CHAIN = "def r(f): if .>=1000000 then f else (.+1)|r(f|.) end; 0|r(.)";

  /** {@link #CLOSURE_CHAIN}'s twin: the same call passes {@code f} on bare. */
  static final String PASSED_ON = "def r(f): if .>=1000000 then f else (.+1)|r(f) end; 0|r(.)";

  /** A free Break in the closure argument; jq-ref gives {@code [3]}. */
  static final String FREE_BREAK = "[label $out | def r(f): if . >= 3 then f else .+1 | r(., break $out) end; 0 | r(.)]";

  /** {@link #FREE_BREAK}'s twin: the same closure without the Break. */
  static final String NO_BREAK = "[label $out | def r(f): if . >= 3 then f else .+1 | r(., .) end; 0 | r(.)]";

  /** A Call inside a closure argument, and the call that passes it; jq-ref gives {@code [[[2,9],9],9]}. */
  static final String CALL_IN_CLOSURE = "def r(f): if .>=2 then [f,9] else .+1|r(r(f)) end; 0|r(.)";

  /** The MANY-valued Bind of ADR-0047 #118; jq-ref gives {@code [4,13,12,11,10]}. */
  static final String MANY_BIND = "def f: if . > 3 then . else ((.+1),(.+10)) as $x | $x | f end; [0|f]";

  /** A free slot: the closure argument reads the caller's bind slot {@code $x}. */
  static final String FREE_SLOT = "def r(f): if . >= 3 then f else . as $x | .+1 | r($x) end; [0|r(.)]";

  /** {@link #FREE_SLOT}'s twin: the same Bind, and a closure that reads no slot. */
  static final String NO_FREE_SLOT = "def r(f): if . >= 3 then f else . as $x | .+1 | r(.) end; [0|r(.)]";

  private static final Pattern STEPS = Pattern.compile(", (\\d+) steps$");

  private static List<Line> lines(CompileMode mode, String filter) {
    String text = JqEngine.builder().compileMode(mode).build().compile(filter).explain();
    assertThat(text).as("explain of %s under %s", filter, mode).endsWith("\n");
    return Arrays.stream(text.split("\n")).map(Line::of).toList();
  }

  /** A node line: its first field is its body's column, {@code main} or {@code fn<place>}. */
  private static boolean isNode(Line l) {
    return !l.fields().isEmpty() && l.fields().getFirst().matches("main|fn[0-9]+");
  }

  private static boolean isCall(Line l) {
    return isNode(l) && l.fields().get(2).equals("Call");
  }

  /**
   * The Call lines in the bodies of the functions named {@code caller}, only those of copies when {@code copies}, whose
   * callee is a function named {@code callee}.
   */
  private static List<Line> calls(List<Line> lines, String caller, String callee, boolean copies) {
    Map<String, String> names = new HashMap<>();
    Set<String> copied = new HashSet<>();
    for (Line l : lines) {
      if (!l.fields().isEmpty() && l.isFunction()) {
        names.put(l.fields().get(1), l.fields().get(2));
        if (l.facts().containsKey("copyOf")) {
          copied.add(l.fields().get(1));
        }
      }
    }
    return lines.stream()
        .filter(TailCallKindsTest::isCall)
        .filter(l -> caller.equals(names.get(l.fields().getFirst())))
        .filter(l -> !copies || copied.contains(l.fields().getFirst()))
        .filter(l -> callee.equals(names.get(l.facts().get("callee"))))
        .toList();
  }

  /** The call kinds of {@code calls}. */
  private static List<String> kinds(List<Line> calls) {
    return calls.stream().map(l -> l.facts().get("call")).toList();
  }

  static Stream<Arguments> tailCalls() {
    return Stream.of(
        Arguments.argumentSet("until's copy", UNTIL, "_until/0", "_until/0", true, "TAIL_SELF"),
        Arguments.argumentSet("a fresh closed closure", FRESH_CLOSURE, "r/1", "r/1", false, "TAIL_SELF"),
        Arguments.argumentSet("permuted closure parameters", PERMUTED, "r/2", "r/2", false, "TAIL_SELF"),
        Arguments.argumentSet("recurse's copy", RECURSE, "r/0", "r/0", true, "TAIL_SELF"),
        Arguments.argumentSet("range/3's copies of while", RANGE, "_while/0", "_while/0", true, "TAIL_SELF"),
        Arguments.argumentSet("until's copy with a trailing value", UNTIL_FREE_SLOT, "_until/0", "_until/0", true, "TAIL_SELF"),
        Arguments.argumentSet("repeat's copy", REPEAT, "_repeat/0", "_repeat/0", true, "TAIL_SELF"),
        Arguments.argumentSet("repeat's copy, shallow", REPEAT_SHALLOW, "_repeat/0", "_repeat/0", true, "TAIL_SELF"),
        Arguments.argumentSet("the right of an Alt", ALT_RIGHT, "f/0", "f/0", false, "TAIL_SELF"),
        Arguments.argumentSet("the body of a Bind of one value", BIND_BODY, "f/0", "f/0", false, "TAIL_SELF"),
        Arguments.argumentSet("a closure parameter passed on bare", PASSED_ON, "r/1", "r/1", false, "TAIL_SELF"),
        Arguments.argumentSet("a closure without a Break", NO_BREAK, "r/1", "r/1", false, "TAIL_SELF"),
        Arguments.argumentSet("a closure that reads no slot", NO_FREE_SLOT, "r/1", "r/1", false, "TAIL_SELF"),
        Arguments.argumentSet("a to b", GROUP, "a/0", "b/0", false, "TAIL_GROUP"),
        Arguments.argumentSet("b to a", GROUP, "b/0", "a/0", false, "TAIL_GROUP"));
  }

  /** In the optimised compile each selected call is a tail call and shows its kind. */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("tailCalls")
  void tailCallsShowTheirKind(String filter, String caller, String callee, boolean copies, String kind) {
    List<Line> calls = calls(lines(CompileMode.OPTIMISED, filter), caller, callee, copies);
    assertThat(calls).as("the Calls of %s%s to %s in %s", caller, copies ? "'s copies" : "", callee, filter).isNotEmpty();
    assertThat(kinds(calls)).as("call= of the Calls of %s to %s in %s", caller, callee, filter).containsOnly(kind);
  }

  static Stream<Arguments> recursiveCalls() {
    return Stream.of(
        Arguments.argumentSet("a closure holding a ParamCall, r(f|.)", CLOSURE_CHAIN, PASSED_ON, "r/1", 1),
        Arguments.argumentSet("a free Break", FREE_BREAK, NO_BREAK, "r/1", 1),
        Arguments.argumentSet("a Call inside a closure, and the call passing it", CALL_IN_CLOSURE, PASSED_ON, "r/1", 2),
        Arguments.argumentSet("a Bind of many values", MANY_BIND, BIND_BODY, "f/0", 1),
        Arguments.argumentSet("a free slot", FREE_SLOT, NO_FREE_SLOT, "r/1", 1));
  }

  /**
   * A call that fails one condition stays RECURSIVE in the optimised compile: {@code r(f|.)} (ADR-0047's pin, which
   * jq-ref runs 10^6 deep), a free Break, a Call inside a closure argument's body and the call that passes it, the body
   * of a Bind whose value has more than one result, and a free slot. Witness: the twin, where only that condition holds,
   * is TAIL_SELF.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("recursiveCalls")
  void callsThatFailAConditionStayRecursive(String filter, String twin, String function, int count) {
    List<Line> calls = calls(lines(CompileMode.OPTIMISED, filter), function, function, false);
    assertThat(kinds(calls)).as("call= of the Calls of %s to itself in %s", function, filter).hasSize(count).containsOnly("RECURSIVE");

    List<Line> twins = calls(lines(CompileMode.OPTIMISED, twin), function, function, false);
    assertThat(twins).as("witness: the Calls of %s to itself in the twin %s", function, twin).isNotEmpty();
    assertThat(kinds(twins)).as("witness: call= of the Calls of %s to itself in the twin %s", function, twin).containsOnly("TAIL_SELF");
  }

  static Stream<Arguments> everyFilter() {
    return Stream.of(
            UNTIL, FRESH_CLOSURE, PERMUTED, RECURSE, RANGE, UNTIL_FREE_SLOT, REPEAT, GROUP, ALT_RIGHT, BIND_BODY, REPEAT_SHALLOW,
            CLOSURE_CHAIN, PASSED_ON, FREE_BREAK, NO_BREAK, CALL_IN_CLOSURE, MANY_BIND, FREE_SLOT, NO_FREE_SLOT)
        .flatMap(f -> Stream.of(CompileMode.values()).map(m -> Arguments.argumentSet(m + " " + f, m, f)));
  }

  /** Main has no tail position: every Call of main is PLAIN, in both modes. */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("everyFilter")
  void callsFromMainArePlain(CompileMode mode, String filter) {
    List<String> main = lines(mode, filter).stream().filter(l -> isCall(l) && l.fields().getFirst().equals("main")).map(l -> l.facts().get("call")).toList();
    assertThat(main).as("call= of main's Calls in %s under %s", filter, mode).isNotEmpty().containsOnly("PLAIN");
  }

  /**
   * T1, ADR-0018: the conservative compile stores no tail kind, so every tail call above is RECURSIVE there, repeat's
   * {@code _repeat} included, whose bare Comma-right call has no left context. It makes no copy (ADR-0045), so the
   * selection takes every function of the name. Witness: the optimised compile shows the tail kind for the same call.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("tailCalls")
  void conservativeStoresNoTailKind(String filter, String caller, String callee, boolean copies, String kind) {
    List<Line> lines = lines(CompileMode.CONSERVATIVE, filter);
    assertThat(lines.stream().filter(TailCallKindsTest::isCall).map(l -> l.facts().get("call")).toList())
        .as("call= of every Call in %s under CONSERVATIVE", filter)
        .doesNotContain("TAIL_SELF", "TAIL_GROUP");
    List<Line> calls = calls(lines, caller, callee, false);
    assertThat(calls).as("the Calls of %s to %s in %s under CONSERVATIVE", caller, callee, filter).isNotEmpty();
    assertThat(kinds(calls)).as("call= of the Calls of %s to %s in %s under CONSERVATIVE", caller, callee, filter).containsOnly("RECURSIVE");

    assertThat(kinds(calls(lines(CompileMode.OPTIMISED, filter), caller, callee, copies)))
        .as("witness: call= of the same Calls under OPTIMISED")
        .contains(kind);
  }

  static Stream<Arguments> deep() {
    return Stream.of(
        Arguments.argumentSet("tail/until-ten-million", UNTIL, "[10000000], ran out"),
        Arguments.argumentSet("tail/fresh-closure-five-million", FRESH_CLOSURE, "[5000001], ran out"),
        Arguments.argumentSet("tail/permuted-closures-five-million", PERMUTED, "[[1,2]], ran out"),
        Arguments.argumentSet("tail/recurse-three-million", RECURSE, "[2999999], ran out"),
        Arguments.argumentSet("tail/range-3-five-million", RANGE, "[4999999], ran out"),
        Arguments.argumentSet("tail/until-free-slot-five-million", UNTIL_FREE_SLOT, "[5000000], ran out"),
        Arguments.argumentSet("tail/repeat-five-million", REPEAT, "[1], ran out"),
        Arguments.argumentSet("tail/group-five-million", GROUP, "[5000000], ran out"),
        Arguments.argumentSet("tail/alt-right-five-million", ALT_RIGHT, "[5000000], ran out"),
        Arguments.argumentSet("tail/bind-body-five-million", BIND_BODY, "[5000000], ran out"));
  }

  /**
   * The deep cases of diff/tail.cases in the optimised compile with {@code stackBytes} 0, where main and every body run
   * on the trampoline (ADR-0046), under a recursion-depth budget of 16: {@code CallOp.Active.resume} takes each jump on
   * the driver, so neither the driver's Java depth nor the live activations grow, and the run gives reference jq's
   * result on a {@value TrampolineTest#SMALL}-byte thread.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("deep")
  void tailJumpsOnTheTrampoline(String filter, String expected) {
    Limits cli = Limits.cli();
    JqEngine zero =
        JqEngine.builder()
            .limits(new Limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), 16, Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), 0))
            .compileMode(CompileMode.OPTIMISED)
            .build();
    assertThat(TrampolineTest.ended(zero, filter, "null", TrampolineTest.SMALL, false))
        .as("OPTIMISED run of %s with stackBytes 0 and recursionDepth 16", filter)
        .isEqualTo(expected);
  }

  /** The fact {@code name} of {@code line}, a whole number it must carry. */
  private static long fact(Line line, String name) {
    assertThat(line.facts()).as("the facts of %s", line.fields()).containsKey(name);
    return Long.parseLong(line.facts().get(name));
  }

  /** The one function line named {@code name}. */
  private static Line functionLine(List<Line> lines, String name) {
    List<Line> found = lines.stream().filter(l -> !l.fields().isEmpty() && l.isFunction() && l.fields().get(2).equals(name)).toList();
    assertThat(found).as("the function line of %s", name).hasSize(1);
    return found.getFirst();
  }

  /**
   * ADR-0046 #111 and ADR-0047 #118: a jump keeps the units of its activation, so a call into a group with TAIL_GROUP
   * sites is charged the group's largest leaf-emit depth. Main calls a, a jumps to b and b back to a, and b emits from
   * under a hundred Trys, at least a hundred frames more than a's leaf-emit depth: main's call of a holds at least b's
   * {@code emitDepth}. Witness: the conservative compile, which has no group jump, charges the same call a's own leaf-emit
   * depth, below b's.
   */
  @Test
  void groupJumpChargesDeepestEmit() {
    String filter = "def a: def b: if . >= 3 then " + "try(".repeat(100) + "." + ")".repeat(100) + " else .+1|a end; if . >= 3 then . else .+1|b end; 0|a";
    for (CompileMode mode : CompileMode.values()) {
      List<Line> lines = lines(mode, filter);
      long a = fact(functionLine(lines, "a/0"), "emitDepth");
      long b = fact(functionLine(lines, "b/0"), "emitDepth");
      assertThat(b - a).as("premise under %s: b's emitDepth %d against a's %d", mode, b, a).isGreaterThanOrEqualTo(100);
      List<Line> main = lines.stream().filter(l -> isCall(l) && l.fields().getFirst().equals("main")).toList();
      assertThat(main).as("main's Calls under %s", mode).hasSize(1);
      long units = fact(main.getFirst(), "units");
      if (mode == CompileMode.OPTIMISED) {
        assertThat(units).as("the units of main's call of a, whose group jumps, against b's emitDepth %d", b).isGreaterThanOrEqualTo(b);
      } else {
        assertThat(units).as("witness: the units of main's call of a under %s, which has no jump, against b's emitDepth %d", mode, b).isLessThan(b);
      }
    }
  }

  /** The step pin's filter, which reference jq gives n for. */
  private static String stepFilter(int n) {
    return "def f: if . >= " + n + " then . else .+1|f end; 0|f";
  }

  private static long steps(String ended) {
    Matcher m = STEPS.matcher(ended);
    assertThat(m.find()).as("the steps of %s", ended).isTrue();
    return Long.parseLong(m.group(1));
  }

  /**
   * A jump ticks one step (ADR-0047 #118, ADR-0008): f's recursive call is TAIL_SELF, so under a recursion-depth budget
   * of 16 the optimised run of 1000 and of 2000 levels completes only by jumping, and the 1000 levels more cost 1000
   * steps more. Each run ends as the conservative compile's, which calls RECURSIVE under the default budget: the same
   * result, the same end and the same {@code Workspace.steps()}.
   */
  @Test
  void jumpTicksOneStep() {
    String[] optimised = new String[2];
    int[] levels = {1000, 2000};
    for (int i = 0; i < levels.length; i++) {
      String filter = stepFilter(levels[i]);
      optimised[i] = TrampolineTest.ended(RunBudgets.recursionDepth(16, CompileMode.OPTIMISED), filter, "null", SpecialiseExplainTest.RUNNER_STACK, true);
      assertThat(optimised[i]).as("OPTIMISED run of %s with recursionDepth 16", filter).startsWith("[" + levels[i] + "], ran out, ");
      String conservative =
          TrampolineTest.ended(RunBudgets.recursionDepth(Limits.cli().recursionDepth(), CompileMode.CONSERVATIVE), filter, "null", SpecialiseExplainTest.RUNNER_STACK, true);
      assertThat(optimised[i]).as("OPTIMISED run of %s, against CONSERVATIVE's", filter).isEqualTo(conservative);
    }
    assertThat(steps(optimised[1]) - steps(optimised[0])).as("the steps of 1000 jumps more").isEqualTo(1000);
  }

  /**
   * ADR-0047 #118 (ADR-0018): the conservative compile's deep cases run on the trampoline, one live activation per level,
   * so a recursion-depth budget of 16 ends them; the differential cases pin this with {@code limit recursion-depth 16}.
   * Witness: the optimised compile of the same filter under the same budget gives reference jq's result.
   */
  @Test
  void conservativeEndsAtTheDepthBudget() {
    String filter = stepFilter(1000);
    assertThat(TrampolineTest.ended(RunBudgets.recursionDepth(16, CompileMode.CONSERVATIVE), filter, "null", SpecialiseExplainTest.RUNNER_STACK, false))
        .as("CONSERVATIVE run of %s with recursionDepth 16", filter)
        .isEqualTo("[], termination BUDGET RECURSION_DEPTH");
    assertThat(TrampolineTest.ended(RunBudgets.recursionDepth(16, CompileMode.OPTIMISED), filter, "null", SpecialiseExplainTest.RUNNER_STACK, false))
        .as("witness: OPTIMISED run of %s with recursionDepth 16", filter)
        .isEqualTo("[1000], ran out");
  }
}
