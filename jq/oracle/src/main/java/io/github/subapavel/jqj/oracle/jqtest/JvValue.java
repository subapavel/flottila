package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The oracle's copy of a jqj value, with what {@link JvEqual} compares and nothing more (ADR-0020). It is a copy
 * because a result is valid only during its sink call (ADR-0016), and the oracle's own because the oracle module
 * does not read jq-core's.
 */
public sealed interface JvValue {

  /** The value as JSON-like text, for reading a diff only. */
  String text();

  /** jq's null, false and true: three kinds, as in jv. */
  enum Constant implements JvValue {
    NULL,
    FALSE,
    TRUE;

    @Override
    public String text() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * A number.
   *
   * @param value the double jq compares a computed number by
   * @param decimal the exact value of a literal, null for NaN and the infinities
   * @param literal whether it is a literal number (GLOSSARY)
   */
  record Num(double value, BigDecimal decimal, boolean literal) implements JvValue {
    @Override
    public String text() {
      return decimal != null ? decimal.toString() : Double.toString(value);
    }
  }

  /** A string as its UTF-8 bytes. */
  record Str(byte[] utf8) implements JvValue {
    @Override
    public String text() {
      return '"' + new String(utf8, UTF_8) + '"';
    }
  }

  record Arr(List<JvValue> elements) implements JvValue {
    public Arr {
      elements = List.copyOf(elements);
    }

    @Override
    public String text() {
      return elements.stream().map(JvValue::text).collect(Collectors.joining(",", "[", "]"));
    }
  }

  /** An object in jq's key order, which is shown but not compared. */
  record Obj(Map<String, JvValue> members) implements JvValue {
    public Obj {
      members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    @Override
    public String text() {
      return members.entrySet().stream().map(e -> '"' + e.getKey() + "\":" + e.getValue().text()).collect(Collectors.joining(",", "{", "}"));
    }
  }
}
