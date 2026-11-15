package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.mapping.Lowering;
import io.flottila.mapping.oracle.CaseFiles.CorpusCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every standard function the engine-internal catalogue lists has a golden-corpus case that uses it
 * (ADR-0025, rule #19): a case under {@code cases/fn/<name>/} whose mapping holds a function box of
 * that name. The catalogue is the one {@code function-catalogue.json} that {@code engine/mapping}
 * packs (ADR-0025), read with the corpus's strict reader. Not a corpus class: its test is no case
 * id (scripts/ratchet.java).
 */
class CatalogueCoverageTest {

  private static final String FILE = "function-catalogue.json";

  /** The text of the one {@value #FILE} in engine/mapping's jar or classes directory. */
  static String catalogue() throws Exception {
    Path artifact =
        Path.of(Lowering.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (Files.isDirectory(artifact)) {
      return find(artifact, artifact);
    }
    try (FileSystem jar = FileSystems.newFileSystem(artifact)) {
      return find(jar.getPath("/"), artifact);
    }
  }

  private static String find(Path root, Path artifact) throws IOException {
    List<Path> found;
    try (Stream<Path> files = Files.walk(root)) {
      found =
          files
              .filter(p -> p.getFileName() != null && p.getFileName().toString().equals(FILE))
              .toList();
    }
    assertThat(found).as("%s in %s", FILE, artifact).hasSize(1);
    return Files.readString(found.getFirst(), UTF_8);
  }

  /** The function names: the catalogue is keyed by them (ADR-0011). */
  static Set<String> functionNames(String catalogue) {
    if (!(new CaseFiles.Json(catalogue).document() instanceof Map<?, ?> functions)) {
      throw new AssertionError("the catalogue is no JSON object");
    }
    Set<String> names = new TreeSet<>();
    for (Object name : functions.keySet()) {
      names.add((String) name);
    }
    return names;
  }

  /** Whether the mapping of {@code c} holds a function box that calls {@code name}. */
  static boolean usesFunction(CorpusCase c, String name) {
    Object model;
    try {
      model =
          new CaseFiles.Json(Files.readString(CaseFiles.corpus().resolve(c.mapping()), UTF_8))
              .document();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!(model instanceof Map<?, ?> root)
        || !(root.get("targetFieldMappings") instanceof Map<?, ?> mappings)) {
      return false;
    }
    for (Object mapping : mappings.values()) {
      if (mapping instanceof Map<?, ?> m && m.get("boxes") instanceof Map<?, ?> boxes) {
        for (Object box : boxes.values()) {
          if (box instanceof Map<?, ?> b
              && "function".equals(b.get("kind"))
              && name.equals(b.get("fn"))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Test
  void everyCatalogueFunctionHasACorpusCase() throws Exception {
    Set<String> names = functionNames(catalogue());
    List<CorpusCase> cases = CaseFiles.all();
    // witness: the catalogue is not empty, so the check below has a function to look for
    assertThat(names).as("the catalogue's functions").isNotEmpty();

    Set<String> uncovered = new TreeSet<>();
    for (String name : names) {
      boolean covered =
          cases.stream()
              .filter(c -> c.id().startsWith("fn/" + name + "/"))
              .anyMatch(c -> usesFunction(c, name));
      if (!covered) {
        uncovered.add(name);
      }
    }

    assertThat(uncovered).as("functions without a case under cases/fn/<name>/").isEmpty();
  }
}
