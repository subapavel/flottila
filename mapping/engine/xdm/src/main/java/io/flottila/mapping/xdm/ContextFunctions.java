package io.flottila.mapping.xdm;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.IntFunction;

/**
 * The context functions of the runtime library over queue blocks (ADR-0025): the algebra, where the
 * sharing rules of ADR-0004 hold. {@code engine/runtime} binds each by its catalogue name. A
 * function never changes its input; a block is immutable.
 */
public final class ContextFunctions {

  private ContextFunctions() {}

  /**
   * {@code collapseContexts}: the first slot of each context of {@code queue}, a SUPPRESS kept as
   * SUPPRESS, and {@code ""} for an empty context, all in one context (#2 §4; observed on PO 7.50
   * and CPI, F-47). A queue with no slot and at most one context, one empty context or none, gives
   * one empty context: PO holds such a queue as nothing at all, no value and no context change, and
   * writes no value for it (fn/collapseContexts/003). It copies references only (ADR-0004).
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock collapseContexts(QueueBlock queue) {
    int contexts = queue.slots() == 0 && queue.contexts() <= 1 ? 0 : queue.contexts();
    String[] firsts = new String[contexts];
    for (int c = 0; c < contexts; c++) {
      Interrupts.check("collapseContexts");
      // a SUPPRESS first slot's value is null, which stays SUPPRESS in the result
      firsts[c] = queue.end(c) > queue.start(c) ? queue.value(queue.start(c)) : "";
    }
    return QueueBlock.ofSlots(firsts, new int[] {0, contexts});
  }

  /**
   * {@code createIf}: per slot of {@code condition}, its value cast as PO's {@code Bool.toBoolean}
   * does ({@link BooleanFunctions#truth}, ADR-0006, amended with #133, F-37): {@code ""} where it
   * is {@code 1} or {@code true} in any case, SUPPRESS where it is {@code 0} or {@code false} in
   * any case, and SUPPRESS where the slot is SUPPRESS. White space is part of a value, and the case
   * is folded for ASCII letters only, as the export's {@code lower-case} folds them. The result
   * keeps the slots and shares the context starts of {@code condition} (ADR-0004).
   *
   * @throws QueueDataException at the first slot, in order, whose value is none of those, {@code
   *     ""} included: {@code Cannot cast '<value>' to boolean}, PO's text
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock createIf(QueueBlock condition) {
    String[] values = new String[condition.slots()];
    for (int i = 0; i < values.length; i++) {
      // its own check per slot, though the block's read of the values after this loop checks too
      // (ADR-0021)
      Interrupts.check("createIf");
      // a SUPPRESS slot's value is null: it stays SUPPRESS, uncast
      String value = condition.value(i);
      if (value != null) {
        values[i] = BooleanFunctions.truth(value) ? "" : null;
      }
    }
    return condition.withValues(values);
  }

  /**
   * {@code exists}: per context of {@code queue}, one context holding {@code true} for each of its
   * slots, SUPPRESS and {@code ""} included, or {@code false} once if it is empty (observed on PO
   * 7.50 and CPI, F-48: fn/exists/003, 004). A queue with no context at all gives none.
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock exists(QueueBlock queue) {
    // a context keeps its slot count or gets one slot, so the result has at most one slot more per
    // context
    String[] values = new String[queue.slots() + queue.contexts()];
    int[] starts = new int[queue.contexts() + 1];
    int slots = 0;
    for (int c = 0; c < queue.contexts(); c++) {
      // one check per context: its slots are filled in one call, without a loop of this class
      Interrupts.check("exists");
      starts[c] = slots;
      int size = queue.end(c) - queue.start(c);
      if (size == 0) {
        values[slots++] = "false";
      } else {
        Arrays.fill(values, slots, slots + size, "true");
        slots += size;
      }
    }
    starts[queue.contexts()] = slots;
    return QueueBlock.ofSlots(Arrays.copyOf(values, slots), starts);
  }

  /**
   * {@code formatByExample(values, example)}: every slot of {@code values}, in order, split at the
   * context changes of {@code example}, empty contexts included (#2 §4). The contexts of {@code
   * values} play no part. It shares the values array and the SUPPRESS bitset of {@code values} and
   * the context starts of {@code example}, so it copies nothing (ADR-0004). A SUPPRESS of {@code
   * values} stays SUPPRESS at its slot, and a SUPPRESS of {@code example} counts as a value (an
   * assumed reading since #51).
   *
   * @throws QueueDataException unless both queues hold as many values: the SAP documentation
   *     requires equal counts, where empty contexts are not counted, and raises an exception
   *     otherwise (#2 §4)
   * @throws CancellationException when the thread is interrupted while the context starts are
   *     checked
   */
  public static QueueBlock formatByExample(QueueBlock values, QueueBlock example) {
    if (values.slots() != example.slots()) {
      throw new QueueDataException(
          "formatByExample needs as many values in its first input as in its second: the first"
              + " has "
              + values.slots()
              + " values, the second "
              + example.slots()
              + " values");
    }
    return values.withContextsOf(example);
  }

  /**
   * {@code mapWithDefault(queue, defaultValue)}: every empty context of {@code queue} replaced by
   * one context holding {@code defaultValue}, every other context kept with its slots (#2 §4). A
   * context of SUPPRESS only is not empty, so it is kept (observed on PO 7.50 and CPI, F-48:
   * fn/mapWithDefault/002, obs-context-functions ctx-2); {@code ""} is a value. A queue with no
   * context at all gives none, so only a context that is there is filled. It copies references only
   * (ADR-0004).
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock mapWithDefault(QueueBlock queue, String defaultValue) {
    // a context keeps its slots or gets one, so the result has at most one slot per context more
    String[] values = new String[queue.slots() + queue.contexts()];
    int[] starts = new int[queue.contexts() + 1];
    int slots = 0;
    for (int c = 0; c < queue.contexts(); c++) {
      // one check per context: its slots are read below, bounded by their count
      Interrupts.check("mapWithDefault");
      starts[c] = slots;
      if (queue.end(c) > queue.start(c)) {
        for (int i = queue.start(c); i < queue.end(c); i++) {
          values[slots++] = queue.value(i);
        }
      } else {
        values[slots++] = defaultValue;
      }
    }
    starts[queue.contexts()] = slots;
    return QueueBlock.ofSlots(Arrays.copyOf(values, slots), starts);
  }

  /**
   * {@code removeContexts}: every slot of {@code queue}, in order, in one context, each SUPPRESS at
   * its own slot. It shares the values array and the SUPPRESS bitset and builds only the context
   * starts {@code {0, n}} (ADR-0004), so a queue with no context at all gives one empty context.
   */
  public static QueueBlock removeContexts(QueueBlock queue) {
    return queue.withContextStarts(new int[] {0, queue.slots()});
  }

  /**
   * {@code replaceValue(queue, replacement)}: {@code replacement} at every slot of {@code queue},
   * SUPPRESS included, so the result holds no SUPPRESS (observed, PO 7.50 and CPI, F-45). It shares
   * the context starts (ADR-0004), so an empty context stays empty and a queue with no context at
   * all gives none.
   *
   * @throws CancellationException when the thread is interrupted while the result is read
   */
  public static QueueBlock replaceValue(QueueBlock queue, String replacement) {
    String[] values = new String[queue.slots()];
    Arrays.fill(values, replacement);
    // the block reads the values for their bitset, checking for interruption per slot (ADR-0021)
    return queue.withValues(values);
  }

  // sort and sortByKey (#59, owner decision Q4 of #48): each context on its own, a stable sort
  // (#2 §4), so equal keys keep their input order in either direction. The runtime binds the
  // comparison and the direction once per call site. A SUPPRESS key is the greatest key in every
  // comparison, so it comes last ascending and first descending (observed on PO 7.50 and CPI for a
  // case-sensitive sort and an ascending sortByKey, F-47), and a numeric sort does not parse it; a
  // SUPPRESS value moves with its key and stays SUPPRESS, as the result's bitset is read off its
  // values (assumed since #51, #61). A numeric sortByKey key must not be SUPPRESS (#2 §3,
  // documented): ADR-0006 since #51.

  /** How a sort compares two keys (owner decision Q4 of #48). */
  public enum Comparison {
    /** {@link String#compareTo}: by UTF-16 code unit, so U+1F600 comes before U+FF21. */
    LEXICOGRAPHIC,
    /** {@link String#compareToIgnoreCase}: by code point, each folded to lower of upper case. */
    CASE_INSENSITIVE,
    /**
     * By decimal value. Every key must be in the {@code xs:decimal} lexical form (ADR-0005), XML
     * whitespace around it allowed, as the export's cast collapses it; anything else stops the
     * sort.
     */
    NUMERIC
  }

  /**
   * {@code sort}: the values of each context of {@code queue} in the order of {@code comparison},
   * ascending or {@code descending}. The contexts stay as they are; the result copies references
   * only and shares the context starts (ADR-0004).
   *
   * @throws QueueDataException when {@code comparison} is numeric and a value is no decimal
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock sort(QueueBlock queue, Comparison comparison, boolean descending) {
    return sorted("sort", queue, queue, comparison, descending, true);
  }

  /**
   * {@code sortByKey}: the values of each context of {@code values} in the order of the keys at the
   * same slots of {@code keys} (#2 §4), by {@code comparison}, ascending or {@code descending}.
   * Only the keys are compared, so only they are parsed. The result copies references only and
   * shares the context starts of {@code values} (ADR-0004).
   *
   * @throws QueueDataException unless both queues have the same number of contexts and each context
   *     as many keys as values, or when {@code comparison} is numeric and a key is no decimal or is
   *     SUPPRESS (#2 §3: "numeric keys must not be equal to the constant ResultList.SUPPRESS")
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock sortByKey(
      QueueBlock keys, QueueBlock values, Comparison comparison, boolean descending) {
    if (keys.contexts() != values.contexts()) {
      throw new QueueDataException(
          "sortByKey needs one context of keys per context of values; contexts of keys: "
              + keys.contexts()
              + ", of values: "
              + values.contexts());
    }
    return sorted("sortByKey", keys, values, comparison, descending, false);
  }

  /**
   * The values of {@code values}, each context ordered by the keys at the same slots of {@code
   * keys}, which has as many contexts. A context with more or fewer keys than values stops it; the
   * contexts before it had as many, so the two queues' starts agree up to it. A numeric key may be
   * SUPPRESS only when {@code suppressKeys}.
   */
  private static QueueBlock sorted(
      String function,
      QueueBlock keys,
      QueueBlock values,
      Comparison comparison,
      boolean descending,
      boolean suppressKeys) {
    String[] sorted = new String[values.slots()];
    for (int c = 0; c < values.contexts(); c++) {
      // one check per context: a context's slots are read and sorted below, bounded by their count
      Interrupts.check(function);
      if (keys.end(c) != values.end(c)) {
        throw new QueueDataException(
            function
                + " needs as many keys as values in each context; in context "
                + (c + 1)
                + " keys: "
                + (keys.end(c) - keys.start(c))
                + ", values: "
                + (values.end(c) - values.start(c)));
      }
      int start = values.start(c);
      Integer[] slots = new Integer[values.end(c) - start];
      for (int k = 0; k < slots.length; k++) {
        slots[k] = start + k;
      }
      Comparator<Integer> order =
          order(function, keys, start, slots.length, comparison, suppressKeys);
      // Arrays.sort of objects is stable, and a reversed order keeps ties as they are
      Arrays.sort(slots, descending ? order.reversed() : order);
      for (int k = 0; k < slots.length; k++) {
        sorted[start + k] = values.value(slots[k]);
      }
    }
    return values.withValues(sorted);
  }

  /**
   * The order of the slots {@code start} to {@code start + size} of {@code keys}, by their keys. A
   * SUPPRESS key comes after every other key and is not parsed, except that in a numeric comparison
   * it stops the sort unless {@code suppressKeys} (ADR-0006 since #51, amended for F-47).
   */
  private static Comparator<Integer> order(
      String function,
      QueueBlock keys,
      int start,
      int size,
      Comparison comparison,
      boolean suppressKeys) {
    return switch (comparison) {
      case LEXICOGRAPHIC -> suppressLast(keys::value, String::compareTo);
      case CASE_INSENSITIVE -> suppressLast(keys::value, String::compareToIgnoreCase);
      case NUMERIC -> {
        // every key is parsed, once, even alone in its context where nothing is compared
        BigDecimal[] numbers = new BigDecimal[size];
        for (int k = 0; k < size; k++) {
          // a SUPPRESS key's value is null, which stays null where it may stand
          String key = keys.value(start + k);
          if (key == null && !suppressKeys) {
            throw new QueueDataException(
                function
                    + " compares numerically, and a key is SUPPRESS, which a numeric key must not"
                    + " be (#2 §3)");
          }
          numbers[k] = key == null ? null : decimal(function, key);
        }
        yield suppressLast(i -> numbers[i - start], BigDecimal::compareTo);
      }
    };
  }

  /** The slots in the order of their {@code key}s by {@code order}, a null key, SUPPRESS, last. */
  private static <K> Comparator<Integer> suppressLast(IntFunction<K> key, Comparator<K> order) {
    Comparator<K> keys = Comparator.nullsLast(order);
    return (i, j) -> keys.compare(key.apply(i), key.apply(j));
  }

  /** {@code value} as a decimal, read by {@link XsDecimal#parse} as the export reads it. */
  private static BigDecimal decimal(String function, String value) {
    return XsDecimal.parse(value)
        .orElseThrow(
            () ->
                new QueueDataException(
                    function
                        + " compares numerically, and \""
                        + value
                        + "\" is not in the xs:decimal lexical form (ADR-0005)"));
  }

  // splitByValue (#55): one function per mode, which the runtime binds once per call site. Each
  // splits every context of its input on its own, so the input's context boundaries stay, and an
  // empty context stays one empty context. A context gives at most one context more than it has
  // slots, which bounds the starts array by slots + contexts + 1, the end sentinel included. A
  // SUPPRESS slot stays a slot in every mode (observed on PO 7.50 and CPI, fn/splitByValue/019 to
  // 021, F-47).

  /**
   * {@code splitByValue} in mode {@code eachValue}: every value, and every SUPPRESS, in a context
   * of its own. It shares the values array and the SUPPRESS bitset and builds only the context
   * starts (ADR-0004).
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock splitByEachValue(QueueBlock queue) {
    return split(queue, true);
  }

  /**
   * {@code splitByValue} in mode {@code valueChange}: a new context at every value that is not
   * equal to the value before it in its context, as {@link String#equals} compares. SUPPRESS equals
   * SUPPRESS and no value, as SAP's SUPPRESS string would compare (observed, fn/splitByValue/020).
   * It shares the values array and the SUPPRESS bitset and builds only the context starts
   * (ADR-0004).
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock splitByValueChange(QueueBlock queue) {
    return split(queue, false);
  }

  /**
   * The context starts of {@code queue} plus one at each slot that starts a context: every slot
   * when {@code eachValue}, otherwise each slot whose value differs from the slot before it.
   */
  private static QueueBlock split(QueueBlock queue, boolean eachValue) {
    int[] starts = new int[queue.slots() + queue.contexts() + 1];
    int count = 0;
    for (int c = 0; c < queue.contexts(); c++) {
      // one check per context: its slots are read below, bounded by their count (ADR-0021)
      Interrupts.check("splitByValue");
      starts[count++] = queue.start(c);
      for (int i = queue.start(c) + 1; i < queue.end(c); i++) {
        // a SUPPRESS slot's value is null, which equals only another SUPPRESS slot's
        if (eachValue || !Objects.equals(queue.value(i), queue.value(i - 1))) {
          starts[count++] = i;
        }
      }
    }
    starts[count++] = queue.slots();
    // the block checks the starts it is given, and for interruption on the way, too
    return queue.withContextStarts(Arrays.copyOf(starts, count));
  }

  /**
   * {@code splitByValue} in mode {@code emptyValue}: every {@code ""} ends its context there and
   * starts a new one, and is itself dropped, so a {@code ""} first or last in a context leaves an
   * empty context there. SUPPRESS is no {@code ""}, so it stays a slot (observed,
   * fn/splitByValue/021). Dropping values needs a values array of its own, which holds the input's
   * value objects: no characters are copied (ADR-0004).
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock splitByEmptyValue(QueueBlock queue) {
    String[] values = new String[queue.slots()];
    int[] starts = new int[queue.slots() + queue.contexts() + 1];
    int slots = 0;
    int count = 0;
    for (int c = 0; c < queue.contexts(); c++) {
      // one check per context: its slots are read below, bounded by their count (ADR-0021)
      Interrupts.check("splitByValue");
      starts[count++] = slots;
      for (int i = queue.start(c); i < queue.end(c); i++) {
        // a SUPPRESS slot's value is null, which is no ""
        String value = queue.value(i);
        if ("".equals(value)) {
          starts[count++] = slots;
        } else {
          values[slots++] = value;
        }
      }
    }
    starts[count++] = slots;
    return QueueBlock.ofSlots(Arrays.copyOf(values, slots), Arrays.copyOf(starts, count));
  }

  /**
   * {@code useOneAsMany(value, occurrences, contexts)}: the one value of context k of {@code value}
   * once per slot of context k of {@code occurrences}, in order, split at the context starts of
   * {@code contexts}, which it shares (#2 §4). It copies references only (ADR-0004). A SUPPRESS
   * value is repeated as SUPPRESS and a SUPPRESS slot of {@code occurrences} is an occurrence like
   * any other (observed on PO 7.50 and CPI, F-48: the probes of obs-useoneasmany); the slots of
   * {@code contexts} give only their boundaries (assumed since #51).
   *
   * @throws QueueDataException unless {@code value} has as many contexts as {@code occurrences},
   *     each holding one value, SUPPRESS included, and {@code contexts} as many slots as {@code
   *     occurrences}, checked in this order (observed on PO 7.50 and CPI, F-48: fn/useOneAsMany/003
   *     to 009)
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock useOneAsMany(
      QueueBlock value, QueueBlock occurrences, QueueBlock contexts) {
    if (value.contexts() != occurrences.contexts()) {
      throw new QueueDataException(
          "useOneAsMany needs as many contexts in its first input as in its second: the first has "
              + value.contexts()
              + " contexts, the second "
              + occurrences.contexts());
    }
    // the slots of occurrences, counted across its contexts, are the slots of the result; a
    // SUPPRESS value is a null, repeated as one
    String[] repeated = new String[occurrences.slots()];
    for (int k = 0; k < value.contexts(); k++) {
      // one check per context: its slots are filled in one call, without a loop of this class
      Interrupts.check("useOneAsMany");
      int values = value.end(k) - value.start(k);
      if (values != 1) {
        throw new QueueDataException(
            "useOneAsMany needs one value in each context of its first input: its context "
                + (k + 1)
                + " has "
                + values
                + " values");
      }
      Arrays.fill(repeated, occurrences.start(k), occurrences.end(k), value.value(value.start(k)));
    }
    if (contexts.slots() != occurrences.slots()) {
      throw new QueueDataException(
          "useOneAsMany needs as many slots in its third input as in its second: the third has "
              + contexts.slots()
              + " slots, the second "
              + occurrences.slots());
    }
    return contexts.withValues(repeated);
  }
}
