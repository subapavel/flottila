package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A host reads a built object through {@link JqObject} (ADR-0016, ADR-0026): {@code get} gives a key's value or Java
 * {@code null} for a missing key, {@code size} the distinct keys, and {@code forEach} the key list in its order (GLOSSARY
 * "Key list"). Both kinds of built object are read: a shape site's ({@code {a:1,b:2}}) and a growable one whose keys
 * are a document's strings ({@code {(.k):1, (.j):2, (.k):3}}). The expected values are jq-ref 1.8.2's recordings in
 * diff/construct.rec: construct/object-field-second ({@code .b} of {@code {a:1,b:2}} is 2) and
 * construct/key-computed-repeated ({"a":3,"b":2}).
 */
class BuiltObjectHostTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void aHostReadsAShapeSitesObject() {
    JqObject object = single("{a:1,b:2}", JqNull.NULL);
    assertThat(object.toJson()).as("text of {a:1,b:2}").isEqualTo("{\"a\":1,\"b\":2}");
    assertThat(object.size()).as("size of {a:1,b:2}").isEqualTo(2);
    assertThat(number(object.get("b"))).as("get b").isEqualTo(2L);
    assertThat(number(object.get("a"))).as("get a").isEqualTo(1L);
    assertThat(object.get("z")).as("get of a missing key").isNull();
    assertThat(entries(object)).as("forEach of {a:1,b:2}").containsExactly("a=1", "b=2");
  }

  @Test
  void aHostReadsAGrowableObjectWhoseKeysAreADocumentsStrings() {
    Workspace reading = ENGINE.newWorkspace();
    JqValue input = RunBudgets.read(reading, "{\"k\":\"a\",\"j\":\"b\"}".getBytes(US_ASCII)).root();
    JqObject object = single("{(.k):1, (.j):2, (.k):3}", input);
    assertThat(object.toJson()).as("text of the growable object").isEqualTo("{\"a\":3,\"b\":2}");
    assertThat(object.size()).as("size: the repeated key counts once").isEqualTo(2);
    assertThat(number(object.get("a"))).as("get a: the last value of the repeated key").isEqualTo(3L);
    assertThat(number(object.get("b"))).as("get b").isEqualTo(2L);
    assertThat(object.get("k")).as("get of the input's key, which the object lacks").isNull();
    assertThat(entries(object)).as("forEach: the repeated key in its first place").containsExactly("a=3", "b=2");
  }

  /** The one result of {@code filter} on {@code input}, an object. */
  private static JqObject single(String filter, JqValue input) {
    Results results = new Results();
    assertThat(ENGINE.compile(filter).run(input, NONE, results, ENGINE.newWorkspace())).as("run's answer for %s", filter).isTrue();
    assertThat(results.values).as("results of %s", filter).singleElement().isInstanceOf(JqObject.class);
    return (JqObject) results.values.getFirst();
  }

  private static long number(JqValue value) {
    assertThat(value).as("a number").isInstanceOf(JqNumber.class);
    return ((JqNumber) value).longValue();
  }

  private static List<String> entries(JqObject object) {
    List<String> out = new ArrayList<>();
    object.forEach((k, v) -> out.add(k + "=" + v.toJson()));
    return out;
  }
}
