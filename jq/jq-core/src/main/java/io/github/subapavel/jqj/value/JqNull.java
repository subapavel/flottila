package io.github.subapavel.jqj.value;

/** jq's null (ADR-0003). */
public enum JqNull implements JqValue {
  NULL;

  @Override
  public String toJson() {
    return "null";
  }
}
