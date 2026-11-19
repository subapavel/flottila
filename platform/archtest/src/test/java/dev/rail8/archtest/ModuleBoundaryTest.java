package dev.rail8.archtest;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production tree (every module on the archtest classpath, test classes excluded) passes every rule. */
class ModuleBoundaryTest {

    static JavaClasses tree;

    @BeforeAll
    static void importTree() {
        tree = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dev.rail8");
    }

    @Test void apiDependsOnNoOtherRail8Module() { Rules.API_DEPENDS_ON_NO_OTHER_MODULE.check(tree); }
    @Test void apiUsesOnlyTheJdkAndTheVertxAllowlist() { Rules.API_SURFACE.check(tree); }
    @Test void thePluginContractUsesOnlyTheJdkApiVertxAndTheAllowlist() { Rules.PLUGIN_SURFACE.check(tree); }
    @Test void noOtherApiPackageAndNoCoreClassNamesThePluginContract() { Rules.NOTHING_ELSE_NAMES_THE_PLUGIN_CONTRACT.check(tree); }

    /** The two plugin rules check the contract, not an empty set. */
    @Test void thePluginContractIsOnTheArchtestClasspath() {
        for (String c : List.of("dev.rail8.api.plugin.Plugin", "dev.rail8.api.plugin.ComponentContext", "dev.rail8.api.plugin.HttpIngress")) {
            assertTrue(tree.contain(c), "the production tree holds " + c);
        }
    }
    @Test void coreDependsOnApiOnly() { Rules.CORE_DEPENDS_ON_API_ONLY.check(tree); }
    @Test void coreUsesOnlyTheJdkApiAndTheVertxAllowlist() { Rules.CORE_SURFACE.check(tree); }
    @Test void componentsDependOnApiOnly() { Rules.COMPONENTS_DEPEND_ON_API_ONLY.check(tree); }
    @Test void stepsDependOnApiOnly() { Rules.STEPS_DEPEND_ON_API_ONLY.check(tree); }
    @Test void testkitDoesNotDependOnRuntimeComponentsStepsOrCli() { Rules.TESTKIT_DEPENDS_ON_CORE_AND_API.check(tree); }
    @Test void theSharedHttpPortDependsOnApiOnly() { Rules.INGRESS_DEPENDS_ON_API_ONLY.check(tree); }
    @Test void nothingDependsOnCli() { Rules.NOTHING_DEPENDS_ON_CLI.check(tree); }
    @Test void simDependsOnApiCoreTestkitOnly() { Rules.SIM_DEPENDS_ON_API_CORE_TESTKIT.check(tree); }
    @Test void nothingDependsOnSim() { Rules.NOTHING_DEPENDS_ON_SIM.check(tree); }

    /** The two sim rules check the harness, not an empty set: its src/main is on the archtest classpath (F-36, DoNotIncludeTests). */
    @Test void simIsOnTheArchtestClasspath() {
        for (String harness : List.of("dev.rail8.sim.Simulation", "dev.rail8.sim.World", "dev.rail8.sim.Verdict", "dev.rail8.sim.SimSeed")) {
            assertTrue(tree.contain(harness), "the production tree holds " + harness + " (rail8-sim is a test dependency of the archtest)");
        }
    }
    @Test void onlyTheMappingStepDependsOnTheMappingEngine() { Rules.ONLY_THE_MAPPING_STEP_DEPENDS_ON_THE_MAPPING_ENGINE.check(tree); }
    @Test void theMappingStepUsesOnlyTheEmbeddingApiAndTheStudio() {
        Rules.THE_MAPPING_STEP_USES_ONLY_THE_EMBEDDING_API_AND_THE_STUDIO.check(tree);
    }

    /** The two mapping rules check the step and the engine, not an empty set: both are on the archtest classpath. */
    @Test void theMappingStepAndTheEngineAreOnTheArchtestClasspath() {
        for (String c : List.of("dev.rail8.steps.mapping.MappingStepFactory", "dev.rail8.mapping.embed.Mapping", "dev.rail8.mapping.studio.Studio",
                "dev.rail8.mapping.api.EngineFacade")) {
            assertTrue(tree.contain(c), "the production tree holds " + c);
        }
    }
    @Test void onlyRuntimeTouchesStoreClients() { Rules.ONLY_RUNTIME_TOUCHES_STORE_CLIENTS.check(tree); }
    @Test void onlyRuntimeTouchesJGit() {
        assertTrue(tree.contain("dev.rail8.runtime.git.GitCache"), "the rule checks the git cache, not an empty set");
        Rules.ONLY_RUNTIME_TOUCHES_JGIT.check(tree);
    }
    @Test void rail8PackagesAreFreeOfCycles() { Rules.NO_CYCLES.check(tree); }
    @Test void noRuntimeClassNamesAComponentOrStep() { Rules.RUNTIME_NAMES_NO_COMPONENT_OR_STEP.check(tree); }
    @Test void theCliNamesNoComponentOrStepButTheMockAndTheHttpComponent() { Rules.CLI_NAMES_NO_COMPONENT_OR_STEP.check(tree); }
    @Test void noRuntimeClassNamesTheMock() { Rules.RUNTIME_NEVER_NAMES_THE_MOCK.check(tree); }
    @Test void onlyThePeerLinkUsesVertxNetTypes() { Rules.ONLY_THE_PEER_LINK_OPENS_RAW_TCP.check(tree); }

    /**
     * The two rules on naming a component or step check the node's entry point, the CLI and the seven modules that ship with the
     * platform, not an empty set: all are on the archtest classpath.
     */
    @Test void theEntryPointTheCliAndTheShippedModulesAreOnTheArchtestClasspath() {
        for (String c : List.of("dev.rail8.runtime.Main", "dev.rail8.runtime.plugin.Plugins", "dev.rail8.cli.Cli", "dev.rail8.ingress.SharedHttpPort",
                "dev.rail8.components.direct.DirectPlugin", "dev.rail8.components.http.HttpPlugin", "dev.rail8.components.sftp.SftpPlugin",
                "dev.rail8.steps.jq.JqPlugin", "dev.rail8.steps.mapping.MappingPlugin", "dev.rail8.steps.validate.XsdPlugin",
                "dev.rail8.steps.xslt.XsltPlugin")) {
            assertTrue(tree.contain(c), "the production tree holds " + c);
        }
    }
}
