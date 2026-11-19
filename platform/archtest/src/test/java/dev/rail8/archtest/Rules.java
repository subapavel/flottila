package dev.rail8.archtest;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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

    static final String API = "dev.rail8.api..";
    static final String CORE = "dev.rail8.core..";
    static final String RUNTIME = "dev.rail8.runtime..";
    static final String COMPONENTS = "dev.rail8.components..";
    static final String STEPS = "dev.rail8.steps..";
    static final String TESTKIT = "dev.rail8.testkit..";
    static final String CLI = "dev.rail8.cli..";
    /** The node's shared HTTP port. */
    static final String INGRESS = "dev.rail8.ingress..";
    static final String SIM = "dev.rail8.sim..";
    /** The mapping engine under {@code mapping/}, every package of it. */
    static final String MAPPING_ENGINE = "dev.rail8.mapping..";
    /** The engine's embedding API: the one package of the engine a platform module may use. */
    static final String MAPPING_EMBED = "dev.rail8.mapping.embed";
    static final String MAPPING_STEP = "dev.rail8.steps.mapping..";

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

    /** The plugin contract: the one api package that may name {@link #VERTX} and {@link #HTTP_REQUEST}. */
    static final String PLUGIN_CONTRACT = "dev.rail8.api.plugin..";

    /** The node's Vert.x, which the plugin contract hands to a plugin's components. */
    static final String VERTX = "io.vertx.core.Vertx";

    /** A request on the node's HTTP port, which the plugin contract hands to the consumer that mounted its path. */
    static final String HTTP_REQUEST = "io.vertx.core.http.HttpServerRequest";

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
        JavaClass base = c.isArray() ? c.getBaseComponentType() : c;   // byte[] etc. carry no source of their own
        if (base.isPrimitive()) return true;
        String p = base.getPackageName();
        boolean fromRuntimeImage = base.getSource().map(s -> "jrt".equals(s.getUri().getScheme())).orElse(false);
        return fromRuntimeImage && !p.startsWith("java.sql") && !p.startsWith("javax.sql");
    });

    private static final DescribedPredicate<JavaClass> VERTX_ALLOWED =
            describe("the allowed Vert.x types " + VERTX_ALLOWLIST, c -> VERTX_ALLOWLIST.contains(c.getName()));

    private static final DescribedPredicate<JavaClass> THE_VERTX = describe(VERTX + " and " + HTTP_REQUEST,
            c -> VERTX.equals(c.getName()) || HTTP_REQUEST.equals(c.getName()));

    static final ArchRule API_DEPENDS_ON_NO_OTHER_MODULE = noClasses().that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, COMPONENTS, STEPS, TESTKIT, CLI, INGRESS)
            .because("api is the bottom of the dependency direction api <- core <- runtime");

    static final ArchRule API_SURFACE = classes().that().resideInAPackage(API).and().resideOutsideOfPackage(PLUGIN_CONTRACT)
            .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API)).or(VERTX_ALLOWED))
            .because("api uses the JDK plus Vert.x Future/Buffer/ReadStream/WriteStream and nothing else (CLAUDE.md, ARCHITECTURE §12)");

    static final ArchRule PLUGIN_SURFACE = classes().that().resideInAPackage(PLUGIN_CONTRACT)
            .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API)).or(VERTX_ALLOWED).or(THE_VERTX))
            .because("a plugin's components run on the node's Vert.x and take requests on its HTTP port: " + VERTX + " and " + HTTP_REQUEST
                    + " are the types the plugin contract adds to api's surface");

    static final ArchRule NOTHING_ELSE_NAMES_THE_PLUGIN_CONTRACT = noClasses().that().resideInAnyPackage(API, CORE)
            .and().resideOutsideOfPackage(PLUGIN_CONTRACT)
            .should().dependOnClassesThat().resideInAPackage(PLUGIN_CONTRACT)
            .because("the rest of api and core never reach " + VERTX + ", not even through the plugin contract");

    static final ArchRule CORE_DEPENDS_ON_API_ONLY = noClasses().that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS, TESTKIT, CLI, INGRESS)
            .because("core depends on api only (ARCHITECTURE §12)");

    static final ArchRule CORE_SURFACE = classes().that().resideInAPackage(CORE)
            .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API, CORE)).or(VERTX_ALLOWED))
            .because("core uses the JDK, api and the same Vert.x allowlist as api (CLAUDE.md)");

    static final ArchRule COMPONENTS_DEPEND_ON_API_ONLY = noClasses().that().resideInAPackage(COMPONENTS)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, TESTKIT, CLI, STEPS, INGRESS)
            .because("components depend on api only (CLAUDE.md)");

    static final ArchRule STEPS_DEPEND_ON_API_ONLY = noClasses().that().resideInAPackage(STEPS)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, TESTKIT, CLI, COMPONENTS, INGRESS)
            .because("steps depend on api only (CLAUDE.md)");

    static final ArchRule TESTKIT_DEPENDS_ON_CORE_AND_API = noClasses().that().resideInAPackage(TESTKIT)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS, CLI, INGRESS)
            .because("testkit depends on core (and api) only (ARCHITECTURE §12)");

    static final ArchRule INGRESS_DEPENDS_ON_API_ONLY = noClasses().that().resideInAPackage(INGRESS)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, COMPONENTS, STEPS, TESTKIT, CLI, SIM)
            .because("the shared HTTP port is api only: the node runs it, and so do the tests of a component, a shipped one or a plugin's");

    static final ArchRule NOTHING_DEPENDS_ON_CLI = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(CLI)
            .should().dependOnClassesThat().resideInAPackage(CLI)
            .because("cli is the outermost module: it may depend on every module and nothing depends on it (F-16, D-079)");

    static final ArchRule SIM_DEPENDS_ON_API_CORE_TESTKIT = noClasses().that().resideInAPackage(SIM)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS, CLI)
            .because("the simulation runs the real core over api, core and testkit only (F-36, D-143)");

    static final ArchRule NOTHING_DEPENDS_ON_SIM = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(SIM)
            .should().dependOnClassesThat().resideInAPackage(SIM)
            .because("rail8-sim is test infrastructure at the top beside cli: never shipped, nothing depends on it (F-36, D-143)");

    static final ArchRule ONLY_THE_MAPPING_STEP_DEPENDS_ON_THE_MAPPING_ENGINE = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackages(MAPPING_ENGINE, MAPPING_STEP)
            .should().dependOnClassesThat().resideInAPackage(MAPPING_ENGINE)
            .because("the mapping step is the only platform module that depends on the mapping engine");

    static final ArchRule THE_MAPPING_STEP_USES_ONLY_THE_EMBEDDING_API = noClasses().that().resideInAPackage(MAPPING_STEP)
            .should().dependOnClassesThat(resideInAPackage(MAPPING_ENGINE).and(DescribedPredicate.not(resideInAPackage(MAPPING_EMBED))))
            .because("the mapping step reaches the engine through its embedding API, " + MAPPING_EMBED + ", and nothing else of it");

    static final ArchRule ONLY_RUNTIME_TOUCHES_STORE_CLIENTS = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAnyPackage(STORE_CLIENT_PACKAGES)
            .because("only runtime touches the Postgres, Kafka, S3, Valkey and ClickHouse clients (CLAUDE.md)");

    static final ArchRule NO_CYCLES = slices().matching("dev.rail8.(*)..").should().beFreeOfCycles()
            .because("a cycle between modules would break the dependency direction");

    static final ArchRule RUNTIME_NAMES_NO_COMPONENT_OR_STEP = noClasses().that().resideInAPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAnyPackage(COMPONENTS, STEPS)
            .because("the node finds its components and steps, the shipped ones among them, as modules on its class path and "
                    + "reaches them through the SPI; no runtime class names one");

    /** The platform's mock, which {@code rail8 test} puts in place of every partner. */
    static final String MOCK = "dev.rail8.components.mock..";

    /** The http component, whose answer to a delivery {@code rail8 test} compares with a case's expected status. */
    static final String HTTP_COMPONENT = "dev.rail8.components.http.HttpComponent";

    private static final DescribedPredicate<JavaClass> A_MODULE_THE_CLI_MAY_NAME = describe("the mock or " + HTTP_COMPONENT,
            c -> resideInAPackage(MOCK).test(c) || c.getName().equals(HTTP_COMPONENT));

    static final ArchRule CLI_NAMES_NO_COMPONENT_OR_STEP = noClasses().that().resideInAPackage(CLI)
            .should().dependOnClassesThat(resideInAnyPackage(COMPONENTS, STEPS).and(DescribedPredicate.not(A_MODULE_THE_CLI_MAY_NAME)))
            .because("the CLI finds a node's components and steps as the node does, as modules on its class path; it names only "
                    + "the mock it tests with and the http component's answer to a delivery");

    static final ArchRule RUNTIME_NEVER_NAMES_THE_MOCK = noClasses().that().resideInAPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAPackage("dev.rail8.components.mock..")
            .because("a deployed node refuses a mock binding (ARCHITECTURE §11); no runtime class, Main included, names one (F-4)");

    /** The one package that may open a raw TCP socket with Vert.x's {@code io.vertx.core.net.Net*} types (F-35, CORE-5a). */
    static final String PEER_LINK = "dev.rail8.runtime.journal.peer..";

    /**
     * {@code io.vertx.core.net.Net*} (NetServer, NetClient, NetSocket, their options): the classes the peer link opens its sockets
     * with. When the rule was added (CORE-5a, grep of every src/main) no other production class used one — the admin listener
     * and the http doors are Vert.x HTTP servers, the store clients their own libraries — so the peer link is the only user
     * listed; a new raw-socket user is an architecture change: finding first.
     */
    static final DescribedPredicate<JavaClass> VERTX_NET = describe("io.vertx.core.net.Net*",
            c -> c.getPackageName().equals("io.vertx.core.net") && c.getSimpleName().startsWith("Net"));

    static final ArchRule ONLY_THE_PEER_LINK_OPENS_RAW_TCP = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(PEER_LINK)
            .should().dependOnClassesThat(VERTX_NET)
            .because("nothing but the Component SPI and the runtime's own listeners opens a socket; the node-to-node journal link is "
                    + "the one raw TCP user (F-35, CORE-5a)");
}
