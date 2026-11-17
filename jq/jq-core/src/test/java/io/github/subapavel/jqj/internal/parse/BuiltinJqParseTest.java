package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * jq 1.8.2's {@code builtin.jq} parses with no error (#62 acceptance; spec 2a "Parser": the parser accepts the whole of
 * {@code builtin.jq}, update operators and {@code $$$$v} included). It holds definitions only, so this is the parse
 * alone: resolving them is #70's and #71's, and the "Top-level program not given" error belongs to the resolution, not
 * to {@code Parser.parse} (ADR-0032). The resource is a byte-for-byte copy of the jq 1.8.2 release tarball's
 * {@code src/builtin.jq} (NOTICE), whose SHA-256 is in {@code jq/SOURCES.sha256}; since #71 it is the prelude's main
 * resource (ADR-0042).
 */
class BuiltinJqParseTest {
  /** The SHA-256 of jq 1.8.2's src/builtin.jq, from jq-1.8.2.tar.gz (jq/SOURCES.sha256). */
  private static final String SHA256 = "b8a5fd9579be9b51c9a04e6620f8c1655539aa57eea33a84e202a8dea401f2a4";

  /** ADR-0042: the prelude's main resource, moved there from the test resource jq/builtin.jq at #71. */
  private static final String RESOURCE = "/io/github/subapavel/jqj/internal/parse/builtin.jq";

  @Test
  void jqsBuiltinJqParses() throws IOException, NoSuchAlgorithmException {
    byte[] bytes;
    try (InputStream in = BuiltinJqParseTest.class.getResourceAsStream(RESOURCE)) {
      assertThat(in).as("the resource %s", RESOURCE).isNotNull();
      bytes = in.readAllBytes();
    }
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .as("SHA-256 of %s (jq's bytes, unchanged)", RESOURCE)
        .isEqualTo(SHA256);
    String text = new String(bytes, UTF_8);
    assertThatCode(() -> Parser.parse(text)).as("parse of jq 1.8.2's builtin.jq").doesNotThrowAnyException();
  }
}
