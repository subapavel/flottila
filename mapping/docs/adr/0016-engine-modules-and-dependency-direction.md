# Milestone 1 has nine JPMS engine modules behind one facade, a Quarkus service and a separate `ui/`

Status: accepted (2026-10-30, grilling #13); `engine/oracle` added by ADR-0018; `engine/udf-api` and `engine/udf` added by ADR-0020; `engine/bytecode` (after milestone 1) and `bench/jmh` requires set by ADR-0021; ArchUnit rule F added by the spec-4 grilling (2026-11-05), lettered by #90

FOUNDING §11 sketches modules along the pipeline. ADR-0008 split structure import into two, and
ADR-0014 took the service off the module path. This ADR fixes the modules milestone 1 builds,
their names and the direction of their dependencies. Modules for the XPath and XSLT front ends,
the JAXP adapter and the bytecode back end are added by the specs that build them. The bytecode
module's row is fixed now (ADR-0021); its spec comes after milestone 1.

## Modules

| Directory | artifactId | JPMS module | `requires` |
| --- | --- | --- | --- |
| `engine/xdm` | `flottila-mapping-xdm` | `io.flottila.mapping.xdm` | — |
| `engine/structure` | `flottila-mapping-structure` | `io.flottila.mapping.structure` | — |
| `engine/structure-import` | `flottila-mapping-structure-import` | `io.flottila.mapping.structure.importer` | structure |
| `engine/ir` | `flottila-mapping-ir` | `io.flottila.mapping.ir` | xdm |
| `engine/serializer` | `flottila-mapping-serializer` | `io.flottila.mapping.serializer` | xdm |
| `engine/udf-api` | `flottila-mapping-udf-api` | `io.flottila.mapping.udf` | — (UDF authors compile against it, ADR-0020) |
| `engine/udf` | `flottila-mapping-udf` | `io.flottila.mapping.udf.compiler` | udf-api, `java.compiler` |
| `engine/runtime` | `flottila-mapping-runtime` | `io.flottila.mapping.runtime` | ir, xdm, serializer, udf-api |
| `engine/mapping` | `flottila-mapping-mapping` | `io.flottila.mapping.mapping` | ir, xdm, structure |
| `engine/xslt-export` | `flottila-mapping-xslt-export` | `io.flottila.mapping.xslt.export` | mapping, ir, structure |
| `engine/bytecode` (after milestone 1, ADR-0021) | `flottila-mapping-bytecode` | `io.flottila.mapping.bytecode` | ir, runtime, xdm; `api` adds it when the spec lands |
| `engine/api` | `flottila-mapping-api` | `io.flottila.mapping.api` | mapping, runtime, structure-import, xslt-export, udf (transitive where its types are exposed) |
| `engine/oracle` | `flottila-mapping-oracle` | none (tests only, class path; Saxon-HE and XMLUnit at test scope, ADR-0018) | api |
| `service` | `flottila-mapping-service` | none (class path) | api |
| `bench/jmh` | `flottila-mapping-bench` | none (class path), profile `bench` only | api, and the `corpus/bench/` mappings and generators (ADR-0021) |
| `ui/` | npm project | — | `contract/` files only |

- groupId `io.flottila.mapping`. artifactIds carry the `flottila-mapping-` prefix so the jars do
  not collide inside a host application. A module's root package is its JPMS name. Packages it
  does not export end in `.internal`. `import` is a Java keyword, which is why the module and
  package are named `structure.importer`.
- `ir` holds the whole sealed `Expr` hierarchy and `xdm` the whole sealed atomic hierarchy. In
  a named module, `permits` cannot reach into another module.
- `mapping` does not depend on `runtime`. The facade `engine/api` joins them. It runs the chain
  load → three-stage validation (ADR-0011) → lowering → closure compile in `runtime` (ADR-0021) → interpreter with a tracer → output or
  runtime error with the trace, and it applies the test timeout (ADR-0014; `testRun`, since #54). The service
  translates between the facade and the wire DTOs and holds no engine logic. The facade is not
  the embedding API for Flottila, which is still out of milestone 1 (ADR-0014). It carries no
  compatibility promise.
- `contract/` at the root holds the hand-written `mapping-model.schema.json` and
  `function-catalogue.schema.json` and the generated, committed `openapi.json`. They sit in one
  directory because `openapi.json` refers to the model with the relative `$ref:
  ./mapping-model.schema.json` (ADR-0015). `engine/mapping` packs the model schema into its jar from
  there, because it validates against it. The catalogue schema is checked by a test only (ADR-0026),
  so it is not packed. `ui` `gen:types` reads them without Java.

## Enforcement

- **Between engine modules**, JPMS enforces the direction. javac refuses a module that is not
  in `requires`, so no ArchUnit rule repeats it.
- **The service** is the unnamed module, so ArchUnit enforces three rules in
  `service/src/test/java/io/flottila/mapping/service/ArchitectureTest.java`, which runs in every
  build of the service:
  - A: service classes use only engine packages that are exported. The exported packages are
    read from the engine jars' `module-info.class`, not from a hand-written list;
  - B: no engine class depends on `io.quarkus..`, `jakarta..`, `com.fasterxml..` or
    `io.flottila.mapping.service..`;
  - C: every service class lives in `io.flottila.mapping.service..`, so B can see it.
  - D and E: added by ADR-0024 and #54 (`ruleD_serviceReachesTheEngineOnlyThroughTheFacade`,
    `ruleE_serviceBuildsNoMappingModel`).
  - F (spec-4 grilling, 2026-11-05; #90): no class in `io.flottila.mapping.structure.importer..`
    depends on `java.nio.file..`, `java.io.File*`, `java.io.RandomAccessFile` or
    `java.nio.channels.FileChannel`. It reads every file through the reference resolver
    (ADR-0014). Planted fixtures prove the rule fails. GATE check 8 still names A–C, and the
    `backend` job holds D–F with them.
- **Artefacts**: maven-enforcer `bannedDependencies` bans `io.quarkus*`, `jakarta.*` and
  `com.fasterxml*` in `engine/pom.xml`, `org.openjdk.jmh` everywhere except `bench/jmh`, and
  `net.sf.saxon` at every scope except in `engine/oracle`, which switches the `enforce-no-saxon`
  execution off (`<phase>none</phase>`, #27). Maven cannot stop a child POM from doing the same,
  so reviewers check every diff that names `enforce-no-saxon`.

## Considered options

- **The FOUNDING sketch as is** (`xpath`, `xslt-compiler`, `jaxp-adapter` now): rejected. Empty
  modules would wait a milestone for code.
- **`serializer` inside `runtime`**: rejected. The XSLT back ends will reuse it.
- **The service wiring compile and test itself**: rejected. The logic would sit on the class
  path, testable only through `@QuarkusTest`, and Flottila could not reuse it.
- **A separate `architecture-tests` module**: rejected. It works, but `-pl service -am` would
  no longer run the rules (observed).
- **Unprefixed artifactIds** (`xdm`): rejected. A fat jar would get an `xdm-0.1.0.jar` with no
  owner.

## Consequences

- Observed on 2026-10-30 in a scratch reactor (`xdm`, `structure`, `structure-import`,
  `service`), not committed:
  - rule A failed on a planted import of an `.internal` package and passed once it was removed;
  - rule B failed for a service class placed outside `..service..`;
  - ArchUnit 1.5.1 reads Java 25 class files.
- Rule B cannot fail for a real engine module unless an automatic-module dependency bundles
  shaded `jakarta`/`com.fasterxml` classes. It stays as that guard.
- `CODING_STANDARDS.md` §Module boundaries now points here.
