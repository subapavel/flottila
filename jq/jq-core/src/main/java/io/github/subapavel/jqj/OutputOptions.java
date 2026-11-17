package io.github.subapavel.jqj;

/**
 * How a {@link JsonWriter} prints (ADR-0015, ADR-0024). Built by factories; a later option arrives as a
 * factory or a wither, never as a constructor parameter.
 */
public final class OutputOptions {
  private static final OutputOptions COMPACT = new OutputOptions();
  private static final OutputOptions PRETTY = new OutputOptions();

  private OutputOptions() {}

  /** One line per result, as jq's {@code -c}. */
  public static OutputOptions compact() {
    return COMPACT;
  }

  /** Indented by 2, jq's default. */
  public static OutputOptions pretty() {
    return PRETTY;
  }
}
