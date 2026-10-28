package io.flottila.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code flottila} command line (D-079): {@code validate}, {@code deploy} and {@code help}; {@code test} comes with P2-03b.
 * Exit codes: 0 done, 1 the package has problems or the node refused or could not be reached, 2 a usage error (nothing done).
 * Everything a command reports goes to stdout; stderr carries the usage and the tool's own failures.
 *
 * @since 0.1
 */
public final class Cli {

    static final String USAGE = """
            usage: flottila <command> [arguments]
              validate <dir> [--env <env>]
                  check the package in <dir> as a node would deploy it (schemas, component configs, a dry build of every
                  flow) and print its tree and its problems; nothing is started or stored (environment: dev unless --env)
              deploy <dir> [--name <name>] [--revision <revision>] [--admin <url>]
                  zip the package in <dir> and PUT it to <url>/admin/packages/<name> (name: package.yaml's unless --name;
                  url: --admin, else $FLOTTILA_ADMIN, else http://localhost:8081; bearer token: $FLOTTILA_ADMIN_TOKEN only)
              help
                  print this text
            exit codes: 0 done, 1 problems / refused / unreachable, 2 usage
            """;

    private Cli() {}

    /** Runs {@link #run} on the process's arguments, environment and streams and exits with its code. */
    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    /**
     * Runs the command {@code args} names, on a virtual thread of its own (the node's deploy code blocks on Vert.x futures), and
     * returns its exit code; never exits the JVM.
     *
     * @param env the environment the command reads ({@code FLOTTILA_ADMIN}, {@code FLOTTILA_ADMIN_TOKEN}); validate reads none
     */
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        return CompletableFuture.supplyAsync(() -> command(args, env, out, err), task -> Thread.ofVirtual().name("flottila").start(task)).join();
    }

    private static int command(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        try {
            String command = args.length == 0 ? "" : args[0];
            return switch (command) {
                case "validate" -> Validate.run(options("validate", args, Set.of("--env")), out);
                case "deploy" -> Deploy.run(options("deploy", args, Set.of("--name", "--revision", "--admin")), env, out, err);
                case "help" -> {
                    out.print(USAGE);
                    yield 0;
                }
                case "test" -> {
                    err.println("flottila test: not yet (P2-03b)");
                    yield 2;
                }
                default -> throw new Usage(command.isEmpty() ? "no command" : "unknown command '" + command + "'");
            };
        } catch (Usage wrong) {
            err.println("flottila: " + wrong.getMessage());
            err.print(USAGE);
            return 2;
        } catch (IOException | UncheckedIOException failed) {
            err.println("flottila: " + oneLine(failed.toString()));
            return 1;
        }
    }

    /** A usage error: printed with the usage, exit 2, nothing done. */
    static final class Usage extends RuntimeException {
        Usage(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * A command's arguments: one package directory, which exists, and each option at most once with a value that is not blank.
     *
     * @param dir the package directory
     * @param values the options given, by name ({@code --env}, ...)
     */
    record Options(Path dir, Map<String, String> values) {
        Optional<String> value(String option) {
            return Optional.ofNullable(values.get(option));
        }
    }

    /** The arguments of {@code command} after its name ({@code args[0]}), each option one of {@code allowed}; a {@link Usage} otherwise. */
    static Options options(String command, String[] args, Set<String> allowed) {
        Path dir = null;
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                if (!allowed.contains(arg)) throw new Usage(command + ": unknown option " + arg);
                if (i + 1 == args.length || args[i + 1].isBlank()) throw new Usage(command + ": " + arg + " needs a value");
                if (values.putIfAbsent(arg, args[++i]) != null) throw new Usage(command + ": " + arg + " is given twice");
            } else if (dir == null) {
                dir = Path.of(arg);
            } else {
                throw new Usage(command + ": one package directory, not also '" + arg + "'");
            }
        }
        if (dir == null) throw new Usage(command + ": the package directory is missing");
        if (!Files.isDirectory(dir)) throw new Usage(command + ": no package directory at " + dir);
        return new Options(dir, Map.copyOf(values));
    }

    /** {@code text} on one line: a parser's multi-line message makes one output line. */
    static String oneLine(String text) {
        return String.valueOf(text).replaceAll("\\s*\\R\\s*", " ");
    }
}
