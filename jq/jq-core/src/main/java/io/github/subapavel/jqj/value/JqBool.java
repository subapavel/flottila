package io.github.subapavel.jqj.value;

/** jq's booleans (ADR-0003). */
public enum JqBool implements JqValue {
  FALSE(false),
  TRUE(true);

  private final boolean value;

  JqBool(boolean value) {
    this.value = value;
  }

  public boolean value() {
    return value;
  }

  @Override
  public String toJson() {
    return Boolean.toString(value);
  }
}
