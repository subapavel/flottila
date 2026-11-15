package io.flottila.mapping.xdm;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/**
 * The single-value step over queue blocks: the semantics of the IR's {@code Lift} and of the
 * export's {@code po:lift} (ADR-0006), where the sharing rules of ADR-0004 hold.
 */
public final class Lifting {

  private Lifting() {}

  /**
   * {@code body} applied slot by slot to {@code inputs}:
   *
   * <ul>
   *   <li>context i of the result pairs context i of every input, and the result has as many
   *       contexts as the input with the most;
   *   <li>if any input's context i is empty or missing, result context i is empty;
   *   <li>otherwise the longest input sets the slot count, and a shorter input reuses its last
   *       value inside the context;
   *   <li>SUPPRESS in any input, reused or not, gives a SUPPRESS slot without running the body; the
   *       other inputs are still consumed.
   * </ul>
   *
   * One input shares its context starts and SUPPRESS bitset with the result (ADR-0006's fast path).
   *
   * @param body gets the value of each input at the slot, in input order, in an array the lift
   *     reuses for the next slot; it never sees SUPPRESS and must not give null
   * @throws CancellationException when the thread is interrupted
   * @throws IllegalStateException when {@code body} gives null
   */
  public static QueueBlock lift(List<QueueBlock> inputs, Function<String[], String> body) {
    if (inputs.size() == 1) {
      return liftOne(inputs.getFirst(), body);
    }
    QueueBlock[] queues = inputs.toArray(QueueBlock[]::new);
    int contexts = 0;
    for (QueueBlock queue : queues) {
      contexts = Math.max(contexts, queue.contexts());
    }
    String[] slot = new String[queues.length];
    QueueBlock.Builder result = new QueueBlock.Builder();
    for (int c = 0; c < contexts; c++) {
      Interrupts.check("a lift");
      result.newContext();
      int count = count(queues, c);
      for (int j = 0; j < count; j++) {
        Interrupts.check("a lift");
        append(result, queues, c, j, slot, body);
      }
    }
    return result.build();
  }

  /** {@code body} over the one input {@code queue}, slot by slot, SUPPRESS kept where it stands. */
  private static QueueBlock liftOne(QueueBlock queue, Function<String[], String> body) {
    String[] values = new String[queue.slots()];
    String[] slot = new String[1];
    for (int i = 0; i < values.length; i++) {
      Interrupts.check("a lift");
      if (!queue.suppressed(i)) {
        slot[0] = queue.value(i);
        values[i] = apply(body, slot);
      }
    }
    return queue.withSameSuppress(values);
  }

  /**
   * {@code body} over {@code slot}, which is never null: only an input's SUPPRESS gives a SUPPRESS
   * slot, so a null would break the bit invariant of ADR-0004, silently with one input.
   */
  private static String apply(Function<String[], String> body, String[] slot) {
    String value = body.apply(slot);
    if (value == null) {
      throw new IllegalStateException(
          "Lifting.lift: its body gave null, which is no value (ADR-0004, ADR-0006)");
    }
    return value;
  }

  /**
   * The slot count of result context {@code c}: none when an input's context {@code c} is empty or
   * missing, else that of the longest.
   */
  private static int count(QueueBlock[] queues, int c) {
    int longest = 0;
    for (QueueBlock queue : queues) {
      if (c >= queue.contexts() || queue.end(c) == queue.start(c)) {
        return 0;
      }
      longest = Math.max(longest, queue.end(c) - queue.start(c));
    }
    return longest;
  }

  /**
   * Appends slot {@code j} of result context {@code c}: SUPPRESS when an input's value there is,
   * else the body over the inputs' values there, a shorter input's last one past its end.
   */
  private static void append(
      QueueBlock.Builder result,
      QueueBlock[] queues,
      int c,
      int j,
      String[] slot,
      Function<String[], String> body) {
    for (int k = 0; k < queues.length; k++) {
      QueueBlock queue = queues[k];
      int i = Math.min(queue.start(c) + j, queue.end(c) - 1);
      if (queue.suppressed(i)) {
        result.appendSuppress();
        return;
      }
      slot[k] = queue.value(i);
    }
    result.append(apply(body, slot));
  }
}
