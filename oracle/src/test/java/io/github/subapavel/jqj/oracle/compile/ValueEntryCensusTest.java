package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.oracle.compile.SpecialiseCensusTest.Filter;
import io.github.subapavel.jqj.oracle.diff.Corpus;
import io.github.subapavel.jqj.oracle.jqtest.JqTestCase;
import io.github.subapavel.jqj.oracle.jqtest.JqTestCorpus;
import io.github.subapavel.jqj.query.analysis.CallKind;
import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.Compiler;
import io.github.subapavel.jqj.query.compile.Executable;
import io.github.subapavel.jqj.query.compile.RunForm;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Program;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * The value entry's jumps over the census (#118 design comment, "valueEntryTargetsAreValue: a property over the
 * census"; ADR-0047 "Amendment (ticket #118)", "Where a jump is taken": the entry loops of {@code CallOp.run}, {@code
 * CallOp.eval} and {@code CallOp.Active.resume} take a pending jump, split so since 180b412; ADR-0047 "Amendment
 * (ticket #116)", "RunForm"). Each loop runs the next body in its own form. The value-form loop, {@code eval}, returns
 * one value and has no sink, so a jump taken in it must re-enter a body whose root runs in the value form: a jump whose
 * site runs in the value form never lands in a push-form body.
 *
 * <p>The filters are SpecialiseCensusTest's census, read through its own methods: each jqtest case's program, each
 * differential case's {@code filter} key and each builtin.jq definition called as {@code name(.; …)}, resolved as {@code
 * JqEngine.compile} does and compiled OPTIMISED (the conservative compile stores no tail kind, ADR-0018). A filter jqj
 * does not compile is left out, as in the census; a compile that throws is logged and left out, and the census fails on
 * it. For each body of the frozen program and each freeze id whose {@link Executable#callKind(int, int)} is TAIL_SELF
 * or TAIL_GROUP, the site's form is {@link Executable#form(int, int)} and its target's root form is {@code
 * form(c.fn(), 0)}: freeze id 0 is a body's root (Numbering's pre-order).
 *
 * <p>The verdict: no site of VALUE form has a target whose root is PUSH. Witness that the property is not vacuous: at
 * least {@link #WITNESS} value-form sites of each tail kind are checked, each the count observed at impl/118 18070a4
 * rounded down. The counts and every failing site go to {@link #LOG} under the repository root, before the verdict. A
 * second verdict: no push-form site lies in a body whose root is VALUE (0 observed); such a jump would be taken in a
 * value entry too, and the property above does not see it. Its witness: at least {@link #PUSH_WITNESS} push-form sites
 * are checked.
 */
class ValueEntryCensusTest {

  /** The log of the counts and the failing sites, below the repository root. */
  static final String LOG = ".logs/118-value-entry-census.log";

  /**
   * The value-form jump sites of each tail kind the census must check at least: the counts observed at impl/118 18070a4
   * by #118's test-author, rounded down to two significant figures, so a census that checks no jump of a kind fails.
   * TAIL_SELF: 191 observed (of 392 sites, 201 push form). TAIL_GROUP: 4 observed (of 6), the two jumps of each of
   * calls/mutual-recursion-through-a-nested-def and tail/group-five-million. A ticket that adds or removes jumps moves
   * the counts; lowering a witness needs the new observed count in the commit.
   */
  static final Map<CallKind, Integer> WITNESS = Map.of(CallKind.TAIL_SELF, 190, CallKind.TAIL_GROUP, 4);

  /**
   * The push-form jump sites the second verdict must check at least, so that it is not vacuous: 201 observed at impl/118
   * 18070a4 and 205 at 1a9b028 (TAIL_SELF 203, TAIL_GROUP 2), rounded down to two significant figures. A mutant that
   * gives every caller a VALUE root makes the verdict list all 205 (#118's implementer, .logs/118-census-mutant.log). Lowering it needs the new
   * observed count in the commit.
   */
  static final int PUSH_WITNESS = 200;

  /**
   * One jump site of a compiled census filter: the Call {@code id} of the body {@code function} (-1 for main), its kind,
   * its callee's place {@code target}, its own form, its own body's root form and its target's root form.
   */
  record Site(Filter filter, int function, int id, CallKind kind, int target, RunForm form, RunForm callerForm, RunForm targetForm) {
    String shown() {
      return filter.id() + " fn" + function + "#" + id + " " + kind + " -> fn" + target + " site=" + form + " caller root=" + callerForm + " target root=" + targetForm + " " + abbreviated(filter.text());
    }
  }

  @Test
  void valueEntryTargetsAreValue() {
    List<Filter> filters = new ArrayList<>();
    for (JqTestCase c : JqTestCorpus.cases()) {
      filters.add(new Filter(c.id().substring(0, c.id().indexOf('#')), c.id(), c.program()));
    }
    filters.addAll(SpecialiseCensusTest.differential(new TreeMap<>()));
    filters.addAll(SpecialiseCensusTest.prelude());

    int notCompiled = 0;
    int compiled = 0;
    List<String> threw = new ArrayList<>();
    List<String> notCalls = new ArrayList<>();
    List<Site> sites = new ArrayList<>();
    for (Filter f : filters) {
      Program program;
      try {
        program = SpecialiseCensusTest.resolve(f.text());
      } catch (JqCompileException e) {
        notCompiled++;
        continue;
      }
      Executable executable;
      try {
        executable = Compiler.compile(program, 0, CompileMode.OPTIMISED);
      } catch (RuntimeException e) {
        threw.add(f.id() + ": " + e);
        continue;
      }
      compiled++;
      Numbering numbering = executable.frozen().numbering();
      int functions = executable.frozen().program().functions().size();
      for (int body = -1; body < functions; body++) {
        List<Node> nodes = numbering.nodes(body);
        for (int id = 0; id < nodes.size(); id++) {
          CallKind kind = executable.callKind(body, id);
          if (kind != CallKind.TAIL_SELF && kind != CallKind.TAIL_GROUP) {
            continue;
          }
          if (nodes.get(id) instanceof Call c) {
            sites.add(new Site(f, body, id, kind, c.fn(), executable.form(body, id), executable.form(body, 0), executable.form(c.fn(), 0)));
          } else {
            notCalls.add(f.id() + " fn" + body + "#" + id + " " + kind + " on a " + nodes.get(id).getClass().getSimpleName());
          }
        }
      }
    }

    Map<CallKind, int[]> byForm = new EnumMap<>(CallKind.class);
    byForm.put(CallKind.TAIL_SELF, new int[RunForm.values().length]);
    byForm.put(CallKind.TAIL_GROUP, new int[RunForm.values().length]);
    for (Site s : sites) {
      byForm.get(s.kind())[s.form().ordinal()]++;
    }
    List<Site> pushTargets = sites.stream().filter(s -> s.form() == RunForm.VALUE && s.targetForm() != RunForm.VALUE).toList();
    log(filters.size(), notCompiled, compiled, threw, notCalls, sites, byForm, pushTargets);

    SoftAssertions soft = new SoftAssertions();
    soft.assertThat(notCalls).as("sites whose call kind is a tail kind but whose node is no Call").isEmpty();
    soft.assertThat(pushTargets.stream().map(Site::shown).toList())
        .as("value-form jump sites whose target's root is not VALUE, a value-form jump landing in a push-form body (log %s)", LOG)
        .isEmpty();
    soft.assertThat(sites.stream().filter(s -> s.form() == RunForm.PUSH && s.callerForm() == RunForm.VALUE).map(Site::shown).toList())
        .as("push-form jump sites in a body whose root is VALUE, a jump taken in a value entry the verdict above does not see (log %s)", LOG)
        .isEmpty();
    soft.assertThat(sites.stream().filter(s -> s.form() == RunForm.PUSH).count())
        .as("witness: the push-form sites the verdict above checks (log %s)", LOG)
        .isGreaterThanOrEqualTo(PUSH_WITNESS);
    for (Map.Entry<CallKind, Integer> w : WITNESS.entrySet()) {
      soft.assertThat(byForm.get(w.getKey())[RunForm.VALUE.ordinal()])
          .as("witness: the value-form %s sites the census checks (log %s)", w.getKey(), LOG)
          .isGreaterThanOrEqualTo(w.getValue());
    }
    soft.assertAll();
  }

  /** {@code text} on one line, at most 100 characters. */
  private static String abbreviated(String text) {
    String line = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
    return line.length() <= 100 ? line : line.substring(0, 97) + "...";
  }

  private static void log(
      int filters, int notCompiled, int compiled, List<String> threw, List<String> notCalls, List<Site> sites, Map<CallKind, int[]> byForm, List<Site> pushTargets) {
    List<String> lines = new ArrayList<>();
    lines.add("# ValueEntryCensusTest (#118): SpecialiseCensusTest's census compiled OPTIMISED, every TAIL_SELF and TAIL_GROUP site");
    lines.add("filters\t" + filters);
    lines.add("not compiled\t" + notCompiled);
    lines.add("compiled\t" + compiled);
    lines.add("compile threw\t" + threw.size());
    threw.forEach(t -> lines.add("  " + t));
    lines.add("tail kinds on no Call\t" + notCalls.size());
    notCalls.forEach(n -> lines.add("  " + n));
    lines.add("jump sites\t" + sites.size());
    for (Map.Entry<CallKind, int[]> e : byForm.entrySet()) {
      lines.add(e.getKey() + "\tvalue " + e.getValue()[RunForm.VALUE.ordinal()] + "\tpush " + e.getValue()[RunForm.PUSH.ordinal()]);
    }
    List<Site> pushInValue = sites.stream().filter(s -> s.form() == RunForm.PUSH && s.callerForm() == RunForm.VALUE).toList();
    lines.add("push-form sites in a body whose root is VALUE\t" + pushInValue.size());
    pushInValue.forEach(s -> lines.add("  " + s.shown()));
    lines.add("value-form sites whose target's root is not VALUE\t" + pushTargets.size());
    pushTargets.forEach(s -> lines.add("  " + s.shown()));
    lines.add("value-form sites, one line each");
    sites.stream().filter(s -> s.form() == RunForm.VALUE).forEach(s -> lines.add("  " + s.shown()));
    Path log = Corpus.root().resolve(LOG);
    try {
      Files.createDirectories(log.getParent());
      Files.write(log, lines, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    System.out.println("ValueEntryCensusTest: " + sites.size() + " jump sites, " + pushTargets.size() + " value-form sites with a push-form target; log " + log);
  }
}
