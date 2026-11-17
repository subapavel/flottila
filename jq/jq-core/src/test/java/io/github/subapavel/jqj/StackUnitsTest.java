package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.CallRuns.F;
import static io.github.subapavel.jqj.CallRuns.onStack;
import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.StackUnits;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Stack units and the switch at {@code U_f} at the public API (#111, ADR-0046 and its #111 amendment, ADR-0011): a
 * run derives {@code U_f = floorDiv(stackBytes - reserve, b) - D_f} at its start, and a call whose units reach
 * {@code U_f} starts the trampoline and becomes its starter. The {@code stackBytes} of a wanted {@code U_f} come from
 * the filter's own {@link JqFilter#explain()}: its closing line's {@code callFreeDepth} ({@code D_f}),
 * {@code bytesPerUnit} ({@code b}) and {@code reserve}, so {@code stackBytes = reserve + b * (U_f + D_f)}.
 *
 * <p>Every run is on a thread of four times its declared {@code stackBytes} (ADR-0046 "Where the stack is tested"),
 * at least {@value #SMALL} bytes. Each control runs the same filter on the same thread with a declared stack of 1 GiB,
 * which keeps every level on the Java stack, and ends with STACK there; so a result shows the switch.
 *
 * <p>Expected results are reference jq 1.8.2's: diff/deep.rec (deep/downstream-*, recorded by oracle-record.sh for
 * #111) and F on N gives N (F-87).
 */
class StackUnitsTest {
  /** The floor of a run's thread: TrampolineTest's small stack, which holds a run with {@code stackBytes} 0. */
  private static final long SMALL = 256 * 1024;

  /** A declared stack far larger than any thread here: every level stays on the Java stack. */
  private static final long GIB = 1L << 30;

  /** ADR-0046 M4 at depth 10 000: an error downstream of a result goes back to the heap AltBind that produced it. */
  static final String M4_ERROR = "def f: if .==0 then ([[1]]|.[] as [$a] ?// $a|$a) else (.-1|f)|. end; [10000|f|if .==1 then error(\"x\") else . end]";

  /** ADR-0046 M4 at depth 10 000: a break downstream of a result stops the heap forms. */
  static final String M4_BREAK = "[label $out | def f: if .==0 then (1,2) else (.-1|f)|. end; 10000|f|., break $out]";

  /** The closing line of {@code explain()}: {@code D_f}, {@code T}, {@code b} and the reserve. */
  record Constants(long callFreeDepth, long starterUnits, long bytesPerUnit, long reserve) {
    /** The {@code stackBytes} that give {@code unitLimit} as {@code U_f}. */
    long stackBytesFor(long unitLimit) {
      return reserve + bytesPerUnit * (unitLimit + callFreeDepth);
    }
  }

  /**
   * The facts of the one line of {@code filter}'s explain text that carries {@code callFreeDepth}; asserts it carries
   * the other three and that each is a whole number, {@code b} and the reserve positive.
   */
  static Constants constants(String filter, CompileMode mode) {
    String text = engine(Limits.cli().stackBytes(), mode).compile(filter).explain();    List<String> closing = Arrays.stream(text.split("\n")).filter(l -> l.contains("callFreeDepth=")).toList();
    assertThat(closing).as("%s: the closing line of explain with callFreeDepth, in:%n%s", mode, text).hasSize(1);
    Map<String, String> facts = new HashMap<>();
    for (String field : closing.getFirst().split(" ")) {
      int eq = field.indexOf('=');
      if (eq > 0) facts.put(field.substring(0, eq), field.substring(eq + 1));
    }
    assertThat(facts).as("%s: the closing line's facts", mode).containsKeys("callFreeDepth", "starterUnits", "bytesPerUnit", "reserve");
    Constants c =
        new Constants(
            Long.parseLong(facts.get("callFreeDepth")),
            Long.parseLong(facts.get("starterUnits")),
            Long.parseLong(facts.get("bytesPerUnit")),
            Long.parseLong(facts.get("reserve")));
    assertThat(c.bytesPerUnit()).as("%s: b", mode).isPositive();
    // ADR-0046 #113 (K6): the reserve explain() shows is the release constant.
    assertThat(c.reserve()).as("%s: the reserve", mode).isEqualTo(StackUnits.reserve());
    return c;
  }

  /** An engine of {@link Limits#cli()} whose declared stack is {@code stackBytes}, compiling in {@code mode}. */
  static JqEngine engine(long stackBytes, CompileMode mode) {
    Limits cli = Limits.cli();
    return JqEngine.builder()
        .limits(new Limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), cli.recursionDepth(), Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), stackBytes))
        .compileMode(mode)
        .build();
  }

  /** The thread of a run that declares {@code stackBytes}: four times that, at least {@value #SMALL}. */
  static long thread(long stackBytes) {
    return Math.max(4 * stackBytes, SMALL);
  }

  /**
   * How {@code filter} on {@code json} ended under {@code engine} on a new thread of {@code threadBytes}: its result
   * texts, then {@code ran out}, {@code stopped}, {@code error} and the error value's JSON, or {@code termination} and
   * its cause.
   */
  static String ended(JqEngine engine, String filter, String json, long threadBytes) {
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
          outcome.set(results.values.stream().map(JqValue::toJson).toList() + ", " + end);
        });
    return outcome.get();
  }

  /** Runs {@code filter} at the {@code stackBytes} of {@code unitLimit}, then the control on the same thread. */
  private static void assertSwitches(CompileMode mode, String filter, String json, long unitLimit, String expected) {
    long stackBytes = constants(filter, mode).stackBytesFor(unitLimit);
    long thread = thread(stackBytes);
    assertThat(ended(engine(stackBytes, mode), filter, json, thread))
        .as("%s: %s on %s at U_f %d (stackBytes %d, thread %d)", mode, filter, json, unitLimit, stackBytes, thread)
        .isEqualTo(expected);
    assertThat(ended(engine(GIB, mode), filter, json, thread))
        .as("%s: the control, %s on %s with a declared stack of 1 GiB on a thread of %d", mode, filter, json, thread)
        .isEqualTo("[], termination STACK");
  }

  /**
   * ADR-0046 #109 and #111: at {@code U_f} 1 every call starts the trampoline, so each M4 event crosses a real starter:
   * the error raised downstream of the starter's emit goes back into the driver to the heap AltBind, and the break
   * stops the heap forms.
   */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void m4AtUnitLimitOne(CompileMode mode) {
    assertSwitches(mode, M4_ERROR, "null", 1, "[[[1]]], ran out");
    assertSwitches(mode, M4_BREAK, "null", 1, "[[1]], ran out");
  }

  /**
   * At {@code U_f} 64 the first levels run in push form until a call's units reach 64, then that call starts the
   * trampoline: the M4 events cross the starter with push-form levels under it.
   */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void m4UnderPushLevels(CompileMode mode) {
    assertSwitches(mode, M4_ERROR, "null", 64, "[[[1]]], ran out");
    assertSwitches(mode, M4_BREAK, "null", 64, "[[1]], ran out");
  }

  /**
   * The edges of {@code U_f = floorDiv(stackBytes - reserve, b) - D_f}: {@code U_f} 0, where the run entry is the
   * starter; {@code U_f} 1; one byte under {@code U_f} 1, which rounds down to 0; one byte under {@code U_f} 0, which
   * rounds down to -1; a declared stack under the reserve; and 0, which is zero bytes, never unlimited. F on 9999
   * completes at each.
   */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void unitLimitBoundary(CompileMode mode) {
    Constants c = constants(F, mode);
    Map<String, Long> declared =
        Map.of(
            "U_f 0", c.stackBytesFor(0),
            "U_f 1", c.stackBytesFor(1),
            "one byte under U_f 1", c.stackBytesFor(1) - 1,
            "one byte under U_f 0", c.stackBytesFor(0) - 1,
            "one byte under the reserve", c.reserve() - 1,
            "stackBytes 0", 0L);
    declared.forEach(
        (what, stackBytes) -> {
          long thread = thread(stackBytes);
          assertThat(ended(engine(stackBytes, mode), F, "9999", thread))
              .as("%s: F on 9999 at %s (stackBytes %d, thread %d)", mode, what, stackBytes, thread)
              .isEqualTo("[9999], ran out");
        });
    long thread = thread(c.stackBytesFor(1));
    assertThat(ended(engine(GIB, mode), F, "9999", thread)).as("%s: the control, F on 9999 with a declared stack of 1 GiB on a thread of %d", mode, thread).isEqualTo("[], termination STACK");
  }
}
