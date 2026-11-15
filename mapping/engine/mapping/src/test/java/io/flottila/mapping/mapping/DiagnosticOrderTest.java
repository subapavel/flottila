package io.flottila.mapping.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code Diagnostic.ORDER} sorts by location, then code, for every location kind: a JSON pointer
 * and an authoring id by their text, a file position by file, then line and column as numbers.
 * Between kinds, pointers come before authoring ids, and those before file positions, as {@code
 * Diagnostic.ORDER} states (#26 review). It is a total order, so every shuffle of the same
 * diagnostics sorts the same.
 */
class DiagnosticOrderTest {

  private static Diagnostic at(Location location, String code) {
    return new Diagnostic(Severity.ERROR, code, location, "message");
  }

  private static AtAuthoringId id(String value) {
    return new AtAuthoringId(new AuthoringId(value));
  }

  private static AtFilePosition file(String file, int line, int column) {
    return new AtFilePosition(file, line, column);
  }

  // each list in its expected order

  private static final List<Diagnostic> POINTERS =
      List.of(
          at(new AtPointer(""), "format-version-unsupported"),
          at(new AtPointer("/source/bundle"), "wrong-type"),
          at(new AtPointer("/target"), "missing-member"));

  private static final List<Diagnostic> IDS =
      List.of(
          at(id("a1"), "box-cycle"),
          at(id("a1"), "unknown-box-reference"),
          at(id("b1"), "box-cycle"));

  /** Line 9 before 10 and column 4 before 30: numbers, not text. */
  private static final List<Diagnostic> FILES =
      List.of(
          at(file("a.xsd", 12, 5), "schema-invalid"),
          at(file("b.xsd", 9, 4), "schema-construct-unsupported"),
          at(file("b.xsd", 9, 4), "schema-invalid"),
          at(file("b.xsd", 9, 30), "schema-invalid"),
          at(file("b.xsd", 10, 1), "schema-invalid"));

  private static final List<Diagnostic> ALL =
      Stream.of(POINTERS, IDS, FILES).flatMap(List::stream).toList();

  @Test
  void sortsAllThreeLocationKinds() {
    List<List<Diagnostic>> sortings = new ArrayList<>();
    for (long seed = 1; seed <= 20; seed++) {
      sortings.add(shuffledAndSorted(seed));
    }

    List<Diagnostic> sorted = sortings.getFirst();
    assertThat(sortings).allSatisfy(s -> assertThat(s).isEqualTo(sorted));
    assertThat(sorted.stream().filter(d -> d.location() instanceof AtPointer))
        .containsExactlyElementsOf(POINTERS);
    assertThat(sorted.stream().filter(d -> d.location() instanceof AtAuthoringId))
        .containsExactlyElementsOf(IDS);
    assertThat(sorted.stream().filter(d -> d.location() instanceof AtFilePosition))
        .containsExactlyElementsOf(FILES);
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 20);
  }

  /**
   * The kinds' texts interleave: {@code "/target"} sorts before {@code "a.xsd"} and {@code "a1"},
   * and {@code "a.xsd"} before {@code "a1"}, so an order by text alone would mix the kinds.
   */
  @ParameterizedTest(name = "seed {0}: pointers, then authoring ids, then file positions")
  @MethodSource("seeds")
  void pointersSortBeforeAuthoringIdsAndThoseBeforeFilePositions(long seed) {
    assertThat(shuffledAndSorted(seed)).containsExactlyElementsOf(ALL);
  }

  private static List<Diagnostic> shuffledAndSorted(long seed) {
    List<Diagnostic> shuffled = new ArrayList<>(ALL);
    Collections.shuffle(shuffled, new Random(seed));
    shuffled.sort(Diagnostic.ORDER);
    return shuffled;
  }
}
