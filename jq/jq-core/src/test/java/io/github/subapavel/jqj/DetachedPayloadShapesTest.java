package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.internal.value.BuiltObject;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The shapes a detached error payload keeps (ADR-0016, ADR-0039), beside ErrorPayloadTest: containers longer than the
 * detach's first capacity, an object with a duplicate key, empty containers, a built container holding views, and a
 * document's {@code -0}. Each payload is read after the Workspace's next read, which makes every earlier view stale.
 * The expected texts are jq-ref 1.8.2's, observed 2026-11-13: {@code .a | error} on each input prints the payload after
 * {@code (not a string): }.
 */
class DetachedPayloadShapesTest {

  /** The payload {@code filter} raises on {@code json}, read after the next read over the same tape. */
  private static JqValue payloadAfterTheNextRead(String filter, String json) {
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.getBytes(UTF_8)).root();
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class)
            .as("%s on %s", filter, json)
            .isThrownBy(() -> engine.compile(filter).run(root, NONE, new Results(), workspace))
            .actual();
    read(workspace, "[2,3,4,5,6,7,8,9]".getBytes(UTF_8));
    return e.value();
  }

  @Test
  void aViewArrayLongerThanTheFirstCapacityKeepsEveryElement() {
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":[1,2,3,4,5,6,7,8,9,10]}");
    assertThat(((JqArray) payload).length()).as("length of the payload").isEqualTo(10);
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("[1,2,3,4,5,6,7,8,9,10]");
  }

  @Test
  void aViewObjectOfMoreKeysThanTheFirstCapacityKeepsTheLastValueOfADuplicateKey() {
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":{\"k1\":1,\"k2\":2,\"k3\":3,\"k4\":4,\"k5\":5,\"k1\":6}}");
    assertThat(((JqObject) payload).size()).as("size of the payload").isEqualTo(5);
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"k1\":6,\"k2\":2,\"k3\":3,\"k4\":4,\"k5\":5}");
  }

  @ParameterizedTest
  @ValueSource(strings = {"aA", "Aa"})
  void keysOfOneHashDetachInBoundedTime(String block) {
    // Second review of #68: 2^15 keys of 15 blocks, each the given block or "BB". With "aA" they share one 31-polynomial
    // hash of their bytes read last to first (ByteBuffer's), with "Aa" one read first to last (Arrays.hashCode's). A
    // map that cannot order two colliding keys scans the bin for each key, 2^30 / 2 compares in all (a time witness).
    int count = 1 << 15;
    StringBuilder json = new StringBuilder("{");
    for (int i = 0; i < count; i++) {
      json.append('"');
      for (int bit = 0; bit < 15; bit++) {
        json.append((i >> bit & 1) == 0 ? block : "BB");
      }
      json.append("\":").append(i).append(i + 1 < count ? "," : "}");
    }
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.toString().getBytes(UTF_8)).root();
    JqFilter filter = engine.compile("error");
    JqFilterException e =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> assertThatExceptionOfType(JqFilterException.class).isThrownBy(() -> filter.run(root, NONE, new Results(), workspace)).actual(),
            "error on a read object of 2^15 keys of one hash");
    JqObject payload = (JqObject) e.value();
    assertThat(payload.size()).as("size of the payload").isEqualTo(count);
    assertThat(((JqNumber) payload.get("BB".repeat(15))).longValue()).as("payload of the last key").isEqualTo(count - 1L);
  }

  @Test
  void aSharedBuiltContainerIsCopiedOnce() {
    // Third review of #68: [.,.] 32 times on null builds 32 arrays of two references to the one below, a value of 2^32
    // paths. A detach that copies a container once per reference walks all of them after the run (a time witness).
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqFilter filter = engine.compile("[.,.]|".repeat(32) + "error");
    JqFilterException e =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> assertThatExceptionOfType(JqFilterException.class).isThrownBy(() -> filter.run(JqNull.NULL, NONE, new Results(), workspace)).actual(),
            "[.,.]| 32 times, then error, on null");
    JqValue level = e.value();
    for (int depth = 0; depth < 32; depth++) {
      JqArray pair = (JqArray) level;
      assertThat(pair.length()).as("length at depth %d", depth).isEqualTo(2);
      assertThat(pair.get(1)).as("the second element at depth %d is the first's copy", depth).isSameAs(pair.get(0));
      level = pair.get(0);
    }
    assertThat(level).as("the leaf").isEqualTo(JqNull.NULL);
  }

  @Test
  void viewsOfOneSubtreeAreCopiedOnce() {
    // Ruling on #68: each $r.a and $r.l makes a new view of the same subtree, 10^4 times each. A detach that copies a
    // view once per instance copies 2 * 10^8 nodes after the run (a time witness). jq-ref: on
    // {"xs":[0,1],"a":{"b":[0]},"l":[1]} the filter prints [{"b":[0]},[1],{"b":[0]},[1]].
    int n = 10_000;
    String elements = String.join(",", java.util.stream.IntStream.range(0, n).mapToObj(Integer::toString).toList());
    String json = "{\"xs\":[" + elements + "],\"a\":{\"b\":[" + elements + "]},\"l\":[" + elements + "]}";
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.getBytes(UTF_8)).root();
    JqFilter filter = engine.compile(". as $r | [.xs[] | $r.a, $r.l] | error");
    JqFilterException e =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> assertThatExceptionOfType(JqFilterException.class).isThrownBy(() -> filter.run(root, NONE, new Results(), workspace)).actual(),
            ". as $r | [.xs[] | $r.a, $r.l] | error on xs, a.b and l of 10^4 elements");
    JqArray payload = (JqArray) e.value();
    assertThat(payload.length()).as("length of the payload").isEqualTo(2 * n);
    assertThat(((JqArray) ((JqObject) payload.get(0)).get("b")).length()).as("length of payload[0].b").isEqualTo(n);
    assertThat(((JqArray) payload.get(1)).length()).as("length of payload[1]").isEqualTo(n);
    for (int i = 2; i < 2 * n; i++) {
      assertThat(payload.get(i)).as("payload[%d] is payload[%d]'s copy", i, i % 2).isSameAs(payload.get(i % 2));
    }
  }

  /** {@code filter}'s payload on {"xs": 10^4 numbers, "s": and "k": strings of 1 MiB, "n": 12345}, under a 5 s bound. */
  private static JqValue payloadOfSharedLeaves(String filter) {
    String xs = String.join(",", java.util.stream.IntStream.range(0, 10_000).mapToObj(Integer::toString).toList());
    String mebi = "a".repeat(1 << 20);
    String json = "{\"xs\":[" + xs + "],\"s\":\"" + mebi + "\",\"k\":\"" + mebi + "\",\"n\":12345}";
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.getBytes(UTF_8)).root();
    JqFilter compiled = engine.compile(filter);
    return assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> assertThatExceptionOfType(JqFilterException.class).isThrownBy(() -> compiled.run(root, NONE, new Results(), workspace)).actual(),
            filter + " on xs of 10^4 elements and strings of 1 MiB")
        .value();
  }

  @Test
  void aDocumentStringAtManyPlacesIsDecodedOnce() {
    // Fourth review of #68: each $r.s is a new view of one 1 MiB string. A detach that decodes it per place builds
    // 10^4 MiB after the run (a time and memory witness); its copy is one built string, shared.
    JqArray payload = (JqArray) payloadOfSharedLeaves(". as $r | [.xs[] | $r.s] | error");
    assertThat(payload.length()).as("length of the payload").isEqualTo(10_000);
    assertThat(((JqString) payload.get(0)).value()).as("payload[0]").hasSize(1 << 20);
    for (int i = 1; i < 10_000; i++) {
      assertThat(payload.get(i)).as("payload[%d] is payload[0]'s copy", i).isSameAs(payload.get(0));
    }
  }

  @Test
  void aDocumentStringKeyOfManyBuiltObjectsIsDecodedOnce() {
    // Fourth review of #68: each {($r.k): 1} is a built object whose key is a view of one 1 MiB string
    // (BuiltObject keeps a view key); the key's copy is one built string, shared by every object's copy.
    JqArray payload = (JqArray) payloadOfSharedLeaves(". as $r | [.xs[] | {($r.k): 1}] | error");
    assertThat(payload.length()).as("length of the payload").isEqualTo(10_000);
    JqString key = ((BuiltObject) payload.get(0)).key(0);
    assertThat(key.value()).as("payload[0]'s key").hasSize(1 << 20);
    for (int i = 1; i < 10_000; i++) {
      assertThat(((BuiltObject) payload.get(i)).key(0)).as("payload[%d]'s key is payload[0]'s", i).isSameAs(key);
    }
  }

  @Test
  void aDocumentNumberAtManyPlacesIsCopiedOnce() {
    JqArray payload = (JqArray) payloadOfSharedLeaves(". as $r | [.xs[] | $r.n] | error");
    assertThat(payload.get(0).toJson()).as("payload[0]").isEqualTo("12345");
    for (int i = 1; i < 10_000; i++) {
      assertThat(payload.get(i)).as("payload[%d] is payload[0]'s copy", i).isSameAs(payload.get(0));
    }
  }

  @Test
  void viewsOfTwoDocumentsAtOnePlaceAreCopiedApart() {
    // The input [1] and the host variable $x, the root of [2] read into another Workspace, are both a document's root at
    // tape index 0; their copies are their own: [$x, .] | error raises [[2],[1]].
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue other = read(engine.newWorkspace(), "[2]".getBytes(UTF_8)).root();
    JqValue root = read(workspace, "[1]".getBytes(UTF_8)).root();
    JqFilter filter = engine.compile("[$x, .] | error", java.util.Set.of("x"));
    Bindings bindings = Bindings.builder().put("x", other).build();
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class)
            .as("[$x, .] | error")
            .isThrownBy(() -> filter.run(root, bindings, new Results(), workspace))
            .actual();
    assertThat(e.value().toJson()).as("the payload's text").isEqualTo("[[2],[1]]");
  }

  @Test
  void aKeyRepeatedThreeTimesTakesItsLastValue() {
    // jq-ref: .a | error on {"a":{"a":1,"b":2,"a":3,"a":4}} prints {"a":4,"b":2}.
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":{\"a\":1,\"b\":2,\"a\":3,\"a\":4}}");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"a\":4,\"b\":2}");
  }

  @Test
  void anEscapedKeyIsTheKeyItDecodesTo() {
    // jq-ref: .a | error on {"a":{"a":1,"b":2,"\u0061":3}} prints {"a":3,"b":2}: keys are one by their decoded bytes.
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":{\"a\":1,\"b\":2,\"\\u0061\":3}}");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"a\":3,\"b\":2}");
  }

  @Test
  void emptyViewContainersStayEmpty() {
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":{\"x\":[],\"y\":{}}}");
    assertThat(((JqArray) ((JqObject) payload).get("x")).length()).as("length of payload.x").isZero();
    assertThat(((JqObject) ((JqObject) payload).get("y")).size()).as("size of payload.y").isZero();
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"x\":[],\"y\":{}}");
  }

  @Test
  void aBuiltPayloadHoldingViewsIsDetachedToo() {
    // jq-ref: {a: .a, b: [.a]} | error on {"a":"s\nt"} prints {"a":"s\nt","b":["s\nt"]}.
    JqValue payload = payloadAfterTheNextRead("{a: .a, b: [.a]} | error", "{\"a\":\"s\\nt\"}");
    JqObject object = (JqObject) payload;
    assertThat(((JqString) object.get("a")).value()).as("payload.a").isEqualTo("s\nt");
    assertThat(((JqString) ((JqArray) object.get("b")).get(0)).value()).as("payload.b[0]").isEqualTo("s\nt");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"a\":\"s\\nt\",\"b\":[\"s\\nt\"]}");
  }

  @Test
  void aReadObjectOf10To5KeysDetachesInLinearTime() {
    // Review ruling 3 on #68: the detach runs after the run, where no budget, deadline or interrupt reaches it, so it
    // must be linear. A walk that checks each key against the earlier ones compares 10^10 / 2 pairs, far past the
    // limit (a time witness). The last key repeats the first: it keeps the first position and takes the last value.
    StringBuilder json = new StringBuilder("{");
    for (int i = 0; i < 100_000; i++) {
      json.append("\"k").append(i).append("\":").append(i).append(',');
    }
    json.append("\"k0\":-1}");
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.toString().getBytes(UTF_8)).root();
    JqFilter filter = engine.compile("error");
    JqFilterException e =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> assertThatExceptionOfType(JqFilterException.class).isThrownBy(() -> filter.run(root, NONE, new Results(), workspace)).actual(),
            "error on a read object of 10^5 keys");
    JqObject payload = (JqObject) e.value();
    assertThat(payload.size()).as("size of the payload").isEqualTo(100_000);
    assertThat(((JqNumber) payload.get("k0")).longValue()).as("payload.k0, the repeated key's last value").isEqualTo(-1L);
    assertThat(((JqNumber) payload.get("k99999")).longValue()).as("payload.k99999").isEqualTo(99_999L);
    assertThat(payload.toJson()).as("the payload's text").startsWith("{\"k0\":-1,\"k1\":1,").endsWith(",\"k99999\":99999}");
  }

  @Test
  void aBuiltObjectWithADocumentsKeyDetachesTheKey() {
    // jq-ref: ({x: 1} + .a) | error on {"a":{"k":"v"}} prints {"x":1,"k":"v"}. The merge keeps the document's key, so
    // the built object's walk copies a view key.
    JqObject payload = (JqObject) payloadAfterTheNextRead("({x: 1} + .a) | error", "{\"a\":{\"k\":\"v\"}}");
    assertThat(((JqString) payload.get("k")).value()).as("payload.k").isEqualTo("v");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"x\":1,\"k\":\"v\"}");
  }

  @Test
  void aDocumentMinusZeroKeepsItsSignAndFitsNoLong() {
    // Witness: the document's -0 fits no long as a view (ADR-0012 FITS_LONG); the detached copy keeps that flag.
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, "{\"a\":-0}".getBytes(UTF_8)).root();
    Results kept = new Results();
    assertThat(engine.compile(".a").run(root, NONE, kept, workspace)).as("run's answer for .a").isTrue();
    assertThat(((JqNumber) kept.values.getFirst()).fitsLong()).as("fitsLong() of the view -0").isFalse();

    JqNumber payload = (JqNumber) payloadAfterTheNextRead(".a | error", "{\"a\":-0}");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("-0");
    assertThat(payload.fitsLong()).as("fitsLong() of the detached -0").isFalse();
  }
}
