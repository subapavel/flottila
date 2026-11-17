package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The block syntax shared by case files ({@code <family>.cases}) and recordings ({@code <family>.rec}) of the
 * differential oracle (ADR-0020).
 *
 * <p>A file is UTF-8 and LF. Outside a block, blank lines and lines starting with {@code #} are skipped. A block runs
 * from {@code case <family>/<name>} to {@code end}; every line between is {@code key value}, split at the first space
 * (a line without a space is a key with an empty value). Values are escaped {@code \n \t \\ \xHH} and otherwise
 * UTF-8. A block's {@code def} is the first 12 hex digits of SHA-256 over its lines from {@code case} to {@code end},
 * each followed by LF.
 */
public final class CaseFormat {

  /** One {@code key value} line of a block; the value is still escaped. */
  public record Line(int number, String key, String value) {}

  /** One block: its id, the number of its {@code case} line, its key lines and its def. */
  public record Block(String id, int number, List<Line> lines, String def) {}

  private CaseFormat() {}

  /**
   * The family of a {@code .cases} or {@code .rec} file (ADR-0025): its path below the nearest enclosing directory
   * named {@code diff} ({@code diff/gen/values-v1.cases} is {@code gen/values-v1}); its name alone when no directory
   * of the path is named {@code diff}. Either way without the extension.
   */
  public static String family(Path file) {
    Path absolute = file.toAbsolutePath().normalize();
    for (Path dir = absolute.getParent(); dir.getFileName() != null; dir = dir.getParent()) {
      if (dir.getFileName().toString().equals("diff")) return family(dir, absolute);
    }
    return family(absolute.getParent(), absolute);
  }

  /** The family of a file below {@code dir}: its relative path with {@code /} separators, without the extension. */
  public static String family(Path dir, Path file) {
    StringJoiner relative = new StringJoiner("/");
    for (Path name : dir.relativize(file)) relative.add(name.toString());
    String path = relative.toString();
    return path.substring(0, path.lastIndexOf('.'));
  }

  /** Reads the blocks of a file whose family is given by {@link #family(Path)}. */
  public static List<Block> read(Path file) {
    return read(file, family(file));
  }

  /** Reads the blocks of a file of the given family. */
  public static List<Block> read(Path file, String family) {
    try {
      return parse(file.getFileName().toString(), family, Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Parses the blocks of one file. {@code where} names the file in error messages; every id must be {@code
   * <family>/<name>} with a non-empty name and occur once.
   *
   * @throws IllegalArgumentException naming the file and line of the first malformed line
   */
  public static List<Block> parse(String where, String family, byte[] content) {
    String text;
    try {
      text = strictUtf8().decode(ByteBuffer.wrap(content)).toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException(where + ": not UTF-8", e);
    }
    String[] lines = text.split("\n", -1);
    List<Block> blocks = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    String id = null;
    int start = 0;
    List<Line> body = new ArrayList<>();
    StringBuilder raw = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      int number = i + 1;
      if (id == null) {
        if (line.isEmpty() || line.startsWith("#")) continue;
        if (!line.startsWith("case ")) {
          throw new IllegalArgumentException(where + ":" + number + ": expected 'case " + family + "/<name>'");
        }
        id = line.substring("case ".length());
        if (!id.startsWith(family + "/") || id.length() == family.length() + 1) {
          throw new IllegalArgumentException(where + ":" + number + ": case id '" + id + "' is not " + family + "/<name>");
        }
        if (!ids.add(id)) throw new IllegalArgumentException(where + ":" + number + ": case " + id + " occurs twice");
        start = number;
        body = new ArrayList<>();
        raw.setLength(0);
        raw.append(line).append('\n');
        continue;
      }
      raw.append(line).append('\n');
      if (line.equals("end")) {
        blocks.add(new Block(id, start, List.copyOf(body), def(raw.toString())));
        id = null;
        continue;
      }
      String[] keyAndValue = line.split(" ", 2);
      body.add(new Line(number, keyAndValue[0], keyAndValue.length == 2 ? keyAndValue[1] : ""));
    }
    if (id != null) throw new IllegalArgumentException(where + ":" + start + ": case " + id + " has no 'end'");
    return blocks;
  }

  private static String def(String block) {
    try {
      byte[] sha = MessageDigest.getInstance("SHA-256").digest(block.getBytes(UTF_8));
      return HexFormat.of().formatHex(sha).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JDK has SHA-256", e);
    }
  }

  private static CharsetDecoder strictUtf8() {
    return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
  }

  /**
   * The bytes an escaped value stands for.
   *
   * @throws IllegalArgumentException on a backslash not followed by {@code n}, {@code t}, {@code \} or {@code x} and
   *     two hex digits
   */
  public static byte[] unescape(String value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    StringBuilder plain = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != '\\') {
        plain.append(c);
        continue;
      }
      out.writeBytes(plain.toString().getBytes(UTF_8));
      plain.setLength(0);
      if (i + 1 == value.length()) throw new IllegalArgumentException("a value ends with a lone '\\'");
      char e = value.charAt(i + 1);
      switch (e) {
        case 'n' -> out.write('\n');
        case 't' -> out.write('\t');
        case '\\' -> out.write('\\');
        case 'x' -> {
          if (i + 3 >= value.length() || !HexFormat.isHexDigit(value.charAt(i + 2)) || !HexFormat.isHexDigit(value.charAt(i + 3))) {
            throw new IllegalArgumentException("'\\x' needs two hex digits");
          }
          out.write(HexFormat.fromHexDigits(value, i + 2, i + 4));
          i += 2;
        }
        default -> throw new IllegalArgumentException("unknown escape '\\" + e + "'");
      }
      i++;
    }
    out.writeBytes(plain.toString().getBytes(UTF_8));
    return out.toByteArray();
  }

  /**
   * The escaped text of bytes: {@code \} as {@code \\}, LF as {@code \n}, tab as {@code \t}, other control bytes, DEL
   * and every byte of a malformed UTF-8 sequence as {@code \xHH} (lower case); valid UTF-8 stays as it is.
   */
  public static String escape(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    CharsetDecoder decoder = strictUtf8();
    ByteBuffer in = ByteBuffer.wrap(bytes);
    CharBuffer chars = CharBuffer.allocate(bytes.length);
    while (true) {
      CoderResult result = decoder.decode(in, chars, true);
      chars.flip();
      while (chars.hasRemaining()) escapeChar(out, chars.get());
      chars.clear();
      if (!result.isError()) break;
      for (int k = 0; k < result.length(); k++) out.append(String.format("\\x%02x", in.get() & 0xff));
    }
    return out.toString();
  }

  private static void escapeChar(StringBuilder out, char c) {
    switch (c) {
      case '\\' -> out.append("\\\\");
      case '\n' -> out.append("\\n");
      case '\t' -> out.append("\\t");
      default -> {
        if (c < 0x20 || c == 0x7f) out.append(String.format("\\x%02x", (int) c));
        else out.append(c);
      }
    }
  }
}
