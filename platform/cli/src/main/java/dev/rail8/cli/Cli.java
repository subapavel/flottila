package dev.rail8.cli;

import dev.rail8.api.plugin.CommandContext;
import dev.rail8.runtime.plugin.Plugins;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code rail8} command line: {@code validate}, {@code test}, {@code lock}, {@code deploy}, {@code new-adapter} and {@code help},
 * and {@code rail8 <name>}, the command a module of its class path brings ({@link dev.rail8.api.plugin.Command}).
 * Exit codes: 0 done, 1 the package has problems, a flow test failed, or the node refused or could not be reached, 2 a usage
 * error or refused plugins (nothing done).
 * Everything a command reports goes to stdout; stderr carries the usage and the tool's own failures.
 *
 * @since 0.1
 */
public final class Cli {

    /** The CLI's own commands, before the modules' commands in {@code rail8 help}. */
    static final String COMMANDS = """
            usage: rail8 <command> [arguments]
              validate <dir> [--env <env>] [--deps <dir>] [--plugins <dir>]
                  check the package in <dir> as a node would deploy it (schemas, component configs, a dry build of every
                  flow) and print its tree and its problems (environment: dev unless --env); its dependencies, read
                  from the repositories and commits package.lock pins, are held for the dry build, never deployed; nothing
                  is started or stored; the node has the plugins of --plugins, else of $RAIL8_PLUGINS, else none
              test <dir> [--deps <dir>] [--plugins <dir>]
                  run the package's flow tests (*.test.yaml) as a node of environment ci would, each case on mocks and
                  in-memory stores of its own and on virtual time; print PASS or FAIL per case and what failed (a problem
                  validate would print runs no case); each case's node deploys the package's dependencies, read as
                  validate reads them, on the same mocks; the plugins as for validate, their steps run and a mock stands
                  in for their schemes
              lock <dir> [--deps <dir>]
                  write <dir>/package.lock pinning each dependency package.yaml declares to its candidate's repository
                  (the folder's name), its HEAD commit and that commit's content digest: candidates are the folders of
                  --deps, else of the package folder's parent, whose package.yaml loads (the package's own excluded),
                  matched by name and version; each must be the root of a git working tree whose files are its HEAD's;
                  validate and test read each pinned commit from the same folders; print one line per entry
              deploy <repository> <commit|ref> [--check] [--admin <url>]
                  ask the node at <url>/admin/deploy to deploy that commit of the package repository <repository>, which
                  it reads under its RAIL8_GIT_BASE (a ref such as HEAD or main in dev and ci only); with --check it
                  answers what the deploy would do and changes nothing (url: --admin, else $RAIL8_ADMIN, else
                  http://localhost:8081; bearer token: $RAIL8_ADMIN_TOKEN only)
              new-adapter <dir> --scheme <scheme> [--package <java package>]
                  write the skeleton of a new adapter into <dir>, which must not exist or be empty, as a git repository
                  with one commit: its Maven build, the component of <scheme> with its settings description, the plugin,
                  the contract tests of the component and of its mock, and a package that uses it; it builds and passes
                  the contract suite as written, over HTTP, the protocol to replace (scheme: a lower-case letter, then
                  lower-case letters and digits, not a scheme the node has; package: --package, else com.example.<scheme>)
              help
                  print this text, with the command each module brings
              <module> [arguments]
                  run the command that the module of that name brings; help lists them
            """;

    /** The last line of the usage. */
    static final String EXIT_CODES = "exit codes: 0 done, 1 problems / a failed case / refused / unreachable, 2 usage / plugins refused\n";

    /** The CLI's own usage, printed after a usage error. */
    static final String USAGE = COMMANDS + EXIT_CODES;

    /** How far {@code rail8 help} indents a line after a command's first. */
    private static final String INDENT = "      ";

    private Cli() {}

    /** Runs {@link #run} on the process's arguments, environment and streams and exits with its code. */
    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    /** Runs {@link #run(String[], Map, PrintStream, PrintStream, ClassLoader, Clock)} over the CLI's own modules and the system clock. */
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        return run(args, env, out, err, Cli.class.getClassLoader(), Clock.systemUTC());
    }

    /**
     * Runs the command {@code args} names, on a virtual thread of its own (the node's deploy code blocks on Vert.x futures), and
     * returns its exit code; never exits the JVM.
     *
     * @param env     the environment the command reads: deploy {@code RAIL8_ADMIN} and {@code RAIL8_ADMIN_TOKEN}, validate and test
     *                {@code RAIL8_PLUGINS}; a module's command is given all of it
     * @param modules the class loader whose service files name the modules: the CLI's own, which are a node's
     * @param clock   the clock a module's command is given
     */
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err, ClassLoader modules, Clock clock) {
        return CompletableFuture.supplyAsync(() -> command(args, env, out, err, modules, clock), task -> Thread.ofVirtual().name("rail8").start(task))
                .join();
    }

    private static int command(String[] args, Map<String, String> env, PrintStream out, PrintStream err, ClassLoader modules, Clock clock) {
        try {
            String command = args.length == 0 ? "" : args[0];
            return switch (command) {
                case "validate" -> {
                    Options options = options("validate", args, Set.of("--env", "--deps", "--plugins"));
                    yield withPlugins(modules, options, env, plugins -> Validate.run(options, plugins, out));
                }
                case "deploy" -> Deploy.run(Deploy.arguments(args), env, out, err);
                case "help" -> help(modules, out, err);
                case "test" -> {
                    Options options = options("test", args, Set.of("--deps", "--plugins"));
                    yield withPlugins(modules, options, env, plugins -> FlowTests.run(options, plugins, out));
                }
                case "lock" -> Lock.run(options("lock", args, Set.of("--deps")), out);
                case "new-adapter" -> {
                    Options options = parse("new-adapter", args, Set.of("--scheme", "--package"));
                    if (options.dir() == null) throw new Usage("new-adapter: the adapter directory is missing");
                    yield NewAdapter.run(options, modules, out);
                }
                case "" -> throw new Usage("no command");
                default -> moduleCommand(command, args, env, out, err, modules, clock);
            };
        } catch (Usage wrong) {
            err.println("rail8: " + wrong.getMessage());
            err.print(USAGE);
            return 2;
        } catch (Refused refused) {
            err.println("rail8: plugins refused: " + oneLine(refused.getMessage()));
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
     * Runs {@code command} over the modules {@code modules} names and the plugins of {@code --plugins}, else of
     * {@code RAIL8_PLUGINS} in {@code env}, else none ({@link Plugins#load}), and closes the plugins after it. It reads no
     * setting, so every module and plugin has its settings' fallbacks.
     *
     * @throws Refused modules or plugins, or what they bring, that a node would refuse; nothing run
     */
    private static int withPlugins(ClassLoader modules, Options options, Map<String, String> env, OverPlugins command) throws IOException {
        Optional<Path> dir = options.value("--plugins").map(Path::of).or(() -> Plugins.dir(env));
        try (Plugins plugins = plugins(modules, dir)) {
            return command.run(plugins);
        }
    }

    /**
     * {@code rail8 help}: the CLI's own commands, then the command each module of {@code modules} brings, in name order, then
     * the exit codes; 0. When a node would refuse the modules: the CLI's own usage on {@code out}, the refusal on {@code err}, 2.
     * It loads no plugin of {@code RAIL8_PLUGINS}.
     */
    private static int help(ClassLoader modules, PrintStream out, PrintStream err) {
        SequencedMap<String, String> commands;
        try (Plugins loaded = plugins(modules, Optional.empty())) {
            commands = loaded.commands();
        } catch (Refused refused) {
            out.print(USAGE);
            err.println("rail8: plugins refused: " + oneLine(refused.getMessage()));
            return 2;
        }
        out.print(COMMANDS);
        commands.forEach((name, usage) -> out.print(usage("  " + name, usage)));
        out.print(EXIT_CODES);
        return 0;
    }

    /**
     * {@code rail8 <name> [arguments]}: the command the module {@code name} of {@code modules} brings, run once on this thread
     * with the arguments after the name, all of {@code env} and {@code clock}. It loads no plugin of {@code RAIL8_PLUGINS} and
     * reads no setting for the load. What the command returns, 0, 1 or 2, is the exit code, and after a 2 its usage follows on
     * {@code err}. Any other return, and what it throws, is one line on {@code err} and 1.
     *
     * @throws Usage   no module of that name, or one that brings no command
     * @throws Refused modules a node would refuse
     */
    private static int moduleCommand(String name, String[] args, Map<String, String> env, PrintStream out, PrintStream err, ClassLoader modules,
                                     Clock clock) {
        try (Plugins loaded = plugins(modules, Optional.empty())) {
            Optional<Plugins.Listed> module = loaded.list().stream().filter(l -> l.name().equals(name)).findFirst();
            if (module.isEmpty()) throw new Usage("unknown command '" + name + "'");
            if (!module.get().command()) throw new Usage("module " + name + " brings no command");
            CommandContext context = new CommandContext(List.of(args).subList(1, args.length), env, out, err, clock);
            int code;
            try {
                code = loaded.run(name, context);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                err.println("rail8: " + name + ": " + oneLine(interrupted.toString()));
                return 1;
            } catch (IOException | RuntimeException | ServiceConfigurationError | LinkageError failed) {
                err.println("rail8: " + name + ": " + oneLine(failed.toString()));
                return 1;
            }
            if (code == 2) err.print(usage("usage: rail8 " + name, loaded.commands().get(name)));
            if (code < 0 || code > 2) {
                err.println("rail8: " + name + ": returned " + code);
                return 1;
            }
            return code;
        }
    }

    /**
     * {@code head}, then line 1 of {@code usage} after a space unless it is empty, then each other line of it indented by six
     * spaces; every line ends with a line break and has no trailing space.
     */
    static String usage(String head, String usage) {
        List<String> lines = List.of(usage.split("\n", -1));
        StringBuilder text = new StringBuilder(head);
        if (!lines.getFirst().isEmpty()) text.append(' ').append(lines.getFirst());
        text.append('\n');
        lines.subList(1, lines.size()).forEach(line -> text.append((INDENT + line).stripTrailing()).append('\n'));
        return text.toString();
    }

    /**
     * The modules {@code modules} names, which are a node's in the CLI, and the plugins of {@code dir}, none when it is empty;
     * each with the fallbacks of its settings, as the CLI reads none.
     *
     * @throws Refused modules or plugins a node would refuse
     */
    static Plugins plugins(ClassLoader modules, Optional<Path> dir) {
        try {
            return Plugins.load(modules, dir, Map.of());
        } catch (IllegalArgumentException refused) {
            throw new Refused(refused.getMessage());
        }
    }

    /** Modules or plugins a node would refuse: one line on stderr, exit 2, nothing done. */
    static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message, null, false, false);
        }
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
