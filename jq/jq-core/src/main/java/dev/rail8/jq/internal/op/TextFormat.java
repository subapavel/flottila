package dev.rail8.jq.internal.op;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.internal.json.Printer;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;

/**
 * jq 1.8.2's {@code @text}: its input as {@code tostring} gives it, a string itself and any other value its compact
 * text. The dump is a loop as long as the value, so it charges its run one tick per started KiB of the text, the
 * writer's rate. It knows the text's length only after the dump, so it charges the string then, before the value
 * exists; a string is returned unchanged and charges nothing. {@code tostring} is this operation and {@code tojson} its
 * {@link #dump} of every value, a string included. A read object's key proof in the dump charges the run's state.
 */
public final class TextFormat implements Fn0 {
  @Override
  public Object apply(RunState state, Object in) {
    if (in instanceof JqString) {
      return in;
    }
    return dump(state, (JqValue) in);
  }

  /** {@code v}'s compact text as a built string, jq's {@code jv_dump_string}, charged as the class comment says. */
  static BuiltString dump(RunState state, JqValue v) {
    byte[] text = Printer.json(state, v).getBytes(UTF_8);
    state.tick((text.length + 1023) >>> 10);
    state.chargeBuilt(Charges.STRING + text.length);
    return BuiltString.of(text);
  }
}
