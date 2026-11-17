package io.github.subapavel.jqj;

import io.github.subapavel.jqj.value.JqValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The values of a filter's host variables for one run (ADR-0016, ADR-0038), each by its name without the {@code $}.
 * Immutable; a run reads the names its compile declared and ignores the others.
 */
public final class Bindings {
  private final Map<String, JqValue> values;

  private Bindings(Map<String, JqValue> values) {
    this.values = values;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The value bound to {@code name}, or null when none is. */
  JqValue value(String name) {
    return values.get(name);
  }

  /** Builds {@link Bindings}. */
  public static final class Builder {
    private final Map<String, JqValue> values = new HashMap<>();

    private Builder() {}

    /**
     * Binds {@code $name} to {@code value}, in place of a value put before for the name.
     *
     * @throws NullPointerException if {@code name} or {@code value} is null
     */
    public Builder put(String name, JqValue value) {
      values.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    public Bindings build() {
      return new Bindings(Map.copyOf(values));
    }
  }
}
