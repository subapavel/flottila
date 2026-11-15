package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.Name;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The projection of a JSON input, in one pass of {@link JsonParser} (ADR-0008 "JSON as a source at
 * run time", ADR-0006 "A SourceQueue over a JSON field"): only the properties the queues name are
 * followed, every other value is skipped, and no tree is built. A field's steps are {@code $}, the
 * input's one value, and then property names. A context opens at each object its context field
 * reads, and a value is appended at each occurrence of its field: a string as it escapes, a number
 * as the input spells it, a boolean as {@code true} or {@code false}, {@code null} as {@code ""},
 * and {@code ""} for each object a structure field reads. An array property's items are the
 * occurrences of its field. A value of the wrong kind reads as absent: an object or an array for a
 * leaf, a scalar or an array for a structure field, a value that is no array for an array property,
 * and an array for a property that is none. The input is still parsed whole, so it must be one
 * well-formed UTF-8 JSON text throughout, with no key twice in an object.
 *
 * <p>The step trie is built once at compile and only read by a run.
 */
final class JsonProjection implements Projection {

  /** A node of the trie of property steps. */
  private static final class Step {
    private final String name;
    private Step[] children = new Step[0];

    /** Whether the step is an array property, whose items are its occurrences. */
    private boolean array;

    /** The queues whose context field this step is: each object it reads opens a context. */
    private int[] contexts = new int[0];

    /** The queues whose leaf field this step is: each scalar it reads appends its text. */
    private int[] values = new int[0];

    /**
     * The queues whose structure field this step is: each object it reads appends {@code ""}, and
     * the object is still walked for the queues below it.
     */
    private int[] occurrences = new int[0];

    Step(String name) {
      this.name = name;
    }

    /** The child step of that property name, or {@code skipped}. */
    Step child(String name, Step skipped) {
      for (Step child : children) {
        if (child.name.equals(name)) {
          return child;
        }
      }
      return skipped;
    }

    Step childOrAdd(Name name) {
      Step child = child(name.localName(), null);
      if (child == null) {
        child = new Step(name.localName());
        children = Arrays.copyOf(children, children.length + 1);
        children[children.length - 1] = child;
      }
      return child;
    }
  }

  /** Above the root: its one child is {@code $}, the input's value. */
  private final Step document = new Step("");

  /** What a skipped value stands on: it has no children, and reads nothing. */
  private final Step skipped = new Step("");

  /** The step of the input's value; a plan without a queue still has it. */
  private final Step root = document.childOrAdd(new Name("", "$"));

  private final int queues;

  /**
   * @param plan the queues, by their index in the frame
   */
  JsonProjection(List<Queue> plan) {
    queues = plan.size();
    for (int k = 0; k < queues; k++) {
      Queue queue = plan.get(k);
      List<Name> field = queue.field();
      Step step = document;
      for (int depth = 1; depth <= field.size(); depth++) {
        step = step.childOrAdd(field.get(depth - 1));
        if (depth == queue.contextDepth()) {
          step.contexts = Projection.append(step.contexts, k);
        }
      }
      for (int depth : queue.arrays()) {
        Step array = document;
        for (int d = 1; d <= depth; d++) {
          array = array.childOrAdd(field.get(d - 1));
        }
        array.array = true;
      }
      if (queue.structureField()) {
        step.occurrences = Projection.append(step.occurrences, k);
      } else {
        step.values = Projection.append(step.values, k);
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @throws MalformedInputException when the input is no JSON text by RFC 8259, is no UTF-8, or has
   *     an object with a key twice
   */
  @Override
  public QueueBlock[] read(InputStream in) throws MalformedInputException {
    QueueBlock.Builder[] builders = Projection.builders(queues);
    // a decoder of its own reports a malformed byte sequence, where the charset would replace it;
    // the reader is not closed, as the caller owns the stream
    JsonParser parser =
        new JsonParser(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()));
    try {
      walk(parser, builders);
    } catch (IOException e) {
      throw new MalformedInputException("the JSON input is no UTF-8: " + e.getMessage(), e);
    }
    return Projection.blocks(builders);
  }

  private void walk(JsonParser parser, QueueBlock.Builder[] builders)
      throws IOException, MalformedInputException {
    ArrayDeque<Step> open = new ArrayDeque<>();
    // the step of the next value that is no array item: the root's, then each key's
    Step target = root;
    while (true) {
      // checked per event, so an interrupt stops the parse before the input is read to its end
      if (Thread.currentThread().isInterrupted()) {
        throw new CancellationException("the mapping run was interrupted while parsing its input");
      }
      int event = parser.next();
      if (event == JsonParser.END) {
        return;
      }
      switch (event) {
        case JsonParser.KEY -> target = open.peek().child(parser.text(), skipped);
        case JsonParser.END_OBJECT, JsonParser.END_ARRAY -> open.pop();
        default -> {
          // an array item stands on its array's step, which reads it as one occurrence
          boolean item = parser.item();
          Step step = item ? open.peek() : target;
          // the value an array property reads directly, as no item, must be its array
          boolean array = !item && step.array;
          value(event, parser, step, array, open, builders);
        }
      }
    }
  }

  /**
   * Reads the value that {@code event} starts on {@code step}; {@code array} says whether it must
   * be an array, whose items {@code step} reads, or else no array.
   */
  private void value(
      int event,
      JsonParser parser,
      Step step,
      boolean array,
      ArrayDeque<Step> open,
      QueueBlock.Builder[] builders) {
    switch (event) {
      case JsonParser.START_OBJECT -> {
        if (array || step.values.length > 0) {
          open.push(skipped);
        } else {
          for (int k : step.contexts) {
            builders[k].newContext();
          }
          for (int k : step.occurrences) {
            builders[k].append("");
          }
          open.push(step);
        }
      }
      case JsonParser.START_ARRAY -> open.push(array ? step : skipped);
      default -> {
        if (!array) {
          // taken per queue, so a scalar no leaf reads is not even taken as a string
          for (int k : step.values) {
            builders[k].append(scalar(event, parser));
          }
        }
      }
    }
  }

  /** The text of the scalar {@code event} gives: {@code null} is {@code ""}. */
  private static String scalar(int event, JsonParser parser) {
    return switch (event) {
      case JsonParser.TRUE -> "true";
      case JsonParser.FALSE -> "false";
      case JsonParser.NULL -> "";
      default -> parser.text();
    };
  }
}
