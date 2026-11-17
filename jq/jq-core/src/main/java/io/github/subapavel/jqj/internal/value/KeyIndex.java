package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.value.JqValue;

/**
 * Equality's view of a read right side for one object comparison (ADR-0044 as amended by #95, GLOSSARY "Key index"),
 * made by {@link KeyProof#index}: its {@code size} distinct keys, and in {@code values}, for each left member in walk
 * order up to the first one it lacks, the tape index of its last value, then 0. {@code values} is the comparison's own
 * array of tape positions, never values, so a nested pair's proof in the same scratch leaves it whole. It is no walk.
 */
record KeyIndex(Document doc, int size, int[] values) {

  /**
   * The right side's last value of the key of left member {@code member}, or Java {@code null} when the right side lacks
   * it; {@code member} is at most the place of the first one it lacks.
   */
  JqValue value(int member) {
    int value = values[member];
    return value == 0 ? null : doc.valueAt(doc.live(), value);
  }
}
