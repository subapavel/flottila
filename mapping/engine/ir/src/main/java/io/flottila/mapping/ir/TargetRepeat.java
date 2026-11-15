package io.flottila.mapping.ir;

import java.util.Optional;

/**
 * A fixed number of occurrences of one target field per parent occurrence (ADR-0006 "Target
 * fields"; #63): {@code body} {@code copies} times, the j-th with the slot number {@code (p - 1) *
 * copies + j} bound to {@code slotVar}, where {@code p} is the parent's slot number. Its children
 * read their queues under that slot number, as under a {@link TargetSlots}' slot. It has no min
 * check. Since #63 it creates an unmapped structure field whose effective occurrence is {@code
 * n..n} with {@code n > 0}, {@code n} times, with no value. Since #83 it creates a generating
 * function's field, {@code max(1, minOccurs)} times; on a structure field it holds the function's
 * single value, which each copy evaluates before its body and never writes, and a leaf holds its
 * value in its text instead (ADR-0006 as amended by #83). Since #217 (F-46) its value may be a
 * generating block, a queue read at most once per run: a copy binds the block's first slot to
 * {@code valueVar} and writes its body when that slot is a value, and writes nothing, keeping its
 * slot number, when it is SUPPRESS or the block has no slot.
 *
 * @param copies the occurrences per parent occurrence, at least 1
 * @param parentSlot the parent's slot number, an {@code INTEGER}
 * @param slotVar the {@code INTEGER} variable that holds the occurrence's slot number inside {@code
 *     body}, which its children read as their {@code parentSlot}
 * @param value in the parent's scope, so it cannot read {@code slotVar}: a generating structure
 *     field's single value, a {@code STRING}, or a generating block, a {@code QUEUE}; empty for a
 *     generating leaf's single value and for an unmapped field
 * @param valueVar the {@code STRING} variable that holds a generating block's first slot inside
 *     {@code body}; present exactly when {@code value} is a {@code QUEUE}
 * @param body the content created per occurrence
 * @param origin a generating function's target field mapping; no authoring element creates an
 *     unmapped field, so its field path as the mapping model spells it, which no authoring id can
 *     be (ADR-0010 allows no {@code /} in one)
 */
public record TargetRepeat(
    long copies,
    Expr parentSlot,
    String slotVar,
    Optional<Expr> value,
    Optional<String> valueVar,
    Expr body,
    AuthoringId origin)
    implements Expr {

  /** A {@code TargetRepeat} with no value: an unmapped field's, or a generating leaf's. */
  public TargetRepeat(long copies, Expr parentSlot, String slotVar, Expr body, AuthoringId origin) {
    this(copies, parentSlot, slotVar, Optional.empty(), body, origin);
  }

  /**
   * A {@code TargetRepeat} with no value variable: one with no value, or a generating structure
   * field's with its single value.
   */
  public TargetRepeat(
      long copies,
      Expr parentSlot,
      String slotVar,
      Optional<Expr> value,
      Expr body,
      AuthoringId origin) {
    this(copies, parentSlot, slotVar, value, Optional.empty(), body, origin);
  }
}
