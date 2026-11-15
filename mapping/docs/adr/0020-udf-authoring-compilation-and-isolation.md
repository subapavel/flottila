# UDFs are static Java methods over a small API, compiled in-process from the workspace and guarded in-process

Status: accepted (2026-10-30, grilling #16)

FOUNDING §6 says UDFs are Java, use the extension-function mechanism (annotation +
`MethodHandle`) and declare their kind in the signature. ADR-0010 keeps the declaration in the
model's `udfs` and the Java source in a referenced file, and ADR-0006 lifts single-value UDFs.
This ADR fixes how a UDF is written, compiled, loaded, verified and isolated in milestone 1.
The Security Manager is gone from the JDK, so no in-process sandbox exists. The editor's test
run has a 10 s timeout (ADR-0014), and a UDF can loop forever.

## Writing a UDF

A UDF is a `public static` method annotated `@Udf` in a `.java` file of the workspace. It is
compiled against `engine/udf-api` (`io.flottila.mapping.udf`), which has no dependencies, so an
author can put it on an IDE class path. The execution kind follows from the parameter types:

| Kind | Signature |
| --- | --- |
| single value | `@Udf static String f(String a, String b, @Parameter("sep") String sep)` |
| context | `@Udf static void f(Context a, Context b, @Parameter… , ContextOut out)` |
| queue | `@Udf static void f(Queue a, …, ContextOut out)` |

- A single-value UDF is lifted (ADR-0006). Its arguments are never `null` and never SUPPRESS.
  A `null` result is a runtime error.
- `Context` is a read-only view of one context: `size()`, `value(i)`, `isSuppressed(i)`.
  `Queue` gives `contexts()` and `context(i)`. `ContextOut` has `add(value)`, `addSuppress()`
  and `contextChange()`. The runtime implements them over queue blocks. This is the adapter at
  the UDF boundary that ADR-0004 allows. SAP's flat `ResultList` is not used.
- `@Parameter` arguments are `String` or `int`, as ADR-0010's `params` declare.
- `@Udf(deterministic = true)` lets the optimiser fold calls over literals (ADR-0006). The flag
  lives only in the annotation, not in the model, because it is a property of the code.
- **State**: a UDF may take `MappingRun run` as its last parameter.
  - `run.state()` is a mutable map from `String` to `Object`. It lives for one transformation
    and is shared by every UDF of the mapping. It replaces PO's `Container` and
    `GlobalContainer`.
  - It starts empty at each run and is dropped at the end. Milestone 1 has no `@Init` or
    `@Cleanup`, so a UDF initialises lazily with `computeIfAbsent`.
  - A UDF that takes `MappingRun` is non-deterministic, whatever its annotation says.
  - Mutable static fields are refused (`udf-mutable-static-state`): a compiled mapping runs
    many times at once.
- **Logging**: `run.log(message)` replaces PO's `getTrace()`. In a test run the message goes into
  the trace under the box (`messages`, ADR-0015), and the display queue shows it. Otherwise it
  goes to the engine's `System.Logger`.
- An exception from a UDF body is the runtime error `flottila:UDF-ERROR`, with the box's
  `AuthoringId`. Its message names the exception, its text and the first frame in the UDF source
  (file:line). The trace up to the error is returned, as with `po:MINOCCURS`.

## Compiling

- `engine/udf` (`io.flottila.mapping.udf.compiler`, `requires java.compiler`) runs javac in the
  process: `ToolProvider.getSystemJavaCompiler()` with an in-memory file manager and
  `--release 25 -Xlint:all -Werror -parameters`.
- The class path is the `udf-api` jar and nothing else, found through the `CodeSource` of its
  classes. A UDF therefore sees neither the engine nor Quarkus. `java.class.path` is not used,
  because the Quarkus fast-jar's class path has only `quarkus-run.jar` (observed).
- A **UDF library** is the unit: the referenced `.java` files that share a source root (from their
  `package`), compiled by one javac call. Helper classes are nested or in other referenced files.
- Compiling happens on every mapping compile: the compile endpoint, the test run, the scan and
  deploy. A bounded in-memory LRU cache keyed by the SHA-256 of the source bytes, the `udf-api`
  version and the javac options keeps the editor's debounced compile cheap.
- javac errors are diagnostics `udf-compile-error` with a `{file, line, column}` location.
- Sources resolve through `ReferenceResolver` like any reference (ADR-0014). A package reference
  is a design-time error in milestone 1, as for schemas.

## Declaration and the editor

- The model's `udfs` entry stays the declaration (ADR-0010). Compile derives the kind, the inputs
  (parameter names, hence `-parameters`) and the `@Parameter`s from the method. Any difference is
  `udf-declaration-mismatch`.
- `POST /api/mappings/{id}/udfs/scan` takes `{source}` (a reference relative to the mapping) and
  returns the declarations of every `@Udf` method in that file, or the compile diagnostics. The
  editor adds the chosen ones to the working copy and offers "update from source" on a
  mismatch. It is stateless, like compile (ADR-0015).

## Loading, isolation and runaway UDFs

Everything runs in the engine's process. There are three layers:

1. **A class loader per UDF library.** Its parent is the loader of `udf-api`. Two versions of one
   class (two libraries, ADR-0010) live side by side. A library is unloaded when nothing holds
   its handles.
2. **An API allow-list**, checked at compile by walking the instructions of every compiled class
   (including `invokedynamic` bootstrap arguments) with the Class-File API. Allowed:
   - `java.lang`, without `System.exit`, `Runtime`, `Thread`, `ProcessBuilder`, `ClassLoader`,
     `Class.forName` and reflection;
   - `java.util`, `java.util.regex`, `java.math`, `java.time`, `java.text`;
   - `udf-api`.

   Anything else is `udf-forbidden-api` at the call's line. A constant-pool scan is not enough,
   because string concatenation puts `MethodHandles` entries into every class (observed).
3. **An interruption guard.** After the allow-list check, the Class-File API inserts a call to
   the guard at every method entry and every backward branch. The guard throws when the thread
   is interrupted. On timeout the engine interrupts the run and returns `flottila:TIMEOUT` with
   the trace so far (ADR-0014). The guard class sits in an exported package of `udf-api`, and the
   allow-list forbids authors calling it. The guard is always inserted, not only in test runs.

- A test run runs on a platform thread, never a virtual one: a spinning virtual thread holds
  its carrier (observed).
- A thread that does not stop after the interrupt (a long loop inside JDK code, such as a regex
  with a back-reference) is abandoned and logged. While more than two abandoned threads run,
  the service refuses new test runs with a diagnostic that says to restart it.
- `StackOverflowError` and `OutOfMemoryError` from a UDF are caught and reported as
  `flottila:UDF-ERROR`.
- The allow-list guards against mistakes and keeps UDFs pure enough to move into a host. It is not
  a defence against a hostile author: in milestone 1 the author already has write access to
  the workspace, and the service has no authentication (ADR-0014).

## Linking

The IR keeps a symbolic `FunctionRef` (ADR-0006). The facade `engine/api` turns the model's
`udfs` into `UdfDeclaration`s, has `engine/udf` compile and verify them, and hands the resulting
`MethodHandle`s to the runtime. The interpreter calls them through the adapter. How the
bytecode tier calls or inlines them is #17's.

## Corpus and export

- UDF sources of the golden corpus live in `corpus/udfs/<package path>/<Class>.java`. Mappings
  refer to them relatively (`../udfs/corpus/text/TextUdfs.java#appendAfterTrim`). The reactor
  does not compile them; the engine does, as in production.
- UDF cases are golden-corpus cases only (ADR-0018). Their provenance is `assumed` until an
  observation request confirms it (#2 §7.15, a shared box with state), otherwise `decided`
  (this ADR).
- The corpus has witnesses for both rules: a deterministic UDF folded in the optimised variant
  with a byte-identical output, and a stateful UDF whose output would change if calls were
  reordered or merged.
- An optional hand-written `<Class>.xsl` beside a source supplies XSLT bodies that override the
  export's `po:UDF-NOT-EXPORTED` stubs by import precedence (#15 §6). When it exists, the case
  also runs in the differential oracle. A UDF with `MappingRun` exports as a stub only.

## Considered options

- **PO's shape** (`String[]`, `ResultList`, `Container`): rejected. It brings back the flat list
  with sentinels and the SUPPRESS string that ADR-0004 rejects. The PO importer (fog) ports or
  adapts PO UDFs.
- **Plain JDK types** (`List<String>` in, `List<List<String>>` out): rejected. SUPPRESS needs a
  magic value.
- **State through a `ScopedValue`** (`MappingRun.current()`): rejected. It hides the dependency
  and is harder to test. `ScopedValue` (final in JDK 25) stays inside the engine.
- **No state in milestone 1**: rejected. A `counter`-like UDF and the shared-box observation
  request need it.
- **`udfs` written by hand**: rejected, because the editor could not add a UDF. **The declaration
  derived from the source only**: rejected. The model would not describe its boxes without a
  compile, against ADR-0010.
- **A precompiled jar, or a build step**: deferred to the fog with package references. The
  editor could not report errors at source positions. GraalVM native image has no javac, so UDFs
  will have to arrive compiled there.
- **Hidden classes** (`defineHiddenClass`): rejected for UDFs. A hidden class cannot be named, so
  a nested or helper class fails (`NoClassDefFoundError`). It must sit in the host's package, and
  with a lambda it did not unload (observed). Hidden classes stay the bytecode tier's tool
  (FOUNDING §5).
- **A module layer per library** (`defineModulesWithOneLoader`): rejected. It works and unloads,
  but it needs a `module-info` in every UDF and classes on disk or a custom finder, for no gain
  over a class loader.
- **Running test runs in a child JVM**: deferred to the fog, for a multi-user Flottila. Killing it
  is certain (4–6 ms) and starting it costs about 70 ms. But production runs in-process anyway,
  and the model and trace would need a second, serialised path.
- **A class loader only, without the guard**: rejected. `Thread.interrupt()` and `Thread.stop()`
  (which throws `UnsupportedOperationException`) do not stop `while (true) {}`, so a looping UDF
  would burn a service thread for good.
- **A deadline check in the guard** (`ThreadLocal` + `nanoTime`): rejected. It costs about 28 ns
  per iteration, where an interrupt check costs nothing measurable.

## Consequences

- ADR-0016 gains `engine/udf-api` and `engine/udf`. `runtime` requires `udf-api`, and `api`
  requires `udf`. ADR-0019's class-C floors cover both, so PIT runs on nine modules.
- ADR-0015 gains the scan endpoint and the trace member `messages`. GATE checks 3, 6 and 8
  change with it.
- New diagnostic codes in `UdfDiagnostics`:
  - `udf-source-not-found`;
  - `udf-compile-error`;
  - `udf-method-not-found`;
  - `udf-signature-invalid`: the method fits no kind;
  - `udf-declaration-mismatch`;
  - `udf-forbidden-api`;
  - `udf-mutable-static-state`;
  - a package reference uses the general package-reference code.

  New runtime errors: `flottila:UDF-ERROR` (and `flottila:TIMEOUT` reaches UDFs).
- Remaining risks: a regex with a back-reference, other long loops inside JDK code, and memory.
  A UDF that fills the heap can make another thread's allocation fail (one run in four, observed).
- Observed on 2026-10-30 in scratch reactors on the VM (JDK 25.0.5), not committed; numbers in
  `bench/RESULTS.md`:
  - all three kinds compile and run through `invokeExact`. A cold compile takes 0.6–0.8 s, a warm
    one about 60–70 ms, and a ten-method file 111–147 ms. A call costs about 17 ns (rough, no JMH);
  - in `@QuarkusTest` and in the packaged fast-jar of Quarkus 3.40.1.1, `jdk.compiler` compiles
    against `udf-api` found through `CodeSource`. The `java.class.path` approach fails in the
    fast-jar (`package … does not exist`). The cold compile takes 0.6 s and the warm one 55 ms
    (median), and the loader unloads under `RunnerClassLoader`;
  - `-Xlint:all -Werror` in-process flags raw types, unchecked casts, deprecation and the
    like, and the environment adds no warning. Without `-parameters` the names are `arg0`;
  - instrumentation takes 1.2–1.6 ms per class, and the interrupted loop stops in 0.6 ms. The
    allow-list walk takes 28–390 µs per class.
  - `ScopedValue` and the Class-File API are final in JDK 25. `StructuredTaskScope` is still
    preview and is not used.
