package io.flottila.mapping.oracle;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.api.Trace;
import io.flottila.mapping.ir.AtomicType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Witness of the corpus trace check's result entry (#83, F-21): a field whose graph has no
 * source-field box may have an atomic entry, a generating function's value, or a queue, as {@code
 * count} of a constant gives; a field read from a source field may not have an atomic entry. Not a
 * corpus or differential class, so the ratchet does not read its test as a case id
 * (scripts/ratchet.java).
 */
class GeneratingTraceCheckTest {

  private static final Trace.Entry ATOMIC = new Trace.Atomic(AtomicType.STRING, "k");
  private static final Trace.Entry QUEUE = new Trace.Queue(List.of(List.of("k")), false, 1, 1);

  private static Map<String, Object> link(String resultKind) {
    return Map.of(
        "id", "T1",
        "result", "B1",
        "boxes", Map.of("B1", Map.of("kind", resultKind)));
  }

  @Test
  void onlyAFieldWithoutASourceFieldMayHaveAnAtomicEntry() {
    Map<String, Object> noSourceField = link("function");
    Map<String, Object> read = link("source");

    assertThatCode(() -> GoldenCorpusTest.checkResultEntry(noSourceField, ATOMIC, "no source"))
        .doesNotThrowAnyException();
    assertThatCode(() -> GoldenCorpusTest.checkResultEntry(noSourceField, QUEUE, "no source"))
        .doesNotThrowAnyException();
    assertThatCode(() -> GoldenCorpusTest.checkResultEntry(read, QUEUE, "read"))
        .doesNotThrowAnyException();
    // the witness: a field read from a source field with an atomic entry fails
    assertThatThrownBy(() -> GoldenCorpusTest.checkResultEntry(read, ATOMIC, "read"))
        .isInstanceOf(AssertionError.class);
    // no entry at all fails either way
    assertThatThrownBy(() -> GoldenCorpusTest.checkResultEntry(noSourceField, null, "no source"))
        .isInstanceOf(AssertionError.class);
  }
}
