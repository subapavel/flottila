package io.flottila.mapping.structure.importer.internal;

import io.flottila.mapping.structure.ValueType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The value type of each XSD 1.0 built-in simple type, by built-in ancestry (ADR-0008): the nearest
 * of ADR-0004's seven atomic types it derives from, otherwise {@code xs:string}. A list type is one
 * value, so it is a string.
 */
final class BuiltInTypes {

  private static final Map<String, ValueType> BY_LOCAL_NAME = table();

  private BuiltInTypes() {}

  /** The value type of the built-in type {@code xs:<localName>}, or empty if there is none. */
  static Optional<ValueType> valueType(String localName) {
    return Optional.ofNullable(BY_LOCAL_NAME.get(localName));
  }

  private static Map<String, ValueType> table() {
    Map<String, ValueType> table = new HashMap<>();
    put(
        table,
        ValueType.STRING,
        List.of(
            "anySimpleType",
            "anyAtomicType",
            "string",
            "normalizedString",
            "token",
            "language",
            "Name",
            "NCName",
            "NMTOKEN",
            "NMTOKENS",
            "ID",
            "IDREF",
            "IDREFS",
            "ENTITY",
            "ENTITIES",
            "anyURI",
            "QName",
            "NOTATION",
            "base64Binary",
            "hexBinary",
            "float",
            "double",
            "duration",
            "dayTimeDuration",
            "yearMonthDuration",
            "gYear",
            "gYearMonth",
            "gMonth",
            "gMonthDay",
            "gDay"));
    put(table, ValueType.BOOLEAN, List.of("boolean"));
    put(table, ValueType.DECIMAL, List.of("decimal"));
    put(
        table,
        ValueType.INTEGER,
        List.of(
            "integer",
            "nonPositiveInteger",
            "negativeInteger",
            "long",
            "int",
            "short",
            "byte",
            "nonNegativeInteger",
            "unsignedLong",
            "unsignedInt",
            "unsignedShort",
            "unsignedByte",
            "positiveInteger"));
    put(table, ValueType.DATE, List.of("date"));
    put(table, ValueType.TIME, List.of("time"));
    put(table, ValueType.DATE_TIME, List.of("dateTime", "dateTimeStamp"));
    return Map.copyOf(table);
  }

  private static void put(Map<String, ValueType> table, ValueType type, List<String> names) {
    for (String name : names) {
      table.put(name, type);
    }
  }
}
