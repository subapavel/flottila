package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The slot rules of ADR-0038 at the IR seam, over plain objects (founding §14): a slot is bound by one Bind, by one
 * AltBind (its value slot and its group slots), or is a parameter; a group slot is read only in its AltBind's body; each
 * from-slot is in scope at its match's leaf; a parameter slot is in scope everywhere and bound by no node. A broken rule
 * is an IllegalStateException from compile, as for the rules of CommaBindTest.
 *
 * <p>Shapes the test-author fixed from ADR-0038 (no draft text reached the red): {@code AltBind(Source, Node value, int
 * slot, List<Alternative> alternatives, List<Integer> slots, Node body)} and {@code Alternative(Node match, List<Integer>
 * from)} in {@code query.ir}, {@code from} giving, for each group slot in {@code slots}' order, the alternative's own slot;
 * {@code Compiler.compile(Node, int parameters, CompileMode)}; {@code Executable.run(RunState, Object[] parameters,
 * Object in, Sink)}. A match is a chain of Binds whose innermost body is its leaf. No case here has an alternative
 * without a slot for some group slot, the "or null" of ADR-0038, whose form the ADR leaves open.
 *
 * <p>ADR-0043's {@code Binds(Source, List<Node> values, List<Integer> slots, Node body)}, n ≥ 2, has the slot rule of
 * the nested Binds it means.
 */
class IrVerifierTest {
  private static final Source AT = new Source(0, 1);

  private static final Fn1 ID = (state, in, a) -> a;

  private static Executable compile(Node root, int parameters) {
    return Compiler.compile(root, parameters, CompileMode.OPTIMISED);
  }

  private static Intrinsic id(Node arg) {
    return new Intrinsic(AT, new Operation("id", ID), List.of(arg));
  }

  private static Var var(int slot) {
    return new Var(AT, slot);
  }

  /** An alternative whose match binds slot {@code own} to {@code id($value)} and whose one from-slot is {@code own}. */
  private static Alternative binding(int value, int own) {
    return new Alternative(new Bind(AT, id(var(value)), own, new Identity(AT)), List.of(own));
  }

  /** {@code value as $0 ?// (two alternatives, own slots 1 and 3) | body}, with group slot 2. */
  private static AltBind chain(Node value, Node body) {
    return new AltBind(AT, value, 0, List.of(binding(0, 1), binding(0, 3)), List.of(2), body);
  }

  /** The results of {@code executable} on {@code in} with {@code parameters}; the sink never stops. */
  private static List<Object> results(Executable executable, Object[] parameters, Object in) {
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        executable.run(
            new RunState(),
            parameters,
            in,
            v -> {
              emitted.add(v);
              return true;
            });
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  @Test
  void aWellFormedAltBindCompilesAndItsBodyReadsTheGroupSlot() {
    // Witness for every rejection below: the group slot read in the body, each from-slot bound around its leaf.
    Object in = new Object();
    assertThat(results(compile(chain(new Identity(AT), id(var(2))), 0), new Object[0], in)).as("results of the well-formed chain").singleElement().isSameAs(in);
  }

  @Test
  void rejectsAGroupSlotReadInTheValue() {
    assertThatIllegalStateException().as("compile of a chain whose value reads its group slot").isThrownBy(() -> compile(chain(id(var(2)), id(var(2))), 0));
  }

  @Test
  void rejectsAGroupSlotReadAfterTheAltBind() {
    Comma after = new Comma(AT, chain(new Identity(AT), id(var(2))), id(var(2)));
    assertThatIllegalStateException().as("compile of (chain), id($2)").isThrownBy(() -> compile(after, 0));
  }

  @Test
  void rejectsAFromSlotNotInScopeAtItsLeaf() {
    // A from-slot no Bind of the match binds.
    AltBind unbound = new AltBind(AT, new Identity(AT), 0, List.of(new Alternative(new Identity(AT), List.of(1))), List.of(2), id(var(2)));
    assertThatIllegalStateException().as("compile of a match . whose from-slot is 1").isThrownBy(() -> compile(unbound, 0));

    // The second alternative names the first one's slot: bound in the tree, but not around the second leaf.
    Alternative wrong = new Alternative(new Bind(AT, id(var(0)), 3, new Identity(AT)), List.of(1));
    AltBind crossed = new AltBind(AT, new Identity(AT), 0, List.of(binding(0, 1), wrong), List.of(2), id(var(2)));
    assertThatIllegalStateException().as("compile of a second alternative whose from-slot is the first one's").isThrownBy(() -> compile(crossed, 0));

    // Witness: from its own slot, the same second alternative compiles.
    Alternative right = new Alternative(new Bind(AT, id(var(0)), 3, new Identity(AT)), List.of(3));
    assertThat(compile(new AltBind(AT, new Identity(AT), 0, List.of(binding(0, 1), right), List.of(2), id(var(2))), 0))
        .as("compile of a second alternative from its own slot")
        .isNotNull();
  }

  @Test
  void rejectsABindOfAParameterSlot() {
    assertThatIllegalStateException().as("compile of . as $0 | id($0) with one parameter").isThrownBy(() -> compile(new Bind(AT, new Identity(AT), 0, id(var(0))), 1));
    // Witnesses: with no parameter the same tree compiles, and with one a Bind of slot 1 does.
    assertThat(compile(new Bind(AT, new Identity(AT), 0, id(var(0))), 0)).as("compile of . as $0 | id($0) with no parameter").isNotNull();
    assertThat(compile(new Bind(AT, new Identity(AT), 1, id(var(1))), 1)).as("compile of . as $1 | id($1) with one parameter").isNotNull();
  }

  @Test
  void rejectsAnAltBindOfAParameterSlot() {
    // Its value slot 0 is the parameter.
    assertThatIllegalStateException().as("compile of a chain whose value slot is a parameter").isThrownBy(() -> compile(chain(new Identity(AT), id(var(2))), 1));
    // Its group slot 0 is the parameter (value slot 3, own slot 4).
    AltBind group = new AltBind(AT, new Identity(AT), 3, List.of(binding(3, 4)), List.of(0), id(var(0)));
    assertThatIllegalStateException().as("compile of a chain whose group slot is a parameter").isThrownBy(() -> compile(group, 1));
    // Witness: group slot 5 instead.
    assertThat(compile(new AltBind(AT, new Identity(AT), 3, List.of(binding(3, 4)), List.of(5), id(var(5))), 1)).as("compile of the chain with group slot 5").isNotNull();
  }

  @Test
  void rejectsAGroupSlotBoundAgain() {
    // An alternative's match binds the group slot itself.
    AltBind byMatch = new AltBind(AT, new Identity(AT), 0, List.of(binding(0, 2)), List.of(2), id(var(2)));
    assertThatIllegalStateException().as("compile of a match that binds the group slot").isThrownBy(() -> compile(byMatch, 0));
    // The value slot is also the group slot.
    AltBind byValue = new AltBind(AT, new Identity(AT), 2, List.of(binding(2, 1)), List.of(2), id(var(2)));
    assertThatIllegalStateException().as("compile of a chain whose value slot is its group slot").isThrownBy(() -> compile(byValue, 0));
    // A Bind beside the chain takes its group slot.
    Comma beside = new Comma(AT, chain(new Identity(AT), id(var(2))), new Bind(AT, new Identity(AT), 2, id(var(2))));
    assertThatIllegalStateException().as("compile of (chain), (. as $2 | id($2))").isThrownBy(() -> compile(beside, 0));
  }

  @Test
  void aParameterSlotIsReadAnywhereAndBoundByNoNode() {
    Object parameter = new Object();
    Object[] parameters = {parameter};
    assertThat(results(compile(id(var(0)), 1), parameters, new Object())).as("results of id($0) with $0 a parameter").singleElement().isSameAs(parameter);
    // In a Bind's value and body, and in a chain's match.
    Bind inner = new Bind(AT, id(var(0)), 1, id(var(1)));
    assertThat(results(compile(inner, 1), parameters, new Object())).as("results of id($0) as $1 | id($1)").singleElement().isSameAs(parameter);
    AltBind match = new AltBind(AT, new Identity(AT), 1, List.of(binding(0, 2)), List.of(3), id(var(3)));
    assertThat(results(compile(match, 1), parameters, new Object())).as("results of a chain whose match reads the parameter").singleElement().isSameAs(parameter);

    // Slot 1 is no parameter and no Bind takes it; with no parameter, slot 0 is not in scope either.
    assertThatIllegalStateException().as("compile of id($1) with one parameter").isThrownBy(() -> compile(id(var(1)), 1));
    assertThatIllegalStateException().as("compile of id($0) with no parameter").isThrownBy(() -> compile(id(var(0)), 0));
  }

  private static Const constant(Object value) {
    return new Const(AT, value);
  }

  /** The list of its arguments, any number of them. */
  private static Intrinsic list(Node... args) {
    FnN all = (state, in, a) -> List.of(a);
    return new Intrinsic(AT, new Operation("list", all), List.of(args));
  }

  /** {@code values[0] as $slots[0] | … | body} as one Binds (ADR-0043). */
  private static Binds binds(List<Node> values, List<Integer> slots, Node body) {
    return new Binds(AT, values, slots, body);
  }

  /** {@code a as $4 | b as $6 | list($4; $6)}, a well-formed Binds of two values over {@code a} and {@code b}. */
  private static Binds two(Object a, Object b) {
    return binds(List.of(constant(a), constant(b)), List.of(4, 6), list(var(4), var(6)));
  }

  /**
   * ADR-0043: a Binds means {@code Bind(v1, s1, … Bind(vn, sn, body))}, so slot k is in scope in the later values and
   * in the body, and nowhere else; the verifier rejects anything else, as it rejects a Var outside its Bind's body.
   */
  @Test
  void aBindsSlotIsInScopeInLaterValuesAndTheBodyOnly() {
    Object a = new Object();
    Object b = new Object();
    // A value reads its own slot: it runs before the slot is set.
    Binds own = binds(List.of(constant(a), list(var(6))), List.of(4, 6), list(var(4), var(6)));
    assertThatIllegalStateException().as("compile of a as $4 | list($6) as $6 | list($4; $6)").isThrownBy(() -> compile(own, 0));
    // A value reads a later value's slot.
    Binds later = binds(List.of(constant(a), list(var(8)), constant(b)), List.of(4, 6, 8), list(var(4), var(6), var(8)));
    assertThatIllegalStateException().as("compile of a as $4 | list($8) as $6 | b as $8 | list($4; $6; $8)").isThrownBy(() -> compile(later, 0));
    // The first value reads the last slot.
    Binds first = binds(List.of(list(var(6)), constant(b)), List.of(4, 6), list(var(4), var(6)));
    assertThatIllegalStateException().as("compile of list($6) as $4 | b as $6 | list($4; $6)").isThrownBy(() -> compile(first, 0));
    // A Var of a Binds slot after the Binds, and one before it.
    Comma after = new Comma(AT, two(a, b), id(var(6)));
    assertThatIllegalStateException().as("compile of (a as $4 | b as $6 | list($4; $6)), id($6)").isThrownBy(() -> compile(after, 0));
    Comma before = new Comma(AT, id(var(4)), two(a, b));
    assertThatIllegalStateException().as("compile of id($4), (a as $4 | b as $6 | list($4; $6))").isThrownBy(() -> compile(before, 0));
    // A later value reads the slot of a Bind inside an earlier value, which is in scope in that Bind's body only.
    Binds inner = binds(List.of(new Bind(AT, constant(a), 9, id(var(9))), list(var(9))), List.of(4, 6), list(var(4), var(6)));
    assertThatIllegalStateException().as("compile of (a as $9 | id($9)) as $4 | list($9) as $6 | list($4; $6)").isThrownBy(() -> compile(inner, 0));
    // Witness for the last: the inner Bind's body reads its slot, and the later value reads the earlier one's.
    Binds nested = binds(List.of(new Bind(AT, constant(a), 9, id(var(9))), list(var(4))), List.of(4, 6), list(var(4), var(6)));
    assertThat(results(compile(nested, 0), new Object[0], new Object())).as("results of (a as $9 | id($9)) as $4 | list($4) as $6 | list($4; $6)").containsExactly(List.of(a, List.of(a)));
    // Witness for the rest: the second value reads the first slot, the third both, and the body all three. Slots 4, 6
    // and 8 make a run of nine slots, so a slot the verifier did not count would be past the run's slots.
    Binds well = binds(List.of(constant(a), list(var(4)), list(var(6), var(4))), List.of(4, 6, 8), list(var(8), var(6), var(4)));
    assertThat(results(compile(well, 0), new Object[0], new Object()))
        .as("results of a as $4 | list($4) as $6 | list($6; $4) as $8 | list($8; $6; $4)")
        .containsExactly(List.of(List.of(List.of(a), a), List.of(a), a));
  }

  /** ADR-0043: a Binds has two values at least; one value is a Bind. */
  @Test
  void aBindsOfOneValueIsRejected() {
    Object a = new Object();
    Object b = new Object();
    Binds one = binds(List.of(constant(a)), List.of(4), list(var(4)));
    assertThatIllegalStateException().as("compile of a Binds of one value, a as $4 | list($4)").isThrownBy(() -> compile(one, 0));
    Binds none = binds(List.of(), List.of(), constant(a));
    assertThatIllegalStateException().as("compile of a Binds of no value").isThrownBy(() -> compile(none, 0));
    // Witnesses: the same one value as a Bind compiles, and a Binds of two values, the fewest, runs.
    assertThat(results(compile(new Bind(AT, constant(a), 4, list(var(4))), 0), new Object[0], new Object())).as("results of the Bind a as $4 | list($4)").containsExactly(List.of(a));
    assertThat(results(compile(two(a, b), 0), new Object[0], new Object())).as("results of a as $4 | b as $6 | list($4; $6)").containsExactly(List.of(a, b));
  }

  /**
   * ADR-0043: a Binds has one slot per value, the value k sets slot k; values and slots of different lengths are
   * rejected, more values than slots and more slots than values, whatever the verifier walks the Binds by.
   */
  @Test
  void aBindsOfValuesAndSlotsOfDifferentLengthsIsRejected() {
    Object a = new Object();
    Object b = new Object();
    // A third value with no slot of its own.
    Binds values = binds(List.of(constant(a), constant(b), constant(a)), List.of(4, 6), list(var(4), var(6)));
    assertThatIllegalStateException().as("compile of a Binds of three values and two slots").isThrownBy(() -> compile(values, 0));
    // A third slot with no value; the body does not read it, so only the lengths are wrong.
    Binds slots = binds(List.of(constant(a), constant(b)), List.of(4, 6, 8), list(var(4), var(6)));
    assertThatIllegalStateException().as("compile of a Binds of two values and three slots").isThrownBy(() -> compile(slots, 0));
    // Witness: two values and two slots, the same body, run.
    assertThat(results(compile(two(a, b), 0), new Object[0], new Object())).as("results of a as $4 | b as $6 | list($4; $6)").containsExactly(List.of(a, b));
  }

  /**
   * ADR-0043 (#93, the #92 hand-over): each alternative's match of a wide pattern is {@code Binds(steps, Identity)}, so
   * the leaf of a match is the innermost body of its chain of Bind and Binds nodes, where every slot of that chain is in
   * scope and a from-slot may name it.
   */
  @Test
  void anAlternativeWhoseMatchIsABindsReadsItsSlotsAtItsLeaf() {
    Object in = new Object();
    Object b = new Object();
    Object c = new Object();
    // . as $0 ?// (id($0) as $4 | b as $6 | .) -> [$4, $6] as [$2, $3] | list($2; $3)
    Binds steps = binds(List.of(id(var(0)), constant(b)), List.of(4, 6), new Identity(AT));
    AltBind wide = new AltBind(AT, new Identity(AT), 0, List.of(new Alternative(steps, List.of(4, 6))), List.of(2, 3), list(var(2), var(3)));
    assertThat(results(compile(wide, 0), new Object[0], in)).as("results of a chain whose match is Binds(steps, Identity)").containsExactly(List.of(in, b));
    // A Bind inside the Binds: (id($0) as $4 | b as $6 | c as $8 | .) -> [$4, $8].
    Binds outer = binds(List.of(id(var(0)), constant(b)), List.of(4, 6), new Bind(AT, constant(c), 8, new Identity(AT)));
    AltBind bindInside = new AltBind(AT, new Identity(AT), 0, List.of(new Alternative(outer, List.of(4, 8))), List.of(2, 3), list(var(2), var(3)));
    assertThat(results(compile(bindInside, 0), new Object[0], in)).as("results of a chain whose match is a Binds around a Bind").containsExactly(List.of(in, c));
    // A Binds inside a Bind: (id($0) as $4 | (b as $6 | c as $8 | .)) -> [$4, $8].
    Bind around = new Bind(AT, id(var(0)), 4, binds(List.of(constant(b), constant(c)), List.of(6, 8), new Identity(AT)));
    AltBind bindsInside = new AltBind(AT, new Identity(AT), 0, List.of(new Alternative(around, List.of(4, 8))), List.of(2, 3), list(var(2), var(3)));
    assertThat(results(compile(bindsInside, 0), new Object[0], in)).as("results of a chain whose match is a Bind around a Binds").containsExactly(List.of(in, c));
  }

  /**
   * The twin of {@link #anAlternativeWhoseMatchIsABindsReadsItsSlotsAtItsLeaf}: a slot bound inside a value of the
   * match's Binds, by that value's own Bind, is in scope in that Bind's body only, not at the leaf, so a from-slot that
   * names it is rejected, as a from-slot no Bind of the match holds is.
   */
  @Test
  void rejectsAFromSlotBoundOnlyInsideAValueOfTheMatchsBinds() {
    Object a = new Object();
    Object b = new Object();
    // ((a as $9 | id($9)) as $4 | b as $6 | .) -> [$9, $6]
    Binds steps = binds(List.of(new Bind(AT, constant(a), 9, id(var(9))), constant(b)), List.of(4, 6), new Identity(AT));
    AltBind inner = new AltBind(AT, new Identity(AT), 0, List.of(new Alternative(steps, List.of(9, 6))), List.of(2, 3), list(var(2), var(3)));
    assertThatIllegalStateException().as("compile of a match whose from-slot 9 is bound inside a value of its Binds").isThrownBy(() -> compile(inner, 0)).withMessageContaining("from-slot 9");
    // Witness: the same match from the Binds' own slot 4 runs, $4 the inner Bind's result.
    Binds same = binds(List.of(new Bind(AT, constant(a), 9, id(var(9))), constant(b)), List.of(4, 6), new Identity(AT));
    AltBind own = new AltBind(AT, new Identity(AT), 0, List.of(new Alternative(same, List.of(4, 6))), List.of(2, 3), list(var(2), var(3)));
    assertThat(results(compile(own, 0), new Object[0], new Object())).as("results of the same match from its Binds' slots 4 and 6").containsExactly(List.of(a, b));
  }

  /** ADR-0034's rule holds for a Binds slot as for a Bind's: a slot is bound by one node of the tree, or is a parameter. */
  @Test
  void aBindsSlotIsBoundOnceInTheTree() {
    Object a = new Object();
    Object b = new Object();
    Binds twice = binds(List.of(constant(a), constant(b)), List.of(4, 4), list(var(4)));
    assertThatIllegalStateException().as("compile of a as $4 | b as $4 | list($4)").isThrownBy(() -> compile(twice, 0));
    Binds again = binds(List.of(constant(a), constant(b)), List.of(4, 6), new Bind(AT, constant(a), 4, list(var(4), var(6))));
    assertThatIllegalStateException().as("compile of a as $4 | b as $6 | a as $4 | list($4; $6)").isThrownBy(() -> compile(again, 0));
    Binds parameter = binds(List.of(constant(a), constant(b)), List.of(0, 6), list(var(0), var(6)));
    assertThatIllegalStateException().as("compile of a as $0 | b as $6 | list($0; $6) with $0 a parameter").isThrownBy(() -> compile(parameter, 1));
    // Witness: slots 1 and 6 beside the parameter slot 0, which every value and the body may read.
    Object p = new Object();
    Binds beside = binds(List.of(list(var(0)), constant(b)), List.of(1, 6), list(var(0), var(1), var(6)));
    assertThat(results(compile(beside, 1), new Object[] {p}, new Object()))
        .as("results of list($0) as $1 | b as $6 | list($0; $1; $6) with $0 a parameter")
        .containsExactly(List.of(p, List.of(p), b));
  }
}
