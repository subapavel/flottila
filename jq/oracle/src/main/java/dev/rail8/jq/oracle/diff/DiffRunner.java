package dev.rail8.jq.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.cli.Invocation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.ToIntFunction;

/**
 * Runs a differential case through this engine's in-process command line and judges it against the reference jq's
 * recording. The expected outcome is the recording's; for a deviation case, each part its {@code engine-*} keys give
 * replaces the recording's part. The rules, in order:
 *
 * <ol>
 *   <li>a deviation case whose slug names no row of {@code DEVIATIONS.md} is an error, before the engine runs, and so
 *       is a case with {@code limit recursion-depth} for a runner without a limited entry;
 *   <li>an exception out of the entry is FAIL if its message carries the Stub prefix, otherwise CRASH;
 *   <li>the engine's stderr carrying the Stub prefix is FAIL;
 *   <li>a case without a recording, or whose def differs from the recording's, is STALE;
 *   <li>a deviation case whose recording equals every part its {@code engine-*} keys give is FAIL, "deviation gone";
 *   <li>otherwise the exit code, stdout and stderr must equal the expected outcome byte for byte, except that stderr
 *       is not compared when the expected stderr has a line starting {@code jq: error: syntax error}: PASS or FAIL.
 * </ol>
 */
public final class DiffRunner {

  private static final String SYNTAX_ERROR = "\njq: error: syntax error";
  /** Bytes shown of a differing stream, starting a little before the first difference. */
  private static final int WINDOW = 200;
  private static final int BEFORE = 40;

  private final ToIntFunction<Invocation> entry;
  private final LongFunction<ToIntFunction<Invocation>> limited;
  private final Path scratch;
  private final Set<String> deviations;

  /**
   * A runner of the cases without {@code limit recursion-depth}.
   *
   * @param entry the in-process entry, {@code Main::run}
   * @param scratch where each run's working directory is made and deleted again
   * @param deviations the slugs that {@code DEVIATIONS.md} names, {@link Deviations#slugs}
   */
  public DiffRunner(ToIntFunction<Invocation> entry, Path scratch, Set<String> deviations) {
    this(entry, null, scratch, deviations);
  }

  /**
   * @param entry the in-process entry, {@code Main::run}
   * @param limited the entry of a case with {@code limit recursion-depth n}, given {@code n}: the engine under that
   *     budget; null for a runner of no such case
   * @param scratch where each run's working directory is made and deleted again
   * @param deviations the slugs that {@code DEVIATIONS.md} names, {@link Deviations#slugs}
   */
  public DiffRunner(ToIntFunction<Invocation> entry, LongFunction<ToIntFunction<Invocation>> limited, Path scratch, Set<String> deviations) {
    this.entry = entry;
    this.limited = limited;
    this.scratch = scratch;
    this.deviations = Set.copyOf(deviations);
  }

  /**
   * The verdict of one case; {@code recording} is null when the case was never recorded. A case with {@code limit
   * recursion-depth} runs through the limited entry.
   *
   * @throws IllegalArgumentException when the case's deviation slug names no row of {@code DEVIATIONS.md}, or when the
   *     case has {@code limit recursion-depth} and this runner has no limited entry
   */
  public Verdict run(DiffCase c, Recording recording) {
    if (c.deviation() != null && !deviations.contains(c.deviation())) {
      throw new IllegalArgumentException("case " + c.id() + ": deviation '" + c.deviation() + "' names no row of DEVIATIONS.md");
    }
    ToIntFunction<Invocation> cli = entry;
    if (c.recursionDepth() != null) {
      if (limited == null) {
        throw new IllegalArgumentException("case " + c.id() + ": limit recursion-depth, and this runner has no limited entry");
      }
      cli = limited.apply(c.recursionDepth());
    }
    Outcome engine;
    try (CaseDirectory dir = CaseDirectory.of(c.files(), scratch)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      Invocation invocation =
          new Invocation(c.args(), c.environment(dir.path()), dir.path(), new ByteArrayInputStream(c.stdin()), out, err);
      int rc;
      try {
        rc = cli.applyAsInt(invocation);
      } catch (RuntimeException e) {
        String line = CaseFormat.escape(e.toString().getBytes(UTF_8));
        if (String.valueOf(e.getMessage()).contains(Verdict.STUB)) {
          return new Verdict(Verdict.Kind.FAIL, "the engine ended with a Stub: " + line, c.id() + ": FAIL, the engine ended with a Stub: " + e);
        }
        return new Verdict(Verdict.Kind.CRASH, line, c.id() + ": CRASH, the entry threw " + e);
      }
      engine = new Outcome(rc, out.toByteArray(), err.toByteArray());
    }
    return judge(c, recording, engine);
  }

  private static Verdict judge(DiffCase c, Recording recording, Outcome engine) {
    // Where the expected outcome comes from, as the diff names it.
    boolean deviation = c.deviation() != null;
    String name = deviation ? "expect" : "jq-ref";
    String against = deviation ? " against deviation " + c.deviation() + "'s engine-* keys, the recording where one is absent" : "";
    if (new String(engine.stderr(), UTF_8).contains(Verdict.STUB)) {
      String headline = "FAIL" + against + ", the engine ended with a Stub";
      String diff =
          recording == null
              ? c.id() + ": " + headline + "; the case has no recording\n"
              : diff(c.id(), headline, name, c.engine().over(recording.outcome()), engine, true);
      return new Verdict(Verdict.Kind.FAIL, "the engine ended with a Stub", diff);
    }
    if (recording == null) {
      String why = "no recording; run scripts/oracle-record.sh";
      return new Verdict(Verdict.Kind.STALE, why, c.id() + ": STALE, " + why);
    }
    if (!recording.def().equals(c.def())) {
      String why = "the case's def " + c.def() + " differs from the recording's " + recording.def() + "; re-record with scripts/oracle-record.sh";
      return new Verdict(Verdict.Kind.STALE, why, c.id() + ": STALE, " + why);
    }
    if (deviation && c.engine().matches(recording.outcome())) {
      String why = "jq-ref's recording equals the engine-* keys of deviation " + c.deviation() + "; drop the case's deviation and engine-* lines, and the DEVIATIONS.md row if no case is left";
      return new Verdict(Verdict.Kind.FAIL, "deviation gone", c.id() + ": FAIL, deviation gone: " + why + "\n");
    }
    Outcome ref = c.engine().over(recording.outcome());
    boolean compareStderr = !("\n" + new String(ref.stderr(), UTF_8)).contains(SYNTAX_ERROR);
    List<String> why = new ArrayList<>();
    if (ref.rc() != engine.rc()) why.add("rc " + engine.rc() + ", " + name + " " + ref.rc());
    int d = Arrays.mismatch(ref.stdout(), engine.stdout());
    if (d >= 0) why.add("stdout differs at byte " + d);
    d = Arrays.mismatch(ref.stderr(), engine.stderr());
    if (compareStderr && d >= 0) why.add("stderr differs at byte " + d);
    if (why.isEmpty()) return new Verdict(Verdict.Kind.PASS, compareStderr ? "" : "stderr not compared: syntax error", "");
    return new Verdict(Verdict.Kind.FAIL, String.join("; ", why), diff(c.id(), "FAIL" + against, name, ref, engine, compareStderr));
  }

  /**
   * The readable comparison: each part either "same" or the expected and the engine's bytes, escaped as in the case
   * format.
   *
   * @param name what the expected outcome is called: {@code jq-ref}, or {@code expect} for a deviation case
   */
  private static String diff(String id, String headline, String name, Outcome ref, Outcome engine, boolean compareStderr) {
    StringBuilder b = new StringBuilder(id).append(": ").append(headline).append('\n');
    if (ref.rc() == engine.rc()) b.append("  rc      same\n");
    else b.append("  rc      ").append(name).append(' ').append(ref.rc()).append("\n          engine ").append(engine.rc()).append('\n');
    stream(b, "stdout", name, ref.stdout(), engine.stdout());
    if (compareStderr) stream(b, "stderr", name, ref.stderr(), engine.stderr());
    else b.append("  stderr  not compared: jq-ref's is a syntax error (ADR-0020)\n");
    return b.toString();
  }

  private static void stream(StringBuilder b, String part, String name, byte[] ref, byte[] engine) {
    String label = String.format("  %-8s", part);
    int d = Arrays.mismatch(ref, engine);
    if (d < 0) {
      b.append(label).append("same\n");
      return;
    }
    b.append(label).append(name).append(' ').append(window(ref, d)).append('\n');
    b.append("          engine ").append(window(engine, d)).append('\n');
    b.append("          first difference at byte ").append(d).append('\n');
  }

  /** Up to {@value #WINDOW} bytes from a little before the first difference, escaped. */
  private static String window(byte[] bytes, int difference) {
    if (bytes.length == 0) return "(empty)";
    int from = Math.max(0, difference - BEFORE);
    int to = Math.min(bytes.length, from + WINDOW);
    String shown = CaseFormat.escape(Arrays.copyOfRange(bytes, from, to));
    return (from > 0 ? "..." : "") + shown + (to < bytes.length ? "... (" + bytes.length + " bytes)" : "");
  }
}
