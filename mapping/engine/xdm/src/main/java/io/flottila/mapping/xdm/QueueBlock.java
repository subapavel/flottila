package io.flottila.mapping.xdm;

import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * The runtime representation of a queue (ADR-0004): {@code n} slots in {@code values}, {@code c +
 * 1} {@code contextStarts}, where {@code [0] = 0}, {@code [c] = n} and no entry decreases, and the
 * SUPPRESS bitset {@code suppress}. Context {@code k} spans the slot indexes {@code start(k)} to
 * {@code end(k)}, the end excluded. A context index and a slot index count from 0: a slot index is
 * the slot number minus 1. A block with no contexts ({@code {0}}) differs from one with one empty
 * context ({@code {0, 0}}).
 *
 * <p>A SUPPRESS slot has a {@code null} value and its bit set: bit {@code k} of word {@code k / 64}
 * is set exactly when {@code values[k]} is null, and the bitset ends at its last word with a set
 * bit, so a block without SUPPRESS has an empty one (ADR-0004 since #51).
 *
 * <p>A block is immutable. It takes the arrays it is given without copying them, so that context
 * operations can share them; whoever hands them over no longer writes them.
 */
public final class QueueBlock {

  /** The bitset of a block without SUPPRESS. It has no word, so nobody can write it. */
  private static final long[] NO_SUPPRESS = new long[0];

  private final String[] values;
  private final int[] contextStarts;
  private final long[] suppress;

  private QueueBlock(String[] values, int[] contextStarts, long[] suppress) {
    this.values = values;
    this.contextStarts = contextStarts;
    this.suppress = suppress;
  }

  /**
   * A block without SUPPRESS over {@code values} split at {@code contextStarts}.
   *
   * @throws IllegalArgumentException unless the starts begin at 0, never decrease and end with the
   *     sentinel {@code values.length}, and no value is null
   * @throws CancellationException when the thread is interrupted while the arrays are checked
   */
  public static QueueBlock of(String[] values, int[] contextStarts) {
    return of(values, contextStarts, NO_SUPPRESS);
  }

  /**
   * A block over {@code values} split at {@code contextStarts}, with the SUPPRESS bitset {@code
   * suppress}.
   *
   * @throws IllegalArgumentException unless the starts begin at 0, never decrease and end with the
   *     sentinel {@code values.length}, and {@code suppress} sets exactly the bits of the null
   *     values and ends at its last word with a set bit
   * @throws CancellationException when the thread is interrupted while the arrays are checked
   */
  public static QueueBlock of(String[] values, int[] contextStarts, long[] suppress) {
    if (!Arrays.equals(ofSlots(values, contextStarts).suppress, suppress)) {
      throw new IllegalArgumentException(
          "the SUPPRESS bitset does not set exactly the bits of the null values, ending at its last"
              + " word with a set bit");
    }
    return new QueueBlock(values, contextStarts, suppress);
  }

  /**
   * A block over {@code values} split at {@code contextStarts}, a null value a SUPPRESS slot: the
   * bitset is the one the null values give.
   *
   * @throws IllegalArgumentException as {@link #of(String[], int[])} does for the starts
   * @throws CancellationException when the thread is interrupted while the arrays are read
   */
  static QueueBlock ofSlots(String[] values, int[] contextStarts) {
    // the values first: a block with slots has a context, whose start the check below reads
    long[] suppress = bits(values);
    checkStarts(contextStarts, values.length);
    return new QueueBlock(values, contextStarts, suppress);
  }

  /**
   * The SUPPRESS bitset of {@code values}: a bit at each null value, ending at the last word with a
   * set bit.
   *
   * @throws CancellationException when the thread is interrupted
   */
  private static long[] bits(String[] values) {
    long[] bits = NO_SUPPRESS;
    int words = 0;
    for (int k = 0; k < values.length; k++) {
      // every loop over slots checks for interruption (ADR-0021)
      if (Thread.currentThread().isInterrupted()) {
        throw new CancellationException("the queue block was being read when interrupted");
      }
      if (values[k] == null) {
        int word = k >>> 6;
        if (word >= bits.length) {
          bits = Arrays.copyOf(bits, Math.max(word + 1, 2 * bits.length));
        }
        bits[word] |= 1L << k;
        words = word + 1;
      }
    }
    return Arrays.copyOf(bits, words);
  }

  /**
   * @throws IllegalArgumentException unless {@code contextStarts} begin at 0, never decrease and
   *     end with the sentinel {@code slots}
   * @throws CancellationException when the thread is interrupted while the starts are checked
   */
  private static void checkStarts(int[] contextStarts, int slots) {
    if (contextStarts.length == 0) {
      throw new IllegalArgumentException("contextStarts has no entry, not even the end sentinel");
    }
    if (contextStarts[0] != 0) {
      throw new IllegalArgumentException("contextStarts[0] is " + contextStarts[0] + ", not 0");
    }
    for (int k = 1; k < contextStarts.length; k++) {
      // every loop over contexts checks for interruption (ADR-0021)
      if (Thread.currentThread().isInterrupted()) {
        throw new CancellationException("the queue block was being checked when interrupted");
      }
      if (contextStarts[k] < contextStarts[k - 1]) {
        throw new IllegalArgumentException("contextStarts decreases at " + k);
      }
    }
    int sentinel = contextStarts[contextStarts.length - 1];
    if (sentinel != slots) {
      throw new IllegalArgumentException(
          "the end sentinel is " + sentinel + ", but there are " + slots + " slots");
    }
  }

  /** The number of contexts. */
  public int contexts() {
    return contextStarts.length - 1;
  }

  /** The number of slots across all contexts. */
  public int slots() {
    return values.length;
  }

  /** The slot index of the first slot of the context at index {@code context}. */
  public int start(int context) {
    return contextStarts[context];
  }

  /** The slot index after the last slot of the context at index {@code context}. */
  public int end(int context) {
    return contextStarts[context + 1];
  }

  /** The value at slot index {@code slot}; {@code null} for a SUPPRESS slot. */
  public String value(int slot) {
    return values[slot];
  }

  /** Whether the slot at index {@code slot} is SUPPRESS: whether its bit is set. */
  public boolean suppressed(int slot) {
    int word = slot >>> 6;
    return word < suppress.length && (suppress[word] & 1L << slot) != 0;
  }

  /**
   * A block over this block's own values and SUPPRESS bitset, split at {@code example}'s context
   * starts, which it shares too: an operation that takes another queue's boundaries copies nothing
   * (ADR-0004).
   *
   * @throws IllegalArgumentException unless {@code example} has as many slots as this block
   */
  QueueBlock withContextsOf(QueueBlock example) {
    return withContextStarts(example.contextStarts);
  }

  /**
   * A block over this block's own values and SUPPRESS bitset, split at {@code contextStarts}
   * instead: a context operation that changes only the boundaries shares both (ADR-0004).
   *
   * @throws IllegalArgumentException as {@link #of(String[], int[])} does for the starts
   */
  QueueBlock withContextStarts(int[] contextStarts) {
    checkStarts(contextStarts, values.length);
    return new QueueBlock(values, contextStarts, suppress);
  }

  /**
   * A block over {@code values}, one per slot of this block and a null value a SUPPRESS slot, split
   * at this block's own context starts: a per-slot function shares them (ADR-0004).
   */
  QueueBlock withValues(String[] values) {
    return new QueueBlock(values, contextStarts, bits(values));
  }

  /**
   * A block over {@code values}, one per slot of this block and null exactly where this block's
   * value is, sharing this block's context starts and SUPPRESS bitset: the fast path of a one-input
   * {@code Lift} (ADR-0006).
   */
  QueueBlock withSameSuppress(String[] values) {
    return new QueueBlock(values, contextStarts, suppress);
  }

  /**
   * Builds a block context by context: {@link #newContext()} opens a context, {@link
   * #append(String)} and {@link #appendSuppress()} add a slot to it. Without a {@code newContext()}
   * the block has no context.
   */
  public static final class Builder {

    private String[] values = new String[8];
    private int slots;

    /** The start of each context opened so far. */
    private int[] starts = new int[8];

    private int contexts;

    /** An empty builder: no context, no slot. */
    public Builder() {}

    /** Opens a new context; the slots appended next belong to it. */
    public void newContext() {
      if (contexts == starts.length) {
        starts = Arrays.copyOf(starts, contexts * 2);
      }
      starts[contexts++] = slots;
    }

    /** Appends {@code value}, which is not null, as a slot of the context opened last. */
    public void append(String value) {
      if (slots == values.length) {
        values = Arrays.copyOf(values, slots * 2);
      }
      values[slots++] = value;
    }

    /** Appends a SUPPRESS slot to the context opened last. */
    public void appendSuppress() {
      append(null);
    }

    /**
     * The block built so far.
     *
     * @throws IllegalArgumentException when a slot was appended before the first context opened
     */
    public QueueBlock build() {
      int[] contextStarts = Arrays.copyOf(starts, contexts + 1);
      contextStarts[contexts] = slots;
      return ofSlots(Arrays.copyOf(values, slots), contextStarts);
    }
  }
}
