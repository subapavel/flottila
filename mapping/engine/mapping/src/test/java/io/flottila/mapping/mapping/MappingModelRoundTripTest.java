package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.Fixtures.assertBytes;
import static io.flottila.mapping.mapping.Fixtures.utf8;
import static io.flottila.mapping.mapping.LoadResults.loaded;
import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.function;
import static io.flottila.mapping.mapping.Models.inputs;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.params;
import static io.flottila.mapping.mapping.Models.source;
import static io.flottila.mapping.mapping.Models.targetFieldMapping;
import static io.flottila.mapping.mapping.Models.targetFieldMappings;
import static io.flottila.mapping.mapping.Models.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * GATE check 2: a mapping model round-trips byte-identical in canonical form (ADR-0010), and a
 * model containing every box kind loads and round-trips, so storing is never limited to what the
 * engine runs.
 */
class MappingModelRoundTripTest {

  @Test
  void canonicalFixturesAreTheCommittedOnes() {
    assertThat(Fixtures.canonicalFixtures())
        .map(p -> p.getFileName().toString())
        .containsExactly(
            "escapes.mapping.json",
            "every-box-kind.mapping.json",
            "new-mapping.mapping.json",
            "no-structures.mapping.json",
            "studio-members.mapping.json");
  }

  static List<Path> canonicalFixtures() {
    return Fixtures.canonicalFixtures();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("canonicalFixtures")
  void canonicalFixturesRoundTripByteIdentical(Path fixture) throws Exception {
    byte[] committed = Files.readAllBytes(fixture);

    assertBytes(committed, MappingModelFormat.save(loaded(MappingModelFormat.load(committed))));
  }

  static Stream<Arguments> fixtureModels() {
    return Stream.of(
        Arguments.of("every-box-kind.mapping.json", FixtureModels.everyBoxKind()),
        Arguments.of("new-mapping.mapping.json", FixtureModels.newMapping()),
        Arguments.of("escapes.mapping.json", FixtureModels.escapes()),
        Arguments.of("studio-members.mapping.json", FixtureModels.studioMembers()),
        Arguments.of("no-structures.mapping.json", FixtureModels.noStructures()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtureModels")
  void canonicalFixturesLoadToTheModelsTheyDescribe(String fixture, MappingModel described)
      throws Exception {
    assertThat(loaded(MappingModelFormat.load(Fixtures.fixture(fixture)))).isEqualTo(described);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtureModels")
  void canonicalFixturesAreWhatSaveWritesForTheirModels(String fixture, MappingModel described) {
    assertBytes(Fixtures.fixture(fixture), MappingModelFormat.save(described));
  }

  /**
   * Valid, but in no canonical spelling: members out of order, compact with CR LF and tabs, {@code
   * \/} and {@code \u0040} escapes, an unsorted bundle, a context equal to the field's parent and
   * empty optional collections.
   */
  static final String NON_CANONICAL =
      "{\"udfs\":{\"today\":{\"source\":\"udf\\/Dates.java#today\",\"inputs\":[],\"params\":{},"
          + "\"executionKind\":\"single\"}},\r\n\t\"targetFieldMappings\":{\"\\/Invoice\\/Date\":"
          + "{\"result\":\"f1\",\"id\":\"t1\",\"boxes\":{\"u1\":{\"udf\":\"today\",\"kind\":\"udf\","
          + "\"inputs\":[],\"params\":{}},\r\n\"s1\":{\"kind\":\"source\",\"context\":\"/Order/Header\","
          + "\"field\":\"/Order/Header/\\u0040date\"},\"f1\":{\"params\":{\"table\":{\"b\":\"2\","
          + "\"a\":\"1\"},\"pattern\":\"\\u0079yyy-MM-dd\"},\"kind\":\"function\",\"inputs\":"
          + "[ \"s1\" , null ],\"fn\":\"formatDate\"}}}},\t\"target\":{\"root\":{\"element\":"
          + "\"Invoice\"},\"fingerprint\":\"sha256:fedcba9876543210fedcba9876543210fedcba9876543210"
          + "fedcba9876543210\",\"bundle\":[\"invoice.xsd\"]},\"source\":{\"root\":{\"element\":"
          + "\"Order\"},\"fingerprint\":\"sha256:0123456789abcdef0123456789abcdef0123456789abcdef"
          + "0123456789abcdef\",\"bundle\":[\"types.xsd\",\"order.xsd\"]},\"formatVersion\":1}\r\n";

  @Test
  void loadThenSaveWritesCanonicalForm() throws Exception {
    byte[] saved = MappingModelFormat.save(loaded(MappingModelFormat.load(utf8(NON_CANONICAL))));

    assertBytes(
        """
        {
          "formatVersion": 1,
          "source": {
            "bundle": [
              "order.xsd",
              "types.xsd"
            ],
            "fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "root": {
              "element": "Order"
            }
          },
          "target": {
            "bundle": [
              "invoice.xsd"
            ],
            "fingerprint": "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            "root": {
              "element": "Invoice"
            }
          },
          "targetFieldMappings": {
            "/Invoice/Date": {
              "boxes": {
                "f1": {
                  "fn": "formatDate",
                  "inputs": [
                    "s1",
                    null
                  ],
                  "kind": "function",
                  "params": {
                    "pattern": "yyyy-MM-dd",
                    "table": {
                      "a": "1",
                      "b": "2"
                    }
                  }
                },
                "s1": {
                  "field": "/Order/Header/@date",
                  "kind": "source"
                },
                "u1": {
                  "kind": "udf",
                  "udf": "today"
                }
              },
              "id": "t1",
              "result": "f1"
            }
          },
          "udfs": {
            "today": {
              "executionKind": "single",
              "source": "udf/Dates.java#today"
            }
          }
        }
        """,
        saved);
  }

  /**
   * The model of {@code studio-members.mapping.json} (ADR-0029) in no canonical spelling: members
   * reversed, compact with CR LF and a tab, {@code y} before {@code x}, {@code target} before
   * {@code boxes}, layout boxes against UTF-16 order, and {@code \}{@code u} escapes in the
   * description.
   */
  static final String STUDIO_NON_CANONICAL =
      "{\"targetFieldMappings\":{\"/Invoice/Total\":{\"result\":\"c2\",\"layout\":{\"boxes\":"
          + "{\"c2\":{\"y\":34,\"x\":12}}},\"id\":\"t2\",\"boxes\":{\"c2\":{\"params\":{\"value\":"
          + "\"0\"},\"kind\":\"function\",\"fn\":\"constant\"}}},\r\n\"/Invoice/Number\":{\"result\":"
          + "\"B2\",\"layout\":{\"target\":{\"y\":-16,\"x\":1000000},\"boxes\":{\"a1\":{\"y\":80,"
          + "\"x\":-1000000},\"_p\":{\"y\":1000000,\"x\":-240},\"B2\":{\"y\":-1000000,\"x\":0}}},"
          + "\"id\":\"t1\",\"boxes\":{\"a1\":{\"kind\":\"source\",\"field\":\"/Order/Number\"},"
          + "\"_p\":{\"kind\":\"source\",\"field\":\"/Order/Prefix\"},\"B2\":{\"kind\":\"function\","
          + "\"inputs\":[\"_p\",\"a1\"],\"fn\":\"concat\"}}},\r\n\"/Invoice/Date\":{\"result\":\"s3\","
          + "\"layout\":{\"target\":{\"y\":0,\"x\":640}},\"id\":\"t3\",\"boxes\":{\"s3\":{\"kind\":"
          + "\"source\",\"field\":\"/Order/Date\"}}},\r\n\"/Invoice/Currency\":{\"result\":\"s4\","
          + "\"id\":\"t4\",\"boxes\":{\"s4\":{\"kind\":\"source\",\"field\":\"/Order/Currency\"}}}},"
          + "\r\n\"target\":{\"root\":{\"element\":\"Invoice\"},\"fingerprint\":\"sha256:"
          + "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210\",\"bundle\":"
          + "[\"../structures/invoice.xsd\"]},\r\n\"source\":{\"root\":{\"element\":\"Order\"},"
          + "\"fingerprint\":\"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
          + "\",\"bundle\":[\"../structures/order.xsd\"]},\r\n\"formatVersion\":1,\t\"description\":"
          + "\"Orders to invoices, laid out in the studio.\\nZweite Zeile: \\\"Gr\\u00f6\\u00dfe\\\""
          + " \\ud83d\\ude00\"}\r\n";

  /**
   * The description and the layouts are kept and sorted as the rest of the file (ADR-0029): the
   * expected bytes are the committed fixture, checked against {@code JSON.stringify}.
   */
  @Test
  void loadThenSaveWritesTheStudioMembersInCanonicalForm() throws Exception {
    byte[] saved =
        MappingModelFormat.save(loaded(MappingModelFormat.load(utf8(STUDIO_NON_CANONICAL))));

    assertBytes(Fixtures.fixture("studio-members.mapping.json"), saved);
  }

  @Test
  void saveOfLoadIsIdempotent() throws Exception {
    byte[] once = MappingModelFormat.save(loaded(MappingModelFormat.load(utf8(NON_CANONICAL))));
    byte[] twice = MappingModelFormat.save(loaded(MappingModelFormat.load(once)));

    assertThat(once).isNotEqualTo(utf8(NON_CANONICAL));
    assertBytes(once, twice);
  }

  /** A constant whose value is the lone low surrogate U+DE00, in canonical form. */
  static final String LONE_LOW_SURROGATE =
      MappingModelWriterTest.HEADER
          + """
            "targetFieldMappings": {
              "/Invoice/Currency": {
                "boxes": {
                  "c1": {
                    "fn": "constant",
                    "kind": "function",
                    "params": {
                      "value": "\\ude00"
                    }
                  }
                },
                "id": "t1",
                "result": "c1"
              }
            }
          }
          """;

  /**
   * json-sKema 0.32.0 decodes this escape as U+00DE (see StrictJsonReader); the reader must not.
   * The bytes come first: the writer spells U+00DE literally, so a wrong decode fails with a
   * readable diff, where a report of the lone surrogate itself is cut off.
   */
  @Test
  void loneLowSurrogateEscapeRoundTripsAsItself() throws Exception {
    MappingModel read = loaded(MappingModelFormat.load(utf8(LONE_LOW_SURROGATE)));

    assertBytes(LONE_LOW_SURROGATE, MappingModelFormat.save(read));
    assertThat(read)
        .isEqualTo(
            model(
                MappingModelWriterTest.ORDER,
                MappingModelWriterTest.INVOICE,
                targetFieldMappings(
                    "/Invoice/Currency",
                    targetFieldMapping(
                        "t1",
                        "c1",
                        boxes(
                            "c1",
                            function("constant", inputs(), params("value", text("\ude00")))))),
                Map.of()));
  }

  static Stream<Arguments> contextsOnTheParent() {
    return Stream.of(
        Arguments.of("/Order/Header/Date", "/Order/Header"),
        Arguments.of("/Order/Header/@date", "/Order/Header"),
        // the last step's namespace holds a '/', so the parent is not the text before the last '/'
        Arguments.of("/Order/Q{urn:x/y}Date", "/Order"));
  }

  /**
   * The parent is a source box's default context, and the writer leaves it out; a loaded model has
   * no {@code context} equal to the field's parent either, so load agrees with load after save.
   */
  @ParameterizedTest(name = "{0} in context {1}")
  @MethodSource("contextsOnTheParent")
  void loadDropsAContextEqualToTheFieldsParentAsSaveDoes(String field, String parent)
      throws Exception {
    String json =
        MappingModelWriterTest.HEADER
            + """
              "targetFieldMappings": {
                "/Invoice/Date": {
                  "boxes": {
                    "s1": {
                      "context": "%s",
                      "field": "%s",
                      "kind": "source"
                    }
                  },
                  "id": "t1",
                  "result": "s1"
                }
              }
            }
            """
                .formatted(parent, field);

    MappingModel once = loaded(MappingModelFormat.load(utf8(json)));
    MappingModel again = loaded(MappingModelFormat.load(MappingModelFormat.save(once)));

    assertThat(once).isEqualTo(again);
    assertThat(once.targetFieldMappings().get("/Invoice/Date").boxes())
        .isEqualTo(boxes("s1", source(field)));
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /** Property: any valid model in canonical normal form survives save then load unchanged. */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void savedModelReloadsEqual(long seed) throws Exception {
    MappingModel model = new ModelGenerator(seed).model();

    assertThat(loaded(MappingModelFormat.load(MappingModelFormat.save(model)))).isEqualTo(model);
  }

  /**
   * Witness for {@link #savedModelReloadsEqual}: its seeds reach every member ADR-0029 adds, so the
   * property cannot pass on models that never have them.
   */
  @Test
  void theGeneratedModelsReachTheDescriptionTheLayoutAndAMissingSide() {
    List<MappingModel> models = seeds().mapToObj(seed -> new ModelGenerator(seed).model()).toList();
    List<Layout> layouts =
        models.stream()
            .flatMap(m -> m.targetFieldMappings().values().stream())
            .map(TargetFieldMapping::layout)
            .toList();
    List<Integer> coordinates =
        layouts.stream()
            .flatMap(l -> Stream.concat(l.target().stream(), l.boxes().values().stream()))
            .flatMap(p -> Stream.of(p.x(), p.y()))
            .toList();

    assertThat(models).anyMatch(m -> m.source().isEmpty()).anyMatch(m -> m.target().isEmpty());
    assertThat(models)
        .anyMatch(m -> m.description().isPresent())
        .anyMatch(m -> m.description().isEmpty())
        .anyMatch(
            m -> m.description().map(d -> d.codePointCount(0, d.length()) == 4000).orElse(false));
    assertThat(layouts)
        .anyMatch(l -> l.target().isPresent())
        .anyMatch(l -> !l.boxes().isEmpty())
        .anyMatch(l -> l.target().isEmpty() && l.boxes().isEmpty());
    assertThat(coordinates).contains(-1_000_000, 0, 1_000_000);
  }
}
