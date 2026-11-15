package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The verdicts themselves (ADR-0018 as amended by F-11): the golden corpus's and the differential
 * oracle's. This class is no corpus or differential class, so the ratchet does not read its tests
 * as case ids (scripts/ratchet.java).
 */
class OracleVerdictTest {

  private static boolean differs(String expected, String actual) {
    return OracleVerdict.similar(expected.getBytes(UTF_8), actual.getBytes(UTF_8)).hasDifferences();
  }

  @Test
  void similarStillSeesWhitespaceInsideAValue() {
    assertThat(differs("<a><b>1</b></a>", "<a><b> 1 </b></a>")).isTrue();
    assertThat(differs("<a><b>1 2</b></a>", "<a><b>1  2</b></a>")).isTrue();
    assertThat(differs("<a><b>1</b></a>", "<a><b>1&#13;</b></a>")).isTrue();
  }

  @Test
  void similarIgnoresIndentationBetweenElements() {
    assertThat(differs("<a>\n  <b>1</b>\n</a>\n", "<a><b>1</b></a>")).isFalse();
  }

  @Test
  void similarIgnoresThePrefixButNotTheNamespace() {
    assertThat(
            differs(
                "<ns0:a xmlns:ns0=\"urn:x\"><b>1</b></ns0:a>",
                "<p:a xmlns:p=\"urn:x\"><b>1</b></p:a>"))
        .isFalse();
    assertThat(
            differs(
                "<ns0:a xmlns:ns0=\"urn:x\"><b>1</b></ns0:a>",
                "<ns0:a xmlns:ns0=\"urn:y\"><b>1</b></ns0:a>"))
        .isTrue();
  }

  @Test
  void similarSeesAMissingOrExtraField() {
    assertThat(differs("<a><b>1</b></a>", "<a><b>1</b><c>3</c></a>")).isTrue();
    assertThat(differs("<a><b>1</b></a>", "<a/>")).isTrue();
  }

  // --- the differential verdict (ADR-0018 as amended by F-11)

  private static boolean notIdentical(String expected, String actual) {
    return OracleVerdict.identical(expected.getBytes(UTF_8), actual.getBytes(UTF_8))
        .hasDifferences();
  }

  private static List<Set<String>> declarations(String document) {
    return OracleVerdict.declarations(document.getBytes(UTF_8));
  }

  /** The F-11 witness: XMLUnit 2.14.0 drops {@code xmlns} attributes before it compares. */
  @Test
  void identicalSeesAPrefixButNotWhereANamespaceIsDeclared() {
    assertThat(
            notIdentical(
                "<p:a xmlns:p=\"urn:x\"><p:b/></p:a>", "<q:a xmlns:q=\"urn:x\"><q:b/></q:a>"))
        .as("another prefix for the same namespace")
        .isTrue();
    assertThat(notIdentical("<a xmlns:p=\"urn:x\"><p:b/></a>", "<a><p:b xmlns:p=\"urn:x\"/></a>"))
        .as("the same binding declared on the parent or on the child")
        .isFalse();
    assertThat(notIdentical("<a xmlns:p=\"urn:x\"><b/></a>", "<a><b/></a>"))
        .as("a declaration no name uses")
        .isFalse();
  }

  @Test
  void declarationsSeeWhereANamespaceIsDeclared() {
    // the pairs identicalSeesAPrefixButNotWhereANamespaceIsDeclared cannot tell apart
    assertThat(declarations("<a xmlns:p=\"urn:x\"><p:b/></a>"))
        .containsExactly(Set.of("p=urn:x"), Set.of());
    assertThat(declarations("<a><p:b xmlns:p=\"urn:x\"/></a>"))
        .containsExactly(Set.of(), Set.of("p=urn:x"));
    assertThat(declarations("<a xmlns:p=\"urn:x\"><b/></a>"))
        .containsExactly(Set.of("p=urn:x"), Set.of());
    assertThat(declarations("<a><b/></a>")).containsExactly(Set.of(), Set.of());
    // elements in document order; on one element the order of its declarations does not count
    assertThat(declarations("<a><b><c xmlns:p=\"urn:x\" xmlns:q=\"urn:y\"/></b><d/></a>"))
        .containsExactly(Set.of(), Set.of(), Set.of("p=urn:x", "q=urn:y"), Set.of());
    assertThat(declarations("<a><b><c xmlns:q=\"urn:y\" xmlns:p=\"urn:x\"/></b><d/></a>"))
        .containsExactly(Set.of(), Set.of(), Set.of("p=urn:x", "q=urn:y"), Set.of());
    // a default namespace is a declaration too, with no prefix
    assertThat(declarations("<a xmlns=\"urn:x\"/>")).containsExactly(Set.of("=urn:x"));
  }

  @Test
  void identicalSeesAMissingXmlDeclaration() {
    String declaration = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    assertThat(notIdentical(declaration + "<a><b>1</b></a>", "<a><b>1</b></a>")).isTrue();
    // witness: the same declaration on both sides is no difference
    assertThat(notIdentical(declaration + "<a><b>1</b></a>", declaration + "<a><b>1</b></a>"))
        .isFalse();
  }

  /** Our serializer writes CR as {@code &#13;} (ADR-0022), Saxon as {@code &#xD;}. */
  @Test
  void identicalIgnoresHowACharacterReferenceIsSpelled() {
    assertThat(notIdentical("<a><b>x&#13;&amp;&gt;</b></a>", "<a><b>x&#xD;&#38;></b></a>"))
        .isFalse();
    // witness: another character is a difference
    assertThat(notIdentical("<a><b>x&#13;</b></a>", "<a><b>x&#10;</b></a>")).isTrue();
  }
}
