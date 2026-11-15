package io.flottila.mapping.structure.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.ValueType;
import io.flottila.mapping.structure.WsdlDirection;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * WSDL 1.1 and 2.0 (#96, ADR-0008 "JSON Schema and WSDL") at the reader behind {@code
 * Structures.load}, over the fixtures of {@code corpus/structures/wsdl/} and the #93 XSDs of {@code
 * corpus/structures/files-and-names/}, read through a resolver over {@code corpus/structures} that
 * records every reference it is asked for.
 *
 * <p>In the expected rows, {@code {x}} stands for {@code Q{urn:flottila:corpus:x}}; a selector is
 * {@code <interface> <operation> <direction> [<fault>]}.
 */
class WsdlReaderTest {

  private static final Path STRUCTURES =
      Path.of(System.getProperty("flottila.corpus"), "structures");

  private static final List<String> ORDER_11 =
      List.of(
          "wsdl/order-11.wsdl",
          "files-and-names/import-location/po/order.xsd",
          "files-and-names/import-location/common/types.xsd");

  private static final List<String> ORDER_20 =
      List.of(
          "files-and-names/import-location/common/types.xsd",
          "files-and-names/import-location/po/order.xsd",
          "wsdl/order-20.wsdl");

  private static final List<String> PO_ORDER =
      List.of(
          "/{po}Order 1..1 structure",
          "/{po}Order/{po}Id 1..1 xs:string",
          "/{po}Order/{po}ShipTo 1..1 structure",
          "/{po}Order/{po}ShipTo/Street 1..1 xs:string",
          "/{po}Order/{po}ShipTo/City 1..1 xs:string",
          "/{po}Order/{po}Total 1..1 xs:decimal",
          "/{po}Order/{common}Note 0..unbounded xs:string");

  /** A resolver over {@code corpus/structures} that records the references it is asked for. */
  private static final class Recording implements ReferenceResolver {
    final List<String> calls = new ArrayList<>();
    private final ReferenceResolver directory = DirectoryResolver.over(STRUCTURES);

    @Override
    public ResolvedFile resolve(String reference) throws UnresolvedReferenceException {
      calls.add(reference);
      return directory.resolve(reference);
    }
  }

  static Stream<Arguments> loads() {
    return Stream.of(
        Arguments.of(
            "WSDL 1.1: the input's message part element, from the XSD the inline schema imports",
            ORDER_11,
            "{wsdl11}OrderPort Submit input",
            PO_ORDER),
        Arguments.of(
            "WSDL 1.1: the output, an element of the inline schema",
            ORDER_11,
            "{wsdl11}OrderPort Submit output",
            List.of(
                "/{wsdl11}Receipt 1..1 structure",
                "/{wsdl11}Receipt/{wsdl11}OrderId 1..1 xs:string",
                "/{wsdl11}Receipt/{wsdl11}Accepted 1..1 xs:boolean")),
        Arguments.of(
            "WSDL 1.1: a fault, whose element's type uses a prefix the wsdl:definitions declares",
            ORDER_11,
            "{wsdl11}OrderPort Submit fault Rejected",
            List.of(
                "/{wsdl11}Rejection 1..1 structure",
                "/{wsdl11}Rejection/{wsdl11}Reason 1..unbounded xs:string")),
        Arguments.of(
            "WSDL 2.0: the input's element, from the XSD the inline schema imports by namespace",
            ORDER_20,
            "{wsdl20}OrderInterface Submit input",
            PO_ORDER),
        Arguments.of(
            "WSDL 2.0: the output, with the WSDL namespace as the default namespace",
            ORDER_20,
            "{wsdl20}OrderInterface Submit output",
            List.of(
                "/{wsdl20}Receipt 1..1 structure",
                "/{wsdl20}Receipt/@accepted 1..1 xs:boolean",
                "/{wsdl20}Receipt/OrderId 1..1 xs:string")),
        Arguments.of(
            "WSDL 2.0: a fault, the interface fault an outfault refers to",
            ORDER_20,
            "{wsdl20}OrderInterface Submit fault Rejected",
            List.of(
                "/{wsdl20}Rejection 1..1 structure", "/{wsdl20}Rejection/Reason 0..1 xs:string")),
        Arguments.of(
            "a message of an imported WSDL, two inline schemas, and wsdl:imports by URL that fall"
                + " back to the bundle WSDL and XSD of their namespaces",
            List.of("wsdl/split/service.wsdl", "wsdl/split/messages.wsdl", "wsdl/split/types.xsd"),
            "{split}Port Send input",
            List.of(
                "/{split-a}Doc 1..1 structure",
                "/{split-a}Doc/{split-b}Item 1..1 structure",
                "/{split-a}Doc/{split-b}Item/{split-types}Code 1..1 xs:string")),
        Arguments.of(
            "WSDL 2.0: an interface of an included file, an element of the including one",
            List.of("wsdl/include/main.wsdl", "wsdl/include/parts/interface.wsdl"),
            "{include20}Pinger Ping input",
            List.of("/{include20}Ping 1..1 structure", "/{include20}Ping/@at 0..1 xs:date")));
  }

  /** Each bundle file is read once, in bundle order, and no other file. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("loads")
  void a_WSDL_operation_message_is_the_root(
      String row, List<String> bundle, String selector, List<String> tree) throws Exception {
    Recording resolver = new Recording();

    Structure structure = load(resolver, bundle, selector);

    assertThat(tree(structure))
        .containsExactlyElementsOf(tree.stream().map(t -> expand(t)).toList());
    assertThat(resolver.calls).containsExactlyElementsOf(bundle);
  }

  static Stream<Arguments> notFound() {
    String at = "wsdl/order-11.wsdl 11:64 ";
    return Stream.of(
        Arguments.of(
            "an unknown interface",
            ORDER_11,
            "{wsdl11}Nowhere Submit input",
            at + "no portType or interface {wsdl11}Nowhere is declared in the schema bundle"),
        Arguments.of(
            "an interface named without its namespace",
            ORDER_11,
            "OrderPort Submit input",
            at + "no portType or interface Q{}OrderPort is declared in the schema bundle"),
        Arguments.of(
            "an unknown operation",
            ORDER_11,
            "{wsdl11}OrderPort Resubmit input",
            at + "the interface {wsdl11}OrderPort declares no operation Resubmit"),
        Arguments.of(
            "the output of a one-way operation",
            ORDER_11,
            "{wsdl11}OrderPort Cancel output",
            at + "the operation Cancel of {wsdl11}OrderPort has no output"),
        Arguments.of(
            "an unknown fault",
            ORDER_11,
            "{wsdl11}OrderPort Submit fault Lost",
            at + "the operation Submit of {wsdl11}OrderPort has no fault Lost"),
        Arguments.of(
            "a message with no part",
            ORDER_11,
            "{wsdl11}OrderPort Cancel input",
            at + "the message {wsdl11}CancelRequest has no part with an element"),
        Arguments.of(
            "a bundle of no WSDL: at the first file's xs:schema",
            List.of("skeleton/order.xsd"),
            "{wsdl11}OrderPort Submit input",
            "skeleton/order.xsd 6:59 no portType or interface {wsdl11}OrderPort is declared in the"
                + " schema bundle"));
  }

  /**
   * An interface, operation, message or fault the selector names but the bundle does not have is
   * {@code schema-root-not-found}, at the first bundle file's document element.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("notFound")
  void a_selector_the_bundle_does_not_have_is_schema_root_not_found(
      String row, List<String> bundle, String selector, String error) {
    Recording resolver = new Recording();

    assertThatThrownBy(() -> load(resolver, bundle, selector))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.errors())
                  .extracting(
                      x -> x.file() + " " + x.line() + ":" + x.column() + " " + x.getMessage())
                  .containsExactly(expand(error));
              assertThat(e.code()).isEqualTo("schema-root-not-found");
            });
    assertThat(resolver.calls).containsExactlyElementsOf(bundle);
  }

  static Stream<Arguments> refusals() {
    String outside = "schema-reference-outside-bundle ";
    String unsupported = "schema-construct-unsupported ";
    String invalid = "schema-invalid ";
    String file11 = "wsdl/refusals-11.wsdl ";
    String file20 = "wsdl/refusals-20.wsdl ";
    return Stream.of(
        Arguments.of(
            "WSDL 1.1: references outside the bundle, an inline refusal, RPC and multi-part"
                + " messages, a part without element, an input without message, undeclared names",
            List.of("wsdl/refusals-11.wsdl"),
            "{refusals11}Port Op input",
            List.of(
                outside + file11 + "11:81",
                outside + file11 + "14:62",
                unsupported + file11 + "20:33",
                unsupported + file11 + "26:44",
                unsupported + file11 + "30:47",
                unsupported + file11 + "33:29",
                invalid + file11 + "36:46",
                unsupported + file11 + "40:20",
                invalid + file11 + "41:41")),
        Arguments.of(
            "WSDL 2.0: an include outside the bundle, #other, #any and #none, an undeclared prefix,"
                + " a reference to no fault of the interface, an interface that extends another",
            List.of("wsdl/refusals-20.wsdl"),
            "{refusals20}Any Op input",
            List.of(
                outside + file20 + "9:44",
                unsupported + file20 + "11:43",
                unsupported + file20 + "12:27",
                unsupported + file20 + "14:30",
                unsupported + file20 + "15:32",
                unsupported + file20 + "17:34",
                invalid + file20 + "18:36",
                unsupported + file20 + "21:46")),
        Arguments.of(
            "names are checked beside an XSD that stopped, but none into its namespace",
            List.of("wsdl/after-errors/user.wsdl", "files-and-names/names-after-errors/broken.xsd"),
            "{user-wsdl}Port Send input",
            List.of(
                invalid + "wsdl/after-errors/user.wsdl 10:49",
                "schema-not-well-formed files-and-names/names-after-errors/broken.xsd 8:3")),
        Arguments.of(
            "a WSDL that stopped may declare any name in any namespace",
            List.of("wsdl/after-errors/user.wsdl", "wsdl/after-errors/broken.wsdl"),
            "{user-wsdl}Port Send input",
            List.of("schema-not-well-formed wsdl/after-errors/broken.wsdl 9:7")),
        Arguments.of(
            "a file never read may declare any name",
            List.of("wsdl/after-errors/user.wsdl", "wsdl/after-errors/missing.wsdl"),
            "{user-wsdl}Port Send input",
            List.of("schema-unreadable wsdl/after-errors/missing.wsdl 1:1")),
        Arguments.of(
            "a file that is neither a WSDL nor an XSD is read as an XSD",
            List.of("wsdl/order-11.wsdl", "wsdl/neither.xml"),
            "{wsdl11}OrderPort Submit output",
            List.of(unsupported + "wsdl/neither.xml 4:60")));
  }

  /**
   * One load reports every load error (ADR-0008): files in bundle order, each in document order,
   * inline schemas at their place in the WSDL file.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  void every_load_error_is_reported_at_once(
      String row, List<String> bundle, String selector, List<String> errors) {
    Recording resolver = new Recording();

    assertThatThrownBy(() -> load(resolver, bundle, selector))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e -> {
              assertThat(e.errors())
                  .extracting(
                      error ->
                          error.code()
                              + " "
                              + error.file()
                              + " "
                              + error.line()
                              + ":"
                              + error.column())
                  .containsExactlyElementsOf(errors);
              assertThat(e.errors())
                  .allSatisfy(error -> assertThat(error.getMessage()).isNotBlank());
            });
    assertThat(resolver.calls).containsExactlyElementsOf(bundle);
  }

  /** The messages of the WSDL refusals name the construct, and a reference the file it misses. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("messages")
  void a_WSDL_load_error_names_what_is_refused(
      String file, String selector, List<String> messages) {
    assertThatThrownBy(() -> load(new Recording(), List.of(file), selector))
        .isInstanceOfSatisfying(
            StructureLoadException.class,
            e ->
                assertThat(e.errors())
                    .extracting(Throwable::getMessage)
                    .containsExactlyElementsOf(messages));
  }

  static Stream<Arguments> messages() {
    return Stream.of(
        Arguments.of(
            "wsdl/refusals-11.wsdl",
            "{refusals11}Port Op input",
            List.of(
                "the location \"nowhere.wsdl\" of this wsdl:import names no file of the schema"
                    + " bundle, and no bundle file has its namespace \"urn:flottila:corpus:nowhere\"",
                "no file of the schema bundle has the namespace"
                    + " \"urn:flottila:corpus:refusals11\" this xs:import names",
                "<xs:assert> here is outside the XSD the reader reads",
                "<wsdl:part> with type, an RPC-style part, is outside the WSDL the reader reads",
                "<wsdl:part> after the first part of its message is outside the WSDL the reader"
                    + " reads",
                "<wsdl:part> without element is outside the WSDL the reader reads",
                "the element Q{urn:flottila:corpus:refusals11-types}Lost is declared in no file of"
                    + " the schema bundle",
                "<wsdl:input> without message is outside the WSDL the reader reads",
                "the message Q{urn:flottila:corpus:refusals11-types}Missing is declared in no file"
                    + " of the schema bundle")),
        Arguments.of(
            "wsdl/refusals-20.wsdl",
            "{refusals20}Any Op input",
            List.of(
                "the location \"../../../etc/passwd\" of this wsdl:include names no file of the"
                    + " schema bundle",
                "<fault> of the message content model #other is outside the WSDL the reader reads",
                "<fault> of the message content model #other is outside the WSDL the reader reads",
                "<input> of the message content model #any is outside the WSDL the reader reads",
                "<output> of the message content model #none is outside the WSDL the reader reads",
                "the prefix of nope:Other is not declared",
                "the fault Q{urn:flottila:corpus:refusals20}Unknown is declared in no fault of"
                    + " this interface",
                "<interface> with extends is outside the WSDL the reader reads")));
  }

  /** {@code <interface> <operation> <direction> [<fault>]}, the interface abbreviated. */
  private static Structure load(Recording resolver, List<String> bundle, String selector)
      throws StructureLoadException {
    String[] parts = selector.split(" ");
    WsdlDirection direction = WsdlDirection.valueOf(parts[2].toUpperCase(java.util.Locale.ROOT));
    Optional<String> fault = parts.length > 3 ? Optional.of(parts[3]) : Optional.empty();
    return new StructureReader(resolver)
        .read(
            new SchemaBundle(bundle),
            new WsdlRoot(new WsdlOperation(expand(parts[0]), parts[1], direction, fault)));
  }

  private static final Pattern ABBREVIATION = Pattern.compile("\\{([a-z0-9:-]+)}");

  /** {@code {x}} to {@code Q{urn:flottila:corpus:x}}. */
  private static String expand(String text) {
    return ABBREVIATION
        .matcher(text)
        .replaceAll(m -> Matcher.quoteReplacement("Q{urn:flottila:corpus:" + m.group(1) + "}"));
  }

  /** Paths of at most this many steps. */
  private static final int DEPTH = 4;

  /** Every field, depth first in child order: {@code "<path> <occurrence> <kind>"}. */
  private static List<String> tree(Structure structure) {
    List<String> rows = new ArrayList<>();
    addTree(structure, structure.rootPath(), rows);
    return rows;
  }

  private static void addTree(Structure structure, FieldPath path, List<String> rows) {
    Field field = structure.field(path).orElseThrow();
    rows.add(
        path
            + " "
            + field.occurrence()
            + " "
            + field.valueType().map(ValueType::typeName).orElse("structure"));
    if (path.steps().size() < DEPTH) {
      for (FieldPath child : structure.children(path)) {
        addTree(structure, child, rows);
      }
    }
  }
}
