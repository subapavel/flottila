package io.github.subapavel.jqj.internal.op;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;

/**
 * jq's string interpolation {@code "a\(f)b"} and {@code @text "a\(f)b"} (ADR-0038, jq-ref 1.8.2, recorded:
 * diff/interpolation.rec): its arguments are the literal's parts in order, its text parts as strings and one value per
 * interpolation, each as {@code tostring} gives it, a string's text as it is and any other value its compact text. The
 * dumps and the copy are loops as long as the result, so it charges one tick per started KiB of the result, as a
 * concatenation does (ADR-0034 amendment), and then the string, STRING plus the total length, before it allocates
 * (ADR-0035 amendment). A read object's key proof in a dump charges the run's state (ADR-0044 as amended).
 */
public final class Interpolate implements FnN {
  /** The interpolation of a literal, its parts the arguments. */
  public static final Operation OP = new Operation("interpolation", new Interpolate());

  private Interpolate() {}

  @Override
  public Object apply(RunState state, Object in, Object[] args) {
    MemorySegment[] texts = new MemorySegment[args.length];
    long length = 0;
    for (int i = 0; i < args.length; i++) {
      texts[i] =
          args[i] instanceof JqString s ? s.utf8() : MemorySegment.ofArray(Printer.json(state, (JqValue) args[i]).getBytes(UTF_8));
      length += texts[i].byteSize();
    }
    state.tick(Built.kibibytes(length));
    byte[] out = Built.string(state, length);
    int at = 0;
    for (MemorySegment text : texts) {
      MemorySegment.copy(text, JAVA_BYTE, 0, out, at, (int) text.byteSize());
      at += (int) text.byteSize();
    }
    return BuiltString.of(out);
  }
}
