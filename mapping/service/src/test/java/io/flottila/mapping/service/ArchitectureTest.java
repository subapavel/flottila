package io.flottila.mapping.service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.flottila.mapping.mapping.MappingModel;
import io.flottila.mapping.structure.importer.planted.PlantedFileReads;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The service is the unnamed module, so javac does not keep it out of the engine's internals. These
 * rules do (ADR-0016, "Enforcement"). Rule A reads the exported packages from the engine jars'
 * {@code module-info.class}; there is no hand-written list.
 *
 * <p>No rule allows an empty set (#28): a rule never passes because it matches nothing.
 */
class ArchitectureTest {

  private static final String ENGINE_MODULE_PREFIX = "io.flottila.mapping.";
  private static final String SERVICE_PACKAGE = "io.flottila.mapping.service";

  /** Rule A: service classes use only engine packages that the engine modules export. */
  @Test
  void ruleA_serviceUsesOnlyExportedEnginePackages() throws IOException {
    Set<String> exported = exportedEnginePackages();
    DescribedPredicate<JavaClass> unexportedEnginePackage =
        DescribedPredicate.describe(
            "classes in an engine package that its module does not export",
            c ->
                c.getPackageName().startsWith(ENGINE_MODULE_PREFIX)
                    && !isServicePackage(c.getPackageName())
                    && !exported.contains(c.getPackageName()));
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(SERVICE_PACKAGE + "..")
            .should()
            .dependOnClassesThat(unexportedEnginePackage);
    rule.check(importedClasses());
  }

  /** Rule B: no engine class depends on a framework or on the service. */
  @Test
  void ruleB_engineKnowsNoFrameworkAndNoService() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.flottila.mapping..")
            .and()
            .resideOutsideOfPackage(SERVICE_PACKAGE + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "io.quarkus..", "jakarta..", "com.fasterxml..", SERVICE_PACKAGE + "..");
    rule.check(importedClasses());
  }

  /** Rule C: every service class lives in the service package, so rule B can see it. */
  @Test
  void ruleC_everyServiceClassLivesInTheServicePackage() {
    JavaClasses serviceClasses =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPath(Path.of("target", "classes"));
    ArchRule rule = classes().should().resideInAPackage(SERVICE_PACKAGE + "..");
    rule.check(serviceClasses);
  }

  /** Engine classes the service never touches, though their packages are exported. */
  private static final Set<String> BEHIND_THE_FACADE =
      Set.of(
          "io.flottila.mapping.mapping.MappingModelFormat",
          "io.flottila.mapping.mapping.Lowering",
          "io.flottila.mapping.runtime.Interpreter",
          "io.flottila.mapping.structure.importer.StructureReader",
          "io.flottila.mapping.ir.IrOptimiser");

  /** Engine packages the service never touches, but for {@code ir.AuthoringId}. */
  private static final List<String> PACKAGES_BEHIND_THE_FACADE =
      List.of(
          "io.flottila.mapping.ir",
          "io.flottila.mapping.xdm",
          "io.flottila.mapping.serializer",
          "io.flottila.mapping.udf",
          "io.flottila.mapping.xslt.export");

  /**
   * Rule D: the service reaches the engine only through the facade (ADR-0024, "Service boundary"):
   * {@code EngineFacade}, {@code Structures} and the model and structure records.
   */
  @Test
  void ruleD_serviceReachesTheEngineOnlyThroughTheFacade() {
    DescribedPredicate<JavaClass> behindTheFacade =
        DescribedPredicate.describe(
            "engine classes behind the facade",
            c ->
                BEHIND_THE_FACADE.contains(c.getName())
                    || (!c.getName().equals("io.flottila.mapping.ir.AuthoringId")
                        && PACKAGES_BEHIND_THE_FACADE.stream()
                            .anyMatch(
                                p ->
                                    c.getPackageName().equals(p)
                                        || c.getPackageName().startsWith(p + "."))));
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(SERVICE_PACKAGE + "..")
            .should()
            .dependOnClassesThat(behindTheFacade);
    rule.check(importedClasses());
  }

  /**
   * Rule E: the service builds no mapping model (ADR-0016, #54): a new one comes from {@code
   * EngineFacade.newMapping}, so the format version and its defaults stay in the engine.
   */
  @Test
  void ruleE_serviceBuildsNoMappingModel() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(SERVICE_PACKAGE + "..")
            .should()
            .callConstructorWhere(
                DescribedPredicate.describe(
                    "a MappingModel constructor",
                    call -> call.getTargetOwner().isEquivalentTo(MappingModel.class)));
    rule.check(importedClasses());
  }

  private static final String STRUCTURE_IMPORT = "io.flottila.mapping.structure.importer";

  /**
   * Rule F (ADR-0016, #90): structure import opens no file. It reads every schema file through the
   * reference resolver (ADR-0014), so no class of its packages depends on {@code java.nio.file..},
   * {@code java.io.File*}, {@code java.io.RandomAccessFile} or {@code
   * java.nio.channels.FileChannel}. Its subject is never empty.
   */
  private static ArchRule ruleF() {
    DescribedPredicate<JavaClass> fileSystem =
        DescribedPredicate.describe(
            "the file system (java.nio.file.., java.io.File*, java.io.RandomAccessFile,"
                + " java.nio.channels.FileChannel)",
            c ->
                c.getPackageName().equals("java.nio.file")
                    || c.getPackageName().startsWith("java.nio.file.")
                    || (c.getPackageName().equals("java.io")
                        && c.getSimpleName().startsWith("File"))
                    || c.getName().equals("java.io.RandomAccessFile")
                    || c.getName().equals("java.nio.channels.FileChannel"));
    return noClasses()
        .that()
        .resideInAPackage(STRUCTURE_IMPORT + "..")
        .should()
        .dependOnClassesThat(fileSystem)
        .allowEmptyShould(false);
  }

  @Test
  void ruleF_structureImportOpensNoFile() {
    ruleF().check(importedClasses());
  }

  /**
   * A planted class and the file-system dependency its violation must name: the call or the
   * constructor, as ArchUnit writes its target.
   */
  record Planted(Class<?> fixture, String dependency) {}

  static Stream<Named<Planted>> plantedFileReads() {
    return Stream.of(
        planted(
            "Files.readAllBytes",
            PlantedFileReads.FilesReadAllBytes.class,
            "<java.nio.file.Files.readAllBytes("),
        planted("Path.of", PlantedFileReads.PathOf.class, "<java.nio.file.Path.of("),
        planted("Paths.get", PlantedFileReads.PathsGet.class, "<java.nio.file.Paths.get("),
        planted("new File", PlantedFileReads.NewFile.class, "<java.io.File.<init>("),
        planted(
            "new FileInputStream",
            PlantedFileReads.NewFileInputStream.class,
            "<java.io.FileInputStream.<init>("),
        planted(
            "FileChannel.open",
            PlantedFileReads.FileChannelOpen.class,
            "<java.nio.channels.FileChannel.open("),
        planted(
            "new RandomAccessFile",
            PlantedFileReads.NewRandomAccessFile.class,
            "<java.io.RandomAccessFile.<init>("),
        // a java.nio.file subpackage only: the fixture of the rule's subpackage clause
        planted(
            "PosixFilePermissions.fromString",
            PlantedFileReads.PosixPermissionsFromString.class,
            "<java.nio.file.attribute.PosixFilePermissions.fromString("));
  }

  private static Named<Planted> planted(String name, Class<?> fixture, String dependency) {
    return Named.of(name, new Planted(fixture, dependency));
  }

  /**
   * Rule F fails on each planted file read, and the violation names the planted class and the
   * forbidden target, so it never fails only because it checked no class.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("plantedFileReads")
  void ruleF_failsOnAPlantedFileRead(Planted planted) {
    EvaluationResult result =
        ruleF().evaluate(new ClassFileImporter().importClasses(planted.fixture()));

    assertThat(result.hasViolation()).as("rule F on %s", planted.fixture().getName()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .as("the violations of %s", planted.fixture().getName())
        .anySatisfy(
            detail ->
                assertThat(detail)
                    .contains("<" + planted.fixture().getName() + ".")
                    .contains(planted.dependency()));
  }

  /** The control: a class of the subject that reads only bytes in memory passes rule F. */
  @Test
  void ruleF_passesAClassThatReadsOnlyBytes() {
    ruleF().check(new ClassFileImporter().importClasses(PlantedFileReads.ReadsBytes.class));
  }

  /**
   * Rule F checks at least one class: an import with no class of its subject fails it (ArchUnit
   * 1.5.1 throws from the evaluation, observed 2026-11-05).
   */
  @Test
  void ruleF_failsWhenItChecksNoClass() {
    JavaClasses noSubject = new ClassFileImporter().importClasses(String.class);

    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> ruleF().check(noSubject))
        .withMessageContaining("failed to check any classes");
  }

  @Test
  void exportedPackagesAreReadFromTheEngineJars() throws IOException {
    assertThat(engineModuleNames())
        .as("module-info.class of the engine modules on the class path")
        .contains("io.flottila.mapping.api", "io.flottila.mapping.xdm");
  }

  private static boolean isServicePackage(String packageName) {
    return packageName.equals(SERVICE_PACKAGE) || packageName.startsWith(SERVICE_PACKAGE + ".");
  }

  private static JavaClasses importedClasses() {
    return new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("io.flottila.mapping");
  }

  private static Set<String> exportedEnginePackages() throws IOException {
    Set<String> exported = new HashSet<>();
    for (ModuleDescriptor descriptor : engineDescriptors()) {
      for (ModuleDescriptor.Exports exports : descriptor.exports()) {
        if (!exports.isQualified()) {
          exported.add(exports.source());
        }
      }
    }
    return exported;
  }

  private static List<String> engineModuleNames() throws IOException {
    return engineDescriptors().stream().map(ModuleDescriptor::name).toList();
  }

  private static List<ModuleDescriptor> engineDescriptors() throws IOException {
    List<ModuleDescriptor> descriptors = new ArrayList<>();
    var urls = ArchitectureTest.class.getClassLoader().getResources("module-info.class");
    while (urls.hasMoreElements()) {
      URL url = urls.nextElement();
      try (InputStream in = url.openStream()) {
        ModuleDescriptor descriptor = ModuleDescriptor.read(in);
        if (descriptor.name().startsWith(ENGINE_MODULE_PREFIX)) {
          descriptors.add(descriptor);
        }
      }
    }
    return descriptors;
  }
}
