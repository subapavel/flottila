package dev.rail8.api.plugin;

import java.io.IOException;

/**
 * A command of the {@code rail8} command line that a module brings ({@link Plugin#command()}). It has no name of its own: it runs
 * as {@code rail8 <the module's name> [arguments]}, so a module with several verbs reads them from its arguments
 * ({@code rail8 mapping compile <dir>}). Only the command line runs it, never a node.
 *
 * <p>The loader calls {@link #usage()} once, when it loads the module. It refuses, naming the module: a usage that throws or is
 * null, more than {@value #MAX_USAGE_LINES} lines (split at {@code \n}, so a trailing line break makes an empty last line), a
 * line over {@value #MAX_USAGE_LINE} {@code char}s, and any control character other than {@code \n} or format character
 * (Unicode categories Cc and Cf), such as a tab, a carriage return or a right-to-left override.
 *
 * @since 0.3
 */
public interface Command {

    /** The most lines a usage may have. */
    int MAX_USAGE_LINES = 16;

    /** The most {@code char}s a line of a usage may have. */
    int MAX_USAGE_LINE = 120;

    /**
     * How the command is called. Line 1 is what follows the module's name, such as {@code <file> [--twice]}, and may be empty;
     * the other lines say what it does. {@code rail8 help} lists it, and the command line prints it after a usage error.
     */
    String usage();

    /**
     * Runs the command once, on the command line's thread, which it may block, with the thread's context class loader set to
     * its module's. It writes what it reports to {@link CommandContext#out()} and its usage errors and failures to
     * {@link CommandContext#err()}, closes neither, and never exits the JVM.
     *
     * @return the exit code: 0 done, 1 problems, 2 a usage error or a refused setting, after writing why on {@code err}; the
     *         command line then prints the usage
     * @throws IOException          a failure, which the command line prints as one line, exit 1, as for any other exception
     * @throws InterruptedException when the thread is interrupted; exit 1
     */
    int run(CommandContext context) throws IOException, InterruptedException;
}
