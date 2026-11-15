package io.flottila.mapping.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Lift;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.FutureTask;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Target fields at any depth (ADR-0006 "Target fields" as refined by #52) at {@code
 * Interpreter.compile} and {@code ExecutableMapping.run}. A {@code TargetSlots} reads context p of
 * its queue, p its parent's slot number; each slot gets its slot number, its position across all
 * contexts of the queue, which the body holds in {@code slotVar} and the children read as their
 * {@code parentSlot}. A SUPPRESS slot and a truncated slot create nothing but keep their numbers;
 * fewer than {@code min} created occurrences raise {@code po:MINOCCURS} at the target field
 * mapping, checked before the first occurrence of context p is written; a missing context counts as
 * empty (observed for an optional child, #31 d1); a queue is evaluated at most once per run, on
 * first read.
 *
 * <p>The IR is built by hand as lowering gives it: the slot and value variables of a field at depth
 * d are {@code slot<d>} and {@code value<d>}, the root's is {@code root}. The source is {@code
 * src-orders.xsd}'s shape ({@code Orders/Order/Id, Tag, Line/Sku, Line/Qty, Memo/Text}), the
 * targets {@code tgt-nested.xsd}'s ({@code Doc/Grp/Row/Val}) and {@code tgt-list.xsd}'s ({@code
 * Out/Item/V}, {@code Out/All}). Expected outputs are byte literals worked out from ADR-0006.
 */
class TargetSlotsInterpreterTest {

  /** The time every run of this class starts at; no function here reads it. */
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-04T12:00:00Z");

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final String MINOCCURS = "po:MINOCCURS";
  private static final long UNBOUNDED = Long.MAX_VALUE;
  private static final QueueType QUEUE = new QueueType();
  private static final FunctionRef CREATE_IF = new FunctionRef("createIf", List.of(QUEUE), QUEUE);

  private static final Name ORDERS = local("Orders");
  private static final Name ORDER = local("Order");
  private static final Name ID = local("Id");
  private static final Name TAG = local("Tag");
  private static final Name LINE = local("Line");
  private static final Name SKU = local("Sku");
  private static final Name QTY = local("Qty");
  private static final Name MEMO = local("Memo");
  private static final Name TEXT = local("Text");

  private static final Name DOC = local("Doc");
  private static final Name GRP = local("Grp");
  private static final Name ROW = local("Row");
  private static final Name VAL = local("Val");
  private static final Name OUT = local("Out");
  private static final Name ITEM = local("Item");
  private static final Name V = local("V");
  private static final Name ALL = local("All");

  // --- IR, built by hand as lowering gives it

  private static Name local(String name) {
    return new Name("", name);
  }

  private static AuthoringId id(String value) {
    return new AuthoringId(value);
  }

  /** The queue of the source field {@code steps} in the context of its ancestor at that depth. */
  private static SourceQueue source(String box, int contextDepth, Name... steps) {
    return new SourceQueue(List.of(steps), contextDepth, id(box));
  }

  private static FunCall createIf(String box, Expr condition) {
    return new FunCall(CREATE_IF, List.of(condition), id(box));
  }

  /** The parent's slot number of a field at {@code depth}: the root's, or {@code slot<d - 1>}. */
  private static VarRef parentSlot(int depth, AuthoringId origin) {
    return new VarRef(depth == 2 ? "root" : "slot" + (depth - 1), AtomicType.INTEGER, origin);
  }

  /** A structure field at {@code depth}: per created slot, the element with {@code children}. */
  private static TargetSlots node(
      Name name, int depth, Expr queue, long min, long max, String mapping, Expr... children) {
    AuthoringId origin = id(mapping);
    return new TargetSlots(
        queue,
        parentSlot(depth, origin),
        min,
        max,
        "slot" + depth,
        "value" + depth,
        new ElementConstructor("", name, List.of(children), origin),
        origin);
  }

  /** A leaf field at {@code depth}: per created slot, the element with the slot's value. */
  private static TargetSlots leaf(
      Name name, int depth, Expr queue, long min, long max, String mapping) {
    AuthoringId origin = id(mapping);
    VarRef value = new VarRef("value" + depth, AtomicType.STRING, origin);
    return new TargetSlots(
        queue,
        parentSlot(depth, origin),
        min,
        max,
        "slot" + depth,
        "value" + depth,
        new ElementConstructor("", name, List.of(new TextConstructor(value, origin)), origin),
        origin);
  }

  private static TargetDocument document(Name root, Expr... content) {
    TargetDocument document = new TargetDocument("", root, "root", List.of(content));
    // the IR is well-formed, so what a test observes is the run's
    IrVerifier.verify(document);
    return document;
  }

  /**
   * {@code Doc/Grp/Row/Val}: Grp per Order Id in the root's context, Row per Sku in Order's context
   * at {@code rowMin..rowMax}, Val per Qty in Line's context, 0..1.
   */
  private static TargetDocument groupsOfRows(long rowMin, long rowMax) {
    return document(
        DOC,
        node(
            GRP,
            2,
            source("a1", 1, ORDERS, ORDER, ID),
            0,
            UNBOUNDED,
            "t1",
            node(
                ROW,
                3,
                source("a2", 2, ORDERS, ORDER, LINE, SKU),
                rowMin,
                rowMax,
                "t2",
                leaf(VAL, 4, source("a3", 3, ORDERS, ORDER, LINE, QTY), 0, 1, "t3"))));
  }

  // --- running

  private static String run(TargetDocument document, String input, Tracer tracer, boolean traced)
      throws Exception {
    ExecutableMapping mapping = Interpreter.compile(document, traced);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    mapping.run(new ByteArrayInputStream(input.getBytes(UTF_8)), out, tracer, NOW);
    return out.toString(UTF_8);
  }

  private static String run(TargetDocument document, String input) throws Exception {
    return run(document, input, new Tracer(), false);
  }

  private static String orders(String... orders) {
    return "<Orders>" + String.join("", orders) + "</Orders>";
  }

  /** {@code <Line>} with a Sku, and a Qty unless {@code qty} is null. */
  private static String line(String sku, String qty) {
    return "<Line><Sku>"
        + sku
        + "</Sku>"
        + (qty == null ? "" : "<Qty>" + qty + "</Qty>")
        + "</Line>";
  }

  /** An Order with an Id and its content. */
  private static String order(String id, String... content) {
    return "<Order><Id>" + id + "</Id>" + String.join("", content) + "</Order>";
  }

  /** The reports of {@code box}, in the order made. */
  private static List<QueueBlock> reports(Tracer tracer, String box) {
    return tracer.values().stream()
        .filter(e -> e.getKey().equals(id(box)))
        .map(Map.Entry::getValue)
        .toList();
  }

  /** The block as contexts of values, SUPPRESS as null. */
  private static List<List<String>> contexts(QueueBlock block) {
    List<List<String>> contexts = new ArrayList<>();
    for (int c = 0; c < block.contexts(); c++) {
      List<String> context = new ArrayList<>();
      for (int i = block.start(c); i < block.end(c); i++) {
        context.add(block.suppressed(i) ? null : block.value(i));
      }
      contexts.add(context);
    }
    return contexts;
  }

  private static void assertRuntimeError(ThrowingCallable run, String code, String authoringId) {
    assertThatThrownBy(run)
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).as("code").isEqualTo(code);
              assertThat(e.authoringId()).as("authoring id").isEqualTo(id(authoringId));
              assertThat(e.getMessage()).as("message").isNotBlank();
            });
  }

  // --- slot numbers (ADR-0006)

  /**
   * #2 §2 (the createIf example): a SUPPRESS slot creates nothing but keeps its number, so it
   * consumes its child context. Item per {@code createIf(Id)} in the root's context: "", SUPPRESS,
   * "". V per Sku in Order's context: [a1, a2], [b1], [c1]. Item 2 is suppressed and takes [b1]
   * with it, so the second Item reads context 3; renumbered slots would give it b1.
   */
  @Test
  void suppressedParentCreatesNoChildrenAndTheNextReadsItsOwnContext() throws Exception {
    TargetDocument items =
        document(
            OUT,
            node(
                ITEM,
                2,
                createIf("c1", source("a1", 1, ORDERS, ORDER, ID)),
                0,
                UNBOUNDED,
                "t1",
                leaf(V, 3, source("a2", 2, ORDERS, ORDER, LINE, SKU), 0, UNBOUNDED, "t2")));
    String input =
        orders(
            order("1", line("a1", null), line("a2", null)),
            order("0", line("b1", null)),
            order("1", line("c1", null)));

    assertThat(run(items, input))
        .isEqualTo(
            DECLARATION + "<Out><Item><V>a1</V><V>a2</V></Item><Item><V>c1</V></Item></Out>");
  }

  /**
   * Row per Sku in Order's context: [a, b], [c, d], slot numbers 1 to 4 across both contexts. Val
   * per Qty in Line's context reads context 3 under c, not context 1: a slot number is no position
   * within its context. The Line d has no Qty, so its context is empty.
   */
  @Test
  void slotNumbersCountAcrossContextsAtDepthFour() throws Exception {
    String input =
        orders(
            order("o1", line("a", "1"), line("b", "2")),
            order("o2", line("c", "3"), line("d", null)));

    assertThat(run(groupsOfRows(1, 2), input))
        .isEqualTo(
            DECLARATION
                + "<Doc><Grp><Row><Val>1</Val></Row><Row><Val>2</Val></Row></Grp>"
                + "<Grp><Row><Val>3</Val></Row><Row/></Grp></Doc>");
  }

  /**
   * Row is 1..2. The first Order has three Lines, so Row's slot 3 is truncated: it creates nothing
   * but keeps its number, and the second Order's Row is slot 4 and reads Qty 4. Renumbered slots
   * would give it Qty 3.
   */
  @Test
  void truncatedSlotsKeepTheirNumbers() throws Exception {
    String input =
        orders(
            order("o1", line("a", "1"), line("b", "2"), line("c", "3")),
            order("o2", line("d", "4")));

    assertThat(run(groupsOfRows(1, 2), input))
        .isEqualTo(
            DECLARATION
                + "<Doc><Grp><Row><Val>1</Val></Row><Row><Val>2</Val></Row></Grp>"
                + "<Grp><Row><Val>4</Val></Row></Grp></Doc>");
  }

  // --- min and po:MINOCCURS (ADR-0006 since #52)

  /**
   * #2 §2 (documented): fewer occurrences than {@code minOccurs} raise an exception. The second
   * Order has no Line, so its Grp gets no Row, below Row's min 1: {@code po:MINOCCURS} at Row's
   * target field mapping t2, not at its source box a2 or the parent's mapping t1.
   */
  @Test
  void fewerThanMinRaisesMinOccursAtTheTargetFieldMapping() throws Exception {
    String input = orders(order("o1", line("a", "1")), order("o2"));
    // witness: with min 0 the same input runs, and the second Grp is empty
    assertThat(run(groupsOfRows(0, 2), input))
        .isEqualTo(DECLARATION + "<Doc><Grp><Row><Val>1</Val></Row></Grp><Grp/></Doc>");

    assertRuntimeError(() -> run(groupsOfRows(1, 2), input), MINOCCURS, "t2");
  }

  /**
   * #31 a2 (assumed): SUPPRESS does not count toward min. Row per {@code createIf(Sku)} in Order's
   * context at 1..2: ["", SUPPRESS] and [SUPPRESS]. The first Grp creates one Row beside its
   * SUPPRESS slot; the second creates none.
   */
  @Test
  void suppressDoesNotCountTowardMin() throws Exception {
    TargetDocument rows =
        document(
            DOC,
            node(
                GRP,
                2,
                source("a1", 1, ORDERS, ORDER, ID),
                0,
                UNBOUNDED,
                "t1",
                node(
                    ROW,
                    3,
                    createIf("c2", source("a2", 2, ORDERS, ORDER, LINE, SKU)),
                    1,
                    2,
                    "t2")));
    // witness: a true Sku in the second Order meets min
    assertThat(
            run(
                rows,
                orders(
                    order("o1", line("1", null), line("0", null)), order("o2", line("1", null)))))
        .isEqualTo(DECLARATION + "<Doc><Grp><Row/></Grp><Grp><Row/></Grp></Doc>");

    assertRuntimeError(
        () ->
            run(
                rows,
                orders(
                    order("o1", line("1", null), line("0", null)), order("o2", line("0", null)))),
        MINOCCURS,
        "t2");
  }

  /**
   * #31 d1 (observed for an optional child): a context the queue lacks counts as empty. Row per
   * Text in Memo's context: the second Order has no Memo, so the queue has one context, and the
   * second Grp's slot number 2 names none.
   */
  @Test
  void aMissingContextCountsAsEmpty() throws Exception {
    TargetDocument atLeast = memoRows(1);
    TargetDocument optional = memoRows(0);
    String input = orders(order("o1", "<Memo><Text>a</Text></Memo>"), order("o2"));
    Tracer tracer = new Tracer();

    // witness: at min 0 the second Grp is empty, and the queue lacks context 2, not holds it empty
    assertThat(run(optional, input, tracer, true))
        .isEqualTo(DECLARATION + "<Doc><Grp><Row>a</Row></Grp><Grp/></Doc>");
    assertThat(reports(tracer, "a2"))
        .singleElement()
        .satisfies(block -> assertThat(contexts(block)).containsExactly(List.of("a")));

    assertRuntimeError(() -> run(atLeast, input), MINOCCURS, "t2");
  }

  private static TargetDocument memoRows(long min) {
    return document(
        DOC,
        node(
            GRP,
            2,
            source("a1", 1, ORDERS, ORDER, ID),
            0,
            UNBOUNDED,
            "t1",
            leaf(ROW, 3, source("a2", 3, ORDERS, ORDER, MEMO, TEXT), min, 2, "t2")));
  }

  /**
   * {@code min} is checked before the first occurrence of context p is written: Row is 2..2 and the
   * Order has one Line, so no Row is created and Val's queue, read only inside a Row, is never
   * evaluated. A check after writing would have read it.
   */
  @Test
  void minIsCheckedBeforeAnyChildRuns() throws Exception {
    TargetDocument exactlyTwo = groupsOfRows(2, 2);
    Tracer witness = new Tracer();
    // witness: with two Lines the run reads Val's queue
    assertThat(run(exactlyTwo, orders(order("o1", line("a", "1"), line("b", "2"))), witness, true))
        .isEqualTo(
            DECLARATION + "<Doc><Grp><Row><Val>1</Val></Row><Row><Val>2</Val></Row></Grp></Doc>");
    assertThat(reports(witness, "a3")).hasSize(1);
    Tracer tracer = new Tracer();

    assertRuntimeError(
        () -> run(exactlyTwo, orders(order("o1", line("a", "1"))), tracer, true), MINOCCURS, "t2");
    assertThat(reports(tracer, "a2")).as("Row's queue").hasSize(1);
    assertThat(reports(tracer, "a3")).as("Val's queue").isEmpty();
  }

  // --- tracing the occurrences (ADR-0006 since #53)

  /** Each occurrences report: the target field mapping, the parent slot, min..max (* unbounded). */
  private static List<String> occurrences(Tracer tracer) {
    return tracer.occurrences().stream()
        .map(
            o ->
                o.field().value()
                    + " "
                    + o.parentSlot()
                    + " "
                    + o.min()
                    + ".."
                    + (o.max() == UNBOUNDED ? "*" : o.max()))
        .toList();
  }

  /**
   * A traced TargetSlots reports its field, parent slot, queue, min and max before its min check
   * (ADR-0006 since #53), so a shortfall is traced. Row is 1..2; the second Order has no Line, so
   * its Grp's Row reads the empty context 2 and raises po:MINOCCURS at t2. Its report, the last, is
   * already made: parent slot 2 over Row's queue [a] | [], whose context 2 shows the shortfall. A
   * report after the check, or after the slots were written, would have none for parent slot 2.
   */
  @Test
  void aTracedTargetSlotsReportsBeforeItsMinCheck() throws Exception {
    Tracer tracer = new Tracer();

    assertRuntimeError(
        () ->
            run(groupsOfRows(1, 2), orders(order("o1", line("a", "1")), order("o2")), tracer, true),
        MINOCCURS,
        "t2");

    assertThat(occurrences(tracer))
        .containsExactly("t1 1 0..*", "t2 1 1..2", "t3 1 0..1", "t2 2 1..2");
    Tracer.Occurrences shortfall = tracer.occurrences().getLast();
    assertThat(contexts(shortfall.queue())).containsExactly(List.of("a"), List.of());
    assertThat(shortfall.queue()).isSameAs(reports(tracer, "a2").getFirst());
  }

  /**
   * The tracer's lists may be read while the run reports, as the facade does at a timeout (ADR-0014
   * since #53): each read is a consistent snapshot, the reports made so far in order, so every read
   * extends the one before it, a read is a copy the later reports never reach, and the last read is
   * a prefix of the run's whole list. Grp per Id, Row per Sku in Order's context, over 100,000
   * Orders: Row reports once per Grp while the run writes. Up to five runs are read until a read
   * falls while the run reports.
   */
  @Test
  void aSnapshotWhileTheRunReportsIsConsistent() throws Exception {
    TargetDocument rows =
        document(
            DOC,
            node(
                GRP,
                2,
                source("a1", 1, ORDERS, ORDER, ID),
                0,
                UNBOUNDED,
                "t1",
                leaf(ROW, 3, source("a2", 2, ORDERS, ORDER, LINE, SKU), 0, UNBOUNDED, "t2")));
    StringBuilder xml = new StringBuilder("<Orders>");
    for (int o = 1; o <= 100_000; o++) {
      xml.append(order("o" + o, line("s" + o, null)));
    }
    byte[] input = xml.append("</Orders>").toString().getBytes(UTF_8);
    ExecutableMapping mapping = Interpreter.compile(rows, true);

    boolean midRun = false;
    List<Tracer.Occurrences> midRead = null;
    int midSize = 0;
    for (int attempt = 0; attempt < 5 && !midRun; attempt++) {
      Tracer tracer = new Tracer();
      FutureTask<Void> run =
          new FutureTask<>(
              () -> {
                mapping.run(
                    new ByteArrayInputStream(input), OutputStream.nullOutputStream(), tracer, NOW);
                return null;
              });
      Thread.ofPlatform().name("traced run").daemon(true).start(run);
      List<Tracer.Occurrences> previous = List.of();
      List<Map.Entry<AuthoringId, QueueBlock>> previousValues = List.of();
      int reads = 0;
      while (!run.isDone()) {
        List<Tracer.Occurrences> read = tracer.occurrences();
        List<Map.Entry<AuthoringId, QueueBlock>> values = tracer.values();
        assertThat(read.size()).as("read %d", reads).isGreaterThanOrEqualTo(previous.size());
        assertThat(read.subList(0, previous.size())).as("read %d", reads).isEqualTo(previous);
        assertThat(values.size())
            .as("values %d", reads)
            .isGreaterThanOrEqualTo(previousValues.size());
        assertThat(values.subList(0, previousValues.size()))
            .as("values %d", reads)
            .isEqualTo(previousValues);
        if (!midRun && !read.isEmpty() && read.size() < 100_001) {
          midRun = true;
          midRead = read;
          midSize = read.size();
        }
        previous = read;
        previousValues = values;
        reads++;
      }
      run.get();
      List<Tracer.Occurrences> all = tracer.occurrences();

      // a read is a copy: the reports after it never reach it
      if (midRead != null) {
        assertThat(midRead).as("a read during the run, after it").hasSize(midSize);
      }
      assertThat(all).hasSize(100_001);
      assertThat(all.subList(0, previous.size())).isEqualTo(previous);
      assertThat(all.getFirst().field()).isEqualTo(id("t1"));
      assertThat(all.getLast().field()).isEqualTo(id("t2"));
      assertThat(all.getLast().parentSlot()).isEqualTo(100_000);
      assertThat(tracer.values().subList(0, previousValues.size())).isEqualTo(previousValues);
    }
    assertThat(midRun).as("witness: a read while the run reported").isTrue();
  }

  // --- evaluation (ADR-0006: a queue is evaluated at most once per run, on first read)

  /**
   * Three Grps read Row's queue, a createIf over Sku, and three Rows read Val's: each queue, and
   * the call, is evaluated once, so a traced compile reports each once. With no Order no Grp is
   * created, and the queues under it are never evaluated.
   */
  @Test
  void aChildQueueIsEvaluatedOncePerRun() throws Exception {
    TargetDocument document =
        document(
            DOC,
            node(
                GRP,
                2,
                source("a1", 1, ORDERS, ORDER, ID),
                0,
                UNBOUNDED,
                "t1",
                node(
                    ROW,
                    3,
                    createIf("c2", source("a2", 2, ORDERS, ORDER, LINE, SKU)),
                    0,
                    2,
                    "t2",
                    leaf(VAL, 4, source("a3", 3, ORDERS, ORDER, LINE, QTY), 0, 1, "t3"))));
    Tracer tracer = new Tracer();

    String out =
        run(
            document,
            orders(
                order("o1", line("1", "1")),
                order("o2", line("1", "2")),
                order("o3", line("1", "3"))),
            tracer,
            true);

    assertThat(out)
        .isEqualTo(
            DECLARATION
                + "<Doc><Grp><Row><Val>1</Val></Row></Grp><Grp><Row><Val>2</Val></Row></Grp>"
                + "<Grp><Row><Val>3</Val></Row></Grp></Doc>");
    for (String box : List.of("a1", "a2", "c2", "a3")) {
      assertThat(reports(tracer, box)).as("reports of %s", box).hasSize(1);
    }
    Tracer none = new Tracer();
    assertThat(run(document, orders(), none, true)).isEqualTo(DECLARATION + "<Doc/>");
    assertThat(reports(none, "a1")).hasSize(1);
    for (String box : List.of("a2", "c2", "a3")) {
      assertThat(reports(none, box)).as("reports of %s with no Grp", box).isEmpty();
    }
  }

  // --- context functions' data errors (ADR-0025 since #52): one code per function, at its box

  private static final FunctionRef USE_ONE_AS_MANY =
      new FunctionRef("useOneAsMany", List.of(QUEUE, QUEUE, QUEUE), QUEUE);
  private static final FunctionRef FORMAT_BY_EXAMPLE =
      new FunctionRef("formatByExample", List.of(QUEUE, QUEUE), QUEUE);
  private static final FunctionRef SORT =
      new FunctionRef("sort", List.of(QUEUE, AtomicType.STRING, AtomicType.STRING), QUEUE);
  private static final FunctionRef SORT_BY_KEY =
      new FunctionRef(
          "sortByKey", List.of(QUEUE, QUEUE, AtomicType.STRING, AtomicType.STRING), QUEUE);
  private static final FunctionRef CONCAT =
      new FunctionRef("concat", List.of(AtomicType.STRING, AtomicType.STRING), AtomicType.STRING);

  /** {@code Out/All} per slot of {@code queue}'s context 1, mapping t1. */
  private static TargetDocument all(Expr queue) {
    return document(OUT, leaf(ALL, 2, queue, 0, UNBOUNDED, "t1"));
  }

  private static FunCall call(FunctionRef function, String box, Expr... args) {
    return new FunCall(function, List.of(args), id(box));
  }

  private static Literal param(String value, String box) {
    return new Literal(value, id(box));
  }

  /** One Order with Id A, {@code tags} Tags and one Line per Sku, as the facade's tests use. */
  private static String tagsAndSkus(int tags, int skus) {
    return orders(
        order("A", "<Tag>t</Tag>".repeat(tags) + "<Line><Sku>s</Sku></Line>".repeat(skus)));
  }

  static Stream<Arguments> dataErrors() {
    SourceQueue idInOrder = source("a1", 2, ORDERS, ORDER, ID);
    SourceQueue skuInOrder = source("a2", 2, ORDERS, ORDER, LINE, SKU);
    SourceQueue tagInOrder = source("a3", 2, ORDERS, ORDER, TAG);
    SourceQueue qtyInOrder = source("a4", 2, ORDERS, ORDER, LINE, QTY);
    // a key 5 where the Tag is true and SUPPRESS where it is not: concat passes a SUPPRESS on
    // (obs-lift-reuse lift-2); replaceValue built it until #216, but it replaces a SUPPRESS (F-45)
    Lift keys =
        new Lift(
            List.of(createIf("c1", tagInOrder)),
            List.of("in1"),
            new FunCall(
                CONCAT,
                List.of(new VarRef("in1", AtomicType.STRING, id("r1")), param("5", "r1")),
                id("r1")),
            id("r1"));
    return Stream.of(
        Arguments.of(
            "useOneAsMany, three Skus but two Tags (obs-useoneasmany u2)",
            all(call(USE_ONE_AS_MANY, "u1", idInOrder, skuInOrder, tagInOrder)),
            tagsAndSkus(3, 3),
            tagsAndSkus(2, 3),
            "po:USEONEASMANY",
            "u1",
            "useOneAsMany"),
        Arguments.of(
            "formatByExample, three Tags but two Skus (#2 §4)",
            all(call(FORMAT_BY_EXAMPLE, "f1", tagInOrder, skuInOrder)),
            tagsAndSkus(2, 2),
            tagsAndSkus(3, 2),
            "po:FORMATBYEXAMPLE",
            "f1",
            "formatByExample"),
        Arguments.of(
            "a numeric sort over x",
            all(call(SORT, "s1", tagInOrder, param("numeric", "s1"), param("ascending", "s1"))),
            orders(order("A", "<Tag>10</Tag><Tag>9</Tag>")),
            orders(order("A", "<Tag>10</Tag><Tag>x</Tag><Tag>9</Tag>")),
            "po:SORT",
            "s1",
            "sort"),
        Arguments.of(
            "sortByKey, two keys but one value (#2 §4)",
            all(
                call(
                    SORT_BY_KEY,
                    "k1",
                    skuInOrder,
                    qtyInOrder,
                    param("lexicographic", "k1"),
                    param("ascending", "k1"))),
            orders(order("A", line("b", "1"), line("a", "2"))),
            orders(order("A", line("b", "1"), line("a", null))),
            "po:SORTBYKEY",
            "k1",
            "sortByKey"),
        Arguments.of(
            "sortByKey, keys per Line but values per Order (#2 §4)",
            all(
                call(
                    SORT_BY_KEY,
                    "k1",
                    source("a5", 3, ORDERS, ORDER, LINE, SKU),
                    qtyInOrder,
                    param("lexicographic", "k1"),
                    param("ascending", "k1"))),
            orders(order("A", line("b", "1"))),
            orders(order("A", line("b", "1"), line("a", "2"))),
            "po:SORTBYKEY",
            "k1",
            "sortByKey"),
        Arguments.of(
            "a numeric sortByKey over a SUPPRESS key (#2 §3)",
            all(
                call(
                    SORT_BY_KEY,
                    "k1",
                    keys,
                    tagInOrder,
                    param("numeric", "k1"),
                    param("ascending", "k1"))),
            orders(order("A", "<Tag>1</Tag>")),
            orders(order("A", "<Tag>1</Tag><Tag>0</Tag>")),
            "po:SORTBYKEY",
            "k1",
            "sortByKey"));
  }

  /**
   * A context function's data error stops the run with the function's one code, {@code po:<NAME>},
   * at its function box, not at the target field mapping t1; the message names the function
   * (ADR-0025 since #52).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("dataErrors")
  void contextFunctionDataErrorsCarryCodeAndBox(
      String why,
      TargetDocument document,
      String fits,
      String misfits,
      String code,
      String box,
      String function) {
    // witness: the same mapping runs on an input that meets the function's counts
    assertThatCode(() -> run(document, fits)).doesNotThrowAnyException();

    assertRuntimeError(() -> run(document, misfits), code, box);
    assertThatThrownBy(() -> run(document, misfits)).hasMessageContaining(function);
  }

  /**
   * F-37 (ADR-0006 as amended with #133): createIf over a value that is no boolean stops the run
   * with {@code po:CREATEIF} at its box, PO's text after the place; false and 0 give SUPPRESS.
   */
  @Test
  void createIfOverAValueThatIsNoBooleanRaisesAtItsBox() throws Exception {
    TargetDocument document =
        document(
            OUT,
            node(ITEM, 2, createIf("c1", source("a1", 1, ORDERS, ORDER, ID)), 0, UNBOUNDED, "t1"));
    // witness: false and 0 give SUPPRESS, so only the true Id creates an Item
    assertThat(run(document, orders(order("false"), order("TRUE"), order("0"))))
        .isEqualTo(DECLARATION + "<Out><Item/></Out>");

    assertRuntimeError(
        () -> run(document, orders(order("1"), order("no"), order("skip"))), "po:CREATEIF", "c1");
    assertThatThrownBy(() -> run(document, orders(order("1"), order("no"), order("skip"))))
        .hasMessage("the function box c1: Cannot cast 'no' to boolean");
  }

  // --- generated nested targets against a reference of ADR-0006 "Target fields"

  /** An Order: its Id, or null when it has none, and its Lines. */
  private record GeneratedOrder(String id, List<GeneratedLine> lines) {}

  /** A Line: its Sku and its Qty, each null when absent. */
  private record GeneratedLine(String sku, String qty) {}

  private static String pick(RandomGenerator random, String... values) {
    return values[random.nextInt(values.length)];
  }

  private static List<GeneratedOrder> generatedOrders(RandomGenerator random) {
    List<GeneratedOrder> orders = new ArrayList<>();
    int count = random.nextInt(4);
    for (int o = 1; o <= count; o++) {
      List<GeneratedLine> lines = new ArrayList<>();
      int lineCount = random.nextInt(4);
      for (int l = 1; l <= lineCount; l++) {
        String sku = random.nextInt(4) == 0 ? null : pick(random, "1", "0", "TRUE", "s" + o + l);
        String qty = random.nextInt(3) == 0 ? null : pick(random, "1", "true", "q" + o + l);
        lines.add(new GeneratedLine(sku, qty));
      }
      String id = random.nextInt(4) == 0 ? null : pick(random, "1", "0", "o" + o);
      orders.add(new GeneratedOrder(id, lines));
    }
    return orders;
  }

  private static String xml(List<GeneratedOrder> orders) {
    StringBuilder xml = new StringBuilder("<Orders>");
    for (GeneratedOrder order : orders) {
      xml.append("<Order>");
      if (order.id() != null) {
        xml.append("<Id>").append(order.id()).append("</Id>");
      }
      for (GeneratedLine line : order.lines()) {
        xml.append("<Line>");
        if (line.sku() != null) {
          xml.append("<Sku>").append(line.sku()).append("</Sku>");
        }
        if (line.qty() != null) {
          xml.append("<Qty>").append(line.qty()).append("</Qty>");
        }
        xml.append("</Line>");
      }
      xml.append("</Order>");
    }
    return xml.append("</Orders>").toString();
  }

  /** {@code value}, or {@code false} where it is no boolean that createIf casts (F-37). */
  private static String castable(String value) {
    return value == null || value.equals("1") || value.equals("0") || value.equalsIgnoreCase("true")
        ? value
        : "false";
  }

  /**
   * {@code orders} with the field of each level that reads it through createIf cast to booleans PO
   * accepts: an Id, Sku or Qty that is no boolean becomes {@code false}, which createIf reads as
   * SUPPRESS, as it read that value before #133, so no run raises {@code po:CREATEIF}. A field a
   * level reads as it is keeps its values, which tell its contexts apart.
   */
  private static List<GeneratedOrder> castable(
      List<GeneratedOrder> orders, boolean ids, boolean skus, boolean qtys) {
    return orders.stream()
        .map(
            order ->
                new GeneratedOrder(
                    ids ? castable(order.id()) : order.id(),
                    order.lines().stream()
                        .map(
                            line ->
                                new GeneratedLine(
                                    skus ? castable(line.sku()) : line.sku(),
                                    qtys ? castable(line.qty()) : line.qty()))
                        .toList()))
        .toList();
  }

  /**
   * A queue of the reference: contexts of values, SUPPRESS as null. Its values are booleans
   * createIf casts ({@link #castable(List, boolean, boolean, boolean)}).
   */
  private static List<List<String>> referenceCreateIf(List<List<String>> queue) {
    return queue.stream()
        .map(
            context ->
                context.stream()
                    .map(v -> "1".equals(v) || "true".equalsIgnoreCase(v) ? "" : null)
                    .toList())
        .toList();
  }

  /**
   * Id in the root's context: one context, as the root occurs once (#2 §1); Sku in Order's context:
   * one per Order; Sku in the root's: one; Qty in Line's: one per Line; Qty in Order's: one per
   * Order. Absent fields add no slot.
   */
  private static List<List<String>> referenceQueue(
      String field, int depth, List<GeneratedOrder> orders) {
    List<List<String>> contexts = new ArrayList<>();
    List<String> root = new ArrayList<>();
    for (GeneratedOrder order : orders) {
      List<String> inOrder = new ArrayList<>();
      if (field.equals("Id") && order.id() != null) {
        root.add(order.id());
      }
      for (GeneratedLine line : order.lines()) {
        String value = field.equals("Sku") ? line.sku() : field.equals("Qty") ? line.qty() : null;
        if (depth == 3) {
          contexts.add(value == null ? List.of() : List.of(value));
        } else if (value != null) {
          (depth == 1 ? root : inOrder).add(value);
        }
      }
      if (depth == 2) {
        contexts.add(inOrder);
      }
    }
    if (depth == 1) {
      contexts.add(root);
    }
    return contexts;
  }

  /** One generated target field: its IR queue, the reference's queue, min and max. */
  private record Level(
      Name name, String mapping, Expr queue, List<List<String>> reference, long min, long max) {}

  /** The reference's {@code po:MINOCCURS} at {@code mapping}. */
  private static final class ShortOfMin extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String mapping;

    ShortOfMin(String mapping) {
      super(mapping);
      this.mapping = mapping;
    }
  }

  /**
   * The reference, from ADR-0006 "Target fields": {@code levels[k]} reads context p of its queue, p
   * the slot number of its parent occurrence; it raises po:MINOCCURS when that context, empty when
   * missing, has fewer values than min, SUPPRESS not counted, before anything of it is written; it
   * creates its values in order until max are created; each slot's number is its position across
   * all contexts of the queue, and the next level reads that context.
   */
  private static void reference(List<Level> levels, int k, int parentSlot, StringBuilder out) {
    Level level = levels.get(k);
    List<List<String>> queue = level.reference();
    List<String> context = parentSlot <= queue.size() ? queue.get(parentSlot - 1) : List.of();
    if (context.stream().filter(Objects::nonNull).count() < level.min()) {
      throw new ShortOfMin(level.mapping());
    }
    int before = 0;
    for (int c = 0; c < parentSlot - 1 && c < queue.size(); c++) {
      before += queue.get(c).size();
    }
    long created = 0;
    for (int s = 0; s < context.size(); s++) {
      String value = context.get(s);
      if (value == null || created == level.max()) {
        continue;
      }
      created++;
      String name = level.name().localName();
      StringBuilder content = new StringBuilder();
      if (k == levels.size() - 1) {
        content.append(value);
      } else {
        reference(levels, k + 1, before + s + 1, content);
      }
      out.append(
          content.isEmpty() ? "<" + name + "/>" : "<" + name + ">" + content + "</" + name + ">");
    }
  }

  private static long max(RandomGenerator random, long min) {
    long[] maxes = {1, 2, 3, UNBOUNDED};
    long max;
    do {
      max = maxes[random.nextInt(maxes.length)];
    } while (max < Math.max(min, 1));
    return max;
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /**
   * A seeded Doc/Grp/Row/Val target over seeded Orders: each level's queue a source field in one of
   * its contexts or createIf over it, with seeded min and max, against the reference. The generator
   * makes SUPPRESS, truncated slots, missing and empty contexts and min shortfalls.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void generatedNestedTargetsMatchTheReference(long seed) throws Exception {
    RandomGenerator random = new SplittableRandom(seed);
    List<GeneratedOrder> drawn = generatedOrders(random);
    // the draws in the order they were made before #133, so each seed makes the same target
    long grpMin = random.nextInt(2);
    boolean grpCreateIf = !random.nextBoolean();
    long grpMax = max(random, grpMin);
    long rowMin = random.nextInt(3);
    int rowChoice = random.nextInt(3);
    long rowMax = max(random, rowMin);
    long valMin = random.nextInt(2);
    int valChoice = random.nextInt(3);
    long valMax = max(random, valMin);
    List<GeneratedOrder> orders = castable(drawn, grpCreateIf, rowChoice == 1, valChoice == 2);
    List<Level> levels = new ArrayList<>();
    // Grp: Id in the root's context, or createIf over it
    SourceQueue ids = source("a1", 1, ORDERS, ORDER, ID);
    List<List<String>> idQueue = referenceQueue("Id", 1, orders);
    levels.add(
        grpCreateIf
            ? new Level(GRP, "t1", createIf("c1", ids), referenceCreateIf(idQueue), grpMin, grpMax)
            : new Level(GRP, "t1", ids, idQueue, grpMin, grpMax));
    // Row: Sku in Order's context, createIf over it, or Sku in the root's context
    int rowDepth = rowChoice == 2 ? 1 : 2;
    SourceQueue skus = source("a2", rowDepth, ORDERS, ORDER, LINE, SKU);
    List<List<String>> skuQueue = referenceQueue("Sku", rowDepth, orders);
    levels.add(
        rowChoice == 1
            ? new Level(
                ROW, "t2", createIf("c2", skus), referenceCreateIf(skuQueue), rowMin, rowMax)
            : new Level(ROW, "t2", skus, skuQueue, rowMin, rowMax));
    // Val: Qty in Line's context, in Order's context, or createIf over Qty in Line's context
    int valDepth = valChoice == 1 ? 2 : 3;
    SourceQueue qtys = source("a3", valDepth, ORDERS, ORDER, LINE, QTY);
    List<List<String>> qtyQueue = referenceQueue("Qty", valDepth, orders);
    levels.add(
        valChoice == 2
            ? new Level(
                VAL, "t3", createIf("c3", qtys), referenceCreateIf(qtyQueue), valMin, valMax)
            : new Level(VAL, "t3", qtys, qtyQueue, valMin, valMax));
    Level val = levels.get(2);
    Level row = levels.get(1);
    Level grp = levels.get(0);
    TargetDocument document =
        document(
            DOC,
            node(
                GRP,
                2,
                grp.queue(),
                grp.min(),
                grp.max(),
                "t1",
                node(
                    ROW,
                    3,
                    row.queue(),
                    row.min(),
                    row.max(),
                    "t2",
                    leaf(VAL, 4, val.queue(), val.min(), val.max(), "t3"))));
    String input = xml(orders);
    StringBuilder content = new StringBuilder();
    String failing = null;
    try {
      reference(levels, 0, 1, content);
    } catch (ShortOfMin shortOfMin) {
      failing = shortOfMin.mapping;
    }

    if (failing != null) {
      assertRuntimeError(() -> run(document, input), MINOCCURS, failing);
    } else {
      String root = content.isEmpty() ? "<Doc/>" : "<Doc>" + content + "</Doc>";
      assertThat(run(document, input)).as("input %s", input).isEqualTo(DECLARATION + root);
    }
  }
}
