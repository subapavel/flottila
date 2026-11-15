package io.flottila.mapping.ir;

import static io.flottila.mapping.ir.FieldLinkIr.A1;
import static io.flottila.mapping.ir.FieldLinkIr.ORDER;
import static io.flottila.mapping.ir.FieldLinkIr.T1;
import static io.flottila.mapping.ir.FieldLinkIr.document;
import static io.flottila.mapping.ir.FieldLinkIr.rootSlot;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The verifier on {@link TargetRepeat} (ADR-0006; #63): at least one copy, an {@code INTEGER}
 * parent slot, content as its body, and its slot number in scope as an {@code INTEGER} only in its
 * body. Every refused document differs from the accepted one in the one defect it names. Since #83
 * a generating structure field's value is an optional {@code STRING} in its parent's scope, which
 * does not hold its own slot number (ADR-0006 as amended by #83).
 */
class TargetRepeatVerifierTest {

  private static final Name PAIR = new Name("", "Pair");
  private static final Name V = new Name("", "V");
  private static final AuthoringId PAIR_ORIGIN = new AuthoringId("/Order/Pair");

  /** V per slot of context {@code parentSlot} of V's queue in Pair's context. */
  private static TargetSlots values(Expr parentSlot) {
    return new TargetSlots(
        new SourceQueue(List.of(ORDER, PAIR, V), 2, A1),
        parentSlot,
        0,
        Long.MAX_VALUE,
        "vSlot",
        "v",
        new ElementConstructor(
            "", V, List.of(new TextConstructor(new VarRef("v", AtomicType.STRING, T1), T1)), T1),
        T1);
  }

  private static TargetRepeat pairs(long copies, Expr parentSlot, Expr body) {
    return new TargetRepeat(copies, parentSlot, "pairSlot", body, PAIR_ORIGIN);
  }

  private static ElementConstructor pair(Expr... content) {
    return new ElementConstructor("", PAIR, List.of(content), PAIR_ORIGIN);
  }

  private static VarRef pairSlot(AtomicType type) {
    return new VarRef("pairSlot", type, T1);
  }

  /** Pair twice under the root, its V reading Pair's slot number as its parent slot. */
  private static TargetDocument accepted() {
    return document(pairs(2, rootSlot(), pair(values(pairSlot(AtomicType.INTEGER)))));
  }

  static Stream<Arguments> defects() {
    Expr child = pair(values(pairSlot(AtomicType.INTEGER)));
    return Stream.of(
        Arguments.of("no copy", document(pairs(0, rootSlot(), child))),
        Arguments.of("fewer than no copy", document(pairs(-1, rootSlot(), child))),
        Arguments.of(
            "a string parent slot", document(pairs(2, new Literal("1", PAIR_ORIGIN), child))),
        Arguments.of(
            "its slot number read as a string",
            document(pairs(2, rootSlot(), pair(values(pairSlot(AtomicType.STRING)))))),
        Arguments.of(
            "its slot number read beside it, out of its body",
            document(pairs(2, rootSlot(), pair()), values(pairSlot(AtomicType.INTEGER)))),
        Arguments.of(
            "a value as its body", document(pairs(2, rootSlot(), new Literal("a", PAIR_ORIGIN)))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("defects")
  void refusesADefectiveTargetRepeat(String why, TargetDocument document) {
    assertThatCode(() -> IrVerifier.verify(accepted())).doesNotThrowAnyException();
    // one copy is the least accepted, as an unmapped 1..1 field gives it
    assertThatCode(
            () ->
                IrVerifier.verify(
                    document(pairs(1, rootSlot(), pair(values(pairSlot(AtomicType.INTEGER)))))))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> IrVerifier.verify(document))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- the value of a generating structure field (#83)

  private static final AuthoringId C1 = new AuthoringId("c1");

  /** Pair twice under the root with {@code value}, its V reading Pair's slot number. */
  private static TargetDocument valued(Expr value) {
    return document(
        new TargetRepeat(
            2,
            rootSlot(),
            "pairSlot",
            Optional.of(value),
            pair(values(pairSlot(AtomicType.INTEGER))),
            PAIR_ORIGIN));
  }

  /** A single-value call over {@code slot}, an {@code INTEGER}, as a value that reads it. */
  private static FunCall over(VarRef slot) {
    return new FunCall(
        new FunctionRef("formatSlot", List.of(AtomicType.INTEGER), AtomicType.STRING),
        List.of(slot),
        C1);
  }

  /**
   * A STRING value is accepted: a Literal, a single-value call over the root's slot number, and,
   * under a TargetSlots, that TargetSlots' value variable, all of the parent's scope.
   */
  @Test
  void aTargetRepeatValueIsAStringInItsParentsScope() {
    assertThatCode(() -> IrVerifier.verify(valued(new Literal("g", C1))))
        .doesNotThrowAnyException();
    assertThatCode(() -> IrVerifier.verify(valued(over(rootSlot())))).doesNotThrowAnyException();
    TargetSlots parent =
        new TargetSlots(
            new SourceQueue(List.of(ORDER, PAIR), 1, true, A1),
            rootSlot(),
            0,
            Long.MAX_VALUE,
            "outerSlot",
            "outer",
            new TargetRepeat(
                2,
                new VarRef("outerSlot", AtomicType.INTEGER, T1),
                "pairSlot",
                Optional.of(new VarRef("outer", AtomicType.STRING, T1)),
                pair(),
                PAIR_ORIGIN),
            T1);
    assertThatCode(() -> IrVerifier.verify(document(parent))).doesNotThrowAnyException();
  }

  /** A queue, an INTEGER and content are no STRING value, refused at the TargetRepeat. */
  @Test
  void aTargetRepeatValueThatIsAQueueIsRefused() {
    assertThatThrownBy(
            () -> IrVerifier.verify(valued(new SourceQueue(List.of(ORDER, PAIR), 1, A1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("TargetRepeat of /Order/Pair: its value is no ");
    assertThatThrownBy(() -> IrVerifier.verify(valued(rootSlot())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("TargetRepeat of /Order/Pair: its value is no STRING");
    assertThatThrownBy(() -> IrVerifier.verify(valued(pair())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("TargetRepeat of /Order/Pair: its value is no STRING");
  }

  /**
   * The value is evaluated before its copy's body, in the parent's scope: a value that reads its
   * own slot number is refused, as TargetSlots' queue may not read its slot (ADR-0006 as amended by
   * #83). Witness: the same call over the root's slot number is accepted.
   */
  @Test
  void aTargetRepeatValueThatReadsItsOwnSlotIsRefused() {
    assertThatCode(() -> IrVerifier.verify(valued(over(rootSlot())))).doesNotThrowAnyException();

    assertThatThrownBy(() -> IrVerifier.verify(valued(over(pairSlot(AtomicType.INTEGER)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pairSlot is not in scope as INTEGER");
  }
}
