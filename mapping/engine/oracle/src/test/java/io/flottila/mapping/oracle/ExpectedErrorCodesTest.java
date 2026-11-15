package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.mapping.MappingDiagnostics;
import io.flottila.mapping.oracle.CaseFiles.CorpusCase;
import io.flottila.mapping.runtime.RuntimeErrors;
import io.flottila.mapping.structure.importer.StructureImportDiagnostics;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The corpus half of the diagnostic-catalogue check (F-9, ADR-0018): no case expects a code that
 * does not exist. {@code engine/api} checks the catalogue itself. The catalogue classes are listed
 * here; a case that expects a code of another module's catalogue fails until it is added, so the
 * check fails closed. Not a corpus class: its tests are no case ids (scripts/ratchet.java).
 */
class ExpectedErrorCodesTest {

  private static final List<Class<?>> CATALOGUE =
      List.of(MappingDiagnostics.class, StructureImportDiagnostics.class, RuntimeErrors.class);

  /** Every {@code public static final String} constant of the catalogue classes. */
  static Set<String> catalogue() throws IllegalAccessException {
    Set<String> codes = new TreeSet<>();
    for (Class<?> diagnostics : CATALOGUE) {
      for (Field field : diagnostics.getFields()) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)
            && Modifier.isFinal(modifiers)
            && field.getType() == String.class) {
          codes.add((String) field.get(null));
        }
      }
    }
    return codes;
  }

  /** The ids of the {@code expect: error} cases whose expected code is not in {@code catalogue}. */
  static List<String> unknownCodes(List<CorpusCase> cases, Set<String> catalogue) {
    return cases.stream()
        .filter(c -> c.expect().equals("error"))
        .filter(c -> !catalogue.contains(c.expectedError().get("code")))
        .map(CorpusCase::id)
        .toList();
  }

  @Test
  void noCaseExpectsAnUnknownCode() throws Exception {
    Set<String> catalogue = catalogue();
    List<CorpusCase> cases = CaseFiles.all();

    assertThat(catalogue)
        .contains("unsupported-box-kind", "schema-not-well-formed", "po:MINOCCURS");
    assertThat(cases).as("cases under %s", CaseFiles.cases()).isNotEmpty();
    assertThat(unknownCodes(cases, catalogue)).isEmpty();
  }

  @Test
  void theCheckFlagsACaseThatExpectsAnUnknownCode(@TempDir Path cases) throws Exception {
    Path known = errorCase(cases.resolve("synthetic/known/001"), "unsupported-box-kind");
    Path unknown = errorCase(cases.resolve("synthetic/unknown/001"), "no-such-code");
    Path output = outputCase(cases.resolve("synthetic/output/001"));

    List<CorpusCase> synthetic =
        List.of(
            CaseFiles.read(cases, known),
            CaseFiles.read(cases, unknown),
            CaseFiles.read(cases, output));

    assertThat(unknownCodes(synthetic, catalogue())).containsExactly("synthetic/unknown/001");
  }

  static Path errorCase(Path dir, String code) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("case.json"),
        """
        {"mapping": "mappings/x.mapping.json", "provenance": "decided", "ref": "ADR-0018",
         "expect": "error", "note": "synthetic"}
        """,
        UTF_8);
    Files.writeString(
        dir.resolve("expected-error.json"),
        "{\"code\": \"" + code + "\", \"authoringId\": \"f1\"}",
        UTF_8);
    return dir;
  }

  static Path outputCase(Path dir) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("case.json"),
        """
        {"mapping": "mappings/x.mapping.json", "provenance": "decided", "ref": "ADR-0018",
         "expect": "output", "note": "synthetic, no expected-error.json"}
        """,
        UTF_8);
    return dir;
  }
}
