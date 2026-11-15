package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The events of {@code JsonParser} (#98), which the projection reads: each container ends with the
 * event of its own kind, and a value says whether it is an array item. What the projection makes of
 * them is {@code JsonInputTest}'s question.
 */
class JsonParserTest {

  /** Each event of {@code text}, with {@code *} after an item's start and its text after a key. */
  private static List<String> events(String text) throws Exception {
    JsonParser parser = new JsonParser(new StringReader(text));
    List<String> events = new ArrayList<>();
    for (int event = parser.next(); event != JsonParser.END; event = parser.next()) {
      String name =
          switch (event) {
            case JsonParser.START_OBJECT -> "{";
            case JsonParser.END_OBJECT -> "}";
            case JsonParser.START_ARRAY -> "[";
            case JsonParser.END_ARRAY -> "]";
            case JsonParser.KEY -> "key " + parser.text();
            default -> "value " + parser.text();
          };
      boolean starts = event != JsonParser.END_OBJECT && event != JsonParser.END_ARRAY;
      events.add(starts && event != JsonParser.KEY && parser.item() ? name + " *" : name);
    }
    return events;
  }

  @Test
  void eachContainerEndsWithItsOwnEvent() throws Exception {
    assertThat(events("{\"a\": [1, {}, []], \"b\": {}}"))
        .containsExactly(
            "{", "key a", "[", "value 1 *", "{ *", "}", "[ *", "]", "]", "key b", "{", "}", "}");
  }
}
