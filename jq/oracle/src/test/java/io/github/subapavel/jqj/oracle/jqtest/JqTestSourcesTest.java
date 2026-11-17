package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * jq's files under {@code jq/tests/} are byte for byte jq 1.8.2's (ADR-0020, ADR-0021): every file but jqj's own
 * {@code SOURCES.sha256} and {@code NOTICE} has a line there with its SHA-256, and every line names a file.
 */
class JqTestSourcesTest {

  @Test
  void everyCopiedFileMatchesItsSources256Line() throws IOException, NoSuchAlgorithmException {
    Path dir = JqTestCorpus.dir();
    Map<String, String> listed = new TreeMap<>();
    for (String line : Files.readAllLines(dir.resolve("SOURCES.sha256"), UTF_8)) {
      if (line.isEmpty() || line.startsWith("#")) continue;
      listed.put(line.substring(66), line.substring(0, 64));
    }

    Map<String, String> present = new TreeMap<>();
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        String name = dir.relativize(f).toString().replace(f.getFileSystem().getSeparator(), "/");
        if (name.equals("SOURCES.sha256") || name.equals("NOTICE")) continue;
        present.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))));
      }
    }

    assertThat(present).hasSize(27).containsKeys("COPYING", "jq.test", "modules/home2/.jq/g.jq");
    assertThat(present).isEqualTo(listed);
  }
}
