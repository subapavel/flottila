package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.Bindings;
import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JqFilter;
import io.github.subapavel.jqj.JqFilterException;
import io.github.subapavel.jqj.JqSyntaxException;
import io.github.subapavel.jqj.JqTermination;
import io.github.subapavel.jqj.JsonDoc;
import io.github.subapavel.jqj.JsonWriter;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.OutputOptions;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.cli.Invocation;
import io.github.subapavel.jqj.cli.Main;
import io.github.subapavel.jqj.host.Host;
import io.github.subapavel.jqj.oracle.diff.CaseDirectory;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import io.github.subapavel.jqj.oracle.diff.Outcome;
import io.github.subapavel.jqj.oracle.jqtest.JqTestCase;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The compile-mode oracle's reach into jqj (ADR-0020 amendment for #60, ADR-0018): the one class that reaches the
 * engine-internal switch, by reflection on the package-private {@code JqEngine.Builder.compileMode(CompileMode)}, on
 * the class path. A reflective failure is an {@link IllegalStateException}, never a second optimised run; renaming the
 * switch is a change to this class. It lives with the tests because the oracle module does not read jq-core.
 *
 * <p>A run goes through the command line's in-process entry for a differential case, or through the public API for a
 * jqtest case, and ends as a {@link Run}. An API run's outcome is this oracle's own encoding, compared between the two
 * modes only: each result as one compact line on stdout; a compile error as exit code 3 and its message; a filter
 * error as 5 and its value's JSON; an invalid input as 5 and the reader's message.
 */
final class CompileModes {

  private CompileModes() {}

  /** {@link Limits#embedded()} without the deadline and the regex match time: a slower mode cannot disagree by timing. */
  static Limits limits() {
    Limits e = Limits.embedded();
    return new Limits(
        e.steps(), Limits.UNLIMITED_TIME, e.nestingDepth(), e.recursionDepth(), e.bytesBuilt(), e.bytesWritten(), Limits.UNLIMITED_TIME, e.stackBytes());
  }

  /**
   * The hook that gives a builder this oracle's {@link #limits()} and {@code mode}.
   *
   * @throws IllegalStateException when the switch is not there, or when the hook runs and the switch fails
   */
  static UnaryOperator<JqEngine.Builder> hook(CompileMode mode) {
    Method compileMode = method(JqEngine.Builder.class, "compileMode", CompileMode.class);
    return b -> {
      try {
        return (JqEngine.Builder) compileMode.invoke(b.limits(limits()), mode);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("the compile-mode switch JqEngine.Builder.compileMode(CompileMode) failed for " + mode, e);
      }
    };
  }

  /** The compile mode of {@code engine}, read back through the package-private {@code JqEngine.compileMode()}. */
  static CompileMode modeOf(JqEngine engine) throws ReflectiveOperationException {
    return (CompileMode) method(JqEngine.class, "compileMode").invoke(engine);
  }

  /**
   * The declared method {@code name} of {@code owner}, made accessible.
   *
   * @throws IllegalStateException when there is none or it cannot be made accessible
   */
  static Method method(Class<?> owner, String name, Class<?>... parameters) {
    try {
      Method m = owner.getDeclaredMethod(name, parameters);
      m.setAccessible(true);
      return m;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("the compile-mode oracle cannot reach " + owner.getName() + "." + name, e);
    }
  }

  /** Runs a differential case through {@link Main#run(Invocation, UnaryOperator)}, in a fresh working directory. */
  static Run cli(DiffCase c, Path scratch, UnaryOperator<JqEngine.Builder> hook) {
    try (CaseDirectory dir = CaseDirectory.of(c.files(), scratch)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      Invocation invocation = new Invocation(c.args(), c.environment(dir.path()), dir.path(), new ByteArrayInputStream(c.stdin()), out, err);
      try {
        int rc = Main.run(invocation, hook);
        return new Run.Ended(new Outcome(rc, out.toByteArray(), err.toByteArray()));
      } catch (JqTermination t) {
        return termination(t, () -> new Outcome(t.exitCode(), out.toByteArray(), err.toByteArray()));
      } catch (RuntimeException e) {
        return new Run.Threw(e);
      }
    }
  }

  /** Runs a jqtest case through the public API with {@link Host#sandboxed()}; a FAIL case only compiles. */
  static Run api(JqTestCase c, UnaryOperator<JqEngine.Builder> hook) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      JqEngine engine = hook.apply(JqEngine.builder().host(Host.sandboxed())).build();
      JqFilter filter;
      try {
        filter = engine.compile(c.program());
      } catch (JqCompileException e) {
        return ended(3, out, e.getMessage());
      }
      if (c.mode() != JqTestCase.Mode.RESULTS) return ended(0, out, "");
      Workspace workspace = engine.newWorkspace();
      try (JsonDoc doc = workspace.read(MemorySegment.ofArray(c.input().getBytes(UTF_8)))) {
        // The error value and a halt's value are read while the document is open.
        try {
          filter.run(doc.root(), Bindings.builder().build(), JsonWriter.sink(workspace, out, OutputOptions.compact()), workspace);
          return ended(0, out, "");
        } catch (JqFilterException e) {
          return ended(5, out, e.value().toJson());
        } catch (JqTermination t) {
          return termination(t, () -> new Outcome(t.exitCode(), out.toByteArray(), t.value().toJson().getBytes(UTF_8)));
        }
      }
    } catch (JqSyntaxException e) {
      return ended(5, out, e.getMessage());
    } catch (RuntimeException e) {
      return new Run.Threw(e);
    }
  }

  /**
   * A halt ends the run with {@code halted}, compared like any result; every other termination (budget, stack, cancel,
   * stale view) leaves the case out.
   */
  static Run termination(JqTermination t, Supplier<Outcome> halted) {
    if (t.cause() == JqTermination.Cause.HALT) return new Run.Ended(halted.get());
    return new Run.Terminated(t.cause() + (t.budget() == null ? "" : " " + t.budget()));
  }

  private static Run ended(int rc, ByteArrayOutputStream out, String stderr) {
    return new Run.Ended(new Outcome(rc, out.toByteArray(), stderr.getBytes(UTF_8)));
  }
}
