package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.query.compile.CompileMode;
import dev.rail8.jq.value.JqValue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The stack claims of #111 in fresh, agent-free child JVMs (ADR-0046 and its #111 amendment, F-44, F-49): a 512 KiB edge
 * runs only here, never in the suite. Each child is started with this JVM's class path, the JVM options of its tier and
 * no agent or tool options, the case a host meets, as IrDepthTest's child is.
 *
 * <p>Expected results are reference jq 1.8.2's, recorded by oracle-record.sh in diff/deep.rec for #111: deep/collect
 * 1, deep/reduce 9998, deep/first-or 7, deep/hold 0 and deep/nested-drivers 1000.
 */
class DeepStackFreshJvmTest {
  /** 9 999 {@code [} then 9 999 {@code ]}: one level under jq's parse limit of 10 000 (F-22). */
  static final String DEEP = "[".repeat(9_999) + "]".repeat(9_999);

  /** The adviser's witness of the leaf-emit depth (C1): every level's rise calls g, which emits under 100 Trys. */
  static final String HOLD =
      "def g: if . == 0 then " + "try(".repeat(100) + "." + ")".repeat(100) + " else (. - 1 | g) end; def f: if . == 0 then 0 else (. - 1 | f) | (1 | g) end; 9999 | f";

  /** Spike A's nested shape at n = 2 000 (ADR-0011): form A ended at 201 drivers on 1 MiB. */
  static final String NESTED = "def r: if .==0 then 0 else (.-1|r)+1 end; def n: if .==0 then 0 else (.-1|n)|(1000|r) end; 2000|n";

  /** What the child prints for the deep cases, in their order. */
  private static final String DEEP_CASES = "deep/collect: [1], ran out\ndeep/reduce: [9998], ran out\ndeep/first-or: [7], ran out\ndeep/hold: [0], ran out\n";

  private static final long KIB = 1024;

  @TempDir Path dir;

  static Stream<Arguments> tiers() {
    return Stream.of(
        Arguments.argumentSet("-Xint", List.of("-Xint")),
        Arguments.argumentSet("C1 only", List.of("-Xbatch", "-XX:TieredStopAtLevel=1")),
        Arguments.argumentSet("the default JIT", List.of()));
  }

  /** The deep cases with the command line's 512 KiB declared, on a 512 KiB thread: the real edge, in each tier. */
  @ParameterizedTest
  @MethodSource("tiers")
  void deepCasesComplete(List<String> flags) throws IOException, InterruptedException {
    assertThat(runChild(flags, Child.class.getName(), String.valueOf(512 * KIB), String.valueOf(512 * KIB), "deep")).as("the deep cases at 512 KiB under %s", flags).isEqualTo(DEEP_CASES);
  }

  /**
   * {@code b} is the largest over {@code -Xint} and C1, and proven in the warm default JIT (ADR-0046 #113, K1): declaring 1 MiB on a 1 MiB thread puts more units of each recursion on
   * the Java stack before the switch, and the frames per unit of every tier still fit.
   */
  @ParameterizedTest
  @MethodSource("tiers")
  void bytesPerUnitCoversTheTiers(List<String> flags) throws IOException, InterruptedException {
    assertThat(runChild(flags, Child.class.getName(), String.valueOf(1024 * KIB), String.valueOf(1024 * KIB), "deep")).as("the deep cases declaring 1 MiB on 1 MiB under %s", flags).isEqualTo(DEEP_CASES);
  }

  /** Form B (ADR-0011): at most one driver is live, so the nested shape at n = 2 000 completes on a 1 MiB thread. */
  @Test
  void nestedDriversDoNotStack() throws IOException, InterruptedException {
    assertThat(runChild(List.of(), Child.class.getName(), String.valueOf(1024 * KIB), String.valueOf(512 * KIB), "nested")).as("the nested shape at n = 2 000 on 1 MiB").isEqualTo("deep/nested-drivers: [1000], ran out\n");
  }

  /**
   * ADR-0046 #111: the reserve, a source constant that {@code explain()} shows, covers this JVM's guard pages (red,
   * yellow, reserved and shadow, each a page) and the 32 KiB the amendment sets aside for natives. The flags are read in
   * a child JVM; main code reads none.
   */
  @Test
  void reserveCoversTheGuardPages() throws IOException, InterruptedException {
    String flags = runChild(List.of("-XX:+PrintFlagsFinal"), "-version");
    Map<String, Long> pages = new LinkedHashMap<>();
    for (String line : flags.split("\n")) {
      String[] f = line.trim().split("\\s+");
      if (f.length >= 4 && f[1].matches("Stack(Red|Yellow|Reserved|Shadow)Pages")) pages.put(f[1], Long.parseLong(f[3]));
    }
    assertThat(pages).as("the guard-page flags of the child JVM").containsOnlyKeys("StackRedPages", "StackYellowPages", "StackReservedPages", "StackShadowPages");
    long pageSize = Long.parseLong(command(List.of("getconf", "PAGESIZE")).trim());
    long guard = pages.values().stream().mapToLong(Long::longValue).sum() * pageSize;
    long reserve = StackUnitsTest.constants(CallRuns.F, CompileMode.OPTIMISED).reserve();
    assertThat(reserve).as("the reserve against %s pages of %d bytes (%d bytes) and 32 KiB for natives", pages, pageSize, guard).isGreaterThanOrEqualTo(guard + 32 * KIB);
  }

  /** What a fresh child JVM ({@link ChildJvm}) printed, run with {@code flags} and {@code args}. */
  private String runChild(List<String> flags, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(flags);
    command.addAll(List.of("-cp", System.getProperty("java.class.path")));
    command.addAll(List.of(args));
    return command(command);
  }

  private String command(List<String> command) throws IOException, InterruptedException {
    return ChildJvm.command(dir, command);
  }

  /**
   * The child: {@code <threadBytes> <stackBytes> deep|nested}. Runs each case under {@link Limits#cli()} with
   * {@code stackBytes} declared, on a new thread of {@code threadBytes}, and prints {@code <id>: <results>, <end>}.
   */
  static final class Child {
    public static void main(String[] args) throws InterruptedException {
      long thread = Long.parseLong(args[0]);
      long stackBytes = Long.parseLong(args[1]);
      Map<String, String[]> cases = new LinkedHashMap<>();
      if (args[2].equals("deep")) {
        cases.put("deep/collect", new String[] {"def d: [.[]?|d]; d|length", DEEP});
        cases.put("deep/reduce", new String[] {"def r: reduce .[] as $x (0; .+($x|r)+1); r", DEEP});
        cases.put("deep/first-or", new String[] {"def l: first(.[]?|l)//7; l", DEEP});
        cases.put("deep/hold", new String[] {HOLD, "null"});
      } else {
        cases.put("deep/nested-drivers", new String[] {NESTED, "null"});
      }
      Limits cli = Limits.cli();
      JqEngine engine =
          JqEngine.builder()
              .limits(new Limits(cli.steps(), cli.deadline(), cli.nestingDepth(), cli.recursionDepth(), cli.bytesBuilt(), cli.bytesWritten(), cli.regexMatchTime(), stackBytes))
              .build();
      for (Map.Entry<String, String[]> c : cases.entrySet()) {
        AtomicReference<String> outcome = new AtomicReference<>();
        Thread t = new Thread(null, () -> outcome.set(ended(engine, c.getValue()[0], c.getValue()[1])), c.getKey(), thread);
        t.start();
        t.join();
        System.out.println(c.getKey() + ": " + outcome.get());
      }
    }

    private static String ended(JqEngine engine, String filter, String json) {
      try {
        JqFilter compiled = engine.compile(filter);
        Workspace workspace = engine.newWorkspace();
        JqValue root = read(workspace, json.getBytes(US_ASCII)).root();
        Results results = new Results();
        String end;
        try {
          end = compiled.run(root, NONE, results, workspace) ? "ran out" : "stopped";
        } catch (JqFilterException e) {
          end = "error " + e.value().toJson();
        } catch (JqTermination t) {
          end = "termination " + t.cause();
        }
        return results.values.stream().map(JqValue::toJson).toList() + ", " + end;
      } catch (Throwable t) {
        return "threw " + t;
      }
    }
  }
}
