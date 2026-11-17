package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The deviation slugs a {@code DEVIATIONS.md} row names in its {@code case} cell (ADR-0020). */
class DeviationsTest {

  @Test
  void aRowNamesTheSlugOfItsCaseInItsCaseCell(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("DEVIATIONS.md");
    Files.writeString(
        file,
        String.join(
            "\n",
            "# Deviations",
            "",
            "Prose that quotes `deviation prose` is no row.",
            "",
            "| case | filter | input | jq 1.8.2 | jqj |",
            "| --- | --- | --- | --- | --- |",
            "| `json/a` (`deviation one`) | `-c .` | `1` | `1` | error |",
            "| none yet | `-c .` | `[1]` | `1` | error |",
            "| `t/b` (`deviation two`), `t/c` (`deviation three`) | `-c .` | `2` | `2` | error |",
            "| `a \\| b` (`deviation piped`) | `.` | `3` | `3` | error |",
            "| `t/d` | `.` | `4` | `4` | `deviation jqj-cell` |",
            ""),
        UTF_8);

    assertThat(Deviations.slugs(file)).containsExactlyInAnyOrder("one", "two", "three", "piped");
  }

  @Test
  void aMissingFileIsAnError(@TempDir Path dir) {
    assertThatThrownBy(() -> Deviations.slugs(dir.resolve("DEVIATIONS.md"))).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void theRepositorysNulRowNamesTheJsonOraclesNulCase() {
    assertThat(Corpus.deviations()).contains("nul");
  }
}
