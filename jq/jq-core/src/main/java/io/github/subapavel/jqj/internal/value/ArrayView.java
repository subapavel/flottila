package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqValue;

/**
 * An array of a document (ADR-0003, ADR-0016): its document and tape index. Public only so the kind can permit it.
 * Equality is the record's, never jq's (ADR-0026). The host's accessors walk the tape for nothing; the walks a filter
 * makes take its run state and charge it the elements they step, once per walk (ADR-0008, ADR-0034).
 */
public record ArrayView(Document doc, int index) implements JqArray, Members {

  /** jq's compact text (ADR-0015). */
  @Override
  public String toJson() {
    return Printer.json(doc, index);
  }

  @Override
  public int length() {
    long[] tape = doc.live();
    int end = Tape.end(tape, index);
    int length = 0;
    for (int i = index + 1; i < end; i = Tape.next(tape, i)) {
      length++;
    }
    return length;
  }

  /** @throws IndexOutOfBoundsException unless {@code 0 <= i < length()} (ADR-0026) */
  @Override
  public JqValue get(int i) {
    long[] tape = doc.live();
    int end = Tape.end(tape, index);
    int n = 0;
    for (int at = index + 1; at < end; at = Tape.next(tape, at)) {
      if (n == i) {
        return doc.valueAt(tape, at);
      }
      n++;
    }
    throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + n);
  }

  /** {@link #length()} for a filter: one tick per element. */
  public int length(RunState state) {
    int length = length();
    state.tick(length);
    return length;
  }

  /**
   * The element at {@code i >= 0} for a filter, or Java {@code null} when the array is shorter: one tick per element
   * stepped over, all of them past the end.
   */
  public JqValue get(RunState state, int i) {
    long[] tape = doc.live();
    int end = Tape.end(tape, index);
    int at = index + 1;
    int stepped = 0;
    while (stepped < i && at < end) {
      at = Tape.next(tape, at);
      stepped++;
    }
    state.tick(stepped);
    return at < end ? doc.valueAt(tape, at) : null;
  }

  /**
   * The elements {@code [from, to)}, {@code 0 <= from <= to <= length()}, as a built array of their values: {@code to}
   * ticks, for the walk to {@code from} and the copy, charged before either, and the copy charged as a built container
   * of as many elements before it is allocated (ADR-0035).
   */
  public BuiltArray slice(RunState state, int from, int to) {
    state.tick(to);
    state.chargeBuilt(Charges.CONTAINER + Charges.ELEMENT * (to - from));
    long[] tape = doc.live();
    int at = index + 1;
    for (int i = 0; i < from; i++) {
      at = Tape.next(tape, at);
    }
    Object[] elements = new Object[to - from];
    for (int i = 0; i < elements.length; i++) {
      elements[i] = doc.valueAt(tape, at);
      at = Tape.next(tape, at);
    }
    return new BuiltArray(elements, elements.length);
  }

  /** The cursor of the first element, its tape index, or -1 for an empty array. */
  @Override
  public int first() {
    long[] tape = doc.live();
    return index + 1 < Tape.end(tape, index) ? index + 1 : -1;
  }

  /** The cursor after the element at tape index {@code at}, or -1 after the last: one step, no scan. */
  @Override
  public int next(RunState state, int at) {
    long[] tape = doc.live();
    int next = Tape.next(tape, at);
    return next < Tape.end(tape, index) ? next : -1;
  }

  /** The element at tape index {@code at}. */
  @Override
  public JqValue member(RunState state, int at) {
    return doc.valueAt(doc.live(), at);
  }
}
