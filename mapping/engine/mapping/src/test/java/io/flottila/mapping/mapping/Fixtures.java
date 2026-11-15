package io.flottila.mapping.mapping;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Files the tests read from the repository: the committed canonical fixtures under {@code
 * engine/mapping/src/test/resources/mapping-model} (not in {@code corpus/}, the service's dev
 * workspace, which lists every mapping model in it) and the contract under {@code contract/}.
 * Surefire runs in the module directory, so the repository root is the nearest ancestor holding
 * {@code mvnw} and {@code contract/}.
 */
final class Fixtures {

  private Fixtures() {}

  static Path repoRoot() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("mvnw")) && Files.isDirectory(p.resolve("contract"))) {
        return p;
      }
    }
    throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
  }

  static Path mappingModelFixtures() {
    return repoRoot().resolve("engine/mapping/src/test/resources/mapping-model");
  }

  static Path schemaFile() {
    return repoRoot().resolve("contract/mapping-model.schema.json");
  }

  /** Every {@code *.mapping.json} of {@link #mappingModelFixtures}, sorted by name. */
  static List<Path> canonicalFixtures() {
    try (Stream<Path> files = Files.list(mappingModelFixtures())) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".mapping.json"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] fixture(String fileName) {
    try {
      return Files.readAllBytes(mappingModelFixtures().resolve(fileName));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  /**
   * Asserts that {@code actual} is exactly the UTF-8 encoding of {@code expected}: the text first,
   * for a readable diff, then every byte, so a BOM or an encoding slip cannot hide.
   */
  static void assertBytes(String expected, byte[] actual) {
    assertThat(strictUtf8(actual)).isEqualTo(expected);
    assertThat(actual).isEqualTo(expected.getBytes(UTF_8));
  }

  static void assertBytes(byte[] expected, byte[] actual) {
    assertBytes(strictUtf8(expected), actual);
  }

  static String strictUtf8(byte[] bytes) {
    try {
      return UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new AssertionError("not well-formed UTF-8", e);
    }
  }
}
