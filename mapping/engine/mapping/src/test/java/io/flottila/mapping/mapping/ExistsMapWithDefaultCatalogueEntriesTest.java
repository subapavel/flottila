package io.flottila.mapping.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.mapping.JsonTree.Arr;
import io.flottila.mapping.mapping.JsonTree.Bool;
import io.flottila.mapping.mapping.JsonTree.Obj;
import io.flottila.mapping.mapping.JsonTree.Str;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The catalogue entries #57 adds (ADR-0025), read from the packed {@code function-catalogue.json}
 * with the tests' own strict reader: {@code exists} is a context function with the input {@code
 * queue}; {@code mapWithDefault} a context function with the input {@code queue} and the string
 * param {@code default}, default {@code ""}. Both are portable.
 */
class ExistsMapWithDefaultCatalogueEntriesTest {

  private static Map<String, JsonTree> entry(String name) throws Exception {
    JsonTree catalogue = JsonTree.parse(Fixtures.strictUtf8(FunctionCatalogueTest.catalogue()));
    assertThat(catalogue).isInstanceOf(Obj.class);
    JsonTree entry = ((Obj) catalogue).members().get(name);
    assertThat(entry).as("the catalogue entry %s", name).isInstanceOf(Obj.class);
    return ((Obj) entry).members();
  }

  @Test
  void existsIsAPortableContextFunctionOfAQueue() throws Exception {
    Map<String, JsonTree> exists = entry("exists");

    assertThat(exists.get("executionKind")).isEqualTo(new Str("context"));
    assertThat(exists.get("inputs"))
        .isEqualTo(new Arr(List.of(new Obj(Map.of("name", new Str("queue"))))));
    assertThat(exists).doesNotContainKey("params");
    assertThat(exists.get("portable")).isEqualTo(new Bool(true));
  }

  @Test
  void mapWithDefaultIsAPortableContextFunctionWithAStringParam() throws Exception {
    Map<String, JsonTree> mapWithDefault = entry("mapWithDefault");

    assertThat(mapWithDefault.get("executionKind")).isEqualTo(new Str("context"));
    assertThat(mapWithDefault.get("inputs"))
        .isEqualTo(new Arr(List.of(new Obj(Map.of("name", new Str("queue"))))));
    assertThat(mapWithDefault.get("params"))
        .isEqualTo(
            new Arr(
                List.of(
                    new Obj(
                        Map.of(
                            "default",
                            new Str(""),
                            "name",
                            new Str("default"),
                            "type",
                            new Str("string"))))));
    assertThat(mapWithDefault.get("portable")).isEqualTo(new Bool(true));
  }
}
