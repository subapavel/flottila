package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.assertStale;
import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * GLOSSARY "Stale view", ADR-0004 and ADR-0026: a view of a closed or superseded document throws STALE_VIEW
 * (a JqTermination, exit code 5) and never reads a later document's bytes. Exported types only.
 */
class StaleViewTest {

  @Test
  void viewUsedAfterCloseIsStale() {
    Workspace workspace = newWorkspace();
    JsonDoc doc = read(workspace, "{\"n\":1,\"s\":\"x\",\"a\":[true],\"o\":{\"k\":null}}");
    JqObject root = kind(JqObject.class, doc.root(), ".");
    JqNumber n = kind(JqNumber.class, root.get("n"), ".n");
    JqString s = kind(JqString.class, root.get("s"), ".s");
    JqArray a = kind(JqArray.class, root.get("a"), ".a");
    JqObject o = kind(JqObject.class, root.get("o"), ".o");
    // A child view taken before close and first used after it (the draft: checked when first used).
    JqArray untouched = kind(JqArray.class, root.get("a"), ".a, kept unused");

    // Witness: before close every accessor answers.
    assertThat(doc.root()).as("root() before close").isInstanceOf(JqObject.class);
    assertThat(root.size()).as("size of . before close").isEqualTo(4);
    assertThat(kind(JqNumber.class, root.get("n"), ".n").longValue()).as(".n before close").isEqualTo(1L);
    List<String> keys = new ArrayList<>();
    root.forEach((k, v) -> keys.add(k));
    assertThat(keys).as("forEach over . before close").containsExactly("n", "s", "a", "o");
    assertThat(n.fitsLong()).as(".n fits a long before close").isTrue();
    assertThat(n.longValue()).as(".n longValue before close").isEqualTo(1L);
    assertThat(s.value()).as(".s before close").isEqualTo("x");
    assertThat(s.utf8().byteSize()).as(".s utf8 before close").isEqualTo(1L);
    assertThat(a.length()).as("length of .a before close").isEqualTo(1);
    assertThat(a.get(0)).as(".a[0] before close").isSameAs(JqBool.TRUE);
    assertThat(o.size()).as("size of .o before close").isEqualTo(1);
    assertThat(o.get("k")).as(".o.k before close").isSameAs(JqNull.NULL);

    doc.close();

    Map<String, ThrowingCallable> calls = new LinkedHashMap<>();
    calls.put("JsonDoc.root()", doc::root);
    calls.put("JqObject.size()", root::size);
    calls.put("JqObject.get(String)", () -> root.get("n"));
    calls.put("JqObject.forEach(BiConsumer)", () -> root.forEach((k, v) -> keys.add(k)));
    calls.put("JqNumber.fitsLong()", n::fitsLong);
    calls.put("JqNumber.longValue()", n::longValue);
    calls.put("JqString.value()", s::value);
    calls.put("JqString.utf8()", s::utf8);
    calls.put("JqArray.length()", a::length);
    calls.put("JqArray.get(int)", () -> a.get(0));
    calls.put("JqObject.size() of a child", o::size);
    calls.put("JqObject.get(String) of a child", () -> o.get("k"));
    calls.put("JqArray.length() of a child first used after close", untouched::length);
    calls.forEach((what, call) -> assertStale(call, what));
    assertThat(keys).as("forEach on a stale object calls nothing").containsExactly("n", "s", "a", "o");
  }

  @Test
  void nextReadMakesEarlierViewsStale() {
    Workspace workspace = newWorkspace();
    JsonDoc first = read(workspace, "[1]");
    JqArray one = kind(JqArray.class, first.root(), "[1]");
    JqNumber oneAt0 = kind(JqNumber.class, one.get(0), "[1][0]");
    assertThat(one.length()).as("witness: [1] before the next read").isEqualTo(1);
    assertThat(oneAt0.longValue()).as("witness: [1][0] before the next read").isEqualTo(1L);

    // [2,3] lands on the same reused tape: a view of [1] that answered would read [2,3]'s entries.
    JsonDoc second = read(workspace, "[2,3]");
    assertStale(first::root, "root() of [1] after reading [2,3]");
    assertStale(one::length, "length() of [1] after reading [2,3]");
    assertStale(() -> one.get(0), "get(0) of [1] after reading [2,3]");
    assertStale(oneAt0::longValue, "longValue() of [1][0] after reading [2,3]");
    assertStale(oneAt0::fitsLong, "fitsLong() of [1][0] after reading [2,3]");
    // Witness: the new root reads.
    JqArray twoThree = kind(JqArray.class, second.root(), "[2,3]");
    assertThat(twoThree.length()).as("length of [2,3]").isEqualTo(2);
    JqNumber twoAt0 = kind(JqNumber.class, twoThree.get(0), "[2,3][0]");
    assertThat(twoAt0.longValue()).as("[2,3][0]").isEqualTo(2L);
    assertThat(kind(JqNumber.class, twoThree.get(1), "[2,3][1]").longValue()).as("[2,3][1]").isEqualTo(3L);

    // ADR-0026: a read makes the previous document stale as it starts, a failed read included.
    assertThatThrownBy(() -> read(workspace, "[2,")).as("the malformed read").isInstanceOf(JqSyntaxException.class);
    assertStale(second::root, "root() of [2,3] after a malformed read");
    assertStale(twoThree::length, "length() of [2,3] after a malformed read");
    assertStale(() -> twoThree.get(1), "get(1) of [2,3] after a malformed read");
    assertStale(twoAt0::longValue, "longValue() of [2,3][0] after a malformed read");

    // Witness: the Workspace still reads, and the new root answers.
    JqArray four = kind(JqArray.class, read(workspace, "[4]").root(), "[4]");
    assertThat(four.length()).as("length of [4]").isEqualTo(1);
    assertThat(kind(JqNumber.class, four.get(0), "[4][0]").longValue()).as("[4][0]").isEqualTo(4L);
  }

  @Test
  void readInsideForEachMakesTheRestOfTheWalkStale() {
    // ADR-0016: "A mistake gives STALE_VIEW, never a wrong value". The callback reads another document into the
    // same Workspace, so the walk's next step must not hand over that document's keys or values.
    // jq-ref 1.8.2, observed 2026-11-11: printf -- '{"a":1,"b":2,"c":3}' | jq-ref -c '[keys_unsorted, length]'
    //   -> [["a","b","c"],3]   (rc 0)
    Workspace workspace = newWorkspace();
    JqObject abc = kind(JqObject.class, read(workspace, "{\"a\":1,\"b\":2,\"c\":3}").root(), "{a,b,c}");
    List<String> calls = new ArrayList<>();
    List<JsonDoc> readInside = new ArrayList<>();
    assertStale(
        () ->
            abc.forEach(
                (k, v) -> {
                  if (readInside.isEmpty()) {
                    calls.add(k + "=" + kind(JqNumber.class, v, "forEach " + k).longValue());
                    readInside.add(read(workspace, "{\"x\":9,\"y\":8,\"z\":7}"));
                  } else {
                    // A later call touches no view, so only forEach's own step can throw STALE_VIEW here.
                    calls.add(k);
                  }
                }),
        "forEach of {a,b,c} after its callback read another document");
    assertThat(calls).as("calls before the re-read only, with the first document's key and value").containsExactly("a=1");
    // Witness: the document read inside the callback is the current one and reads.
    assertThat(readInside).as("documents read inside the callback").hasSize(1);
    JqObject xyz = kind(JqObject.class, readInside.get(0).root(), "{x,y,z}");
    assertThat(xyz.size()).as("size of {x,y,z}").isEqualTo(3);
    assertThat(kind(JqNumber.class, xyz.get("y"), ".y").longValue()).as(".y of {x,y,z}").isEqualTo(8L);
  }

  @Test
  void readDuringAnIndexWalkMakesTheRestStale() {
    // The index walk's counterpart of readInsideForEachMakesTheRestOfTheWalkStale: each get(i) checks again.
    Workspace workspace = newWorkspace();
    JqArray three = kind(JqArray.class, read(workspace, "[1,2,3]").root(), "[1,2,3]");
    assertThat(kind(JqNumber.class, three.get(0), "[1,2,3][0]").longValue()).as("witness: [0] before the re-read").isEqualTo(1L);
    JqArray other = kind(JqArray.class, read(workspace, "[7,8,9]").root(), "[7,8,9]");
    assertStale(() -> three.get(1), "get(1) of [1,2,3] after reading [7,8,9]");
    assertStale(() -> three.get(2), "get(2) of [1,2,3] after reading [7,8,9]");
    assertStale(three::length, "length() of [1,2,3] after reading [7,8,9]");
    // Witness: the new document reads.
    assertThat(kind(JqNumber.class, other.get(1), "[7,8,9][1]").longValue()).as("[7,8,9][1]").isEqualTo(8L);
  }

  @Test
  void closeDropsOnlyItsOwnDocument() {
    Workspace workspace = newWorkspace();
    JsonDoc first = read(workspace, "[1]");
    JsonDoc second = read(workspace, "[2,3]");
    first.close();
    // Closing the superseded document leaves the current one readable.
    JqArray twoThree = kind(JqArray.class, second.root(), "[2,3] after closing [1]");
    assertThat(twoThree.length()).as("length of [2,3] after closing [1]").isEqualTo(2);

    second.close();
    second.close();
    // Witness: the first close did drop [2,3]; the second one is a no-op.
    assertStale(second::root, "root() of [2,3] after close");
    assertStale(twoThree::length, "length() of [2,3] after close");

    JsonDoc third = read(workspace, "[4,5,6]");
    JqArray fourFiveSix = kind(JqArray.class, third.root(), "[4,5,6]");
    first.close();
    second.close();
    // Closing documents that are no longer current breaks neither the current root nor its views.
    assertThat(kind(JqArray.class, third.root(), "[4,5,6] after closing older documents").length())
        .as("length of [4,5,6] via root() after closing older documents")
        .isEqualTo(3);
    assertThat(fourFiveSix.length()).as("length of a [4,5,6] view taken before the closes").isEqualTo(3);
    assertThat(kind(JqNumber.class, fourFiveSix.get(2), "[4,5,6][2]").longValue()).as("[4,5,6][2]").isEqualTo(6L);
  }

  /** jq-ref 1.8.2, observed 2026-11-11: jq-ref -c '., .n, .s, .a, .o' -> the document, 1.5, "x", [true], {}. */
  private static final String FOUR_KINDS = "{\"n\":1.5,\"s\":\"x\",\"a\":[true],\"o\":{}}";

  @Test
  void toJsonOfAStaleViewIsStale() {
    // The #32 carry for #33 (ADR-0026): toJson() checks its document first, after close() and after a later read.
    Workspace workspace = newWorkspace();
    for (String supersededBy : new String[] {null, "[0]"}) {
      String how = supersededBy == null ? "after close" : "after reading " + supersededBy;
      JsonDoc doc = read(workspace, FOUR_KINDS);
      JqObject root = kind(JqObject.class, doc.root(), ".");
      JqNumber n = kind(JqNumber.class, root.get("n"), ".n");
      JqString s = kind(JqString.class, root.get("s"), ".s");
      JqArray a = kind(JqArray.class, root.get("a"), ".a");
      JqObject o = kind(JqObject.class, root.get("o"), ".o");
      // Witness: before the document goes stale each toJson() is jq's compact text.
      assertThat(root.toJson()).as("JqObject.toJson() of . before it is stale").isEqualTo(FOUR_KINDS);
      assertThat(n.toJson()).as("JqNumber.toJson() before it is stale").isEqualTo("1.5");
      assertThat(s.toJson()).as("JqString.toJson() before it is stale").isEqualTo("\"x\"");
      assertThat(a.toJson()).as("JqArray.toJson() before it is stale").isEqualTo("[true]");
      assertThat(o.toJson()).as("JqObject.toJson() of .o before it is stale").isEqualTo("{}");

      if (supersededBy == null) {
        doc.close();
      } else {
        read(workspace, supersededBy);
      }

      assertStale(root::toJson, "JqObject.toJson() " + how);
      assertStale(n::toJson, "JqNumber.toJson() " + how);
      assertStale(s::toJson, "JqString.toJson() " + how);
      assertStale(a::toJson, "JqArray.toJson() " + how);
      assertStale(o::toJson, "JqObject.toJson() of a child " + how);
    }
  }

  @Test
  void tag6AccessorsOfAStaleViewAreStale() {
    // #35 design 1.9: each decode reads bytes only after doc.live(), so a tag-6 string or key of a closed or
    // superseded document is stale, never a decode of another document's bytes. jq-ref 1.8.2, observed 2026-11-12:
    // printf -- '{"\\u0073":"a\\nb","o":{"\\u006b":1}}' | jq-ref -c '., (.s | explode), .o.k' (\\u is one backslash
    // and u) -> {"s":"a\nb","o":{"k":1}}  [97,10,98]  1
    String tag6 = "{\"\\u0073\":\"a\\nb\",\"o\":{\"\\u006b\":1}}";
    Workspace workspace = newWorkspace();
    for (String supersededBy : new String[] {null, "{\"s\":\"xyz\",\"o\":{\"k\":2}}"}) {
      String how = supersededBy == null ? "after close" : "after reading another document";
      JsonDoc doc = read(workspace, tag6);
      JqObject root = kind(JqObject.class, doc.root(), ".");
      JqString s = kind(JqString.class, root.get("s"), ".s");
      JqObject o = kind(JqObject.class, root.get("o"), ".o");
      // Witness: before the document goes stale each accessor decodes.
      assertThat(s.value()).as(".s before it is stale").isEqualTo("a\nb");
      assertThat(s.utf8().byteSize()).as(".s utf8 length before it is stale").isEqualTo(3L);
      assertThat(s.toJson()).as(".s toJson before it is stale").isEqualTo("\"a\\nb\"");
      assertThat(o.size()).as("size of .o before it is stale").isEqualTo(1);
      assertThat(kind(JqNumber.class, o.get("k"), ".o.k").longValue()).as(".o.k before it is stale").isEqualTo(1L);
      List<String> keys = new ArrayList<>();
      root.forEach((k, v) -> keys.add(k));
      assertThat(keys).as("forEach over . before it is stale").containsExactly("s", "o");

      if (supersededBy == null) {
        doc.close();
      } else {
        read(workspace, supersededBy);
      }

      assertStale(s::value, "JqString.value() of a tag-6 string " + how);
      assertStale(s::utf8, "JqString.utf8() of a tag-6 string " + how);
      assertStale(s::toJson, "JqString.toJson() of a tag-6 string " + how);
      assertStale(o::size, "JqObject.size() over a tag-6 key " + how);
      assertStale(() -> o.get("k"), "JqObject.get(String) over a tag-6 key " + how);
      assertStale(() -> root.forEach((k, v) -> keys.add(k)), "JqObject.forEach over tag-6 keys " + how);
      assertThat(keys).as("forEach on a stale object calls nothing, %s", how).containsExactly("s", "o");
    }
  }

  @Test
  void doubleValueOfAStaleViewIsStale() {
    // #36 design table U (ADR-0026): doubleValue() checks its document before it reads a byte, after close() and
    // after a later read. FOUR_KINDS's .n prints 1.5 on jq-ref (above), a double that is exact.
    Workspace workspace = newWorkspace();
    for (String supersededBy : new String[] {null, "[0]"}) {
      String how = supersededBy == null ? "after close" : "after reading " + supersededBy;
      JsonDoc doc = read(workspace, FOUR_KINDS);
      JqNumber n = kind(JqNumber.class, kind(JqObject.class, doc.root(), ".").get("n"), ".n");
      // Witness: before the document goes stale the double is 1.5.
      assertThat(n.doubleValue()).as("JqNumber.doubleValue() before it is stale").isEqualTo(1.5);
      if (supersededBy == null) {
        doc.close();
      } else {
        read(workspace, supersededBy);
      }
      assertStale(n::doubleValue, "JqNumber.doubleValue() " + how);
    }
  }

  @Test
  void decimalValueOfAStaleViewIsStale() {
    // As doubleValueOfAStaleViewIsStale, for decimalValue(): before the document goes stale it is 1.5, scale 1.
    Workspace workspace = newWorkspace();
    for (String supersededBy : new String[] {null, "[0]"}) {
      String how = supersededBy == null ? "after close" : "after reading " + supersededBy;
      JsonDoc doc = read(workspace, FOUR_KINDS);
      JqNumber n = kind(JqNumber.class, kind(JqObject.class, doc.root(), ".").get("n"), ".n");
      assertThat(n.decimalValue()).as("JqNumber.decimalValue() before it is stale").isEqualTo(new BigDecimal("1.5"));
      if (supersededBy == null) {
        doc.close();
      } else {
        read(workspace, supersededBy);
      }
      assertStale(n::decimalValue, "JqNumber.decimalValue() " + how);
    }
  }

  @Test
  void writerOfAStaleViewWritesNothing() {
    // ADR-0026 and ADR-0027: a stale view gives STALE_VIEW, never a wrong value, and the writer checks before it
    // writes. jq-ref 1.8.2, observed 2026-11-11: printf -- '[1,{"a":"x"}]' | jq-ref -c . -> [1,{"a":"x"}] (rc 0).
    Workspace workspace = newWorkspace();
    RecordingTarget target = new RecordingTarget();
    JqSink sink = JsonWriter.sink(workspace, target, OutputOptions.compact());

    JsonDoc closed = read(workspace, "[1,{\"a\":\"x\"}]");
    JqValue closedRoot = closed.root();
    JqValue closedMember = kind(JqArray.class, closedRoot, ".").get(1);
    // Witness: before close the sink writes the document.
    assertThat(sink.emit(closedRoot)).as("emit's answer before close").isTrue();
    assertThat(target.text()).as("bytes written before close").isEqualTo("[1,{\"a\":\"x\"}]\n");
    closed.close();
    assertStale(() -> sink.emit(closedRoot), "JsonWriter's emit of . after close");
    assertStale(() -> sink.emit(closedMember), "JsonWriter's emit of .[1] after close");
    assertThat(target.text()).as("bytes written by the emits after close").isEqualTo("[1,{\"a\":\"x\"}]\n");

    JqValue superseded = read(workspace, "[2]").root();
    JsonDoc current = read(workspace, "[3]");
    assertStale(() -> sink.emit(superseded), "JsonWriter's emit of [2] after reading [3]");
    assertThat(target.text()).as("bytes written by the emit after a later read").isEqualTo("[1,{\"a\":\"x\"}]\n");
    // Witness: the current document still writes.
    assertThat(sink.emit(current.root())).as("emit's answer for the current document").isTrue();
    assertThat(target.text()).as("bytes written for the current document").isEqualTo("[1,{\"a\":\"x\"}]\n[3]\n");
  }

  @Test
  void readDuringADrainMakesTheRestStale() {
    // ADR-0027: "After each drain the writer re-checks its document: the target is host code and may read into the
    // same Workspace." jq-ref 1.8.2, observed 2026-11-11: a compact array of 10 000 strings "abcdefgh" (110 001
    // bytes) prints unchanged under -c . (110 002 bytes with the LF), more than one 64 KiB print buffer.
    String big = "[" + "\"abcdefgh\",".repeat(9_999) + "\"abcdefgh\"]";
    Workspace workspace = newWorkspace();

    // Witness: with no read in between, every byte arrives, in more than one write.
    RecordingTarget whole = new RecordingTarget();
    assertThat(JsonWriter.sink(workspace, whole, OutputOptions.compact()).emit(read(workspace, big).root()))
        .as("emit's answer for the 110 001-byte document")
        .isTrue();
    assertThat(whole.text()).as("output of the 110 001-byte document").isEqualTo(big + "\n");
    assertThat(whole.writes).as("write calls for a 110 002-byte result").isGreaterThan(1);

    // The target reads another document into the same Workspace during its first write.
    List<JsonDoc> readInside = new ArrayList<>();
    RecordingTarget reading =
        new RecordingTarget(
            n -> {
              if (n == 1) readInside.add(read(workspace, "[7]"));
            });
    JqSink sink = JsonWriter.sink(workspace, reading, OutputOptions.compact());
    JqValue root = read(workspace, big).root();
    assertStale(() -> sink.emit(root), "emit of the big document after its target read [7]");
    assertThat(reading.writes).as("write calls after the read inside the first one").isEqualTo(1);
    assertThat(reading.text()).as("bytes written before the document went stale").isNotEmpty();
    assertThat(big + "\n").as("the bytes written are a prefix of the result").startsWith(reading.text());
    // Witness: the document read inside the write is the current one and reads.
    assertThat(readInside).as("documents read inside the write").hasSize(1);
    JqArray seven = kind(JqArray.class, readInside.get(0).root(), "[7]");
    assertThat(kind(JqNumber.class, seven.get(0), "[7][0]").longValue()).as("[7][0]").isEqualTo(7L);
  }
}
