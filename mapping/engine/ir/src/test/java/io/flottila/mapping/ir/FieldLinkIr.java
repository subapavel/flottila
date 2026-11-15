package io.flottila.mapping.ir;

import java.util.List;

/**
 * The IR that lowering gives for one field link (ADR-0006 as refined by #26), built by hand: the
 * root {@code ns0:Order} created once, holding {@code TargetSlots} over the source queue of {@code
 * /Q{urn:flottila:corpus:skeleton}Order/OrderId} with the parent as context. The slot number is an
 * {@code INTEGER}; a slot's value is an {@code xs:string}, as every queue value is (ADR-0004).
 */
final class FieldLinkIr {

  static final String NS = "urn:flottila:corpus:skeleton";
  static final Name ORDER = new Name(NS, "Order");
  static final Name ORDER_ID = new Name("", "OrderId");
  static final Name NOTE = new Name("", "Note");
  static final AuthoringId T1 = new AuthoringId("t1");
  static final AuthoringId A1 = new AuthoringId("a1");
  static final AuthoringId T2 = new AuthoringId("t2");
  static final AuthoringId A2 = new AuthoringId("a2");
  static final String ROOT_SLOT = "root";

  private FieldLinkIr() {}

  static TargetDocument document(Expr... content) {
    return new TargetDocument("ns0", ORDER, ROOT_SLOT, List.of(content));
  }

  static SourceQueue queue(Name leaf, int contextDepth, AuthoringId box) {
    return new SourceQueue(List.of(ORDER, leaf), contextDepth, box);
  }

  static VarRef rootSlot() {
    return new VarRef(ROOT_SLOT, AtomicType.INTEGER, T1);
  }

  /**
   * {@code <leaf>{value}</leaf>} per slot of {@code queue}, the value bound to {@code valueVar}.
   */
  static TargetSlots slots(
      Name leaf,
      Expr queue,
      Expr parentSlot,
      long min,
      long max,
      String valueVar,
      Expr textValue,
      AuthoringId mapping) {
    return new TargetSlots(
        queue,
        parentSlot,
        min,
        max,
        "slot",
        valueVar,
        new ElementConstructor("", leaf, List.of(new TextConstructor(textValue, mapping)), mapping),
        mapping);
  }

  /** The field link {@code OrderId <- OrderId}, 1..1, as lowering gives it. */
  static TargetSlots orderIdLink() {
    return slots(
        ORDER_ID,
        queue(ORDER_ID, 1, A1),
        rootSlot(),
        1,
        1,
        "v",
        new VarRef("v", AtomicType.STRING, T1),
        T1);
  }

  static TargetDocument fieldLink() {
    return document(orderIdLink());
  }
}
