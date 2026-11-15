package contexts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * The seeded input generator of the {@code contexts} bench mapping (ADR-0021, #181): customers of
 * one to three orders, each of one to four items, with invented English names (ADR-0002). An
 * order's priority (1 to 9) and an item's rank (1 to 999) are the keys the mapping sorts by; equal
 * keys occur. The same seed and size give the same bytes on every JDK, because the specification of
 * {@link Random} fixes its algorithm. The input grows by whole customers until it holds at least
 * {@code size} bytes; it is ASCII, so a character is a byte.
 *
 * <p>The inputs for a comparison outside the benchmarks come from the bench jar, after {@code
 * ./mvnw -B -Pbench -pl bench/jmh -am verify}:
 *
 * <pre>
 * java -cp bench/jmh/target/benchmarks.jar contexts.ContextsGenerator large contexts-large.xml
 * </pre>
 *
 * <p>{@code small} writes the small input. The pinned digests of both are in {@code
 * ContextsInputTest}.
 */
public final class ContextsGenerator {

  private static final String[] FIRST_NAMES = {
    "Ada", "Basil", "Cora", "Dale", "Edith", "Felix", "Greta", "Hugo", "Iris", "Jonah", "Kira",
    "Leon"
  };

  private static final String[] LAST_NAMES = {
    "Ashby", "Brookfield", "Calloway", "Dunmore", "Ellwood", "Fairbank", "Garrow", "Hollis"
  };

  private static final String END = "</Customers>\n";

  private static final String USAGE =
      "usage: contexts.ContextsGenerator <small|large> <output file>";

  private ContextsGenerator() {}

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
    try (InputStream in = ContextsGenerator.class.getResourceAsStream("bench.json")) {
      bench =
          new String(
              Objects.requireNonNull(in, "no class-path resource contexts/bench.json")
                  .readAllBytes(),
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
   * The input of {@code seed}: whole customers, numbered from {@code CU-10001}, their orders from
   * {@code PO-200001}, until the document holds at least {@code size} bytes.
   */
  public static byte[] generate(long seed, int size) {
    Random random = new Random(seed);
    StringBuilder xml = new StringBuilder(size + 1024);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Customers>\n");
    int orders = 0;
    for (int number = 1; xml.length() + END.length() < size; number++) {
      orders = customer(xml, number, orders, random);
    }
    return xml.append(END).toString().getBytes(UTF_8);
  }

  /**
   * Writes customer {@code number}, whose orders are numbered on from the {@code orders} written
   * before it, and returns the count of orders written so far.
   */
  private static int customer(StringBuilder xml, int number, int orders, Random random) {
    xml.append("  <Customer>\n");
    element(xml, 4, "CustomerId", "CU-" + (10_000 + number));
    element(
        xml,
        4,
        "Name",
        FIRST_NAMES[random.nextInt(FIRST_NAMES.length)]
            + " "
            + LAST_NAMES[random.nextInt(LAST_NAMES.length)]);
    int count = 1 + random.nextInt(3);
    for (int order = 0; order < count; order++) {
      xml.append("    <Order>\n");
      element(xml, 6, "OrderNo", "PO-" + (200_000 + ++orders));
      element(xml, 6, "Priority", String.valueOf(1 + random.nextInt(9)));
      int items = 1 + random.nextInt(4);
      for (int item = 0; item < items; item++) {
        xml.append("      <Item>\n");
        element(xml, 8, "Sku", "SKU-" + (10_000 + random.nextInt(90_000)));
        element(xml, 8, "Rank", String.valueOf(1 + random.nextInt(999)));
        element(xml, 8, "Quantity", String.valueOf(1 + random.nextInt(50)));
        xml.append("      </Item>\n");
      }
      xml.append("    </Order>\n");
    }
    xml.append("  </Customer>\n");
    return orders;
  }

  private static void element(StringBuilder xml, int indent, String name, String value) {
    xml.repeat(' ', indent)
        .append('<')
        .append(name)
        .append('>')
        .append(value)
        .append("</")
        .append(name)
        .append(">\n");
  }
}
