package io.github.subapavel.jqj.value;

/**
 * A jq value (ADR-0016): one of six kinds, so a host's exhaustive {@code switch} needs no default. The
 * null and boolean kinds are enums; the other four are sealed interfaces whose views are not exported
 * (ADR-0024).
 */
public sealed interface JqValue permits JqNull, JqBool, JqNumber, JqString, JqArray, JqObject {
  /** The value as compact JSON text. */
  String toJson();
}
