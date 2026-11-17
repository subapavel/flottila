package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The heap claims of #118 in fresh child JVMs (ADR-0047 #118, "Bullet 1 on passing closures"): a closure that passes is
 * bound at the jump with no defining frame, so a chain of jumps keeps no earlier frame alive; a RECURSIVE closure chain
 * still runs at the default depth on the trampoline. Each child runs one filter on {@code null} under {@link
 * Limits#cli()} on the launcher's main thread, sized by {@code -Xss} (F-93: no {@code new Thread}), with this JVM's
 * class path and no agent or tool options. ChildJvm of #113 is not on this branch, so the launcher is the minimal one
 * below.
 *
 * <p>Expected results are reference jq 1.8.2's: diff/tail.rec (tail/fresh-closure-five-million, {@code 5000001}), and
 * {@code .logs/118-jqref.log} of #118's red for the closure chain, {@code 1000000} in 0.37 s under {@code ulimit -v
 * 2000000}.
 */
class TailJumpFreshJvmTest {
  /** The main thread's stack: four times {@link Limits#cli()}'s declared 512 KiB (ADR-0046 "Where the stack is tested"). */
  private static final String XSS = "-Xss2m";

  @TempDir Path dir;

  /**
   * A chain of 5 000 000 jumps, each binding a fresh closed closure {@code .+1}, runs in a 64 MiB heap: no jump keeps the
   * frame of the one before alive. Held frames would be millions of live objects, past 64 MiB.
   */
  @Test
  void jumpChainHoldsNoFrame() throws IOException, InterruptedException {
    String printed = runChild("-Xmx64m", TailCallKindsTest.FRESH_CLOSURE);
    assertThat(printed).as("the child at -Xmx64m running %s", TailCallKindsTest.FRESH_CLOSURE).startsWith("[5000001], ran out\n");
  }

  /**
   * ADR-0047's RECURSIVE pin, {@code r(f|.)}, at the default recursion depth: its 10^6 calls and the 10^6 closures the
   * last {@code f} runs through stay live, under {@link Limits#cli()}'s 4 194 304, so the run gives reference jq's
   * result past {@code U_f}, on the trampoline. The heap is the design's estimate, about 2 GiB; the child prints its
   * committed heap after the run, which this test records.
   */
  @Test
  void recursiveClosureChainRunsDeep() throws IOException, InterruptedException {
    String printed = runChild("-Xmx2g", TailCallKindsTest.CLOSURE_CHAIN);
    System.out.println("recursiveClosureChainRunsDeep at -Xmx2g: " + printed.strip().replace('\n', ' '));
    assertThat(printed).as("the child at -Xmx2g running %s", TailCallKindsTest.CLOSURE_CHAIN).startsWith("[1000000], ran out\n");
  }

  /** What a fresh child JVM with {@code heap} printed for {@code filter}, then its exit code; it must end within 600 s. */
  private String runChild(String heap, String filter) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(List.of(heap, XSS, "-cp", System.getProperty("java.class.path"), Child.class.getName(), filter));
    File out = Files.createTempFile(dir, "child", ".out").toFile();
    ProcessBuilder launch = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out);
    // An agent or a heap option from the environment would reach the child's command line.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    Process child = launch.start();
    try {
      assertThat(child.waitFor(600, TimeUnit.SECONDS)).as("the child running %s ends within 600 s", filter).isTrue();
      return Files.readString(out.toPath(), UTF_8) + "exit " + child.exitValue() + "\n";
    } finally {
      child.destroyForcibly();
    }
  }

  /**
   * The child: {@code <filter>}. Runs it on {@code null} under {@link Limits#cli()} on this, the launcher's main thread,
   * and prints {@code <results>, <end>}, then {@code committed=<bytes> used=<bytes>}, the heap after the run.
   */
  static final class Child {
    public static void main(String[] args) {
      JqEngine engine = JqEngine.builder().limits(Limits.cli()).build();
      String end;
      Results results = new Results();
      try {
        JqFilter compiled = engine.compile(args[0]);
        Workspace workspace = engine.newWorkspace();
        JqValue root = read(workspace, "null".getBytes(US_ASCII)).root();
        try {
          end = compiled.run(root, NONE, results, workspace) ? "ran out" : "stopped";
        } catch (JqFilterException e) {
          end = "error " + e.value().toJson();
        } catch (JqTermination t) {
          end = "termination " + t.cause() + (t.budget() == null ? "" : " " + t.budget());
        }
      } catch (Throwable t) {
        end = "threw " + t;
      }
      List<String> texts = new ArrayList<>();
      for (JqValue v : results.values) {
        texts.add(v.toJson());
      }
      System.out.println(texts + ", " + end);
      Runtime runtime = Runtime.getRuntime();
      System.out.println("committed=" + runtime.totalMemory() + " used=" + (runtime.totalMemory() - runtime.freeMemory()));
    }
  }
}
