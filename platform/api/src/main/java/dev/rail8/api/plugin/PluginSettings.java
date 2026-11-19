package dev.rail8.api.plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The settings of one plugin: the variables of the node's environment named {@code RAIL8_<NAME>_<KEY>}, where {@code <NAME>} is
 * the plugin's name in upper case with each dash an underscore (the plugin {@code jq} reads {@code RAIL8_JQ_MAX_RECURSION_DEPTH}
 * as the key {@code MAX_RECURSION_DEPTH}). A node reads them from its environment once, as it starts; {@code rail8 validate} and
 * {@code rail8 test} read none, so there every setting is its fallback.
 *
 * <p>A plugin runs in the node's JVM and could read the environment itself; settings give each plugin its own names and one
 * refusal for a value that is wrong, before the node starts.
 *
 * @since 0.2
 */
public final class PluginSettings {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final String prefix;
    private final Map<String, String> values;

    private PluginSettings(String prefix, Map<String, String> values) {
        this.prefix = prefix;
        this.values = values;
    }

    /**
     * The settings of the plugin {@code plugin} in {@code env}: the variables whose names start with its prefix, the others left out.
     *
     * @param plugin a plugin's name ({@link Plugin#name()}): a lower-case letter, then at most 63 lower-case letters, digits or dashes
     * @param env    the variables to read, such as the node's environment; empty for none
     * @throws IllegalArgumentException {@code plugin} is no plugin's name
     */
    public static PluginSettings of(String plugin, Map<String, String> env) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(env, "env");
        if (!NAME.matcher(plugin).matches()) throw new IllegalArgumentException("'" + plugin + "' is no plugin's name");
        String prefix = "RAIL8_" + plugin.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
        return new PluginSettings(prefix, env.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * The whole number the variable {@code RAIL8_<NAME>_<key>} holds, written in decimal digits with an optional sign;
     * {@code fallback} when it is absent.
     *
     * @param key      the key: an upper-case letter, then at most 63 upper-case letters, digits or underscores
     * @param fallback the value of an absent setting, from {@code min} to {@code max}
     * @param min      the smallest value the setting may hold
     * @param max      the largest value the setting may hold
     * @throws IllegalArgumentException the variable holds no whole number, or one below {@code min} or above {@code max}; the
     *                                  message names the variable and its value. Also a key that is none, or a fallback out of
     *                                  its range.
     */
    public long whole(String key, long fallback, long min, long max) {
        Objects.requireNonNull(key, "key");
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("'" + key + "' is no setting's key");
        if (fallback < min || fallback > max) {
            throw new IllegalArgumentException("the fallback " + fallback + " of " + prefix + key + " is not between " + min + " and " + max);
        }
        String variable = prefix + key;
        String raw = values.get(variable);
        if (raw == null) return fallback;
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(variable + " must be a whole number, was '" + raw + "'");
        }
        if (value < min || value > max) throw new IllegalArgumentException(variable + " must be between " + min + " and " + max + ", was " + value);
        return value;
    }
}
