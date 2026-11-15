package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.MARK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.Compiler;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Program;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;

/**
 * The Resolver's lifting (ADR-0045 #107 "Parameters", GLOSSARY "Lifted function", "Capture"): a call into a recursive
 * group of the filter's text is a Call of its function, whose parameters are its own, then its captures in Binder
 * order, each value parameter's argument bound around the Call, the first outermost. Every program here passes the
 * IrVerifier, which checks each Call's closure and value counts against its function's.
 */
class LiftedFunctionsTest {

  /** The program of {@code text}, which the Compiler accepts: its verifier checked it. */
  private static Program program(String text) {
    Program program = Resolver.resolve(Parser.parse(text));
    assertThatCode(() -> Compiler.compile(program, 0, CompileMode.OPTIMISED)).as("compile of %s", text).doesNotThrowAnyException();
    return program;
  }

  /**
   * Own parameters first, a filter parameter a closure and {@code $a} a value, then the captures in the order the
   * walk met them, {@code $y} before {@code $x}: value slots 0 ($a), 1 ($y) and 2 ($x). The caller binds 5 around the
   * Call and passes its own slots of $y and $x; the recursive call passes its closure parameter on as its ParamCall.
   */
  @Test
  void ownParametersComeFirstThenTheCapturesInWalkOrder() {
    Program p = program("1 as $x | 2 as $y | def r(f; $a): if . > 2 then [$y, $x, f, $a] else (. + 1 | r(f; $a)) end; 0 | r(.; 5)");
    assertThat(p.functions()).as("functions").hasSize(1);
    Function r = p.functions().getFirst();
    assertThat(r.closures()).as("closures of r").isEqualTo(1);
    assertThat(r.values()).as("values of r").isEqualTo(3);
  }

  /** A label the definition is written inside is a value capture, its token: the body's break names its slot. */
  @Test
  void aCapturedLabelIsAValueParameter() {
    Program p = program("[label $l | def f: if . > 2 then break $l else (. + 1 | f) end; 0 | f]");
    Function f = p.functions().getFirst();
    assertThat(f.closures()).as("closures of f").isZero();
    assertThat(f.values()).as("values of f").isEqualTo(1);
  }

  /**
   * A value parameter the body calls by its name is a value and a closure, and a variable a callee reads is a capture
   * of its caller: r calls h, which reads $x, so r captures $x. The recursive call passes {@code $n - 1} twice, bound
   * as the value and written as the closure.
   */
  @Test
  void aCalledValueParameterIsAlsoAClosureAndACalleeCaptureIsTheCallers() {
    Program p = program("1 as $x | def h: $x; def r($n): if $n > 0 then [n, h] else r($n - 1) end; r(1)");
    Function r = p.functions().getFirst();
    assertThat(r.closures()).as("closures of r").isEqualTo(1);
    assertThat(r.values()).as("values of r").isEqualTo(2);
  }

  /**
   * Mutual recursion through a nested definition: ev is placed first, od second, and each body calls the other by its
   * place.
   */
  @Test
  void aGroupHasAFunctionPerMember() {
    Program p = program("def ev: def od: if . == 0 then false else . - 1 | ev end; if . == 0 then true else . - 1 | od end; ev");
    assertThat(p.functions()).as("functions").hasSize(2);
  }

  /** The text of a recursive definition of {@code n} value parameters, and a call of it on 1 for each. */
  private static String valueParameters(int n) {
    StringJoiner params = new StringJoiner("; ");
    StringJoiner passed = new StringJoiner("; ");
    StringJoiner ones = new StringJoiner("; ");
    for (int i = 0; i < n; i++) {
      params.add("$a" + i);
      passed.add(i == 0 ? "$a0 - 1" : "$a" + i);
      ones.add("1");
    }
    return "def r(" + params + "): if $a0 == 0 then 0 else r(" + passed + ") end; r(" + ones + ")";
  }

  /** ADR-0043: up to {@code Resolver.BIND_RUN} value parameters are nested Binds around the Call, past it one Binds. */
  @Test
  void pastTheBindRunTheValueArgumentsAreOneBinds() {
    assertThat(program(valueParameters(Resolver.BIND_RUN)).main()).as("main of %d value parameters", Resolver.BIND_RUN).isInstanceOf(Bind.class);
    Program wide = program(valueParameters(Resolver.BIND_RUN + 1));
    assertThat(wide.main()).as("main of %d value parameters", Resolver.BIND_RUN + 1).isInstanceOf(Binds.class);
    assertThat(((Binds) wide.main()).values()).as("values of the Binds").hasSize(Resolver.BIND_RUN + 1);
  }

  /**
   * A function's body is a definition's body, inside an expansion (ADR-0040 "Inline cap"): its nodes spend the cap,
   * so a body of more than {@code Resolver.INLINE_CAP} nodes is a Stub, while the same nodes in the main filter, inside
   * no expansion, spend nothing.
   */
  @Test
  void aFunctionBodySpendsTheInlineCap() {
    String nodes = "." + ",.".repeat(Resolver.INLINE_CAP);
    assertThatCode(() -> Resolver.resolve(Parser.parse("if . then 1 else " + nodes + " end"))).as("the nodes in the main filter").doesNotThrowAnyException();
    assertThatExceptionOfType(JqCompileException.class)
        .as("the nodes in a recursive body")
        .isThrownBy(() -> Resolver.resolve(Parser.parse("def r: if . then r else " + nodes + " end; r")))
        .withMessageStartingWith(MARK + "a filter that inlines more than " + Resolver.INLINE_CAP + " nodes");
  }
}
