package io.github.subapavel.jqj.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import org.junit.jupiter.api.Test;

/**
 * ADR-0031: a Source holds UTF-8 byte offsets into the filter text. No oracle sees a Source, so the offsets are read
 * from the field after a string literal holding é, two bytes and one char: in bytes the field starts one later than in
 * chars.
 */
class SourceBytesTest {

  @Test
  void sourcesAreUtf8ByteOffsets() {
    // "é" | .a: the quote, é (2 bytes), the quote, " | " (3 bytes), then .a at bytes [7, 9) (chars [6, 8)).
    Pipe piped = (Pipe) Resolver.resolve(Parser.parse("\"é\" | .a"));
    assertThat(piped.right().source()).as("source of .a in \"é\" | .a").isEqualTo(new Source(7, 9));
    assertThat(piped.left().source()).as("source of \"é\" in \"é\" | .a").isEqualTo(new Source(0, 4));

    // "é".a: a field after a string term (jq-ref 1.8.2, recorded: literal/access-string-term-field) at bytes [4, 6).
    Pipe term = (Pipe) Resolver.resolve(Parser.parse("\"é\".a"));
    assertThat(term.right().source()).as("source of .a in \"é\".a").isEqualTo(new Source(4, 6));
  }
}
