package dev.rail8.api.settings;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A tab of an endpoint's settings form. The platform supplies the common tabs {@link #GENERAL}, {@link #RETRY},
 * {@link #RECORDING} and {@link #SECURITY}; an adapter adds its own with {@link #of} and puts a field on a tab with
 * {@link Field#tab}. Two tabs are equal when their id and label are.
 *
 * @since 0.1
 */
public final class Tab {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final int MAX_LABEL = 64;

    /** Where a field goes when it names no tab. */
    public static final Tab GENERAL = new Tab("general", "General", true);
    public static final Tab RETRY = new Tab("retry", "Retry", true);
    public static final Tab RECORDING = new Tab("recording", "Recording", true);
    public static final Tab SECURITY = new Tab("security", "Security", true);

    /** The common tabs in the order the form shows them; an adapter's own tabs come between the first and the second. */
    static final List<Tab> COMMON = List.of(GENERAL, RETRY, RECORDING, SECURITY);

    private final String id;
    private final String label;
    private final boolean common;

    private Tab(String id, String label, boolean common) {
        this.id = id;
        this.label = label;
        this.common = common;
    }

    /**
     * An adapter's own tab.
     *
     * @param id a lower-case letter, then at most 31 lower-case letters, digits or dashes; not the id of a common tab
     * @param label what the form shows, 1 to 64 characters
     * @throws IllegalArgumentException naming the tab when either is out of these bounds
     */
    public static Tab of(String id, String label) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (COMMON.stream().anyMatch(t -> t.id.equals(id))) {
            throw new IllegalArgumentException("tab '" + id + "' is one of the platform's: general, retry, recording, security");
        }
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("tab '" + id + "': an id is a lower-case letter, then at most 31 lower-case letters, digits or dashes");
        }
        if (label.isBlank() || label.length() > MAX_LABEL) throw new IllegalArgumentException("tab '" + id + "': a label of 1 to 64 characters");
        return new Tab(id, label, false);
    }

    public String id() { return id; }

    public String label() { return label; }

    /** Whether the platform supplies it. */
    public boolean common() { return common; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Tab t && t.id.equals(id) && t.label.equals(label);
    }

    @Override
    public int hashCode() { return Objects.hash(id, label); }

    @Override
    public String toString() { return "Tab[" + id + ", " + label + "]"; }
}
