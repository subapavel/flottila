package io.github.subapavel.jqj.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqException;
import io.github.subapavel.jqj.value.JqValue;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The kinds permit views in jq-core's non-exported {@code internal.value} (ADR-0016). This runs with
 * jq-core as the named module a host puts on its module path; it needs the module-path test execution
 * of this module's build. Each PermittedSubclasses attribute, read from the class file, must equal what
 * the JVM reports, and every permitted class must load into jq-core's module.
 */
class SealedPermitsModulePathTest {
  private static final String CORE = "io.github.subapavel.jqj";

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
  void permitsLoadOnTheModulePath() throws IOException, ClassNotFoundException {
    Module core = JqValue.class.getModule();
    assertThat(core.getName()).as("jq-core runs as a named module, on the module path").isEqualTo(CORE);
    assertThat(core.isExported("io.github.subapavel.jqj.value")).as("value package exported").isTrue();
    assertThat(core.isExported("io.github.subapavel.jqj.internal.value")).as("views' package exported").isFalse();

    Map<String, Set<String>> walked = new TreeMap<>();
    walk(JqValue.class, walked);
    walk(JqException.class, walked);
    assertThat(walked).as("sealed types reached from JqValue and JqException, with their permits").isEqualTo(PERMITS);

    Set<String> names = new TreeSet<>(PERMITS.keySet());
    PERMITS.values().forEach(names::addAll);
    ClassLoader loader = JqValue.class.getClassLoader();
    for (String name : names) {
      assertThat(Class.forName(name, true, loader).getModule().getName()).as("module of %s, loaded", name).isEqualTo(CORE);
    }
  }

  private static void walk(Class<?> type, Map<String, Set<String>> out) throws IOException {
    Set<String> inClassFile = permittedSubclassesAttribute(type);
    Class<?>[] permitted = type.getPermittedSubclasses();
    Set<String> reflected =
        permitted == null ? Set.of() : Arrays.stream(permitted).map(Class::getName).collect(Collectors.toCollection(TreeSet::new));
    assertThat(reflected)
        .as("getPermittedSubclasses() of %s against its PermittedSubclasses attribute", type.getName())
        .isEqualTo(inClassFile);
    if (permitted == null) {
      return;
    }
    out.put(type.getName(), inClassFile);
    for (Class<?> p : permitted) {
      walk(p, out);
    }
  }

  private static Set<String> permittedSubclassesAttribute(Class<?> type) throws IOException {
    String resource = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream in = type.getResourceAsStream(resource)) {
      assertThat(in).as("class file %s", resource).isNotNull();
      ClassModel model = ClassFile.of().parse(in.readAllBytes());
      return model
          .findAttribute(Attributes.permittedSubclasses())
          .map(
              a ->
                  a.permittedSubclasses().stream()
                      .map(entry -> entry.asInternalName().replace('/', '.'))
                      .collect(Collectors.toCollection(TreeSet::new)))
          .orElseGet(TreeSet::new);
    }
  }
}
