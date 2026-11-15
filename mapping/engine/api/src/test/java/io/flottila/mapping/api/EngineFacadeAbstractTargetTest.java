package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.AtAuthoringId;
import io.flottila.mapping.mapping.Diagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An abstract element in a target (#95, ADR-0008 "Refused when a mapping uses the field"): a target
 * field mapping that writes an element of an abstract type, or a field below one, is {@code
 * abstract-target-element} at that mapping, and the message names the abstract declaration in its
 * declaring file. A source reads such an element, and a target writes the member of a substitution
 * group whose head is abstract.
 */
class EngineFacadeAbstractTargetTest {

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

  /**
   * The root {@code Doc} holds {@code Id}, {@code Shape} of the abstract type {@code Shape}, whose
   * start tag ends at 3:48, and a reference to the abstract head {@code Item} with its one member
   * {@code Pen}. Local elements have no namespace; every field is optional.
   */
  private static final String SHAPES_XSD =
      """
      <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:s" targetNamespace="urn:s">
        <xs:element name="Doc" type="Doc"/>
        <xs:complexType name="Shape" abstract="true">
          <xs:sequence>
            <xs:element name="Colour" type="xs:string" minOccurs="0"/>
          </xs:sequence>
        </xs:complexType>
        <xs:complexType name="Doc">
          <xs:sequence>
            <xs:element name="Id" type="xs:string" minOccurs="0"/>
            <xs:element name="Shape" type="Shape" minOccurs="0"/>
            <xs:element ref="Item" minOccurs="0"/>
          </xs:sequence>
        </xs:complexType>
        <xs:element name="Item" type="xs:string" abstract="true"/>
        <xs:element name="Pen" type="xs:string" substitutionGroup="Item"/>
      </xs:schema>
      """;

  private static final String PLACEHOLDER = "sha256:" + "0".repeat(64);

  /**
   * The model from {@code shapes.xsd}'s {@code Doc} to itself with {@code targetFieldMappings},
   * written to {@code base} and refreshed from it.
   */
  private static byte[] model(Path base, String targetFieldMappings) throws Exception {
    Files.writeString(base.resolve("shapes.xsd"), SHAPES_XSD);
    String side =
        """
        {"bundle": ["shapes.xsd"], "fingerprint": "%s", "root": {"element": "Q{urn:s}Doc"}}"""
            .formatted(PLACEHOLDER);
    return Fingerprints.refreshed(
        """
        {"formatVersion": 1, "source": %1$s, "target": %1$s, "targetFieldMappings": {%2$s}}
        """
            .formatted(side, targetFieldMappings)
            .getBytes(UTF_8),
        base);
  }

  /**
   * The target field mapping {@code t1} of {@code /Q{urn:s}Doc/<targetField>}, whose result is the
   * source-field box {@code s1} on {@code /Q{urn:s}Doc/<sourceField>}.
   */
  private static String link(String targetField, String sourceField) {
    return """
        "/Q{urn:s}Doc/%s": {"id": "t1", "result": "s1",
          "boxes": {"s1": {"kind": "source", "field": "/Q{urn:s}Doc/%s"}}}"""
        .formatted(targetField, sourceField);
  }

  /** The output of {@code model}, compiled as deployed, on {@code input}. */
  private static String run(byte[] model, Path base, String input) throws Exception {
    EngineFacade facade = new EngineFacade();
    CompileResult result =
        facade.compile(model, DirectoryResolver.over(base), CompileOptions.DEPLOY);
    if (!(result instanceof CompileResult.Compiled(CompiledMapping mapping))) {
      throw new AssertionError("expected Compiled, got " + result);
    }
    return new String(facade.run(mapping, input.getBytes(UTF_8)), UTF_8);
  }

  @Test
  void a_target_field_mapping_through_an_element_of_an_abstract_type_is_refused_there(
      @TempDir Path base) throws Exception {
    byte[] model = model(base, link("Shape/Colour", "Id"));

    for (CompileOptions options : List.of(CompileOptions.DEPLOY, CompileOptions.TEST_RUN)) {
      CompileResult result =
          new EngineFacade().compile(model, DirectoryResolver.over(base), options);
      if (!(result instanceof CompileResult.Refused(List<Diagnostic> diagnostics))) {
        throw new AssertionError(options + ": expected Refused, got " + result);
      }

      assertThat(diagnostics)
          .as("%s", options)
          .extracting(d -> d.code() + " " + d.location())
          .containsExactly("abstract-target-element " + new AtAuthoringId(new AuthoringId("t1")));
      assertThat(diagnostics.getFirst().message())
          .as("%s", options)
          .contains("/Q{urn:s}Doc/Shape ")
          .contains("shapes.xsd 3:48");
    }
  }

  @Test
  void a_source_box_reads_an_element_of_an_abstract_type(@TempDir Path base) throws Exception {
    byte[] model = model(base, link("Id", "Shape/Colour"));

    assertThat(
            run(model, base, "<s:Doc xmlns:s=\"urn:s\"><Shape><Colour>c</Colour></Shape></s:Doc>"))
        .isEqualTo(DECLARATION + "<ns0:Doc xmlns:ns0=\"urn:s\"><Id>c</Id></ns0:Doc>");
  }

  @Test
  void a_target_writes_the_member_that_replaces_an_abstract_head(@TempDir Path base)
      throws Exception {
    byte[] model = model(base, link("Q{urn:s}Pen", "Id"));

    assertThat(run(model, base, "<s:Doc xmlns:s=\"urn:s\"><Id>i</Id></s:Doc>"))
        .isEqualTo(DECLARATION + "<ns0:Doc xmlns:ns0=\"urn:s\"><ns0:Pen>i</ns0:Pen></ns0:Doc>");
  }
}
