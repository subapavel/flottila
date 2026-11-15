package io.flottila.mapping.xdm;

import java.math.BigDecimal;
import java.util.concurrent.CancellationException;

/**
 * The queue algebra of the function family statistic (ADR-0025 "Families", #75): {@code sum},
 * {@code average} and {@code count} give one value per context (#2 §5), {@code index} numbers every
 * slot of each context or of the whole queue, and {@code counter} the values of the whole queue. A
 * number is an {@code xs:decimal} (ADR-0005), read by {@link XsDecimal#parse} and written in the
 * XPath canonical form. SUPPRESS is no value to {@code sum}, {@code average}, {@code count} and
 * {@code counter}: they do not read, count or number it, and {@code counter} keeps it at its slot
 * (observed for {@code count}, an assumed reading of #75 for the others); {@code index} numbers it
 * as a value (observed, F-45). A function never changes its input; a block is immutable.
 */
public final class StatisticFunctions {

  private StatisticFunctions() {}

  /**
   * {@code sum}: per context of {@code values}, one context holding the total of its values; a
   * context without a value gives {@code 0} (an assumed reading of #75, as XPath's {@code
   * sum(())}). A queue with no context at all gives none.
   *
   * @throws QueueDataException when a value is no decimal
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock sum(QueueBlock values) {
    String[] sums = new String[values.contexts()];
    int[] starts = new int[sums.length + 1];
    for (int c = 0; c < sums.length; c++) {
      // one check per context: its slots are read below, bounded by their count
      Interrupts.check("sum");
      sums[c] = XsDecimal.canonical(total("sum", values, c));
      starts[c + 1] = c + 1;
    }
    return QueueBlock.ofSlots(sums, starts);
  }

  /**
   * {@code average}: per context of {@code values}, one context holding the total of its values
   * divided by their count by ADR-0005's rule, as Saxon-HE divides: rounded half-down to the
   * total's fractional digits, trailing zeros not counted, plus 18. A context without a value, of
   * SUPPRESS only too, has no count to divide by: the call stops there, as PO 7.5 and CPI stop with
   * "Division undefined" (observed for an empty context, F-45). A queue with no context at all
   * gives none.
   *
   * @throws QueueDataException when a value is no decimal
   * @throws ArithmeticException at the first context without a value, naming it from 1
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock average(QueueBlock values) {
    String[] averages = new String[values.contexts()];
    int[] starts = new int[averages.length + 1];
    for (int c = 0; c < averages.length; c++) {
      // one check per context: its slots are read below, bounded by their count
      Interrupts.check("average");
      BigDecimal total = total("average", values, c);
      int count = count(values, c);
      if (count == 0) {
        throw new ArithmeticException(
            "average: context " + (c + 1) + " holds no value, division undefined");
      }
      averages[c] = XsDecimal.canonical(XsDecimal.quotient(total, BigDecimal.valueOf(count)));
      starts[c + 1] = c + 1;
    }
    return QueueBlock.ofSlots(averages, starts);
  }

  /**
   * {@code count}: per context of {@code values}, one context holding the number of its values,
   * {@code ""} and text that is no number included. A queue with no context at all gives none.
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock count(QueueBlock values) {
    String[] counts = new String[values.contexts()];
    int[] starts = new int[counts.length + 1];
    for (int c = 0; c < counts.length; c++) {
      // one check per context: its slots are read below, bounded by their count
      Interrupts.check("count");
      counts[c] = String.valueOf(count(values, c));
      starts[c + 1] = c + 1;
    }
    return QueueBlock.ofSlots(counts, starts);
  }

  /**
   * {@code index}: the k-th slot of each context of {@code values}, counting from 0, becomes {@code
   * initial + k * increment}; when not {@code resetEachContext}, k counts across the whole queue
   * instead. A SUPPRESS slot is numbered as a value is, so the result holds no SUPPRESS (observed,
   * F-45). The values are not read. The result shares the context starts of {@code values}
   * (ADR-0004).
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock index(
      QueueBlock values, BigDecimal initial, BigDecimal increment, boolean resetEachContext) {
    String[] numbers = new String[values.slots()];
    long k = 0;
    for (int c = 0; c < values.contexts(); c++) {
      // one check per context: its slots are numbered below, bounded by their count
      Interrupts.check("index");
      if (resetEachContext) {
        k = 0;
      }
      for (int i = values.start(c); i < values.end(c); i++) {
        numbers[i] = number(initial, increment, k++);
      }
    }
    return values.withValues(numbers);
  }

  /**
   * {@code counter}: the k-th value of the whole of {@code values}, counting from 0, becomes {@code
   * initial + k * increment}. The values are not read, and each SUPPRESS stays at its slot without
   * a number (decided, F-43: PO and CPI have no counter with an input). The result shares the
   * context starts and the SUPPRESS bitset of {@code values} (ADR-0004).
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock counter(QueueBlock values, BigDecimal initial, BigDecimal increment) {
    String[] numbers = new String[values.slots()];
    long k = 0;
    for (int c = 0; c < values.contexts(); c++) {
      // one check per context: its slots are numbered below, bounded by their count
      Interrupts.check("counter");
      for (int i = values.start(c); i < values.end(c); i++) {
        if (!values.suppressed(i)) {
          numbers[i] = number(initial, increment, k++);
        }
      }
    }
    return values.withSameSuppress(numbers);
  }

  /** {@code initial + k * increment} in the XPath canonical form. */
  private static String number(BigDecimal initial, BigDecimal increment, long k) {
    return XsDecimal.canonical(initial.add(increment.multiply(BigDecimal.valueOf(k))));
  }

  /** The total of the values of the context at index {@code context} of {@code values}. */
  private static BigDecimal total(String function, QueueBlock values, int context) {
    BigDecimal total = BigDecimal.ZERO;
    for (int i = values.start(context); i < values.end(context); i++) {
      // a SUPPRESS slot's value is null: it is no value, so it adds nothing
      String value = values.value(i);
      if (value != null) {
        total =
            total.add(
                XsDecimal.parse(value)
                    .orElseThrow(
                        () ->
                            new QueueDataException(
                                function + ": \"" + value + "\" is no xs:decimal")));
      }
    }
    return total;
  }

  /**
   * The number of slots of the context at index {@code context} of {@code values} that hold a
   * value.
   */
  private static int count(QueueBlock values, int context) {
    int count = 0;
    for (int i = values.start(context); i < values.end(context); i++) {
      if (!values.suppressed(i)) {
        count++;
      }
    }
    return count;
  }
}
