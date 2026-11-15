package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.InputFormat;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Fills the source queues of a compiled mapping in one pass over the input (ADR-0004), in the input
 * format of the mapping's IR, which its source's root selector gives (ADR-0008 "JSON as a source at
 * run time", #98): {@link XmlProjection} or {@link JsonProjection}. Only the steps the queues name
 * are followed, and no tree is built. A queue whose context field never occurs is one empty
 * context, as in PO (ADR-0004 as amended, F-17), a structure field's too.
 *
 * <p>The plan's entries are distinct (field, context) pairs; equal pairs share one queue
 * (ADR-0006).
 */
sealed interface Projection permits XmlProjection, JsonProjection {

  /**
   * One queue of the plan: a field by its steps, its context by depth, whether it is a structure
   * field, whose queue gets {@code ""} per occurrence (ADR-0008; #63), whether its last step is an
   * attribute (#102), so that {@code /A/b} and {@code /A/@b} are two queues, and the depths of its
   * JSON array properties (#98).
   */
  record Queue(
      List<Name> field,
      int contextDepth,
      boolean structureField,
      boolean attribute,
      List<Integer> arrays) {}

  /** The projection of {@code plan} that reads {@code input}. */
  static Projection of(InputFormat input, List<Queue> plan) {
    return switch (input) {
      case XML -> new XmlProjection(plan);
      case JSON -> new JsonProjection(plan);
    };
  }

  /**
   * The queues of the plan, read from {@code in}.
   *
   * @throws MalformedInputException when the input is not well-formed in its format
   * @throws CancellationException when the thread is interrupted
   */
  QueueBlock[] read(InputStream in) throws MalformedInputException;

  /** {@code indexes} and then {@code index}. */
  static int[] append(int[] indexes, int index) {
    int[] longer = Arrays.copyOf(indexes, indexes.length + 1);
    longer[indexes.length] = index;
    return longer;
  }

  /** A builder per queue of a plan of {@code queues}. */
  static QueueBlock.Builder[] builders(int queues) {
    QueueBlock.Builder[] builders = new QueueBlock.Builder[queues];
    for (int k = 0; k < queues; k++) {
      builders[k] = new QueueBlock.Builder();
    }
    return builders;
  }

  /**
   * The blocks {@code builders} hold once the input is read; one whose context field never occurred
   * is one empty context.
   */
  static QueueBlock[] blocks(QueueBlock.Builder[] builders) {
    QueueBlock[] blocks = new QueueBlock[builders.length];
    for (int k = 0; k < builders.length; k++) {
      blocks[k] = builders[k].build();
      if (blocks[k].contexts() == 0) {
        // the context field never occurred: one empty context, {0,0} (ADR-0004 as amended, F-17)
        builders[k].newContext();
        blocks[k] = builders[k].build();
      }
    }
    return blocks;
  }
}
