package dev.rail8.mapping.embed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.rail8.mapping.embed.MappingRefusedException.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The contract of the embedding API, over mappings of the corpus: what {@link Mapping#compile}
 * reads and refuses, and what {@link Mapping#run} returns and throws. Any implementation behind
 * {@code Mapping.compile} passes these tests.
 */
class MappingTest {

  /** {@code corpus/mappings}, where every mapping below lies; its references are relative to it. */
  private static final Path MAPPINGS = corpus().resolve("mappings");

  private static final OffsetDateTime NOON = OffsetDateTime.parse("2026-01-01T12:00:00Z");

  private static final String SOURCE = "../structures/observations/src-orders.xsd";
  private static final String TARGET = "../structures/observations/tgt-list.xsd";
  private static final String SOURCE_FINGERPRINT =
      "sha256:5e9bdd3db3b284fc086f0233797ede5e4f3fb874bd696ba3f496dd30bffef9a7";
  private static final String TARGET_FINGERPRINT =
      "sha256:626cbacff639a86b480bb3c20cc032d8aa47ad341d9111e31882ad30ca6eca70";

  /** {@code currentDate} with the default pattern into {@code /Out/All}. */
  private static final String CURRENT_DATE = "current-date.mapping.json";

  /** {@code formatNum} with pattern {@code 0} over {@code Qty} into {@code /Out/All}. */
  private static final String FORMAT_NUM = "format-num-zero.mapping.json";

  private static final byte[] ORDERS =
      "<Orders><Order><Line><Qty>7</Qty></Line></Order></Orders>".getBytes(UTF_8);

  private static final String ALL_AT_NOON =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Out><All>2026/01/01</All></Out>";

  @Test
  void aMappingCompilesAndRunsToItsOutput() throws Exception {
    Mapping mapping = compile(FORMAT_NUM);

    assertThat(new String(mapping.run(ORDERS, 1000, NOON), UTF_8))
        .isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Out><All>7</All></Out>");
    assertThat(mapping.mediaType()).isEqualTo("application/xml");
  }

  @Test
  void compileReadsEachBundleReferenceOnceSourceFirstOnTheCallingThread() throws Exception {
    List<String> asked = new ArrayList<>();
    Thread caller = Thread.currentThread();

    Mapping.compile(
        read(FORMAT_NUM),
        reference -> {
          assertThat(Thread.currentThread()).isSameAs(caller);
          asked.add(reference.spelling());
          return files().read(reference);
        });

    assertThat(asked).containsExactly(SOURCE, TARGET);
  }

  @Test
  void aRelativeReferenceKeepsItsPathAsWritten() throws Exception {
    List<BundleReference> asked = new ArrayList<>();

    compile(read(FORMAT_NUM), asked);

    assertThat(asked)
        .containsExactly(
            new BundleReference.Relative(SOURCE), new BundleReference.Relative(TARGET));
  }

  @Test
  void aPackageReferenceIsParsedIntoItsNameVersionAndPath() throws Exception {
    List<BundleReference> asked = new ArrayList<>();
    byte[] model = replace(read(FORMAT_NUM), SOURCE, "common@1.4.0:/orders/src-orders.xsd");

    compile(model, asked);

    BundleReference packaged = asked.getFirst();
    assertThat(packaged)
        .isEqualTo(
            new BundleReference.Packaged(
                "common@1.4.0:/orders/src-orders.xsd", "common", "1.4.0", "orders/src-orders.xsd"));
    assertThat(packaged.spelling()).isEqualTo("common@1.4.0:/orders/src-orders.xsd");
  }

  @Test
  void anIOExceptionFromTheFilesIsSchemaUnreadableAtThatReference() {
    BundleFiles noSource =
        reference -> {
          if (reference.spelling().equals(SOURCE)) {
            throw new IOException("gone");
          }
          return files().read(reference);
        };

    assertThatThrownBy(() -> Mapping.compile(read(FORMAT_NUM), noSource))
        .isInstanceOfSatisfying(
            MappingRefusedException.class,
            refused ->
                assertThat(refused.problems())
                    .extracting(Problem::code, Problem::at)
                    .containsExactly(tuple("schema-unreadable", SOURCE + ":1:1")));
  }

  @Test
  void anUncheckedExceptionFromTheFilesPassesThroughUnchanged() {
    IllegalArgumentException hosts = new IllegalArgumentException("the host's own words");

    assertThatThrownBy(
            () ->
                Mapping.compile(
                    read(FORMAT_NUM),
                    reference -> {
                      throw hosts;
                    }))
        .isSameAs(hosts);
  }

  @Test
  void bytesThatAreNoJsonAreMalformedJsonAtTheFile() {
    assertThatThrownBy(() -> Mapping.compile("{\"formatVersion\":".getBytes(UTF_8), files()))
        .isInstanceOfSatisfying(
            MappingRefusedException.class,
            refused -> {
              assertThat(refused.problems()).hasSize(1);
              assertThat(refused.problems().getFirst().code()).isEqualTo("malformed-json");
              assertThat(refused.problems().getFirst().at()).isEmpty();
            });
  }

  @Test
  void aStoredFingerprintThatNoLongerMatchesIsRefused() {
    assertThatThrownBy(() -> compile("fingerprint-stale-source.mapping.json"))
        .isInstanceOfSatisfying(
            MappingRefusedException.class,
            refused ->
                assertThat(refused.problems())
                    .extracting(Problem::code, Problem::at)
                    .containsExactly(tuple("fingerprint-mismatch", "/source/fingerprint")));
  }

  @Test
  void aUdfBoxTheMappingReachesIsRefusedAtTheBoxWithoutReadingItsSource() {
    String model =
        """
        {"formatVersion": 1,
         "source": {"bundle": ["%s"], "fingerprint": "%s", "root": {"element": "Orders"}},
         "target": {"bundle": ["%s"], "fingerprint": "%s", "root": {"element": "Out"}},
         "udfs": {"twice": {"executionKind": "single", "inputs": ["value"],
                            "source": "udfs/Numbers.java#twice"}},
         "targetFieldMappings": {"/Out/All": {"id": "T1", "result": "N1", "boxes": {
           "N1": {"kind": "udf", "udf": "twice", "inputs": ["S1"]},
           "S1": {"kind": "source", "field": "/Orders/Order/Line/Qty", "context": "/Orders"}}}}}
        """
            .formatted(SOURCE, SOURCE_FINGERPRINT, TARGET, TARGET_FINGERPRINT);
    List<BundleReference> asked = new ArrayList<>();

    assertThatThrownBy(() -> compile(model.getBytes(UTF_8), asked))
        .isInstanceOfSatisfying(
            MappingRefusedException.class,
            refused ->
                assertThat(refused.problems())
                    .extracting(Problem::code, Problem::at)
                    .containsExactly(tuple("unsupported-box-kind", "N1")));
    assertThat(asked).extracting(BundleReference::spelling).containsExactly(SOURCE, TARGET);
  }

  @Test
  void theProblemsOfTheRefusingStageAreAllReported() {
    byte[] bothStale =
        replace(
            replace(read(FORMAT_NUM), SOURCE_FINGERPRINT, "sha256:" + "0".repeat(64)),
            TARGET_FINGERPRINT,
            "sha256:" + "1".repeat(64));

    assertThatThrownBy(() -> Mapping.compile(bothStale, files()))
        .isInstanceOfSatisfying(
            MappingRefusedException.class,
            refused -> {
              assertThat(refused.problems())
                  .extracting(Problem::at)
                  .containsExactly("/source/fingerprint", "/target/fingerprint");
              assertThat(refused)
                  .hasMessageContaining("fingerprint-mismatch at '/source/fingerprint'");
            });
  }

  @Test
  void nowIsTheTimeEveryDateFunctionSeesAndItsOffsetIsTheRunsZone() throws Exception {
    Mapping mapping = compile(CURRENT_DATE);
    OffsetDateTime lateInUtc = OffsetDateTime.parse("2026-01-01T23:30:00Z");

    assertThat(new String(mapping.run(ORDERS, 1000, NOON), UTF_8)).isEqualTo(ALL_AT_NOON);
    assertThat(new String(mapping.run(ORDERS, 1000, lateInUtc), UTF_8)).contains("2026/01/01");
    assertThat(
            new String(
                mapping.run(ORDERS, 1000, lateInUtc.withOffsetSameInstant(ZoneOffset.ofHours(1))),
                UTF_8))
        .contains("2026/01/02");
  }

  @Test
  void anInputThatIsNotWellFormedIsMalformedInput() throws Exception {
    Mapping mapping = compile(FORMAT_NUM);

    assertThatThrownBy(() -> mapping.run("<Orders>".getBytes(UTF_8), 1000, NOON))
        .isInstanceOfSatisfying(
            RunFailure.class,
            failure -> {
              assertThat(failure.reason()).isEqualTo(RunFailure.Reason.MALFORMED_INPUT);
              assertThat(failure.code()).isEmpty();
              assertThat(failure.authoringId()).isEmpty();
            });
  }

  @Test
  void anInputWithADoctypeIsMalformedInput() throws Exception {
    Mapping mapping = compile(FORMAT_NUM);
    byte[] doctype = ("<!DOCTYPE Orders []>" + new String(ORDERS, UTF_8)).getBytes(UTF_8);

    assertThatThrownBy(() -> mapping.run(doctype, 1000, NOON))
        .isInstanceOfSatisfying(
            RunFailure.class,
            failure -> assertThat(failure.reason()).isEqualTo(RunFailure.Reason.MALFORMED_INPUT));
  }

  @Test
  void aDataErrorCarriesItsCodeAndAuthoringId() throws Exception {
    Mapping mapping = compile(FORMAT_NUM);
    byte[] notADecimal =
        "<Orders><Order><Line><Qty>1e3</Qty></Line></Order></Orders>".getBytes(UTF_8);

    assertThatThrownBy(() -> mapping.run(notADecimal, 1000, NOON))
        .isInstanceOfSatisfying(
            RunFailure.class,
            failure -> {
              assertThat(failure.reason()).isEqualTo(RunFailure.Reason.DATA_ERROR);
              assertThat(failure.code()).contains("err:FORG0001");
              assertThat(failure.authoringId()).contains("N1");
              assertThat(failure).hasMessageContaining("N1");
            });
  }

  @Test
  void anOutputOfExactlyTheLimitIsReturnedAndOneByteMoreIsTooLarge() throws Exception {
    Mapping mapping = compile(CURRENT_DATE);
    int size = ALL_AT_NOON.getBytes(UTF_8).length;

    assertThat(mapping.run(ORDERS, size, NOON)).hasSize(size);
    assertThatThrownBy(() -> mapping.run(ORDERS, size - 1, NOON))
        .isInstanceOfSatisfying(
            RunFailure.class,
            failure -> {
              assertThat(failure.reason()).isEqualTo(RunFailure.Reason.OUTPUT_TOO_LARGE);
              assertThat(failure).hasMessageContaining(String.valueOf(size - 1));
            });
  }

  @Test
  void aLimitBelowOneByteIsRefusedBeforeAnythingRuns() throws Exception {
    Mapping mapping = compile(CURRENT_DATE);

    assertThatThrownBy(() -> mapping.run(ORDERS, 0, NOON))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anInterruptEndsTheRunWithInterruptedExceptionAndClearsTheStatus() throws Exception {
    Mapping mapping = compile(CURRENT_DATE);

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> mapping.run(ORDERS, 1000, NOON))
          .isInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isFalse();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void oneMappingServesConcurrentRuns() throws Exception {
    Mapping mapping = compile(CURRENT_DATE);
    Callable<String> run = () -> new String(mapping.run(ORDERS, 1000, NOON), UTF_8);
    List<Future<String>> outputs = new ArrayList<>();

    try (ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 16; i++) {
        outputs.add(runs.submit(run));
      }
    }

    for (Future<String> output : outputs) {
      assertThat(output.get()).isEqualTo(ALL_AT_NOON);
    }
  }

  /** The corpus mapping {@code name}, compiled over its corpus structures. */
  private static Mapping compile(String name) throws MappingRefusedException {
    return Mapping.compile(read(name), files());
  }

  /**
   * {@code model} compiled over the corpus structures, with each reference asked for added to
   * {@code asked}.
   */
  private static Mapping compile(byte[] model, List<BundleReference> asked)
      throws MappingRefusedException {
    return Mapping.compile(
        model,
        reference -> {
          asked.add(reference);
          return switch (reference) {
            case BundleReference.Relative r -> files().read(r);
            // the package reference's file is the corpus file of the same name
            case BundleReference.Packaged p -> files().read(new BundleReference.Relative(SOURCE));
          };
        });
  }

  /** The files of the corpus, read relative to {@code corpus/mappings}. */
  private static BundleFiles files() {
    return reference ->
        switch (reference) {
          case BundleReference.Relative(String path) -> Files.readAllBytes(MAPPINGS.resolve(path));
          case BundleReference.Packaged p -> throw new IOException("no package " + p.name());
        };
  }

  private static byte[] read(String name) {
    try {
      return Files.readAllBytes(MAPPINGS.resolve(name));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] replace(byte[] model, String text, String replacement) {
    String before = new String(model, UTF_8);
    assertThat(before).contains(text);
    return before.replace(text, replacement).getBytes(UTF_8);
  }

  /** {@code corpus/} of the repository that holds the working directory. */
  private static Path corpus() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      Path candidate = p.resolve("corpus/mappings");
      if (Files.isRegularFile(p.resolve("pom.xml")) && Files.isDirectory(candidate)) {
        return p.resolve("corpus");
      }
    }
    throw new IllegalStateException("no corpus above " + Path.of("").toAbsolutePath());
  }
}
