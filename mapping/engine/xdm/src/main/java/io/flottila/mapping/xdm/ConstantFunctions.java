package io.flottila.mapping.xdm;

import java.math.BigInteger;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The queue algebra of the function family constant (ADR-0025 "Families", #79): {@code copyValue}
 * gives a generating block, one context holding the slot at a position of the whole queue, counted
 * from 0 with context boundaries ignored; a SUPPRESS slot counts as a position and is copied as
 * SUPPRESS (observed, F-46, #217). A position past the last slot, an empty queue's included, gives
 * one empty context (assumed, ADR-0006). A function never changes its input; a block is immutable.
 */
public final class ConstantFunctions {

  /** The {@code xs:integer} lexical form, with XML whitespace on either side. */
  private static final Pattern INTEGER = Pattern.compile("[ \t\r\n]*([+-]?[0-9]+)[ \t\r\n]*");

  /** The largest position: no queue has more slots than an {@code int} counts. */
  private static final BigInteger LAST = BigInteger.valueOf(Integer.MAX_VALUE);

  private ConstantFunctions() {}

  /**
   * {@code text} as a position: its {@code xs:nonNegativeInteger} lexical form, with XML whitespace
   * around it ignored, as the export's cast reads it. A position beyond {@link Integer#MAX_VALUE}
   * is beyond every queue's last slot, so it is that. Empty when {@code text} is none, such as a
   * negative number.
   */
  public static OptionalInt position(String text) {
    Matcher m = INTEGER.matcher(text);
    if (!m.matches()) {
      return OptionalInt.empty();
    }
    BigInteger position = new BigInteger(m.group(1));
    return position.signum() < 0
        ? OptionalInt.empty()
        : OptionalInt.of(position.min(LAST).intValue());
  }

  /**
   * {@code copyValue}: one context holding the slot of {@code values} at {@code position}, counted
   * from 0 across all its contexts, or one empty context when {@code values} has fewer slots. A
   * SUPPRESS slot counts as a position and is copied as SUPPRESS.
   *
   * @param position the position, not negative
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock copyValue(QueueBlock values, int position) {
    // one check: it reads one slot at most, by its index, and walks no context
    Interrupts.check("copyValue");
    QueueBlock.Builder copy = new QueueBlock.Builder();
    copy.newContext();
    if (position < values.slots()) {
      if (values.suppressed(position)) {
        copy.appendSuppress();
      } else {
        copy.append(values.value(position));
      }
    }
    return copy.build();
  }
}
