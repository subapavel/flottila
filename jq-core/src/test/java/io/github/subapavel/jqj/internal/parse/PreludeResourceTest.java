package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * ADR-0042 "Source": jq 1.8.2's builtin.jq ships verbatim as a main resource beside the parser, and NOTICE names it
 * there. The expected SHA-256 is the one {@code jq/SOURCES.sha256} records for jq-1.8.2.tar.gz's src/builtin.jq, which
 * the test-author also observed on https://raw.githubusercontent.com/jqlang/jq/jq-1.8.2/src/builtin.jq (2026-11-13).
 * Red until the file moves.
 */
class PreludeResourceTest {
  /** ADR-0042: the main resource's class-path name. */
  private static final String RESOURCE = "/io/github/subapavel/jqj/internal/parse/builtin.jq";

  /** jq/SOURCES.sha256's builtin.jq line. */
  private static final String SHA256 = "b8a5fd9579be9b51c9a04e6620f8c1655539aa57eea33a84e202a8dea401f2a4";

  @Test
  void theMainResourceIsJqsBuiltinJqByteForByte() throws IOException, NoSuchAlgorithmException {
    byte[] bytes;
    try (InputStream in = PreludeResourceTest.class.getResourceAsStream(RESOURCE)) {
      assertThat(in).as("the main resource %s", RESOURCE).isNotNull();
      bytes = in.readAllBytes();
    }
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .as("SHA-256 of %s (jq's bytes, no header added)", RESOURCE)
        .isEqualTo(SHA256);
  }

  /** The same hash is the one SOURCES.sha256 records, so the pinned literal and the proof file agree. */
  @Test
  void sourcesSha256RecordsThatHash() throws IOException {
    String sources;
    try (InputStream in = PreludeResourceTest.class.getResourceAsStream("/jq/SOURCES.sha256")) {
      assertThat(in).as("the resource jq/SOURCES.sha256").isNotNull();
      sources = new String(in.readAllBytes(), UTF_8);
    }
    assertThat(sources.lines()).as("lines of jq/SOURCES.sha256").contains(SHA256 + "  builtin.jq");
  }

  /** CLAUDE.md and ADR-0042: NOTICE lists the copied file by its main path. */
  @Test
  void noticeNamesTheMainPath() throws IOException {
    Path notice = Path.of(System.getProperty("user.dir")).resolve("../NOTICE").normalize();
    assertThat(Files.readString(notice, UTF_8))
        .as("the repository's NOTICE (%s)", notice)
        .contains("jq-core/src/main/resources/io/github/subapavel/jqj/internal/parse/builtin.jq");
  }
}
