package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqTermination.Cause;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Reading through the exported API only: a Workspace, a heap segment of UTF-8 text, kind checks. */
final class TestDocuments {
  private TestDocuments() {}

  static Workspace newWorkspace() {
    return JqEngine.builder().build().newWorkspace();
  }

  /** A writable heap segment over the UTF-8 bytes of {@code json}; a Java char below 0x80 is one byte. */
  static MemorySegment segment(String json) {
    return MemorySegment.ofArray(json.getBytes(UTF_8));
  }

  static JsonDoc read(Workspace workspace, String json) {
    return workspace.read(segment(json));
  }

  /** Asserts that {@code value} is of the kind {@code type} and returns it as that kind. */
  static <T extends JqValue> T kind(Class<T> type, JqValue value, String path) {
    assertThat(value).as("kind of %s", path).isInstanceOf(type);
    return type.cast(value);
  }

  /** ADR-0026 (Q2 of #32): a stale view or document throws JqTermination(STALE_VIEW, 5, JqNull.NULL, null). */
  static void assertStale(ThrowingCallable call, String what) {
    assertThatThrownBy(call)
        .as("%s on a stale document", what)
        .isInstanceOfSatisfying(
            JqTermination.class,
            t -> {
              assertThat(t.cause()).as("cause of %s", what).isEqualTo(Cause.STALE_VIEW);
              assertThat(t.exitCode()).as("exit code of %s", what).isEqualTo(5);
              assertThat(t.value()).as("value of %s", what).isSameAs(JqNull.NULL);
              assertThat(t.budget()).as("budget of %s", what).isNull();
            });
  }
}
