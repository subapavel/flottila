package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Queues with SUPPRESS for the tests of #51 (ADR-0004, ADR-0018): a queue as its contexts of slots,
 * where {@link #S} stands for a SUPPRESS slot. A test builds a block from such a queue with the
 * three-argument {@code QueueBlock.of} and reads a block back into one through the public API only.
 */
final class SuppressQueues {

  /** A SUPPRESS slot of a reference queue: a null value, as in the block (ADR-0004). */
  static final String S = null;

  /**
   * Values a source queue holds: {@code ""} is a value and whitespace is part of it. Some are what
   * createIf takes as true ({@code 1}, {@code true} in any case), some as false ({@code 0}, {@code
   * false}), and some no boolean, on which it raises its data error (#133).
   */
  static final String[] SAMPLE = {
    "", "a", " b ", "1", "true", "TRUE", "True", "0", "false", "yes", " true", "Grüße", "x\ty"
  };

  private SuppressQueues() {}

  /**
   * A queue as its contexts of slots, {@link #S} for SUPPRESS: the reference a block is built from
   * and read against.
   *
   * @param name the case name: the edge it crosses, or its seed
   * @param contexts the slots of each context, in order
   */
  record Queue(String name, List<List<String>> contexts) {

    /** Every slot, context after context. */
    List<String> flat() {
      List<String> all = new ArrayList<>();
      contexts.forEach(all::addAll);
      return all;
    }

    /** A fresh values array, a null at each SUPPRESS slot. */
    String[] values() {
      return flat().toArray(String[]::new);
    }

    /** Fresh context starts with the end sentinel (ADR-0004). */
    int[] starts() {
      int[] starts = new int[contexts.size() + 1];
      for (int c = 0; c < contexts.size(); c++) {
        starts[c + 1] = starts[c] + contexts.get(c).size();
      }
      return starts;
    }

    /** A fresh SUPPRESS bitset of the shape ADR-0004 gives it since #51. */
    long[] suppress() {
      return bits(values());
    }

    QueueBlock block() {
      return QueueBlock.of(values(), starts(), suppress());
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /** One context of slots; {@link #S} may stand among them. */
  static List<String> context(String... slots) {
    return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(slots)));
  }

  @SafeVarargs
  static Queue queue(String name, List<String>... contexts) {
    List<List<String>> list = new ArrayList<>();
    for (List<String> context : contexts) {
      list.add(context);
    }
    return new Queue(name, Collections.unmodifiableList(list));
  }

  /** {@code n} slots of the value {@code "v<k>"}, with {@link #S} at the slot indexes given. */
  static List<String> slots(int n, int... suppressed) {
    List<String> slots = new ArrayList<>();
    for (int k = 0; k < n; k++) {
      slots.add("v" + k);
    }
    for (int k : suppressed) {
      slots.set(k, S);
    }
    return Collections.unmodifiableList(slots);
  }

  /**
   * The SUPPRESS bitset of ADR-0004 as #51 shapes it: bit {@code k} of word {@code k / 64} is set
   * exactly where {@code values[k]} is null, and the array ends at its last word with a set bit, so
   * a queue without SUPPRESS has an empty one.
   */
  static long[] bits(String[] values) {
    int last = -1;
    for (int k = 0; k < values.length; k++) {
      if (values[k] == null) {
        last = k;
      }
    }
    long[] bits = new long[last < 0 ? 0 : last / 64 + 1];
    for (int k = 0; k < values.length; k++) {
      if (values[k] == null) {
        bits[k / 64] |= 1L << (k % 64);
      }
    }
    return bits;
  }

  /**
   * The block as contexts of slots, {@link #S} where {@code suppressed} is true, checking
   * ADR-0004's invariants on the way: the starts never decrease and end at the slot count, and a
   * slot holds a null value exactly when it is SUPPRESS.
   */
  static List<List<String>> read(QueueBlock block) {
    List<List<String>> contexts = new ArrayList<>();
    int previousEnd = 0;
    for (int c = 0; c < block.contexts(); c++) {
      assertThat(block.start(c)).as("start(%d)", c).isEqualTo(previousEnd);
      assertThat(block.end(c)).as("end(%d)", c).isGreaterThanOrEqualTo(block.start(c));
      List<String> context = new ArrayList<>();
      for (int i = block.start(c); i < block.end(c); i++) {
        if (block.suppressed(i)) {
          assertThat(block.value(i)).as("the value of SUPPRESS slot index %d", i).isNull();
          context.add(S);
        } else {
          assertThat(block.value(i)).as("the value of slot index %d", i).isNotNull();
          context.add(block.value(i));
        }
      }
      contexts.add(context);
      previousEnd = block.end(c);
    }
    assertThat(block.slots()).as("slots() is the sentinel").isEqualTo(previousEnd);
    return contexts;
  }

  /** Whether each slot of {@code block} is SUPPRESS, slot index by slot index. */
  static List<Boolean> suppressedSlots(QueueBlock block) {
    List<Boolean> bits = new ArrayList<>();
    for (int i = 0; i < block.slots(); i++) {
      bits.add(block.suppressed(i));
    }
    return bits;
  }

  /**
   * The edges every property over one queue crosses on every run: no context, empty contexts,
   * SUPPRESS alone, SUPPRESS on both sides of a word boundary of the bitset, and SUPPRESS followed
   * by whole words without it.
   */
  static Stream<Queue> edges() {
    return Stream.of(
        // no context at all, {0}: a function result can be one, a source queue never (ADR-0004)
        queue("no context"),
        queue("one empty context", context()),
        queue("no SUPPRESS", context("a", "b"), context(), context("")),
        queue("only SUPPRESS", context(S), context(S, S)),
        // the worked example of fn/createIf/001: Tag true, false, true in one context
        queue("\"\" | SUPPRESS | \"\"", context("", S, "")),
        queue("SUPPRESS at slot indexes 63 and 64", slots(65, 63, 64)),
        queue("SUPPRESS at slot index 64 only", slots(66, 64)),
        queue("SUPPRESS at slot index 0, then two words without", slots(130, 0)),
        queue(
            "SUPPRESS across contexts and an empty context",
            context("1", S),
            context(),
            context(S, "true", "0")));
  }

  /**
   * A seeded random queue. Some queues have many contexts and some contexts many slots, so the
   * bitset spans several words; empty contexts, queues without contexts and SUPPRESS come up often.
   */
  static Queue seeded(String name, RandomGenerator random) {
    List<List<String>> contexts = new ArrayList<>();
    int count = random.nextInt(8) == 0 ? random.nextInt(40) : random.nextInt(5);
    for (int c = 0; c < count; c++) {
      List<String> context = new ArrayList<>();
      int slots = random.nextInt(8) == 0 ? random.nextInt(40) : random.nextInt(4);
      for (int k = 0; k < slots; k++) {
        context.add(random.nextInt(5) == 0 ? S : SAMPLE[random.nextInt(SAMPLE.length)]);
      }
      contexts.add(Collections.unmodifiableList(context));
    }
    return new Queue(name, List.copyOf(contexts));
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /** The edges, then 200 seeded queues, the seed in the case name. */
  static Stream<Queue> queues() {
    return Stream.concat(
        edges(), seeds().mapToObj(seed -> seeded("seed " + seed, new SplittableRandom(seed))));
  }
}
