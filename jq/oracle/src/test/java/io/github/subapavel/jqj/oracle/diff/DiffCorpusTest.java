package io.github.subapavel.jqj.oracle.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The differential corpus walks its directory recursively; a family is the path below it (ADR-0025). */
class DiffCorpusTest {

  @Test
  void theWalkIsRecursiveAndAFamilyIsThePathBelowTheDirectory(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve("gen"));
    Files.writeString(dir.resolve("b.cases"), "case b/x\nfilter .\nend\n");
    Files.writeString(dir.resolve("gen/v.cases"), "# dropped 1 rc3\ncase gen/v/2\nfilter .\nend\n\ncase gen/v/0\nfilter .\nend\n");
    Files.writeString(dir.resolve("gen/v.rec"), "# jq-1.8.2 sha256 00\n\ncase gen/v/0\ndef d\nrc 0\nstdout\nstderr\nend\n");
    Files.writeString(dir.resolve("gen/notes.txt"), "not a case file\n");

    var entries = DiffCorpus.load(dir);

    assertThat(entries).extracting(e -> e.c().id()).containsExactly("b/x", "gen/v/0", "gen/v/2");
    assertThat(entries.get(1).recording().def()).isEqualTo("d");
    assertThat(entries.get(0).recording()).isNull();
    assertThat(entries.get(2).recording()).isNull();
  }

  @Test
  void aCaseOutsideItsPathFamilyIsRefused(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve("gen"));
    Files.writeString(dir.resolve("gen/v.cases"), "case v/1\nfilter .\nend\n");

    assertThatThrownBy(() -> DiffCorpus.load(dir)).hasMessageContaining("is not gen/v/<name>");
  }

  @Test
  void aMissingDirectoryIsAnIoError(@TempDir Path dir) {
    assertThatThrownBy(() -> DiffCorpus.load(dir.resolve("absent"))).isInstanceOf(UncheckedIOException.class);
  }
}
