package io.flottila.mapping.mapping;

import com.github.erosb.jsonsKema.IJsonArray;
import com.github.erosb.jsonsKema.IJsonObject;
import com.github.erosb.jsonsKema.IJsonString;
import com.github.erosb.jsonsKema.IJsonValue;
import io.flottila.mapping.mapping.internal.StrictJsonReader;
import io.flottila.mapping.xdm.DatePattern;
import io.flottila.mapping.xdm.NumberPattern;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The function catalogue (ADR-0025, ADR-0026): {@code function-catalogue.json}, a resource of this
 * package, read once and published as it is packed, through {@link #bytes()}. Only lowering reads
 * its entries, and only what lowering needs: a function's execution kind, whether it is generating
 * (#217), the names of its inputs and its params, in order, with each param's default and the
 * values or the syntax it takes; {@code family}, {@code portable} and {@code fidelity} are read
 * past.
 *
 * <p>A param is a string param or a table param, by its {@code type}, and lowering makes either a
 * {@code Literal} (ADR-0026, #83). A string param with {@code values} takes only those, one with a
 * {@code syntax} only the patterns the formatting core translates (ADR-0026, #74, #80), one with
 * neither any string. A table param takes any table of strings, and its default is a table.
 */
public final class FunctionCatalogue {

  /**
   * A param of a function: a string param, or a table param, whose default is a table.
   *
   * @param name the param's name, the key a box gives it under
   * @param fallback the {@code default}, the value of a box that leaves the param out: a {@link
   *     Text} for a string param, a {@link Table} for a table param
   * @param values the strings a string param takes, or empty when it takes any string; empty for a
   *     table param
   * @param syntax the pattern syntax a string param is written in, or empty when it has none
   */
  record Param(
      String name, ParamValue fallback, Optional<List<String>> values, Optional<String> syntax) {

    /** A string param whose {@code default} is the string {@code fallback}. */
    Param(String name, String fallback, Optional<List<String>> values, Optional<String> syntax) {
      this(name, new Text(fallback), values, syntax);
    }

    /** Whether the param is a table param, whose box value is a table. */
    boolean table() {
      return fallback instanceof Table;
    }

    /** Whether the param takes the string {@code value} by its values. */
    boolean takes(String value) {
      return values.map(allowed -> allowed.contains(value)).orElse(true);
    }

    /**
     * Why the param's syntax refuses the pattern {@code value}, the element the formatting core
     * names, or empty when it takes it or the param has no syntax. The schema's syntaxes are {@code
     * numberPattern}, a DecimalFormat pattern, and {@code datePattern}, a SimpleDateFormat pattern
     * (ADR-0003, ADR-0026), each checked by the core's compiler of its syntax.
     */
    Optional<String> untranslatable(String value) {
      return syntax.flatMap(
          name -> {
            try {
              PICTURES.get(name).apply(value);
              return Optional.empty();
            } catch (IllegalArgumentException refused) {
              return Optional.of(refused.getMessage());
            }
          });
    }
  }

  /**
   * A function's signature.
   *
   * @param executionKind what one call sees: one value, one context or the whole queue
   * @param inputs the input names, in order
   * @param params the params, in order
   * @param generating whether its result is a generating block, a queue function's one value that
   *     every occurrence of a target field takes, as a constant's (ADR-0006, F-46)
   */
  record Entry(
      ExecutionKind executionKind, List<String> inputs, List<Param> params, boolean generating) {

    /** The entry of a function whose result is no generating block. */
    Entry(ExecutionKind executionKind, List<String> inputs, List<Param> params) {
      this(executionKind, inputs, params, false);
    }

    /** The param {@code name}, or empty when the function has no such one. */
    Optional<Param> param(String name) {
      return params.stream().filter(p -> p.name().equals(name)).findFirst();
    }
  }

  /**
   * The formatting core's compiler of each syntax the schema lists (ADR-0026): a pattern to its
   * XPath picture, or refused with the element it cannot translate.
   */
  private static final Map<String, UnaryOperator<String>> PICTURES =
      Map.of("numberPattern", NumberPattern::picture, "datePattern", DatePattern::picture);

  /** The packed catalogue's bytes, never handed out: {@link #bytes()} copies them. */
  private static final byte[] PACKED;

  private static final Map<String, Entry> ENTRIES;

  static {
    try (InputStream in = FunctionCatalogue.class.getResourceAsStream("function-catalogue.json")) {
      PACKED = in.readAllBytes();
      ENTRIES = read(PACKED);
    } catch (IOException | MalformedJsonException e) {
      throw new IllegalStateException("the packed function catalogue cannot be read", e);
    }
  }

  private FunctionCatalogue() {}

  /**
   * The packed catalogue as it is published (ADR-0026): its bytes in canonical form, an instance of
   * {@code contract/function-catalogue.schema.json}. Each call gives a copy of its own.
   */
  public static byte[] bytes() {
    return PACKED.clone();
  }

  /** The entry of function {@code name}, or empty when the catalogue has no such one. */
  static Optional<Entry> entry(String name) {
    return Optional.ofNullable(ENTRIES.get(name));
  }

  /**
   * The entries of {@code catalogue}, a function catalogue's bytes, by function name.
   *
   * @throws MalformedJsonException if the bytes are no JSON document
   */
  static Map<String, Entry> read(byte[] catalogue) throws MalformedJsonException {
    IJsonObject functions = (IJsonObject) StrictJsonReader.read(catalogue);
    Map<String, Entry> entries = new HashMap<>();
    functions
        .getProperties()
        .forEach((name, entry) -> entries.put(name.getValue(), entry((IJsonObject) entry)));
    return Map.copyOf(entries);
  }

  /**
   * {@code entry}'s execution kind, inputs, params and whether it is generating; a function without
   * inputs, as {@code constant}, or without params leaves the member out, a string param that takes
   * any string leaves {@code values} out, and a function that is not generating leaves {@code
   * generating} out, which the schema allows only as {@code true} (ADR-0026).
   */
  private static Entry entry(IJsonObject entry) {
    ExecutionKind kind =
        ExecutionKind.valueOf(string(entry, "executionKind").toUpperCase(Locale.ROOT));
    List<String> inputs = new ArrayList<>();
    if (entry.get("inputs") != null) {
      for (IJsonValue input : elements(entry, "inputs")) {
        inputs.add(string((IJsonObject) input, "name"));
      }
    }
    List<Param> params = new ArrayList<>();
    if (entry.get("params") != null) {
      for (IJsonValue value : elements(entry, "params")) {
        IJsonObject param = (IJsonObject) value;
        params.add(
            new Param(
                string(param, "name"),
                fallback(param),
                values(param),
                Optional.ofNullable(param.get("syntax")).map(s -> ((IJsonString) s).getValue())));
      }
    }
    return new Entry(
        kind, List.copyOf(inputs), List.copyOf(params), entry.get("generating") != null);
  }

  /** The {@code default} of {@code param}: a table for a table param, else a string. */
  private static ParamValue fallback(IJsonObject param) {
    if (string(param, "type").equals("table")) {
      Map<String, String> rows = new HashMap<>();
      ((IJsonObject) param.get("default"))
          .getProperties()
          .forEach((key, row) -> rows.put(key.getValue(), ((IJsonString) row).getValue()));
      return new Table(rows);
    }
    return new Text(string(param, "default"));
  }

  /** The {@code values} of {@code param}, or empty when it has none and takes any string. */
  private static Optional<List<String>> values(IJsonObject param) {
    if (param.get("values") == null) {
      return Optional.empty();
    }
    List<String> values = new ArrayList<>();
    for (IJsonValue allowed : elements(param, "values")) {
      values.add(((IJsonString) allowed).getValue());
    }
    return Optional.of(List.copyOf(values));
  }

  private static List<? extends IJsonValue> elements(IJsonObject object, String member) {
    return ((IJsonArray) object.get(member)).getElements();
  }

  private static String string(IJsonObject object, String member) {
    return ((IJsonString) object.get(member)).getValue();
  }
}
