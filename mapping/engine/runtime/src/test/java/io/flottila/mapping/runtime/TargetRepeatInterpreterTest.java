package io.flottila.mapping.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetRepeat;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The interpreter on {@code TargetRepeat} and on the queue of a source structure field (ADR-0006
 * "Target fields", ADR-0008; #63), with IR built by hand as lowering gives it.
 */
class TargetRepeatInterpreterTest {

  /** The time every run of this class starts at; no function here reads it. */
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-04T12:00:00Z");

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final long UNBOUNDED = Long.MAX_VALUE;
  private static final Name ORDERS = local("Orders");
  private static final Name ORDER = local("Order");
  private static final Name ID = local("Id");
  private static final Name LINE = local("Line");
  private static final Name QTY = local("Qty");
  private static final Name DOC = local("Doc");
  private static final Name ITEM = local("Item");
  private static final Name TWIN = local("Twin");
  private static final AuthoringId T1 = new AuthoringId("t1");
  private static final AuthoringId T2 = new AuthoringId("t2");
  private static final AuthoringId TWIN_ORIGIN = new AuthoringId("/Doc/Item/Twin");

  private static Name local(String name) {
    return new Name("", name);
  }

  private static VarRef integer(String name, AuthoringId origin) {
    return new VarRef(name, AtomicType.INTEGER, origin);
  }

  private static TargetDocument document(Expr... content) {
    TargetDocument document = new TargetDocument("", DOC, "root", List.of(content));
    IrVerifier.verify(document);
    return document;
  }

  /** Item per slot of {@code queue} under the root, with {@code children}. */
  private static TargetSlots items(Expr queue, Expr... children) {
    return new TargetSlots(
        queue,
        integer("root", T1),
        0,
        UNBOUNDED,
        "slot2",
        "value2",
        new ElementConstructor("", ITEM, List.of(children), T1),
        T1);
  }

  /** Qty per slot of the Qty queue in context Line, under the slot number {@code slot3}. */
  private static TargetSlots quantities() {
    return new TargetSlots(
        new SourceQueue(List.of(ORDERS, ORDER, LINE, QTY), 3, new AuthoringId("a2")),
        integer("slot3", T2),
        0,
        UNBOUNDED,
        "slot4",
        "value4",
        new ElementConstructor(
            "",
            QTY,
            List.of(new TextConstructor(new VarRef("value4", AtomicType.STRING, T2), T2)),
            T2),
        T2);
  }

  private static String run(TargetDocument document, String input) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Interpreter.compile(document, false)
        .run(new ByteArrayInputStream(input.getBytes(UTF_8)), out, new Tracer(), NOW);
    return out.toString(UTF_8);
  }

  private static final String INPUT =
      "<Orders><Order><Id>o1</Id><Line><Qty>1</Qty></Line><Line><Qty>2</Qty></Line>"
          + "<Line><Qty>3</Qty></Line></Order><Order/><Order><Id>o3</Id>"
          + "<Line><Qty>4</Qty></Line></Order></Orders>";

  /**
   * Twin, 2 copies under each Item: the j-th under Item slot p has the slot number (p - 1) * 2 + j,
   * so Item 1's Twins read Qty contexts 1 and 2, Item 2's contexts 3 and 4. Item is per Order Id,
   * so the Order without one gives no Item.
   */
  @Test
  void theCopiesOfTargetRepeatAreNumberedAcrossItsParentsSlots() throws Exception {
    TargetDocument document =
        document(
            items(
                new SourceQueue(List.of(ORDERS, ORDER, ID), 1, new AuthoringId("a1")),
                new TargetRepeat(
                    2,
                    integer("slot2", TWIN_ORIGIN),
                    "slot3",
                    new ElementConstructor("", TWIN, List.of(quantities()), TWIN_ORIGIN),
                    TWIN_ORIGIN)));

    assertThat(run(document, INPUT))
        .isEqualTo(
            DECLARATION
                + "<Doc><Item><Twin><Qty>1</Qty></Twin><Twin><Qty>2</Qty></Twin></Item>"
                + "<Item><Twin><Qty>3</Qty></Twin><Twin><Qty>4</Qty></Twin></Item></Doc>");
  }

  /**
   * #83: a traced TargetRepeat reports its copies under each parent slot at its origin, before its
   * children report, and writes what the untraced one writes; the untraced one reports nothing. The
   * Twins of Item 1 and Item 2, two each, so the Qtys under them run under slots 1, 2 and 3, 4.
   */
  @Test
  void aTracedTargetRepeatReportsItsCopiesPerParentSlot() throws Exception {
    TargetDocument document =
        document(
            items(
                new SourceQueue(List.of(ORDERS, ORDER, ID), 1, new AuthoringId("a1")),
                new TargetRepeat(
                    2,
                    integer("slot2", TWIN_ORIGIN),
                    "slot3",
                    new ElementConstructor("", TWIN, List.of(quantities()), TWIN_ORIGIN),
                    TWIN_ORIGIN)));
    Tracer untraced = new Tracer();
    Tracer tracer = new Tracer();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    Interpreter.compile(document, false)
        .run(
            new ByteArrayInputStream(INPUT.getBytes(UTF_8)),
            new ByteArrayOutputStream(),
            untraced,
            NOW);
    Interpreter.compile(document, true)
        .run(new ByteArrayInputStream(INPUT.getBytes(UTF_8)), out, tracer, NOW);

    assertThat(untraced.reports()).isEmpty();
    assertThat(out.toString(UTF_8)).isEqualTo(run(document, INPUT));
    assertThat(tracer.reports())
        .filteredOn(Tracer.Repeat.class::isInstance)
        .containsExactly(
            new Tracer.Repeat(TWIN_ORIGIN, 1, 2, false),
            new Tracer.Repeat(TWIN_ORIGIN, 2, 2, false));
    assertThat(tracer.reports())
        .filteredOn(Tracer.FieldReport.class::isInstance)
        .map(Tracer.FieldReport.class::cast)
        .extracting(r -> r.field().value() + "@" + r.parentSlot())
        .containsExactly(
            "t1@1", "/Doc/Item/Twin@1", "t2@1", "t2@2", "/Doc/Item/Twin@2", "t2@3", "t2@4");
  }

  /**
   * The queue of the structure field Order holds one {@code ""} per occurrence (ADR-0008), so the
   * empty Order makes an Item too, and the descendants of Order are still read for the other queue.
   */
  @Test
  void aStructureFieldsQueueHoldsAnEmptyStringPerOccurrence() throws Exception {
    TargetDocument document =
        document(
            items(
                new SourceQueue(List.of(ORDERS, ORDER), 1, true, new AuthoringId("a1")),
                new TargetSlots(
                    new SourceQueue(List.of(ORDERS, ORDER, ID), 2, new AuthoringId("a2")),
                    integer("slot2", T2),
                    0,
                    1,
                    "slot3",
                    "value3",
                    new ElementConstructor(
                        "",
                        ID,
                        List.of(
                            new TextConstructor(new VarRef("value3", AtomicType.STRING, T2), T2)),
                        T2),
                    T2)));

    assertThat(run(document, INPUT))
        .isEqualTo(
            DECLARATION + "<Doc><Item><Id>o1</Id></Item><Item/><Item><Id>o3</Id></Item></Doc>");
  }

  // --- the value of a generating structure field (ADR-0006 as amended by #83)

  private static final Name GEN = local("Gen");
  private static final AuthoringId T3 = new AuthoringId("t3");
  private static final AuthoringId C1 = new AuthoringId("c1");
  private static final AuthoringId ADD = new AuthoringId("add1");

  /** The constant c1 with {@code value}, an atomic FunCall at its box. */
  private static FunCall constant(String value) {
    return new FunCall(
        new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING),
        List.of(new Literal(value, C1)),
        C1);
  }

  /**
   * Item per Order Id (t1), and under each Item the structure field Gen {@code copies} times from
   * the generating {@code value} (t3), holding Qty per slot of Qty's queue in context Line (t2).
   */
  private static TargetDocument generating(long copies, Expr value) {
    return document(
        items(
            new SourceQueue(List.of(ORDERS, ORDER, ID), 1, new AuthoringId("a1")),
            new TargetRepeat(
                copies,
                integer("slot2", T3),
                "slot3",
                Optional.of(value),
                new ElementConstructor("", GEN, List.of(quantities()), T3),
                T3)));
  }

  private static String run(TargetDocument document, String input, boolean traced, Tracer tracer)
      throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Interpreter.compile(document, traced)
        .run(new ByteArrayInputStream(input.getBytes(UTF_8)), out, tracer, NOW);
    return out.toString(UTF_8);
  }

  /** A report in the order checked: Gen's copies, c1's value and Qty's parent slots only. */
  private static String describe(Tracer.Report report) {
    return switch (report) {
      case Tracer.Repeat r ->
          "repeat " + r.field().value() + "@" + r.parentSlot() + "x" + r.copies();
      case Tracer.Atomic a -> "atomic " + a.box().value() + "=" + a.value();
      case Tracer.Occurrences o when o.field().equals(T2) -> "qty@" + o.parentSlot();
      case Tracer.Occurrences o -> null;
      case Tracer.Value v -> null;
    };
  }

  /**
   * Per seed, copies and Items, the slot numbers of Qty under each Item's copies, the Items split
   * by {@code |}: copy j of Item p is slot (p - 1) * copies + j, as ADR-0006 numbers a {@code
   * TargetRepeat}'s copies.
   */
  static Stream<Arguments> repeats() {
    return Stream.of(
        Arguments.of(1L, 1L, 0, ""),
        Arguments.of(2L, 1L, 1, "1"),
        Arguments.of(3L, 1L, 2, "1 | 2"),
        Arguments.of(4L, 1L, 3, "1 | 2 | 3"),
        Arguments.of(5L, 1L, 4, "1 | 2 | 3 | 4"),
        Arguments.of(6L, 1L, 5, "1 | 2 | 3 | 4 | 5"),
        Arguments.of(7L, 2L, 0, ""),
        Arguments.of(8L, 2L, 1, "1 2"),
        Arguments.of(9L, 2L, 2, "1 2 | 3 4"),
        Arguments.of(10L, 2L, 3, "1 2 | 3 4 | 5 6"),
        Arguments.of(11L, 2L, 4, "1 2 | 3 4 | 5 6 | 7 8"),
        Arguments.of(12L, 2L, 5, "1 2 | 3 4 | 5 6 | 7 8 | 9 10"),
        Arguments.of(13L, 3L, 0, ""),
        Arguments.of(14L, 3L, 1, "1 2 3"),
        Arguments.of(15L, 3L, 2, "1 2 3 | 4 5 6"),
        Arguments.of(16L, 3L, 3, "1 2 3 | 4 5 6 | 7 8 9"),
        Arguments.of(17L, 3L, 4, "1 2 3 | 4 5 6 | 7 8 9 | 10 11 12"),
        Arguments.of(18L, 3L, 5, "1 2 3 | 4 5 6 | 7 8 9 | 10 11 12 | 13 14 15"),
        Arguments.of(19L, 4L, 0, ""),
        Arguments.of(20L, 4L, 1, "1 2 3 4"),
        Arguments.of(21L, 4L, 2, "1 2 3 4 | 5 6 7 8"),
        Arguments.of(22L, 4L, 3, "1 2 3 4 | 5 6 7 8 | 9 10 11 12"),
        Arguments.of(23L, 4L, 4, "1 2 3 4 | 5 6 7 8 | 9 10 11 12 | 13 14 15 16"),
        Arguments.of(24L, 4L, 5, "1 2 3 4 | 5 6 7 8 | 9 10 11 12 | 13 14 15 16 | 17 18 19 20"));
  }

  /**
   * For copies 1 to 4 and 0 to 5 Items, each Item reports Gen's copies, then per copy c1's value,
   * before the copy's children: Qty under the copy's slot number. So the value is evaluated once
   * per copy, copies * Items times, never once per run, and the traced run writes what the untraced
   * one writes. The value and the Orders' 0 to 2 Lines come from the seed.
   */
  @ParameterizedTest(name = "seed {0}: copies {1}, Items {2}")
  @MethodSource("repeats")
  void aGeneratingStructureFieldReportsItsValueOncePerCopy(
      long seed, long copies, int parents, String slots) throws Exception {
    Random random = new Random(seed);
    String value = "g" + random.nextInt(1000);
    StringBuilder input = new StringBuilder("<Orders>");
    for (int order = 1; order <= parents; order++) {
      input.append("<Order><Id>o").append(order).append("</Id>");
      for (int line = random.nextInt(3); line > 0; line--) {
        input.append("<Line><Qty>").append(random.nextInt(10)).append("</Qty></Line>");
      }
      input.append("</Order>");
    }
    input.append("</Orders>");
    String[] items = slots.isEmpty() ? new String[0] : slots.split(" \\| ");
    assertThat(items).as("the row's Items").hasSize(parents);
    List<String> expected = new ArrayList<>();
    for (int p = 1; p <= items.length; p++) {
      expected.add("repeat t3@" + p + "x" + copies);
      for (String slot : items[p - 1].split(" ")) {
        expected.add("atomic c1=" + value);
        expected.add("qty@" + slot);
      }
    }
    TargetDocument document = generating(copies, constant(value));
    Tracer tracer = new Tracer();

    String traced = run(document, input.toString(), true, tracer);

    assertThat(tracer.reports().stream().map(r -> describe(r)).filter(Objects::nonNull))
        .as("input %s", input)
        .containsExactlyElementsOf(expected);
    assertThat(traced).isEqualTo(run(document, input.toString()));
  }

  /**
   * The value is evaluated, never written: Gen, from the constant g, holds its Qty only, traced or
   * not, two copies under each of the two Items, numbered as any TargetRepeat's.
   */
  @Test
  void aGeneratingStructureFieldWritesNoText() throws Exception {
    TargetDocument document = generating(2, constant("g"));
    String expected =
        DECLARATION
            + "<Doc><Item><Gen><Qty>1</Qty></Gen><Gen><Qty>2</Qty></Gen></Item>"
            + "<Item><Gen><Qty>3</Qty></Gen><Gen><Qty>4</Qty></Gen></Item></Doc>";

    assertThat(run(document, INPUT)).isEqualTo(expected);
    assertThat(run(document, INPUT, true, new Tracer())).isEqualTo(expected);
  }

  /**
   * The value is evaluated in every compile, not only a traced one (ADR-0018 l.48-49): add over x
   * and 1 raises err:FORG0001 at the add box in an untraced compile, as in a traced one. Witness:
   * the constant g runs.
   */
  @Test
  void anUntracedCompileRaisesTheValuesDataError() throws Exception {
    FunCall add =
        new FunCall(
            new FunctionRef(
                "add", List.of(AtomicType.STRING, AtomicType.STRING), AtomicType.STRING),
            List.of(new Literal("x", ADD), new Literal("1", ADD)),
            ADD);
    assertThat(run(generating(1, constant("g")), INPUT, false, new Tracer()))
        .startsWith(DECLARATION);

    for (boolean traced : List.of(false, true)) {
      MappingRunException error =
          catchThrowableOfType(
              MappingRunException.class,
              () -> run(generating(1, add), INPUT, traced, new Tracer()));

      assertThat(error).as("traced %s", traced).isNotNull();
      assertThat(error.code()).isEqualTo(RuntimeErrors.FORG0001);
      assertThat(error.authoringId()).isEqualTo(ADD);
    }
  }

  /** An output that interrupts the thread writing it at its first write. */
  private static final class InterruptingOutput extends OutputStream {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean interrupted;

    @Override
    public void write(int b) {
      interruptOnce();
      bytes.write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
      interruptOnce();
      bytes.write(buffer, offset, length);
    }

    private void interruptOnce() {
      if (!interrupted) {
        interrupted = true;
        Thread.currentThread().interrupt();
      }
    }
  }

  /** An interrupt with the first bytes of output stops the loop over copies (ADR-0021). */
  @Test
  void anInterruptStopsTheLoopOverCopies() throws Exception {
    AuthoringId origin = new AuthoringId("/Doc/Twin");
    TargetDocument twins =
        document(
            new TargetRepeat(
                200_000,
                integer("root", origin),
                "slot2",
                new ElementConstructor("", TWIN, List.of(), origin),
                origin));
    String all = DECLARATION + "<Doc>" + "<Twin/>".repeat(200_000) + "</Doc>";
    // witness: without the interrupt the same run writes every copy
    assertThat(run(twins, "<Orders/>")).isEqualTo(all);
    InterruptingOutput out = new InterruptingOutput();

    try {
      assertThatThrownBy(
              () ->
                  Interpreter.compile(twins, false)
                      .run(
                          new ByteArrayInputStream("<Orders/>".getBytes(UTF_8)),
                          out,
                          new Tracer(),
                          NOW))
          .isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }

    assertThat(out.bytes.size())
        .as("bytes written by the cancelled run")
        .isLessThan(all.length() / 2);
  }
}
