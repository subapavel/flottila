package io.flottila.archtest;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rule must fail on the deliberate violations under {@code io.flottila.*.violation} and
 * {@code io.flottila.cycle*} (test-scoped fixtures of this module) and name the offending class.
 * A rule that passes on its own violation is no rule.
 */
class ModuleBoundaryViolationTest {

    static JavaClasses fixtures;

    @BeforeAll
    static void importFixtures() {
        fixtures = new ClassFileImporter()
                .withImportOption(new ImportOption.OnlyIncludeTests())
                .importPackages("io.flottila");
    }

    private static void assertFails(ArchRule rule, String offender) {
        AssertionError e = assertThrows(AssertionError.class, () -> rule.check(fixtures), rule.getDescription());
        assertTrue(e.getMessage().contains(offender),
                () -> "violation report should name " + offender + " but was:\n" + e.getMessage());
    }

    @Test void apiDependingOnCoreIsCaught() { assertFails(Rules.API_DEPENDS_ON_NO_OTHER_MODULE, "ApiDependsOnCore"); }
    @Test void apiUsingVertxBeyondTheAllowlistIsCaught() { assertFails(Rules.API_SURFACE, "ApiUsesVertxBeyondTheAllowlist"); }

    @Test void apiUsingEveryAllowlistedVertxTypeIsAllowed() {
        JavaClasses allowedOnly = new ClassFileImporter()
                .withImportOption(new ImportOption.OnlyIncludeTests())
                .importClasses(io.flottila.api.violation.ApiUsesAllowedVertxTypes.class);
        assertDoesNotThrow(() -> Rules.API_SURFACE.check(allowedOnly));
    }

    @Test void coreUsingJdbcIsCaught() { assertFails(Rules.CORE_SURFACE, "CoreUsesJdbc"); }
    @Test void stepDependingOnCoreIsCaught() { assertFails(Rules.STEPS_DEPEND_ON_API_ONLY, "StepDependsOnCore"); }

    @Test void coreDependingOnRuntimeIsCaught() { assertFails(Rules.CORE_DEPENDS_ON_API_ONLY, "CoreDependsOnRuntime"); }
    @Test void coreUsingAPostgresClientIsCaught() { assertFails(Rules.CORE_SURFACE, "CoreUsesAPostgresClient"); }
    @Test void componentDependingOnCoreIsCaught() { assertFails(Rules.COMPONENTS_DEPEND_ON_API_ONLY, "ComponentDependsOnCore"); }
    @Test void stepTouchingAKafkaClientIsCaught() { assertFails(Rules.ONLY_RUNTIME_TOUCHES_STORE_CLIENTS, "StepUsesAKafkaClient"); }
    @Test void coreTouchingAPostgresClientIsCaughtByTheClientRuleToo() { assertFails(Rules.ONLY_RUNTIME_TOUCHES_STORE_CLIENTS, "CoreUsesAPostgresClient"); }
    @Test void runtimeTouchingAPostgresClientIsAllowed() {
        AssertionError e = assertThrows(AssertionError.class, () -> Rules.ONLY_RUNTIME_TOUCHES_STORE_CLIENTS.check(fixtures));
        assertFalse(e.getMessage().contains("RuntimeFixture"), () -> "runtime may use the client, but the report says:\n" + e.getMessage());
    }
    @Test void testkitDependingOnRuntimeIsCaught() { assertFails(Rules.TESTKIT_DEPENDS_ON_CORE_AND_API, "TestkitDependsOnRuntime"); }
    @Test void cliDependingOnRuntimeIsCaught() { assertFails(Rules.CLI_DEPENDS_ON_API_AND_TESTKIT, "CliDependsOnRuntime"); }
    @Test void packageCycleIsCaught() { assertFails(Rules.NO_CYCLES, "cyclea"); }
}
