package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AuthoringId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The runtime's family index (ADR-0025 "Families", F-15). Each function family has one {@code
 * <Family>Family.java} in engine/runtime, holding its name-to-binder maps; the build lists them in
 * {@code FunctionFamilies}, and no hand-written file does. {@code FunctionFamilies.ids()} gives the
 * families the index lists, {@code FunctionFamilies.merge} joins their maps, and {@code
 * QueueLibrary.bind} and {@code SingleValueLibrary.bind} look a name up in the merged maps; since
 * #71 both take the call's box (ADR-0026).
 */
class FunctionFamiliesTest {

  private static final String SUFFIX = "Family.java";

  /** Surefire runs in the module directory: the root is the nearest ancestor holding mvnw. */
  private static Path repoRoot() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("mvnw")) && Files.isDirectory(p.resolve("contract"))) {
        return p;
      }
    }
    throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
  }

  /**
   * The family of each {@code <Family>Family.java} in engine/runtime's main sources, one per file,
   * in order: {@code <Family>} with its first letter in lower case, as {@code ContextFamily.java}
   * is the family {@code context}.
   */
  private static List<String> familyFiles() throws IOException {
    Path sources = repoRoot().resolve("engine/runtime/src/main/java");
    List<String> names;
    try (Stream<Path> walk = Files.walk(sources)) {
      names =
          walk.filter(Files::isRegularFile)
              .map(p -> p.getFileName().toString())
              .filter(n -> n.endsWith(SUFFIX) && n.length() > SUFFIX.length())
              .sorted()
              .toList();
    }
    List<String> families = new ArrayList<>();
    for (String name : names) {
      String type = name.substring(0, name.length() - SUFFIX.length());
      families.add(Character.toLowerCase(type.charAt(0)) + type.substring(1));
    }
    return families;
  }

  @Test
  void theIndexListsEveryFamilyFile() throws Exception {
    List<String> files = familyFiles();
    // today's eleven functions form the family context (ADR-0025)
    assertThat(files).as("the family files of engine/runtime").contains("context");
    // one file per family: two of one family, in two packages, would be one id of the index
    assertThat(files).as("the families of the family files").doesNotHaveDuplicates();

    assertThat(FunctionFamilies.ids())
        .as("the families the index lists")
        .containsExactlyInAnyOrderElementsOf(files);
  }

  @Test
  void aNameInTwoFamiliesIsRefused() {
    // witness: families whose names differ merge into one map of every name
    Map<String, Map<String, String>> apart =
        new TreeMap<>(
            Map.of(
                "context",
                Map.of("sort", "context's", "exists", "context's"),
                "text",
                Map.of("trim", "text's")));
    assertThat(FunctionFamilies.merge(apart))
        .isEqualTo(Map.of("sort", "context's", "exists", "context's", "trim", "text's"));
    Map<String, Map<String, String>> clash =
        new TreeMap<>(
            Map.of(
                "context",
                Map.of("sort", "context's"),
                "text",
                Map.of("sort", "text's", "trim", "text's")));

    assertThatThrownBy(() -> FunctionFamilies.merge(clash))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sort")
        .hasMessageContaining("context")
        .hasMessageContaining("text");
  }

  /**
   * A name no family binds is a compiler bug, as before the split: lowering calls only functions of
   * the catalogue. A name of the other kind's map is no exception.
   */
  @Test
  void anUnknownNameStaysAnIllegalArgument() {
    AuthoringId box = new AuthoringId("f1");
    Node.OfQueue queue =
        frame -> {
          throw new AssertionError("binding runs nothing");
        };
    Node.OfString value =
        frame -> {
          throw new AssertionError("binding runs nothing");
        };
    // witness: a name of each merged map binds; replaceValue is a context function since #216,
    // because it reads SUPPRESS (F-45), so replaceString is the single-value name
    assertThat(QueueLibrary.bind("removeContexts", box, List.of(queue), List.of())).isNotNull();
    assertThat(QueueLibrary.bind("replaceValue", box, List.of(queue), List.of(""))).isNotNull();
    assertThat(SingleValueLibrary.bind("replaceString", box, List.of(value, value, value)))
        .isNotNull();

    assertThatThrownBy(() -> QueueLibrary.bind("noSuchFunction", box, List.of(queue), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("noSuchFunction");
    assertThatThrownBy(() -> SingleValueLibrary.bind("noSuchFunction", box, List.of(value)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("noSuchFunction");
    assertThatThrownBy(() -> SingleValueLibrary.bind("replaceValue", box, List.of(value, value)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replaceValue");
    assertThatThrownBy(
            () -> QueueLibrary.bind("replaceString", box, List.of(queue), List.of("", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replaceString");
    assertThatThrownBy(() -> SingleValueLibrary.bind("removeContexts", box, List.of(value)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("removeContexts");
  }
}
