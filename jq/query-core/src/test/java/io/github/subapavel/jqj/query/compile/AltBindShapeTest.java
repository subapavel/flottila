package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shape of an AltBind at the IR seam (ADR-0038 and its rulings on the red), over plain objects (founding §14): an
 * Alternative's from entry of -1 sets its group slot to null, whatever an earlier result left there; an AltBind has an
 * alternative at least, and each alternative one from entry per group slot, or compile rejects the tree with an
 * IllegalStateException.
 */
class AltBindShapeTest {
  private static final Source AT = new Source(0, 1);

  private static final int VALUE = 0;
  private static final int GROUP = 5;

  private static Executable compile(Node root) {
    return Compiler.compile(root, CompileMode.OPTIMISED);
  }

  private static List<Object> results(Node root, Object in) {
    List<Object> emitted = new ArrayList<>();
    assertThat(compile(root).run(new RunState(), in, emitted::add)).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  private static Intrinsic constant(Object value) {
    return new Intrinsic(AT, new Operation("constant", (Fn0) (state, in) -> value), List.of());
  }

  /** The first element of a non-empty list; a FilterException of its argument on anything else. */
  private static Intrinsic head(Node arg) {
    Fn1 head =
        (state, in, a) -> {
          if (a instanceof List<?> list && !list.isEmpty()) return list.get(0);
          throw new FilterException(a);
        };
    return new Intrinsic(AT, new Operation("head", head), List.of(arg));
  }

  /** An alternative whose match binds {@code own} to the head of the value. */
  private static Alternative heads(int own) {
    return new Alternative(new Bind(AT, head(new Var(AT, VALUE)), own, new Identity(AT)), List.of(own));
  }

  @Test
  void aFromEntryOfMinusOneSetsTheGroupSlotToNull() {
    // The first value [x] matches the first alternative, which sets the group slot to x; the second value y matches
    // only the second, whose from entry -1 sets it to null, not to the x the first result left.
    Object x = new Object();
    Object y = new Object();
    Node value = new Comma(AT, constant(List.of(x)), constant(y));
    AltBind chain = new AltBind(AT, value, VALUE, List.of(heads(1), new Alternative(new Identity(AT), List.of(Alternative.NULL))), List.of(GROUP), new Var(AT, GROUP));
    assertThat(results(chain, new Object())).as("the group slot after each value").containsExactly(x, null);
  }

  @Test
  void rejectsAnAltBindWithNoAlternative() {
    AltBind none = new AltBind(AT, new Identity(AT), VALUE, List.of(), List.of(GROUP), new Var(AT, GROUP));
    assertThatIllegalStateException().as("compile of a chain with no alternative").isThrownBy(() -> compile(none));
  }

  @Test
  void rejectsAnAlternativeWithoutOneFromEntryPerGroupSlot() {
    Alternative two = new Alternative(new Bind(AT, head(new Var(AT, VALUE)), 1, new Identity(AT)), List.of(1, 1));
    AltBind more = new AltBind(AT, new Identity(AT), VALUE, List.of(two), List.of(GROUP), new Var(AT, GROUP));
    assertThatIllegalStateException().as("compile of an alternative with two from entries for one group slot").isThrownBy(() -> compile(more));

    Alternative none = new Alternative(new Bind(AT, head(new Var(AT, VALUE)), 1, new Identity(AT)), List.of());
    AltBind fewer = new AltBind(AT, new Identity(AT), VALUE, List.of(none), List.of(GROUP), new Var(AT, GROUP));
    assertThatIllegalStateException().as("compile of an alternative with no from entry for one group slot").isThrownBy(() -> compile(fewer));

    // Witness: one from entry per group slot compiles and runs.
    Object x = new Object();
    AltBind one = new AltBind(AT, new Identity(AT), VALUE, List.of(heads(1)), List.of(GROUP), new Var(AT, GROUP));
    assertThat(results(one, List.of(x))).as("results of the chain with one from entry").containsExactly(x);
  }
}
