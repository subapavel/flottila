package dev.rail8.jq.pitfilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pitest.classinfo.ClassName;
import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.mutationtest.engine.Location;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.plugin.FeatureParser;
import org.pitest.plugin.FeatureSelector;
import org.pitest.plugin.FeatureSetting;
import org.pitest.plugin.ToggleStatus;

/**
 * The OWN_SURVIVORS feature (F-84, #192) over synthetic own-run reports in PIT 1.30.2's {@code mutations.xml} format.
 * A mutant is identified as {@code scripts/ratchet.java} identifies it for its union: mutatedClass, mutatedMethod,
 * methodDescription, lineNumber, mutator and indexes.
 */
class OwnSurvivorsFactoryTest {

  private static final String NEGATE = "org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator";
  private static final String MATH = "org.pitest.mutationtest.engine.gregor.mutators.MathMutator";
  private static final String BOUNDARY = "org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator";
  private static final String RETURNS = "org.pitest.mutationtest.engine.gregor.mutators.returns.PrimitiveReturnsMutator";

  private static Path fixture(String name) throws URISyntaxException {
    return Path.of(OwnSurvivorsFactoryTest.class.getResource("/" + name + "/mutations.xml").toURI());
  }

  private static MutationDetails mutant(String cls, String method, String desc, int line, String mutator,
      List<Integer> indexes) {
    MutationIdentifier id =
        new MutationIdentifier(Location.location(ClassName.fromString(cls), method, desc), indexes, mutator);
    return new MutationDetails(id, "Sample.java", "a synthetic mutant", line, List.of(0));
  }

  /** The mutants of own-run/mutations.xml, in its order, with their status there. */
  private static final class OwnRun {
    final MutationDetails killed = mutant("example.fixture.Sample", "run", "(I)V", 10, NEGATE, List.of(4));
    final MutationDetails survived = mutant("example.fixture.Sample", "run", "(I)V", 11, NEGATE, List.of(9));
    final MutationDetails noCoverage = mutant("example.fixture.Sample", "run", "(I)V", 12, MATH, List.of(15));
    final MutationDetails timedOut = mutant("example.fixture.Sample", "run", "(I)V", 13, NEGATE, List.of(20));
    final MutationDetails memoryError = mutant("example.fixture.Sample", "run", "(I)V", 14, MATH, List.of(25));
    final MutationDetails survivedInit =
        mutant("example.fixture.Sample$Inner", "<init>", "(Lexample/fixture/Sample;)V", 5, BOUNDARY, List.of(3, 7));
    final MutationDetails runError = mutant("example.fixture.Sample", "run", "(I)V", 15, NEGATE, List.of(30));
    final MutationDetails nonViable = mutant("example.fixture.Sample", "run", "(I)V", 16, MATH, List.of(35));

    List<MutationDetails> all() {
      return List.of(killed, survived, noCoverage, timedOut, memoryError, survivedInit, runError, nonViable);
    }
  }

  private static MutationInterceptor filter(Path... reports) {
    List<String> paths = new ArrayList<>();
    for (Path p : reports) {
      paths.add(p.toString());
    }
    FeatureSetting setting = new FeatureSetting("own_survivors", ToggleStatus.ACTIVATE, Map.of("report", paths));
    return new OwnSurvivorsFactory().createInterceptor(new InterceptorParameters(setting, null, null, null, null, null, null));
  }

  private static List<MutationDetails> keep(MutationInterceptor filter, List<MutationDetails> mutants) {
    return List.copyOf(filter.intercept(mutants, null));
  }

  @Test
  void keepsEveryMutantTheOwnRunDidNotKill() throws Exception {
    OwnRun own = new OwnRun();

    List<MutationDetails> kept = keep(filter(fixture("own-run")), own.all());

    assertThat(kept).containsExactly(own.survived, own.noCoverage, own.survivedInit, own.runError, own.nonViable);
  }

  private static final String WHOLE_ROW = "<mutation detected='false' status='SURVIVED' numberOfTestsRun='1'>"
      + "<sourceFile>Sample.java</sourceFile><mutatedClass>example.fixture.Sample</mutatedClass>"
      + "<mutatedMethod>run</mutatedMethod><methodDescription>(I)V</methodDescription><lineNumber>40</lineNumber>"
      + "<mutator>" + NEGATE + "</mutator><indexes><index>50</index></indexes><blocks><block>8</block></blocks>"
      + "<killingTest/><description>negated conditional</description></mutation>";

  static Stream<Arguments> malformedRows() {
    return Stream.of(
        Arguments.argumentSet("self-closing", "<mutation detected='true' status='KILLED' numberOfTestsRun='1'/>"),
        Arguments.argumentSet("no status", WHOLE_ROW.replace(" status='SURVIVED'", "")),
        Arguments.argumentSet("no mutatedClass", WHOLE_ROW.replaceAll("<mutatedClass>.*</mutatedClass>", "")),
        Arguments.argumentSet("no mutatedMethod", WHOLE_ROW.replaceAll("<mutatedMethod>.*</mutatedMethod>", "")),
        Arguments.argumentSet("no methodDescription",
            WHOLE_ROW.replaceAll("<methodDescription>.*</methodDescription>", "")),
        Arguments.argumentSet("no lineNumber", WHOLE_ROW.replaceAll("<lineNumber>.*</lineNumber>", "")),
        Arguments.argumentSet("no mutator", WHOLE_ROW.replaceAll("<mutator>.*</mutator>", "")),
        Arguments.argumentSet("no index", WHOLE_ROW.replace("<index>50</index>", "")),
        Arguments.argumentSet("lineNumber not a number", WHOLE_ROW.replace("<lineNumber>40<", "<lineNumber>x<")),
        Arguments.argumentSet("index not a number", WHOLE_ROW.replace("<index>50<", "<index>x<")));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("malformedRows")
  void failsOnAMutationRowWithoutItsWholeKey(String row, @TempDir Path dir) throws Exception {
    Path report = dir.resolve("mutations.xml");
    Files.writeString(report, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations partial=\"true\">\n" + WHOLE_ROW
        + "\n" + row + "\n</mutations>\n");

    assertThatThrownBy(() -> filter(report))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(report.toString());
  }

  @Test
  void readsTheWholeRowTheMalformedCasesStartFrom(@TempDir Path dir) throws Exception {
    Path report = dir.resolve("mutations.xml");
    Files.writeString(report, "<mutations>" + WHOLE_ROW + "</mutations>");
    MutationDetails whole = mutant("example.fixture.Sample", "run", "(I)V", 40, NEGATE, List.of(50));

    assertThat(keep(filter(report), List.of(whole))).containsExactly(whole);
  }

  @Test
  void dropsAMutantThatNoOwnReportNames() throws Exception {
    OwnRun own = new OwnRun();
    MutationDetails elsewhere = mutant("example.fixture.Unlisted", "run", "(I)V", 11, NEGATE, List.of(9));

    List<MutationDetails> kept = keep(filter(fixture("own-run")), List.of(elsewhere, own.survived));

    assertThat(kept).containsExactly(own.survived);
  }

  static Stream<Arguments> oneKeyFieldDiffers() {
    String cls = "example.fixture.Sample";
    return Stream.of(
        Arguments.argumentSet("mutatedClass", mutant(cls + "2", "run", "(I)V", 11, NEGATE, List.of(9))),
        Arguments.argumentSet("mutatedMethod", mutant(cls, "walk", "(I)V", 11, NEGATE, List.of(9))),
        Arguments.argumentSet("methodDescription", mutant(cls, "run", "(J)V", 11, NEGATE, List.of(9))),
        Arguments.argumentSet("lineNumber", mutant(cls, "run", "(I)V", 12, NEGATE, List.of(9))),
        Arguments.argumentSet("mutator", mutant(cls, "run", "(I)V", 11, MATH, List.of(9))),
        Arguments.argumentSet("indexes", mutant(cls, "run", "(I)V", 11, NEGATE, List.of(10))),
        Arguments.argumentSet("one index of two",
            mutant(cls + "$Inner", "<init>", "(Lexample/fixture/Sample;)V", 5, BOUNDARY, List.of(3))));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("oneKeyFieldDiffers")
  void dropsAMutantThatDiffersFromASurvivorInOneKeyField(MutationDetails variant) throws Exception {
    assertThat(keep(filter(fixture("own-run")), List.of(variant))).isEmpty();
  }

  @Test
  void keepsTheSurvivorsOfEveryOwnReport() throws Exception {
    OwnRun own = new OwnRun();
    MutationDetails other = mutant("example.fixture.Other", "size", "()J", 30, RETURNS, List.of(6));

    List<MutationDetails> kept =
        keep(filter(fixture("own-run"), fixture("second-module")), List.of(other, own.killed, own.noCoverage));

    assertThat(kept).containsExactly(other, own.noCoverage);
  }

  @Test
  void failsWhenAnOwnReportIsMissing(@TempDir Path dir) throws Exception {
    Path missing = dir.resolve("jq-core/target/pit-reports/mutations.xml");
    Path present = fixture("own-run");

    assertThatThrownBy(() -> filter(present, missing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(missing.toString());
  }

  @Test
  void failsWhenAnOwnReportIsCutShort(@TempDir Path dir) throws Exception {
    Path cut = dir.resolve("mutations.xml");
    String whole = Files.readString(fixture("own-run"));
    Files.writeString(cut, whole.substring(0, whole.indexOf("<mutation detected='false' status='SURVIVED'") + 40));

    assertThatThrownBy(() -> filter(cut))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(cut.toString());
  }

  @Test
  void failsWithoutAnyOwnReport() {
    assertThatThrownBy(() -> filter())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("report");
  }

  @Test
  void isAFilterInterceptor() throws Exception {
    assertThat(filter(fixture("own-run")).type()).isEqualTo(InterceptorType.FILTER);
  }

  private static List<MutationInterceptorFactory> activeOwnSurvivors(List<String> features) {
    List<MutationInterceptorFactory> factories = new ArrayList<>();
    ServiceLoader.load(MutationInterceptorFactory.class).forEach(factories::add);
    List<FeatureSetting> settings = new FeatureParser().parseFeatures(features);
    return new FeatureSelector<>(settings, factories).getActiveFeatures().stream()
        .filter(OwnSurvivorsFactory.class::isInstance)
        .toList();
  }

  @Test
  void pitFindsTheFeatureByTheNameOraclesPomGives() {
    String feature = "+OWN_SURVIVORS(report[a] report[b])";

    assertThat(activeOwnSurvivors(List.of(feature))).hasSize(1);
    assertThat(new FeatureParser().parseFeatures(List.of(feature)).getFirst().getList(OwnSurvivorsFactory.REPORT.name()))
        .containsExactly("a", "b");
  }

  @Test
  void isOffUnlessAPomTurnsItOn() {
    assertThat(activeOwnSurvivors(List.of())).isEmpty();
  }
}
