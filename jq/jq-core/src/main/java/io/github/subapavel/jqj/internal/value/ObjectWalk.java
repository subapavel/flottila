package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqValue;

/**
 * One walk of a document's object, made by its {@link KeyProof} (ADR-0044, GLOSSARY "Object walk"): its {@code size}
 * members, each key at its first place with its last value (ADR-0002). With {@code plan} null no key repeats, and the
 * cursor is a key's tape index, its value the next entry. Otherwise {@code plan} holds a pair per member in order, the
 * tape index of its key's first place and that of its last value, and the cursor is the place of a pair in it. No
 * step scans or charges: the proof charged them all. A step after a member checks the document again, since the sink
 * that took the member may have read another one onto the tape (ADR-0026). The walk is no value, and a filter never
 * sees it.
 */
public record ObjectWalk(Document doc, int index, int size, int[] plan) implements Members, Keyed {

  /** The cursor of the first member, or -1 for an empty object. */
  @Override
  public int first() {
    if (size == 0) {
      return -1;
    }
    return plan == null ? index + 1 : 0;
  }

  /** The cursor after the member at {@code cursor}, or -1 after the last. */
  @Override
  public int next(RunState state, int cursor) {
    long[] tape = doc.live();
    if (plan == null) {
      int next = Tape.next(tape, cursor + 1);
      return next < Tape.end(tape, index) ? next : -1;
    }
    return cursor + 2 < plan.length ? cursor + 2 : -1;
  }

  /** The last value of the member at {@code cursor}. */
  @Override
  public JqValue member(RunState state, int cursor) {
    return doc.valueAt(doc.live(), valueAt(cursor));
  }

  /** The key of the member at {@code cursor}, at its first place. */
  @Override
  public StringView key(int cursor) {
    return new StringView(doc, keyAt(cursor));
  }

  /** The value of {@code key}: the object's own lookup, which a walk does not change (ObjectView). */
  @Override
  public JqValue get(RunState state, String key) {
    return new ObjectView(doc, index).get(state, key);
  }

  /** The tape index of the key of the member at {@code cursor}, at its first place. */
  public int keyAt(int cursor) {
    return plan == null ? cursor : plan[cursor];
  }

  /** The tape index of the last value of the member at {@code cursor}. */
  public int valueAt(int cursor) {
    return plan == null ? cursor + 1 : plan[cursor + 1];
  }
}
