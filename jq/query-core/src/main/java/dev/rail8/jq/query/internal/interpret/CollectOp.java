package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.Collector;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;
import dev.rail8.jq.query.run.Termination;
import java.util.Arrays;

/**
 * Gathers its body's results and emits what the collector makes of them. It charges the container before its first
 * allocation and each element before it stores it: a collector charges per element, never the capacity it grows to,
 * and its growth copies charge no step. After a break in its body it emits nothing and passes the false on.
 */
public final class CollectOp extends Op {
  /** The capacity of the first array, allocated with the first result. */
  private static final int FIRST = 4;

  /**
   * The longest array a collector grows to: the JDK's own ceiling for growing arrays,
   * {@code jdk.internal.util.ArraysSupport.SOFT_MAX_ARRAY_LENGTH}, which it keeps below the VMs' limits.
   */
  static final int MAX_LENGTH = Integer.MAX_VALUE - 8;

  /** The results of a body that gave none: an empty array has nothing to write, so the collectors share it. */
  private static final Object[] NONE = {};

  private final Op body;
  private final Collector collector;

  public CollectOp(boolean valueForm, Op body, Collector collector) {
    super(valueForm);
    this.body = body;
    this.collector = collector;
  }

  /**
   * The body in push form: a Collect is at most one, so it is push form only where it may break out, and then so may
   * its body.
   */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    state.chargeBuilt(Charges.CONTAINER);
    Elements elements = new Elements(state);
    // The body's sink never stops, so a false from the body is a break.
    if (!body.run(state, slots, in, elements)) {
      return false;
    }
    return out.emit(collector.collect(elements.values, elements.length));
  }

  /**
   * What the collector makes of the body's results, charged as {@link #run} charges them. The body of a value-form
   * Collect may not break out, so a push body's run ends with true.
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    state.chargeBuilt(Charges.CONTAINER);
    Elements elements = new Elements(state);
    if (!body.valueForm) {
      body.run(state, slots, in, elements);
    } else {
      Object v = body.eval(state, slots, in);
      if (v != CallForm.NONE) {
        elements.emit(v);
      }
    }
    return collector.collect(elements.values, elements.length);
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.state.chargeBuilt(Charges.CONTAINER);
    Gather gather = new Gather(under, new Elements(t.state), out);
    t.start(body, gather, slots, in, gather);
  }

  /**
   * The capacity after {@code length} results filled the array: twice {@code length}, at least {@link #FIRST} and at
   * most {@link #MAX_LENGTH}.
   *
   * @throws Termination over the bytes-built budget when {@code length} is {@link #MAX_LENGTH} already
   */
  static int grown(RunState state, int length) {
    if (length >= MAX_LENGTH) {
      throw state.overBuilt();
    }
    return (int) Math.min(MAX_LENGTH, Math.max(FIRST, 2L * length));
  }

  /**
   * The heap form while the body runs, and the body's sink: each result is stored and answered to go on; when the body
   * ran out, what the collector makes of them is emitted, and after a break nothing is.
   */
  private final class Gather extends HeapForm implements HeapSink {
    private final Elements elements;
    private final HeapSink out;

    Gather(HeapForm under, Elements elements, HeapSink out) {
      super(under);
      this.elements = elements;
      this.out = out;
    }

    @Override
    public void accept(Trampoline t, HeapForm producer, Object v) {
      elements.emit(v);
      t.answer(producer, true);
    }

    @Override
    void resume(Trampoline t, boolean more) {
      if (!more) {
        t.answer(under, false);
        return;
      }
      t.emit(out, under, collector.collect(elements.values, elements.length));
    }
  }

  /** The results so far, {@code [0, length)} of {@code values}. */
  private static final class Elements implements Sink {
    private final RunState state;
    private Object[] values = NONE;
    private int length;

    Elements(RunState state) {
      this.state = state;
    }

    @Override
    public boolean emit(Object v) {
      state.chargeBuilt(Charges.ELEMENT);
      if (length == values.length) {
        values = Arrays.copyOf(values, grown(state, length));
      }
      values[length++] = v;
      return true;
    }
  }
}
