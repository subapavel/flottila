package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.api.ExportResult;
import io.flottila.mapping.api.StructureResult;
import io.flottila.mapping.api.Structures;
import io.flottila.mapping.runtime.MappingRunException;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XsltExecutable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every input of a box is evaluated before the box runs, as PO computes every input queue of a box
 * first (F-22, ADR-0006), for every function at once (#86): for each portable function of the
 * merged catalogue and each of its inputs, the box D1, {@code divide(1, 0)}, at that input raises
 * err:FOAR0001 at D1 in the runtime (deploy and test-run compiles) and in the export under
 * Saxon-HE. It generalises {@link UntakenBranchDataErrorTest}; the cases come from the catalogue,
 * so a new family is covered without a new test. No input is left out: one that reads only the
 * context boundaries of its queue (useOneAsMany's contexts, formatByExample's example) raises D1's
 * error too, in both back ends.
 *
 * <p>Every other input is a source box on {@code /Orders/Order/Id}, whose one value the input
 * document sets to a filler. Each filler of {@link #FILLERS} under which the function runs, with
 * every input that filler, is tried: a truth value, 0 or 1 and a date of the default pattern are
 * the values on which a function could short-circuit past the erroring input (as {@code and} over
 * {@code false}, {@code or} over {@code true} and {@code if} either way did), and a filler the
 * function refuses would raise its own data error instead. The fillers come from the input, so each
 * case compiles and exports once. The models are no corpus cases and need no observation row; this
 * class is no corpus or differential class, so the ratchet does not read its tests as case ids
 * (scripts/ratchet.java).
 */
class EveryInputDataErrorTest {

  /** The fingerprints of the kit's structures, as compile checks them (#91). */
  private static final String SOURCE_FINGERPRINT = fingerprint("src-orders.xsd", "Orders");

  private static final String TARGET_FINGERPRINT = fingerprint("tgt-list.xsd", "Out");
  private static final String CODE = "err:FOAR0001";

  /** The values the other inputs take, one at a time. */
  private static final List<String> FILLERS = List.of("true", "false", "0", "1", "2024/01/31");

  /** Per function, the fillers under which it runs; the same for every input position. */
  private static final Map<String, List<String>> RUNS_UNDER = new ConcurrentHashMap<>();

  @TempDir Path dir;

  private final EngineFacade facade = new EngineFacade();

  /** Per portable function and per input: the case name, the function, its arity, the position. */
  static Stream<Arguments> inputs() throws Exception {
    String catalogue = CatalogueCoverageTest.catalogue();
    // functionNames has refused a catalogue that is no JSON object
    Set<String> names = CatalogueCoverageTest.functionNames(catalogue);
    Map<?, ?> functions = (Map<?, ?>) new CaseFiles.Json(catalogue).document();
    List<Arguments> cases = new ArrayList<>();
    for (String name : names) {
      Map<?, ?> entry = (Map<?, ?>) functions.get(name);
      if (Boolean.TRUE.equals(entry.get("portable"))
          && entry.get("inputs") instanceof List<?> inputs) {
        for (int position = 1; position <= inputs.size(); position++) {
          cases.add(
              Arguments.argumentSet(name + " input " + position, name, inputs.size(), position));
        }
      }
    }
    return cases.stream();
  }

  /**
   * F1, {@code fn} over {@code arity} inputs on tgt-list's All: D1, {@code divide(1, 0)}, at input
   * {@code erroring} (none when 0), and a source box on {@code /Orders/Order/Id} at every other.
   */
  private static byte[] model(String fn, int arity, int erroring) {
    List<String> inputs = new ArrayList<>();
    StringBuilder boxes = new StringBuilder();
    for (int i = 1; i <= arity; i++) {
      if (i == erroring) {
        inputs.add("\"D1\"");
        boxes.append(
            """
            ,
             "D1": {"kind": "function", "fn": "divide", "inputs": ["N1", "N0"]},
             "N1": {"kind": "function", "fn": "constant", "params": {"value": "1"}},
             "N0": {"kind": "function", "fn": "constant", "params": {"value": "0"}}""");
      } else {
        inputs.add("\"S" + i + "\"");
        boxes.append(
            """
            ,
             "S%d": {"kind": "source", "field": "/Orders/Order/Id", "context": "/Orders"}"""
                .formatted(i));
      }
    }
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%1$s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["tgt-list.xsd"], "fingerprint": "%5$s",
                    "root": {"element": "Out"}},
         "targetFieldMappings": {
           "/Out/All": {"id": "T1", "result": "F1", "boxes": {
             "F1": {"kind": "function", "fn": "%2$s", "inputs": [%3$s]}%4$s}}}}
        """
        .formatted(SOURCE_FINGERPRINT, fn, String.join(", ", inputs), boxes, TARGET_FINGERPRINT)
        .getBytes(UTF_8);
  }

  /** One Order whose Id is {@code filler}. */
  private static byte[] input(String filler) {
    return ("<Orders><Order><Id>" + filler + "</Id></Order></Orders>").getBytes(UTF_8);
  }

  private static ReferenceResolver structures() {
    return DirectoryResolver.over(CaseFiles.corpus().resolve("structures/observations"));
  }

  /** The fingerprint the engine derives for {@code file} of the kit under {@code root}. */
  private static String fingerprint(String file, String root) {
    StructureResult result =
        Structures.load(structures(), new SchemaBundle(List.of(file)), new ElementRoot(root));
    return ((StructureResult.Loaded) result).structure().fingerprint();
  }

  private CompiledMapping compiled(byte[] model, CompileOptions options, String what)
      throws Exception {
    CompileResult result = facade.compile(model, structures(), options);
    assertThat(result).as(what).isInstanceOf(CompileResult.Compiled.class);
    return ((CompileResult.Compiled) result).mapping();
  }

  /** The fillers under which {@code fn}, every input that filler, runs in the runtime. */
  private List<String> runsUnder(String fn, int arity) throws Exception {
    List<String> known = RUNS_UNDER.get(fn);
    if (known != null) {
      return known;
    }
    CompiledMapping witness =
        compiled(model(fn, arity, 0), CompileOptions.DEPLOY, fn + " over the source box");
    List<String> runs = new ArrayList<>();
    for (String filler : FILLERS) {
      if (!(catchThrowable(() -> facade.run(witness, input(filler)))
          instanceof MappingRunException)) {
        runs.add(filler);
      }
    }
    RUNS_UNDER.put(fn, List.copyOf(runs));
    return runs;
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("inputs")
  void aDataErrorAtTheInputIsRaisedAtItsBoxOnBothBackEnds(String fn, int arity, int position)
      throws Exception {
    List<String> fillers = runsUnder(fn, arity);
    assertThat(fillers).as("the fillers of %s that %s runs under", FILLERS, fn).isNotEmpty();
    String erroring = "%s with divide(1, 0) at input %d".formatted(fn, position);
    byte[] fails = model(fn, arity, position);
    CompiledMapping deploy = compiled(fails, CompileOptions.DEPLOY, erroring);
    CompiledMapping testRun = compiled(fails, CompileOptions.TEST_RUN, erroring);
    ExportResult.Exported exported =
        SaxonRunner.exported(facade.export(fails, structures()), erroring);
    XsltExecutable saxon = SaxonRunner.compile(exported.stylesheet(), exported.helperModule(), dir);
    for (String filler : fillers) {
      String what = erroring + ", " + filler + " elsewhere";
      for (CompiledMapping mapping : List.of(deploy, testRun)) {
        Throwable error = catchThrowable(() -> facade.run(mapping, input(filler)));
        String ours = what + ": ours, " + (mapping == deploy ? "deploy" : "test run");
        assertThat(error).as(ours).isInstanceOf(MappingRunException.class);
        MappingRunException run = (MappingRunException) error;
        assertThat(run.code()).as(ours).isEqualTo(CODE);
        assertThat(run.authoringId().value()).as(ours).isEqualTo("D1");
      }
      SaxonApiException raised = SaxonRunner.raised(saxon, input(filler), what);
      SaxonRunner.assertSameError(raised, CODE, what);
      SaxonRunner.assertNamesItsPlace(raised.getMessage(), CODE, "D1", what);
    }
  }
}
