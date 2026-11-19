package dev.rail8.mapping.runtime;

import dev.rail8.mapping.ir.AuthoringId;
import dev.rail8.mapping.xdm.QueueBlock;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Records what a traced compile did in one mapping run: one report per evaluation of a box and one
 * per parent slot of a target field, in one list in the order made, so a box evaluated twice shows
 * twice. An untraced compile never reports. One tracer serves one run.
 *
 * <p>The run's thread reports while another thread may read (the facade snapshots the tracer at
 * timeout). Every report and every read holds the tracer's lock, so a read is a copy of the reports
 * made so far, in order, which later reports never reach.
 *
 * <p>A tracer may have a bound: it keeps that many reports and refuses the next one with {@link
 * TooManyReports}, which leaves the run through the report's caller and so stops it.
 */
public final class Tracer {

  /** One report of a traced compile. */
  public sealed interface Report permits Value, Atomic, FieldReport {}

  /**
   * A report of a target field under one parent slot: a {@code TargetSlots}' occurrences or a
   * {@code TargetRepeat}'s copies.
   */
  public sealed interface FieldReport extends Report permits Occurrences, Repeat {

    /** The target field's origin: its target field mapping, or an unmapped field's path. */
    AuthoringId field();

    /** The parent slot it ran under. */
    long parentSlot();
  }

  /**
   * A box's evaluation: the queue it gave.
   *
   * @param box the box's authoring id
   * @param queue the queue it gave
   */
  public record Value(AuthoringId box, QueueBlock queue) implements Report {}

  /**
   * A box's evaluation that gave a single value, an {@code {atomic}} trace entry, such as a {@code
   * constant}'s.
   *
   * @param box the box's authoring id
   * @param value the value it gave
   */
  public record Atomic(AuthoringId box, String value) implements Report {}

  /**
   * A traced {@code TargetSlots} under one parent slot, reported before its min check: the slots of
   * context {@code parentSlot} of {@code queue} are its occurrences.
   *
   * @param field the target field mapping
   * @param parentSlot the parent slot whose context it reads
   * @param queue the target field's queue
   * @param min the effective minimum occurrence
   * @param max the effective maximum occurrence; {@link Long#MAX_VALUE} is unbounded
   */
  public record Occurrences(
      AuthoringId field, long parentSlot, QueueBlock queue, long min, long max)
      implements FieldReport {}

  /**
   * A traced {@code TargetRepeat} under one parent slot, reported before it writes: its {@code
   * copies} occurrences, numbered {@code (parentSlot - 1) * copies + j}, are all created, unless
   * its generating block's first slot is SUPPRESS or missing, when none is. An unmapped field's
   * repeat reports its field path as its origin.
   *
   * @param field the target field mapping of a generating function, or the unmapped field's path
   * @param parentSlot the parent slot it repeats under
   * @param copies the occurrences per parent slot
   * @param suppressed whether its copies write nothing: its generating block's first slot is
   *     SUPPRESS, or the block has no slot
   */
  public record Repeat(AuthoringId field, long parentSlot, long copies, boolean suppressed)
      implements FieldReport {}

  /** A report past a tracer's bound; unchecked, it leaves the run and stops it. */
  public static final class TooManyReports extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    TooManyReports(long bound) {
      super("the trace has more than " + bound + " reports");
    }
  }

  private final Object lock = new Object();

  /** Every report, in the order made; guarded by {@link #lock}. */
  private final List<Report> reports = new ArrayList<>();

  /** The most reports kept. */
  private final long bound;

  /** An empty tracer that keeps every report. */
  public Tracer() {
    bound = Long.MAX_VALUE;
  }

  /**
   * An empty tracer that keeps at most {@code bound} reports.
   *
   * @throws IllegalArgumentException when {@code bound} is less than 1
   */
  public Tracer(long bound) {
    if (bound < 1) {
      throw new IllegalArgumentException("a tracer keeps at least 1 report, not " + bound);
    }
    this.bound = bound;
  }

  void value(AuthoringId box, QueueBlock queue) {
    report(new Value(box, queue));
  }

  void atomic(AuthoringId box, String value) {
    report(new Atomic(box, value));
  }

  void occurrences(AuthoringId field, long parentSlot, QueueBlock queue, long min, long max) {
    report(new Occurrences(field, parentSlot, queue, min, max));
  }

  void repeat(AuthoringId field, long parentSlot, long copies, boolean suppressed) {
    report(new Repeat(field, parentSlot, copies, suppressed));
  }

  private void report(Report report) {
    synchronized (lock) {
      if (reports.size() >= bound) {
        throw new TooManyReports(bound);
      }
      reports.add(report);
    }
  }

  /** A copy of every report so far, of every kind, in the order made. */
  public List<Report> reports() {
    synchronized (lock) {
      return List.copyOf(reports);
    }
  }

  /** A copy of every box's report so far, in the order made: its authoring id and its queue. */
  public List<Map.Entry<AuthoringId, QueueBlock>> values() {
    List<Map.Entry<AuthoringId, QueueBlock>> values = new ArrayList<>();
    for (Report report : reports()) {
      if (report instanceof Value(AuthoringId box, QueueBlock queue)) {
        values.add(Map.entry(box, queue));
      }
    }
    return Collections.unmodifiableList(values);
  }

  /** A copy of every occurrences report so far, in the order made. */
  public List<Occurrences> occurrences() {
    List<Occurrences> occurrences = new ArrayList<>();
    for (Report report : reports()) {
      if (report instanceof Occurrences o) {
        occurrences.add(o);
      }
    }
    return Collections.unmodifiableList(occurrences);
  }
}
