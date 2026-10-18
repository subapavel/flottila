package io.flottila.archtest;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The module boundaries of CLAUDE.md and ARCHITECTURE §12, as ArchUnit rules. Shared by
 * {@link ModuleBoundaryTest} (the production tree passes) and {@link ModuleBoundaryViolationTest}
 * (each rule fails on its deliberate violation). Changing a rule is an architecture change:
 * finding first (CLAUDE.md).
 */
final class Rules {

    private Rules() {}

    static final String API = "io.flottila.api..";
    static final String CORE = "io.flottila.core..";
    static final String RUNTIME = "io.flottila.runtime..";
    static final String COMPONENTS = "io.flottila.components..";
    static final String STEPS = "io.flottila.steps..";
    static final String TESTKIT = "io.flottila.testkit..";
    static final String CLI = "io.flottila.cli..";

    /**
     * The whole Vert.x surface api and core may touch (D-020): the four types CLAUDE.md names
     * plus the chosen subset of the Vert.x types their signatures expose (javap on vertx-core
     * 5.1.10; Context, Expectation, JsonObject/JsonArray deliberately left out).
     */
    static final Set<String> VERTX_ALLOWLIST = Set.of(
            "io.vertx.core.Future", "io.vertx.core.CompositeFuture", "io.vertx.core.Promise",
            "io.vertx.core.Completable", "io.vertx.core.AsyncResult", "io.vertx.core.Handler",
            "io.vertx.core.buffer.Buffer",
            "io.vertx.core.streams.ReadStream", "io.vertx.core.streams.WriteStream",
            "io.vertx.core.streams.StreamBase", "io.vertx.core.streams.Pipe");

    /** Client libraries of the five stores; only runtime may depend on them (CLAUDE.md). */
    static final String[] STORE_CLIENT_PACKAGES = {
            "io.vertx.pgclient..", "io.vertx.sqlclient..", "io.vertx.jdbcclient..", "org.postgresql..",
            "org.flywaydb..", "com.zaxxer.hikari..",
            "io.vertx.kafka..", "org.apache.kafka..", "io.confluent..",
            "software.amazon.awssdk..", "com.amazonaws..", "io.minio..",
            "io.vertx.redis..", "io.lettuce..", "redis.clients..",
            "com.clickhouse.."};

    /**
     * The JDK = every class the runtime image provides ({@code jrt:} source), minus JDBC:
     * java.sql / javax.sql are a JDK-only route to Postgres, which only runtime may take.
     */
    private static final DescribedPredicate<JavaClass> JDK = describe("the JDK (jrt:, without java.sql/javax.sql)", c -> {
        String p = c.getPackageName();
        boolean fromRuntimeImage = c.getSource().map(s -> "jrt".equals(s.getUri().getScheme())).orElse(false);
        return fromRuntimeImage && !p.startsWith("java.sql") && !p.startsWith("javax.sql");
    });

    private static final DescribedPredicate<JavaClass> VERTX_ALLOWED =
            describe("the allowed Vert.x types " + VERTX_ALLOWLIST, c -> VERTX_ALLOWLIST.contains(c.getName()));

    static final ArchRule API_DEPENDS_ON_NO_OTHER_MODULE = noClasses().that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, COMPONENTS, STEPS, TESTKIT, CLI)
            .because("api is the bottom of the dependency direction api <- core <- runtime");

    static final ArchRule API_SURFACE = classes().that().resideInAPackage(API)
            .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API)).or(VERTX_ALLOWED))
            .because("api uses the JDK plus Vert.x Future/Buffer/ReadStream/WriteStream and nothing else (CLAUDE.md, ARCHITECTURE §12)");

    static final ArchRule CORE_DEPENDS_ON_API_ONLY = noClasses().that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS, TESTKIT, CLI)
            .because("core depends on api only (ARCHITECTURE §12)");

    static final ArchRule CORE_SURFACE = classes().that().resideInAPackage(CORE)
            .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API, CORE)).or(VERTX_ALLOWED))
            .because("core uses the JDK, api and the same Vert.x allowlist as api (CLAUDE.md)");

    static final ArchRule COMPONENTS_DEPEND_ON_API_ONLY = noClasses().that().resideInAPackage(COMPONENTS)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, TESTKIT, CLI, STEPS)
            .because("components depend on api only (CLAUDE.md)");

    static final ArchRule STEPS_DEPEND_ON_API_ONLY = noClasses().that().resideInAPackage(STEPS)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, TESTKIT, CLI, COMPONENTS)
            .because("steps depend on api only (CLAUDE.md)");

    static final ArchRule TESTKIT_DEPENDS_ON_CORE_AND_API = noClasses().that().resideInAPackage(TESTKIT)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS, CLI)
            .because("testkit depends on core (and api) only (ARCHITECTURE §12)");

    static final ArchRule CLI_DEPENDS_ON_API_AND_TESTKIT = noClasses().that().resideInAPackage(CLI)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS)
            .because("cli depends on api and testkit only (ARCHITECTURE §12; core is reachable through testkit, D-021)");

    static final ArchRule ONLY_RUNTIME_TOUCHES_STORE_CLIENTS = noClasses().that().resideInAPackage("io.flottila..")
            .and().resideOutsideOfPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAnyPackage(STORE_CLIENT_PACKAGES)
            .because("only runtime touches the Postgres, Kafka, S3, Valkey and ClickHouse clients (CLAUDE.md)");

    static final ArchRule NO_CYCLES = slices().matching("io.flottila.(*)..").should().beFreeOfCycles()
            .because("a cycle between modules would break the dependency direction");
}
