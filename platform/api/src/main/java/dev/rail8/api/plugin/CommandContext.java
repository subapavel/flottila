package dev.rail8.api.plugin;

import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One run of a {@link Command}.
 *
 * @param args the arguments after the module's name, as they were typed, {@code --plugins} too
 * @param env  the process's environment; the command reads its module's settings with
 *             {@code PluginSettings.of(<module name>, env)} and answers 2 for a setting it refuses
 * @param out  where the command reports
 * @param err  where it writes its usage errors and failures
 * @param clock the system clock on the command line, a fake one in tests
 * @since 0.3
 */
public record CommandContext(List<String> args, Map<String, String> env, PrintStream out, PrintStream err, Clock clock) {
    public CommandContext {
        args = List.copyOf(args);
        env = Map.copyOf(env);
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(clock, "clock");
    }
}
