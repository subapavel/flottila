package io.flottila.mapping.xdm;

import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * The queue algebra of the function family boolean (ADR-0025 "Families", #78): {@code
 * ifWithoutElse}, a context function because it makes SUPPRESS (ADR-0006), and the cast to a
 * boolean that the family and {@code createIf} share, PO's {@code Bool.toBoolean} as PO 7.5 and CPI
 * showed it (F-37, F-44). A function never changes its input; a block is immutable.
 */
public final class BooleanFunctions {

  private BooleanFunctions() {}

  /**
   * {@code value} cast to a boolean as PO's {@code Bool.toBoolean} casts it (ADR-0006, F-37, F-44):
   * {@code 1} and {@code true} in any case are true, {@code 0} and {@code false} in any case false.
   * White space is part of a value. Like the export's {@code lower-case}, {@code
   * toLowerCase(Locale.ROOT)} maps no character but an ASCII capital to a letter of {@code true} or
   * {@code false}; {@code equalsIgnoreCase} would also take U+017F, the long s, for {@code s},
   * which {@code lower-case} keeps.
   *
   * @throws QueueDataException when {@code value} is none of those, {@code ""} included: {@code
   *     Cannot cast '<value>' to boolean}, PO's text
   */
  public static boolean truth(String value) {
    String folded = value.toLowerCase(Locale.ROOT);
    if (value.equals("1") || folded.equals("true")) {
      return true;
    }
    if (value.equals("0") || folded.equals("false")) {
      return false;
    }
    throw new QueueDataException("Cannot cast '" + value + "' to boolean");
  }

  /**
   * {@code ifWithoutElse(condition, then)}, as PO 7.5 and CPI showed it (obs-boolean, F-44):
   *
   * <ul>
   *   <li>the result has as many contexts as {@code then}; context i pairs context i of both
   *       inputs, and it is empty when {@code condition} has no context i;
   *   <li>otherwise the longer context sets the slot count and the shorter reuses its last value,
   *       and an empty one gives SUPPRESS at every slot of the other;
   *   <li>SUPPRESS in either input at a slot gives SUPPRESS there;
   *   <li>otherwise the condition is cast by {@link #truth}: true gives the {@code then} value,
   *       false gives SUPPRESS.
   * </ul>
   *
   * Only a condition paired with a value is cast. PO shows {@code <null>}, not SUPPRESS, where the
   * condition's context is empty; both create nothing and keep their slot.
   *
   * @throws QueueDataException at the first slot, in order, whose condition is cast and is no
   *     boolean
   * @throws CancellationException when the thread is interrupted
   */
  public static QueueBlock ifWithoutElse(QueueBlock condition, QueueBlock then) {
    QueueBlock.Builder result = new QueueBlock.Builder();
    for (int c = 0; c < then.contexts(); c++) {
      // one check per context: its slots are read below, bounded by the longer input's count
      Interrupts.check("ifWithoutElse");
      result.newContext();
      if (c >= condition.contexts()) {
        continue;
      }
      int conditions = condition.end(c) - condition.start(c);
      int values = then.end(c) - then.start(c);
      int count = Math.max(conditions, values);
      for (int j = 0; j < count; j++) {
        int test = condition.start(c) + Math.min(j, conditions - 1);
        int value = then.start(c) + Math.min(j, values - 1);
        if (conditions == 0
            || values == 0
            || condition.suppressed(test)
            || then.suppressed(value)
            || !truth(condition.value(test))) {
          result.appendSuppress();
        } else {
          result.append(then.value(value));
        }
      }
    }
    return result.build();
  }
}
