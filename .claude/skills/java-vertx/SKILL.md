---
name: java-vertx
description: Java 25 and Vert.x 5.1 rules for Rail8's platform code — virtual threads, Future.await, the event loop, records, sealed types, tests with a fake clock. Use when writing or reviewing Java under platform/ or mapping/.
---

## Look the API up

Vert.x documentation comes from Context7: library `/eclipse-vertx/vert.x` (core, versioned) or
`/websites/vertx_io` (all modules). When Context7 is unreachable or ambiguous, run
`javap -cp <jar>` on the resolved jar in `~/.m2`. The resolved jar decides.

## Threads

- One plain JDK virtual thread runs one message: `Thread.ofVirtual().name("rail8-…").start(…)`.
  Steps and stages run there and may block.
- Block on Vert.x results with `Future.await()` (Vert.x 5) on that virtual thread. It suspends
  the virtual thread, not a carrier. Await several futures with `Future.all(…).await()`.
- The event loop never blocks: no `await()`, no `Thread.sleep`, no JDBC, no file I/O, no
  `synchronized` around I/O in a handler, a verticle callback or a timer. Hand the work to a
  virtual thread and complete a `Promise` back.
- Verticles own sockets and timers and never run a pipeline. Blocking JNI or JCo calls go to the
  small platform-thread pool, never to a virtual thread or the event loop.
- Prefer `ReentrantLock` to `synchronized` where a lock is held across blocking calls.
- Tests fail on a blocked platform thread: `NoBlockedPlatformThreads` and
  `BlockedPlatformThreadDetector` in `platform/testkit`.

## Types

- Data is a `record`: immutable, validated in the compact constructor
  (`Objects.requireNonNull`, range checks), copied with `with…` methods that return a new record.
- A closed set of cases is a `sealed interface` with `record` cases (`Ack` is one), read with a
  `switch` over patterns and no `default`, so a new case breaks the build where it is unhandled.
- Collections in records are copied (`List.copyOf`, `Map.copyOf`). Sizes and offsets are `long`.
- An interface exists only where two implementations exist today (a store and its in-memory
  twin, a component and its mock).
- No static singletons: clock, timers and ids come in through the constructor.

## Errors

- A failed `Future` carries the cause; `await()` rethrows it. Never swallow an exception: rethrow,
  fail the future, or turn it into a typed result.
- Every resource is closed with try-with-resources; a body stream from `Body.open()` included.

## Tests

- JUnit 5 assertions; jqwik for property tests. A test asserts what the caller can observe.
- Time is driven by `FakeClock` and `FakeTimer` (`platform/testkit`): `clock.advance(Duration)`
  fires due timers. Never `Thread.sleep`, never a wall-clock timeout as the assertion.
- Ids come from `FakeIds`; a whole runtime from `FakeRuntime`.
- Mocks only at the Component SPI or a store boundary, never of the class under test. A component
  test runs the shared contract (`ComponentContract`, `BlobStoreContract`, …) against both the
  real component and its mock.
- Integration tests use Testcontainers (`PostgresImage` for the pinned Postgres) and run with
  `-Pit`. Run the modules you touched:
  `mvn -B -ntp -q -f platform/pom.xml verify -Pit -pl :<module> -am`, after `bin/install-jq` and
  `bin/install-mapping` once (the jq and mapping steps depend on the engines in `jq/` and `mapping/`).
