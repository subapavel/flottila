package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.run.Truth;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;

/** jq's truth: a value is true when it is neither {@code false} nor {@code null}. */
public final class JqTruth implements Truth {
  /** The one instance: the If and Alt nodes of jq take it as their truth. */
  public static final JqTruth TRUTHY = new JqTruth();

  private JqTruth() {}

  @Override
  public boolean test(Object value) {
    return value != JqBool.FALSE && value != JqNull.NULL;
  }
}
