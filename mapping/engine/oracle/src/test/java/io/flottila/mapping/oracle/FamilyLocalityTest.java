package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Function families (ADR-0025 "Families", F-15): a standard function's catalogue name, quoted as
 * {@code "name"}, and its {@code po:<name>} stand in the engine's main sources only in the files of
 * its own family, so that a ticket adding a family edits no shared file. A family's files are
 * {@code engine/mapping/src/main/catalogue/<family>.json}, {@code <Family>Family.java} in
 * engine/runtime, {@code engine/xslt-export/src/main/xsl/po-runtime/<family>.xsl} and, when the
 * family has queue algebra, {@code <Family>Functions.java} in engine/xdm. The names are those of
 * the packed catalogue and of the family files; a name's family is the stem of the family file that
 * holds it. The packed artifacts lie under {@code target/}, which is no main source.
 */
class FamilyLocalityTest {

  static final String CATALOGUE_DIR = "engine/mapping/src/main/catalogue";

  static final String HELPER_DIR = "engine/xslt-export/src/main/xsl/po-runtime";

  /** The repository root: the golden corpus lies at its top (the module's surefire setting). */
  static Path repoRoot() {
    return CaseFiles.corpus().toAbsolutePath().normalize().getParent();
  }

  /** Each name of a family file of {@code root}, with the families whose file holds it. */
  static Map<String, Set<String>> familiesByName(Path root) throws IOException {
    Map<String, Set<String>> families = new TreeMap<>();
    Path dir = root.resolve(CATALOGUE_DIR);
    if (!Files.isDirectory(dir)) {
      return families;
    }
    List<Path> files;
    try (Stream<Path> listing = Files.list(dir)) {
      files = listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
    }
    for (Path file : files) {
      String fileName = file.getFileName().toString();
      String family = fileName.substring(0, fileName.length() - ".json".length());
      if (!(new CaseFiles.Json(Files.readString(file, UTF_8)).document()
          instanceof Map<?, ?> entries)) {
        throw new AssertionError(file + " is no JSON object");
      }
      for (Object name : entries.keySet()) {
        families.computeIfAbsent((String) name, n -> new TreeSet<>()).add(family);
      }
    }
    return families;
  }

  /** Every file under {@code engine/<module>/src/main/}, by its path from {@code root}. */
  static Map<String, String> mainSources(Path root) throws IOException {
    Map<String, String> sources = new TreeMap<>();
    List<Path> modules;
    try (Stream<Path> listing = Files.list(root.resolve("engine"))) {
      modules = listing.filter(Files::isDirectory).sorted().toList();
    }
    for (Path module : modules) {
      Path main = module.resolve("src/main");
      if (!Files.isDirectory(main)) {
        continue;
      }
      List<Path> files;
      try (Stream<Path> walk = Files.walk(main)) {
        files = walk.filter(Files::isRegularFile).toList();
      }
      for (Path file : files) {
        String path = root.relativize(file).toString().replace('\\', '/');
        sources.put(path, new String(Files.readAllBytes(file), UTF_8));
      }
    }
    return sources;
  }

  /**
   * The files of {@code sources} that name {@code name}: {@code "name"}, or {@code po:name} not
   * followed by a name character, so that {@code po:sortByKey} and {@code po:sort-key} are no
   * mention of {@code sort}. The {@code name} attribute of an {@code xsl:param} or {@code
   * xsl:with-param} is a variable's name, no mention (core.xsl's {@code $max} and {@code $min}).
   */
  static List<String> mentions(Map<String, String> sources, String name) {
    Pattern mention =
        Pattern.compile(
            "(?<!<xsl:param name=)(?<!<xsl:with-param name=)\""
                + Pattern.quote(name)
                + "\"|po:"
                + Pattern.quote(name)
                + "(?![\\w.-])");
    return sources.entrySet().stream()
        .filter(source -> mention.matcher(source.getValue()).find())
        .map(Map.Entry::getKey)
        .toList();
  }

  /** Whether {@code path}, from the repository root, is one of the files of {@code family}. */
  static boolean ownFile(String path, String family) {
    String type = Character.toUpperCase(family.charAt(0)) + family.substring(1);
    String fileName = path.substring(path.lastIndexOf('/') + 1);
    return path.equals(CATALOGUE_DIR + "/" + family + ".json")
        || path.equals(HELPER_DIR + "/" + family + ".xsl")
        || (path.startsWith("engine/runtime/src/main/java/")
            && fileName.equals(type + "Family.java"))
        || (path.startsWith("engine/xdm/src/main/java/")
            && fileName.equals(type + "Functions.java"));
  }

  @Test
  void everyFunctionNameStandsOnlyInItsFamilysFiles() throws Exception {
    // witness: a mention is a quoted name or a po: name, never a longer name it begins
    Map<String, String> sample =
        Map.of(
            "a/Binding.java", "case \"sort\" ->",
            "b/keys.xsl", "po:sortByKey($k), po:sort-key($v)",
            "c/sort.xsl", "select=\"po:sort($q, 'numeric', 'ascending')\"",
            "d/Other.java", "sorted(\"sortByKey\")");
    assertThat(new TreeSet<>(mentions(sample, "sort")))
        .containsExactly("a/Binding.java", "c/sort.xsl");
    // witness: a param's name is no mention of the function of that name, a real foreign mention is
    Map<String, String> params =
        Map.of(
            "core.xsl", "<xsl:param name=\"max\" as=\"xs:integer?\"/>",
            "core2.xsl", "<xsl:with-param name=\"max\" select=\"1\"/>",
            "text.xsl", "select=\"po:max($a, $b)\"",
            "Text.java", "case \"max\" ->",
            "cat.json", "\"max\": {");
    assertThat(new TreeSet<>(mentions(params, "max")))
        .containsExactly("Text.java", "cat.json", "text.xsl");
    // witness: a family's files are its own four, and nothing else is
    assertThat(ownFile(CATALOGUE_DIR + "/context.json", "context")).isTrue();
    assertThat(ownFile(HELPER_DIR + "/context.xsl", "context")).isTrue();
    assertThat(
            ownFile(
                "engine/runtime/src/main/java/io/flottila/mapping/runtime/ContextFamily.java",
                "context"))
        .isTrue();
    assertThat(
            ownFile(
                "engine/xdm/src/main/java/io/flottila/mapping/xdm/ContextFunctions.java",
                "context"))
        .isTrue();
    assertThat(
            ownFile(
                "engine/runtime/src/main/java/io/flottila/mapping/runtime/QueueLibrary.java",
                "context"))
        .isFalse();
    assertThat(ownFile(HELPER_DIR + "/core.xsl", "context")).isFalse();
    assertThat(ownFile(CATALOGUE_DIR + "/context.json", "text")).isFalse();

    Path root = repoRoot();
    Map<String, Set<String>> families = familiesByName(root);
    Set<String> names =
        new TreeSet<>(CatalogueCoverageTest.functionNames(CatalogueCoverageTest.catalogue()));
    names.addAll(families.keySet());
    Map<String, String> sources = mainSources(root);
    // witness: the names and the sources were read
    assertThat(names).contains("removeContexts", "replaceValue");
    assertThat(sources.keySet())
        .anyMatch(p -> p.startsWith("engine/mapping/src/main/"))
        .anyMatch(p -> p.startsWith("engine/runtime/src/main/"))
        .anyMatch(p -> p.startsWith("engine/xslt-export/src/main/"))
        .anyMatch(p -> p.startsWith("engine/xdm/src/main/"));

    List<String> misplaced = new ArrayList<>();
    for (String name : names) {
      Set<String> own = families.getOrDefault(name, Set.of());
      if (own.size() > 1) {
        misplaced.add(name + " is in the catalogue files of the families " + own);
      }
      List<String> files = mentions(sources, name);
      if (files.isEmpty()) {
        misplaced.add(name + " stands in no main source, not even its family's catalogue file");
      }
      for (String file : files) {
        if (own.stream().noneMatch(family -> ownFile(file, family))) {
          misplaced.add(
              name
                  + " (family "
                  + (own.isEmpty() ? "none" : String.join(", ", own))
                  + ") stands in "
                  + file);
        }
      }
    }

    assertThat(misplaced).as("names outside their family's files").isEmpty();
  }
}
