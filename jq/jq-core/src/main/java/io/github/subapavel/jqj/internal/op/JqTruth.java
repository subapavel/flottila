package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.query.run.Truth;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;

/** jq's truth (GLOSSARY "Truthy", ADR-0037): a value is true when it is neither {@code false} nor {@code null}. */
public final class JqTruth implements Truth {
  /** The one instance: the If and Alt nodes of jq take it as their truth. */
  public static final JqTruth TRUTHY = new JqTruth();

  private JqTruth() {}

  @Override
  public boolean test(Object value) {
    return value != JqBool.FALSE && value != JqNull.NULL;
  }
}
