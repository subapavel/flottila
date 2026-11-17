package io.github.subapavel.jqj.query.internal.interpret;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.Compiler;
import io.github.subapavel.jqj.query.compile.Executable;
import io.github.subapavel.jqj.query.compile.StackUnits;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Heap forms that neither {@link TrampolineTest} nor the seeded property of {@code query.compile} reaches (ADR-0046
 * and its #109 amendment): each program runs with {@code stackBytes} 0, on the trampoline, and must end as its push
 * form does, with the same results, answer and steps, in both compile modes; each test also pins the push form's end.
 * The push run declares {@link #PUSH}, and every run is on a thread of four times that.
 */
class HeapFormsTest {
  private static final Source AT = new Source(0, 1);
  private static final long UNLIMITED = Long.MAX_VALUE;

  /**
   * The push run's declared stack: these programs' calls hold far fewer units than its unit limit. It is above the
   * reserve, 280 KiB since #113, or every run, the push run too, would start on the trampoline.
   */
  private static final long PUSH = 512 * 1024;

  /** The thread of every run: four times the push run's declared stack (ADR-0046 "Where the stack is tested"). */
  private static final long THREAD = 4 * PUSH;

  /**
   * The results, the answer and the steps of {@code program} run on a fresh input, declaring {@code stackBytes}, on a
   * new thread of {@link #THREAD} bytes.
   */
  private static String ran(Executable program, long stackBytes) {
    AtomicReference<String> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              RunState state = new RunState();
              state.begin(UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, stackBytes);
              List<Object> seen = new ArrayList<>();
              boolean answer =
                  program.run(
                      state,
                      new Object[0],
                      "in",
                      v -> {
                        seen.add(v);
                        return true;
                      });
              outcome.set("results " + seen + ", answer " + answer + ", " + state.steps() + " steps");
            },
            "heap-forms",
            THREAD);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return outcome.get();
  }

  private static void assertSameAsPushForm(Executable program, String expected, String what) {
    assertThat(PUSH).as("the push run's declared stack against the reserve: at or under it, the push run starts on the trampoline too").isGreaterThan(StackUnits.reserve());
    String pushed = ran(program, PUSH);
    assertThat(pushed).as("push form of %s", what).startsWith(expected);
    assertThat(ran(program, 0)).as("%s on the trampoline", what).isEqualTo(pushed);
  }

  /** A closure that binds a slot runs in a frame of its own, so its read of the caller's slot is an outer Var. */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBindingClosureReadsTheCallersSlotOnTheTrampoline(CompileMode mode) {
    // "a" as $1 | f("b" as $2 | $1, $2), with f(g): "own" as $1 | g, g.
    Node closure = new Bind(AT, new Const(AT, "b"), 2, new Comma(AT, new Var(AT, 1), new Var(AT, 2)));
    Node main = new Bind(AT, new Const(AT, "a"), 1, new Call(AT, 0, List.of(closure), List.of()));
    Node body = new Bind(AT, new Const(AT, "own"), 1, new Comma(AT, new ParamCall(AT, 0), new ParamCall(AT, 0)));
    Executable program = Compiler.compile(new Program(main, List.of(new Function(AT, 1, 0, body))), 0, mode);
    assertSameAsPushForm(program, "results [a, b, a, b], answer true", "a binding closure reading the caller's slot");
  }

  /** A break on the left of an Alt before any result passed stops the Alt without its right side (ADR-0039). */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aBreakOnTheLeftOfAnAltSkipsItsRight(CompileMode mode) {
    // label $0 | (break $0) // "right", "after"
    Node alt = new Alt(AT, new Break(AT, 0), new Const(AT, "right"), "right"::equals);
    Executable program = Compiler.compile(new Comma(AT, new Label(AT, 0, alt), new Const(AT, "after")), mode);
    assertSameAsPushForm(program, "results [after], answer true", "a break on the left of an Alt");
  }
}
