package io.flottila.archtest;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The production tree (every module on the archtest classpath, test classes excluded) passes every rule. */
class ModuleBoundaryTest {

    static JavaClasses tree;

    @BeforeAll
    static void importTree() {
        tree = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.flottila");
    }

    @Test void apiDependsOnNoOtherFlottilaModule() { Rules.API_DEPENDS_ON_NO_OTHER_MODULE.check(tree); }
    @Test void apiUsesOnlyTheJdkAndTheVertxAllowlist() { Rules.API_SURFACE.check(tree); }
    @Test void coreDependsOnApiOnly() { Rules.CORE_DEPENDS_ON_API_ONLY.check(tree); }
    @Test void coreUsesOnlyTheJdkApiAndTheVertxAllowlist() { Rules.CORE_SURFACE.check(tree); }
    @Test void componentsDependOnApiOnly() { Rules.COMPONENTS_DEPEND_ON_API_ONLY.check(tree); }
    @Test void stepsDependOnApiOnly() { Rules.STEPS_DEPEND_ON_API_ONLY.check(tree); }
    @Test void testkitDoesNotDependOnRuntimeComponentsStepsOrCli() { Rules.TESTKIT_DEPENDS_ON_CORE_AND_API.check(tree); }
    @Test void cliDependsOnApiAndTestkitOnly() { Rules.CLI_DEPENDS_ON_API_AND_TESTKIT.check(tree); }
    @Test void onlyRuntimeTouchesStoreClients() { Rules.ONLY_RUNTIME_TOUCHES_STORE_CLIENTS.check(tree); }
    @Test void flottilaPackagesAreFreeOfCycles() { Rules.NO_CYCLES.check(tree); }
}
