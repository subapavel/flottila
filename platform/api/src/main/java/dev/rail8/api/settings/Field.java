package dev.rail8.api.settings;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One setting of an endpoint: its name in the endpoint file's {@code options}, its type, a help text, and what the form
 * and the check make of it. A field is built from a factory ({@link #text}, {@link #secret}, {@link #integer},
 * {@link #bool}, {@link #choice}) and refined with withers; each wither returns a new field and checks it whole, so a
 * field that cannot be right throws {@link IllegalArgumentException} naming it while the description is built.
 *
 * <p>By default a field is optional, has no default, sits on {@link Tab#GENERAL}, serves both directions and is always
 * shown. The empty string is no value: a text or secret set to {@code ""} is refused, never taken for absent.
 *
 * @since 0.1
 */
public final class Field {

    /**
     * What a field holds. {@code TEXT} and {@code SECRET} are strings; a secret is shown masked, has no default, and either
     * may be a reference {@code secret://<name>} that the node resolves after the check. {@code INTEGER} is a whole number,
     * {@code BOOLEAN} true or false, {@code CHOICE} one of a fixed list of strings. The JSON form names each in lower case.
     */
    enum Type {
        TEXT("text"), SECRET("secret"), INTEGER("integer"), BOOLEAN("boolean"), CHOICE("choice");

        private final String wire;

        Type(String wire) { this.wire = wire; }
    }

    private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");
    private static final Pattern REFERENCE = Pattern.compile("secret://[a-z0-9][a-z0-9._-]*");
    private static final int MAX_HELP = 1000;
    private static final int MAX_PATTERN = 1000;
    private static final int MAX_CHOICES = 64;
    private static final int MAX_CHOICE = 256;
    private static final int MAX_SHOWN_FOR = 64;

    private final Type type;
    private final String name;
    private final String help;
    private final boolean required;
    private final Object byDefault;
    private final Tab tab;
    private final Set<Direction> directions;
    private final String shownBy;
    private final List<Object> shownFor;
    private final Long min;
    private final Long max;
    private final String pattern;
    private final Pattern compiled;
    private final List<String> choices;

    private Field(Type type, String name, String help, boolean required, Object byDefault, Tab tab, Set<Direction> directions,
                  String shownBy, List<Object> shownFor, Long min, Long max, String pattern, List<String> choices) {
        this.type = type;
        this.name = name;
        this.help = help;
        this.required = required;
        this.tab = tab;
        this.directions = directions;
        this.shownBy = shownBy;
        this.shownFor = shownFor;
        this.min = min;
        this.max = max;
        this.pattern = pattern;
        this.choices = choices;
        this.compiled = validate();
        this.byDefault = byDefault == null ? null : normalDefault(byDefault);
    }

    private static Field of(Type type, String name, String help, List<String> choices) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(help, "help");
        return new Field(type, name, help, false, null, Tab.GENERAL, EnumSet.allOf(Direction.class), null, null, null, null, null, choices);
    }

    /** A string. */
    public static Field text(String name, String help) { return of(Type.TEXT, name, help, null); }

    /** A string shown masked, never with a default; usually a reference {@code secret://<name>}. */
    public static Field secret(String name, String help) { return of(Type.SECRET, name, help, null); }

    /** A whole number. */
    public static Field integer(String name, String help) { return of(Type.INTEGER, name, help, null); }

    /** True or false. */
    public static Field bool(String name, String help) { return of(Type.BOOLEAN, name, help, null); }

    /** One of {@code choices}: 1 to 64 values, each 1 to 256 characters, none twice. */
    public static Field choice(String name, String help, String... choices) {
        return of(Type.CHOICE, name, help, List.copyOf(List.of(choices)));
    }

    private Field with(boolean required, Object byDefault, Tab tab, Set<Direction> directions, String shownBy, List<Object> shownFor,
                       Long min, Long max, String pattern) {
        return new Field(type, name, help, required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern, choices);
    }

    /** A field the endpoint must set; it has no default. While hidden by {@link #visibleWhen} it is not required. */
    public Field required() { return with(true, byDefault, tab, directions, shownBy, shownFor, min, max, pattern); }

    /**
     * The value the endpoint gets when it leaves the field out, which must fit the field. A whole number is kept as a
     * {@code long}.
     */
    public Field byDefault(Object value) {
        Objects.requireNonNull(value, "value");
        return with(required, value, tab, directions, shownBy, shownFor, min, max, pattern);
    }

    /** The tab the form shows the field on. */
    public Field tab(Tab tab) {
        Objects.requireNonNull(tab, "tab");
        return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern);
    }

    /** A setting of in endpoints only; the last of {@code in()} and {@link #out()} wins. */
    public Field in() { return with(required, byDefault, tab, EnumSet.of(Direction.IN), shownBy, shownFor, min, max, pattern); }

    /** A setting of out endpoints only; the last of {@link #in()} and {@code out()} wins. */
    public Field out() { return with(required, byDefault, tab, EnumSet.of(Direction.OUT), shownBy, shownFor, min, max, pattern); }

    /**
     * Shown only while {@code field}, a choice or a boolean declared earlier in the description, has one of {@code values}
     * (1 to 64). Visibility is transitive: a field shown by a hidden field is hidden too. A hidden field is never required
     * and never refused for being set, but a value it is given must still fit it.
     */
    public Field visibleWhen(String field, Object... values) {
        Objects.requireNonNull(field, "field");
        return with(required, byDefault, tab, directions, field, List.copyOf(List.of(values)), min, max, pattern);
    }

    /** The least whole number allowed. */
    public Field min(long min) { return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern); }

    /** The greatest whole number allowed. */
    public Field max(long max) { return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern); }

    /** The least and the greatest whole number allowed. */
    public Field range(long min, long max) { return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern); }

    /**
     * A Java regular expression of at most 1000 characters that a text must match whole. A well-formed secret reference
     * stands in for the value and is not matched.
     */
    public Field pattern(String regex) {
        Objects.requireNonNull(regex, "regex");
        return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, regex);
    }

    /** The key in the endpoint file's {@code options}. */
    public String name() { return name; }

    Type type() { return type; }

    boolean isRequired() { return required; }

    boolean hasDefault() { return byDefault != null; }

    Object byDefault() { return byDefault; }

    Tab tab() { return tab; }

    boolean serves(Direction d) { return directions.contains(d); }

    Set<Direction> directions() { return directions; }

    String shownBy() { return shownBy; }

    List<Object> shownFor() { return shownFor; }

    List<String> choices() { return choices; }

    private IllegalArgumentException refused(String why) {
        return new IllegalArgumentException("field '" + name + "': " + why);
    }

    /** Checks everything a field knows alone and returns its compiled pattern, if any. */
    private Pattern validate() {
        if (!NAME.matcher(name).matches()) throw refused("a name is a lower-case letter, then at most 63 letters or digits");
        if (help.isBlank() || help.length() > MAX_HELP) throw refused("a help text of 1 to 1000 characters");
        if (type == Type.CHOICE && !validChoices()) throw refused("a choice of 1 to 64 values, each 1 to 256 characters, none twice");
        if ((min != null || max != null) && type != Type.INTEGER) throw refused("min and max are for whole numbers");
        if (min != null && max != null && min > max) throw refused("min " + min + " is above max " + max);
        Pattern p = null;
        if (pattern != null) {
            if (type != Type.TEXT) throw refused("a pattern is for text");
            if (pattern.length() > MAX_PATTERN) throw refused("a pattern of at most 1000 characters");
            try {
                p = Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw refused("the pattern does not compile: " + pattern);
            }
        }
        if (shownFor != null && (shownFor.isEmpty() || shownFor.size() > MAX_SHOWN_FOR)) {
            throw refused("visible when '" + shownBy + "' is one of 1 to 64 values");
        }
        return p;
    }

    private boolean validChoices() {
        if (choices.isEmpty() || choices.size() > MAX_CHOICES || new HashSet<>(choices).size() != choices.size()) return false;
        return choices.stream().allMatch(c -> !c.isEmpty() && c.length() <= MAX_CHOICE);
    }

    /** The default as the field keeps it, once it is known to fit. */
    private Object normalDefault(Object value) {
        if (type == Type.SECRET) throw refused("a secret has no default");
        if (required) throw refused("a required field has no default");
        if (type == Type.TEXT && !(value instanceof String s && !s.isEmpty())) throw refused("the default must be text that is not empty");
        String problem = problem(value, false);
        if (problem != null) throw refused("the default " + problem);
        return type == Type.INTEGER ? wholeNumber(value) : value;
    }

    /**
     * What is wrong with {@code value} for this field, in the form's words, or null when it fits. A well-formed secret
     * reference fits a text or a secret when {@code references} is true.
     */
    String problem(Object value, boolean references) {
        return switch (type) {
            case TEXT, SECRET -> {
                if (!(value instanceof String s)) yield "must be text";
                if (s.isEmpty()) yield "must not be empty";
                if (compiled == null || (references && REFERENCE.matcher(s).matches())) yield null;
                yield compiled.matcher(s).matches() ? null : "must match " + pattern;
            }
            case INTEGER -> {
                Long n = wholeNumber(value);
                yield n != null && (min == null || n >= min) && (max == null || n <= max) ? null : "must be a whole number" + bounds();
            }
            case BOOLEAN -> value instanceof Boolean ? null : "must be true or false";
            case CHOICE -> value instanceof String s && choices.contains(s) ? null : "must be one of " + String.join(", ", choices);
        };
    }

    private String bounds() {
        if (min != null && max != null) return " from " + min + " to " + max;
        if (min != null) return " of at least " + min;
        if (max != null) return " of at most " + max;
        return "";
    }

    /** {@code value} as a long when it is a whole number a long holds, else null. */
    static Long wholeNumber(Object value) {
        return switch (value) {
            case Long l -> l;
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case BigInteger b -> b.bitLength() < Long.SIZE ? b.longValue() : null;
            case BigDecimal b -> {
                try {
                    yield b.longValueExact();
                } catch (ArithmeticException e) {
                    yield null;
                }
            }
            case Double d -> wholeDouble(d);
            case Float f -> wholeDouble(f.doubleValue());
            case null, default -> null;
        };
    }

    private static Long wholeDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) return null;
        if (d < -0x1p63 || d >= 0x1p63) return null;
        return (long) d;
    }

    /** The field as the JSON form carries it: the keys every field has, then the ones it sets. */
    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type.wire);
        m.put("help", help);
        m.put("tab", tab.id());
        m.put("required", required);
        List<String> dirs = new ArrayList<>();
        for (Direction d : directions) dirs.add(d.name().toLowerCase(Locale.ROOT));
        m.put("directions", List.copyOf(dirs));
        if (pattern != null) m.put("pattern", pattern);
        if (byDefault != null) m.put("default", byDefault);
        if (min != null) m.put("min", min);
        if (max != null) m.put("max", max);
        if (choices != null) m.put("choices", choices);
        if (shownBy != null) {
            Map<String, Object> when = new LinkedHashMap<>();
            when.put("field", shownBy);
            when.put("values", shownFor);
            m.put("visibleWhen", Collections.unmodifiableMap(when));
        }
        return Collections.unmodifiableMap(m);
    }
}
