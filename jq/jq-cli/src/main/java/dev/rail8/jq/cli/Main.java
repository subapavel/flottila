package dev.rail8.jq.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.Bindings;
import dev.rail8.jq.JqCompileException;
import dev.rail8.jq.JqEngine;
import dev.rail8.jq.JqFilter;
import dev.rail8.jq.JqFilterException;
import dev.rail8.jq.JqSink;
import dev.rail8.jq.JqSyntaxException;
import dev.rail8.jq.JqTermination;
import dev.rail8.jq.JsonDoc;
import dev.rail8.jq.JsonWriter;
import dev.rail8.jq.Limits;
import dev.rail8.jq.OutputOptions;
import dev.rail8.jq.Workspace;
import dev.rail8.jq.host.Host;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The command line. Not exported API: the oracle reaches {@link #run} on the class path, and the launcher
 * {@code target/jqj} starts {@link #main} on the module path of {@code target/lib}.
 *
 * <p>What it handles: the filter ({@code .} when there is none) and at most one file, read instead
 * of stdin; {@code -c} and {@code -M}, alone, combined or as {@code --compact-output} and {@code --monochrome-output},
 * before or after either; {@code --}, after which every argument is the filter or a file. A missing file and an
 * option jq does not know end with jq's text and exit code 2; any other jq option is a Stub with exit code 2. A filter
 * error ends the run with jq's line {@code jq: error (at <name>:N): <text>}, or {@code jq: error (at <name>:N) (not a
 * string): <value>} for a value that is no string, and exit code 5; a termination with {@code jq: error:
 * <message>} and exit code 5. Without {@code -c} the output is pretty, jq's default indent 2. {@code --explain}, a
 * flag of this engine's that jq does not have, prints the compiled filter's {@code explain()} text instead of running
 * it, reads no input and exits 0.
 */
public final class Main {

  /** The mark every Stub message starts with: a Stub is a part of the language not built yet, which ends the run. */
  private static final String STUB = "jqj stub: ";

  /** The lines jq 1.8.2 prints after "jq: Unknown option X". */
  private static final String HELP =
      "Use jq --help for help with command-line options,\nor see the jq manpage, or online docs at https://jqlang.org";

  /** jq-ref 1.8.2's short options other than c and M (observed 2026-11-11: no other ASCII letter is one). */
  private static final String SHORT_NOT_BUILT = "abefhjnrsCLRSV";

  /**
   * jq-ref 1.8.2's long options other than compact-output and monochrome-output, without their {@code --} (observed
   * 2026-11-11: each word of the binary's strings tried as {@code --word}, and the three-letter ones by hand).
   */
  private static final Set<String> LONG_NOT_BUILT =
      Set.of(
          "arg", "args", "argjson", "ascii-output", "binary", "build-configuration", "color-output",
          "debug-dump-disasm", "debug-trace", "debug-trace=all", "exit-status", "from-file", "help", "indent",
          "join-output", "jsonargs", "library-path", "null-input", "raw-input", "raw-output", "raw-output0",
          "rawfile", "run-tests", "seq", "slurp", "slurpfile", "sort-keys", "stream", "stream-errors", "tab",
          "unbuffered", "version");

  /** Exit code of a usage error: an option, a second file, a file that cannot be read. */
  private static final int USAGE = 2;

  /** Exit code of a filter that does not compile. */
  private static final int COMPILE = 3;

  /** Exit code of a failed evaluation: a parse error of the input, a filter error, a Stub during run or write. */
  private static final int FAILED = 5;

  private Main() {}

  /**
   * The process entry the launcher starts: runs the command line on the process's streams, environment
   * and working directory, then exits with its code.
   *
   * @throws IOException when stdout cannot be flushed at the end
   */
  public static void main(String[] args) throws IOException {
    OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out));
    int rc = run(new Invocation(List.of(args), System.getenv(), Path.of("").toAbsolutePath(), System.in, out, System.err));
    out.flush();
    System.exit(rc);
  }

  /**
   * The in-process entry: runs one invocation and returns its exit code. Reads the arguments in order,
   * then compiles the filter, makes the sink, reads all of the file or stdin, and runs the filter once on the one
   * document there. An input stream that holds no document runs nothing: exit code 0 and no output.
   *
   * @throws UncheckedIOException when stdin, stdout or stderr fails
   */
  public static int run(Invocation invocation) {
    return run(invocation, UnaryOperator.identity(), true);
  }

  /** As {@link #run(Invocation)}, with {@code limits} instead of {@link Limits#cli()}: the seam the tests end a run by. */
  static int run(Invocation invocation, Limits limits) {
    return run(invocation, b -> b.limits(limits), true);
  }

  /**
   * As {@link #run(Invocation)}, with the engine built from what {@code engine} makes of the command line's builder:
   * the compile-mode oracle sets its limits and compile mode there. Unlike {@link #run(Invocation)} it ends no run on
   * a termination: the oracle reads its cause.
   *
   * @throws JqTermination when the run ends with one
   * @throws UncheckedIOException when stdin, stdout or stderr fails
   */
  public static int run(Invocation invocation, UnaryOperator<JqEngine.Builder> engine) {
    return run(invocation, engine, false);
  }

  /**
   * The run of both entries. With {@code endsTerminations} a termination ends the run as jq's resource end does: the
   * results so far are flushed, {@code jq: error: <message>} goes to stderr,
   * and the exit code is the termination's, 5; without it the termination is thrown.
   */
  private static int run(Invocation invocation, UnaryOperator<JqEngine.Builder> engine, boolean endsTerminations) {
    boolean compact = false;
    boolean explain = false;
    boolean optionsDone = false;
    String filter = null;
    String file = null;
    for (String arg : invocation.args()) {
      if (optionsDone || !isOption(arg)) {
        if (filter == null) {
          filter = arg;
        } else if (file == null) {
          file = arg;
        } else {
          return report(invocation, USAGE, STUB + "a second input file is not built yet");
        }
      } else if (arg.equals("--")) {
        optionsDone = true;
      } else if (arg.startsWith("--")) {
        String name = arg.substring(2);
        if (name.equals("compact-output")) {
          compact = true;
        } else if (name.equals("explain")) {
          explain = true;
        } else if (!name.equals("monochrome-output")) {
          return refuse(invocation, arg, LONG_NOT_BUILT.contains(name));
        }
      } else {
        // A cluster of short options: the characters in order, the first that is neither c nor M ends the run.
        for (int i = 1; i < arg.length(); i++) {
          char c = arg.charAt(i);
          if (c == 'c') {
            compact = true;
          } else if (c != 'M') {
            return refuse(invocation, "-" + c, SHORT_NOT_BUILT.indexOf(c) >= 0 || c > 0x7F);
          }
        }
      }
    }

    JqEngine built = engine.apply(JqEngine.builder().limits(Limits.cli()).host(Host.sandboxed())).build();
    JqFilter compiled;
    try {
      compiled = built.compile(filter == null ? "." : filter);
    } catch (JqCompileException e) {
      return report(invocation, COMPILE, e.getMessage());
    }
    if (explain) {
      // A flag of this engine's that jq does not have; the input is never read.
      try {
        invocation.out().write(compiled.explain().getBytes(UTF_8));
        invocation.out().flush();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return 0;
    }
    Workspace workspace = built.newWorkspace();
    try {
      JqSink sink = JsonWriter.sink(workspace, invocation.out(), compact ? OutputOptions.compact() : OutputOptions.pretty());
      byte[] input;
      if (file == null) {
        input = invocation.in().readAllBytes();
      } else {
        try {
          input = Files.readAllBytes(invocation.cwd().resolve(file));
        } catch (NoSuchFileException e) {
          return report(invocation, USAGE, "jq: error: Could not open file " + file + ": No such file or directory");
        } catch (IOException e) {
          return report(invocation, USAGE, STUB + "the error for an input file that cannot be read (" + e + ") is not built yet");
        }
      }
      if (!Input.holdsNoDocument(input)) {
        try (JsonDoc doc = workspace.read(MemorySegment.ofArray(input))) {
          compiled.run(doc.root(), Bindings.builder().build(), sink, workspace);
        } catch (JqFilterException e) {
          // The results before the error leave first, as jq's do.
          invocation.out().flush();
          String name = file == null ? "<stdin>" : file;
          return report(invocation, FAILED, "jq: error (at " + name + ":" + ErrorLine.of(input) + ")" + message(e.value()));
        } catch (JqTermination t) {
          // No run ends with HALT: halt is not built.
          if (!endsTerminations) {
            throw t;
          }
          invocation.out().flush();
          return report(invocation, t.exitCode(), "jq: error: " + t.getMessage());
        }
      }
      invocation.out().flush();
      return 0;
    } catch (JqSyntaxException e) {
      return report(invocation, FAILED, "jq: parse error: " + e.getMessage());
    } catch (JqCompileException e) {
      return report(invocation, FAILED, e.getMessage());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * What jq 1.8.2 prints of an error value after its position: a string's text, cut at its first NUL as jq's
   * {@code %s} prints it, after {@code ": "}; any other value's compact text after {@code " (not a string): "}.
   */
  private static String message(JqValue value) {
    if (value instanceof JqString s) {
      String text = s.value();
      int nul = text.indexOf('\0');
      return ": " + (nul < 0 ? text : text.substring(0, nul));
    }
    return " (not a string): " + value.toJson();
  }

  /**
   * Whether jq reads {@code arg} as an option: a {@code -} followed by a second {@code -} or an ASCII letter (jq-ref
   * 1.8.2, observed 2026-11-11: {@code -}, {@code -1}, {@code -_} and {@code -é} are the filter or a file).
   */
  private static boolean isOption(String arg) {
    if (arg.length() < 2 || arg.charAt(0) != '-') {
      return false;
    }
    char c = arg.charAt(1);
    return c == '-' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /**
   * Ends the run at an option that is not built: a Stub when {@code stub}, otherwise jq's usage text for an option it
   * does not know, as jq 1.8.2 prints it. Both exit with code 2. {@code stub} holds for an
   * option jq knows, and for a character outside ASCII in a cluster, which jq names by its first byte alone, no text.
   */
  private static int refuse(Invocation invocation, String option, boolean stub) {
    if (stub) {
      return report(invocation, USAGE, STUB + "the option " + option + " is not built yet");
    }
    return report(invocation, USAGE, "jq: Unknown option " + option + "\n" + HELP);
  }

  /** Writes {@code message} and an LF to stderr; returns {@code rc}. */
  private static int report(Invocation invocation, int rc, String message) {
    try {
      invocation.err().write((message + "\n").getBytes(UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rc;
  }
}
