package io.flottila.mapping.api;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.runtime.Tracer;
import io.flottila.mapping.structure.Occurrence;
import io.flottila.mapping.xdm.QueueBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.LongPredicate;

/**
 * Builds a test run's {@link Trace} from its tracer's reports, off the run's thread (ADR-0006 and
 * ADR-0015 since #53). The slot statuses are derived here from context p and max, and each entry is
 * capped at the trace limit.
 */
final class TraceBuilder {

  private TraceBuilder() {}

  /**
   * The trace of {@code reports}, a tracer's reports in the order made: one entry per authoring id
   * in the order of its first report, across every kind. A box's entry is its first queue or single
   * value, as a box feeding several inputs is reported once per evaluation; a target field
   * mapping's entry holds a parent record per report. A repeat whose origin is no target field
   * mapping is an unmapped field's, whose origin is its field path, and has no entry (ADR-0006).
   *
   * @param fields the occurrence of each target field mapping's field, by its authoring id: the min
   *     and max of a generating function's entry, which its repeat reports do not hold (#83)
   * @param limit the trace limit: the most slots, contexts or occurrence records one entry keeps
   */
  static Trace build(List<Tracer.Report> reports, Map<AuthoringId, Occurrence> fields, int limit) {
    SequencedMap<AuthoringId, Tracer.Report> first = new LinkedHashMap<>();
    Map<AuthoringId, List<Tracer.FieldReport>> parents = new HashMap<>();
    for (Tracer.Report report : reports) {
      switch (report) {
        case Tracer.Value value -> first.putIfAbsent(value.box(), value);
        case Tracer.Atomic atomic -> first.putIfAbsent(atomic.box(), atomic);
        case Tracer.Repeat unmapped when !fields.containsKey(unmapped.field()) -> {
          // an unmapped field's copies: no entry
        }
        case Tracer.FieldReport field -> {
          first.putIfAbsent(field.field(), field);
          parents.computeIfAbsent(field.field(), id -> new ArrayList<>()).add(field);
        }
      }
    }
    SequencedMap<AuthoringId, Trace.Entry> entries = new LinkedHashMap<>();
    first.forEach(
        (id, report) ->
            entries.put(
                id,
                switch (report) {
                  case Tracer.Value value -> queue(value.queue(), limit);
                  // every single value of milestone 1 is an xs:string (ADR-0004)
                  case Tracer.Atomic atomic -> new Trace.Atomic(AtomicType.STRING, atomic.value());
                  case Tracer.Occurrences o ->
                      occurrences(o.min(), o.max(), parents.get(id), limit);
                  case Tracer.Repeat r -> {
                    Occurrence field = fields.get(id);
                    yield occurrences(field.min(), field.max(), parents.get(id), limit);
                  }
                }));
    return new Trace(entries);
  }

  /**
   * {@code block}'s first {@code limit} contexts, holding its first {@code limit} slots, and its
   * totals. A SUPPRESS slot's value is null.
   */
  private static Trace.Queue queue(QueueBlock block, int limit) {
    List<List<String>> contexts = new ArrayList<>();
    int kept = 0;
    for (int context = 0; context < block.contexts() && context < limit; context++) {
      List<String> values = new ArrayList<>();
      for (int slot = block.start(context); slot < block.end(context) && kept < limit; slot++) {
        values.add(block.value(slot));
        kept++;
      }
      contexts.add(values);
    }
    boolean capped = block.slots() > limit || block.contexts() > limit;
    return new Trace.Queue(contexts, capped, block.slots(), block.contexts());
  }

  /**
   * A target field's parent records, one per report: the first {@code limit - 1} and the last,
   * holding at most {@code limit} slot records in order.
   */
  private static Trace.Occurrences occurrences(
      long min, long max, List<Tracer.FieldReport> reports, int limit) {
    List<Trace.ParentSlot> parents = new ArrayList<>();
    long slotRecords = 0;
    int budget = limit;
    int last = reports.size() - 1;
    for (int i = 0; i <= last; i++) {
      Slots slots = slots(reports.get(i));
      slotRecords += slots.end() - slots.start();
      if (i < limit - 1 || i == last) {
        Trace.ParentSlot parent = parent(slots, max, budget);
        budget -= parent.slots().size();
        parents.add(parent);
      }
    }
    boolean capped = reports.size() > limit || slotRecords > limit;
    return new Trace.Occurrences(min, max, parents, capped);
  }

  /**
   * The slots of one report, {@code start} to {@code end} by their index across the field, from 0.
   *
   * @param parentSlot the parent slot they run under
   * @param suppressed whether the slot at an index is SUPPRESS
   */
  private record Slots(long parentSlot, long start, long end, LongPredicate suppressed) {}

  /**
   * The slots of {@code report}: of a {@code TargetSlots}, those of context p of its queue, a
   * context the queue lacks counting as empty (ADR-0006); of a {@code TargetRepeat}, its copies,
   * numbered {@code (p - 1) * copies + j} (#83), each SUPPRESS when its generating block's first
   * slot is SUPPRESS or missing, and none otherwise (#217, F-46).
   */
  private static Slots slots(Tracer.FieldReport report) {
    return switch (report) {
      case Tracer.Occurrences o -> {
        QueueBlock queue = o.queue();
        long parentSlot = o.parentSlot();
        int start = 0;
        int end = 0;
        if (parentSlot <= queue.contexts()) {
          start = queue.start((int) parentSlot - 1);
          end = queue.end((int) parentSlot - 1);
        }
        yield new Slots(parentSlot, start, end, slot -> queue.suppressed((int) slot));
      }
      case Tracer.Repeat r ->
          new Slots(
              r.parentSlot(),
              (r.parentSlot() - 1) * r.copies(),
              r.parentSlot() * r.copies(),
              slot -> r.suppressed());
    };
  }

  /**
   * The parent record of {@code slots}: each slot SUPPRESS is suppressed, any other created until
   * {@code max} are, then truncated (ADR-0006). It keeps at most {@code budget} slot records, and
   * counts the created ones over all of them.
   */
  private static Trace.ParentSlot parent(Slots slots, long max, int budget) {
    List<Trace.Slot> records = new ArrayList<>();
    long created = 0;
    for (long slot = slots.start(); slot < slots.end(); slot++) {
      Trace.SlotStatus status;
      if (slots.suppressed().test(slot)) {
        status = Trace.SlotStatus.SUPPRESSED;
      } else if (created < max) {
        status = Trace.SlotStatus.CREATED;
        created++;
      } else {
        status = Trace.SlotStatus.TRUNCATED;
      }
      if (records.size() < budget) {
        records.add(new Trace.Slot(slot + 1, status));
      }
    }
    return new Trace.ParentSlot(slots.parentSlot(), created, records);
  }
}
