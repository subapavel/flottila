package io.flottila.mapping.mapping;

import java.util.Map;

/**
 * A table box parameter, as {@code fixValues} takes. Its keys are source values, so any string is a
 * key; an empty table is allowed.
 *
 * @param rows the table, from key to value
 */
public record Table(Map<String, String> rows) implements ParamValue {

  public Table {
    rows = Map.copyOf(rows);
  }
}
