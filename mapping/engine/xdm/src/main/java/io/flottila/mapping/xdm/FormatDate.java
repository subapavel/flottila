package io.flottila.mapping.xdm;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine's one date formatting core (ADR-0003): F&amp;O 3.1 {@code format-dateTime} over the
 * pictures a date pattern compiles to ({@link DatePattern}), as Saxon-HE 12.5 formats them, and the
 * one internal parsing function that mirrors that picture syntax, as F&amp;O has none. The helper
 * module's {@code po:parse-date} parses as {@link #parse} does. Such a picture is literal text,
 * with {@code [[} and {@code ]]} for the square brackets, and the components {@code [Y0001]},
 * {@code [M1]}, {@code [M01]}, {@code [D1]}, {@code [D01]}, {@code [H1]}, {@code [H01]}, {@code
 * [m1]}, {@code [m01]}, {@code [s1]}, {@code [s01]}, {@code [f001]}, {@code [Z0000]} and {@code
 * [Z01:01t]}.
 */
public final class FormatDate {

  /** A picture's tokens: an escaped bracket, a component (group 1), or literal text. */
  private static final Pattern TOKEN =
      Pattern.compile("\\[\\[|\\]\\]|\\[([^\\[\\]]*)\\]|[^\\[\\]]+");

  /**
   * A component of a picture: its name, such as {@code Y0001}, and the digits a parse reads for it.
   */
  private enum Field {
    YEAR("Y0001", "[0-9]{4}"),
    MONTH("M1", "[0-9]{1,2}"),
    MONTH_2("M01", "[0-9]{2}"),
    DAY("D1", "[0-9]{1,2}"),
    DAY_2("D01", "[0-9]{2}"),
    HOUR("H1", "[0-9]{1,2}"),
    HOUR_2("H01", "[0-9]{2}"),
    MINUTE("m1", "[0-9]{1,2}"),
    MINUTE_2("m01", "[0-9]{2}"),
    SECOND("s1", "[0-9]{1,2}"),
    SECOND_2("s01", "[0-9]{2}"),
    MILLISECOND("f001", "[0-9]{3}"),
    OFFSET("Z0000", "[+-][0-9]{4}"),
    OFFSET_Z("Z01:01t", "Z|[+-][0-9]{2}:[0-9]{2}");

    final String component;
    final String digits;

    Field(String component, String digits) {
      this.component = component;
      this.digits = digits;
    }
  }

  /** The fields by their component names. */
  private static final Map<String, Field> FIELDS = fields();

  private static Map<String, Field> fields() {
    Map<String, Field> fields = new HashMap<>();
    for (Field field : Field.values()) {
      fields.put(field.component, field);
    }
    return Map.copyOf(fields);
  }

  /** The greatest offset an xs:dateTime has, in minutes: 14 hours. */
  private static final int MAX_OFFSET = 14 * 60;

  /**
   * A picture analysed once (#194), which writes and reads any number of dates: a binder analyses a
   * box's picture when it binds, not per value. It holds no state of a format or a parse and may be
   * shared.
   */
  public static final class Picture {
    private final List<Token> tokens;

    /** The regex a parse matches the whole text with: one group per component, in order. */
    private final Pattern parser;

    /** The fields of the components, in picture order. */
    private final List<Field> components;

    private Picture(List<Token> tokens, Pattern parser, List<Field> components) {
      this.tokens = tokens;
      this.parser = parser;
      this.components = components;
    }

    /** {@code value} written with this picture, in its own offset. */
    public String format(OffsetDateTime value) {
      StringBuilder out = new StringBuilder();
      for (Token token : tokens) {
        out =
            switch (token) {
              case Literal literal -> out.append(literal.text());
              case Component c -> component(out, value, c.field());
            };
      }
      return out.toString();
    }

    /**
     * The date {@code text} writes in this picture, as {@link FormatDate#parse} reads it.
     *
     * @return the date, or empty when {@code text} writes none
     */
    public Optional<OffsetDateTime> parse(String text, ZoneOffset implicit) {
      Matcher m = parser.matcher(text);
      if (!m.matches()) {
        return Optional.empty();
      }
      int[] fields = {1970, 1, 1, 0, 0, 0, 0};
      ZoneOffset offset = implicit;
      for (int k = 0; k < components.size(); k++) {
        String name = components.get(k).component;
        String value = m.group(k + 1);
        switch (name.charAt(0)) {
          case 'Z' -> {
            Optional<ZoneOffset> parsed = offset(value);
            if (parsed.isEmpty()) {
              return Optional.empty();
            }
            offset = parsed.get();
          }
          default -> fields["YMDHmsf".indexOf(name.charAt(0))] = Integer.parseInt(value);
        }
      }
      if (fields[0] == 0) {
        // xs:dateTime has a year 0000 (XSD 1.1), SimpleDateFormat refuses it
        return Optional.empty();
      }
      try {
        return Optional.of(
            OffsetDateTime.of(
                LocalDate.of(fields[0], fields[1], fields[2]),
                LocalTime.of(fields[3], fields[4], fields[5], fields[6] * 1_000_000),
                offset));
      } catch (DateTimeException noDate) {
        return Optional.empty();
      }
    }
  }

  private FormatDate() {}

  /**
   * {@code picture} analysed: its tokens, and the regex a parse reads with.
   *
   * @throws IllegalArgumentException when {@code picture} is none that a date pattern compiles to
   */
  public static Picture picture(String picture) {
    List<Token> tokens = tokens(picture);
    StringBuilder regex = new StringBuilder();
    List<Field> components = new ArrayList<>();
    for (Token token : tokens) {
      switch (token) {
        case Literal literal -> regex.append(Pattern.quote(literal.text()));
        case Component c -> {
          regex.append('(').append(c.field().digits).append(')');
          components.add(c.field());
        }
      }
    }
    return new Picture(tokens, Pattern.compile(regex.toString()), List.copyOf(components));
  }

  /**
   * {@code value} written with {@code picture}, in its own offset.
   *
   * @throws IllegalArgumentException when {@code picture} is none that a date pattern compiles to
   */
  public static String format(OffsetDateTime value, String picture) {
    return picture(picture).format(value);
  }

  /**
   * The date {@code text} writes in {@code picture}: the whole text matches the picture, and its
   * fields make a date and a time of day; a field the picture lacks is that of {@code
   * 1970-01-01T00:00:00.000}, and the offset, when the picture has none, is {@code implicit}.
   *
   * @return the date, or empty when {@code text} writes none
   * @throws IllegalArgumentException when {@code picture} is none that a date pattern compiles to
   */
  public static Optional<OffsetDateTime> parse(String text, String picture, ZoneOffset implicit) {
    return picture(picture).parse(text, implicit);
  }

  /** The offset {@code text} writes, {@code Z} or a sign with hours and minutes, if in range. */
  private static Optional<ZoneOffset> offset(String text) {
    if (text.equals("Z")) {
      return Optional.of(ZoneOffset.UTC);
    }
    String digits = text.replace(":", "");
    int hours = Integer.parseInt(digits.substring(1, 3));
    int minutes = Integer.parseInt(digits.substring(3));
    if (minutes > 59 || hours * 60 + minutes > MAX_OFFSET) {
      return Optional.empty();
    }
    int sign = text.charAt(0) == '-' ? -1 : 1;
    return Optional.of(ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes));
  }

  /** One token of a picture: {@link Literal} text or a {@link Component}. */
  private sealed interface Token permits Literal, Component {}

  /**
   * Literal text of a picture, an escaped bracket as the bracket itself.
   *
   * @param text the text the token writes
   */
  private record Literal(String text) implements Token {}

  /**
   * A component of a picture.
   *
   * @param field the component's field
   */
  private record Component(Field field) implements Token {}

  /** The tokens of {@code picture}, in order, each component one of the {@link Field}s. */
  private static List<Token> tokens(String picture) {
    List<Token> tokens = new ArrayList<>();
    Matcher m = TOKEN.matcher(picture);
    int end = 0;
    while (m.find()) {
      if (m.start() != end) {
        throw unknown(picture);
      }
      String text = m.group();
      if (m.group(1) != null) {
        Field field = FIELDS.get(m.group(1));
        if (field == null) {
          throw unknown(picture);
        }
        tokens.add(new Component(field));
      } else {
        tokens.add(new Literal(text.equals("[[") ? "[" : text.equals("]]") ? "]" : text));
      }
      end = m.end();
    }
    if (end != picture.length()) {
      throw unknown(picture);
    }
    return List.copyOf(tokens);
  }

  /** Appends the component {@code field} of {@code value} to {@code out}. */
  private static StringBuilder component(StringBuilder out, OffsetDateTime value, Field field) {
    return switch (field) {
      case YEAR -> pad(out, value.getYear(), 4);
      case MONTH -> out.append(value.getMonthValue());
      case MONTH_2 -> pad(out, value.getMonthValue(), 2);
      case DAY -> out.append(value.getDayOfMonth());
      case DAY_2 -> pad(out, value.getDayOfMonth(), 2);
      case HOUR -> out.append(value.getHour());
      case HOUR_2 -> pad(out, value.getHour(), 2);
      case MINUTE -> out.append(value.getMinute());
      case MINUTE_2 -> pad(out, value.getMinute(), 2);
      case SECOND -> out.append(value.getSecond());
      case SECOND_2 -> pad(out, value.getSecond(), 2);
      case MILLISECOND -> pad(out, value.getNano() / 1_000_000, 3);
      case OFFSET -> zone(out, value.getOffset(), "");
      case OFFSET_Z ->
          value.getOffset().getTotalSeconds() == 0
              ? out.append('Z')
              : zone(out, value.getOffset(), ":");
    };
  }

  /**
   * Appends {@code offset} to {@code out} as a sign, two digits of hours, {@code separator} and two
   * of minutes.
   */
  private static StringBuilder zone(StringBuilder out, ZoneOffset offset, String separator) {
    int minutes = offset.getTotalSeconds() / 60;
    out.append(minutes < 0 ? '-' : '+');
    pad(out, Math.abs(minutes) / 60, 2).append(separator);
    return pad(out, Math.abs(minutes) % 60, 2);
  }

  /**
   * Appends {@code value} to {@code out} with zeros before its digits up to {@code width}
   * characters, its sign counted, as {@code %0}<i>width</i>{@code d} writes it; a longer number
   * whole.
   */
  private static StringBuilder pad(StringBuilder out, int value, int width) {
    int start = out.length();
    if (value < 0) {
      out.append('-');
    }
    int digits = out.length();
    out.append(Math.abs(value));
    while (out.length() - start < width) {
      out.insert(digits, '0');
    }
    return out;
  }

  private static IllegalArgumentException unknown(String picture) {
    return new IllegalArgumentException(
        "the picture \"" + picture + "\" is none that a date pattern compiles to (ADR-0003)");
  }
}
