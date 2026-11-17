package dev.rail8.jq.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.ParamCall;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A closure argument's frame (ADR-0045 #107 "Frames"): one that binds a slot runs in a frame of its own whose cell 0
 * links the frame it was written in, so a bare Var of a caller's slot in it reads that slot one frame out, beside the
 * closure's own slot, at each of its call activations.
 */
class ClosureFramesTest {
  private static final Source AT = new Source(0, 1);

  @Test
  void aBareVarInABindingClosureReadsTheCallersSlot() {
    Object a = new Object();
    Object b = new Object();
    Object own = new Object();
    // a as $1 | f(b as $2 | $1, $2), with f(g): own as $1 | g, g.
    Node closure = new Bind(AT, new Const(AT, b), 2, new Comma(AT, new Var(AT, 1), new Var(AT, 2)));
    Node main = new Bind(AT, new Const(AT, a), 1, new Call(AT, 0, List.of(closure), List.of()));
    Node body = new Bind(AT, new Const(AT, own), 1, new Comma(AT, new ParamCall(AT, 0), new ParamCall(AT, 0)));
    Executable run = Compiler.compile(new Program(main, List.of(new Function(AT, 1, 0, body))), 0, CompileMode.OPTIMISED);
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        run.run(
            new RunState(),
            new Object[0],
            new Object(),
            v -> {
              emitted.add(v);
              return true;
            });
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
    assertThat(emitted).as("results of a as $1 | f(b as $2 | $1, $2) with f(g): own as $1 | g, g").containsExactly(a, b, a, b);
  }
}
