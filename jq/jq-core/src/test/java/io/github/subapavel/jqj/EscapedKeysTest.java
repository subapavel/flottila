package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Object keys with escapes or bad bytes (tag 6) at the API seam (#35 design, section 1.6): {@code get} matches over the
 * decoded key, two spellings of one decoded key are one key (first position, last value, ADR-0002), and {@code
 * forEach} hands on decoded keys. Exported types only, and {@code .a} through {@code JqEngine.compile}.
 *
 * <p>Every jq-ref observation was taken on 2026-11-12 with {@code printf -- '<input>' | jq-ref -c <filter>}, where a
 * backslash-u sequence is the six bytes of a JSON escape and {@code \xHH} a raw byte.
 */
class EscapedKeysTest {
  private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

  /** The bytes of the parts in order: a String part is its UTF-8, a byte[] part as it is. */
  private static byte[] bytes(Object... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Object p : parts) {
      out.writeBytes(p instanceof byte[] b ? b : ((String) p).getBytes(UTF_8));
    }
    return out.toByteArray();
  }

  private static JqObject object(Workspace workspace, byte[] json) {
    return kind(JqObject.class, workspace.read(MemorySegment.ofArray(json)).root(), HEX.formatHex(json));
  }

  private static long number(JqValue v, String what) {
    return kind(JqNumber.class, v, what).longValue();
  }

  private static List<String> keys(JqObject o) {
    List<String> keys = new ArrayList<>();
    o.forEach((k, v) -> keys.add(k + "=" + number(v, "forEach " + k)));
    return keys;
  }

  @Test
  void escapedKeyMatchesItsDecodedText() {
    // jq-ref 1.8.2: {"\\u00e9\\u20AC":1,"\\u0061\\u0062":2,"\\n\\"":3} with '., .["é€"], .ab, .["\n\""],
    //   .["\\u0061\\u0062"], length, keys_unsorted' (\\u is one backslash and u)
    //   -> {"é€":1,"ab":2,"\n\"":3}  1  2  3  null  3  ["é€","ab","\n\""]
    Workspace workspace = newWorkspace();
    JqObject o = object(workspace, bytes("{\"\\u00e9\\u20AC\":1,\"\\u0061\\u0062\":2,\"\\n\\\"\":3}"));
    assertThat(number(o.get("é€"), "get(é€)")).as("get of é€").isEqualTo(1L);
    assertThat(number(o.get("ab"), "get(ab)")).as("get of ab").isEqualTo(2L);
    assertThat(number(o.get("\n\""), "get(LF quote)")).as("get of LF and a quote").isEqualTo(3L);
    // The spelling is not the key.
    assertThat(o.get("\\u0061\\u0062")).as("get of the escaped spelling itself").isNull();
    assertThat(o.get("a")).as("get of a prefix of the decoded key").isNull();
    assertThat(o.size()).as("size()").isEqualTo(3);
    assertThat(keys(o)).as("forEach's keys and values").containsExactly("é€=1", "ab=2", "\n\"=3");

    // string.rec escaped-key-field: jq-ref -c .a on {"\\u0061":1} -> 1.
    JqValue root = object(workspace, bytes("{\"\\u0061\":1}"));
    List<String> results = new ArrayList<>();
    JqEngine.builder().build().compile(".a").run(root, Bindings.builder().build(), v -> results.add(v.toJson()), workspace);
    assertThat(results).as(".a on {\"\\\\u0061\":1}").containsExactly("1");
  }

  @Test
  void escapedDuplicateIsTheSameKey() {
    // jq-ref 1.8.2: {"é":1,"\\u00e9":2,"x":3,"\\u0078":4} with -c '., length, keys_unsorted'
    //   -> {"é":2,"x":4}  2  ["é","x"]
    // string.rec escaped-duplicate-key and escaped-duplicate-key-field: {"a":1,"\\u0061":2} -> {"a":2} and .a -> 2.
    Workspace workspace = newWorkspace();
    JqObject o = object(workspace, bytes("{\"é\":1,\"\\u00e9\":2,\"x\":3,\"\\u0078\":4}"));
    assertThat(o.size()).as("size()").isEqualTo(2);
    assertThat(number(o.get("é"), "get(é)")).as("get of é: the last value").isEqualTo(2L);
    assertThat(number(o.get("x"), "get(x)")).as("get of x: the last value").isEqualTo(4L);
    assertThat(keys(o)).as("forEach: each key once, at its first position, with its last value").containsExactly("é=2", "x=4");
    assertThat(o.toJson()).as("toJson()").isEqualTo("{\"é\":2,\"x\":4}");

    // The first spelling escaped, the later one plain: the printed key is the decoded first one.
    // jq-ref 1.8.2: {"\\u0061":1,"a":2} -> {"a":2}, keys ["a"], length 1, .a 2.
    JqObject first = object(workspace, bytes("{\"\\u0061\":1,\"a\":2}"));
    assertThat(first.size()).as("size() of {\"\\\\u0061\":1,\"a\":2}").isEqualTo(1);
    assertThat(keys(first)).as("forEach of {\"\\\\u0061\":1,\"a\":2}").containsExactly("a=2");
  }

  @Test
  void replacedKeyMatchesUfffd() {
    // jq-ref 1.8.2: {"\\udfaa":0} -> {"�":0} (json.rec i_object_key_lone_2nd_surrogate);
    //   {"\\udfaa":0,"\xff":1,"\xc0\x80":2} -> {"�":1,"��":2}, length 2, keys ["�","��"]: the lone low surrogate and
    //   ff are both one U+FFFD, so they are one key.
    Workspace workspace = newWorkspace();
    JqObject lone = object(workspace, bytes("{\"\\udfaa\":0}"));
    assertThat(number(lone.get("\ufffd"), "get(U+FFFD)")).as("get of U+FFFD in {\"\\\\udfaa\":0}").isZero();
    // A Java key with a lone surrogate is no code point and never matches (#32 keyWithALoneSurrogateIsMissing).
    assertThat(lone.get("\udfaa")).as("get of a lone low surrogate in {\"\\\\udfaa\":0}").isNull();

    JqObject o = object(workspace, bytes("{\"\\udfaa\":0,\"", HEX.parseHex("ff"), "\":1,\"", HEX.parseHex("c0 80"), "\":2}"));
    assertThat(o.size()).as("size()").isEqualTo(2);
    assertThat(number(o.get("\ufffd"), "get(U+FFFD)")).as("get of one U+FFFD: the last value").isEqualTo(1L);
    assertThat(number(o.get("\ufffd\ufffd"), "get(U+FFFD U+FFFD)")).as("get of two U+FFFD").isEqualTo(2L);
    assertThat(keys(o)).as("forEach's keys").containsExactly("\ufffd=1", "\ufffd\ufffd=2");
  }
}
