package io.flottila.mapping.api;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.structure.ValueType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * The trace of a test run (ADR-0015 "Trace" as refined by #53; GLOSSARY "Trace", "Trace limit",
 * "Parent slot"): an entry per evaluated box and per target field mapping that ran, by authoring
 * id, in the order of first report: a queue, a single value or a field's occurrences. A box that
 * was never evaluated has none.
 *
 * @param entries the entries by authoring id, in the order of first report
 */
public record Trace(SequencedMap<AuthoringId, Entry> entries) {

  public Trace {
    entries = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(entries));
  }

  /** One entry of the trace, by its kind. */
  public sealed interface Entry permits Queue, Occurrences, Atomic {}

  /**
   * The single value a box gave, at its first report (ADR-0015 {@code {atomic: {type, value}}}):
   * the value of a single-value call with no queue input, such as a {@code constant} (#83).
   *
   * @param type the value's type
   * @param value the value
   */
  public record Atomic(AtomicType type, String value) implements Entry {

    /**
     * The XML Schema name of the value's type (ADR-0004), such as {@code xs:string}: what a caller
     * that does not reach the IR, as the service, shows (ADR-0024 "Service boundary").
     */
    public String typeName() {
      ValueType named =
          switch (type) {
            case STRING -> ValueType.STRING;
            case INTEGER -> ValueType.INTEGER;
          };
      return named.typeName();
    }
  }

  /**
   * The queue a box gave, at its first report: at most the trace limit's contexts, holding at most
   * its slots.
   *
   * @param contexts the contexts kept, each its values in order, {@code null} for SUPPRESS
   * @param capped whether the queue has more slots or contexts than the trace limit, so some were
   *     left out
   * @param slotCount the queue's slots, all of them
   * @param contextCount the queue's contexts, all of them
   */
  public record Queue(List<List<String>> contexts, boolean capped, int slotCount, int contextCount)
      implements Entry {

    public Queue {
      List<List<String>> copies = new ArrayList<>(contexts.size());
      for (List<String> context : contexts) {
        // a copy that keeps the nulls of SUPPRESS, which List.copyOf refuses
        copies.add(Collections.unmodifiableList(new ArrayList<>(context)));
      }
      contexts = Collections.unmodifiableList(copies);
    }
  }

  /**
   * The occurrences a target field mapping created, per parent slot in the order run (ADR-0006): at
   * most the trace limit's parent records and slot records, always keeping the last parent. The
   * field's queue is the entry of its {@code result} box; a generating function's field has no
   * queue, and its occurrences are its copies, all created (ADR-0006 as amended by #83), or all
   * suppressed when its generating block's first slot is SUPPRESS or missing (#217, F-46).
   *
   * @param min the field's effective minimum occurrence
   * @param max the field's effective maximum occurrence; {@link Long#MAX_VALUE} is unbounded
   * @param parents the parent records kept
   * @param capped whether the field has more parent records or slot records than the trace limit,
   *     so some were left out
   */
  public record Occurrences(long min, long max, List<ParentSlot> parents, boolean capped)
      implements Entry {

    public Occurrences {
      parents = List.copyOf(parents);
    }
  }

  /**
   * The slots of context {@code parentSlot} of the field's queue, or a generating function's copies
   * under that parent slot (#83). For a field's queue, {@code created < min} marks the parent slot
   * that raised {@code po:MINOCCURS}. A generating function's copies have no min check: when its
   * generating block's first slot is SUPPRESS or missing, every copy is suppressed and {@code
   * created} is 0, below a {@code minOccurs > 0} too, and no {@code po:MINOCCURS} is raised (#217,
   * F-46; assumed, ADR-0006).
   *
   * @param parentSlot the slot number of the parent occurrence
   * @param created how many occurrences the slots create, at most max
   * @param slots the slots kept, in order
   */
  public record ParentSlot(long parentSlot, long created, List<Slot> slots) {

    public ParentSlot {
      slots = List.copyOf(slots);
    }
  }

  /**
   * One slot of a parent slot's context.
   *
   * @param slot its slot number, counted across all contexts of the queue
   * @param status what it created
   */
  public record Slot(long slot, SlotStatus status) {}

  /** What a slot created (GLOSSARY "Truncated slot", "SUPPRESS"). */
  public enum SlotStatus {
    /** An occurrence. */
    CREATED,
    /**
     * Nothing: the slot is SUPPRESS, or it is a copy of a generating block whose first slot is
     * SUPPRESS or missing (#217).
     */
    SUPPRESSED,
    /** Nothing: {@code max} occurrences were created before it in its context. */
    TRUNCATED
  }
}
