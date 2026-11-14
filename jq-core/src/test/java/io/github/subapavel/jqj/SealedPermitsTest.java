package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.subapavel.jqj.value.JqValue;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The kinds permit views in another package of the same module (ADR-0016). javac accepts that only
 * with jq-core's module-info; the unit tests run on the class path (ADR-0013), in the unnamed module,
 * where the JVM checks the permits again when it loads each subclass (F-20).
 */
class SealedPermitsTest {
  /** ADR-0016 and ADR-0024: each sealed type and the classes it permits, written from the API slice. */
  private static final Map<String, Set<String>> PERMITS =
      Map.of(
          "io.github.subapavel.jqj.value.JqValue",
          Set.of(
              "io.github.subapavel.jqj.value.JqNull",
              "io.github.subapavel.jqj.value.JqBool",
              "io.github.subapavel.jqj.value.JqNumber",
              "io.github.subapavel.jqj.value.JqString",
              "io.github.subapavel.jqj.value.JqArray",
              "io.github.subapavel.jqj.value.JqObject"),
          "io.github.subapavel.jqj.value.JqNumber",
          // #61 design: a number written in the filter is a LiteralNumber; #65 (ADR-0003): a computed one is a DoubleNumber.
          Set.of(
              "io.github.subapavel.jqj.internal.value.NumberView",
              "io.github.subapavel.jqj.internal.value.LiteralNumber",
              "io.github.subapavel.jqj.internal.value.DoubleNumber"),
          "io.github.subapavel.jqj.value.JqString",
          // #34 design section 7: a string built at run time (jq's error texts) is a BuiltString.
          Set.of("io.github.subapavel.jqj.internal.value.StringView", "io.github.subapavel.jqj.internal.value.BuiltString"),
          "io.github.subapavel.jqj.value.JqArray",
          // #63 (decision 1 of its approval): an array a slice builds, man.test .[2:4], is a BuiltArray.
          Set.of("io.github.subapavel.jqj.internal.value.ArrayView", "io.github.subapavel.jqj.internal.value.BuiltArray"),
          "io.github.subapavel.jqj.value.JqObject",
          // #64 (ADR-0003): an object a construction builds, jq.test {a: 1}, is a BuiltObject.
          Set.of("io.github.subapavel.jqj.internal.value.ObjectView", "io.github.subapavel.jqj.internal.value.BuiltObject"),
          "io.github.subapavel.jqj.JqException",
          Set.of(
              "io.github.subapavel.jqj.JqCompileException",
              "io.github.subapavel.jqj.JqSyntaxException",
              "io.github.subapavel.jqj.JqFilterException",
              "io.github.subapavel.jqj.JqTermination"));

  @Test
  void permitsLoadOnTheClassPath() {
    assertThat(JqValue.class.getModule().isNamed()).as("jq-core runs in the unnamed module, on the class path").isFalse();

    Map<String, Set<String>> walked = new TreeMap<>();
    walk(JqValue.class, walked);
    walk(JqException.class, walked);
    assertThat(walked).as("sealed types reached from JqValue and JqException, with their permits").isEqualTo(PERMITS);

    Set<String> names = new TreeSet<>(PERMITS.keySet());
    PERMITS.values().forEach(names::addAll);
    ClassLoader loader = JqValue.class.getClassLoader();
    for (String name : names) {
      assertThatCode(() -> Class.forName(name, true, loader)).as("loading %s", name).doesNotThrowAnyException();
    }
  }

  /** getPermittedSubclasses() leaves out a permit the JVM could not load, so a rejected view shows here. */
  private static void walk(Class<?> type, Map<String, Set<String>> out) {
    Class<?>[] permitted = type.getPermittedSubclasses();
    if (permitted == null) {
      return;
    }
    Set<String> names = new TreeSet<>();
    for (Class<?> p : permitted) {
      names.add(p.getName());
      walk(p, out);
    }
    out.put(type.getName(), names);
  }
}
