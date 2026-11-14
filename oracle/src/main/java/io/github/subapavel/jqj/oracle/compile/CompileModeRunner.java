package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import io.github.subapavel.jqj.oracle.diff.Outcome;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Judges one case's optimised run against its conservative run (ADR-0020 amendment for #60, ADR-0018). The rules, in
 * order:
 *
 * <ol>
 *   <li>an exception out of either run is CRASH, unless its message carries the Stub prefix;
 *   <li>either run reaching a Stub, by such an exception or in its stderr, is FAIL {@code stub:};
 *   <li>either run ending as a termination is FAIL {@code left out:}, so the case is never ratcheted;
 *   <li>otherwise both runs ended: equal exit code, stdout and stderr byte for byte is PASS, and anything else is a
 *       Disagreement, FAIL {@code disagree:}, which {@code scripts/ratchet.java regen} refuses.
 * </ol>
 */
public final class CompileModeRunner {

  /** The detail prefix of a Disagreement, which {@code regen} refuses (F-29). */
  public static final String DISAGREE = "disagree: ";

  private static final String[] MODES = {"optimised", "conservative"};

  /** Bytes shown of a differing stream, starting a little before the first difference. */
  private static final int WINDOW = 200;

  private static final int BEFORE = 40;

  private CompileModeRunner() {}

  /** The verdict of case {@code id} from its run under the optimised and under the conservative compile. */
  public static Verdict judge(String id, Run optimised, Run conservative) {
    Run[] runs = {optimised, conservative};
    for (int i = 0; i < runs.length; i++) {
      if (runs[i] instanceof Run.Threw(RuntimeException e) && !stub(e.getMessage())) {
        String why = "the " + MODES[i] + " run threw " + e;
        return new Verdict(Verdict.Kind.CRASH, oneLine(why), id + ": CRASH, " + why + "\n");
      }
    }
    for (int i = 0; i < runs.length; i++) {
      String stub =
          switch (runs[i]) {
            case Run.Threw(RuntimeException e) -> e.toString();
            case Run.Ended(Outcome o) when stub(new String(o.stderr(), UTF_8)) -> new String(o.stderr(), UTF_8);
            case Run.Ended _, Run.Terminated _ -> null;
          };
      if (stub != null) return fail(id, "stub: the " + MODES[i] + " run reached a Stub: " + stub);
    }
    for (int i = 0; i < runs.length; i++) {
      if (runs[i] instanceof Run.Terminated(String cause)) return fail(id, "left out: the " + MODES[i] + " run ended with " + cause);
    }
    Outcome a = ((Run.Ended) optimised).outcome();
    Outcome b = ((Run.Ended) conservative).outcome();
    if (a.sameAs(b)) return new Verdict(Verdict.Kind.PASS, "", "");
    List<String> why = new ArrayList<>();
    if (a.rc() != b.rc()) why.add("rc " + a.rc() + " " + MODES[0] + ", " + b.rc() + " " + MODES[1]);
    int d = Arrays.mismatch(a.stdout(), b.stdout());
    if (d >= 0) why.add("stdout differs at byte " + d);
    d = Arrays.mismatch(a.stderr(), b.stderr());
    if (d >= 0) why.add("stderr differs at byte " + d);
    return new Verdict(Verdict.Kind.FAIL, DISAGREE + String.join("; ", why), diff(id, a, b));
  }

  private static boolean stub(String text) {
    return String.valueOf(text).contains(Verdict.STUB);
  }

  private static Verdict fail(String id, String detail) {
    String line = oneLine(detail);
    return new Verdict(Verdict.Kind.FAIL, line, id + ": FAIL, " + line + "\n");
  }

  /** The readable comparison: each part either "same" or both runs' bytes, escaped as in the case format. */
  private static String diff(String id, Outcome a, Outcome b) {
    StringBuilder s = new StringBuilder(id).append(": FAIL, the optimised and the conservative compile disagree\n");
    if (a.rc() == b.rc()) s.append("  rc      same\n");
    else s.append("  rc      optimised    ").append(a.rc()).append("\n          conservative ").append(b.rc()).append('\n');
    stream(s, "stdout", a.stdout(), b.stdout());
    stream(s, "stderr", a.stderr(), b.stderr());
    return s.toString();
  }

  private static void stream(StringBuilder s, String part, byte[] a, byte[] b) {
    String label = String.format("  %-8s", part);
    int d = Arrays.mismatch(a, b);
    if (d < 0) {
      s.append(label).append("same\n");
      return;
    }
    s.append(label).append("optimised    ").append(window(a, d)).append('\n');
    s.append("          conservative ").append(window(b, d)).append('\n');
    s.append("          first difference at byte ").append(d).append('\n');
  }

  /** Up to {@value #WINDOW} bytes from a little before the first difference, escaped. */
  private static String window(byte[] bytes, int difference) {
    if (bytes.length == 0) return "(empty)";
    int from = Math.max(0, difference - BEFORE);
    int to = Math.min(bytes.length, from + WINDOW);
    String shown = CaseFormat.escape(Arrays.copyOfRange(bytes, from, to));
    return (from > 0 ? "..." : "") + shown + (to < bytes.length ? "... (" + bytes.length + " bytes)" : "");
  }

  /** The report's detail column holds one line without tabs. */
  private static String oneLine(String s) {
    return s.replace("\n", "\\n").replace("\t", "\\t");
  }
}
