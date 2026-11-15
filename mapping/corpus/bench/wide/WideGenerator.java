package wide;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * The seeded input generator of the {@code wide} bench mapping (ADR-0021, #186): a flat, IDoc-like
 * feed of records, each with all 300 fields of {@code wide-source.xsd} in its order, twelve
 * segments of the same 25 attributes, with invented English names (ADR-0002) and mixed types. The
 * same seed and size give the same bytes on every JDK, because the specification of {@link Random}
 * fixes its algorithm. The input grows by whole records until it holds at least {@code size} bytes;
 * it is ASCII, so a character is a byte.
 *
 * <p>The inputs for a comparison outside the benchmarks come from the bench jar, after {@code
 * ./mvnw -B -Pbench -pl bench/jmh -am verify}:
 *
 * <pre>
 * java -cp bench/jmh/target/benchmarks.jar wide.WideGenerator large wide-large.xml
 * </pre>
 *
 * <p>{@code small} writes the small input. The pinned digests of both are in {@code WideInputTest}.
 */
public final class WideGenerator {

  /** The twelve segments: a field's name is its segment's followed by its attribute's. */
  private static final String[] SEGMENTS = {
    "Party", "Route", "Cargo", "Bill", "Duty", "Haul", "Depot", "Ship", "Permit", "Claim", "Tariff",
    "Ledger"
  };

  /** A field's value type, as {@code wide-source.xsd} declares it. */
  private enum Kind {
    CODE,
    WORD,
    REF,
    NOTE,
    AREA,
    CLASS,
    UNIT,
    INTEGER,
    DECIMAL,
    DATE,
    BOOLEAN
  }

  private record Attribute(String name, Kind kind) {}

  /** The 25 attributes of every segment, in schema order. */
  private static final List<Attribute> ATTRIBUTES =
      List.of(
          new Attribute("Code", Kind.CODE),
          new Attribute("Name", Kind.WORD),
          new Attribute("Ref", Kind.REF),
          new Attribute("Note", Kind.NOTE),
          new Attribute("Area", Kind.AREA),
          new Attribute("Class", Kind.CLASS),
          new Attribute("Unit", Kind.UNIT),
          new Attribute("Zone", Kind.AREA),
          new Attribute("Qty", Kind.INTEGER),
          new Attribute("Count", Kind.INTEGER),
          new Attribute("Seq", Kind.INTEGER),
          new Attribute("Level", Kind.INTEGER),
          new Attribute("Mass", Kind.DECIMAL),
          new Attribute("Rate", Kind.DECIMAL),
          new Attribute("Sum", Kind.DECIMAL),
          new Attribute("Vol", Kind.DECIMAL),
          new Attribute("Share", Kind.DECIMAL),
          new Attribute("Cap", Kind.DECIMAL),
          new Attribute("Date", Kind.DATE),
          new Attribute("Due", Kind.DATE),
          new Attribute("From", Kind.DATE),
          new Attribute("Until", Kind.DATE),
          new Attribute("Flag", Kind.BOOLEAN),
          new Attribute("Live", Kind.BOOLEAN),
          new Attribute("Done", Kind.BOOLEAN));

  private static final String[] WORDS = {
    "Alder", "Birch", "Cedar", "Dunlin", "Egret", "Fennel", "Gorse", "Heron", "Ivy", "Juniper"
  };

  private static final String[] NOTES = {"fragile", "urgent", "stack", "dry", "cool", "spare"};

  private static final String[] AREAS = {"NORTH", "SOUTH", "EAST", "WEST", "HARBOUR"};

  private static final String[] UNITS = {"KG", "EA", "M3", "PAL", "BOX"};

  /** The first date; every date falls in the 365 days from it. */
  private static final LocalDate FIRST_DAY = LocalDate.of(2026, 1, 1);

  private static final String END = "</Feed>\n";

  private static final String USAGE = "usage: wide.WideGenerator <small|large> <output file>";

  private WideGenerator() {}

  /**
   * Writes the small or the large input to the output file, from the seed and the size of {@code
   * bench.json} beside this class on the class path: the bytes the benchmarks measure.
   *
   * @throws IllegalArgumentException when the arguments are not {@code small} or {@code large} and
   *     a file
   * @throws IOException when the file cannot be written
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !List.of("small", "large").contains(args[0])) {
      throw new IllegalArgumentException(USAGE);
    }
    String bench;
    try (InputStream in = WideGenerator.class.getResourceAsStream("bench.json")) {
      bench =
          new String(
              Objects.requireNonNull(in, "no class-path resource wide/bench.json").readAllBytes(),
              UTF_8);
    }
    int size = Math.toIntExact(number(bench, args[0] + "Bytes"));
    Files.write(Path.of(args[1]), generate(number(bench, "seed"), size));
  }

  /**
   * The number {@code member} of {@code bench.json}, read as {@code BenchMapping} reads it; a
   * {@code bench.json} without it fails with a {@code NoSuchElementException}.
   */
  private static long number(String bench, String member) {
    return Long.parseLong(
        Pattern.compile("\"" + member + "\"\\s*:\\s*(\\d+)")
            .matcher(bench)
            .results()
            .findFirst()
            .orElseThrow()
            .group(1));
  }

  /**
   * The input of {@code seed}: whole records, numbered by {@code PartyRef} from {@code P-000001},
   * until the document holds at least {@code size} bytes.
   */
  public static byte[] generate(long seed, int size) {
    Random random = new Random(seed);
    StringBuilder xml = new StringBuilder(size + 16_384);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Feed>\n");
    for (int number = 1; xml.length() + END.length() < size; number++) {
      record(xml, number, random);
    }
    return xml.append(END).toString().getBytes(UTF_8);
  }

  private static void record(StringBuilder xml, int number, Random random) {
    // a record is about 9.5 kB, so the small input is one record; records are not indented, to fit
    xml.append("<Record>\n");
    for (String segment : SEGMENTS) {
      for (Attribute attribute : ATTRIBUTES) {
        String name = segment + attribute.name();
        String value =
            name.equals("PartyRef") ? "P-%06d".formatted(number) : value(attribute.kind(), random);
        xml.append("  <")
            .append(name)
            .append('>')
            .append(value)
            .append("</")
            .append(name)
            .append(">\n");
      }
    }
    xml.append("</Record>\n");
  }

  private static String value(Kind kind, Random random) {
    return switch (kind) {
      // two letters and three digits: KD417
      case CODE ->
          "%c%c%d"
              .formatted(
                  'A' + random.nextInt(26), 'A' + random.nextInt(26), 100 + random.nextInt(900));
      case WORD -> WORDS[random.nextInt(WORDS.length)];
      case REF -> "R" + (100_000 + random.nextInt(900_000));
      case NOTE -> NOTES[random.nextInt(NOTES.length)];
      case AREA -> AREAS[random.nextInt(AREAS.length)];
      case CLASS -> String.valueOf((char) ('A' + random.nextInt(5)));
      case UNIT -> UNITS[random.nextInt(UNITS.length)];
      case INTEGER -> String.valueOf(random.nextInt(10_000));
      // 0.01 to 9999.99 as a sender writes it, without trailing zeros: 12.5, 7, 103.25
      case DECIMAL ->
          BigDecimal.valueOf(1 + random.nextInt(999_999), 2).stripTrailingZeros().toPlainString();
      case DATE -> FIRST_DAY.plusDays(random.nextInt(365)).toString();
      case BOOLEAN -> String.valueOf(random.nextBoolean());
    };
  }
}
