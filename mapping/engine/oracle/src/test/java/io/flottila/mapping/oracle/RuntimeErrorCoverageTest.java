package io.flottila.mapping.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.oracle.CaseFiles.CorpusCase;
import io.flottila.mapping.runtime.RuntimeErrors;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-9 (ADR-0018 since #52): from spec 2 on, at least one corpus case raises each runtime error a
 * run raises from its data, the codes of {@code RuntimeErrors} in {@code engine/runtime}. {@code
 * flottila:TIMEOUT} is a test run's limit, not a data error; the facade's tests cover it. Not a
 * corpus class: its tests are no case ids (scripts/ratchet.java).
 */
class RuntimeErrorCoverageTest {

  /** Every {@code public static final String} constant of {@code RuntimeErrors}. */
  static Set<String> runtimeErrors() throws IllegalAccessException {
    Set<String> codes = new TreeSet<>();
    for (Field field : RuntimeErrors.class.getFields()) {
      int modifiers = field.getModifiers();
      if (Modifier.isStatic(modifiers)
          && Modifier.isFinal(modifiers)
          && field.getType() == String.class) {
        codes.add((String) field.get(null));
      }
    }
    return codes;
  }

  /** The codes of {@code codes} that no {@code expect: error} case of {@code cases} expects. */
  static List<String> uncovered(List<CorpusCase> cases, Set<String> codes) {
    Set<String> expected = new TreeSet<>();
    cases.stream()
        .filter(c -> c.expect().equals("error"))
        .forEach(c -> expected.add((String) c.expectedError().get("code")));
    return codes.stream().filter(code -> !expected.contains(code)).sorted().toList();
  }

  @Test
  void everyRuntimeErrorCodeHasACorpusCase() throws Exception {
    Set<String> codes = runtimeErrors();

    // ADR-0006 and ADR-0025 since #52: po:MINOCCURS and one code per context function's data error
    assertThat(codes)
        .contains(
            "po:MINOCCURS",
            "po:USEONEASMANY",
            "po:FORMATBYEXAMPLE",
            "po:SORT",
            "po:SORTBYKEY",
            // #83: fixValues with missing = error, fn/fixValues/004
            "po:FIXVALUES",
            // #133 (F-37): createIf over a value that is no boolean, fn/createIf/005
            "po:CREATEIF",
            "err:FORG0001",
            "err:FOAR0001")
        .doesNotContain("flottila:TIMEOUT");
    assertThat(uncovered(CaseFiles.all(), codes)).isEmpty();
  }

  @Test
  void theCheckFlagsACodeThatNoCaseRaises(@TempDir Path cases) throws Exception {
    List<CorpusCase> synthetic =
        List.of(
            CaseFiles.read(
                cases,
                ExpectedErrorCodesTest.errorCase(
                    cases.resolve("synthetic/min/001"), "po:MINOCCURS")),
            CaseFiles.read(
                cases, ExpectedErrorCodesTest.outputCase(cases.resolve("synthetic/output/001"))));

    assertThat(uncovered(synthetic, new TreeSet<>(Set.of("po:MINOCCURS", "po:SORT"))))
        .containsExactly("po:SORT");
  }
}
