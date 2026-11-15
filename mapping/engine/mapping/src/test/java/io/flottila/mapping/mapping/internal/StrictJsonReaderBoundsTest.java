package io.flottila.mapping.mapping.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.erosb.jsonsKema.SourceLocation;
import io.flottila.mapping.mapping.MalformedJsonException;
import org.junit.jupiter.api.Test;

/**
 * The reader's two bounds that a mapping model alone does not reach: the depth limit at exactly
 * {@link StrictJsonReader#MAX_DEPTH} levels, and a value's column on a line after the first.
 */
class StrictJsonReaderBoundsTest {

  private static byte[] nested(int levels) {
    return ("[".repeat(levels) + "]".repeat(levels)).getBytes(UTF_8);
  }

  @Test
  void aDocumentNestedExactlyToTheDepthLimitReadsAndOneLevelMoreIsMalformed() throws Exception {
    assertThat(StrictJsonReader.read(nested(StrictJsonReader.MAX_DEPTH))).isNotNull();

    assertThatThrownBy(() -> StrictJsonReader.read(nested(StrictJsonReader.MAX_DEPTH + 1)))
        .isInstanceOf(MalformedJsonException.class);
  }

  /** {@code 7} stands on line 2 at column 8, counted from the start of its own line, from 1. */
  @Test
  void aValuesColumnCountsFromTheStartOfItsLine() throws Exception {
    byte[] json = "{\n  \"a\": 7}".getBytes(UTF_8);

    SourceLocation location = StrictJsonReader.read(json).requireObject().get("a").getLocation();

    assertThat(location.getLineNumber()).isEqualTo(2);
    assertThat(location.getPosition()).isEqualTo(8);
  }
}
