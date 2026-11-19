package dev.rail8.cli;

import dev.rail8.runtime.plugin.Plugins;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code rail8} command line: {@code validate}, {@code test}, {@code lock}, {@code deploy}, {@code new-adapter} and {@code help}.
 * Exit codes: 0 done, 1 the package has problems, a flow test failed, or the node refused or could not be reached, 2 a usage
 * error or refused plugins (nothing done).
 * Everything a command reports goes to stdout; stderr carries the usage and the tool's own failures.
 *
 * @since 0.1
 */
public final class Cli {

    static final String USAGE = """
            usage: rail8 <command> [arguments]
              validate <dir> [--env <env>] [--deps <dir>] [--plugins <dir>]
                  check the package in <dir> as a node would deploy it (schemas, component configs, a dry build of every
                  flow) and print its tree and its problems (environment: dev unless --env); its dependencies, resolved
                  as lock finds them, are held for the dry build, never deployed; nothing is started or stored; the node
                  has the plugins of --plugins, else of $RAIL8_PLUGINS, else none
              test <dir> [--deps <dir>] [--plugins <dir>]
                  run the package's flow tests (*.test.yaml) as a node of environment ci would, each case on mocks and
                  in-memory stores of its own and on virtual time; print PASS or FAIL per case and what failed (a problem
                  validate would print runs no case); each case's node deploys the package's dependencies, resolved as
                  lock finds them, on the same mocks; the plugins as for validate, their steps run and a mock stands in
                  for their schemes
              lock <dir> [--deps <dir>]
                  write <dir>/package.lock pinning each dependency package.yaml declares with its candidate's content digest:
                  candidates are the folders of --deps, else of the package folder's parent, whose package.yaml loads (the
                  package's own excluded), matched by name and version; print one line per entry
              deploy <dir> [--name <name>] [--revision <revision>] [--admin <url>]
                  zip the package in <dir> and PUT it to <url>/admin/packages/<name> (name: package.yaml's unless --name;
                  url: --admin, else $RAIL8_ADMIN, else http://localhost:8081; bearer token: $RAIL8_ADMIN_TOKEN only)
              new-adapter <dir> --scheme <scheme> [--package <java package>]
                  write the skeleton of a new adapter into <dir>, which must not exist or be empty: its Maven build, the
                  component of <scheme> with its settings description, the plugin, the contract tests of the component and
                  of its mock, and a package that uses it; it builds and passes the contract suite as written, over HTTP,
                  the protocol to replace (scheme: a lower-case letter, then lower-case letters and digits, not a scheme the
                  node has; package: --package, else com.example.<scheme>)
              help
                  print this text
            exit codes: 0 done, 1 problems / a failed case / refused / unreachable, 2 usage / plugins refused
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
     * @param env the environment the command reads: deploy {@code RAIL8_ADMIN} and {@code RAIL8_ADMIN_TOKEN}, validate and test
     *            {@code RAIL8_PLUGINS}
     */
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        return CompletableFuture.supplyAsync(() -> command(args, env, out, err), task -> Thread.ofVirtual().name("rail8").start(task)).join();
    }

    private static int command(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        try {
            String command = args.length == 0 ? "" : args[0];
            return switch (command) {
                case "validate" -> {
                    Options options = options("validate", args, Set.of("--env", "--deps", "--plugins"));
                    yield withPlugins(options, env, err, plugins -> Validate.run(options, plugins, out));
                }
                case "deploy" -> Deploy.run(options("deploy", args, Set.of("--name", "--revision", "--admin")), env, out, err);
                case "help" -> {
                    out.print(USAGE);
                    yield 0;
                }
                case "test" -> {
                    Options options = options("test", args, Set.of("--deps", "--plugins"));
                    yield withPlugins(options, env, err, plugins -> FlowTests.run(options, plugins, out));
                }
                case "lock" -> Lock.run(options("lock", args, Set.of("--deps")), out);
                case "new-adapter" -> {
                    Options options = parse("new-adapter", args, Set.of("--scheme", "--package"));
                    if (options.dir() == null) throw new Usage("new-adapter: the adapter directory is missing");
                    yield NewAdapter.run(options, out);
                }
                default -> throw new Usage(command.isEmpty() ? "no command" : "unknown command '" + command + "'");
            };
        } catch (Usage wrong) {
            err.println("rail8: " + wrong.getMessage());
            err.print(USAGE);
            return 2;
        } catch (IOException | UncheckedIOException failed) {
            err.println("rail8: " + oneLine(failed.toString()));
            return 1;
        }
    }

    /** A command that runs over a node's modules and plugins. */
    @FunctionalInterface
    private interface OverPlugins {
        int run(Plugins plugins) throws IOException;
    }

    /**
     * Runs {@code command} over the modules of the CLI's class path, which are a node's, and the plugins of {@code --plugins},
     * else of {@code RAIL8_PLUGINS} in {@code env}, else none ({@link Plugins#load}), and closes the plugins after it. It reads
     * no setting, so every module and plugin has its settings' fallbacks. Modules or plugins, or a step type or language of
     * theirs, that a node would refuse are one line on {@code err} and 2, nothing run.
     */
    private static int withPlugins(Options options, Map<String, String> env, PrintStream err, OverPlugins command) throws IOException {
        Optional<Path> dir = options.value("--plugins").or(() -> Optional.ofNullable(env.get(Plugins.ENV)).filter(d -> !d.isBlank())).map(Path::of);
        Plugins plugins;
        try {
            plugins = plugins(dir);
        } catch (IllegalArgumentException refused) {
            err.println("rail8: plugins refused: " + oneLine(refused.getMessage()));
            return 2;
        }
        try (plugins) {
            return command.run(plugins);
        }
    }

    /**
     * The modules of the CLI's class path, which are a node's, and the plugins of {@code dir}, none when it is empty; each with the
     * fallbacks of its settings, as the CLI reads none.
     *
     * @throws IllegalArgumentException modules or plugins a node would refuse
     */
    static Plugins plugins(Optional<Path> dir) {
        return Plugins.load(Cli.class.getClassLoader(), dir, Map.of());
    }

    /** A usage error: printed with the usage, exit 2, nothing done. */
    static final class Usage extends RuntimeException {
        Usage(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * A command's arguments: one directory, and each option at most once with a value that is not blank. From {@link #options}
     * the directory is a package directory that exists, and {@code --deps} and {@code --plugins} name a directory.
     *
     * @param dir the directory; null from {@link #parse} when none was given
     * @param values the options given, by name ({@code --env}, ...)
     */
    record Options(Path dir, Map<String, String> values) {
        Optional<String> value(String option) {
            return Optional.ofNullable(values.get(option));
        }
    }

    /** The arguments of {@code command} after its name ({@code args[0]}), each option one of {@code allowed}; a {@link Usage} otherwise. */
    static Options options(String command, String[] args, Set<String> allowed) {
        Options options = parse(command, args, allowed);
        Path dir = options.dir();
        if (dir == null) throw new Usage(command + ": the package directory is missing");
        if (!Files.isDirectory(dir)) throw new Usage(command + ": no package directory at " + dir);
        for (String option : List.of("--deps", "--plugins")) {
            String value = options.values().get(option);
            if (value != null && !Files.isDirectory(Path.of(value))) throw new Usage(command + ": " + option + " names no directory: " + value);
        }
        return options;
    }

    /**
     * The arguments of {@code command} after its name ({@code args[0]}): at most one directory, null when none is given, and each
     * option one of {@code allowed}, at most once, with a value that is not blank; a {@link Usage} otherwise.
     */
    static Options parse(String command, String[] args, Set<String> allowed) {
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
                throw new Usage(command + ": one directory, not also '" + arg + "'");
            }
        }
        return new Options(dir, Map.copyOf(values));
    }

    /** {@code text} on one line: a parser's multi-line message makes one output line. */
    static String oneLine(String text) {
        return String.valueOf(text).replaceAll("\\s*\\R\\s*", " ");
    }
}
