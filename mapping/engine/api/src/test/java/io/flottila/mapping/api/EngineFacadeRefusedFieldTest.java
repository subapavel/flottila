package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.AtAuthoringId;
import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Location;
import io.flottila.mapping.mapping.Severity;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A mapping that uses a refused field (ADR-0008 "Refused when a mapping uses the field" and its
 * Shape (#92), ADR-0007 Consequences): two sibling declarations with one step have no unique field
 * path, so a source-field box, a target field mapping or a target field the walk must create that
 * goes through one is {@code ambiguous-field-path}, at the box, at the target field mapping, or at
 * the field's {@code /targetFieldMappings/<field path>} pointer. The message names the schema file
 * and the line and column of the step's second declaration, the refusal nearest the root. A model
 * that leaves the siblings unused compiles.
 */
class EngineFacadeRefusedFieldTest {

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

  private static final String CODE = "ambiguous-field-path";

  /** A well-formed fingerprint, refreshed before compile ({@link Fingerprints}). */
  private static final String PLACEHOLDER = "sha256:" + "0".repeat(64);

  /**
   * Same-step siblings, every field of {@code Doc} optional: the leaves {@code Note} (lines 6 and
   * 8) around their neighbour {@code Code}; under {@code Head} the leaves {@code Ref} 0..1, 1..1,
   * 1..1 (lines 12 to 14); under {@code Body} the structure fields {@code Part} 1..1 and 1..2
   * (lines 21 and 28). The second declaration of each step ends its start tag at {@code Note} 8:65,
   * {@code Ref} 13:56 and {@code Part} 28:53.
   */
  private static final String SAME_STEP_XSD =
      """
      <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
        <xs:element name="Doc">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="Id" type="xs:string" minOccurs="0"/>
              <xs:element name="Note" type="xs:string" minOccurs="0"/>
              <xs:element name="Code" type="xs:string" minOccurs="0"/>
              <xs:element name="Note" type="xs:string" minOccurs="0"/>
              <xs:element name="Head" minOccurs="0">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="Ref" type="xs:string" minOccurs="0"/>
                    <xs:element name="Ref" type="xs:string"/>
                    <xs:element name="Ref" type="xs:string"/>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
              <xs:element name="Body" minOccurs="0">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="Part">
                      <xs:complexType>
                        <xs:sequence>
                          <xs:element name="A" type="xs:string" minOccurs="0"/>
                        </xs:sequence>
                      </xs:complexType>
                    </xs:element>
                    <xs:element name="Part" maxOccurs="2">
                      <xs:complexType>
                        <xs:sequence>
                          <xs:element name="B" type="xs:string" minOccurs="0"/>
                        </xs:sequence>
                      </xs:complexType>
                    </xs:element>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:schema>
      """;

  private static final String SAME_STEP = "same-step.xsd";

  /**
   * The model from {@code file}'s {@code root} to itself with the given target field mappings, its
   * fingerprints refreshed from {@code base}.
   */
  private static byte[] model(Path base, String file, String root, String targetFieldMappings) {
    String side =
        """
        {"bundle": ["%s"], "fingerprint": "%s", "root": {"element": "%s"}}"""
            .formatted(file, PLACEHOLDER, root);
    return Fingerprints.refreshed(
        """
        {"formatVersion": 1, "source": %1$s, "target": %1$s, "targetFieldMappings": %2$s}
        """
            .formatted(side, targetFieldMappings)
            .getBytes(UTF_8),
        base);
  }

  /** {@code SAME_STEP_XSD}, written to {@code base}, mapped by {@code targetFieldMappings}. */
  private static byte[] sameStep(Path base, String targetFieldMappings) throws Exception {
    Files.writeString(base.resolve(SAME_STEP), SAME_STEP_XSD);
    return model(base, SAME_STEP, "Doc", targetFieldMappings);
  }

  /** One target field mapping {@code id} whose result is the source-field box {@code box}. */
  private static String link(String targetField, String id, String box, String sourceField) {
    return """
        "%s": {"id": "%s", "result": "%s",
               "boxes": {"%3$s": {"kind": "source", "field": "%s"}}}"""
        .formatted(targetField, id, box, sourceField);
  }

  private static String mappings(String... links) {
    return "{" + String.join(", ", links) + "}";
  }

  private static CompiledMapping compiled(CompileResult result) {
    if (result instanceof CompileResult.Compiled(CompiledMapping mapping)) {
      return mapping;
    }
    throw new AssertionError("expected Compiled, got " + result);
  }

  private static List<Diagnostic> refusal(CompileResult result) {
    if (result instanceof CompileResult.Refused(List<Diagnostic> diagnostics)) {
      return diagnostics;
    }
    throw new AssertionError("expected Refused, got " + result);
  }

  /** Severity, code and location; the message is checked on its own. */
  private static List<String> described(List<Diagnostic> diagnostics) {
    return diagnostics.stream()
        .map(d -> d.severity() + " " + d.code() + " " + d.location())
        .toList();
  }

  private static List<String> ambiguousAt(Location location) {
    return described(List.of(new Diagnostic(Severity.ERROR, CODE, location, "ignored")));
  }

  private static AtAuthoringId at(String id) {
    return new AtAuthoringId(new AuthoringId(id));
  }

  /** The pointer to the absent member of {@code targetFieldMappings} for {@code fieldPath}. */
  private static AtPointer pointer(String fieldPath) {
    return new AtPointer("/targetFieldMappings/" + fieldPath.replace("~", "~0").replace("/", "~1"));
  }

  /**
   * Compiles {@code model} as deployed and as test-run, and checks that each is refused with the
   * one diagnostic {@code ambiguous-field-path} at {@code location}, whose message names {@code
   * path} and the second declaration's {@code file line:column}.
   */
  private static void assertAmbiguous(
      byte[] model, Path base, Location location, String path, String position) throws Exception {
    for (CompileOptions options : List.of(CompileOptions.DEPLOY, CompileOptions.TEST_RUN)) {
      List<Diagnostic> diagnostics =
          refusal(new EngineFacade().compile(model, DirectoryResolver.over(base), options));

      assertThat(described(diagnostics)).as("%s", options).isEqualTo(ambiguousAt(location));
      assertThat(diagnostics.getFirst().message())
          .as("%s: the message", options)
          .contains(path)
          .contains(position);
    }
  }

  static Stream<Arguments> sourceBoxes() {
    return Stream.of(
        Arguments.of("a leaf between its same-step sibling", "/Doc/Note", "same-step.xsd 8:65"),
        Arguments.of("a structure field onto a leaf", "/Doc/Body/Part", "same-step.xsd 28:53"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceBoxes")
  void a_source_box_on_a_same_step_sibling_is_refused_at_the_box(
      String why, String sourceField, String position, @TempDir Path base) throws Exception {
    byte[] model = sameStep(base, mappings(link("/Doc/Id", "t1", "s1", sourceField)));

    assertAmbiguous(model, base, at("s1"), sourceField, position);
  }

  static Stream<Arguments> targetFieldMappings() {
    return Stream.of(
        Arguments.of(
            "a leaf whose same-step sibling is optional",
            mappings(link("/Doc/Note", "t1", "s1", "/Doc/Id")),
            "t1",
            "/Doc/Note",
            "same-step.xsd 8:65"),
        // the walk adds nothing at /targetFieldMappings/~1Doc~1Head~1Ref: it is reported at t2
        Arguments.of(
            "a leaf whose later same-step siblings are mandatory, under a mapped parent",
            mappings(
                link("/Doc/Head", "t1", "s1", "/Doc/Head"),
                link("/Doc/Head/Ref", "t2", "s2", "/Doc/Id")),
            "t2",
            "/Doc/Head/Ref",
            "same-step.xsd 13:56"),
        // neither created as 1..1 nor unsupported-field as 1..2 at its pointer
        Arguments.of(
            "a structure field 1..1 beside one 1..2, under a mapped parent",
            mappings(
                link("/Doc/Body", "t1", "s1", "/Doc/Body"),
                link("/Doc/Body/Part", "t2", "s2", "/Doc/Body")),
            "t2",
            "/Doc/Body/Part",
            "same-step.xsd 28:53"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("targetFieldMappings")
  void a_target_field_mapping_on_a_same_step_sibling_is_refused_at_the_mapping(
      String why,
      String targetFieldMappings,
      String mapping,
      String targetField,
      String position,
      @TempDir Path base)
      throws Exception {
    byte[] model = sameStep(base, targetFieldMappings);

    assertAmbiguous(model, base, at(mapping), targetField, position);
  }

  // --- B stands only under the second Part, yet its path goes through the refused step Part: it is
  // ambiguous-field-path with the refusal nearest the root, never orphaned-reference (ADR-0008
  // Shape (#92), the architect's ruling on #92)

  private static final String ONLY_UNDER_THE_SECOND = "/Doc/Body/Part/B";

  @Test
  void a_source_box_on_a_path_only_the_second_sibling_holds_is_refused_not_orphaned(
      @TempDir Path base) throws Exception {
    byte[] source = sameStep(base, mappings(link("/Doc/Id", "t1", "s1", ONLY_UNDER_THE_SECOND)));

    assertAmbiguous(source, base, at("s1"), "/Doc/Body/Part", "same-step.xsd 28:53");
  }

  /**
   * {@code Body} is mapped, so the walk also meets the two unmapped {@code Part}s, 1..1 and 1..2,
   * which no mapping writes: they are reported once at their pointer.
   */
  @Test
  void a_target_field_mapping_on_a_path_only_the_second_sibling_holds_is_refused_not_orphaned(
      @TempDir Path base) throws Exception {
    byte[] target =
        sameStep(
            base,
            mappings(
                link("/Doc/Body", "t1", "s1", "/Doc/Body"),
                link(ONLY_UNDER_THE_SECOND, "t2", "s2", "/Doc/Id")));
    for (CompileOptions options : List.of(CompileOptions.DEPLOY, CompileOptions.TEST_RUN)) {
      List<Diagnostic> diagnostics =
          refusal(new EngineFacade().compile(target, DirectoryResolver.over(base), options));

      assertThat(described(diagnostics))
          .as("%s", options)
          .isEqualTo(
              described(
                  List.of(
                      new Diagnostic(Severity.ERROR, CODE, pointer("/Doc/Body/Part"), "ignored"),
                      new Diagnostic(Severity.ERROR, CODE, at("t2"), "ignored"))));
      assertThat(diagnostics)
          .as("%s: the messages", options)
          .extracting(Diagnostic::message)
          .allSatisfy(
              m -> assertThat(m).contains("/Doc/Body/Part").contains("same-step.xsd 28:53"));
    }
  }

  static Stream<Arguments> unmappedMandatorySiblings() {
    return Stream.of(
        // the first Ref is 0..1, the other two 1..1: each sibling counts with its own occurrence
        Arguments.of(
            "leaves 0..1, 1..1, 1..1 under a mapped parent",
            link("/Doc/Head", "t1", "s1", "/Doc/Head"),
            "/Doc/Head/Ref",
            "same-step.xsd 13:56"),
        // before #92 the first Part, 1..1, was created; the second, 1..2, needs a mapping
        Arguments.of(
            "structure fields 1..1 and 1..2 under a mapped parent",
            link("/Doc/Body", "t1", "s1", "/Doc/Body"),
            "/Doc/Body/Part",
            "same-step.xsd 28:53"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unmappedMandatorySiblings")
  void an_unmapped_mandatory_same_step_sibling_is_refused_once_at_its_pointer(
      String why, String parentLink, String siblings, String position, @TempDir Path base)
      throws Exception {
    byte[] model = sameStep(base, mappings(parentLink));

    // once per path, and no unmapped-mandatory-leaf or unsupported-field beside it
    assertAmbiguous(model, base, pointer(siblings), siblings, position);
  }

  /**
   * The siblings left unused, and their neighbour {@code Code} mapped on both sides: the model
   * compiles and runs, and the output holds neither sibling, nor {@code Head} or {@code Body},
   * which are optional and unmapped.
   */
  @Test
  void unused_same_step_siblings_compile_clean(@TempDir Path base) throws Exception {
    EngineFacade facade = new EngineFacade();
    byte[] model =
        sameStep(
            base,
            mappings(
                link("/Doc/Id", "t1", "s1", "/Doc/Id"),
                link("/Doc/Code", "t2", "s2", "/Doc/Code")));
    byte[] input =
        ("<Doc><Id>i</Id><Note>n1</Note><Code>c</Code><Note>n2</Note>"
                + "<Head><Ref>r1</Ref><Ref>r2</Ref><Ref>r3</Ref></Head>"
                + "<Body><Part><A>a</A></Part><Part><B>b</B></Part></Body></Doc>")
            .getBytes(UTF_8);

    for (CompileOptions options : List.of(CompileOptions.DEPLOY, CompileOptions.TEST_RUN)) {
      byte[] output =
          facade.run(compiled(facade.compile(model, DirectoryResolver.over(base), options)), input);

      assertThat(new String(output, UTF_8))
          .as("%s", options)
          .isEqualTo(DECLARATION + "<Doc><Id>i</Id><Code>c</Code></Doc>");
    }

    // witness: the siblings this model leaves unused are refused fields of the loaded structure
    StructureResult loaded =
        Structures.load(
            DirectoryResolver.over(base),
            new SchemaBundle(List.of(SAME_STEP)),
            new ElementRoot("Doc"));
    if (!(loaded instanceof StructureResult.Loaded(Structure structure))) {
      throw new AssertionError("expected Loaded, got " + loaded);
    }
    assertThat(structure.types().get(structure.root().type().orElseThrow()).fields())
        .extracting(f -> f.step().localName() + (f.refusal().isPresent() ? " refused" : ""))
        .containsExactly("Id", "Note refused", "Code", "Note refused", "Head", "Body");
  }

  // --- a field below a same-step sibling (property): generated schemas

  /** A generated element declaration, optional; a leaf when it has no children. */
  private static final class Decl {
    private final String name;
    private final String maxOccurs;
    private final List<Decl> children = new ArrayList<>();
    private int line;
    private int column;

    Decl(String name, String maxOccurs) {
      this.name = name;
      this.maxOccurs = maxOccurs;
    }
  }

  /**
   * A generated schema: the path of a leaf below a step that has same-step siblings, the end of the
   * start tag of that step's second declaration, and whether a further step on the path, nearer the
   * leaf, has same-step siblings too.
   */
  private record Generated(String xsd, String leaf, int line, int column, boolean nested) {}

  /** A model that uses the generated leaf on one side, and where its refusal must stand. */
  private record Side(byte[] model, Location at) {}

  /**
   * A root {@code Root} with the leaf {@code Id}, and a path {@code E0/E1/…} of 2 to 5 steps to a
   * leaf. One step above the leaf has 1 or 2 further declarations of its own step after it, some
   * structure fields, some leaves; half the schemas give a step below it the same. Unique optional
   * leaves stand before, between and after them. Every field is optional, so a target walk leaves
   * every unmapped one out.
   */
  private static Generated generate(Random random) {
    Decl root = new Decl("Root", "1");
    root.children.add(new Decl("Id", "1"));
    int depth = 2 + random.nextInt(4);
    int first = random.nextInt(depth - 1);
    int further = random.nextBoolean() ? first + 1 + random.nextInt(depth - 1 - first) : -1;
    int[] neighbour = {0};
    Decl parent = root;
    Decl second = null;
    StringBuilder leaf = new StringBuilder("/Root");
    for (int level = 0; level < depth; level++) {
      String name = "E" + level;
      Decl step = new Decl(name, random.nextBoolean() ? "1" : "unbounded");
      addNeighbours(parent, random, neighbour);
      parent.children.add(step);
      if (level == first || level == further) {
        int more = 1 + random.nextInt(2);
        for (int i = 0; i < more; i++) {
          addNeighbours(parent, random, neighbour);
          Decl again = new Decl(name, random.nextBoolean() ? "1" : "unbounded");
          if (random.nextBoolean()) {
            again.children.add(new Decl("Z", "1"));
          }
          parent.children.add(again);
          if (level == first && i == 0) {
            second = again;
          }
        }
      }
      addNeighbours(parent, random, neighbour);
      leaf.append('/').append(name);
      parent = step;
    }

    List<String> lines = new ArrayList<>();
    lines.add("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">");
    lines.add("  <xs:element name=\"Root\">");
    lines.add("    <xs:complexType>");
    lines.add("      <xs:sequence>");
    for (Decl child : root.children) {
      render(child, 8, lines);
    }
    lines.add("      </xs:sequence>");
    lines.add("    </xs:complexType>");
    lines.add("  </xs:element>");
    lines.add("</xs:schema>");
    return new Generated(
        String.join("\n", lines) + "\n", leaf.toString(), second.line, second.column, further >= 0);
  }

  /** 0 to 2 optional leaves with names no other field of the schema has. */
  private static void addNeighbours(Decl parent, Random random, int[] counter) {
    for (int i = random.nextInt(3); i > 0; i--) {
      parent.children.add(new Decl("N" + counter[0]++, "1"));
    }
  }

  /** Writes {@code decl} at {@code indent} and records where its start tag ends. */
  private static void render(Decl decl, int indent, List<String> lines) {
    String occurs =
        " minOccurs=\"0\"" + (decl.maxOccurs.equals("1") ? "" : " maxOccurs=\"unbounded\"");
    String start =
        decl.children.isEmpty()
            ? "<xs:element name=\"" + decl.name + "\" type=\"xs:string\"" + occurs + "/>"
            : "<xs:element name=\"" + decl.name + "\"" + occurs + ">";
    lines.add(" ".repeat(indent) + start);
    decl.line = lines.size();
    decl.column = indent + start.length() + 1;
    if (!decl.children.isEmpty()) {
      lines.add(" ".repeat(indent + 2) + "<xs:complexType>");
      lines.add(" ".repeat(indent + 4) + "<xs:sequence>");
      for (Decl child : decl.children) {
        render(child, indent + 6, lines);
      }
      lines.add(" ".repeat(indent + 4) + "</xs:sequence>");
      lines.add(" ".repeat(indent + 2) + "</xs:complexType>");
      lines.add(" ".repeat(indent) + "</xs:element>");
    }
  }

  /** The seeds of the generated schemas. */
  static LongStream seeds() {
    return LongStream.rangeClosed(1, 30);
  }

  /**
   * Seeded schemas (ADR-0018: parameterised over seeds, the seed in the case name): a leaf below a
   * same-step sibling, read by a source-field box onto {@code /Root/Id} and written by a target
   * field mapping from {@code /Root/Id}, is refused at the box and at the mapping, and the message
   * names the second declaration of the step nearest the root, also when a step nearer the leaf has
   * same-step siblings too.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void a_field_below_a_same_step_sibling_is_refused(long seed, @TempDir Path dir) throws Exception {
    Random random = new Random(seed);
    Generated generated = generate(random);
    Files.writeString(dir.resolve("gen.xsd"), generated.xsd());
    String position = "gen.xsd " + generated.line() + ":" + generated.column();
    byte[] source =
        model(dir, "gen.xsd", "Root", mappings(link("/Root/Id", "t1", "s1", generated.leaf())));
    byte[] target =
        model(dir, "gen.xsd", "Root", mappings(link(generated.leaf(), "t1", "s1", "/Root/Id")));
    String what = "seed " + seed + ", " + generated.leaf() + " in\n" + generated.xsd();

    for (Side side : List.of(new Side(source, at("s1")), new Side(target, at("t1")))) {
      List<Diagnostic> diagnostics =
          refusal(
              new EngineFacade()
                  .compile(side.model(), DirectoryResolver.over(dir), CompileOptions.DEPLOY));

      assertThat(described(diagnostics)).as(what).isEqualTo(ambiguousAt(side.at()));
      assertThat(diagnostics.getFirst().message()).as(what).contains(position);
    }
  }

  /** The witness of the seeds: some schemas have a further same-step step nearer the leaf. */
  @Test
  void some_seeded_schemas_have_a_further_same_step_sibling_nearer_the_leaf_and_some_not() {
    long nested = seeds().filter(seed -> generate(new Random(seed)).nested()).count();

    assertThat(nested).isBetween(1L, 29L);
  }
}
