package dev.rail8.archtest;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
    @Test void coreDependsOnApiOnly() { Rules.CORE_DEPENDS_ON_API_ONLY.check(tree); }
    @Test void coreUsesOnlyTheJdkApiAndTheVertxAllowlist() { Rules.CORE_SURFACE.check(tree); }
    @Test void componentsDependOnApiOnly() { Rules.COMPONENTS_DEPEND_ON_API_ONLY.check(tree); }
    @Test void stepsDependOnApiOnly() { Rules.STEPS_DEPEND_ON_API_ONLY.check(tree); }
    @Test void testkitDoesNotDependOnRuntimeComponentsStepsOrCli() { Rules.TESTKIT_DEPENDS_ON_CORE_AND_API.check(tree); }
    @Test void nothingDependsOnCli() { Rules.NOTHING_DEPENDS_ON_CLI.check(tree); }
    @Test void simDependsOnApiCoreTestkitOnly() { Rules.SIM_DEPENDS_ON_API_CORE_TESTKIT.check(tree); }
    @Test void nothingDependsOnSim() { Rules.NOTHING_DEPENDS_ON_SIM.check(tree); }

    /** The two sim rules check the harness, not an empty set: its src/main is on the archtest classpath (F-36, DoNotIncludeTests). */
    @Test void simIsOnTheArchtestClasspath() {
        for (String harness : List.of("dev.rail8.sim.Simulation", "dev.rail8.sim.World", "dev.rail8.sim.Verdict", "dev.rail8.sim.SimSeed")) {
            assertTrue(tree.contain(harness), "the production tree holds " + harness + " (rail8-sim is a test dependency of the archtest)");
        }
    }
    @Test void onlyRuntimeTouchesStoreClients() { Rules.ONLY_RUNTIME_TOUCHES_STORE_CLIENTS.check(tree); }
    @Test void rail8PackagesAreFreeOfCycles() { Rules.NO_CYCLES.check(tree); }
    @Test void onlyMainWiresComponentsAndStepsInRuntime() { Rules.ONLY_MAIN_WIRES_COMPONENTS_AND_STEPS.check(tree); }
    @Test void noRuntimeClassNamesTheMock() { Rules.RUNTIME_NEVER_NAMES_THE_MOCK.check(tree); }
    @Test void onlyThePeerLinkUsesVertxNetTypes() { Rules.ONLY_THE_PEER_LINK_OPENS_RAW_TCP.check(tree); }

    /**
     * The exemption of {@link Rules#ONLY_MAIN_WIRES_COMPONENTS_AND_STEPS} is used, not idle: the production Main exists and
     * wires the shipped http component, the xslt steps with the xpath language and the xsd step (F-4).
     */
    @Test void mainIsTheWiringPointOfHttpXsltAndXsd() {
        assertTrue(tree.contain(Rules.MAIN), "runtime has its wiring point " + Rules.MAIN);
        Set<String> wired = new TreeSet<>();
        for (JavaClass c : tree) {
            if (!Rules.THE_WIRING_POINT.test(c)) continue;
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                String target = d.getTargetClass().getPackageName();
                if (target.startsWith("dev.rail8.components.") || target.startsWith("dev.rail8.steps.")) wired.add(target);
            }
        }
        assertTrue(wired.containsAll(Set.of("dev.rail8.components.http", "dev.rail8.steps.xslt", "dev.rail8.steps.validate")),
                "Main wires http, xslt/xpath and xsd; it names: " + wired);
    }
}
