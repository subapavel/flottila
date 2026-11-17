package io.github.subapavel.jqj.oracle.jqtest;

import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Judges one jqtest case as jq_test.c's {@code run_jq_tests} does (ADR-0020, ADR-0024). The rules, in order:
 *
 * <ol>
 *   <li>an exception that is not jqj's is CRASH;
 *   <li>jqj ending with a Stub, wherever it happens, is FAIL;
 *   <li>a FAIL case fails if it compiles. Otherwise its lines are compared with the last compile error block, unless
 *       it is {@code %%FAIL IGNORE MSG} or its first line is a Bison syntax error, which check only that compiling
 *       fails;
 *   <li>any other case fails if it does not compile, if its input or an expected line is invalid, if an expected
 *       value changes through jqj's writer and reader, or on too few results, a mismatch or a superfluous result. An
 *       error after the last expected result passes.
 * </ol>
 */
public final class JqTestRunner {

  private static final String SYNTAX_ERROR = "jq: error: syntax error";

  private final Jqj jqj;

  public JqTestRunner(Jqj jqj) {
    this.jqj = jqj;
  }

  public Verdict run(JqTestCase c) {
    try {
      return c.mode() == JqTestCase.Mode.RESULTS ? results(c) : compileFails(c);
    } catch (JqjException e) {
      // Only a Stub gets here: every call into jqj below rethrows a Stub and handles any other JqjException.
      return fail(c, "jqj ended with a Stub: " + e.getMessage());
    } catch (RuntimeException e) {
      return new Verdict(Verdict.Kind.CRASH, oneLine(e.toString()), c.id() + ": CRASH, jqj threw " + e + "\n" + where(c));
    }
  }

  private Verdict compileFails(JqTestCase c) {
    JqjException error;
    try {
      jqj.compile(c.program());
      return fail(c, "compiled, but the case expects a compile error");
    } catch (JqjException e) {
      if (e.stub()) throw e;
      error = e;
    }
    if (c.mode() == JqTestCase.Mode.FAIL_IGNORE_MSG) return pass("message not compared: %%FAIL IGNORE MSG");
    if (!c.expected().isEmpty() && c.expected().getFirst().startsWith(SYNTAX_ERROR)) return pass("message not compared: syntax error");
    String why = messageMismatch(c.expected(), error.blocks().getLast());
    return why == null ? pass("") : fail(c, why);
  }

  /**
   * jq_test.c's comparison of a FAIL case's lines with the error text: each line must be a prefix of what is left,
   * which then loses the line and one LF after it; text left at the end fails.
   *
   * @return why they differ, null if they match
   */
  static String messageMismatch(List<String> expected, String error) {
    int at = 0;
    for (String line : expected) {
      if (!error.startsWith(line, at)) return "the compile error is '" + firstLine(error.substring(at)) + "', expected '" + line + "'";
      at += line.length();
      if (at < error.length() && error.charAt(at) == '\n') at++;
    }
    if (at < error.length()) return "the compile error has more text: '" + firstLine(error.substring(at)) + "'";
    return null;
  }

  private Verdict results(JqTestCase c) {
    Jqj.Filter filter;
    try {
      filter = jqj.compile(c.program());
    } catch (JqjException e) {
      if (e.stub()) throw e;
      return fail(c, "the program does not compile: " + e.getMessage());
    }
    List<JvValue> expected = new ArrayList<>();
    for (int k = 0; k < c.expected().size(); k++) {
      String text = c.expected().get(k);
      JvValue value;
      JvValue again;
      try {
        value = jqj.read(text);
        again = jqj.reread(text);
      } catch (JqjException e) {
        if (e.stub()) throw e;
        return fail(c, "expected result " + (k + 1) + " is invalid: " + e.getMessage());
      }
      if (!JvEqual.equal(value, again)) return fail(c, "expected result " + (k + 1) + " is " + again.text() + " after jqj's writer and reader");
      expected.add(value);
    }
    Collector results = new Collector(expected);
    String error = "";
    try {
      filter.run(c.input(), results);
    } catch (JqjException e) {
      if (e.stub()) throw e;
      if (e.kind() == JqjException.Kind.SYNTAX) return fail(c, "the input is invalid: " + e.getMessage());
      // An error ends the results; after the last expected one it passes, as in jq_test.c.
      error = ", then " + e.kind() + " " + e.getMessage();
    }
    if (results.mismatch >= 0) {
      return fail(c, "result " + (results.mismatch + 1) + " is " + results.mismatched.text() + ", expected " + c.expected().get(results.mismatch));
    }
    if (results.count < expected.size()) return fail(c, "insufficient results: " + results.count + " of " + expected.size() + error);
    if (results.superfluous != null) return fail(c, "a superfluous result " + results.superfluous.text());
    return pass("");
  }

  /** Compares each result with its expected value; takes one result more than expected, then stops the run. */
  private static final class Collector implements Predicate<JvValue> {
    private final List<JvValue> expected;
    int count;
    int mismatch = -1;
    JvValue mismatched;
    JvValue superfluous;

    Collector(List<JvValue> expected) {
      this.expected = expected;
    }

    @Override
    public boolean test(JvValue v) {
      int k = count++;
      if (k == expected.size()) {
        superfluous = v;
        return false;
      }
      if (mismatch < 0 && !JvEqual.equal(expected.get(k), v)) {
        mismatch = k;
        mismatched = v;
      }
      return true;
    }
  }

  private static Verdict pass(String detail) {
    return new Verdict(Verdict.Kind.PASS, detail, "");
  }

  private static Verdict fail(JqTestCase c, String detail) {
    String line = oneLine(detail);
    return new Verdict(Verdict.Kind.FAIL, line, c.id() + ": FAIL, " + line + "\n" + where(c));
  }

  private static String where(JqTestCase c) {
    StringBuilder b = new StringBuilder("  line    ").append(c.line()).append('\n');
    b.append("  program ").append(c.program()).append('\n');
    if (c.input() != null) b.append("  input   ").append(c.input()).append('\n');
    for (String e : c.expected()) b.append("  expect  ").append(e).append('\n');
    return b.toString();
  }

  private static String firstLine(String s) {
    int lf = s.indexOf('\n');
    return lf < 0 ? s : s.substring(0, lf);
  }

  /** The report's detail column holds one line without tabs. */
  private static String oneLine(String s) {
    return s.replace("\n", "\\n").replace("\t", "\\t");
  }
}
