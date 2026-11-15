package io.flottila.mapping.ir;

/**
 * The occurrences of one target field (ADR-0006 "Target fields", refined by #52): {@code body} once
 * per created slot of context {@code p} of {@code queue}, where {@code p} is the parent's slot
 * number, with the slot's number bound to {@code slotVar} and its value to {@code valueVar}. A
 * SUPPRESS slot creates nothing; creation stops after {@code max} occurrences; fewer than {@code
 * min} created occurrences raise {@code po:MINOCCURS} at {@code origin}, before any occurrence of
 * context {@code p} is written.
 *
 * @param queue the target field's queue
 * @param parentSlot the parent's slot number, an {@code INTEGER}
 * @param min the effective minimum occurrence
 * @param max the effective maximum occurrence; {@link Long#MAX_VALUE} is unbounded
 * @param slotVar the {@code INTEGER} variable that holds the slot's number inside {@code body},
 *     which its children read as their {@code parentSlot}
 * @param valueVar the {@code STRING} variable that holds the slot's value inside {@code body}
 * @param body the content created per slot
 * @param origin the target field mapping
 */
public record TargetSlots(
    Expr queue,
    Expr parentSlot,
    long min,
    long max,
    String slotVar,
    String valueVar,
    Expr body,
    AuthoringId origin)
    implements Expr {}
