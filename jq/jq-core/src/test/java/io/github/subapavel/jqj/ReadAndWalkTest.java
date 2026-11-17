package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static io.github.subapavel.jqj.TestDocuments.segment;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #32 "Read to values" at the public API seam: {@code Workspace.read}, {@code JsonDoc.root()} and the read
 * accessors of every kind (ADR-0002, ADR-0003, ADR-0012, ADR-0015, ADR-0016, ADR-0026). Exported types only.
 */
class ReadAndWalkTest {

  @Test
  void walksEveryKindOfANestedDocument() {
    // jq-ref 1.8.2, observed 2026-11-11:
    //   printf -- ' {\t"a"\r\n:\n[ 1 ,\t{"b" :null}\r,[true\n,false ]] , "s":"x"\t,"e":{ },"z":[\r\n]}\n' \
    //     | jq-ref -c '., keys_unsorted, length, (.a|length), (.a[1]|length), (.a[2]|length), (.e|length), (.z|length)'
    //   -> {"a":[1,{"b":null},[true,false]],"s":"x","e":{},"z":[]}  ["a","s","e","z"]  4  3  1  2  0  0   (rc 0)
    Workspace workspace = newWorkspace();
    JsonDoc doc =
        read(workspace, " {\t\"a\"\r\n:\n[ 1 ,\t{\"b\" :null}\r,[true\n,false ]] , \"s\":\"x\"\t,\"e\":{ },\"z\":[\r\n]}\n");

    JqObject root = kind(JqObject.class, doc.root(), ".");
    assertThat(root.size()).as("size of .").isEqualTo(4);
    List<String> keys = new ArrayList<>();
    List<JqValue> values = new ArrayList<>();
    root.forEach(
        (k, v) -> {
          keys.add(k);
          values.add(v);
        });
    assertThat(keys).as("key order of .").containsExactly("a", "s", "e", "z");

    JqArray a = kind(JqArray.class, root.get("a"), ".a");
    assertThat(a.length()).as("length of .a").isEqualTo(3);
    JqNumber one = kind(JqNumber.class, a.get(0), ".a[0]");
    assertThat(one.fitsLong()).as(".a[0] fits a long").isTrue();
    assertThat(one.longValue()).as(".a[0]").isEqualTo(1L);
    JqObject b = kind(JqObject.class, a.get(1), ".a[1]");
    assertThat(b.size()).as("size of .a[1]").isEqualTo(1);
    assertThat(b.get("b")).as(".a[1].b").isSameAs(JqNull.NULL);
    JqArray booleans = kind(JqArray.class, a.get(2), ".a[2]");
    assertThat(booleans.length()).as("length of .a[2]").isEqualTo(2);
    assertThat(booleans.get(0)).as(".a[2][0]").isSameAs(JqBool.TRUE);
    assertThat(booleans.get(1)).as(".a[2][1]").isSameAs(JqBool.FALSE);

    assertThat(kind(JqString.class, root.get("s"), ".s").value()).as(".s").isEqualTo("x");
    JqObject e = kind(JqObject.class, root.get("e"), ".e");
    assertThat(e.size()).as("size of .e").isZero();
    List<String> keysOfE = new ArrayList<>();
    e.forEach((k, v) -> keysOfE.add(k));
    assertThat(keysOfE).as("forEach over .e (witness: forEach over . called four times)").isEmpty();
    assertThat(kind(JqArray.class, root.get("z"), ".z").length()).as("length of .z").isZero();

    // forEach hands each key its own value, in key order.
    assertThat(kind(JqArray.class, values.get(0), "forEach a").length()).as("forEach a").isEqualTo(3);
    assertThat(kind(JqString.class, values.get(1), "forEach s").value()).as("forEach s").isEqualTo("x");
    assertThat(kind(JqObject.class, values.get(2), "forEach e").size()).as("forEach e").isZero();
    assertThat(kind(JqArray.class, values.get(3), "forEach z").length()).as("forEach z").isZero();
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 64);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void readThenWalkMatchesTheGeneratedTree(long seed) {
    Generator generator = new Generator(new SplittableRandom(seed));
    Node tree = generator.node(1);
    StringBuilder json = new StringBuilder();
    generator.write(tree, json);

    Workspace workspace = newWorkspace();
    assertWalk(tree, read(workspace, json.toString()).root(), "$");
  }

  @Test
  void duplicateKeysKeepFirstPositionAndLastValue() {
    // ADR-0002 (observed on jq-ref 1.8.2), and again here on 2026-11-11:
    //   printf -- '{"x":0,"a":1,"b":2,"a":3,"x":9}' | jq-ref -c '., keys_unsorted, length, .a'
    //   -> {"x":9,"a":3,"b":2}  ["x","a","b"]  3  3   (rc 0)
    Workspace workspace = newWorkspace();
    JqObject object = kind(JqObject.class, read(workspace, "{\"x\":0,\"a\":1,\"b\":2,\"a\":3,\"x\":9}").root(), ".");
    assertThat(object.size()).as("size").isEqualTo(3);
    List<String> keys = new ArrayList<>();
    List<Long> values = new ArrayList<>();
    object.forEach(
        (k, v) -> {
          keys.add(k);
          values.add(kind(JqNumber.class, v, "forEach " + k).longValue());
        });
    assertThat(keys).as("forEach keys: first position").containsExactly("x", "a", "b");
    assertThat(values).as("forEach values: last value").containsExactly(9L, 3L, 2L);
    assertThat(kind(JqNumber.class, object.get("a"), ".a").longValue()).as(".a").isEqualTo(3L);
    assertThat(kind(JqNumber.class, object.get("x"), ".x").longValue()).as(".x").isEqualTo(9L);
    assertThat(kind(JqNumber.class, object.get("b"), ".b").longValue()).as(".b").isEqualTo(2L);

    // jq-ref 1.8.2, observed 2026-11-11: printf -- '{"a":1,"a":2}' | jq-ref -c '., keys, length, .a'
    //   -> {"a":2}  ["a"]  1  2   (rc 0). The duplicate is the key right after the first.
    JqObject twice = kind(JqObject.class, read(workspace, "{\"a\":1,\"a\":2}").root(), "{\"a\":1,\"a\":2}");
    assertThat(twice.size()).as("size of {\"a\":1,\"a\":2}").isEqualTo(1);
    List<String> twiceKeys = new ArrayList<>();
    List<Long> twiceValues = new ArrayList<>();
    twice.forEach(
        (k, v) -> {
          twiceKeys.add(k);
          twiceValues.add(kind(JqNumber.class, v, "forEach " + k).longValue());
        });
    assertThat(twiceKeys).as("forEach keys of {\"a\":1,\"a\":2}").containsExactly("a");
    assertThat(twiceValues).as("forEach values of {\"a\":1,\"a\":2}").containsExactly(2L);
    assertThat(kind(JqNumber.class, twice.get("a"), ".a").longValue()).as(".a of {\"a\":1,\"a\":2}").isEqualTo(2L);
  }

  /**
   * ADR-0012 FITS_LONG: an integer token, not {@code -0}, inside the long range. jq-ref 1.8.2 reads every
   * token below as a number (observed 2026-11-11):
   * {@code printf -- '[9223372036854775807,-9223372036854775808,9223372036854775808,-9223372036854775809,-0,1.0,1e2,0]' | jq-ref -c 'map(type)'}
   * gives eight "number"; {@code 1e400} and {@code 18446744073709551616} read as numbers too (rc 0).
   */
  static Stream<Arguments> numberTokens() {
    return Stream.of(
        argumentSet("9223372036854775807, the largest long, fits", "9223372036854775807", 9223372036854775807L),
        argumentSet("-9223372036854775808, the smallest long, fits", "-9223372036854775808", -9223372036854775808L),
        argumentSet("0 fits (witness for -0)", "0", 0L),
        argumentSet("9223372036854775808, one above the long range", "9223372036854775808", null),
        argumentSet("-9223372036854775809, one below the long range", "-9223372036854775809", null),
        argumentSet("-0 is not a long", "-0", null),
        argumentSet("1.0 has a fraction", "1.0", null),
        argumentSet("1e2 has an exponent", "1e2", null),
        // jq-ref 1.8.2, observed 2026-11-11: printf -- '1e+2' | jq-ref -c '., type'  ->  1E+2  "number"  (rc 0)
        argumentSet("1e+2 has a signed exponent", "1e+2", null),
        argumentSet("1e400 does not fit a double either", "1e400", null),
        argumentSet("18446744073709551616, 2^64", "18446744073709551616", null));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("numberTokens")
  void fitsLongFollowsAdr0012(String token, Long expected) {
    Workspace workspace = newWorkspace();
    JqNumber number = kind(JqNumber.class, read(workspace, token).root(), token);
    if (expected != null) {
      assertThat(number.fitsLong()).as("%s fits a long", token).isTrue();
      assertThat(number.longValue()).as("longValue of %s", token).isEqualTo(expected.longValue());
    } else {
      assertThat(number.fitsLong()).as("%s fits a long", token).isFalse();
      assertThatThrownBy(number::longValue).as("longValue of %s", token).isInstanceOf(ArithmeticException.class);
    }
  }

  @Test
  void plainStringReadsValueAndReadOnlyUtf8() {
    // jq-ref 1.8.2, observed 2026-11-11:
    //   printf -- '["","abc"]' | jq-ref -c '., map(length), map(utf8bytelength)'  ->  ["","abc"]  [0,3]  [0,3]
    Workspace workspace = newWorkspace();
    MemorySegment host = segment("\"\"");
    assertThat(host.isReadOnly()).as("witness: the host's segment is writable").isFalse();
    JqString empty = kind(JqString.class, workspace.read(host).root(), "\"\"");
    assertThat(empty.value()).as("value of \"\"").isEqualTo("");
    assertThat(empty.utf8().byteSize()).as("utf8 size of \"\"").isZero();
    assertThat(empty.utf8().isReadOnly()).as("utf8 of \"\" is read-only").isTrue();

    JqString abc = kind(JqString.class, read(workspace, "\"abc\"").root(), "\"abc\"");
    assertThat(abc.value()).as("value of \"abc\"").isEqualTo("abc");
    assertThat(abc.utf8().toArray(JAVA_BYTE)).as("utf8 of \"abc\"").isEqualTo(new byte[] {'a', 'b', 'c'});
    assertThat(abc.utf8().isReadOnly()).as("utf8 of \"abc\" is read-only").isTrue();

    JqArray both = kind(JqArray.class, read(workspace, "[\"\",\"abc\"]").root(), "[\"\",\"abc\"]");
    JqString first = kind(JqString.class, both.get(0), "[0]");
    JqString second = kind(JqString.class, both.get(1), "[1]");
    assertThat(first.value()).as("[0]").isEqualTo("");
    assertThat(first.utf8().byteSize()).as("utf8 size of [0]").isZero();
    assertThat(second.value()).as("[1]").isEqualTo("abc");
    assertThat(second.utf8().toArray(JAVA_BYTE)).as("utf8 of [1]").isEqualTo(new byte[] {'a', 'b', 'c'});
    assertThat(second.utf8().isReadOnly()).as("utf8 of [1] is read-only").isTrue();
  }

  /** The document every segment kind carries; jq-ref 1.8.2 reads it as {"k":[10,"vw"]}. */
  private static final String SEGMENT_DOC = "{\"k\":[10,\"vw\"]}";

  /** The index of the {@code v} of {@code "vw"} in {@link #SEGMENT_DOC}. */
  private static final int V_AT = 10;

  @Test
  void segmentsAreReadInPlaceOrCopied() {
    byte[] doc = SEGMENT_DOC.getBytes(UTF_8);
    assertThat(doc[V_AT]).as("fixture: the v of \"vw\"").isEqualTo((byte) 'v');
    Workspace workspace = newWorkspace();

    // ADR-0015 "Reader loop": a writable byte[]-backed heap segment is read in place, and ADR-0016 says utf8()
    // of a tag-5 view is a slice of the document without a copy. The slice starts at 3 of a larger array whose
    // bytes before and after it are not JSON, so reading outside the slice fails.
    byte[] larger = new byte[3 + doc.length + 3];
    larger[0] = 'x';
    larger[1] = 'y';
    larger[2] = 'z';
    System.arraycopy(doc, 0, larger, 3, doc.length);
    larger[3 + doc.length] = '!';
    larger[4 + doc.length] = '!';
    larger[5 + doc.length] = '!';
    JqString inPlace = walkSegmentDoc(workspace.read(MemorySegment.ofArray(larger).asSlice(3, doc.length)), "slice at 3");
    MemorySegment inPlaceUtf8 = inPlace.utf8();
    larger[3 + V_AT] = 'W';
    assertThat(inPlaceUtf8.get(JAVA_BYTE, 0)).as("slice at 3 is read in place: the host's later write shows").isEqualTo((byte) 'W');

    // Any other segment is copied once: a later write by the host does not show.
    byte[] readOnlyArray = doc.clone();
    JqString readOnly = walkSegmentDoc(workspace.read(MemorySegment.ofArray(readOnlyArray).asReadOnly()), "read-only heap");
    MemorySegment readOnlyUtf8 = readOnly.utf8();
    readOnlyArray[V_AT] = 'W';
    assertCopied(readOnly, readOnlyUtf8, "read-only heap");

    long[] longs = new long[(doc.length + 7) / 8];
    MemorySegment longBacked = MemorySegment.ofArray(longs).asSlice(0, doc.length);
    MemorySegment.copy(MemorySegment.ofArray(doc), 0, longBacked, 0, doc.length);
    JqString fromLongs = walkSegmentDoc(workspace.read(longBacked), "long[]-backed");
    MemorySegment fromLongsUtf8 = fromLongs.utf8();
    longBacked.set(JAVA_BYTE, V_AT, (byte) 'W');
    assertThat(longBacked.get(JAVA_BYTE, V_AT)).as("witness: the long[] segment was written").isEqualTo((byte) 'W');
    assertCopied(fromLongs, fromLongsUtf8, "long[]-backed");

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeSegment = arena.allocate(doc.length);
      MemorySegment.copy(MemorySegment.ofArray(doc), 0, nativeSegment, 0, doc.length);
      JqString fromNative = walkSegmentDoc(workspace.read(nativeSegment), "native");
      MemorySegment fromNativeUtf8 = fromNative.utf8();
      nativeSegment.set(JAVA_BYTE, V_AT, (byte) 'W');
      assertThat(nativeSegment.get(JAVA_BYTE, V_AT)).as("witness: the native segment was written").isEqualTo((byte) 'W');
      assertCopied(fromNative, fromNativeUtf8, "native");
    }
  }

  /** Walks {@link #SEGMENT_DOC} and returns the view of {@code "vw"}. */
  private static JqString walkSegmentDoc(JsonDoc doc, String source) {
    JqObject root = kind(JqObject.class, doc.root(), source + " .");
    assertThat(root.size()).as("%s size of .", source).isEqualTo(1);
    JqArray k = kind(JqArray.class, root.get("k"), source + " .k");
    assertThat(k.length()).as("%s length of .k", source).isEqualTo(2);
    assertThat(kind(JqNumber.class, k.get(0), source + " .k[0]").longValue()).as("%s .k[0]", source).isEqualTo(10L);
    JqString vw = kind(JqString.class, k.get(1), source + " .k[1]");
    assertThat(vw.value()).as("%s .k[1]", source).isEqualTo("vw");
    assertThat(vw.utf8().toArray(JAVA_BYTE)).as("%s utf8 of .k[1]", source).isEqualTo(new byte[] {'v', 'w'});
    return vw;
  }

  private static void assertCopied(JqString vw, MemorySegment keptUtf8, String source) {
    assertThat(keptUtf8.get(JAVA_BYTE, 0)).as("%s is copied: a utf8() slice kept from before the host's write", source).isEqualTo((byte) 'v');
    assertThat(vw.value()).as("%s is copied: value() after the host's write", source).isEqualTo("vw");
  }

  @Test
  void accessorEdgesFollowTheAdr() {
    // ADR-0026: get(i) outside [0, length) throws IndexOutOfBoundsException; a missing key is Java null.
    Workspace workspace = newWorkspace();
    JqArray pair = kind(JqArray.class, read(workspace, "[1,2]").root(), "[1,2]");
    // Witness: both indexes inside [0, length) answer.
    assertThat(kind(JqNumber.class, pair.get(0), "[0]").longValue()).as("[0]").isEqualTo(1L);
    assertThat(kind(JqNumber.class, pair.get(1), "[1]").longValue()).as("[1]").isEqualTo(2L);
    assertThatIndexOutOfBoundsException().as("get(-1)").isThrownBy(() -> pair.get(-1));
    assertThatIndexOutOfBoundsException().as("get(length)").isThrownBy(() -> pair.get(2));
    assertThatIndexOutOfBoundsException().as("get(Integer.MAX_VALUE)").isThrownBy(() -> pair.get(Integer.MAX_VALUE));
    assertThatIndexOutOfBoundsException().as("get(Integer.MIN_VALUE)").isThrownBy(() -> pair.get(Integer.MIN_VALUE));

    JqArray empty = kind(JqArray.class, read(workspace, "[]").root(), "[]");
    assertThat(empty.length()).as("length of []").isZero();
    assertThatIndexOutOfBoundsException().as("get(0) of []").isThrownBy(() -> empty.get(0));

    // jq-ref 1.8.2, observed 2026-11-11: printf -- '{"a":null}' | jq-ref -c '.a, .b, has("a"), has("b")'
    //   -> null  null  true  false. The accessor tells the present null from the absent key (ADR-0026).
    JqObject withNull = kind(JqObject.class, read(workspace, "{\"a\":null}").root(), "{\"a\":null}");
    assertThat(withNull.get("a")).as("a present null").isSameAs(JqNull.NULL);
    assertThat(withNull.get("b")).as("a missing key").isNull();
    assertThat(withNull.get("")).as("the empty key, missing").isNull();

    JqObject ab = kind(JqObject.class, read(workspace, "{\"ab\":1}").root(), "{\"ab\":1}");
    assertThat(kind(JqNumber.class, ab.get("ab"), ".ab").longValue()).as("witness .ab").isEqualTo(1L);
    assertThat(ab.get("a")).as("a prefix of the key").isNull();
    assertThat(ab.get("abc")).as("the key is a prefix").isNull();
    assertThat(ab.get("ac")).as("same length, last byte differs").isNull();

    JqObject none = kind(JqObject.class, read(workspace, "{}").root(), "{}");
    assertThat(none.get("a")).as("a key of {}").isNull();
  }

  @Test
  void keyWithALoneSurrogateIsMissing() {
    // ADR-0026: a missing key is Java null. A String with a lone surrogate has no UTF-8 form, so no key of a
    // document equals it; String.getBytes(UTF_8) would turn the surrogate into '?', and a compare of those bytes
    // would find the key "?". jq-ref 1.8.2, observed 2026-11-11 (\\u stands for one backslash and u):
    //   printf -- '{"a?":1}' | jq-ref -c '.["a?"], .["a\\udc00"], has("a\\udc00"), ("a\\udc00" | explode)'
    //     -> 1  null  false  [97,65533]   (rc 0: jq makes the lone low surrogate U+FFFD, which is not '?')
    //   printf -- '{"?":1}' | jq-ref -c '.["?"], .["\\ud800"], has("\\ud800")'
    //     -> jq: error: Invalid \\uXXXX\\uXXXX surrogate pair escape ... (a compile error, rc 3): no jq string
    //        holds a lone high surrogate.
    Workspace workspace = newWorkspace();
    JqObject question = kind(JqObject.class, read(workspace, "{\"?\":1}").root(), "{\"?\":1}");
    assertThat(kind(JqNumber.class, question.get("?"), ".\"?\"").longValue()).as("witness .\"?\"").isEqualTo(1L);
    assertThat(question.get("\uD800")).as("a lone high surrogate against the key \"?\"").isNull();

    JqObject aQuestion = kind(JqObject.class, read(workspace, "{\"a?\":1}").root(), "{\"a?\":1}");
    assertThat(kind(JqNumber.class, aQuestion.get("a?"), ".\"a?\"").longValue()).as("witness .\"a?\"").isEqualTo(1L);
    assertThat(aQuestion.get("a\uDC00")).as("a then a lone low surrogate against the key \"a?\"").isNull();
  }

  // ---- The generated tree of readThenWalkMatchesTheGeneratedTree ----

  /** A value of the source tree; the walk of the read document must match it. */
  private sealed interface Node permits Lit, Num, Str, Arr, Obj {}

  /** null, true or false: its token and the enum the walk must give. */
  private record Lit(String token, JqValue value) implements Node {}

  /** A number token and its long, or null when the token does not fit a long (ADR-0012 FITS_LONG). */
  private record Num(String token, Long asLong) implements Node {}

  /** A string of printable ASCII without {@code "} or {@code \}, so it is tag 5 (no decoding needed). */
  private record Str(String value) implements Node {}

  private record Arr(List<Node> elements) implements Node {}

  /** Keys are distinct; duplicate keys have their own test. */
  private record Obj(List<String> keys, List<Node> values) implements Node {}

  private static final long[] LONG_EDGES = {
    -9223372036854775808L, -9223372036854775807L, -1L, 0L, 1L, 9223372036854775806L, 9223372036854775807L
  };

  private static final String[] NOT_A_LONG = {
    "9223372036854775808", "-9223372036854775809", "-0", "1.5", "1e2", "-2.5E-3", "0.0", "18446744073709551616", "1e400"
  };

  private static final String WHITESPACE = " \t\r\n";

  /** Makes a random tree of depth at most 20 and at most about 300 nodes, and writes it as JSON. */
  private static final class Generator {
    private static final int MAX_DEPTH = 20;
    private final RandomGenerator random;
    private final int containerPercent;
    private int budget = 300;

    Generator(RandomGenerator random) {
      this.random = random;
      this.containerPercent = random.nextInt(30, 80);
    }

    Node node(int depth) {
      budget--;
      if (depth <= MAX_DEPTH && budget > 0 && random.nextInt(100) < containerPercent) {
        return random.nextBoolean() ? array(depth) : object(depth);
      }
      return scalar();
    }

    private Node array(int depth) {
      int n = random.nextInt(0, 5);
      List<Node> elements = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        elements.add(node(depth + 1));
      }
      return new Arr(elements);
    }

    private Node object(int depth) {
      int n = random.nextInt(0, 5);
      Set<String> keys = new LinkedHashSet<>();
      while (keys.size() < n) {
        keys.add(text(random.nextInt(0, 6)));
      }
      List<Node> values = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        values.add(node(depth + 1));
      }
      return new Obj(List.copyOf(keys), values);
    }

    private Node scalar() {
      return switch (random.nextInt(7)) {
        case 0 -> new Lit("null", JqNull.NULL);
        case 1 -> new Lit("true", JqBool.TRUE);
        case 2 -> new Lit("false", JqBool.FALSE);
        case 3 -> {
          long edge = LONG_EDGES[random.nextInt(LONG_EDGES.length)];
          yield new Num(Long.toString(edge), edge);
        }
        case 4 -> {
          long any = random.nextLong();
          yield new Num(Long.toString(any), any);
        }
        case 5 -> new Num(NOT_A_LONG[random.nextInt(NOT_A_LONG.length)], null);
        default -> new Str(text(random.nextInt(0, 9)));
      };
    }

    /** Printable ASCII, 0x20 to 0x7E, without the quote and the backslash. */
    private String text(int length) {
      StringBuilder s = new StringBuilder();
      while (s.length() < length) {
        char c = (char) random.nextInt(0x20, 0x7F);
        if (c != '"' && c != '\\') {
          s.append(c);
        }
      }
      return s.toString();
    }

    /** Zero to two of SP, TAB, CR and LF. */
    private void space(StringBuilder out) {
      int n = random.nextInt(0, 3);
      for (int i = 0; i < n; i++) {
        out.append(WHITESPACE.charAt(random.nextInt(WHITESPACE.length())));
      }
    }

    void write(Node node, StringBuilder out) {
      space(out);
      switch (node) {
        case Lit l -> out.append(l.token());
        case Num n -> out.append(n.token());
        case Str t -> out.append('"').append(t.value()).append('"');
        case Arr a -> {
          out.append('[');
          for (int i = 0; i < a.elements().size(); i++) {
            if (i > 0) {
              out.append(',');
            }
            write(a.elements().get(i), out);
          }
          space(out);
          out.append(']');
        }
        case Obj o -> {
          out.append('{');
          for (int i = 0; i < o.keys().size(); i++) {
            if (i > 0) {
              out.append(',');
            }
            space(out);
            out.append('"').append(o.keys().get(i)).append('"');
            space(out);
            out.append(':');
            write(o.values().get(i), out);
          }
          space(out);
          out.append('}');
        }
      }
      space(out);
    }
  }

  private static void assertWalk(Node expected, JqValue actual, String path) {
    switch (expected) {
      case Lit l -> assertThat(actual).as(path).isSameAs(l.value());
      case Num n -> {
        JqNumber number = kind(JqNumber.class, actual, path);
        if (n.asLong() != null) {
          assertThat(number.fitsLong()).as("%s (%s) fits a long", path, n.token()).isTrue();
          assertThat(number.longValue()).as("%s (%s)", path, n.token()).isEqualTo(n.asLong().longValue());
        } else {
          assertThat(number.fitsLong()).as("%s (%s) fits a long", path, n.token()).isFalse();
          assertThatThrownBy(number::longValue).as("%s (%s) longValue", path, n.token()).isInstanceOf(ArithmeticException.class);
        }
      }
      case Str t -> {
        JqString string = kind(JqString.class, actual, path);
        assertThat(string.value()).as(path).isEqualTo(t.value());
        assertThat(string.utf8().toArray(JAVA_BYTE)).as("%s utf8", path).isEqualTo(t.value().getBytes(US_ASCII));
      }
      case Arr a -> {
        JqArray array = kind(JqArray.class, actual, path);
        assertThat(array.length()).as("%s length", path).isEqualTo(a.elements().size());
        for (int i = 0; i < a.elements().size(); i++) {
          assertWalk(a.elements().get(i), array.get(i), path + "[" + i + "]");
        }
      }
      case Obj o -> {
        JqObject object = kind(JqObject.class, actual, path);
        assertThat(object.size()).as("%s size", path).isEqualTo(o.keys().size());
        List<String> keys = new ArrayList<>();
        List<JqValue> values = new ArrayList<>();
        object.forEach(
            (k, v) -> {
              keys.add(k);
              values.add(v);
            });
        assertThat(keys).as("%s key order", path).containsExactlyElementsOf(o.keys());
        for (int i = 0; i < o.keys().size(); i++) {
          String key = o.keys().get(i);
          JqValue byKey = object.get(key);
          assertWalk(o.values().get(i), byKey, path + ".\"" + key + "\"");
          // ADR-0026: views are equal when they share a document and a tape index, so forEach's value is the
          // one get gave; comparing instead of walking it again keeps the walk linear.
          assertThat(values.get(i)).as("%s.\"%s\" from forEach", path, key).isEqualTo(byKey);
        }
      }
    }
  }
}
