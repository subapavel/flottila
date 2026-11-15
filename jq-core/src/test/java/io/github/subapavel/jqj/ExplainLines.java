package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The text of {@link JqFilter#explain()} as the explain-seam tests read it (#108, #112, #115, #116): one {@link Line}
 * per line. A test finds a line by its fields and reads one fact at a time, never the whole text, which is unstable. A
 * node line is {@code <function> <id> <kind> <start>..<end>} and its facts as {@code name=value}; a function line is
 * {@code function fn<place> <name>/<arity> <start>..<end>} and its facts. The main tree's function is {@code main}.
 */
final class ExplainLines {
  private ExplainLines() {}

  /** One line of the text: its fields before the first {@code name=value} one, and its facts by name. */
  record Line(List<String> fields, Map<String, String> facts) {
    static Line of(String line) {
      List<String> fields = new ArrayList<>();
      Map<String, String> facts = new LinkedHashMap<>();
      for (String field : line.split(" ")) {
        int eq = field.indexOf('=');
        if (eq < 0) {
          fields.add(field);
        } else {
          facts.put(field.substring(0, eq), field.substring(eq + 1));
        }
      }
      return new Line(fields, facts);
    }

    boolean isFunction() {
      return fields.getFirst().equals("function");
    }
  }

  /** The lines of the explain text of {@code filter} as {@code engine} compiles it; the text must end with an LF. */
  static List<Line> lines(JqEngine engine, String filter) {
    String text = engine.compile(filter).explain();
    assertThat(text).as("explain of %s", filter).endsWith("\n");
    return Arrays.stream(text.split("\n")).map(Line::of).toList();
  }

  /** The node lines of {@code filter} as {@code engine} compiles it, in text order. */
  static List<Line> nodeLines(JqEngine engine, String filter) {
    return lines(engine, filter).stream().filter(l -> !l.isFunction()).toList();
  }

  /** The column of the body {@code body}: {@code main}, or the function line's {@code fn<place>} of a jq name/arity. */
  static String column(List<Line> lines, String body) {
    if (body.equals("main")) {
      return "main";
    }
    List<Line> found = lines.stream().filter(l -> l.isFunction() && l.fields().get(2).equals(body)).toList();
    assertThat(found).as("function %s in %s", body, lines).hasSize(1);
    return found.getFirst().fields().get(1);
  }

  /** The one node line of {@code body} with the freeze id {@code id}. */
  static Line node(List<Line> lines, String body, int id) {
    return at(lines, column(lines, body), id);
  }

  /** The one node line of the column {@code column} ({@code main} or {@code fn<place>}) with the freeze id {@code id}. */
  static Line at(List<Line> lines, String column, int id) {
    List<Line> found =
        lines.stream().filter(l -> !l.isFunction() && l.fields().get(0).equals(column) && l.fields().get(1).equals(Integer.toString(id))).toList();
    assertThat(found).as("node %d of %s in %s", id, column, lines).hasSize(1);
    return found.getFirst();
  }
}
