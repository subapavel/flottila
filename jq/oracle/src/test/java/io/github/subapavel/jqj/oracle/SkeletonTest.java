package io.github.subapavel.jqj.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.Compiler;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.run.RunState;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Build skeleton (ticket 23): runs one class of each upstream module from the oracle, on the class path.
 * query-core's probe is its compiled identity (#33).
 * jq-core's probe is the exported Limits.embedded() (ticket 24); the oracle module does not read jq-core,
 * so it is called by reflection.
 */
class SkeletonTest {
  private static Object call(String className, String method, Class<?>[] types, Object... args) throws Exception {
    Method m = Class.forName(className).getDeclaredMethod(method, types);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  @Test
  void queryCoreRunsFromTheOracle() {
    // #33 deleted the Placeholder this probed; the probe is now query-core's own API: the compiled identity (ADR-0008).
    Object input = new Object();
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        Compiler.compile(new Identity(new Source(0, 1)), CompileMode.OPTIMISED)
            .run(
                new RunState(),
                input,
                v -> {
                  emitted.add(v);
                  return true;
                });
    assertThat(emitted).as("results of the identity").singleElement().isSameAs(input);
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
  }

  @Test
  void jqCoreRunsFromTheOracle() throws Exception {
    Object limits = call("io.github.subapavel.jqj.Limits", "embedded", new Class<?>[] {});
    Object steps = limits.getClass().getMethod("steps").invoke(limits);
    // ADR-0017: 10^9 steps by default.
    assertThat(steps).isEqualTo(1_000_000_000L);
  }
}
