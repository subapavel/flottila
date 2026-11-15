package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.oracle.CaseFiles.CorpusCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0026: a function is {@code assumed} while one of its corpus cases, a case under {@code
 * fn/<function>/}, has {@code provenance: assumed}, and only then. The catalogue is the one the
 * engine facade publishes, read with the corpus's strict reader. Not a corpus or differential
 * class, so the ratchet does not read its tests as case ids (scripts/ratchet.java); that is why its
 * name holds neither word.
 */
class FidelityAgreesWithTheCasesTest {

  /**
   * Each function of {@code catalogue} that has an assumed case but is not assumed, or is assumed
   * but has no assumed case, and why.
   */
  static List<String> disagreements(String catalogue, List<CorpusCase> cases) {
    if (!(new CaseFiles.Json(catalogue).document() instanceof Map<?, ?> functions)) {
      throw new AssertionError("the catalogue is no JSON object");
    }
    List<String> wrong = new ArrayList<>();
    functions.forEach(
        (name, entry) -> {
          List<String> assumed =
              cases.stream()
                  .filter(c -> c.id().startsWith("fn/" + name + "/"))
                  .filter(c -> c.provenance().equals("assumed"))
                  .map(CorpusCase::id)
                  .toList();
          Object level =
              entry instanceof Map<?, ?> members && members.get("fidelity") instanceof Map<?, ?> f
                  ? f.get("level")
                  : null;
          if (!assumed.isEmpty() && !"assumed".equals(level)) {
            wrong.add(name + " is " + level + ", but " + assumed.getFirst() + " is assumed");
          } else if (assumed.isEmpty() && "assumed".equals(level)) {
            wrong.add(name + " is assumed, but none of its cases is");
          }
        });
    return wrong;
  }

  /** The functions {@code catalogue} marks {@code assumed}. */
  private static List<String> assumedIn(String catalogue) {
    if (!(new CaseFiles.Json(catalogue).document() instanceof Map<?, ?> functions)) {
      throw new AssertionError("the catalogue is no JSON object");
    }
    List<String> assumed = new ArrayList<>();
    functions.forEach(
        (name, entry) -> {
          if (entry instanceof Map<?, ?> members
              && members.get("fidelity") instanceof Map<?, ?> f
              && "assumed".equals(f.get("level"))) {
            assumed.add((String) name);
          }
        });
    return assumed;
  }

  /** {@code case.json} of a case with {@code provenance} under {@code cases/<id>}. */
  private static CorpusCase write(Path cases, String id, String provenance) throws IOException {
    Path dir = Files.createDirectories(cases.resolve(id));
    Files.writeString(
        dir.resolve("case.json"),
        """
        {"mapping": "mappings/none.mapping.json", "provenance": "%s", "ref": "ADR-0026",
         "expect": "none", "note": "synthetic"}
        """
            .formatted(provenance),
        UTF_8);
    return CaseFiles.read(cases, dir);
  }

  @Test
  void aFunctionIsAssumedExactlyWhileOneOfItsCasesIs(@TempDir Path cases) throws Exception {
    // witness over a synthetic cases dir: f has an assumed case, g none, and gh's assumed case is
    // no case of g; k has no case at all
    List<CorpusCase> synthetic =
        List.of(
            write(cases, "fn/f/001", "observed"),
            write(cases, "fn/f/002", "assumed"),
            write(cases, "fn/g/001", "decided"),
            write(cases, "fn/gh/001", "assumed"));
    String exact = "{\"level\": \"exact\"}";
    String assumed = "{\"level\": \"assumed\"}";
    String catalogue =
        """
        {"f": {"fidelity": %s}, "g": {"fidelity": %s}, "gh": {"fidelity": %s},
         "k": {"fidelity": {"level": "deviation", "text": "t"}}}
        """;
    assertThat(disagreements(catalogue.formatted(exact, exact, assumed), synthetic))
        .containsExactly("f is exact, but fn/f/002 is assumed");
    assertThat(disagreements(catalogue.formatted(assumed, exact, assumed), synthetic)).isEmpty();
    assertThat(disagreements("{\"f\": {}, \"gh\": {\"fidelity\": " + exact + "}}", synthetic))
        .containsExactly(
            "f is null, but fn/f/002 is assumed", "gh is exact, but fn/gh/001 is assumed");
    // the converse: g, whose one case is decided (gh's assumed case is none of g's), and k, which
    // has no case at all, are not assumed
    assertThat(
            disagreements(
                "{\"g\": {\"fidelity\": " + assumed + "}, \"k\": {\"fidelity\": " + assumed + "}}",
                synthetic))
        .containsExactly(
            "g is assumed, but none of its cases is", "k is assumed, but none of its cases is");

    String published = new String(new EngineFacade().catalogue(), UTF_8);
    // witness on the published catalogue, over test-only cases rather than the live corpus, which
    // holds no assumed case of a shipped function since #216 and #219: an assumed case of the
    // exact useOneAsMany (F-48) is refused, and one of each function the catalogue marks assumed,
    // if any, is accepted
    List<CorpusCase> fixture = new ArrayList<>();
    fixture.add(write(cases, "fn/useOneAsMany/900", "assumed"));
    for (String name : assumedIn(published)) {
      fixture.add(write(cases, "fn/" + name + "/900", "assumed"));
    }
    assertThat(disagreements(published, fixture))
        .containsExactly("useOneAsMany is exact, but fn/useOneAsMany/900 is assumed");

    List<CorpusCase> corpus = CaseFiles.all();
    assertThat(disagreements(published, corpus))
        .as("functions with an assumed case that are not assumed")
        .isEmpty();
  }
}
