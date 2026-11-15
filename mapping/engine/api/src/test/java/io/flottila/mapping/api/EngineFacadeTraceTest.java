package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.runtime.ExecutableMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The trace of a test run at {@code EngineFacade.testRun} (ADR-0015 "Trace" as refined by #53;
 * GLOSSARY "Trace", "Trace limit", "Parent slot"). The trace is keyed by authoring id in the order
 * of first report: each evaluated box has a queue entry, its contexts of values with SUPPRESS as
 * null; each target field mapping that ran has an occurrences entry, per parent slot the slots of
 * context p, each created, suppressed or truncated, and the count created. An entry above the trace
 * limit is capped and keeps its totals. A runtime error returns the trace up to the error, a
 * timeout the trace so far, and a compile without tracing an empty trace.
 *
 * <p>The structures are the observation kit's ({@code src-orders.xsd}: {@code Orders/Order/Id, Tag,
 * Line/Sku, Line/Qty}; {@code tgt-list.xsd}: {@code Out/Item/V}, {@code Out/All}; {@code
 * tgt-nested.xsd}: {@code Doc/Grp} 0..unbounded, {@code Row} 1..2, {@code Val} 0..1). Expected
 * entries are worked out by hand from ADR-0006 and ADR-0015, and the queues against today's tracer
 * before #53.
 */
class EngineFacadeTraceTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int LIMIT = 10_000;
  private static final long UNBOUNDED = Long.MAX_VALUE;
  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final String FINGERPRINT = "sha256:" + "0".repeat(64);
  private static final String MINOCCURS = "po:MINOCCURS";

  private final EngineFacade facade = new EngineFacade();

  // --- models

  private static Path observations() {
    return CorpusStructures.directory("observations");
  }

  /** {@code corpus/mappings}, whose models' bundle references are relative to it. */
  private static Path mappings() {
    return observations().resolve("../../mappings").normalize();
  }

  private static byte[] corpusMapping(String name) throws Exception {
    return Files.readAllBytes(mappings().resolve(name));
  }

  /** A model from {@code src-orders.xsd} to {@code target} under {@code root}, over the kit. */
  private static byte[] model(String target, String root, String targetFieldMappings) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%1$s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["%2$s"], "fingerprint": "%1$s", "root": {"element": "%3$s"}},
         "targetFieldMappings": %4$s}
        """
        .formatted(FINGERPRINT, target, root, targetFieldMappings)
        .getBytes(UTF_8);
  }

  private CompiledMapping compiled(byte[] model, Path base, CompileOptions options)
      throws Exception {
    CompileResult result =
        facade.compile(Fingerprints.refreshed(model, base), DirectoryResolver.over(base), options);
    if (result instanceof CompileResult.Compiled(CompiledMapping mapping)) {
      return mapping;
    }
    throw new AssertionError("expected Compiled, got " + result);
  }

  private CompiledMapping tested(byte[] model, Path base) throws Exception {
    return compiled(model, base, CompileOptions.TEST_RUN);
  }

  // --- inputs

  private static String orders(String... orders) {
    return "<Orders>" + String.join("", orders) + "</Orders>";
  }

  /** An Order with its Id, none when null, and its content. */
  private static String order(String id, String... content) {
    return "<Order>"
        + (id == null ? "" : "<Id>" + id + "</Id>")
        + String.join("", content)
        + "</Order>";
  }

  /** A Line with its Sku and Qty, each absent when null. */
  private static String line(String sku, String qty) {
    return "<Line>"
        + (sku == null ? "" : "<Sku>" + sku + "</Sku>")
        + (qty == null ? "" : "<Qty>" + qty + "</Qty>")
        + "</Line>";
  }

  private static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  // --- results and expected entries

  private static TestRunResult.Output output(TestRunResult result) {
    assertThat(result).isInstanceOf(TestRunResult.Output.class);
    return (TestRunResult.Output) result;
  }

  private static TestRunResult.RuntimeError error(
      TestRunResult result, String code, String authoringId) {
    assertThat(result).isInstanceOf(TestRunResult.RuntimeError.class);
    TestRunResult.RuntimeError error = (TestRunResult.RuntimeError) result;
    assertThat(error.code()).as("code").isEqualTo(code);
    assertThat(error.authoringId()).as("authoring id").contains(new AuthoringId(authoringId));
    return error;
  }

  /** A context of values, null for SUPPRESS. */
  private static List<String> context(String... values) {
    return Arrays.asList(values);
  }

  private static Trace.Queue queue(
      List<List<String>> contexts, boolean capped, int slotCount, int contextCount) {
    return new Trace.Queue(contexts, capped, slotCount, contextCount);
  }

  private static Trace.Slot created(long slot) {
    return new Trace.Slot(slot, Trace.SlotStatus.CREATED);
  }

  private static Trace.Slot suppressed(long slot) {
    return new Trace.Slot(slot, Trace.SlotStatus.SUPPRESSED);
  }

  private static Trace.Slot truncated(long slot) {
    return new Trace.Slot(slot, Trace.SlotStatus.TRUNCATED);
  }

  private static Trace.ParentSlot parent(long parentSlot, long created, Trace.Slot... slots) {
    return new Trace.ParentSlot(parentSlot, created, List.of(slots));
  }

  private static Trace.Occurrences occurrences(long min, long max, Trace.ParentSlot... parents) {
    return new Trace.Occurrences(min, max, List.of(parents), false);
  }

  /** The expected entries in order: authoring id, entry, authoring id, entry … */
  private static Map<AuthoringId, Trace.Entry> entries(Object... idsAndEntries) {
    Map<AuthoringId, Trace.Entry> entries = new LinkedHashMap<>();
    for (int i = 0; i < idsAndEntries.length; i += 2) {
      entries.put(new AuthoringId((String) idsAndEntries[i]), (Trace.Entry) idsAndEntries[i + 1]);
    }
    return entries;
  }

  private static List<String> keys(Trace trace) {
    return trace.entries().keySet().stream().map(AuthoringId::value).toList();
  }

  private static Trace.Occurrences occurrencesOf(Trace trace, String id) {
    Trace.Entry entry = trace.entries().get(new AuthoringId(id));
    assertThat(entry).as("entry of %s", id).isInstanceOf(Trace.Occurrences.class);
    return (Trace.Occurrences) entry;
  }

  private static Trace.Queue queueOf(Trace trace, String id) {
    Trace.Entry entry = trace.entries().get(new AuthoringId(id));
    assertThat(entry).as("entry of %s", id).isInstanceOf(Trace.Queue.class);
    return (Trace.Queue) entry;
  }

  // --- every evaluated box, by its authoring id (ADR-0015)

  /**
   * A node-function mapping: {@code All <- R1}, R1 removeContexts over M1, M1 mapWithDefault over
   * Qty in Line's context with the default {@code none}. Three Lines, the second without Qty: S1 is
   * [1] | [] | [3], M1 fills the empty context, R1 joins the contexts. Each box is traced by its
   * authoring id in the order of first report, the target field mapping T1 after its queue. The
   * default is a constant, shown from the model and never traced, and X1, which no result reaches,
   * is never evaluated: the trace holds no other entry.
   */
  @Test
  void aTestRunTracesEveryEvaluatedBoxByItsAuthoringId() throws Exception {
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            """
            {"/Out/All": {"id": "T1", "result": "R1", "boxes": {
              "R1": {"kind": "function", "fn": "removeContexts", "inputs": ["M1"]},
              "M1": {"kind": "function", "fn": "mapWithDefault", "inputs": ["S1"],
                     "params": {"default": "none"}},
              "S1": {"kind": "source", "field": "/Orders/Order/Line/Qty"},
              "X1": {"kind": "source", "field": "/Orders/Order/Id"}}}}
            """);
    String input =
        orders(order("o1", line("a", "1"), line("b", null)), order("o2", line("c", "3")));

    TestRunResult.Output result =
        output(facade.testRun(tested(model, observations()), utf8(input), TIMEOUT, LIMIT));

    assertThat(new String(result.output(), UTF_8))
        .isEqualTo(DECLARATION + "<Out><All>1</All><All>none</All><All>3</All></Out>");
    assertThat(keys(result.trace())).containsExactly("S1", "M1", "R1", "T1");
    assertThat(result.trace().entries())
        .containsExactlyEntriesOf(
            entries(
                "S1",
                queue(List.of(context("1"), context(), context("3")), false, 2, 3),
                "M1",
                queue(List.of(context("1"), context("none"), context("3")), false, 3, 3),
                "R1",
                queue(List.of(context("1", "none", "3")), false, 3, 1),
                "T1",
                occurrences(0, UNBOUNDED, parent(1, 3, created(1), created(2), created(3)))));
  }

  /**
   * Item per createIf over Sku in Order's context: the Skus 1, 0 | none | true give "", SUPPRESS |
   * (empty) | "". SUPPRESS is null and the empty context stays, both in the totals. Item reads
   * context 1 under the root: slot 1 is created, slot 2 suppressed.
   */
  @Test
  void aSuppressSlotIsNullAndAnEmptyContextStays() throws Exception {
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            """
            {"/Out/Item": {"id": "T1", "result": "C1", "boxes": {
              "C1": {"kind": "function", "fn": "createIf", "inputs": ["S1"]},
              "S1": {"kind": "source", "field": "/Orders/Order/Line/Sku",
                     "context": "/Orders/Order"}}}}
            """);
    String input =
        orders(
            order("o1", line("1", null), line("0", null)),
            order("o2"),
            order("o3", line("true", null)));

    TestRunResult.Output result =
        output(facade.testRun(tested(model, observations()), utf8(input), TIMEOUT, LIMIT));

    assertThat(new String(result.output(), UTF_8)).isEqualTo(DECLARATION + "<Out><Item/></Out>");
    assertThat(queueOf(result.trace(), "C1").contexts().getFirst()).containsExactly("", null);
    assertThat(result.trace().entries())
        .containsExactlyEntriesOf(
            entries(
                "S1",
                queue(List.of(context("1", "0"), context(), context("true")), false, 3, 3),
                "C1",
                queue(List.of(context("", null), context(), context("")), false, 3, 3),
                "T1",
                occurrences(0, UNBOUNDED, parent(1, 1, created(1), suppressed(2)))));
  }

  /**
   * ADR-0015: a box feeding several inputs has one entry, its first report. S1, Sku in Order's
   * context, feeds both inputs of F1, formatByExample over R1 (removeContexts over S1) and S1, so
   * it is evaluated, and reported, twice: first inside R1, then as F1's example. Its one entry
   * stands where it was first reported, before R1.
   */
  @Test
  void aBoxFeedingTwoInputsHasOneEntry() throws Exception {
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            """
            {"/Out/All": {"id": "T1", "result": "F1", "boxes": {
              "F1": {"kind": "function", "fn": "formatByExample", "inputs": ["R1", "S1"]},
              "R1": {"kind": "function", "fn": "removeContexts", "inputs": ["S1"]},
              "S1": {"kind": "source", "field": "/Orders/Order/Line/Sku",
                     "context": "/Orders/Order"}}}}
            """);
    String input =
        orders(order("o1", line("a", null), line("b", null)), order("o2", line("c", null)));

    TestRunResult.Output result =
        output(facade.testRun(tested(model, observations()), utf8(input), TIMEOUT, LIMIT));

    assertThat(new String(result.output(), UTF_8))
        .isEqualTo(DECLARATION + "<Out><All>a</All><All>b</All></Out>");
    assertThat(result.trace().entries())
        .containsExactlyEntriesOf(
            entries(
                "S1",
                queue(List.of(context("a", "b"), context("c")), false, 3, 2),
                "R1",
                queue(List.of(context("a", "b", "c")), false, 3, 1),
                "F1",
                queue(List.of(context("a", "b"), context("c")), false, 3, 2),
                "T1",
                occurrences(0, UNBOUNDED, parent(1, 2, created(1), created(2)))));
  }

  // --- occurrences per parent slot (ADR-0006, ADR-0015)

  /**
   * Doc/Grp per Id in the root's context, Grp/Row (1..2, depth 3) per createIf over Sku in Order's
   * context, Row/Val (0..1) per Qty in Line's context. The first Order's Skus 1, 0, true, 1 give
   * Row's context 1 "", SUPPRESS, "", "": slot 1 created, 2 suppressed, 3 created, 4 truncated by
   * max 2; the second Order's Sku is slot 5, the slot numbers counting across contexts. Val reads
   * the context its Row's slot number names, 1, 3 and 5, whose Qtys are slots 1, 2 and 4 of its
   * queue (Line 2 has none); Rows 2 and 4 are not created, so Val has no entry for them.
   */
  @Test
  void occurrencesNumberSlotsAcrossContextsAsCreatedSuppressedTruncated() throws Exception {
    byte[] model =
        model(
            "tgt-nested.xsd",
            "Doc",
            """
            {"/Doc/Grp": {"id": "T1", "result": "S1", "boxes": {
               "S1": {"kind": "source", "field": "/Orders/Order/Id", "context": "/Orders"}}},
             "/Doc/Grp/Row": {"id": "T2", "result": "C2", "boxes": {
               "C2": {"kind": "function", "fn": "createIf", "inputs": ["S2"]},
               "S2": {"kind": "source", "field": "/Orders/Order/Line/Sku",
                      "context": "/Orders/Order"}}},
             "/Doc/Grp/Row/Val": {"id": "T3", "result": "S3", "boxes": {
               "S3": {"kind": "source", "field": "/Orders/Order/Line/Qty"}}}}
            """);
    String input =
        orders(
            order("o1", line("1", "q1"), line("0", null), line("true", "q3"), line("1", "q4")),
            order("o2", line("1", "q5")));

    TestRunResult.Output result =
        output(facade.testRun(tested(model, observations()), utf8(input), TIMEOUT, LIMIT));

    assertThat(new String(result.output(), UTF_8))
        .isEqualTo(
            DECLARATION
                + "<Doc><Grp><Row><Val>q1</Val></Row><Row><Val>q3</Val></Row></Grp>"
                + "<Grp><Row><Val>q5</Val></Row></Grp></Doc>");
    assertThat(keys(result.trace())).containsExactly("S1", "T1", "S2", "C2", "T2", "S3", "T3");
    assertThat(occurrencesOf(result.trace(), "T1"))
        .isEqualTo(occurrences(0, UNBOUNDED, parent(1, 2, created(1), created(2))));
    assertThat(occurrencesOf(result.trace(), "T2"))
        .isEqualTo(
            occurrences(
                1,
                2,
                parent(1, 2, created(1), suppressed(2), created(3), truncated(4)),
                parent(2, 1, created(5))));
    assertThat(occurrencesOf(result.trace(), "T3"))
        .isEqualTo(
            occurrences(
                0,
                1,
                parent(1, 1, created(1)),
                parent(3, 1, created(2)),
                parent(5, 1, created(4))));
    assertThat(queueOf(result.trace(), "C2"))
        .isEqualTo(queue(List.of(context("", null, "", ""), context("")), false, 5, 2));
    assertThat(queueOf(result.trace(), "S3"))
        .isEqualTo(
            queue(
                List.of(context("q1"), context(), context("q3"), context("q4"), context("q5")),
                false,
                4,
                5));
  }

  /**
   * ADR-0015: a box never evaluated, because its field was never created, is absent. The corpus
   * mapping create-if-item: Item per createIf over Sku in the root's context, V under it per Qty.
   * Both Skus are false, so no Item is created, V's mapping T2 never runs and its box S2 is never
   * read. Witness: with a true Sku both are traced.
   */
  @Test
  void aFieldUnderASuppressedParentHasNoEntry() throws Exception {
    CompiledMapping mapping = tested(corpusMapping("create-if-item.mapping.json"), mappings());
    TestRunResult.Output witness =
        output(
            facade.testRun(
                mapping,
                utf8(orders(order(null, line("1", "q1"), line("false", "q2")))),
                TIMEOUT,
                LIMIT));
    assertThat(keys(witness.trace())).containsExactly("S1", "C1", "T1", "S2", "T2");

    TestRunResult.Output result =
        output(
            facade.testRun(
                mapping,
                utf8(orders(order(null, line("0", "q1"), line("false", "q2")))),
                TIMEOUT,
                LIMIT));

    assertThat(new String(result.output(), UTF_8)).isEqualTo(DECLARATION + "<Out/>");
    assertThat(result.trace().entries())
        .containsExactlyEntriesOf(
            entries(
                "S1",
                queue(List.of(context("0", "false")), false, 2, 1),
                "C1",
                queue(List.of(context(null, null)), false, 2, 1),
                "T1",
                occurrences(0, UNBOUNDED, parent(1, 0, suppressed(1), suppressed(2)))));
  }

  // --- runtime errors: the trace up to the error (ADR-0015, ADR-0006 since #53)

  /**
   * The corpus mapping nested-grp-row-val, Row 1..2 per Sku in Order's context. The second Order
   * has no Line: its Grp's Row creates 0, fewer than min 1, and the run stops with po:MINOCCURS at
   * T2. A traced TargetSlots reports before its min check, so T2's last parent slot is the one that
   * raised it, created 0 below min 1; everything traced before the error stays.
   */
  @Test
  void aMinOccursShortfallReturnsTheTraceUpToTheError() throws Exception {
    CompiledMapping mapping = tested(corpusMapping("nested-grp-row-val.mapping.json"), mappings());
    String input = orders(order("o1", line("a", "1")), order("o2"));

    TestRunResult.RuntimeError error =
        error(facade.testRun(mapping, utf8(input), TIMEOUT, LIMIT), MINOCCURS, "T2");

    assertThat(error.trace().entries())
        .containsExactlyEntriesOf(
            entries(
                "S1",
                queue(List.of(context("o1", "o2")), false, 2, 1),
                "T1",
                occurrences(0, UNBOUNDED, parent(1, 2, created(1), created(2))),
                "S2",
                queue(List.of(context("a"), context()), false, 1, 2),
                "T2",
                occurrences(1, 2, parent(1, 1, created(1)), parent(2, 0)),
                "S3",
                queue(List.of(context("1")), false, 1, 1),
                "T3",
                occurrences(0, 1, parent(1, 1, created(1)))));
  }

  /**
   * obs-useoneasmany uoam-1 on Out/All: useOneAsMany over Id, Sku in Order's context and Tag, run
   * on three Skus but two Tags, stops with po:USEONEASMANY at u1 (ADR-0025 since #52). Its three
   * inputs were evaluated and are traced; u1 gave no queue, so it has no entry, and t1, whose queue
   * it is, never ran.
   */
  @Test
  void aContextFunctionDataErrorTracesItsInputsNotItsBox() throws Exception {
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            """
            {"/Out/All": {"id": "t1", "result": "u1", "boxes": {
              "u1": {"kind": "function", "fn": "useOneAsMany", "inputs": ["a1", "a2", "a3"]},
              "a1": {"kind": "source", "field": "/Orders/Order/Id"},
              "a2": {"kind": "source", "field": "/Orders/Order/Line/Sku",
                     "context": "/Orders/Order"},
              "a3": {"kind": "source", "field": "/Orders/Order/Tag"}}}}
            """);
    String input =
        orders(
            order(
                "A",
                "<Tag>t</Tag><Tag>t</Tag>",
                line("s", null),
                line("s", null),
                line("s", null)));

    TestRunResult.RuntimeError error =
        error(
            facade.testRun(tested(model, observations()), utf8(input), TIMEOUT, LIMIT),
            "po:USEONEASMANY",
            "u1");

    assertThat(error.trace().entries())
        .containsExactlyEntriesOf(
            entries(
                "a1",
                queue(List.of(context("A")), false, 1, 1),
                "a2",
                queue(List.of(context("s", "s", "s")), false, 3, 1),
                "a3",
                queue(List.of(context("t", "t")), false, 2, 1)));
  }

  /**
   * ADR-0015: a test run compiled without tracing returns an empty trace, with its output and with
   * its runtime error alike. Witness: the test-run compile of the same model traces both runs.
   */
  @Test
  void aDeployCompileTestRunsWithAnEmptyTrace() throws Exception {
    byte[] model = corpusMapping("nested-grp-row-val.mapping.json");
    byte[] runs = utf8(orders(order("o1", line("a", "1"))));
    byte[] fails = utf8(orders(order("o1", line("a", "1")), order("o2")));
    CompiledMapping deployed = compiled(model, mappings(), CompileOptions.DEPLOY);
    CompiledMapping tested = tested(model, mappings());
    assertThat(keys(output(facade.testRun(tested, runs, TIMEOUT, LIMIT)).trace())).contains("T1");
    assertThat(keys(error(facade.testRun(tested, fails, TIMEOUT, LIMIT), MINOCCURS, "T2").trace()))
        .contains("T2");

    TestRunResult.Output output = output(facade.testRun(deployed, runs, TIMEOUT, LIMIT));
    TestRunResult.RuntimeError error =
        error(facade.testRun(deployed, fails, TIMEOUT, LIMIT), MINOCCURS, "T2");

    assertThat(new String(output.output(), UTF_8))
        .isEqualTo(DECLARATION + "<Doc><Grp><Row><Val>1</Val></Row></Grp></Doc>");
    assertThat(output.trace().entries()).isEmpty();
    assertThat(error.trace().entries()).isEmpty();
  }

  // --- the trace limit (ADR-0015, GLOSSARY "Trace limit")

  /**
   * The corpus mapping nested-item-v under a trace limit of 2: Item per Id in the root's context, V
   * per Sku in Order's context. S2's three Skus a | b, c are above the limit: it keeps the first
   * two slots, cutting the context that crosses it, and its totals. S1's two Ids are at the limit,
   * not above it, and T1's two slot records too. T2's three slot records are above it. The cap
   * changes the trace only, never the output.
   */
  @Test
  void theLimitCapsAQueueAndKeepsItsTotals() throws Exception {
    CompiledMapping mapping = tested(corpusMapping("nested-item-v.mapping.json"), mappings());
    String input =
        orders(order("o1", line("a", null)), order("o2", line("b", null), line("c", null)));

    TestRunResult.Output result = output(facade.testRun(mapping, utf8(input), TIMEOUT, 2));

    assertThat(new String(result.output(), UTF_8))
        .isEqualTo(DECLARATION + "<Out><Item><V>a</V></Item><Item><V>b</V><V>c</V></Item></Out>");
    assertThat(queueOf(result.trace(), "S1"))
        .isEqualTo(queue(List.of(context("o1", "o2")), false, 2, 1));
    assertThat(queueOf(result.trace(), "S2"))
        .isEqualTo(queue(List.of(context("a"), context("b")), true, 3, 2));
    assertThat(occurrencesOf(result.trace(), "T1"))
        .isEqualTo(occurrences(0, UNBOUNDED, parent(1, 2, created(1), created(2))));
    assertThat(occurrencesOf(result.trace(), "T2").capped()).as("T2 capped").isTrue();
    assertThat(occurrencesOf(result.trace(), "T2").parents())
        .flatExtracting(Trace.ParentSlot::slots)
        .as("T2's slot records")
        .hasSizeLessThanOrEqualTo(2);
  }

  /**
   * An occurrences entry's slot records count across its parent slots, each parent slot the slots
   * of its own context (#53 review). Under a trace limit of 2, nested-item-v's V has one slot
   * record under each of two Items, slot numbers 1 and 2: two records, at the limit, so it is not
   * capped. The capped side: theLimitCapsAQueueAndKeepsItsTotals, with a third.
   */
  @Test
  void slotRecordsCountAcrossParentSlots() throws Exception {
    CompiledMapping mapping = tested(corpusMapping("nested-item-v.mapping.json"), mappings());
    String input = orders(order("o1", line("a", null)), order("o2", line("b", null)));

    TestRunResult.Output result = output(facade.testRun(mapping, utf8(input), TIMEOUT, 2));

    assertThat(occurrencesOf(result.trace(), "T2"))
        .isEqualTo(occurrences(0, UNBOUNDED, parent(1, 1, created(1)), parent(2, 1, created(2))));
  }

  /**
   * Item per Sku in the root's context, V per Qty in Line's context, under a trace limit of 2. With
   * three Lines and no Qty, V's queue is three empty contexts: no slot, but three contexts, above
   * the limit, so it keeps two and its totals. Witness: two empty contexts are at the limit.
   */
  @Test
  void theLimitCapsEmptyContextsToo() throws Exception {
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            """
            {"/Out/Item": {"id": "T1", "result": "S1", "boxes": {
               "S1": {"kind": "source", "field": "/Orders/Order/Line/Sku", "context": "/Orders"}}},
             "/Out/Item/V": {"id": "T2", "result": "S2", "boxes": {
               "S2": {"kind": "source", "field": "/Orders/Order/Line/Qty"}}}}
            """);
    CompiledMapping mapping = tested(model, observations());
    String two = line("x", null) + line("y", null);
    TestRunResult.Output atTheLimit =
        output(facade.testRun(mapping, utf8(orders(order(null, two))), TIMEOUT, 2));
    assertThat(queueOf(atTheLimit.trace(), "S2"))
        .isEqualTo(queue(List.of(context(), context()), false, 0, 2));

    TestRunResult.Output result =
        output(
            facade.testRun(mapping, utf8(orders(order(null, two, line("z", null)))), TIMEOUT, 2));

    assertThat(new String(result.output(), UTF_8))
        .isEqualTo(DECLARATION + "<Out><Item/><Item/><Item/></Out>");
    assertThat(queueOf(result.trace(), "S2"))
        .isEqualTo(queue(List.of(context(), context()), true, 0, 3));
    assertThat(queueOf(result.trace(), "S1"))
        .isEqualTo(queue(List.of(context("x", "y")), true, 3, 1));
  }

  /**
   * Under a trace limit of 2, nested-grp-row-val runs five Grps; the fifth Order has no Line, so
   * Row's parent slot 5 creates 0, below min 1. T2 has five parent records, above the limit: it is
   * capped and keeps at most two, but always its last, the parent slot that raised po:MINOCCURS.
   */
  @Test
  void theCapKeepsTheShortfallParentSlot() throws Exception {
    CompiledMapping mapping = tested(corpusMapping("nested-grp-row-val.mapping.json"), mappings());
    String input =
        orders(
            order("o1", line("a", null)),
            order("o2", line("b", null)),
            order("o3", line("c", null)),
            order("o4", line("d", null)),
            order("o5"));

    TestRunResult.RuntimeError error =
        error(facade.testRun(mapping, utf8(input), TIMEOUT, 2), MINOCCURS, "T2");

    Trace.Occurrences rows = occurrencesOf(error.trace(), "T2");
    assertThat(rows.capped()).as("capped").isTrue();
    assertThat(rows.min()).isEqualTo(1);
    assertThat(rows.max()).isEqualTo(2);
    assertThat(rows.parents()).hasSizeLessThanOrEqualTo(2);
    assertThat(rows.parents().getFirst()).isEqualTo(parent(1, 1, created(1)));
    assertThat(rows.parents().getLast()).isEqualTo(parent(5, 0));
  }

  /**
   * ADR-0015 and GLOSSARY "Trace limit": an occurrences entry keeps at most the limit's parent
   * records, not only its slot records (#53 review). Under a trace limit of 2, nested-grp-row-val
   * runs two Grps, the first with the Rows a and b, the second with the Row c, and no Line has a
   * Qty: Val reads three empty contexts, so it has three parent records and no slot record. It is
   * capped by its parent records alone and keeps its first and its last. Witness: one Grp with two
   * Rows gives two parent records, at the limit, not capped.
   */
  @Test
  void theLimitCapsParentRecordsWithoutSlotRecords() throws Exception {
    CompiledMapping mapping = tested(corpusMapping("nested-grp-row-val.mapping.json"), mappings());
    String two = order("o1", line("a", null), line("b", null));
    TestRunResult.Output atTheLimit =
        output(facade.testRun(mapping, utf8(orders(two)), TIMEOUT, 2));
    assertThat(occurrencesOf(atTheLimit.trace(), "T3"))
        .isEqualTo(occurrences(0, 1, parent(1, 0), parent(2, 0)));

    TestRunResult.Output result =
        output(
            facade.testRun(mapping, utf8(orders(two, order("o2", line("c", null)))), TIMEOUT, 2));

    assertThat(new String(result.output(), UTF_8))
        .isEqualTo(DECLARATION + "<Doc><Grp><Row/><Row/></Grp><Grp><Row/></Grp></Doc>");
    assertThat(queueOf(result.trace(), "S3"))
        .isEqualTo(queue(List.of(context(), context()), true, 0, 3));
    assertThat(occurrencesOf(result.trace(), "T3"))
        .isEqualTo(new Trace.Occurrences(0, 1, List.of(parent(1, 0), parent(3, 0)), true));
  }

  /**
   * #53 ruling: a trace limit below 1 is refused before anything runs, Integer.MIN_VALUE too, whose
   * limit - 1 would overflow. Witness: the least limit, 1, runs, and S1's two Ids are capped to
   * one.
   */
  @ParameterizedTest(name = "trace limit {0}")
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void aTraceLimitBelowOneIsRefused(int limit) throws Exception {
    CompiledMapping mapping = tested(corpusMapping("nested-item-v.mapping.json"), mappings());
    byte[] input = utf8(orders(order("o1", line("a", null)), order("o2", line("b", null))));
    assertThat(queueOf(output(facade.testRun(mapping, input, TIMEOUT, 1)).trace(), "S1"))
        .isEqualTo(queue(List.of(context("o1")), true, 2, 1));

    assertThatThrownBy(() -> facade.testRun(mapping, input, TIMEOUT, limit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Integer.toString(limit));
  }

  // --- a timeout: the trace so far (ADR-0014 since #53)

  /** The live platform threads other than this one that are inside a mapping run. */
  private static Set<Thread> platformThreadsInAMapping() {
    return Thread.getAllStackTraces().entrySet().stream()
        .filter(e -> e.getKey() != Thread.currentThread())
        .filter(
            e ->
                Stream.of(e.getValue())
                    .anyMatch(f -> f.getClassName().equals(ExecutableMapping.class.getName())))
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  /**
   * {@code snapshot} is a prefix of {@code full}: its authoring ids are the first of the full
   * trace's, in order; a queue entry is the full one, and an occurrences entry has the same min and
   * max and the first of its parent records.
   */
  private static void assertPrefix(Trace snapshot, Trace full) {
    List<AuthoringId> ids = List.copyOf(snapshot.entries().keySet());
    List<AuthoringId> all = List.copyOf(full.entries().keySet());
    assertThat(ids.size()).as("entries at timeout").isLessThanOrEqualTo(all.size());
    assertThat(ids).as("authoring ids at timeout").isEqualTo(all.subList(0, ids.size()));
    for (AuthoringId id : ids) {
      Trace.Entry entry = snapshot.entries().get(id);
      Trace.Entry whole = full.entries().get(id);
      if (entry instanceof Trace.Occurrences part && whole instanceof Trace.Occurrences done) {
        assertThat(part.min()).as("min of %s", id.value()).isEqualTo(done.min());
        assertThat(part.max()).as("max of %s", id.value()).isEqualTo(done.max());
        assertThat(part.capped()).as("capped of %s", id.value()).isFalse();
        assertThat(part.parents().size()).isLessThanOrEqualTo(done.parents().size());
        assertThat(part.parents())
            .as("parents of %s", id.value())
            .isEqualTo(done.parents().subList(0, part.parents().size()));
      } else {
        assertThat(entry).as("entry of %s", id.value()).isEqualTo(whole);
      }
    }
  }

  /**
   * ADR-0014 since #53: at timeout the facade snapshots the tracer, and the run's later reports are
   * dropped. nested-grp-row-val over 20,000 Orders, each with an Id and a Line, reports Row's and
   * Val's occurrences once per Grp while it writes. A fake clock (CODING_STANDARDS), the facade's
   * deadline, reaches the timeout once Row has reported its first parent slot, so the timeout falls
   * while the trace grows, whatever the machine's speed. The timeout's trace is a prefix of the
   * full trace, and stays as it was returned once the timed-out run has ended: the deadline notes
   * the run's thread, live at the timeout, and the test joins it, so no clock waits for its end. A
   * run that ignored its interrupt would hold the join; JUnit's timeout guards against that hang.
   */
  @Test
  @Timeout(60)
  void aTimeoutTraceIsAPrefixOfTheFullTrace() throws Exception {
    timeoutTraceIsAPrefixOfTheFullTrace(false);
  }

  /**
   * The race of #103: the fake clock is descheduled after it saw Row, while the run goes on as far
   * as it can. The clock, still inside the tracer's lock, waits until the run's thread is blocked
   * at its next report, and fails when the run completes first, as it did before #103, when the
   * clock held no lock.
   */
  @Test
  @Timeout(60)
  void aTimeoutTraceIsAPrefixOfTheFullTraceWhenTheClockIsDescheduled() throws Exception {
    timeoutTraceIsAPrefixOfTheFullTrace(true);
  }

  /** Tracer's private lock; a renamed field fails the test here, naming it. */
  private static Object tracerLock(Object tracer) {
    try {
      java.lang.reflect.Field field = tracer.getClass().getDeclaredField("lock");
      field.setAccessible(true);
      return field.get(tracer);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new AssertionError("the fake clock needs the private field Tracer.lock: " + e, e);
    }
  }

  private void timeoutTraceIsAPrefixOfTheFullTrace(boolean descheduled) throws Exception {
    CompiledMapping mapping = tested(corpusMapping("nested-grp-row-val.mapping.json"), mappings());
    StringBuilder xml = new StringBuilder("<Orders>");
    for (int o = 1; o <= 20_000; o++) {
      xml.append(order("o" + o, line("s" + o, "q" + o)));
    }
    byte[] input = utf8(xml.append("</Orders>").toString());
    Trace full =
        output(facade.testRun(mapping, input, Duration.ofSeconds(60), Integer.MAX_VALUE)).trace();
    assertThat(keys(full)).containsExactly("S1", "T1", "S2", "T2", "S3", "T3");
    assertThat(occurrencesOf(full, "T2").parents()).hasSize(20_000);
    AuthoringId row = new AuthoringId("T2");
    Set<Thread> before = platformThreadsInAMapping();
    AtomicReference<Thread> timedOut = new AtomicReference<>();
    EngineFacade timed =
        new EngineFacade(
            (run, timeout, tracer) -> {
              Object lock = tracerLock(tracer);
              while (true) {
                // Row's report and the throw are one step under the tracer's lock, which the run
                // needs for every report: the run is blocked at its next report, live inside
                // ExecutableMapping.run, however the threads are scheduled. Once the monitor is
                // left (the throw), the run may go on, even to its end, before the facade's
                // cancel; every assertion holds then: the result is a RuntimeError because the
                // clock threw, the trace is a prefix, T2 is in it, and the thread was found live.
                synchronized (lock) {
                  if (tracer.occurrences().stream().anyMatch(o -> o.field().equals(row))) {
                    Thread thread =
                        platformThreadsInAMapping().stream()
                            .filter(t -> !before.contains(t))
                            .findFirst()
                            .orElseThrow(
                                () -> new AssertionError("no run's thread at Row's report"));
                    timedOut.set(thread);
                    // a descheduled clock: the run goes on until it blocks at its next report
                    while (descheduled && thread.getState() != Thread.State.BLOCKED) {
                      if (run.isDone()) {
                        throw new AssertionError(
                            "the run completed while the clock was descheduled");
                      }
                      Thread.onSpinWait();
                    }
                    throw new TimeoutException("the fake clock reached the timeout");
                  }
                }
                Thread.onSpinWait();
              }
            });

    TestRunResult result = timed.testRun(mapping, input, Duration.ofSeconds(60), Integer.MAX_VALUE);

    assertThat(result).isInstanceOf(TestRunResult.RuntimeError.class);
    TestRunResult.RuntimeError error = (TestRunResult.RuntimeError) result;
    assertThat(error.code()).isEqualTo("flottila:TIMEOUT");
    Trace grown = error.trace();
    assertPrefix(grown, full);
    assertThat(keys(grown)).as("witness: a timeout after Row's first report").contains("T2");
    String returned = grown.toString();
    assertThat(timedOut.get()).as("the run's thread at the timeout").isNotNull();
    // the facade interrupted it, and it ends at its next interrupt check (ADR-0021)
    timedOut.get().join();
    assertThat(grown.toString()).as("the trace as it was returned").isEqualTo(returned);
  }

  // --- generated nested targets: the occurrences agree with the output (#52's generator)

  /** The levels of the generated target: field path, mapping id, result box. */
  private record Level(String path, String mapping, String result, long min, long max) {}

  /** One seed's model, target schema and input. */
  private record Generated(String links, String schema, String input, List<Level> levels) {}

  private static String pick(RandomGenerator random, String... values) {
    return values[random.nextInt(values.length)];
  }

  /**
   * #52's input generator: up to three Orders, each with an Id or none and up to three Lines, each
   * with a Sku and a Qty or none; the values include createIf's true (1, TRUE, true) and false (0)
   * ones, and ones that tell the contexts apart ({@code o1}, {@code s11}, {@code q11}), which are
   * no boolean ({@link #castable}).
   */
  private static String generatedOrders(RandomGenerator random) {
    StringBuilder xml = new StringBuilder("<Orders>");
    for (int o = 1, count = random.nextInt(4); o <= count; o++) {
      xml.append("<Order>");
      if (random.nextInt(4) != 0) {
        xml.append("<Id>").append(pick(random, "1", "0", "o" + o)).append("</Id>");
      }
      for (int l = 1, lines = random.nextInt(4); l <= lines; l++) {
        xml.append("<Line>");
        if (random.nextInt(4) != 0) {
          xml.append("<Sku>").append(pick(random, "1", "0", "TRUE", "s" + o + l)).append("</Sku>");
        }
        if (random.nextInt(3) != 0) {
          xml.append("<Qty>").append(pick(random, "1", "true", "q" + o + l)).append("</Qty>");
        }
        xml.append("</Line>");
      }
      xml.append("</Order>");
    }
    return xml.append("</Orders>").toString();
  }

  /**
   * {@code input} with each {@code field} value that is no boolean ({@code prefix} and digits) as
   * {@code false}, where createIf reads the field: createIf reads false as SUPPRESS, as it read
   * those values before #133, and raises {@code po:CREATEIF} on them since (F-37).
   */
  private static String castable(String input, boolean cast, String field, String prefix) {
    return cast
        ? input.replaceAll(
            "<" + field + ">" + prefix + "\\d+</" + field + ">",
            "<" + field + ">false</" + field + ">")
        : input;
  }

  private static long max(RandomGenerator random, long min) {
    long[] maxes = {1, 2, 3, UNBOUNDED};
    long max;
    do {
      max = maxes[random.nextInt(maxes.length)];
    } while (max < Math.max(min, 1));
    return max;
  }

  private static String occurs(long min, long max) {
    return "minOccurs=\"" + min + "\" maxOccurs=\"" + (max == UNBOUNDED ? "unbounded" : max) + "\"";
  }

  /** A source box {@code id} over {@code field} in {@code context}, the default when null. */
  private static String source(String id, String field, String context) {
    return "\"%s\": {\"kind\": \"source\", \"field\": \"%s\"%s}"
        .formatted(id, field, context == null ? "" : ", \"context\": \"" + context + "\"");
  }

  private static String createIf(String id, String input) {
    return "\"%s\": {\"kind\": \"function\", \"fn\": \"createIf\", \"inputs\": [\"%s\"]}"
        .formatted(id, input);
  }

  /**
   * #52's nested shape as a model: Doc/Grp per Id in the root's context or createIf over it;
   * Grp/Row per Sku in Order's context, createIf over it, or Sku in the root's context; Row/Val per
   * Qty in Line's context, in Order's context, or createIf over Qty in Line's context; each with
   * seeded min and max, written into the target schema.
   */
  private static Generated generated(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    String drawn = generatedOrders(random);
    long grpMin = random.nextInt(2);
    boolean grpCreateIf = random.nextBoolean();
    long grpMax = max(random, grpMin);
    long rowMin = random.nextInt(3);
    int rowChoice = random.nextInt(3);
    long rowMax = max(random, rowMin);
    long valMin = random.nextInt(2);
    int valChoice = random.nextInt(3);
    long valMax = max(random, valMin);
    String input =
        castable(
            castable(castable(drawn, grpCreateIf, "Id", "o"), rowChoice == 1, "Sku", "s"),
            valChoice == 2,
            "Qty",
            "q");
    String s1 = source("S1", "/Orders/Order/Id", "/Orders");
    String s2 =
        source("S2", "/Orders/Order/Line/Sku", rowChoice == 2 ? "/Orders" : "/Orders/Order");
    String s3 = source("S3", "/Orders/Order/Line/Qty", valChoice == 1 ? "/Orders/Order" : null);
    List<Level> levels =
        List.of(
            new Level("/Doc/Grp", "T1", grpCreateIf ? "C1" : "S1", grpMin, grpMax),
            new Level("/Doc/Grp/Row", "T2", rowChoice == 1 ? "C2" : "S2", rowMin, rowMax),
            new Level("/Doc/Grp/Row/Val", "T3", valChoice == 2 ? "C3" : "S3", valMin, valMax));
    String links =
        """
        {"/Doc/Grp": {"id": "T1", "result": "%s", "boxes": {%s}},
         "/Doc/Grp/Row": {"id": "T2", "result": "%s", "boxes": {%s}},
         "/Doc/Grp/Row/Val": {"id": "T3", "result": "%s", "boxes": {%s}}}
        """
            .formatted(
                levels.get(0).result(),
                grpCreateIf ? createIf("C1", "S1") + ", " + s1 : s1,
                levels.get(1).result(),
                rowChoice == 1 ? createIf("C2", "S2") + ", " + s2 : s2,
                levels.get(2).result(),
                valChoice == 2 ? createIf("C3", "S3") + ", " + s3 : s3);
    String schema =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="unqualified">
          <xs:element name="Doc"><xs:complexType><xs:sequence>
            <xs:element name="Grp" %s><xs:complexType><xs:sequence>
              <xs:element name="Row" %s><xs:complexType><xs:sequence>
                <xs:element name="Val" type="xs:string" %s/>
              </xs:sequence></xs:complexType></xs:element>
            </xs:sequence></xs:complexType></xs:element>
          </xs:sequence></xs:complexType></xs:element>
        </xs:schema>
        """
            .formatted(occurs(grpMin, grpMax), occurs(rowMin, rowMax), occurs(valMin, valMax));
    return new Generated(links, schema, input, levels);
  }

  /** The generated model and its result, run with the schemas written into {@code dir}. */
  private TestRunResult run(Generated generated, Path dir) throws Exception {
    Files.copy(observations().resolve("src-orders.xsd"), dir.resolve("src-orders.xsd"));
    Files.writeString(dir.resolve("tgt-generated.xsd"), generated.schema());
    byte[] model = model("tgt-generated.xsd", "Doc", generated.links());
    return facade.testRun(tested(model, dir), utf8(generated.input()), TIMEOUT, LIMIT);
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /** An element of the generated output: its name and child elements, its text left out. */
  private record Tag(String name, List<Tag> children) {}

  /** {@code <Name>}, {@code </Name>}, {@code <Name/>} or text without markup. */
  private static final Pattern TOKEN = Pattern.compile("<(/?)([A-Za-z]+)(/?)>|[^<]+");

  /**
   * The document element of the generated output. The serializer writes it with no attribute,
   * namespace, comment or escaped character, as the generator's values are letters and digits, so a
   * tag reader suffices; the engine/api module does not read {@code java.xml}.
   */
  private static Tag document(byte[] output) {
    String text = new String(output, UTF_8);
    assertThat(text).startsWith(DECLARATION);
    Matcher token = TOKEN.matcher(text.substring(DECLARATION.length()));
    List<Tag> open = new ArrayList<>(List.of(new Tag("", new ArrayList<>())));
    while (token.find()) {
      if (token.group(2) == null) {
        continue;
      }
      if (token.group(1).equals("/")) {
        assertThat(open.removeLast().name()).isEqualTo(token.group(2));
        continue;
      }
      Tag tag = new Tag(token.group(2), new ArrayList<>());
      open.getLast().children().add(tag);
      if (token.group(3).isEmpty()) {
        open.add(tag);
      }
    }
    assertThat(open).as("every tag closed in %s", text).hasSize(1);
    return open.getFirst().children().getFirst();
  }

  /** The child elements of {@code parents} named {@code name}, in document order. */
  private static List<Tag> children(List<Tag> parents, String name) {
    return parents.stream()
        .flatMap(parent -> parent.children().stream())
        .filter(child -> child.name().equals(name))
        .toList();
  }

  private static long childCount(Tag parent, String name) {
    return children(List.of(parent), name).size();
  }

  /** The parent records of {@code mapping}, none when it has no entry. */
  private static List<Trace.ParentSlot> parentRecords(Trace trace, String mapping) {
    return trace.entries().containsKey(new AuthoringId(mapping))
        ? occurrencesOf(trace, mapping).parents()
        : List.of();
  }

  /** The slot numbers {@code mapping} marks created, over its parent slots in order. */
  private static List<Long> createdSlots(Trace trace, String mapping) {
    return parentRecords(trace, mapping).stream()
        .flatMap(p -> p.slots().stream())
        .filter(s -> s.status() == Trace.SlotStatus.CREATED)
        .map(Trace.Slot::slot)
        .toList();
  }

  /**
   * Each parent record of {@code level} against its result box's queue entry: its slots are the
   * slots of context p, each suppressed exactly where the queue holds null, and its created count
   * is the count of its created slots, at most max.
   */
  private static void assertParentsReadTheirContexts(Trace trace, Level level, String what) {
    Trace.Occurrences entry = occurrencesOf(trace, level.mapping());
    Trace.Queue queue = queueOf(trace, level.result());
    assertThat(entry.capped()).as("%s capped", what).isFalse();
    assertThat(queue.capped()).as("%s queue capped", what).isFalse();
    assertThat(entry.min()).as("%s min", what).isEqualTo(level.min());
    assertThat(entry.max()).as("%s max", what).isEqualTo(level.max());
    for (Trace.ParentSlot parent : entry.parents()) {
      int p = (int) parent.parentSlot();
      long first = 1;
      for (int c = 0; c < p - 1 && c < queue.contexts().size(); c++) {
        first += queue.contexts().get(c).size();
      }
      List<String> context = p <= queue.contexts().size() ? queue.contexts().get(p - 1) : List.of();
      List<Long> numbers = new ArrayList<>();
      for (int s = 0; s < context.size(); s++) {
        numbers.add(first + s);
      }
      String at = what + " parent slot " + p;
      assertThat(parent.slots()).extracting(Trace.Slot::slot).as(at).isEqualTo(numbers);
      for (Trace.Slot slot : parent.slots()) {
        String value = context.get((int) (slot.slot() - first));
        assertThat(slot.status() == Trace.SlotStatus.SUPPRESSED)
            .as("%s slot %d of value %s", at, slot.slot(), value)
            .isEqualTo(value == null);
      }
      long marked =
          parent.slots().stream().filter(s -> s.status() == Trace.SlotStatus.CREATED).count();
      assertThat(parent.created()).as("%s created", at).isEqualTo(marked);
      assertThat(parent.created())
          .as("%s created at most max", at)
          .isLessThanOrEqualTo(level.max());
    }
  }

  /**
   * A seeded Doc/Grp/Row/Val model over seeded Orders (#52's generator). When the run writes its
   * output, each level's parent records are the created slots of the level above, in order, and
   * each creates as many slots as the output holds children under that parent's element; when it
   * stops with po:MINOCCURS, the failing mapping's last parent record created fewer than its min
   * and every earlier one at least min. In both, each parent record reads its context of the result
   * box's queue.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void tracedOccurrencesMatchTheOutput(long seed, @TempDir Path dir) throws Exception {
    Generated generated = generated(seed);
    String what = "seed " + seed + ", input " + generated.input();
    TestRunResult result = run(generated, dir);
    Trace trace =
        switch (result) {
          case TestRunResult.Output o -> o.trace();
          case TestRunResult.RuntimeError e -> {
            assertThat(e.code()).as(what).isEqualTo(MINOCCURS);
            yield e.trace();
          }
        };
    List<Level> levels = generated.levels();
    // the root is created once, so the first level has the one parent slot 1
    assertThat(occurrencesOf(trace, "T1").parents())
        .as(what)
        .extracting(Trace.ParentSlot::parentSlot)
        .containsExactly(1L);
    for (int k = 0; k < levels.size(); k++) {
      Level level = levels.get(k);
      if (trace.entries().containsKey(new AuthoringId(level.mapping()))) {
        assertParentsReadTheirContexts(trace, level, what + " " + level.mapping());
      }
      if (k > 0) {
        // a mapping that never ran has no parent record: none was created above it, or the run
        // stopped before it reached one
        List<Long> above = createdSlots(trace, levels.get(k - 1).mapping());
        List<Long> parents =
            parentRecords(trace, level.mapping()).stream()
                .map(Trace.ParentSlot::parentSlot)
                .toList();
        assertThat(parents.size()).as(what).isLessThanOrEqualTo(above.size());
        assertThat(parents)
            .as("%s: %s's parent slots", what, level.mapping())
            .isEqualTo(above.subList(0, parents.size()));
        if (result instanceof TestRunResult.Output) {
          assertThat(parents).as(what).hasSameSizeAs(above);
        }
      }
    }

    if (result instanceof TestRunResult.RuntimeError e) {
      String failing = e.authoringId().orElseThrow().value();
      assertThat(parentRecords(trace, failing)).as("%s: %s's records", what, failing).isNotEmpty();
      for (Level level : levels) {
        List<Trace.ParentSlot> parents = parentRecords(trace, level.mapping());
        for (int i = 0; i < parents.size(); i++) {
          boolean shortfall = level.mapping().equals(failing) && i == parents.size() - 1;
          assertThat(parents.get(i).created() < level.min())
              .as(
                  "%s: %s parent %s short of min %d",
                  what, level.mapping(), parents.get(i), level.min())
              .isEqualTo(shortfall);
        }
      }
      return;
    }
    Tag doc = document(((TestRunResult.Output) result).output());
    assertThat(doc.name()).isEqualTo("Doc");
    List<Tag> elements = List.of(doc);
    for (int k = 0; k < levels.size(); k++) {
      String name = levels.get(k).path().substring(levels.get(k).path().lastIndexOf('/') + 1);
      List<Trace.ParentSlot> parents = parentRecords(trace, levels.get(k).mapping());
      assertThat(parents)
          .as("%s: a %s record per parent element", what, name)
          .hasSameSizeAs(elements);
      for (int i = 0; i < elements.size(); i++) {
        assertThat(parents.get(i).created())
            .as("%s: %s under parent element %d", what, name, i + 1)
            .isEqualTo(childCount(elements.get(i), name));
      }
      elements = children(elements, name);
    }
    for (Level level : levels) {
      for (Trace.ParentSlot parent : parentRecords(trace, level.mapping())) {
        assertThat(parent.created())
            .as("%s: %s at least min", what, level.mapping())
            .isGreaterThanOrEqualTo(level.min());
      }
    }
  }

  /**
   * Witness for the property: over the seeds, the runs create Vals, suppress and truncate slots,
   * and fall short of min at more than one level, so it compares every branch.
   */
  @Test
  void theSeedsMakeEveryStatusAndShortfallsAtSeveralLevels(@TempDir Path base) throws Exception {
    int withVal = 0;
    TreeSet<Trace.SlotStatus> statuses = new TreeSet<>();
    TreeSet<String> shortfalls = new TreeSet<>();
    for (long seed : seeds().toArray()) {
      Path dir = Files.createDirectory(base.resolve("seed-" + seed));
      TestRunResult result = run(generated(seed), dir);
      Trace trace =
          switch (result) {
            case TestRunResult.Output o -> {
              if (new String(o.output(), UTF_8).contains("<Val>")) {
                withVal++;
              }
              yield o.trace();
            }
            case TestRunResult.RuntimeError e -> {
              shortfalls.add(e.authoringId().orElseThrow().value());
              yield e.trace();
            }
          };
      trace.entries().values().stream()
          .filter(Trace.Occurrences.class::isInstance)
          .map(Trace.Occurrences.class::cast)
          .flatMap(o -> o.parents().stream())
          .flatMap(p -> p.slots().stream())
          .forEach(s -> statuses.add(s.status()));
    }

    assertThat(withVal).as("seeds whose document has a Val").isGreaterThanOrEqualTo(20);
    assertThat(statuses).as("statuses seen").containsExactlyInAnyOrder(Trace.SlotStatus.values());
    assertThat(shortfalls).as("mappings that fall short of min").hasSizeGreaterThanOrEqualTo(2);
  }
}
