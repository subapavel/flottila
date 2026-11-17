package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.subapavel.jqj.host.Host;
import org.junit.jupiter.api.Test;

/** ADR-0016: {@code JqEngine.builder().limits(Limits).host(Host).build()} and {@code newWorkspace()}. */
class EngineTest {
  @Test
  void defaultBuilderBuilds() {
    // Neither limits nor host given: the builder falls back to Limits.embedded() and Host.sandboxed().
    JqEngine engine = JqEngine.builder().build();
    assertThat(engine).isNotNull();
    assertThat(engine.newWorkspace()).isNotNull();
  }

  @Test
  void builderChainsLimitsAndHost() {
    assertThat(JqEngine.builder().limits(Limits.cli()).host(Host.sandboxed()).build()).isNotNull();
    assertThat(JqEngine.builder().host(Host.sandboxed()).limits(Limits.embedded()).build()).isNotNull();
  }

  @Test
  void builderRejectsNullLimitsAndHost() {
    JqEngine.Builder builder = JqEngine.builder();
    assertThatNullPointerException().as("limits(null)").isThrownBy(() -> builder.limits(null));
    assertThatNullPointerException().as("host(null)").isThrownBy(() -> builder.host(null));
    // Witness: the same builder takes non-null values, so the rejections above are the nulls'.
    assertThat(builder.limits(Limits.embedded()).host(Host.sandboxed()).build()).isNotNull();
  }

  @Test
  void newWorkspaceIsFreshEachCall() {
    JqEngine engine = JqEngine.builder().build();
    Workspace first = engine.newWorkspace();
    Workspace second = engine.newWorkspace();
    assertThat(first).isNotNull();
    assertThat(second).isNotNull().isNotSameAs(first);
  }
}
