package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The trace of a {@code TargetRepeat} (#83; ADR-0006 as amended by #83, ADR-0015, GLOSSARY "Trace",
 * "Trace limit"): a generating function's field has an occurrences entry with its field's min and
 * max, capped at the trace limit as any field's; an unmapped {@code n..n} field, whose repeat has
 * its field path as origin, has none. Targets from the observation kit: {@code tgt-gen.xsd} (Gen
 * 2..3 under a repeating Grp) and {@code tgt-parents.xsd} (the unmapped Head 1..1 and Pair 2..2).
 */
class EngineFacadeRepeatTraceTest {

  private static final String FINGERPRINT = "sha256:" + "0".repeat(64);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int LIMIT = 10_000;
  private static final long UNBOUNDED = Long.MAX_VALUE;

  private final EngineFacade facade = new EngineFacade();

  private static Path observations() {
    return CorpusStructures.directory("observations");
  }

  private static byte[] model(String target, String root, String targetFieldMappings) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%1$s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["%2$s"], "fingerprint": "%1$s", "root": {"element": "%3$s"}},
         "targetFieldMappings": {%4$s}}
        """
        .formatted(FINGERPRINT, target, root, targetFieldMappings)
        .getBytes(UTF_8);
  }

  private Trace trace(byte[] model, String input, int limit) throws Exception {
    CompileResult result =
        facade.compile(
            Fingerprints.refreshed(model, observations()),
            DirectoryResolver.over(observations()),
            CompileOptions.TEST_RUN);
    assertThat(result).isInstanceOf(CompileResult.Compiled.class);
    TestRunResult run =
        facade.testRun(
            ((CompileResult.Compiled) result).mapping(), input.getBytes(UTF_8), TIMEOUT, limit);
    assertThat(run).isInstanceOf(TestRunResult.Output.class);
    return ((TestRunResult.Output) run).trace();
  }

  private static Trace.Slot created(long slot) {
    return new Trace.Slot(slot, Trace.SlotStatus.CREATED);
  }

  /** Grp per Order by S0 (T0), and Gen 2..3 from the constant C1 (T1). */
  private static final byte[] GENS =
      model(
          "tgt-gen.xsd",
          "Gens",
          """
          "/Gens/Grp": {"id": "T0", "result": "S0", "boxes": {
              "S0": {"kind": "source", "field": "/Orders/Order", "context": "/Orders"}}},
          "/Gens/Grp/Gen": {"id": "T1", "result": "C1", "boxes": {
              "C1": {"kind": "function", "fn": "constant", "params": {"value": "g"}}}}""");

  private static final String TWO_ORDERS = "<Orders><Order/><Order/></Orders>";

  /**
   * Under a trace limit of 1, Gen's two parent records are above the limit: it keeps the last, Grp
   * 2's, with one of its two slot records, slot 3, and is capped; its min and max are Gen's 2..3.
   * Witness: under the default limit it keeps both, slots 1, 2 and 3, 4, not capped.
   */
  @Test
  void theLimitCapsAGeneratingFieldsCopies() throws Exception {
    assertThat(trace(GENS, TWO_ORDERS, 1).entries().get(new AuthoringId("T1")))
        .isEqualTo(
            new Trace.Occurrences(
                2, 3, List.of(new Trace.ParentSlot(2, 2, List.of(created(3)))), true));
    assertThat(trace(GENS, TWO_ORDERS, LIMIT).entries().get(new AuthoringId("T1")))
        .isEqualTo(
            new Trace.Occurrences(
                2,
                3,
                List.of(
                    new Trace.ParentSlot(1, 2, List.of(created(1), created(2))),
                    new Trace.ParentSlot(2, 2, List.of(created(3), created(4)))),
                false));
  }

  /**
   * Id under the unmapped Head 1..1, beside the unmapped Pair 2..2: both are created by a
   * TargetRepeat whose origin is their field path, which names no target field mapping, so the
   * trace holds the source box and Id's mapping only. Id's entry is its one parent record.
   */
  @Test
  void anUnmappedFieldsRepeatHasNoEntry() throws Exception {
    byte[] model =
        model(
            "tgt-parents.xsd",
            "Parents",
            """
            "/Parents/Head/Id": {"id": "t1", "result": "a1", "boxes": {
                "a1": {"kind": "source", "field": "/Orders/Order/Id", "context": "/Orders"}}}""");

    Trace trace = trace(model, "<Orders><Order><Id>o1</Id></Order></Orders>", LIMIT);

    assertThat(trace.entries().keySet())
        .extracting(AuthoringId::value)
        .containsExactlyInAnyOrder("a1", "t1");
    assertThat(trace.entries().get(new AuthoringId("t1")))
        .isEqualTo(
            new Trace.Occurrences(
                0, UNBOUNDED, List.of(new Trace.ParentSlot(1, 1, List.of(created(1)))), false));
  }
}
