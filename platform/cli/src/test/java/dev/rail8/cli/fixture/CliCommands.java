package dev.rail8.cli.fixture;

import dev.rail8.api.plugin.Command;
import dev.rail8.api.plugin.CommandContext;
import dev.rail8.api.plugin.Plugin;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Modules that bring a command, for the CLI's tests. A test writes one into a jar with every class of this file
 * ({@link #classes()}), whose service file names it, and gives the CLI a class path of that jar over its own.
 */
public final class CliCommands {

    private CliCommands() {}

    /** This class and every class nested in it: what each fixture jar holds. */
    public static Class<?>[] classes() {
        return Stream.concat(Stream.of(CliCommands.class), Stream.of(CliCommands.class.getDeclaredClasses())).toArray(Class<?>[]::new);
    }

    /** The usage of {@link Sample}'s command. */
    public static final String SAMPLE_USAGE = "<word>... | --fail | --throw | --io | --interrupt | --seven\nprint the words, the time and the greeting";

    /**
     * The module {@code sample}. Its command prints its words, the clock's instant and {@code RAIL8_SAMPLE_GREETING} (else
     * {@code none}), and returns 0. Without a word it is a usage error, 2. {@code --fail} writes a line and returns 1,
     * {@code --throw}, {@code --io} and {@code --interrupt} throw, and {@code --seven} returns 7.
     */
    public static final class Sample implements Plugin {
        @Override public String name() { return "sample"; }
        @Override public String api() { return API; }
        @Override public Optional<Command> command() { return Optional.of(new SampleCommand()); }
    }

    /** {@link Sample}'s command. */
    public static final class SampleCommand implements Command {
        @Override public String usage() { return SAMPLE_USAGE; }

        @Override public int run(CommandContext context) throws IOException, InterruptedException {
            List<String> args = context.args();
            if (args.isEmpty()) {
                context.err().println("sample: no word");
                return 2;
            }
            switch (args.getFirst()) {
                case "--fail" -> {
                    context.err().println("sample: failed on purpose");
                    return 1;
                }
                case "--throw" -> throw new IllegalStateException("thrown on purpose");
                case "--io" -> throw new IOException("no disk");
                case "--interrupt" -> throw new InterruptedException("interrupted on purpose");
                case "--seven" -> {
                    return 7;
                }
                default -> {
                    context.out().println(String.join(" ", args) + " at " + context.clock().instant() + ", greeting "
                            + context.env().getOrDefault("RAIL8_SAMPLE_GREETING", "none"));
                    return 0;
                }
            }
        }
    }

    /** The module {@code bare}: a command without arguments, so line 1 of its usage is empty; it prints {@code hello}. */
    public static final class Bare implements Plugin {
        @Override public String name() { return "bare"; }
        @Override public String api() { return API; }
        @Override public Optional<Command> command() { return Optional.of(new BareCommand()); }
    }

    /** {@link Bare}'s command. */
    public static final class BareCommand implements Command {
        @Override public String usage() { return "\nsay hello"; }

        @Override public int run(CommandContext context) {
            context.out().println("hello");
            return 0;
        }
    }

    /** The module {@code deploy}, named as a command of the CLI's own, with a command. */
    public static final class Deploy implements Plugin {
        @Override public String name() { return "deploy"; }
        @Override public String api() { return API; }
        @Override public Optional<Command> command() { return new Sample().command(); }
    }
}
