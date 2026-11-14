package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The synthetic inputs of ADR-0019: {@code orders}, the input of G1, G2, G6 and G7,
 * {@code {"orders":[{id,total,k}…],"items":[{sku,price}…],"a":{"b":{"c":"x"}}}}; {@code recs}, the input of G3,
 * {@code [{"k":"k0".."k63","v":n}…]}; and {@code ints}, the input of G4, {@code [0..n-1]}. The bytes are a pure function
 * of the size (the {@code recs} draws come from a {@link Random} of the fixed seed {@link #RECS_SEED}, whose algorithm
 * its Javadoc fixes), so JMH (which generates them in its setup) and {@code bench/jq-ref.sh} (which writes them with
 * {@link #main}) time the same document.
 */
public final class Inputs {

  /** The seed of the {@code recs} draws. */
  static final long RECS_SEED = 74;

  /** The element count of {@code G4.1e4}, ADR-0019's G4 cell 10⁴. */
  public static final int INTS_1E4 = 10_000;

  /**
   * The cell sizes of ADR-0019, as the lower-case labels of ADR-0024's bench ids. They are 1024-based: {@code 1k} is
   * 1 KiB (1 024 bytes), {@code 100k} is 100 KiB (102 400 bytes), {@code 10m} is 10 MiB (10 485 760 bytes).
   */
  public enum Size {
    K1("1k", 1_024),
    K100("100k", 102_400),
    M10("10m", 10_485_760);

    private final String label;
    private final int bytes;

    Size(String label, int bytes) {
      this.label = label;
      this.bytes = bytes;
    }

    /** The size as it appears in a bench id, such as {@code 10m}. */
    public String label() {
      return label;
    }

    /** The document's length in bytes. */
    public int bytes() {
      return bytes;
    }
  }

  private static final String HEAD = "{\"orders\":[";
  private static final String MIDDLE = "],\"items\":[";
  private static final String TAIL = "],\"a\":{\"b\":{\"c\":\"x\"}}}";

  private Inputs() {}

  /**
   * The document of {@code size}: as many (order, item) pairs as fit, then spaces and one LF up to exactly
   * {@link Size#bytes()}.
   */
  public static byte[] orders(Size size) {
    int bytes = size.bytes();
    StringBuilder orders = new StringBuilder();
    StringBuilder items = new StringBuilder();
    int fixed = HEAD.length() + MIDDLE.length() + TAIL.length() + 1;
    for (int i = 0; ; i++) {
      String order = order(i);
      String item = item(i);
      int comma = i == 0 ? 0 : 1;
      if (fixed + orders.length() + items.length() + comma + order.length() + comma + item.length() > bytes) {
        break;
      }
      if (comma == 1) {
        orders.append(',');
        items.append(',');
      }
      orders.append(order);
      items.append(item);
    }
    StringBuilder doc = new StringBuilder(bytes).append(HEAD).append(orders).append(MIDDLE).append(items).append(TAIL);
    return padded(doc, bytes);
  }

  /**
   * The {@code recs} document of {@code size}: as many records {@code {"k":"k<j>","v":<n>}} as fit in one array, then
   * spaces and one LF up to exactly {@link Size#bytes()}. Record {@code i} takes the {@code i}-th pair of draws ({@code j}
   * of 0 to 63, {@code n} of 0 to 999 999), so a smaller document is a prefix of a larger one's records.
   */
  public static byte[] recs(Size size) {
    Random draws = new Random(RECS_SEED);
    return array(size, () -> "{\"k\":\"k" + draws.nextInt(64) + "\",\"v\":" + draws.nextInt(1_000_000) + "}");
  }

  /**
   * The {@code ints} document of {@code size}: {@code [0,1,…]} with as many integers as fit, then spaces and one LF up
   * to exactly {@link Size#bytes()}.
   */
  public static byte[] ints(Size size) {
    int[] next = {0};
    return array(size, () -> Integer.toString(next[0]++));
  }

  /** The {@code ints} document of {@code n} elements: {@code [0,1,…,n-1]} and one LF, with no padding. */
  public static byte[] ints(int n) {
    StringBuilder doc = new StringBuilder().append('[');
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        doc.append(',');
      }
      doc.append(i);
    }
    return doc.append("]\n").toString().getBytes(US_ASCII);
  }

  /** One array of the elements {@code next} gives, as many as fit in {@code size} with its padding and LF. */
  private static byte[] array(Size size, Supplier<String> next) {
    int bytes = size.bytes();
    StringBuilder doc = new StringBuilder(bytes).append('[');
    String element = next.get();
    // An element after the first needs its comma; "]" and the LF follow the last one.
    while (doc.length() + (doc.length() > 1 ? 1 : 0) + element.length() + 2 <= bytes) {
      if (doc.length() > 1) {
        doc.append(',');
      }
      doc.append(element);
      element = next.get();
    }
    return padded(doc.append(']'), bytes);
  }

  /** {@code doc}, then spaces and one LF up to exactly {@code bytes}, in ASCII. */
  private static byte[] padded(StringBuilder doc, int bytes) {
    doc.repeat(' ', bytes - 1 - doc.length()).append('\n');
    return doc.toString().getBytes(US_ASCII);
  }

  /** Order {@code i}: a total of 0.00 to 199.99 in canonical form, and one of 64 keys {@code k}. */
  private static String order(int i) {
    int cents = (int) ((i * 7_919L + 13) % 20_000);
    return "{\"id\":" + i + ",\"total\":" + decimal(cents) + ",\"k\":\"k" + (i % 64) + "\"}";
  }

  /** Item {@code i}: an integer price of 1 to 9999. */
  private static String item(int i) {
    return "{\"sku\":\"s" + i + "\",\"price\":" + ((i * 4_730L) % 9_999 + 1) + "}";
  }

  /** {@code cents} / 100 without trailing zeros, so the number's token is the one jq prints. */
  private static String decimal(int cents) {
    String whole = Integer.toString(cents / 100);
    int fraction = cents % 100;
    if (fraction == 0) {
      return whole;
    }
    return fraction % 10 == 0 ? whole + "." + fraction / 10 : whole + "." + (fraction < 10 ? "0" : "") + fraction;
  }

  /**
   * Writes {@code orders-<label>.json} and {@code recs-<label>.json} for every {@link Size}, and
   * {@code ints-1e4.json} ({@link #ints(int)} of {@link #INTS_1E4}), into the directory {@code args[0]}, which it
   * creates.
   *
   * @throws IOException if a file cannot be written
   */
  public static void main(String[] args) throws IOException {
    Path dir = Files.createDirectories(Path.of(args[0]));
    for (Size size : Size.values()) {
      Files.write(dir.resolve("orders-" + size.label() + ".json"), orders(size));
      Files.write(dir.resolve("recs-" + size.label() + ".json"), recs(size));
    }
    Files.write(dir.resolve("ints-1e4.json"), ints(INTS_1E4));
  }
}
