package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.api.TestRunResult;
import io.flottila.mapping.api.Trace;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.AtAuthoringId;
import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Location;
import io.flottila.mapping.oracle.CaseFiles.CorpusCase;
import io.flottila.mapping.runtime.MappingRunException;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xmlunit.diff.Diff;

/**
 * The golden-corpus oracle (ADR-0018): one dynamic test per case, named by its case id, so the
 * surefire report names it {@code cases() <case id>} as the ratchet reads it
 * (scripts/ratchet.java). A case passes only when its four metamorphic variants, optimised and
 * unoptimised IR each with and without a tracer, give byte-identical output and that output is
 * similar to {@code expected.xml}.
 */
class GoldenCorpusTest {

  private static final List<CompileOptions> VARIANTS =
      List.of(
          new CompileOptions(true, false),
          new CompileOptions(true, true),
          new CompileOptions(false, false),
          new CompileOptions(false, true));

  @TestFactory
  Stream<DynamicTest> cases() {
    List<CorpusCase> cases = CaseFiles.all();
    assertThat(cases).as("cases under %s", CaseFiles.cases()).isNotEmpty();
    return cases.stream().map(c -> dynamicTest(c.id(), () -> check(c)));
  }

  /**
   * Whether {@code c} expects a runtime error: its expected code is a QName (ADR-0018 since #52).
   */
  static boolean expectsRuntimeError(CorpusCase c) {
    return c.expect().equals("error") && ((String) c.expectedError().get("code")).contains(":");
  }

  /** The check of one case; {@code RuntimeErrorCheckTest} runs it on synthetic cases. */
  static void check(CorpusCase c) throws Exception {
    assertThat(c.provenance()).isIn("observed", "documented", "assumed", "decided");
    assertThat(c.ref()).isNotBlank();
    assertThat(c.expect()).isIn("output", "error", "none");
    Path mapping = CaseFiles.corpus().resolve(c.mapping());
    byte[] model = Files.readAllBytes(mapping);
    ReferenceResolver base = DirectoryResolver.over(mapping.getParent());
    EngineFacade facade = c.facade();

    if (expectsRuntimeError(c)) {
      // a runtime error: every variant compiles, and its run raises the code at the authoring id
      Map<String, Object> expected = c.expectedError();
      String code = (String) expected.get("code");
      AuthoringId at = new AuthoringId((String) expected.get("authoringId"));
      byte[] input = Files.readAllBytes(c.input());
      for (CompileOptions options : VARIANTS) {
        CompileResult result = facade.compile(model, base, options);
        if (!(result instanceof CompileResult.Compiled(CompiledMapping compiled))) {
          throw new AssertionError(c.id() + " " + options + ": expected Compiled, got " + result);
        }
        MappingRunException raised;
        try {
          byte[] output = facade.run(compiled, input);
          throw new AssertionError(
              c.id()
                  + " "
                  + options
                  + ": expected "
                  + code
                  + " at "
                  + at.value()
                  + ", the run wrote "
                  + new String(output, UTF_8));
        } catch (MappingRunException e) {
          raised = e;
        }
        assertThat(raised.code()).as("%s %s: code", c.id(), options).isEqualTo(code);
        assertThat(raised.authoringId()).as("%s %s: authoring id", c.id(), options).isEqualTo(at);
      }
      return;
    }

    if (c.expect().equals("error")) {
      // a design-time error: every variant refuses with the expected code at the location, a
      // pointer when the case gives one (#65: a target field without a target field mapping),
      // otherwise the authoring id
      Map<String, Object> expected = c.expectedError();
      String code = (String) expected.get("code");
      Location at =
          expected.containsKey("pointer")
              ? new AtPointer((String) expected.get("pointer"))
              : new AtAuthoringId(new AuthoringId((String) expected.get("authoringId")));
      for (CompileOptions options : VARIANTS) {
        CompileResult result = facade.compile(model, base, options);
        if (!(result instanceof CompileResult.Refused(List<Diagnostic> diagnostics))) {
          throw new AssertionError(c.id() + " " + options + ": expected Refused, got " + result);
        }
        assertThat(diagnostics)
            .as("%s %s", c.id(), options)
            .anySatisfy(
                d -> {
                  assertThat(d.code()).isEqualTo(code);
                  assertThat(d.location()).isEqualTo(at);
                });
      }
      return;
    }

    byte[] input = Files.readAllBytes(c.input());
    Map<CompileOptions, byte[]> outputs = new LinkedHashMap<>();
    for (CompileOptions options : VARIANTS) {
      CompileResult result = facade.compile(model, base, options);
      if (!(result instanceof CompileResult.Compiled(CompiledMapping compiled))) {
        throw new AssertionError(c.id() + " " + options + ": expected Compiled, got " + result);
      }
      outputs.put(options, facade.run(compiled, input));
    }
    byte[] first = outputs.get(VARIANTS.getFirst());
    outputs.forEach(
        (options, output) ->
            assertThat(output)
                .as("%s byte-identical to %s", options, VARIANTS.getFirst())
                .isEqualTo(first));

    if (c.expect().equals("output")) {
      Diff diff = OracleVerdict.similar(Files.readAllBytes(c.expectedOutput()), first);
      assertThat(diff.hasDifferences()).as("%s: %s", c.id(), diff).isFalse();
    }
  }

  // --- the trace against the corpus (ADR-0015 "Trace" since #53)

  /** The trace limit of ADR-0015; no corpus case comes near it. */
  private static final int TRACE_LIMIT = 10_000;

  /**
   * One dynamic test per case that expects output, named by its case id as {@link #cases()} names
   * it, so the ratchet reads it as part of that case (scripts/ratchet.java). The case's test run
   * traces each target field mapping by its authoring id, and the slots it marks created, over all
   * its parent slots, are as many as {@code expected.xml} holds elements at the mapping's target
   * field path: the expected output is the oracle, not the run's own output. A mapping with no
   * entry was never evaluated and must have no element, and a field directly under the root, whose
   * parent is created once, always has an entry with the one parent slot 1. An entry's {@code
   * created} is the count of its created slots, and the field's queue is the entry of its {@code
   * result} box.
   */
  @TestFactory
  Stream<DynamicTest> everyTracedCaseCreatesWhatTheExpectedOutputHolds() {
    List<CorpusCase> cases =
        CaseFiles.all().stream().filter(c -> c.expect().equals("output")).toList();
    assertThat(cases).as("cases that expect output").isNotEmpty();
    return cases.stream().map(c -> dynamicTest(c.id(), () -> checkTrace(c)));
  }

  private static void checkTrace(CorpusCase c) throws Exception {
    Path mapping = CaseFiles.corpus().resolve(c.mapping());
    byte[] model = Files.readAllBytes(mapping);
    EngineFacade facade = c.facade();
    CompileResult compiled =
        facade.compile(model, DirectoryResolver.over(mapping.getParent()), CompileOptions.TEST_RUN);
    if (!(compiled instanceof CompileResult.Compiled(CompiledMapping tested))) {
      throw new AssertionError(c.id() + ": expected Compiled, got " + compiled);
    }
    TestRunResult result =
        facade.testRun(tested, Files.readAllBytes(c.input()), Duration.ofSeconds(10), TRACE_LIMIT);
    if (!(result instanceof TestRunResult.Output output)) {
      throw new AssertionError(c.id() + ": expected Output, got " + result);
    }
    Map<AuthoringId, Trace.Entry> entries = output.trace().entries();
    Element expected =
        DocumentBuilderFactory.newNSInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(Files.readAllBytes(c.expectedOutput())))
            .getDocumentElement();
    Map<String, Object> links =
        object(
            object(new CaseFiles.Json(new String(model, UTF_8)).document(), "the model")
                .get("targetFieldMappings"),
            "targetFieldMappings");

    for (Map.Entry<String, Object> field : links.entrySet()) {
      Map<String, Object> link = object(field.getValue(), field.getKey());
      AuthoringId id = new AuthoringId(text(link, "id"));
      List<String[]> steps = steps(field.getKey());
      long elements = count(expected, steps);
      String what = c.id() + " " + field.getKey() + " (" + id.value() + ")";
      Trace.Entry entry = entries.get(id);
      if (entry == null) {
        assertThat(steps)
            .as("%s: a field directly under the root is always evaluated", what)
            .hasSizeGreaterThan(2);
        assertThat(elements).as("%s: no entry, so no element", what).isZero();
        continue;
      }
      assertThat(entry).as(what).isInstanceOf(Trace.Occurrences.class);
      Trace.Occurrences occurrences = (Trace.Occurrences) entry;
      assertThat(occurrences.capped()).as("%s capped", what).isFalse();
      long created = 0;
      for (Trace.ParentSlot parent : occurrences.parents()) {
        long marked =
            parent.slots().stream().filter(s -> s.status() == Trace.SlotStatus.CREATED).count();
        assertThat(parent.created())
            .as("%s parent slot %d, slots %s", what, parent.parentSlot(), parent.slots())
            .isEqualTo(marked);
        created += parent.created();
      }
      assertThat(created).as("%s: created against expected.xml", what).isEqualTo(elements);
      if (steps.size() == 2) {
        assertThat(occurrences.parents())
            .as("%s: the root's one slot", what)
            .extracting(Trace.ParentSlot::parentSlot)
            .containsExactly(1L);
      }
      checkResultEntry(link, entries.get(new AuthoringId(text(link, "result"))), what);
    }
  }

  /**
   * The entry of the {@code result} box of {@code link}: the field's queue, or, for a graph without
   * a source-field box, its queue or its atomic value (ADR-0015 {@code {atomic: {type, value}}}).
   * Such a graph is a generating function when its result is a single value, and a queue when it is
   * one, such as {@code count} of a constant (ADR-0006 as amended by #83, F-21). A field read from
   * a source field never has an atomic entry. {@code GeneratingTraceCheckTest} runs it on synthetic
   * links.
   */
  static void checkResultEntry(Map<String, Object> link, Trace.Entry entry, String what) {
    String result = text(link, "result");
    boolean noSourceField =
        object(link.get("boxes"), what + " boxes").values().stream()
            .noneMatch(box -> "source".equals(object(box, what + " box").get("kind")));
    if (noSourceField) {
      assertThat(entry)
          .as("%s: the queue or value of its result box %s, with no source field", what, result)
          .isInstanceOfAny(Trace.Queue.class, Trace.Atomic.class);
    } else {
      assertThat(entry)
          .as("%s: the queue of its result box %s", what, result)
          .isInstanceOf(Trace.Queue.class);
    }
  }

  /** The members of a JSON object that {@link CaseFiles.Json} read. */
  private static Map<String, Object> object(Object json, String what) {
    if (!(json instanceof Map<?, ?> object)) {
      throw new AssertionError(what + " is no JSON object: " + json);
    }
    Map<String, Object> members = new LinkedHashMap<>();
    object.forEach((k, v) -> members.put((String) k, v));
    return members;
  }

  private static String text(Map<String, Object> object, String member) {
    if (!(object.get(member) instanceof String s)) {
      throw new AssertionError(member + " is no string in " + object);
    }
    return s;
  }

  /**
   * The steps of a field path, each its namespace and local name: {@code /Doc/Grp} or {@code
   * /Q{urn:a}Order/OrderId}, where a {@code /} inside the braces belongs to the namespace.
   */
  private static List<String[]> steps(String path) {
    List<String[]> steps = new ArrayList<>();
    int braces = 0;
    StringBuilder step = new StringBuilder();
    for (char ch : path.substring(1).toCharArray()) {
      if (ch == '/' && braces == 0) {
        steps.add(step(step.toString()));
        step.setLength(0);
      } else {
        braces += ch == '{' ? 1 : ch == '}' ? -1 : 0;
        step.append(ch);
      }
    }
    steps.add(step(step.toString()));
    return steps;
  }

  private static String[] step(String step) {
    if (step.startsWith("Q{")) {
      int close = step.indexOf('}');
      return new String[] {step.substring(2, close), step.substring(close + 1)};
    }
    return new String[] {"", step};
  }

  private static boolean named(Node node, String[] step) {
    String namespace = node.getNamespaceURI() == null ? "" : node.getNamespaceURI();
    return node.getNodeType() == Node.ELEMENT_NODE
        && namespace.equals(step[0])
        && node.getLocalName().equals(step[1]);
  }

  /** The number of elements at {@code steps} below and including {@code root}, the first step. */
  private static long count(Element root, List<String[]> steps) {
    if (!named(root, steps.getFirst())) {
      return 0;
    }
    List<Node> level = List.of(root);
    for (String[] step : steps.subList(1, steps.size())) {
      List<Node> next = new ArrayList<>();
      for (Node parent : level) {
        if (step[1].startsWith("@")) {
          // an attribute step (#102), the last: the parent's attribute of that name, if any
          String[] attribute = step(step[1].substring(1));
          Node found =
              parent
                  .getAttributes()
                  .getNamedItemNS(attribute[0].isEmpty() ? null : attribute[0], attribute[1]);
          if (found != null) {
            next.add(found);
          }
          continue;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
          if (named(child, step)) {
            next.add(child);
          }
        }
      }
      level = next;
    }
    return level.size();
  }
}
