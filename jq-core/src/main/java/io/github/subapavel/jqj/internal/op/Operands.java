package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.value.JqValue;

/**
 * The two operands of a binary operator, left and right, which its one exhaustive {@code switch} takes apart (design
 * approval of #65): a case per pair of kinds, no default and no catch-all, so a new kind is a compile error.
 */
record Operands(JqValue a, JqValue b) {
  Operands(Object a, Object b) {
    this((JqValue) a, (JqValue) b);
  }
}
