# Modules and their boundaries

Status: accepted (2026-11-10, owner, issue #14 "How is the repo laid out and built?"); amended by ADR-0018 (issue #12) and ADR-0020 (issue #15); amended by ADR-0030 (run state, #59); amended by F-84 (the build-tool module `pit-filter`, #192)

The tree is flat: a root `pom.xml` (parent and aggregator, `0.1.0-SNAPSHOT`, not published) and the
modules `query-core/`, `jq-core/`, `jq-cli/`, `oracle/` (tests only) and `bench/jmh/` (built only
with `-Pbench`). Since F-84 (#192) there is also `pit-filter/`, a build tool: a PIT mutation filter used only as a pitest-maven plugin dependency of `oracle`, never a dependency of another module and not published. Each artifactId is its directory name. The groupId and root package are
`io.github.subapavel.jqj`. This ADR binds module names and exported packages; the types named in
parentheses belong to ADR-0005, ADR-0008 and ADR-0009. Until the first publish a rename is one mechanical ticket; after it, a
rename is a breaking release for the Rail8 (R8) adapter.

- **`query-core`** is module `io.github.subapavel.jqj.query`. It has no `requires`:
  `java.lang.classfile`, `java.lang.foreign` and `java.lang.invoke` are in `java.base`.
  - It exports `.query.ir` (the sealed `Node` family and `Operation`), `.query.run` (`Sink`, the two
    exception classes, `Fn0`..`FnN`, `Many`) and `.query.compile` (the pass pipeline and the backend
    choice).
  - Everything else is under `.query.internal`. `tape` is the only internal package that reads tape
    entries, and it may assume only the `long[]` tape, an `int` cursor and the sign-bit sibling
    rule (ADR-0002).
  - Hidden classes are defined through a `query-core` Lookup, so generated code links only to
    `java.*` and `query-core` types. jq values reach it as `Object`, and operations as `Fn*` in class
    data (ADR-0009).
- **`jq-core`** is module `io.github.subapavel.jqj`. It requires `query-core` and `org.jruby.joni`
  2.2.9 (MIT, with its own descriptor and jcodings 1.0.64, MIT). It holds the resolver, the
  Operations, the prelude, the value model and the JSON reader and writer, and it exports only the
  packages of "What are the public API, the host SPI and the sandbox?". The requirement on
  `query-core` is `transitive` only if that API shows a `query-core` type.
- **Builtin families** register through a generated index, not `ServiceLoader`. In a named module
  only `provides` counts, which would need one central `module-info` list (CODING_STANDARDS).
- **`jq-cli`** is module `….jqj.cli` and requires `jq-core`. Its in-process entry is
  `int run(String[] args, InputStream in, OutputStream out, OutputStream err)`. `package` writes
  `target/lib/` and a launcher `target/jqj`. Linux is the only supported platform for milestone 1.
- **Where tests run:**
  - `oracle` and `bench/jmh` run on the class path. So do the unit tests of `query-core` and
    `jq-core` (`useModulePath=false`), which keeps white-box access, JaCoCo and PIT simple.
  - The shipped module path is covered by a fixed set of about ten cases in `jq-cli`: a prelude
    builtin, a regex, each call form of ADR-0009 and each backend.
  - A backend test checks that a generated class names only `java.*` and `query-core` types. The
    adviser observed on JDK 25.0.5 that a hidden class linking to another module's type runs on the
    class path but throws `IllegalAccessError` on the module path.
- **Rules and how they are enforced:**
  - JPMS and the reactor fix the direction; Maven 3.9.11 fails a cycle even through test scope.
  - The enforcer requires Java `[25,)` and Maven `[3.9.11,4)` and checks dependency convergence. It
    bans `org.openjdk.jmh` outside `bench`, JSON libraries in the product modules, and every
    non-test dependency in `query-core`.
  - The licence plugin checks CLAUDE.md's list, test scope included.
- **The word check** (`scripts/check-core-neutral.sh`) splits every tracked file under `query-core/`
  into words: camelCase, `_`, `.` and punctuation. It fails on `jq` or `json` in any case, and on
  `non-sealed` under `query/ir` (ADR-0005). The project name `jqj` is a different word and passes.
  This is founding §14's check; a tag's meaning cannot be grepped and relies on the single `tape`
  package and review.

## Considered options

- **A second, neutral root (`io.github.subapavel.query`):** a simpler substring ban, at the cost of
  two namespaces.
- **Splitting `jq-core` into reader and language modules, or separate backend modules:** nothing
  uses either part alone, and a later split is a package move.
- **ArchUnit:** JPMS already enforces direction, and the word rule must see comments and strings.
- **Every test on the module path:** `--add-opens` per package, for no gain.
- **A shaded CLI jar:** it would drop joni's module descriptor (UNVERIFIED).
- **One `.a` case per backend on the module path:** it misses the linkage failure above.
- **Supporting Windows:** it needs a Windows test job and a `jqj.cmd` launcher.

## Amendment (ADR-0018 and ADR-0020, issues #12 and #15)

- In milestone 1 the module-path cases run on the interpreter only. The test that a generated class
  names only `java.*` and `query-core` types moves to the bytecode spec.
- `jq-cli`'s in-process entry is `int run(Invocation)`, with `record Invocation(List<String> args,
  Map<String,String> env, Path cwd, InputStream in, OutputStream out, OutputStream err)`.
