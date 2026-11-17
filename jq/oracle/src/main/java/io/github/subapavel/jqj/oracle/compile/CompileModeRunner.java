package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import io.github.subapavel.jqj.oracle.diff.Outcome;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Judges one case's optimised run against its run under another mode (ADR-0020 amendment for #60, ADR-0018): the
 * conservative compile, or the trampoline run, the conservative compile with {@code stackBytes} 0 (F-58). Each verdict
 * names the other run by its mode. The rules, in order:
 *
 * <ol>
 *   <li>an exception out of either run is CRASH, unless its message carries the Stub prefix;
 *   <li>either run reaching a Stub, by such an exception or in its stderr, is FAIL {@code stub:};
 *   <li>for a case with {@code limit recursion-depth} (ADR-0047 #118), the other run, which stores no tail kind, passes
 *       only as {@code Terminated("BUDGET RECURSION_DEPTH")} while the optimised run ended: PASS; an optimised run that
 *       ended as a termination is FAIL {@code left out:}, and any other end of the other run FAIL {@code no depth end:};
 *   <li>either run ending as a termination is FAIL {@code left out:}, so the case is never ratcheted;
 *   <li>otherwise both runs ended: equal exit code, stdout and stderr byte for byte is PASS, and anything else is a
 *       Disagreement, FAIL {@code disagree:}, which {@code scripts/ratchet.java regen} refuses.
 * </ol>
 */
public final class CompileModeRunner {

  /** The detail prefix of a Disagreement, which {@code regen} refuses (F-29). */
  public static final String DISAGREE = "disagree: ";

  private static final String OPTIMISED = "optimised";

  /** The mode that is a compile of its own; every other mode is a run setting (F-58). */
  private static final String CONSERVATIVE = "conservative";

  /** The one end of a run that stores no tail kind under {@code limit recursion-depth} (ADR-0047 #118). */
  private static final String DEPTH = "BUDGET RECURSION_DEPTH";

  /** Bytes shown of a differing stream, starting a little before the first difference. */
  private static final int WINDOW = 200;

  private static final int BEFORE = 40;

  private CompileModeRunner() {}

  /** The verdict of case {@code id} from its run under the optimised and under the conservative compile. */
  public static Verdict judge(String id, Run optimised, Run conservative) {
    return judge(id, optimised, CONSERVATIVE, conservative);
  }

  /**
   * The verdict of case {@code id} from its run under the optimised compile and {@code other}, its run under
   * {@code mode}: {@code conservative} or {@code trampoline} (F-58), for a case without {@code limit recursion-depth}.
   */
  public static Verdict judge(String id, Run optimised, String mode, Run other) {
    return judge(id, optimised, mode, other, false);
  }

  /**
   * {@link #judge(String, Run, String, Run)} for a case with {@code limit recursion-depth} when {@code limited}
   * (ADR-0047 #118): the other run passes only as a termination over the recursion-depth budget.
   */
  public static Verdict judge(String id, Run optimised, String mode, Run other, boolean limited) {
    String[] modes = {OPTIMISED, mode};
    Run[] runs = {optimised, other};
    for (int i = 0; i < runs.length; i++) {
      if (runs[i] instanceof Run.Threw(RuntimeException e) && !stub(e.getMessage())) {
        String why = "the " + modes[i] + " run threw " + e;
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
      if (stub != null) return fail(id, "stub: the " + modes[i] + " run reached a Stub: " + stub);
    }
    if (limited) {
      if (optimised instanceof Run.Terminated(String cause)) return fail(id, "left out: the " + OPTIMISED + " run ended with " + cause);
      if (other instanceof Run.Terminated(String cause) && cause.equals(DEPTH)) return new Verdict(Verdict.Kind.PASS, "", "");
      String end = other instanceof Run.Terminated(String cause) ? "ended with " + cause : "ended with rc " + ((Run.Ended) other).outcome().rc();
      return fail(id, "no depth end: under limit recursion-depth the " + mode + " run must end with " + DEPTH + "; it " + end);
    }
    for (int i = 0; i < runs.length; i++) {
      if (runs[i] instanceof Run.Terminated(String cause)) return fail(id, "left out: the " + modes[i] + " run ended with " + cause);
    }
    Outcome a = ((Run.Ended) optimised).outcome();
    Outcome b = ((Run.Ended) other).outcome();
    if (a.sameAs(b)) return new Verdict(Verdict.Kind.PASS, "", "");
    List<String> why = new ArrayList<>();
    if (a.rc() != b.rc()) why.add("rc " + a.rc() + " " + modes[0] + ", " + b.rc() + " " + modes[1]);
    int d = Arrays.mismatch(a.stdout(), b.stdout());
    if (d >= 0) why.add("stdout differs at byte " + d);
    d = Arrays.mismatch(a.stderr(), b.stderr());
    if (d >= 0) why.add("stderr differs at byte " + d);
    return new Verdict(Verdict.Kind.FAIL, DISAGREE + String.join("; ", why), diff(id, mode, a, b));
  }

  private static boolean stub(String text) {
    return String.valueOf(text).contains(Verdict.STUB);
  }

  private static Verdict fail(String id, String detail) {
    String line = oneLine(detail);
    return new Verdict(Verdict.Kind.FAIL, line, id + ": FAIL, " + line + "\n");
  }

  /**
   * The readable comparison with the run of {@code mode}, the conservative compile or a run setting: each part either
   * "same" or both runs' bytes, escaped as in the case format, behind the runs' names in one column.
   */
  private static String diff(String id, String mode, Outcome a, Outcome b) {
    String other = mode.equals(CONSERVATIVE) ? "the conservative compile" : "the " + mode + " run";
    StringBuilder s = new StringBuilder(id).append(": FAIL, the optimised and ").append(other).append(" disagree\n");
    String[] names = {String.format("%-13s", OPTIMISED), String.format("%-13s", mode)};
    if (a.rc() == b.rc()) s.append("  rc      same\n");
    else s.append("  rc      ").append(names[0]).append(a.rc()).append("\n          ").append(names[1]).append(b.rc()).append('\n');
    stream(s, names, "stdout", a.stdout(), b.stdout());
    stream(s, names, "stderr", a.stderr(), b.stderr());
    return s.toString();
  }

  private static void stream(StringBuilder s, String[] names, String part, byte[] a, byte[] b) {
    String label = String.format("  %-8s", part);
    int d = Arrays.mismatch(a, b);
    if (d < 0) {
      s.append(label).append("same\n");
      return;
    }
    s.append(label).append(names[0]).append(window(a, d)).append('\n');
    s.append("          ").append(names[1]).append(window(b, d)).append('\n');
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
