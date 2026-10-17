package io.flottila.archtest;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * P0-01 smoke rules over the new module tree ({@code io.flottila..} on the archtest classpath,
 * which never includes {@code flottila-m1}). ArchUnit fails a rule whose subject set is empty,
 * so these tests stay red until the modules exist and carry classes. P0-03 adds the full
 * boundary rules of CLAUDE.md next to them.
 */
class ModuleBoundarySmokeTest {

    static JavaClasses classes;

    @BeforeAll
    static void importTree() {
        classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.flottila");
    }

    @Test
    void apiDependsOnNoOtherFlottilaModule() {
        ArchRule rule = noClasses().that().resideInAPackage("io.flottila.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.flottila.core..", "io.flottila.runtime..", "io.flottila.components..",
                        "io.flottila.steps..", "io.flottila.testkit..", "io.flottila.cli..")
                .because("api is the bottom of the dependency direction api <- core <- runtime");
        rule.check(classes);
    }

    @Test
    void flottilaPackagesAreFreeOfCycles() {
        ArchRule rule = slices().matching("io.flottila.(*)..").should().beFreeOfCycles()
                .because("a cycle between modules would break the ArchUnit dependency direction");
        rule.check(classes);
    }
}
