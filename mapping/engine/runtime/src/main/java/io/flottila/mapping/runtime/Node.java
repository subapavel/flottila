package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * An executable node, built once from one IR node by the closure compiler (ADR-0021). Its kind
 * follows the IR node's type: a queue, an {@code INTEGER}, a {@code STRING}, or content it writes.
 */
interface Node {

  /** A node that gives a queue block. */
  interface OfQueue extends Node {
    QueueBlock eval(Frame frame);
  }

  /** A node that gives an {@code INTEGER}. */
  interface OfInteger extends Node {
    long eval(Frame frame);
  }

  /** A node that gives a {@code STRING}. */
  interface OfString extends Node {
    String eval(Frame frame);
  }

  /** A node that writes content to the frame's output. */
  interface OfContent extends Node {
    void write(Frame frame) throws IOException;
  }

  /**
   * A {@code Literal}: its string, which a binder may read once, when it binds a call over it, as
   * {@code fixValues} decodes its table (ADR-0026 "Table params", #83).
   *
   * @param value the literal's string
   */
  record Literal(String value) implements OfString {

    @Override
    public String eval(Frame frame) {
      return value;
    }
  }

  /**
   * {@code TargetSlots} (ADR-0006 "Target fields", refined by #52): the body once per created slot
   * of context {@code p} of the queue, where {@code p} is the parent's slot number, with the slot's
   * number and value in their frame slots. A slot number is the slot's index across all contexts of
   * the queue plus 1, so a SUPPRESS slot and a truncated slot keep theirs. A context the queue
   * lacks counts as empty. Before anything of context {@code p} is written, fewer than {@code min}
   * slots that are not SUPPRESS raise {@code po:MINOCCURS} at the target field mapping; then each
   * slot that is not SUPPRESS is created until {@code max} are.
   *
   * <p>The queue is evaluated at most once per run, on first read, and kept in the frame. It reads
   * no variable of the target, as the IR verifier allows a context function only {@code Literal}
   * params and a {@code Lift}'s body only its own slot variables, so every parent occurrence gets
   * the same block.
   */
  final class Slots implements OfContent {

    private final OfQueue queue;
    private final int queueIndex;
    private final OfInteger parentSlot;
    private final long min;
    private final long max;
    private final int slotNumberIndex;
    private final int valueIndex;
    private final OfContent body;
    private final AuthoringId origin;

    /**
     * @param queueIndex where the frame keeps the queue's block once it is evaluated
     * @param slotNumberIndex the index of the {@code INTEGER} frame slot that holds the slot number
     * @param valueIndex the index of the {@code STRING} frame slot that holds the slot's value
     * @param origin the target field mapping, where {@code po:MINOCCURS} is raised
     */
    Slots(
        OfQueue queue,
        int queueIndex,
        OfInteger parentSlot,
        long min,
        long max,
        int slotNumberIndex,
        int valueIndex,
        OfContent body,
        AuthoringId origin) {
      this.queue = queue;
      this.queueIndex = queueIndex;
      this.parentSlot = parentSlot;
      this.min = min;
      this.max = max;
      this.slotNumberIndex = slotNumberIndex;
      this.valueIndex = valueIndex;
      this.body = body;
      this.origin = origin;
    }

    @Override
    public void write(Frame frame) throws IOException {
      write(frame, block(frame), parentSlot.eval(frame));
    }

    /**
     * This node in a traced compile (ADR-0006 since #53): under each parent slot it reports its
     * field, the parent slot, its queue, min and max to the run's tracer before its min check, so a
     * shortfall is traced. The untraced node itself holds no hook.
     */
    OfContent traced() {
      return frame -> {
        QueueBlock block = block(frame);
        long parent = parentSlot.eval(frame);
        frame.tracer.occurrences(origin, parent, block, min, max);
        write(frame, block, parent);
      };
    }

    /** The queue's block, evaluated on first read and then kept in the frame. */
    private QueueBlock block(Frame frame) {
      QueueBlock block = frame.targetQueues[queueIndex];
      if (block == null) {
        block = queue.eval(frame);
        frame.targetQueues[queueIndex] = block;
      }
      return block;
    }

    /** The occurrences under the parent slot {@code parent}, read from {@code block}. */
    private void write(Frame frame, QueueBlock block, long parent) throws IOException {
      int start = 0;
      int end = 0;
      if (parent <= block.contexts()) {
        int context = (int) parent - 1;
        start = block.start(context);
        end = block.end(context);
      }
      long created = create(frame, block, start, end, min, false);
      if (created < min) {
        throw new MappingRunException(
            RuntimeErrors.MINOCCURS,
            origin,
            "the target field mapping "
                + origin.value()
                + " creates "
                + created
                + " occurrences under parent slot "
                + parent
                + ", fewer than its minOccurs "
                + min,
            null);
      }
      create(frame, block, start, end, max, true);
    }

    /**
     * Walks the slots {@code start} to {@code end} of {@code block} until {@code limit} of them are
     * created, a SUPPRESS slot creating nothing, and writes the body of each when {@code write}.
     *
     * @return the number of slots created
     */
    private long create(
        Frame frame, QueueBlock block, int start, int end, long limit, boolean write)
        throws IOException {
      long created = 0;
      for (int slot = start; slot < end && created < limit; slot++) {
        if (Thread.currentThread().isInterrupted()) {
          throw new CancellationException("the mapping run was interrupted");
        }
        if (!block.suppressed(slot)) {
          if (write) {
            frame.integers[slotNumberIndex] = slot + 1L;
            frame.strings[valueIndex] = block.value(slot);
            body.write(frame);
          }
          created++;
        }
      }
      return created;
    }
  }

  /**
   * {@code TargetRepeat} (ADR-0006 "Target fields"; #63): the body {@code copies} times per parent
   * occurrence, the j-th with the slot number {@code (p - 1) * copies + j} in its frame slot, where
   * {@code p} is the parent's slot number.
   *
   * <p>With a generating block (#217, F-46) the copies are gated by the block's first slot. The
   * block is evaluated at most once per run, on the first parent occurrence's read, and kept in the
   * frame, as a {@link Slots}' queue is, so a run without a parent occurrence never reads it. When
   * the first slot is a value, it goes into its frame slot and every copy is written; when it is
   * SUPPRESS or the block has no slot, no copy is, and the copies keep their slot numbers.
   */
  final class Repeat implements OfContent {

    private final long copies;
    private final OfInteger parentSlot;
    private final int slotNumberIndex;

    /** The generating block, or null when every copy is written. */
    private final OfQueue block;

    private final int queueIndex;
    private final int valueIndex;
    private final OfContent body;
    private final AuthoringId origin;

    /**
     * A repeat whose copies are all written: an unmapped field's, or a generating single value's.
     *
     * @param slotNumberIndex the index of the {@code INTEGER} frame slot that holds the slot number
     * @param origin the target field mapping of a generating function, or an unmapped field's path
     */
    Repeat(
        long copies,
        OfInteger parentSlot,
        int slotNumberIndex,
        OfContent body,
        AuthoringId origin) {
      this(copies, parentSlot, slotNumberIndex, null, 0, 0, body, origin);
    }

    /**
     * A generating block's repeat (#217).
     *
     * @param slotNumberIndex the index of the {@code INTEGER} frame slot that holds the slot number
     * @param queueIndex where the frame keeps the block once it is evaluated
     * @param valueIndex the index of the {@code STRING} frame slot that holds its first slot
     * @param origin the target field mapping
     */
    Repeat(
        long copies,
        OfInteger parentSlot,
        int slotNumberIndex,
        OfQueue block,
        int queueIndex,
        int valueIndex,
        OfContent body,
        AuthoringId origin) {
      this.copies = copies;
      this.parentSlot = parentSlot;
      this.slotNumberIndex = slotNumberIndex;
      this.block = block;
      this.queueIndex = queueIndex;
      this.valueIndex = valueIndex;
      this.body = body;
      this.origin = origin;
    }

    @Override
    public void write(Frame frame) throws IOException {
      long parent = parentSlot.eval(frame);
      if (generates(frame)) {
        write(frame, parent);
      }
    }

    /**
     * This node in a traced compile (#83): under each parent slot it reports its origin, the parent
     * slot, its copies and whether they write nothing (#217) to the run's tracer before it writes
     * them. The untraced node itself holds no hook.
     */
    OfContent traced() {
      return frame -> {
        long parent = parentSlot.eval(frame);
        boolean generates = generates(frame);
        frame.tracer.repeat(origin, parent, copies, !generates);
        if (generates) {
          write(frame, parent);
        }
      };
    }

    /**
     * Whether the copies under a parent occurrence are written: always without a generating block;
     * with one, when its first slot is a value, which this puts into its frame slot.
     */
    private boolean generates(Frame frame) {
      if (block == null) {
        return true;
      }
      QueueBlock generated = frame.targetQueues[queueIndex];
      if (generated == null) {
        generated = block.eval(frame);
        frame.targetQueues[queueIndex] = generated;
      }
      if (generated.slots() == 0 || generated.suppressed(0)) {
        return false;
      }
      frame.strings[valueIndex] = generated.value(0);
      return true;
    }

    /** The copies under the parent slot {@code parent}. */
    private void write(Frame frame, long parent) throws IOException {
      long first = (parent - 1) * copies;
      for (long j = 1; j <= copies; j++) {
        if (Thread.currentThread().isInterrupted()) {
          throw new CancellationException("the mapping run was interrupted");
        }
        frame.integers[slotNumberIndex] = first + j;
        body.write(frame);
      }
    }
  }

  /** {@code ElementConstructor}: the element and its content. */
  final class Element implements OfContent {

    private final String prefix;
    private final String namespace;
    private final String localName;
    private final OfContent[] content;

    Element(String prefix, String namespace, String localName, OfContent[] content) {
      this.prefix = prefix;
      this.namespace = namespace;
      this.localName = localName;
      this.content = content;
    }

    @Override
    public void write(Frame frame) throws IOException {
      frame.out.startElement(prefix, namespace, localName);
      for (OfContent node : content) {
        node.write(frame);
      }
      frame.out.endElement();
    }
  }

  /**
   * {@code AttributeConstructor} (ADR-0006 since #102): the attribute, with its value, on the
   * element whose start tag is open, as the IR verifier places it first in its element's content.
   */
  final class Attribute implements OfContent {

    private final String prefix;
    private final String namespace;
    private final String localName;
    private final OfString value;

    Attribute(String prefix, String namespace, String localName, OfString value) {
      this.prefix = prefix;
      this.namespace = namespace;
      this.localName = localName;
      this.value = value;
    }

    @Override
    public void write(Frame frame) throws IOException {
      frame.out.attribute(prefix, namespace, localName, value.eval(frame));
    }
  }
}
