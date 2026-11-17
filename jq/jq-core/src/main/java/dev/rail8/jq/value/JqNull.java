package dev.rail8.jq.value;

/** jq's null. */
public enum JqNull implements JqValue {
  NULL;

  @Override
  public String toJson() {
    return "null";
  }
}
