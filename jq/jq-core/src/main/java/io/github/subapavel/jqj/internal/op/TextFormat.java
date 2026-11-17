package io.github.subapavel.jqj.internal.op;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's {@code @text} (#61): its input as {@code tostring} gives it, a string itself and any other value its compact
 * text (jq-ref 1.8.2, recorded: literal/format-text-*). The dump is a loop as long as the value, so it charges its run
 * one tick per started KiB of the text, the writer's rate (ADR-0008, ADR-0015, ADR-0030). It knows the text's length
 * only after the dump, so it charges the string then, before the value exists (ADR-0035 amendment); a string is
 * returned unchanged and charges nothing. {@code tostring} is this operation and {@code tojson} its {@link #dump} of
 * every value, a string included (#72). A read object's key proof in the dump charges the run's state (ADR-0044 as
 * amended).
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
